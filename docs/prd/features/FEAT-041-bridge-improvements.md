# Messaging Bridge Improvements

## Feature Information
- **Feature ID**: FEAT-041
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01 (Scenario 3 fully implemented via RFC-045)
- **Status**: Completed
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-041 (Bridge Improvements)](../../rfc/features/RFC-041-bridge-improvements.md)
- **Related Feature**: [FEAT-024 (Messaging Bridge)](FEAT-024-messaging-bridge.md)

## User Story

**As** a user of OneClawShadow's Messaging Bridge,
**I want** the bridge to work reliably with correct formatting, proper feedback, and seamless session integration,
**so that** my experience chatting with AI agents through Telegram and other platforms feels natural and polished.

### Typical Scenarios
1. User sends a message via Telegram. The AI response appears with clean formatting -- paragraphs separated by a single blank line, list items compact without extra spacing, blockquotes in native Telegram style.
2. User sends a message and immediately sees "typing..." indicator in Telegram while the agent processes, giving real-time feedback.
3. User switches to a different conversation session in the app, then sends a message via Telegram. The bridge message appears in the session they just switched to, not a separate bridge-only session.
4. User sends `/clear` via Telegram. A new session is created and becomes the active session for subsequent bridge messages.
5. The phone reboots. The bridge service auto-starts and resumes operation without user intervention.
6. User opens Bridge settings in the app and sees a clear, organized UI with collapsible channel sections, setup guides, and configuration options.

## Feature Description

### Overview
FEAT-041 consolidates improvements to the Messaging Bridge (FEAT-024) based on user testing feedback. The changes span three categories: formatting quality, interaction feedback, and session management. Additionally, several reliability and UX improvements from recent development are included.

### Improvements

#### 1. Telegram Message Formatting (Fix)
- **Problem**: AI responses in Telegram had excessive blank lines between paragraphs, headings, and list items due to regex-based HTML conversion.
- **Solution**: Rewrote `TelegramHtmlRenderer` from regex-based HTML conversion to AST visitor pattern, directly walking the commonmark parse tree. The smart `appendBlockSeparator` method only adds newlines between sibling blocks, eliminating excess whitespace.
- **Before**: Multiple blank lines between every paragraph, heading, and list item.
- **After**: Single blank line between top-level blocks, compact list items, native `<blockquote>` tags.

#### 2. Typing Indicator Timing (Fix)
- **Problem**: The typing indicator only appeared AFTER the agent finished processing, because `agentExecutor.executeMessage()` blocked before the typing coroutine launched.
- **Solution**: Reordered `processInboundMessage()` so the typing indicator coroutine launches BEFORE agent execution. Agent execution is wrapped in `scope.launch {}` for concurrent operation.

#### 3. Active Session Integration (Enhancement)
- **Problem**: Bridge messages went to a dedicated bridge-only session via `preferences.getBridgeConversationId()`, disconnected from the app's UI.
- **Solution**: Bridge now routes messages to whichever session is currently active in the app. This means:
  - Messages go to whatever session the user is currently viewing in ChatScreen.
  - `/clear` creates a new session; ChatScreen switches to it and it becomes the new active target.
  - Switching sessions in the app immediately changes where bridge messages go.
- The `ConversationMapper` no longer depends on `BridgePreferences` for conversation ID storage.
- *Note*: The initial RFC-041 implementation tracked the active session via `updated_at DESC` in the database, which did not follow manual app-side session switches. This was corrected in RFC-045 using an in-memory `BridgeStateTracker.activeAppSessionId` updated by `ChatViewModel` on every session switch.

#### 4. Plain Text Fallback (Robustness)
- **Problem**: If HTML rendering failed for any reason, the entire message send would fail silently.
- **Solution**: `TelegramChannel.sendResponse()` now wraps HTML rendering in try/catch. On failure, falls back to sending plain text without `parse_mode`.

#### 5. Boot Auto-Start (Previously Implemented)
- `BridgeBootReceiver` listens for `BOOT_COMPLETED` and auto-starts the bridge service if it was enabled before the device shut down.

#### 6. Foreground Notification Persistence (Previously Implemented)
- Fixed `foregroundServiceType` from `specialUse` to `dataSync` for correct system behavior.
- `ACTION_RESTART` intent ensures the foreground notification persists when channels are reloaded.

#### 7. Duplicate Message Prevention (Previously Implemented)
- 500-message LRU deduplication cache prevents processing the same message twice, critical for long-polling channels.

#### 8. Bridge Settings UI (Previously Implemented)
- Collapsible channel sections with enable/disable toggles.
- Setup guides for each channel with step-by-step instructions.
- Wake lock toggle with battery impact warning.
- Access control whitelist configuration per channel.

### Acceptance Criteria

1. Telegram responses have compact formatting with single blank lines between paragraphs (no excessive whitespace).
2. Typing indicator shows immediately in Telegram when the user sends a message.
3. Bridge messages appear in the app's most recently used session, not a separate bridge session.
4. `/clear` creates a new session that becomes the active target for subsequent bridge messages.
5. HTML rendering failures fall back to plain text without breaking message delivery.
6. Bridge service auto-starts on device boot when previously enabled.
7. All existing unit tests pass; new tests cover the formatting changes.

### Out of Scope
- Rich media responses (images, files) from the agent back to messaging platforms.
- Multi-session per-channel mapping (each external chat maps to a different session).
- End-to-end encryption for bridge messages.
