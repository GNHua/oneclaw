# RFC-045: Bridge-App Session Synchronization

## Document Information
- **RFC ID**: RFC-045
- **Related PRD**: [FEAT-045 (Bridge Session Sync)](../../prd/features/FEAT-045-bridge-session-sync.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

After FEAT-041, the bridge routes all incoming messages to the app's most recently updated session. The bridge and the app therefore share one active session under normal use. However, when the user sends `/clear` via Telegram, `MessagingChannel.processInboundMessage()` calls `conversationMapper.createNewConversation()` and silently returns. The newly created session becomes the most recent session in the database, but the app's ChatScreen has no notification of this and continues to display the old session.

This RFC describes a minimal fix: a `SharedFlow`-based in-process event bus in `BridgeStateTracker` that emits the new session ID whenever the bridge creates one, with a `LaunchedEffect` in `ChatScreen` that subscribes and reinitializes the ViewModel.

### Goals

1. Emit a session-switch event from the bridge layer when `/clear` creates a new session.
2. Subscribe in `ChatScreen` and call `viewModel.initialize(sessionId)` upon receiving the event.
3. Keep the change minimal: no new modules, no new data classes, no DB schema changes.

### Non-Goals

- Any new persistence layer or inter-process communication.
- Notification when the app is not in the foreground (the existing Room Flow already handles list refresh; the session switch will apply on next foreground).
- Changes to navigation graph or back stack.
- Changes to `SessionListViewModel` (the drawer list updates automatically via Room Flow).

## Technical Design

### Changed Files Overview

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
└── BridgeStateTracker.kt                            # MODIFIED (add SharedFlow)
    channel/
    └── MessagingChannel.kt                          # MODIFIED (/clear branch emits event)
app/src/main/kotlin/com/oneclaw/shadow/
└── feature/chat/
    └── ChatScreen.kt                                # MODIFIED (LaunchedEffect subscriber)
```

## Detailed Design

### Change 1: BridgeStateTracker -- Add `newSessionFromBridge` SharedFlow

`BridgeStateTracker` is an `object` (singleton) that already holds observable state shared between the bridge service and the app UI. Adding a `SharedFlow` here is consistent with its existing role.

```kotlin
// BridgeStateTracker.kt -- additions only

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

object BridgeStateTracker {

    // ... existing fields and methods unchanged ...

    private val _newSessionFromBridge = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val newSessionFromBridge: SharedFlow<String> = _newSessionFromBridge.asSharedFlow()

    fun emitNewSessionFromBridge(sessionId: String) {
        _newSessionFromBridge.tryEmit(sessionId)
    }
}
```

Design decisions:
- `extraBufferCapacity = 1`: buffers one event so the emission does not drop if no collector is active at the exact moment of emission (e.g., ChatScreen is mid-composition).
- `tryEmit`: fire-and-forget; the bridge does not need a confirmation that the UI received the event.
- `SharedFlow` (not `StateFlow`): a session-switch is a one-time event, not a persistent state value. `StateFlow` would re-deliver the last session ID to every new collector, causing an unwanted navigation on each recomposition.

### Change 2: MessagingChannel -- Emit Event on `/clear`

In `processInboundMessage()`, the `/clear` branch already calls `conversationMapper.createNewConversation()`. Add one line after that call:

```kotlin
// MessagingChannel.kt -- /clear branch (step 4)

if (msg.text.trim() == "/clear") {
    val newConversationId = conversationMapper.createNewConversation()
    BridgeStateTracker.emitNewSessionFromBridge(newConversationId)   // <-- new line
    val clearMessage = BridgeMessage(
        content = "Conversation cleared. Starting a new conversation.",
        timestamp = System.currentTimeMillis()
    )
    runCatching { sendResponse(msg.externalChatId, clearMessage) }
    updateChannelState(newMessage = true)
    return
}
```

No other changes to `MessagingChannel`.

### Change 3: ChatScreen -- Subscribe and Reinitialize

Add a `LaunchedEffect` near the top of the `ChatScreen` composable, alongside the existing `LaunchedEffect` blocks, to collect from `BridgeStateTracker.newSessionFromBridge`:

```kotlin
// ChatScreen.kt -- inside ChatScreen composable

LaunchedEffect(Unit) {
    BridgeStateTracker.newSessionFromBridge.collect { sessionId ->
        viewModel.initialize(sessionId)
    }
}
```

Design decisions:
- `LaunchedEffect(Unit)`: launches once per composition lifetime, which is the correct scope for a persistent subscription.
- `viewModel.initialize(sessionId)`: this is the same method called when the user manually selects a session from the drawer. Reusing it ensures identical behavior: the ViewModel loads messages for the new session and updates `uiState.sessionId`.
- No navigation change is needed because `ChatScreen` is already the current screen.

## Testing

### Unit Tests

No new unit tests are strictly required for this change. The three modified files have the following existing coverage:

- `BridgeStateTracker`: no existing unit tests (it is a simple state holder); the new `SharedFlow` fields follow the same pattern as existing `StateFlow` fields.
- `MessagingChannel` (`MessagingChannelTest`): existing tests cover the `/clear` branch. Update the test to verify that `BridgeStateTracker.newSessionFromBridge` emits the new session ID after a `/clear` message.
- `ChatScreen`: covered by UI/Roborazzi tests; no change to visual layout, so no new screenshot baseline is needed.

### Manual Verification

1. Open the app to `ChatScreen`, confirm it shows the current active session.
2. Send `/clear` via Telegram.
3. Within 3 seconds, verify that `ChatScreen` switches to a new, empty session.
4. Send a normal message via Telegram. Verify it appears in the same new session in the app.
5. Manually switch to an older session in the app drawer. Send a message via Telegram. Verify it appears in the older session (unchanged FEAT-041 behavior).

## Migration Notes

- No database schema changes.
- No API changes to `BridgeConversationManager`, `SessionRepository`, or any repository interface.
- The `BridgeStateTracker` object gains two new public members (`newSessionFromBridge`, `emitNewSessionFromBridge`). Callers outside the bridge module (currently only `ChatScreen`) access these through the existing shared dependency on `BridgeStateTracker`.
