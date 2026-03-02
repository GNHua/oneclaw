# RFC-046: Bridge Response Delivery Fix

## Document Information
- **RFC ID**: RFC-046
- **Related PRD**: [FEAT-046 (Bridge Response Delivery Fix)](../../prd/features/FEAT-046-bridge-response-delivery-fix.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

After FEAT-041 improved the Messaging Bridge with typing indicators, session routing, and HTML formatting, a critical delivery bug was discovered: when the AI agent processes a request involving tool calls (e.g., loading Google Drive tool groups, listing files), Telegram receives only the first intermediate response ("I'll load the tool group...") instead of the final answer (the actual file listing).

The root cause is an architectural indirection in the bridge's response delivery pipeline. `BridgeAgentExecutorImpl` discards all `ChatEvent` emissions from `SendMessageUseCase` by calling `.collect()` without processing events. After execution, `MessagingChannel` must re-discover the response by polling the database via `BridgeMessageObserverImpl`, which uses `maxByOrNull { it.createdAt }` to find the latest `AI_RESPONSE`. This indirect approach fails to reliably return the correct final response.

### Goals

1. Deliver the agent's final response directly from the execution flow, eliminating the DB-polling indirection
2. Keep `BridgeMessageObserver` as a fallback for edge cases
3. Simplify `processInboundMessage()` by removing the `scope.launch`/`join` pattern
4. Ensure post-execution operations (e.g., AI title generation) cannot crash the response delivery pipeline
5. Add diagnostic logging for future debugging

### Non-Goals

- Sending intermediate planning messages to Telegram (they add noise, not value)
- Changes to `SendMessageUseCase` or the tool-call streaming loop
- Database schema changes
- New channel implementations

## Technical Design

### Changed Files Overview

```
bridge/src/main/kotlin/com/oneclaw/shadow/bridge/
├── BridgeAgentExecutor.kt                          # MODIFIED (return type)
└── channel/
    └── MessagingChannel.kt                         # MODIFIED (use direct response)
app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/
└── BridgeAgentExecutorImpl.kt                      # MODIFIED (capture response)
bridge/src/test/kotlin/com/oneclaw/shadow/bridge/
└── channel/
    └── MessagingChannelTest.kt                     # MODIFIED (updated mocks)
```

## Detailed Design

### Change 1: BridgeAgentExecutor Return Type

**File**: `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/BridgeAgentExecutor.kt`

**Current code**:
```kotlin
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}
```

**New code**:
```kotlin
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    ): BridgeMessage?
}
```

**Rationale**: The return type changes from `Unit` to `BridgeMessage?`. Returning `null` signals that the executor could not determine the final response (e.g., the flow emitted only errors), allowing the caller to fall back to the DB observer.

---

### Change 2: BridgeAgentExecutorImpl Captures Final Response

**File**: `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt`

**Current code**:
```kotlin
override suspend fun executeMessage(
    conversationId: String,
    userMessage: String,
    imagePaths: List<String>
) {
    val agentId = resolveAgentId()
    sendMessageUseCase.execute(
        sessionId = conversationId,
        userText = userMessage,
        agentId = agentId
    ).collect()
}
```

**New code**:
```kotlin
override suspend fun executeMessage(
    conversationId: String,
    userMessage: String,
    imagePaths: List<String>
): BridgeMessage? {
    // Phase 1 title: immediate truncated title from user message (FEAT-045)
    val isFirstMessage = (sessionRepository.getSessionById(conversationId)?.messageCount ?: 0) == 0
    if (isFirstMessage) {
        val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(userMessage)
        sessionRepository.updateTitle(conversationId, truncatedTitle)
    }

    val agentId = resolveAgentId()
    val pendingAttachments = imagePaths.mapNotNull { path -> /* ... image handling ... */ }
    var lastResponseContent: String? = null
    var lastResponseTimestamp: Long = 0L
    var lastModelId: String? = null
    var lastProviderId: String? = null

    try {
        sendMessageUseCase.execute(
            sessionId = conversationId,
            userText = userMessage,
            agentId = agentId,
            pendingAttachments = pendingAttachments
        ).collect { event ->
            when (event) {
                is ChatEvent.ResponseComplete -> {
                    lastResponseContent = event.message.content
                    lastResponseTimestamp = event.message.createdAt
                    lastModelId = event.message.modelId
                    lastProviderId = event.message.providerId
                }
                else -> { /* other events not needed by bridge */ }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        // Agent execution failed; return null so caller can fall back
        return null
    }

    val content = lastResponseContent

    // Phase 2 title: AI-generated title after first response (non-fatal)
    if (isFirstMessage && content != null && lastModelId != null && lastProviderId != null) {
        try {
            generateTitleUseCase.generateAiTitle(
                sessionId = conversationId,
                firstUserMessage = userMessage,
                firstAiResponse = content,
                currentModelId = lastModelId!!,
                currentProviderId = lastProviderId!!
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "AI title generation failed (non-fatal)", e)
        }
    }

    return if (content != null && content.isNotBlank()) {
        BridgeMessage(content = content, timestamp = lastResponseTimestamp)
    } else {
        null
    }
}
```

**Rationale**:

- `ChatEvent.ResponseComplete` is emitted by `SendMessageUseCase` exactly once, at the end of the tool-call loop, when no more tool calls remain. It contains the **final** AI response message -- the one the user actually wants.
- If the flow completes without emitting `ResponseComplete` (e.g., all rounds produced tool calls and the max-round limit was hit, or an error occurred), `lastResponseContent` remains null, and the method returns `null`.
- `CancellationException` is re-thrown per Kotlin coroutine convention. All other exceptions return `null` so the caller can fall back gracefully.
- Phase 2 title generation (`generateAiTitle()`) is wrapped in its own try/catch block. This is critical: without this wrapper, a title generation API failure would propagate as an unhandled exception, crashing the entire `processInboundMessage()` pipeline and preventing the response from being delivered to Telegram. See Change 4 for details.

**Behavior after fix**:

| Scenario | `lastResponseContent` | Return value |
|----------|----------------------|--------------|
| Single-round (no tool calls) | Final AI text | `BridgeMessage(content, timestamp)` |
| Multi-round (tool calls) | Final AI text after all tools complete | `BridgeMessage(content, timestamp)` |
| Agent error during execution | null | `null` |
| Max rounds exceeded | null (no `ResponseComplete` emitted) | `null` |

---

### Change 3: MessagingChannel Uses Direct Response

**File**: `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannel.kt`

**Current code** (lines 84-118):
```kotlin
// 7. Execute agent concurrently (SendMessageUseCase inserts the user message internally)
val beforeTimestamp = System.currentTimeMillis()
val agentJob = scope.launch {
    agentExecutor.executeMessage(
        conversationId = conversationId,
        userMessage = msg.text,
        imagePaths = msg.imagePaths
    )
}

// 8. Wait for agent to finish, then fetch the final response
val response = try {
    withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
        agentJob.join()
        messageObserver.awaitNextAssistantMessage(
            conversationId = conversationId,
            afterTimestamp = beforeTimestamp,
            timeoutMs = 10_000
        )
    }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    BridgeMessage(
        content = "Sorry, the agent did not respond in time. Please try again.",
        timestamp = System.currentTimeMillis()
    )
} finally {
    // 9. Cancel typing
    typingJob.cancel()
}

// Send response
runCatching { sendResponse(msg.externalChatId, response) }
```

**New code**:
```kotlin
// 7. Execute agent and get direct response
val beforeTimestamp = System.currentTimeMillis()
Log.d(TAG, "Executing agent for conv=$conversationId, text=${msg.text.take(50)}")
val response = try {
    withTimeout(AGENT_RESPONSE_TIMEOUT_MS) {
        // executeMessage now returns the final response directly
        val directResponse = agentExecutor.executeMessage(
            conversationId = conversationId,
            userMessage = msg.text,
            imagePaths = msg.imagePaths
        )
        Log.d(TAG, "executeMessage returned: ${if (directResponse != null) "content(${directResponse.content.length} chars)" else "null"}")
        // Use direct response; fall back to DB observer if null
        if (directResponse != null) {
            directResponse
        } else {
            Log.d(TAG, "Falling back to DB observer")
            messageObserver.awaitNextAssistantMessage(
                conversationId = conversationId,
                afterTimestamp = beforeTimestamp,
                timeoutMs = 10_000
            )
        }
    }
} catch (e: kotlinx.coroutines.TimeoutCancellationException) {
    Log.w(TAG, "Agent timed out after ${AGENT_RESPONSE_TIMEOUT_MS}ms")
    BridgeMessage(
        content = "Sorry, the agent did not respond in time. Please try again.",
        timestamp = System.currentTimeMillis()
    )
} finally {
    // 8. Cancel typing
    typingJob.cancel()
}

// Send response
Log.d(TAG, "Sending response to ${msg.externalChatId}: ${response.content.length} chars")
val sendResult = runCatching { sendResponse(msg.externalChatId, response) }
if (sendResult.isFailure) {
    Log.e(TAG, "sendResponse FAILED", sendResult.exceptionOrNull())
}
```

**Rationale**:

1. **No more `scope.launch`/`join` pattern**: `agentExecutor.executeMessage()` is called directly as a `suspend` function. Since `processInboundMessage()` itself runs inside `scope.launch {}` (called from the polling loop), the typing indicator coroutine continues to run concurrently -- no change needed there.

2. **Direct response preferred**: The returned `BridgeMessage` from `executeMessage()` is used directly. This is the `ResponseComplete` event's message content -- guaranteed to be the final response.

3. **DB observer as fallback**: If `executeMessage()` returns `null` (agent error, max rounds exceeded), the observer polls the database as before. This preserves backward compatibility and handles edge cases.

4. **Timeout unchanged**: The `withTimeout(AGENT_RESPONSE_TIMEOUT_MS)` (300 seconds) wraps both the direct call and the fallback. If the agent takes too long, the timeout message is returned.

**Concurrency model comparison**:

```
Before:                                    After:
  typingJob = scope.launch { ... }           typingJob = scope.launch { ... }
  agentJob = scope.launch { execute() }      response = execute()  // suspend, typing runs
  agentJob.join()                            // direct return, no join needed
  response = observerPoll()                  // fallback only if null
  typingJob.cancel()                         typingJob.cancel()
```

Both models achieve the same concurrency: the typing coroutine runs in parallel with agent execution. The difference is that the new model directly captures the result instead of discarding it and re-fetching from the database.

---

### Change 4: Exception Safety for Post-Execution Code

**File**: `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt`

**Problem discovered during testing**: FEAT-045 added `generateAiTitle()` to `BridgeAgentExecutorImpl.executeMessage()` after the flow collection. This call was placed **outside** the try/catch block that wraps `sendMessageUseCase.execute().collect()`. When the AI title generation API call failed (e.g., network error, API rate limit), the exception propagated unhandled through `executeMessage()` -> `withTimeout` -> `processInboundMessage()` -> `scope.launch` (where `SupervisorJob` silently swallowed it). The result: `sendResponse()` was never reached, and the user received nothing on Telegram despite the agent having produced a valid response.

**Fix**: Wrap `generateAiTitle()` in its own try/catch block with `Log.w()` for non-fatal logging. See the code in Change 2 above.

**Design principle**: Post-execution operations (title generation, analytics, etc.) must never prevent response delivery. They should be treated as best-effort and wrapped in independent error boundaries.

---

### Change 5: Diagnostic Logging

**Files**: `MessagingChannel.kt`, `BridgeAgentExecutorImpl.kt`

The bridge code previously had zero logging, making it impossible to diagnose delivery failures from logcat. The following log points were added:

| Location | Level | Content |
|----------|-------|---------|
| `MessagingChannel` before agent execution | `Log.d` | Conversation ID, message text preview |
| `MessagingChannel` after `executeMessage()` returns | `Log.d` | Whether response is non-null, content length |
| `MessagingChannel` on fallback to observer | `Log.d` | Fallback triggered |
| `MessagingChannel` before `sendResponse()` | `Log.d` | Target chat ID, response length |
| `MessagingChannel` on `sendResponse()` failure | `Log.e` | Exception details |
| `MessagingChannel` on timeout | `Log.w` | Timeout duration |
| `BridgeAgentExecutorImpl` on `generateAiTitle()` failure | `Log.w` | Exception details (non-fatal) |

---

### No Change: BridgeMessageObserver

`BridgeMessageObserverImpl` is **not modified**. It remains available as a fallback when `executeMessage()` returns `null`. This is a deliberate safety net -- if future changes introduce new code paths where `ResponseComplete` is not emitted, the observer ensures a response is still delivered.

## Testing

### Unit Tests

**MessagingChannelTest** -- update:
- `processInboundMessage sends agent response to user`: Update `agentExecutor.executeMessage()` mock to return `BridgeMessage("Agent response", ...)` instead of `Unit`. Verify the returned response is sent directly (not via observer).
- `processInboundMessage falls back to observer when executor returns null`: New test. Mock `executeMessage()` to return `null`. Verify `messageObserver.awaitNextAssistantMessage()` is called and its result is sent.
- `processInboundMessage handles agent exception gracefully`: New test. Mock `executeMessage()` to throw. Verify fallback to observer.

**BridgeAgentExecutorImplTest** -- new file (optional, may defer):
- `executeMessage returns final response from ResponseComplete event`
- `executeMessage returns null when flow emits no ResponseComplete`
- `executeMessage returns null when flow throws exception`

### Manual Verification

1. Send a message via Telegram that triggers tool calls (e.g., "list my Google Drive root directory"). Verify the final file listing is delivered, not the intermediate planning message.
2. Send a simple question via Telegram (no tool calls). Verify the response is delivered normally.
3. Send a message that causes an agent error (e.g., invalid API key). Verify an error message or fallback response is delivered.

## Migration Notes

- `BridgeAgentExecutor.executeMessage()` return type changes from `Unit` to `BridgeMessage?`. All implementations must be updated.
- `MessagingChannel.processInboundMessage()` no longer uses `scope.launch`/`join` for agent execution. The agent runs directly in the calling coroutine.
- No database schema changes.
- No DI module changes (no new dependencies).

## Open Questions

- [ ] Should intermediate planning messages (e.g., "I'll load the tools...") also be sent to Telegram as separate messages? Current decision: no, they add noise. Only the final response matters.

## Performance Considerations

- Slight improvement: eliminates one DB query (the observer poll) in the happy path. The observer query only runs as a fallback when the direct response is null.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial draft | - |
| 2026-03-01 | 1.1 | Added Change 4 (generateAiTitle exception safety), Change 5 (diagnostic logging), updated code snippets to reflect FEAT-045 integration | - |
