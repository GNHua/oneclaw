# RFC-011: Auto Compact & Tool Result Truncation

## Document Information
- **RFC ID**: RFC-011
- **Related PRD**: [FEAT-011 (Auto Compact & Tool Result Truncation)](../../prd/features/FEAT-011-auto-compact.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md), [RFC-003 (Provider Management)](RFC-003-provider-management.md), [RFC-005 (Session Management)](RFC-005-session-management.md)
- **Depended On By**: None
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
Currently, `SendMessageUseCase` sends the entire message history of a session to the API on every request, with no context window management. As conversations grow long (50+ messages, heavy tool usage), the accumulated token count approaches or exceeds the model's context window limit, causing API errors. Additionally, tool results (e.g., fetching a web page via `HttpRequestTool`) can be hundreds of KB, bloating the database and consuming disproportionate context space.

This RFC introduces two related features:
1. **Auto Compact**: Automatically summarize older messages when the conversation approaches the model's context window limit. The summary replaces older messages in API requests while preserving all original messages in the database.
2. **Tool Result Truncation**: Truncate excessively large tool results at storage time, before they enter the database.

### Goals
1. Add `contextWindowSize` to the AiModel entity and populate it for all preset models
2. Add compact summary storage fields to the Session entity
3. Implement database migration from version 1 to version 2
4. Implement `ToolResultTruncator` to truncate tool results exceeding 30K characters before DB storage
5. Implement `TokenEstimator` for character-based token estimation
6. Implement `AutoCompactUseCase` to detect when compaction is needed and generate summaries
7. Implement `CompactAwareMessageBuilder` to construct API requests using compact summaries
8. Integrate all components into `SendMessageUseCase`
9. Add compact-related `ChatEvent` types for UI feedback
10. Provide comprehensive unit tests for all new components

### Non-Goals
- Client-side tokenizer (we use character-based estimation)
- User-configurable compact threshold in settings
- Manual compact trigger button in the UI
- Context window size auto-detection from provider APIs
- Compression of individual messages (only whole-message granularity)
- Tool result streaming or pagination
- Token usage indicator in the chat UI

## Technical Design

### Architecture Overview

```
+--------------------------------------------------------------------------+
|                              UI Layer                                     |
|  ChatViewModel                                                            |
|    |-- handles ChatEvent.CompactStarted / CompactCompleted                |
|    |-- shows brief indicator during compaction                            |
|    |-- shows Snackbar on compact fallback                                 |
+--------------------------------------------------------------------------+
|                            Domain Layer                                   |
|  SendMessageUseCase                                                       |
|    |-- CompactAwareMessageBuilder.build()  (prepare API messages)         |
|    |-- adapter.sendMessageStream()         (call AI API)                  |
|    |-- ToolResultTruncator.truncate()      (before saving tool results)   |
|    |-- AutoCompactUseCase.compactIfNeeded() (after response completes)    |
|                                                                           |
|  AutoCompactUseCase                                                       |
|    |-- TokenEstimator.estimateTotalTokens()                               |
|    |-- splitMessages() (protected window vs. older)                       |
|    |-- adapter.generateSimpleCompletion()  (summarize)                    |
|    |-- sessionRepository.updateCompactedSummary()                         |
+--------------------------------------------------------------------------+
|                             Data Layer                                    |
|  SessionEntity  (+ compacted_summary, compact_boundary_timestamp)         |
|  ModelEntity    (+ context_window_size)                                   |
|  Migration(1,2) (ALTER TABLE + UPDATE preset values)                      |
+--------------------------------------------------------------------------+
```

### Core Components

#### 1. ToolResultTruncator

A stateless utility that truncates tool result strings exceeding a character limit.

**File**: `app/src/main/kotlin/com/oneclaw/shadow/core/util/ToolResultTruncator.kt`

```kotlin
package com.oneclaw.shadow.core.util

object ToolResultTruncator {
    const val MAX_CHARS = 30_000

    fun truncate(result: String): String {
        if (result.length <= MAX_CHARS) return result
        val suffix = "\n\n[... content truncated, showing first ${MAX_CHARS} of ${result.length} characters ...]"
        return result.substring(0, MAX_CHARS) + suffix
    }
}
```

**Integration point**: In `SendMessageUseCase`, where tool result messages are created (the `toolOutput` field), apply `ToolResultTruncator.truncate()` before constructing the `Message`.

#### 2. TokenEstimator

Estimates token counts for messages using a character-based heuristic. Used for threshold detection only -- precision is not critical since we are triggering a best-effort optimization, not an exact limit.

**File**: `app/src/main/kotlin/com/oneclaw/shadow/core/util/TokenEstimator.kt`

```kotlin
package com.oneclaw.shadow.core.util

import com.oneclaw.shadow.core.model.Message

object TokenEstimator {
    const val CHARS_PER_TOKEN = 4

    fun estimateTotalTokens(messages: List<Message>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun estimateMessageTokens(msg: Message): Int {
        val contentTokens = estimateFromText(msg.content)
        val thinkingTokens = msg.thinkingContent?.let { estimateFromText(it) } ?: 0
        val toolInputTokens = msg.toolInput?.let { estimateFromText(it) } ?: 0
        val toolOutputTokens = msg.toolOutput?.let { estimateFromText(it) } ?: 0
        return contentTokens + thinkingTokens + toolInputTokens + toolOutputTokens
    }

    fun estimateFromText(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }
}
```

#### 3. AutoCompactUseCase

The central orchestrator for the compaction feature.

**File**: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/AutoCompactUseCase.kt`

**Constructor dependencies**:
- `SessionRepository`
- `MessageRepository`
- `ApiKeyStorage`
- `ModelApiAdapterFactory`

**Key method**: `suspend fun compactIfNeeded(sessionId, model, provider): CompactResult`

**Algorithm**:

```
1. Check model.contextWindowSize -- if null, return (no-op)
2. Get all messages for the session
3. Estimate total tokens via TokenEstimator
4. If totalTokens <= contextWindowSize * 0.85, return (no-op)
5. Split messages into (olderMessages, protectedMessages):
   - Walk backwards from newest, accumulating tokens
   - Stop when accumulated tokens reach contextWindowSize * 0.25
   - Everything before the split point = olderMessages
6. If olderMessages is empty, return (no-op)
7. Build summarization prompt:
   - If session already has compactedSummary, include it as "Previous summary"
   - Append all olderMessages as conversation transcript
   - Instruct model to produce a concise factual summary (200-500 words)
8. Call adapter.generateSimpleCompletion(prompt, maxTokens=2048)
9. If success: store summary + boundary timestamp on Session, return CompactResult(true)
10. If failure: retry once
11. If retry fails: return CompactResult(false) -- caller handles fallback
```

**CompactResult data class**:

```kotlin
data class CompactResult(
    val didCompact: Boolean,
    val fallbackToTruncation: Boolean = false
)
```

**Summarization prompt**:

```
You are summarizing a conversation for context continuity. Create a concise but
comprehensive summary that preserves:
- Key topics discussed
- Important decisions or conclusions
- Any pending questions or tasks
- Tool calls made and their results (briefly)

[If existing summary exists:]
Previous conversation summary:
{existingSummary}

Additional conversation to incorporate:

[Conversation transcript with role labels]

Provide a summary in 200-500 words. Be factual and concise.
```

#### 4. CompactAwareMessageBuilder

Replaces the direct `allMessages.toApiMessages()` call in `SendMessageUseCase`. Handles injecting the compact summary into the system prompt and filtering messages.

**File**: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/CompactAwareMessageBuilder.kt`

```kotlin
package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.data.remote.adapter.ApiMessage

object CompactAwareMessageBuilder {

    fun build(
        session: Session,
        allMessages: List<Message>,
        originalSystemPrompt: String?
    ): Pair<String?, List<ApiMessage>> {
        val summary = session.compactedSummary
        val boundary = session.compactBoundaryTimestamp

        if (summary == null || boundary == null) {
            return Pair(originalSystemPrompt, allMessages.toApiMessages())
        }

        val recentMessages = allMessages.filter { it.createdAt >= boundary }
        val apiMessages = recentMessages.toApiMessages()

        val summaryPrefix = "Previous conversation summary:\n$summary\n\n---\n\n"
        val enhancedPrompt = if (originalSystemPrompt != null) {
            summaryPrefix + originalSystemPrompt
        } else {
            summaryPrefix + "Continue the conversation based on the summary above."
        }

        return Pair(enhancedPrompt, apiMessages)
    }
}
```

**Key design decision**: The compact summary is prepended to the system prompt rather than injected as a separate user/assistant message. This ensures the model treats it as background context rather than part of the conversation turn sequence, which avoids confusing the role alternation pattern.

### Data Model

#### Modified Entities

**AiModel** (`core/model/AiModel.kt`):

```kotlin
data class AiModel(
    val id: String,
    val displayName: String?,
    val providerId: String,
    val isDefault: Boolean,
    val source: ModelSource,
    val contextWindowSize: Int? = null  // max context window in tokens; null = unknown
)
```

**ModelEntity** (`data/local/entity/ModelEntity.kt`):

```kotlin
@Entity(tableName = "models", primaryKeys = ["id", "provider_id"], ...)
data class ModelEntity(
    val id: String,
    @ColumnInfo(name = "display_name") val displayName: String?,
    @ColumnInfo(name = "provider_id") val providerId: String,
    @ColumnInfo(name = "is_default") val isDefault: Boolean,
    val source: String,
    @ColumnInfo(name = "context_window_size") val contextWindowSize: Int? = null  // NEW
)
```

**Session** (`core/model/Session.kt`):

```kotlin
data class Session(
    val id: String,
    val title: String,
    val currentAgentId: String,
    val messageCount: Int,
    val lastMessagePreview: String?,
    val isActive: Boolean,
    val deletedAt: Long?,
    val createdAt: Long,
    val updatedAt: Long,
    val compactedSummary: String? = null,           // NEW
    val compactBoundaryTimestamp: Long? = null       // NEW
)
```

**SessionEntity** (`data/local/entity/SessionEntity.kt`):

```kotlin
@Entity(tableName = "sessions", ...)
data class SessionEntity(
    ...existing fields...,
    @ColumnInfo(name = "compacted_summary") val compactedSummary: String? = null,           // NEW
    @ColumnInfo(name = "compact_boundary_timestamp") val compactBoundaryTimestamp: Long? = null  // NEW
)
```

#### Database Migration

**New file**: `app/src/main/kotlin/com/oneclaw/shadow/data/local/db/Migrations.kt`

```kotlin
val MIGRATION_1_2 = object : Migration(1, 2) {
    override fun migrate(db: SupportSQLiteDatabase) {
        // Add context_window_size to models
        db.execSQL("ALTER TABLE models ADD COLUMN context_window_size INTEGER DEFAULT NULL")

        // Populate preset model context window sizes
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o'")
        db.execSQL("UPDATE models SET context_window_size = 128000 WHERE id = 'gpt-4o-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o1'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'o3-mini'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-opus-4-5-20251101'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-sonnet-4-5-20250929'")
        db.execSQL("UPDATE models SET context_window_size = 200000 WHERE id = 'claude-haiku-4-5-20251001'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.0-flash'")
        db.execSQL("UPDATE models SET context_window_size = 1048576 WHERE id = 'gemini-2.5-pro'")

        // Add compact fields to sessions
        db.execSQL("ALTER TABLE sessions ADD COLUMN compacted_summary TEXT DEFAULT NULL")
        db.execSQL("ALTER TABLE sessions ADD COLUMN compact_boundary_timestamp INTEGER DEFAULT NULL")
    }
}
```

**AppDatabase changes**:
- Bump `version = 1` to `version = 2`
- Update seed callback INSERT statements to include `context_window_size` for fresh installs
- Register `MIGRATION_1_2` in `DatabaseModule.kt` via `.addMigrations(MIGRATION_1_2)`

#### Preset Model Context Window Sizes

| Model ID | Provider | Context Window |
|----------|----------|---------------|
| gpt-4o | OpenAI | 128,000 |
| gpt-4o-mini | OpenAI | 128,000 |
| o1 | OpenAI | 200,000 |
| o3-mini | OpenAI | 200,000 |
| claude-opus-4-5-20251101 | Anthropic | 200,000 |
| claude-sonnet-4-5-20250929 | Anthropic | 200,000 |
| claude-haiku-4-5-20251001 | Anthropic | 200,000 |
| gemini-2.0-flash | Gemini | 1,048,576 |
| gemini-2.5-pro | Gemini | 1,048,576 |

### API Design

#### SessionDao additions

```kotlin
@Query("UPDATE sessions SET compacted_summary = :summary, compact_boundary_timestamp = :boundaryTimestamp, updated_at = :updatedAt WHERE id = :id")
suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?, updatedAt: Long)
```

#### SessionRepository additions

```kotlin
suspend fun updateCompactedSummary(id: String, summary: String?, boundaryTimestamp: Long?)
```

#### Mapper updates

**ProviderMapper.kt**: Map `contextWindowSize` in both `ModelEntity.toDomain()` and `AiModel.toEntity()`.

**SessionMapper.kt**: Map `compactedSummary` and `compactBoundaryTimestamp` in both directions.

#### ChatEvent additions

```kotlin
sealed class ChatEvent {
    ...existing events...
    data object CompactStarted : ChatEvent()
    data class CompactCompleted(val didCompact: Boolean) : ChatEvent()
}
```

### SendMessageUseCase Integration

Three changes to `SendMessageUseCase`:

#### Change 1: Compact-aware message building

Replace:
```kotlin
val allMessages = messageRepository.getMessagesSnapshot(sessionId)
val apiMessages = allMessages.toApiMessages()
// ...
adapter.sendMessageStream(..., systemPrompt = agent.systemPrompt)
```

With:
```kotlin
val allMessages = messageRepository.getMessagesSnapshot(sessionId)
val session = sessionRepository.getSessionById(sessionId)!!
val (effectiveSystemPrompt, apiMessages) = CompactAwareMessageBuilder.build(
    session = session,
    allMessages = allMessages,
    originalSystemPrompt = agent.systemPrompt
)
// ...
adapter.sendMessageStream(..., systemPrompt = effectiveSystemPrompt)
```

#### Change 2: Tool result truncation

In the tool result saving section, truncate before creating the Message:
```kotlin
val rawOutput = tr.result.result ?: tr.result.errorMessage ?: ""
val truncatedOutput = ToolResultTruncator.truncate(rawOutput)
// Use truncatedOutput as toolOutput
```

#### Change 3: Post-response compaction trigger

After `send(ChatEvent.ResponseComplete(...))` and before `break`:

```kotlin
if (pendingToolCalls.isEmpty()) {
    sessionRepository.updateMessageStats(...)
    send(ChatEvent.ResponseComplete(aiMessage, usage))

    // Trigger auto-compact check
    send(ChatEvent.CompactStarted)
    val compactResult = autoCompactUseCase.compactIfNeeded(sessionId, model, provider)
    send(ChatEvent.CompactCompleted(compactResult.didCompact))

    break
}
```

#### Constructor change

Add `autoCompactUseCase: AutoCompactUseCase` parameter.

### DI Registration

**FeatureModule.kt**:

```kotlin
// FEAT-011: Auto Compact
factory { AutoCompactUseCase(get(), get(), get(), get()) }

// Update SendMessageUseCase to include AutoCompactUseCase
factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
```

### Constants

```kotlin
object CompactConstants {
    const val COMPACT_THRESHOLD_RATIO = 0.85
    const val PROTECTED_WINDOW_RATIO = 0.25
    const val TOOL_RESULT_MAX_CHARS = 30_000
    const val CHARS_PER_TOKEN_ESTIMATE = 4
    const val SUMMARY_MAX_TOKENS = 2048
    const val MAX_RETRIES = 1
}
```

These can be defined inline in their respective classes (`AutoCompactUseCase.Companion`, `ToolResultTruncator`, `TokenEstimator`) rather than in a separate constants object, following the existing pattern in the codebase (e.g., `SendMessageUseCase.MAX_TOOL_ROUNDS`).

## Data Flow

### Auto Compact Flow

```
User sends message
  |
  v
SendMessageUseCase.execute()
  |
  +-> Get session + all messages
  +-> CompactAwareMessageBuilder.build(session, messages, systemPrompt)
  |     |
  |     +-> session.compactedSummary == null?
  |     |     YES -> return (originalPrompt, allMessages.toApiMessages())
  |     |     NO  -> filter messages >= boundaryTimestamp
  |     |            prepend summary to systemPrompt
  |     |            return (enhancedPrompt, recentMessages.toApiMessages())
  |
  +-> adapter.sendMessageStream(enhancedPrompt, filteredMessages)
  +-> Collect streaming response
  +-> Save AI response message
  |
  +-> pendingToolCalls.isEmpty()?
  |     NO  -> Execute tools, save results (with truncation), next round
  |     YES -> send(ResponseComplete)
  |            |
  |            v
  |            AutoCompactUseCase.compactIfNeeded(sessionId, model, provider)
  |              |
  |              +-> contextWindowSize == null? -> return (no-op)
  |              +-> TokenEstimator.estimateTotalTokens(messages)
  |              +-> totalTokens <= threshold? -> return (no-op)
  |              +-> splitMessages(messages, protectedBudget)
  |              +-> olderMessages.isEmpty()? -> return (no-op)
  |              +-> buildSummarizationPrompt(older, existingSummary)
  |              +-> adapter.generateSimpleCompletion(prompt, 2048)
  |              +-> success? -> sessionRepository.updateCompactedSummary()
  |              +-> failure? -> retry once -> still fail? -> return (no compact)
  |
  +-> break (flow ends)
```

### Tool Result Truncation Flow

```
Tool executes -> returns result string
  |
  v
ToolResultTruncator.truncate(result)
  |
  +-> result.length <= 30,000? -> return as-is
  +-> result.length > 30,000? -> return first 30K chars + truncation marker
  |
  v
Save truncated result to Message.toolOutput -> DB
```

## Error Handling

| Scenario | Action |
|----------|--------|
| `contextWindowSize` is null | Skip compact entirely (no-op). Feature gracefully disabled. |
| All messages within protected window | Skip compact (nothing to summarize). |
| Summarization API fails (network error) | Retry once silently. |
| Retry also fails | Return `CompactResult(false)`. UI shows no error. Next request sends all messages -- may hit API limit, which is handled by existing error flow. |
| Summarization returns empty/blank | Treat as failure, retry once. |
| Compact already in progress (race) | Not possible -- compaction runs synchronously within the channelFlow after ResponseComplete, and UI blocks new messages during streaming. |
| Tool result truncation | Never throws. Returns input unchanged if under limit. |

## Performance Considerations

- **TokenEstimator**: O(n) scan over messages, string length checks only. < 1ms for typical sessions.
- **CompactAwareMessageBuilder**: O(n) filter by timestamp. Negligible overhead.
- **AutoCompactUseCase**: The `generateSimpleCompletion` call is the bottleneck (network I/O). Expected 2-10 seconds depending on model/provider. Runs after `ResponseComplete` has been sent, so user already sees the response.
- **ToolResultTruncator**: O(1) length check, O(n) substring only when needed. < 1ms.
- **DB migration**: One-time cost. ALTER TABLE + UPDATE on 9 rows. < 100ms.

## Security Considerations

- Compact summaries may contain sensitive information from the conversation. They are stored in the same local Room database with the same access controls as the original messages.
- The summarization request sends conversation content to the same API provider already handling the conversation. No new data exposure.
- Tool result truncation reduces the amount of potentially sensitive external data stored locally.

## Implementation Steps

### Phase 1: Database Schema Changes
1. [ ] Add `contextWindowSize: Int?` to `AiModel` and `ModelEntity`
2. [ ] Add `compactedSummary: String?` and `compactBoundaryTimestamp: Long?` to `Session` and `SessionEntity`
3. [ ] Create `Migrations.kt` with `MIGRATION_1_2`
4. [ ] Bump `AppDatabase` to version 2, update seed callback
5. [ ] Register migration in `DatabaseModule.kt`
6. [ ] Update `ProviderMapper` (model mapping)
7. [ ] Update `SessionMapper` (session mapping)
8. [ ] Add `updateCompactedSummary` to `SessionDao`, `SessionRepository`, `SessionRepositoryImpl`

### Phase 2: Tool Result Truncation
9. [ ] Create `ToolResultTruncator.kt`
10. [ ] Integrate into `SendMessageUseCase` (tool result saving)
11. [ ] Write `ToolResultTruncatorTest.kt`

### Phase 3: Auto Compact Core
12. [ ] Create `TokenEstimator.kt`
13. [ ] Create `AutoCompactUseCase.kt`
14. [ ] Create `CompactAwareMessageBuilder.kt`
15. [ ] Write `TokenEstimatorTest.kt`
16. [ ] Write `AutoCompactUseCaseTest.kt`
17. [ ] Write `CompactAwareMessageBuilderTest.kt`

### Phase 4: Integration
18. [ ] Add `CompactStarted` / `CompactCompleted` to `ChatEvent`
19. [ ] Modify `SendMessageUseCase`: compact-aware message building, compact trigger, add dependency
20. [ ] Handle compact events in `ChatViewModel` (brief indicator / Snackbar on fallback)
21. [ ] Register `AutoCompactUseCase` in `FeatureModule.kt`, update `SendMessageUseCase` factory
22. [ ] Update existing `SendMessageUseCaseTest` for new constructor parameter

### Phase 5: Testing
23. [ ] Run `./gradlew test` -- all unit tests pass
24. [ ] Run `./gradlew connectedAndroidTest` -- all instrumented tests pass (update DAO tests for new columns)
25. [ ] Write migration instrumented test
26. [ ] Layer 2 adb verification if applicable
27. [ ] Write test report

## Dependencies

- **Room**: Database migration support (already available)
- **ModelApiAdapter.generateSimpleCompletion()**: Already implemented in all 3 adapters, used by `GenerateTitleUseCase`
- No new external libraries required

## Risks and Mitigations

| Risk | Likelihood | Impact | Mitigation |
|------|-----------|--------|------------|
| Character-based token estimation is inaccurate | Medium | Low | Over-estimation is acceptable; triggers compact slightly early. Under-estimation may cause API errors, handled by existing error flow. |
| Summary quality degrades context continuity | Low | Medium | Prompt is explicit about what to preserve. Summaries are cumulative. User can start a new session if context drifts. |
| Migration failure on existing installs | Low | High | ALTER TABLE ADD COLUMN is safe in SQLite. Thoroughly tested with instrumented migration tests. |
| `generateSimpleCompletion` timeout | Low | Low | Retry once. If both fail, conversation continues without compaction. |

## Alternatives Considered

1. **Sliding window (drop oldest messages)**: Simpler but loses all context from older messages. Summary approach preserves key information.
2. **Client-side tokenizer**: More accurate token counts but adds dependency complexity (tiktoken/sentencepiece). Character estimation is sufficient for threshold detection.
3. **Store summary as a Message**: Would require a new `MessageType.COMPACT_SUMMARY`. Storing on Session is cleaner -- one field per session, not mixed into the message sequence.
4. **Fixed message count for protected window**: Unreliable because message lengths vary wildly (a tool result can be 30K chars). Token-based proportion is more robust.

## Future Extensions

- [ ] User-configurable compact threshold in settings
- [ ] Manual compact trigger button
- [ ] Token usage indicator in chat UI
- [ ] Auto-populate `contextWindowSize` when fetching models from provider APIs
- [ ] "Reset context" button to clear `compactedSummary`

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
