# Bridge Response Delivery Fix

## Feature Information
- **Feature ID**: FEAT-046
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-046 (Bridge Response Delivery Fix)](../../rfc/features/RFC-046-bridge-response-delivery-fix.md)
- **Related Features**: [FEAT-041 (Bridge Improvements)](FEAT-041-bridge-improvements.md), [FEAT-024 (Messaging Bridge)](FEAT-024-messaging-bridge.md)

## User Story

**As** a user chatting with the AI agent through Telegram,
**I want** the agent's final response to be reliably delivered to me,
**so that** I receive the actual answer (e.g., a file listing) instead of only seeing the agent's planning message (e.g., "I'll load the tools...").

### Typical Scenarios

1. User sends "list my Google Drive root directory" via Telegram. The agent responds with "I'll load the Google Drive tool group and then list your root directory", loads the tools, lists files, and produces a final response with the directory contents. **Currently**: Telegram only receives the first planning message. The final file listing is never delivered. **Expected**: Telegram receives the final file listing.
2. User asks the agent to perform any task that requires tool calls (web search, file operations, MCP tools). The agent goes through multiple rounds of tool execution. **Currently**: Telegram may receive an intermediate message instead of the final answer. **Expected**: Telegram receives the final answer after all tool rounds complete.
3. User asks a simple question with no tool calls. **Currently**: Works correctly. **Expected**: Continues to work correctly (no regression).

## Feature Description

### Overview

When the AI agent processes a request that involves tool calls (e.g., loading tool groups, calling MCP tools, web search), it goes through multiple rounds. Each round produces an `AI_RESPONSE` message saved to the database. The Messaging Bridge is supposed to deliver the final response to Telegram, but it currently relies on an indirect mechanism -- polling the database after agent completion -- which fails to reliably return the correct (final) response.

### Root Cause Summary

| Bug | Location | Impact |
|-----|----------|--------|
| Agent execution result discarded | `BridgeAgentExecutorImpl.kt` | `.collect()` discards all `ChatEvent` including `ResponseComplete` |
| Indirect response retrieval | `MessagingChannel.kt` | DB polling via `BridgeMessageObserver` may return wrong `AI_RESPONSE` |
| Unnecessary indirection | Architecture | Agent result is thrown away, then re-fetched from DB via polling |
| Post-execution exception crashes pipeline | `BridgeAgentExecutorImpl.kt` | `generateAiTitle()` called outside try/catch; its failure propagates unhandled, preventing response delivery |

### Detailed Problem

The current message delivery flow has an architectural flaw:

1. `BridgeAgentExecutorImpl.executeMessage()` calls `sendMessageUseCase.execute().collect()`. The `.collect()` consumes all `ChatEvent` emissions but **discards them** -- including `ChatEvent.ResponseComplete` which contains the final AI response.

2. After the agent finishes, `MessagingChannel.processInboundMessage()` calls `BridgeMessageObserverImpl.awaitNextAssistantMessage()`, which polls the database looking for the latest `AI_RESPONSE` using `maxByOrNull { it.createdAt }`.

3. This indirect DB polling approach is fragile. If any issue arises with timestamp ordering, database read visibility, or exception handling during tool call rounds, the observer may return the wrong message (e.g., the first intermediate planning message instead of the final response).

### Fix Description

1. **Direct response propagation**: Replace the indirect DB-polling approach by having `BridgeAgentExecutorImpl` capture the final `ChatEvent.ResponseComplete` from the flow and return it directly to `MessagingChannel`. The DB observer is kept as a fallback.
2. **Exception safety**: Wrap post-execution code (`generateAiTitle()`) in try/catch so that non-critical failures (e.g., AI title generation API errors) cannot crash the response delivery pipeline.
3. **Diagnostic logging**: Add structured logging at key points in `MessagingChannel` and `BridgeAgentExecutorImpl` for future debugging.

## Acceptance Criteria

- [ ] When the agent uses tool calls (multi-round), Telegram receives the final response (not an intermediate planning message)
- [ ] When the agent responds without tool calls (single-round), Telegram receives the response (no regression)
- [ ] When the agent fails during execution, Telegram receives a meaningful error message
- [ ] When the agent times out, Telegram receives the existing timeout message
- [ ] `./gradlew test` passes (all existing + new tests)
- [ ] `./gradlew compileDebugKotlin` passes

## Feature Boundary

### Included
- Change `BridgeAgentExecutor.executeMessage()` return type from `Unit` to `BridgeMessage?`
- Update `BridgeAgentExecutorImpl` to capture final response from `ChatEvent` flow
- Wrap `generateAiTitle()` in try/catch to prevent post-execution exceptions from crashing the delivery pipeline
- Simplify `MessagingChannel.processInboundMessage()` to use returned response directly
- Keep `BridgeMessageObserver` as fallback when direct response is null
- Add diagnostic logging to `MessagingChannel` and `BridgeAgentExecutorImpl`
- Update existing tests

### Not Included
- Sending intermediate messages to Telegram (planning messages are not useful to the user)
- Changes to `SendMessageUseCase` or the streaming/tool-call loop
- Changes to `TelegramHtmlRenderer` or message formatting
- New channel implementations
- Database schema changes

## Dependencies

### Depends On
- **FEAT-041 (Bridge Improvements)**: Current bridge architecture with typing indicator, session routing
- **FEAT-024 (Messaging Bridge)**: Base bridge implementation

### Depended On By
- None

## Files Changed

| File | Change Type |
|------|------------|
| `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/BridgeAgentExecutor.kt` | Modified |
| `app/src/main/kotlin/com/oneclaw/shadow/feature/bridge/BridgeAgentExecutorImpl.kt` | Modified |
| `bridge/src/main/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannel.kt` | Modified |
| `bridge/src/test/kotlin/com/oneclaw/shadow/bridge/channel/MessagingChannelTest.kt` | Modified |

## Error Handling

- If `agentExecutor.executeMessage()` throws an exception, `processInboundMessage()` catches it and falls back to the DB observer.
- If both the direct response and DB observer fail, a user-facing error message is sent to Telegram.
- `BridgeAgentExecutorImpl` wraps the entire flow collection in try/catch so exceptions in `SendMessageUseCase` never propagate unhandled.
- Post-execution operations (`generateAiTitle()`) are wrapped in their own try/catch block, logged as non-fatal warnings, and cannot prevent response delivery.

## Test Points

- Agent with tool calls (multi-round): final response is returned and delivered
- Agent without tool calls (single-round): response is returned and delivered
- Agent execution failure: null returned, fallback to DB observer
- Agent execution timeout: timeout message delivered
- Existing deduplication, whitelist, and /clear tests remain passing

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial draft | - |
| 2026-03-01 | 1.1 | Added 4th root cause (generateAiTitle exception), diagnostic logging, updated fix description | - |
