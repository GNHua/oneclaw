# Auto Compact & Tool Result Truncation

## Feature Information
- **Feature ID**: FEAT-011
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: TBD
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)

## User Story

**As** a user of OneClawShadow,
**I want** the app to automatically manage conversation context length so that long conversations never fail due to exceeding the model's context window,
**so that** I can have extended conversations without worrying about technical limits or losing important context.

### Typical Scenarios
1. User has a long conversation (50+ messages) with an AI model. The accumulated token count approaches the model's context window limit. The app automatically compresses older messages into a summary before the next request, and the conversation continues seamlessly.
2. User asks the AI to fetch a large web page using the HTTP request tool. The tool result (hundreds of KB) is automatically truncated to a reasonable size before being stored and sent to the model.
3. User switches from a model with a 200K context window to a model with an 8K context window mid-session. The app detects the next message would exceed the new model's limit and triggers a compact before sending.

## Feature Description

### Overview
Auto Compact is a context window management system that prevents conversations from exceeding a model's context window limit. When the accumulated input tokens approach a configurable threshold (default: 85% of the model's context window), the app automatically summarizes older messages using the current model and replaces them with a compact summary for future API requests. The original messages are preserved in the database untouched.

Additionally, tool call results that are excessively large (e.g., fetching a web page) are truncated at the point of storage, preventing database bloat and ensuring tool results don't consume disproportionate context space.

### Detailed Description

#### Auto Compact

**Trigger**: After each API response completes (including all tool call rounds), the system checks whether the total token count of the conversation history exceeds 85% of the current model's context window size.

**Process**:
1. Calculate total tokens for all messages in the session
2. If total exceeds the threshold, determine which messages to compact:
   - Starting from the most recent message, walk backwards accumulating token counts
   - Messages whose cumulative tokens fit within 25% of the context window are "protected" (kept as-is)
   - All older messages are candidates for compaction
3. Send a summarization request to the current model with the candidate messages (plus any existing prior summary)
4. Store the resulting summary in the Session's `compactedSummary` field
5. Record which messages have been compacted (by storing the timestamp or message ID of the compaction boundary)

**Subsequent requests**: When building the `messages` array for API calls, the system prepends the `compactedSummary` as a system-level context message, followed by only the non-compacted (recent) messages.

**Multiple compactions**: If a session undergoes multiple compact cycles, each new compact incorporates the previous `compactedSummary` plus newly compacted messages into a single merged summary. The Session always holds exactly one cumulative summary.

#### Tool Result Truncation

**Trigger**: Immediately before storing a tool result message in the database.

**Process**:
1. Check the character length of the tool result content
2. If it exceeds the truncation limit (default: 30,000 characters), truncate from the tail
3. Append a truncation marker: `\n\n[... content truncated, showing first {kept} characters of {total} total ...]`
4. Store the truncated version in the database

The truncation happens once at storage time. All downstream consumers (API requests, UI display) work with the already-truncated version.

### User Interaction Flow

#### Auto Compact (mostly invisible)
```
1. User sends a message and receives a response
2. System checks token count against threshold
3. If threshold exceeded:
   a. System sends a background summarization request
   b. A brief indicator appears (e.g., "Optimizing conversation context...")
   c. Summary is stored on the Session
   d. Indicator disappears
4. User continues chatting normally
5. If compact fails after retry, system falls back to truncation
   and shows a Snackbar: "Conversation history has been automatically trimmed"
```

#### Tool Result Truncation (invisible)
```
1. AI invokes a tool (e.g., HTTP request to fetch a web page)
2. Tool executes and returns a large result
3. System truncates the result before storing
4. Truncated result is displayed in the tool call message
5. Truncated result is sent back to the model
```

## Acceptance Criteria

Must pass (all required):
- [ ] AiModel entity includes a `contextWindowSize` field (Int, nullable)
- [ ] Pre-seeded models have correct `contextWindowSize` values populated
- [ ] When total session tokens exceed 85% of the model's context window, auto compact triggers
- [ ] Compact produces a summary by calling the current model
- [ ] Summary is stored in `Session.compactedSummary` field
- [ ] Original messages are never modified or deleted by compact
- [ ] Subsequent API requests include the compact summary + only recent (non-compacted) messages
- [ ] Multiple compactions in the same session merge into a single cumulative summary
- [ ] A brief UI indicator appears during compact
- [ ] If the summarization API call fails, it retries once silently
- [ ] If retry also fails, system falls back to truncation (drop oldest messages) and shows a Snackbar
- [ ] Tool results exceeding 30,000 characters are truncated before storage
- [ ] Truncated tool results include a marker indicating truncation occurred
- [ ] The 85% threshold is defined as a constant (not hardcoded inline)
- [ ] The 30,000 character truncation limit is defined as a constant

Optional (nice to have):
- [ ] User can manually trigger compact from the UI
- [ ] Token count display in the chat screen showing current usage vs. context window
- [ ] Configurable threshold percentage in settings

## UI/UX Requirements

### Auto Compact Indicator
- During compact: a subtle, non-blocking indicator (e.g., a small progress message below the latest AI response or a brief Snackbar-style banner)
- Text: "Optimizing conversation context..." (or localized equivalent)
- Duration: visible only during the summarization API call
- The indicator must NOT block user input -- the user can still type while compact runs

### Compact Fallback Notification
- When compact fails and falls back to truncation: a Snackbar notification
- Text: "Conversation history has been automatically trimmed"
- Duration: standard Snackbar duration (short)

### Tool Result Truncation
- No UI indication needed -- truncation is invisible to the user
- The truncation marker text is displayed as part of the tool result if the user expands the tool call detail view

## Feature Boundary

### Included
- Automatic context compaction triggered by token threshold
- Token-count-based protected window (most recent 25% of context)
- Summary generation using current conversation model
- Cumulative summary storage on Session entity
- Fallback to message truncation on summary failure
- Tool result truncation at storage time
- Context window size field on AiModel entity

### Not Included
- User-configurable compact threshold in settings (future)
- Manual compact trigger button (future)
- Client-side token counting / tokenizer (we rely on API-reported token counts)
- Compression of individual messages (only whole-message granularity)
- Tool result streaming or pagination
- Context window size auto-detection from API (we use pre-configured values)

## Business Rules

### Compact Rules
1. Compact triggers only after a complete API response (including all tool call rounds), never mid-stream
2. The 85% threshold is calculated against the current model's `contextWindowSize`
3. If `contextWindowSize` is null (unknown), compact does not trigger (no-op)
4. The protected window (recent messages) targets 25% of the context window by token count
5. The summarization request uses the same model, provider, and API key as the current conversation
6. The summary prompt is a fixed system prompt instructing the model to produce a concise factual summary
7. Compact is idempotent -- triggering it when already under threshold is a no-op

### Token Counting Rules
1. Token counts come from API-reported `Usage` events stored on each message (`tokenCountInput`, `tokenCountOutput`)
2. For messages without token counts (e.g., user messages before they've been sent), use a character-based estimate (1 token per 4 characters)
3. The total token count for threshold comparison is the sum of estimated input tokens for all messages that would be sent in the next API request

### Tool Result Truncation Rules
1. Truncation limit: 30,000 characters per tool result
2. Truncation preserves the beginning of the content (head), discards the tail
3. A truncation marker is appended to indicate content was truncated
4. Truncation happens before database insertion -- the full content is never stored
5. Truncation applies to all tool types equally

### Failure Handling Rules
1. If summarization fails, retry exactly once (same request)
2. If retry fails, fall back to truncation: remove the oldest messages from the API request (but NOT from the database) until under the 85% threshold
3. Show a Snackbar notification on fallback
4. Log the failure for debugging

## Non-Functional Requirements

### Performance
- Compact should complete within 15 seconds (summarization API call)
- Compact must not block the UI thread or prevent user input
- Tool result truncation is a synchronous string operation and must complete in < 10ms
- Token count checking after each response should add < 5ms overhead

### Reliability
- Compact failure must never prevent the user from continuing the conversation
- Tool result truncation must never throw an exception
- The system must handle edge cases: empty sessions, sessions with only user messages, sessions where all messages are within the protected window

### Data Integrity
- Original messages are never modified or deleted by compact
- The `compactedSummary` field can be cleared if the user wants to "reset" context (future feature)
- Database size is bounded by tool result truncation

## Dependencies

### Depends On
- **FEAT-001 (Chat Interaction)**: Compact integrates into the chat message flow
- **FEAT-003 (Model/Provider Management)**: Needs model context window size; uses the provider's API for summarization
- **FEAT-005 (Session Management)**: Stores compact summary on Session entity

### Depended On By
- **FEAT-006 (Token/Cost Tracking)**: Token counting infrastructure supports both features

## Data Requirements

### New/Modified Data Fields

| Entity | Field | Type | Required | Description |
|--------|-------|------|----------|-------------|
| AiModel | contextWindowSize | Int? | No | Model's maximum context window in tokens. Null if unknown. |
| Session | compactedSummary | String? | No | Cumulative summary of compacted messages. Null if no compaction has occurred. |
| Session | compactBoundaryTimestamp | Long? | No | Timestamp of the oldest non-compacted message. Messages older than this are covered by the summary. |

### Pre-seeded Model Context Window Sizes

| Model | Context Window |
|-------|---------------|
| claude-sonnet-4-20250514 | 200,000 |
| claude-haiku-3-5-20241022 | 200,000 |
| claude-opus-4-20250514 | 200,000 |
| gpt-4o | 128,000 |
| gpt-4o-mini | 128,000 |
| gpt-4-turbo | 128,000 |
| gemini-2.0-flash | 1,048,576 |
| gemini-2.5-pro-preview-05-06 | 1,048,576 |

## Error Handling

### Error Scenarios

1. **Summarization API call fails (network error)**
   - Action: Retry once silently
   - If retry fails: fall back to truncation, show Snackbar
   - Conversation continues normally

2. **Summarization API returns empty or malformed response**
   - Action: Treat as failure, fall back to truncation
   - Show Snackbar notification

3. **Model context window size unknown (null)**
   - Action: Skip compact entirely (no-op)
   - No user notification needed

4. **All messages fit within the protected window**
   - Action: No compact needed (no-op)
   - This can happen if the conversation has few but very recent long messages

5. **Tool result truncation edge case (content is exactly at limit)**
   - Action: No truncation needed, store as-is

## Constants

```kotlin
object CompactConstants {
    /** Compact triggers when tokens exceed this fraction of the context window */
    const val COMPACT_THRESHOLD_RATIO = 0.85

    /** Recent messages within this fraction of the context window are protected from compaction */
    const val PROTECTED_WINDOW_RATIO = 0.25

    /** Maximum character length for a single tool result before truncation */
    const val TOOL_RESULT_MAX_CHARS = 30_000

    /** Character-to-token estimation ratio (1 token per N characters) */
    const val CHARS_PER_TOKEN_ESTIMATE = 4
}
```

## Test Points

### Functional Tests
- Verify compact triggers when token count exceeds 85% threshold
- Verify compact does not trigger when under threshold
- Verify compact does not trigger when model context window is null
- Verify protected window calculation (most recent 25% by tokens)
- Verify summary is stored in Session.compactedSummary
- Verify original messages remain unchanged in DB
- Verify subsequent API requests use summary + recent messages only
- Verify multiple compactions merge into one cumulative summary
- Verify compact failure retry (one retry)
- Verify fallback to truncation on double failure
- Verify Snackbar shown on fallback
- Verify tool result truncation at 30K characters
- Verify truncation marker is appended
- Verify tool results under 30K are not modified

### Edge Cases
- Session with 0 messages
- Session with only 1 user message
- Session where all messages are within the protected window
- Tool result exactly at 30,000 characters
- Tool result with multi-byte Unicode characters
- Model switch mid-session to a smaller context window
- Compact triggered while compact is already in progress (must not double-compact)
- Session already has a compactedSummary from a previous compact

### Performance Tests
- Compact completion time with 100+ messages
- Token counting overhead per message send
- Tool result truncation speed with 1MB+ content

## Open Issues

- [ ] Exact wording of the summarization system prompt (to be finalized during RFC)
- [ ] Whether to show a token usage indicator in the chat UI (deferred to future)
- [ ] Whether dynamically fetched models should have context window sizes auto-populated (deferred)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
