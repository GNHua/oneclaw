# RFC-005: Session Management

## Document Information
- **RFC ID**: RFC-005
- **Related PRD**: [FEAT-005 (Session Management)](../../prd/features/FEAT-005-session.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md) (Navigation Drawer, Section 1 Chat Screen empty state)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-002 (Agent Management)](RFC-002-agent-management.md), [RFC-003 (Provider Management)](RFC-003-provider-management.md)
- **Depended On By**: RFC-001 (Chat Interaction)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
Session Management provides the infrastructure for creating, resuming, renaming, and deleting conversation sessions. A session is the container for a conversation -- it holds message history, the current Agent reference, and metadata like title and timestamps. Sessions are displayed in a Navigation Drawer sorted by last active time. This RFC covers session data persistence, lazy session creation, two-phase title generation (truncated + AI-generated), soft-delete with undo, batch deletion, and the session list UI in the drawer.

### Goals
1. Implement session data persistence (Room entity, DAO, repository) with soft-delete support
2. Implement lazy session creation (no DB record until first message sent)
3. Implement two-phase title generation (immediate truncated + async AI-generated)
4. Implement lightweight model selection with availability verification for AI title generation
5. Implement single-delete with swipe + undo Snackbar (~5 second window)
6. Implement batch-delete with selection mode + undo Snackbar
7. Implement session rename (manual title editing)
8. Implement the Navigation Drawer session list UI
9. Implement `updateAgentForSessions()` for agent deletion fallback (from RFC-002)
10. Provide enough implementation detail for AI-assisted code generation

### Non-Goals
- Message display and rendering in chat (RFC-001)
- Sending messages and streaming responses (RFC-001)
- Agent switching within a session (RFC-001 integrates AgentSelectorSheet from RFC-002)
- Session folders, tags, or groups
- Session search
- Session pinning
- Session export
- Session sharing
- Data sync / backup (FEAT-007)

## Technical Design

### Architecture Overview

```
+-----------------------------------------------------------------------+
|                           UI Layer                                      |
|  SessionDrawerContent    SessionListViewModel    RenameDialog           |
|       |                       |                                        |
|       v                       v                                        |
|  (Drawer composable)    SessionListViewModel                           |
+-----------------------------------------------------------------------+
|                         Domain Layer                                    |
|  CreateSessionUseCase  DeleteSessionUseCase  BatchDeleteSessionsUseCase|
|  GenerateTitleUseCase  RenameSessionUseCase  CleanupSoftDeletedUseCase |
|       |                                                                |
|       v                                                                |
|  SessionRepository (interface)   MessageRepository (interface)         |
+-----------------------------------------------------------------------+
|                          Data Layer                                     |
|  SessionRepositoryImpl                                                  |
|       |              |                |                                 |
|       v              v                v                                 |
|  SessionDao     MessageDao     ModelApiAdapterFactory                  |
|                                (for AI title generation)               |
+-----------------------------------------------------------------------+
```

### Core Components

1. **SessionRepositoryImpl**
   - Responsibility: Session CRUD, soft-delete/restore/hard-delete, agent fallback
   - Dependencies: SessionDao, MessageDao

2. **Lazy Session Creation**
   - Responsibility: Session is not persisted until first message is sent
   - Mechanism: ChatViewModel holds an in-memory "pending session" object; `CreateSessionUseCase` persists it when the first message is sent
   - See Data Flow section for details

3. **GenerateTitleUseCase**
   - Responsibility: Two-phase title generation
   - Phase 1: Truncate first user message to ~50 chars at word boundary
   - Phase 2: Send async request to lightweight model for a better title
   - Lightweight model: Hardcoded mapping verified against available models in DB

4. **Soft-Delete Manager**
   - Responsibility: Mark sessions as deleted, schedule hard-delete after undo window
   - Mechanism: `deleted_at` field in sessions table + `viewModelScope.launch` with delay
   - Cleanup: On app startup, hard-delete any sessions with `deleted_at != null`

5. **SessionDrawerContent**
   - Responsibility: Compose content for the Navigation Drawer showing session list
   - Integrates: Swipe-to-delete, selection mode, rename dialog

## Data Model

### Domain Models

The `Session` model is updated from RFC-000 to add `deletedAt` and `lastMessagePreview`.

#### Session (Updated)

```kotlin
data class Session(
    val id: String,                    // UUID
    val title: String,                 // Display title
    val currentAgentId: String,        // Current agent for this session
    val messageCount: Int,             // Number of messages
    val lastMessagePreview: String?,   // Truncated last message text for list display
    val isActive: Boolean,             // Whether a request is in-flight
    val deletedAt: Long?,              // Soft-delete timestamp (null = not deleted)
    val createdAt: Long,
    val updatedAt: Long                // Last activity timestamp
)
```

**Changes from RFC-000:**
- Added `lastMessagePreview`: Truncated text of the last message, used for session list display in the drawer. Avoids joining on the messages table for list rendering.
- Added `deletedAt`: Nullable timestamp for soft-delete support. When non-null, the session is pending permanent deletion.

### Room Entity

#### SessionEntity

```kotlin
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey
    val id: String,
    val title: String,
    @ColumnInfo(name = "current_agent_id")
    val currentAgentId: String,
    @ColumnInfo(name = "message_count")
    val messageCount: Int,
    @ColumnInfo(name = "last_message_preview")
    val lastMessagePreview: String?,
    @ColumnInfo(name = "is_active")
    val isActive: Boolean,
    @ColumnInfo(name = "deleted_at")
    val deletedAt: Long?,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### Database Schema

```sql
CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    current_agent_id TEXT NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    last_message_preview TEXT,
    is_active INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_sessions_updated_at ON sessions(updated_at);
CREATE INDEX idx_sessions_deleted_at ON sessions(deleted_at);
```

**Changes from RFC-000 schema:**
- Added `last_message_preview TEXT`
- Added `deleted_at INTEGER` (nullable)
- Added indexes for `updated_at` (sort) and `deleted_at` (filter)

### Entity-Domain Mappers

```kotlin
// SessionMapper.kt
fun SessionEntity.toDomain(): Session = Session(
    id = id,
    title = title,
    currentAgentId = currentAgentId,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview,
    isActive = isActive,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Session.toEntity(): SessionEntity = SessionEntity(
    id = id,
    title = title,
    currentAgentId = currentAgentId,
    messageCount = messageCount,
    lastMessagePreview = lastMessagePreview,
    isActive = isActive,
    deletedAt = deletedAt,
    createdAt = createdAt,
    updatedAt = updatedAt
)
```

## DAO Interface

### SessionDao

```kotlin
@Dao
interface SessionDao {

    /**
     * Get all non-deleted sessions, sorted by updated_at descending (most recent first).
     */
    @Query("SELECT * FROM sessions WHERE deleted_at IS NULL ORDER BY updated_at DESC")
    fun getActiveSessions(): Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    /**
     * Soft delete: set deleted_at timestamp.
     */
    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDeleteSession(id: String, deletedAt: Long)

    /**
     * Soft delete multiple sessions.
     */
    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id IN (:ids)")
    suspend fun softDeleteSessions(ids: List<String>, deletedAt: Long)

    /**
     * Restore a soft-deleted session.
     */
    @Query("UPDATE sessions SET deleted_at = NULL WHERE id = :id")
    suspend fun restoreSession(id: String)

    /**
     * Restore multiple soft-deleted sessions.
     */
    @Query("UPDATE sessions SET deleted_at = NULL WHERE id IN (:ids)")
    suspend fun restoreSessions(ids: List<String>)

    /**
     * Hard delete a single session. CASCADE deletes its messages.
     */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun hardDeleteSession(id: String)

    /**
     * Hard delete all soft-deleted sessions (cleanup on app startup).
     */
    @Query("DELETE FROM sessions WHERE deleted_at IS NOT NULL")
    suspend fun hardDeleteAllSoftDeleted()

    /**
     * Update the current agent for all sessions referencing a specific agent.
     * Used by DeleteAgentUseCase (RFC-002) for fallback to General Assistant.
     */
    @Query("UPDATE sessions SET current_agent_id = :newAgentId WHERE current_agent_id = :oldAgentId")
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)

    /**
     * Update session title.
     */
    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    /**
     * Set the AI-generated title.
     */
    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun setGeneratedTitle(id: String, title: String, updatedAt: Long)

    /**
     * Update message count and last message preview.
     */
    @Query("UPDATE sessions SET message_count = :count, last_message_preview = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageStats(id: String, count: Int, preview: String?, updatedAt: Long)

    /**
     * Set the is_active flag (request in-flight).
     */
    @Query("UPDATE sessions SET is_active = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    /**
     * Update current agent for a specific session.
     */
    @Query("UPDATE sessions SET current_agent_id = :agentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCurrentAgent(id: String, agentId: String, updatedAt: Long)

    /**
     * Get count of all sessions (for statistics).
     */
    @Query("SELECT COUNT(*) FROM sessions WHERE deleted_at IS NULL")
    suspend fun getSessionCount(): Int
}
```

## Repository Implementation

### SessionRepository Interface

Updated from RFC-000 with soft-delete, restore, batch operations, and agent fallback.

```kotlin
interface SessionRepository {
    /**
     * Observe all non-deleted sessions, sorted by updated_at descending.
     */
    fun getAllSessions(): Flow<List<Session>>

    suspend fun getSessionById(id: String): Session?

    /**
     * Create a new session. Called when the first message is sent (lazy creation).
     */
    suspend fun createSession(session: Session): Session

    suspend fun updateSession(session: Session)

    /**
     * Soft-delete a session. Sets deleted_at to current time.
     * The session is hidden from the list but can be restored.
     */
    suspend fun softDeleteSession(id: String)

    /**
     * Soft-delete multiple sessions.
     */
    suspend fun softDeleteSessions(ids: List<String>)

    /**
     * Restore a soft-deleted session (undo).
     */
    suspend fun restoreSession(id: String)

    /**
     * Restore multiple soft-deleted sessions (batch undo).
     */
    suspend fun restoreSessions(ids: List<String>)

    /**
     * Hard-delete a session and all its messages.
     */
    suspend fun hardDeleteSession(id: String)

    /**
     * Hard-delete all soft-deleted sessions. Called on app startup.
     */
    suspend fun hardDeleteAllSoftDeleted()

    /**
     * Update all sessions referencing oldAgentId to use newAgentId.
     * Used by DeleteAgentUseCase (RFC-002).
     */
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)

    /**
     * Update session title.
     */
    suspend fun updateTitle(id: String, title: String)

    /**
     * Set the AI-generated title and mark as generated.
     */
    suspend fun setGeneratedTitle(id: String, title: String)

    /**
     * Update message stats (count + last message preview).
     */
    suspend fun updateMessageStats(id: String, count: Int, preview: String?)

    /**
     * Set session active/inactive (request in-flight).
     */
    suspend fun setActive(id: String, isActive: Boolean)

    /**
     * Switch the current agent for a session.
     */
    suspend fun updateCurrentAgent(id: String, agentId: String)
}
```

### SessionRepositoryImpl

```kotlin
class SessionRepositoryImpl(
    private val sessionDao: SessionDao
) : SessionRepository {

    override fun getAllSessions(): Flow<List<Session>> {
        return sessionDao.getActiveSessions().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getSessionById(id: String): Session? {
        return sessionDao.getSessionById(id)?.toDomain()
    }

    override suspend fun createSession(session: Session): Session {
        val now = System.currentTimeMillis()
        val newSession = session.copy(
            id = if (session.id.isBlank()) java.util.UUID.randomUUID().toString() else session.id,
            createdAt = now,
            updatedAt = now
        )
        sessionDao.insertSession(newSession.toEntity())
        return newSession
    }

    override suspend fun updateSession(session: Session) {
        sessionDao.updateSession(session.toEntity())
    }

    override suspend fun softDeleteSession(id: String) {
        sessionDao.softDeleteSession(id, System.currentTimeMillis())
    }

    override suspend fun softDeleteSessions(ids: List<String>) {
        sessionDao.softDeleteSessions(ids, System.currentTimeMillis())
    }

    override suspend fun restoreSession(id: String) {
        sessionDao.restoreSession(id)
    }

    override suspend fun restoreSessions(ids: List<String>) {
        sessionDao.restoreSessions(ids)
    }

    override suspend fun hardDeleteSession(id: String) {
        sessionDao.hardDeleteSession(id)
        // Messages are CASCADE deleted via foreign key
    }

    override suspend fun hardDeleteAllSoftDeleted() {
        sessionDao.hardDeleteAllSoftDeleted()
    }

    override suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String) {
        sessionDao.updateAgentForSessions(oldAgentId, newAgentId)
    }

    override suspend fun updateTitle(id: String, title: String) {
        sessionDao.updateTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun setGeneratedTitle(id: String, title: String) {
        sessionDao.setGeneratedTitle(id, title, System.currentTimeMillis())
    }

    override suspend fun updateMessageStats(id: String, count: Int, preview: String?) {
        sessionDao.updateMessageStats(id, count, preview, System.currentTimeMillis())
    }

    override suspend fun setActive(id: String, isActive: Boolean) {
        sessionDao.setActive(id, isActive)
    }

    override suspend fun updateCurrentAgent(id: String, agentId: String) {
        sessionDao.updateCurrentAgent(id, agentId, System.currentTimeMillis())
    }
}
```

## Use Cases

### CreateSessionUseCase

```kotlin
/**
 * Creates a new session in the database. Called when the first message is sent.
 * Implements lazy session creation: the session does not exist in DB until this is called.
 *
 * Located in: feature/session/usecase/CreateSessionUseCase.kt
 */
class CreateSessionUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(
        agentId: String = AgentConstants.GENERAL_ASSISTANT_ID
    ): Session {
        val session = Session(
            id = "",                // Generated by repository
            title = "New Conversation",   // Placeholder, replaced by Phase 1 title
            currentAgentId = agentId,
            messageCount = 0,
            lastMessagePreview = null,
            isActive = false,
            deletedAt = null,
            createdAt = 0,          // Set by repository
            updatedAt = 0           // Set by repository
        )
        return sessionRepository.createSession(session)
    }
}
```

### DeleteSessionUseCase

```kotlin
/**
 * Soft-deletes a single session. Returns the session ID so undo can reference it.
 *
 * Located in: feature/session/usecase/DeleteSessionUseCase.kt
 */
class DeleteSessionUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(sessionId: String): AppResult<Unit> {
        val session = sessionRepository.getSessionById(sessionId)
            ?: return AppResult.Error(
                message = "Session not found.",
                code = ErrorCode.VALIDATION_ERROR
            )
        sessionRepository.softDeleteSession(sessionId)
        return AppResult.Success(Unit)
    }
}
```

### BatchDeleteSessionsUseCase

```kotlin
/**
 * Soft-deletes multiple sessions at once.
 *
 * Located in: feature/session/usecase/BatchDeleteSessionsUseCase.kt
 */
class BatchDeleteSessionsUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(sessionIds: List<String>): AppResult<Unit> {
        if (sessionIds.isEmpty()) {
            return AppResult.Error(
                message = "No sessions selected.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        sessionRepository.softDeleteSessions(sessionIds)
        return AppResult.Success(Unit)
    }
}
```

### RenameSessionUseCase

```kotlin
/**
 * Renames a session title. Validates that the title is non-empty.
 *
 * Located in: feature/session/usecase/RenameSessionUseCase.kt
 */
class RenameSessionUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(sessionId: String, newTitle: String): AppResult<Unit> {
        val trimmed = newTitle.trim()
        if (trimmed.isBlank()) {
            return AppResult.Error(
                message = "Session title cannot be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (trimmed.length > 200) {
            return AppResult.Error(
                message = "Session title is too long (max 200 characters).",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        sessionRepository.updateTitle(sessionId, trimmed)
        return AppResult.Success(Unit)
    }
}
```

### GenerateTitleUseCase

```kotlin
/**
 * Two-phase title generation for a session.
 *
 * Phase 1: Immediate truncated title from first user message.
 * Phase 2: Async AI-generated title using a lightweight model.
 *
 * Located in: feature/session/usecase/GenerateTitleUseCase.kt
 */
class GenerateTitleUseCase(
    private val sessionRepository: SessionRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) {

    companion object {
        private const val TRUNCATED_TITLE_MAX_LENGTH = 50
        private const val AI_RESPONSE_CONTEXT_MAX_LENGTH = 200

        /**
         * Hardcoded lightweight model IDs for each provider type.
         * These are verified against the DB before use.
         */
        private val LIGHTWEIGHT_MODELS = mapOf(
            ProviderType.OPENAI to "gpt-4o-mini",
            ProviderType.ANTHROPIC to "claude-haiku-4-20250414",
            ProviderType.GEMINI to "gemini-2.0-flash"
        )
    }

    /**
     * Phase 1: Generate a truncated title from the first user message.
     * Called immediately when the first message is sent.
     * This is synchronous and instant.
     */
    fun generateTruncatedTitle(firstMessage: String): String {
        val trimmed = firstMessage.trim()
        if (trimmed.length <= TRUNCATED_TITLE_MAX_LENGTH) {
            return trimmed
        }

        // Truncate at word boundary
        val truncated = trimmed.substring(0, TRUNCATED_TITLE_MAX_LENGTH)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > TRUNCATED_TITLE_MAX_LENGTH / 2) {
            // Found a reasonable word boundary
            truncated.substring(0, lastSpace) + "..."
        } else {
            // No good word boundary, just truncate
            truncated + "..."
        }
    }

    /**
     * Phase 2: Generate an AI-powered title using a lightweight model.
     * Called asynchronously after the first AI response is received.
     * 
     * @param sessionId The session to update
     * @param firstUserMessage The user's first message
     * @param firstAiResponse The AI's first response text
     * @param currentModelId The model used for the conversation
     * @param currentProviderId The provider used for the conversation
     */
    suspend fun generateAiTitle(
        sessionId: String,
        firstUserMessage: String,
        firstAiResponse: String,
        currentModelId: String,
        currentProviderId: String
    ) {
        try {
            // Resolve provider and model for title generation
            val provider = providerRepository.getProviderById(currentProviderId) ?: return
            val apiKey = apiKeyStorage.getApiKey(currentProviderId) ?: return
            val modelId = resolveLightweightModel(provider, currentModelId)

            // Build the title generation prompt
            val truncatedResponse = if (firstAiResponse.length > AI_RESPONSE_CONTEXT_MAX_LENGTH) {
                firstAiResponse.substring(0, AI_RESPONSE_CONTEXT_MAX_LENGTH) + "..."
            } else {
                firstAiResponse
            }

            val prompt = buildTitlePrompt(firstUserMessage, truncatedResponse)

            // Send request via the appropriate adapter
            val adapter = adapterFactory.getAdapter(provider.type)
            val titleResult = adapter.generateSimpleCompletion(
                apiBaseUrl = provider.apiBaseUrl,
                apiKey = apiKey,
                modelId = modelId,
                prompt = prompt,
                maxTokens = 30
            )

            when (titleResult) {
                is AppResult.Success -> {
                    val generatedTitle = titleResult.data
                        .trim()
                        .removeSurrounding("\"")  // Remove quotes if model wraps in them
                        .take(200)                 // Safety limit
                    if (generatedTitle.isNotBlank()) {
                        sessionRepository.setGeneratedTitle(sessionId, generatedTitle)
                    }
                }
                is AppResult.Error -> {
                    // Silently fail -- keep the truncated title
                    // No retry to avoid unnecessary API costs
                }
            }
        } catch (e: Exception) {
            // Silently fail
        }
    }

    /**
     * Resolve which model to use for title generation.
     * 
     * Strategy:
     * 1. Look up the hardcoded lightweight model for this provider type
     * 2. Check if that model actually exists in the DB for this provider
     * 3. If it exists, use it; otherwise fall back to the current conversation model
     */
    private suspend fun resolveLightweightModel(
        provider: Provider,
        currentModelId: String
    ): String {
        val lightweightId = LIGHTWEIGHT_MODELS[provider.type] ?: return currentModelId

        // Verify the lightweight model actually exists for this provider
        val models = providerRepository.getModelsForProvider(provider.id)
        val exists = models.any { it.id == lightweightId }

        return if (exists) lightweightId else currentModelId
    }

    private fun buildTitlePrompt(userMessage: String, aiResponse: String): String {
        return """Generate a short title (5-10 words) for this conversation. Return only the title text, no quotes or extra formatting.

User: $userMessage
Assistant: $aiResponse"""
    }
}
```

### ModelApiAdapter Extension

The `generateSimpleCompletion` method is a new addition to the `ModelApiAdapter` interface for non-streaming single-response requests. It is used by title generation and potentially other simple API calls.

```kotlin
// Addition to ModelApiAdapter interface (data/remote/adapter/ModelApiAdapter.kt)
interface ModelApiAdapter {
    // ... existing methods from RFC-003 ...

    /**
     * Send a simple (non-streaming) chat completion request.
     * Used for lightweight tasks like title generation.
     * 
     * IMPLEMENTATION: In RFC-005 for title generation.
     * Full streaming implementation in RFC-001.
     *
     * @param prompt The user message to send
     * @param maxTokens Maximum tokens in the response
     * @return The assistant's text response
     */
    suspend fun generateSimpleCompletion(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = 100
    ): AppResult<String>
}
```

#### OpenAI Implementation

```kotlin
// In OpenAiAdapter
override suspend fun generateSimpleCompletion(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    prompt: String,
    maxTokens: Int
): AppResult<String> = withContext(Dispatchers.IO) {
    try {
        val requestBody = Json.encodeToString(
            mapOf(
                "model" to modelId,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to maxTokens
            )
        )

        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext AppResult.Error(
                message = "Empty response", code = ErrorCode.PROVIDER_ERROR
            )
            // Parse: response.choices[0].message.content
            val json = Json.parseToJsonElement(body).jsonObject
            val content = json["choices"]?.jsonArray?.get(0)
                ?.jsonObject?.get("message")
                ?.jsonObject?.get("content")
                ?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(
                    message = "Unexpected response format", code = ErrorCode.PROVIDER_ERROR
                )
            AppResult.Success(content)
        } else {
            AppResult.Error(
                message = "API error: ${response.code}",
                code = ErrorCode.PROVIDER_ERROR
            )
        }
    } catch (e: Exception) {
        AppResult.Error(message = "Request failed: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
    }
}
```

#### Anthropic Implementation

```kotlin
// In AnthropicAdapter
override suspend fun generateSimpleCompletion(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    prompt: String,
    maxTokens: Int
): AppResult<String> = withContext(Dispatchers.IO) {
    try {
        val requestBody = Json.encodeToString(
            mapOf(
                "model" to modelId,
                "messages" to listOf(
                    mapOf("role" to "user", "content" to prompt)
                ),
                "max_tokens" to maxTokens
            )
        )

        val request = Request.Builder()
            .url("${apiBaseUrl.trimEnd('/')}/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", ANTHROPIC_VERSION)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext AppResult.Error(
                message = "Empty response", code = ErrorCode.PROVIDER_ERROR
            )
            // Parse: response.content[0].text
            val json = Json.parseToJsonElement(body).jsonObject
            val content = json["content"]?.jsonArray?.get(0)
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(
                    message = "Unexpected response format", code = ErrorCode.PROVIDER_ERROR
                )
            AppResult.Success(content)
        } else {
            AppResult.Error(
                message = "API error: ${response.code}",
                code = ErrorCode.PROVIDER_ERROR
            )
        }
    } catch (e: Exception) {
        AppResult.Error(message = "Request failed: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
    }
}
```

#### Gemini Implementation

```kotlin
// In GeminiAdapter
override suspend fun generateSimpleCompletion(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    prompt: String,
    maxTokens: Int
): AppResult<String> = withContext(Dispatchers.IO) {
    try {
        val requestBody = Json.encodeToString(
            mapOf(
                "contents" to listOf(
                    mapOf(
                        "parts" to listOf(
                            mapOf("text" to prompt)
                        )
                    )
                ),
                "generationConfig" to mapOf(
                    "maxOutputTokens" to maxTokens
                )
            )
        )

        val url = "${apiBaseUrl.trimEnd('/')}/models/$modelId:generateContent?key=$apiKey"

        val request = Request.Builder()
            .url(url)
            .addHeader("Content-Type", "application/json")
            .post(requestBody.toRequestBody("application/json".toMediaTypeOrNull()))
            .build()

        val response = client.newCall(request).execute()

        if (response.isSuccessful) {
            val body = response.body?.string() ?: return@withContext AppResult.Error(
                message = "Empty response", code = ErrorCode.PROVIDER_ERROR
            )
            // Parse: response.candidates[0].content.parts[0].text
            val json = Json.parseToJsonElement(body).jsonObject
            val content = json["candidates"]?.jsonArray?.get(0)
                ?.jsonObject?.get("content")
                ?.jsonObject?.get("parts")
                ?.jsonArray?.get(0)
                ?.jsonObject?.get("text")
                ?.jsonPrimitive?.content
                ?: return@withContext AppResult.Error(
                    message = "Unexpected response format", code = ErrorCode.PROVIDER_ERROR
                )
            AppResult.Success(content)
        } else {
            AppResult.Error(
                message = "API error: ${response.code}",
                code = ErrorCode.PROVIDER_ERROR
            )
        }
    } catch (e: Exception) {
        AppResult.Error(message = "Request failed: ${e.message}", code = ErrorCode.UNKNOWN, exception = e)
    }
}
```

### CleanupSoftDeletedUseCase

```kotlin
/**
 * Hard-deletes all soft-deleted sessions. Called on app startup.
 *
 * Located in: feature/session/usecase/CleanupSoftDeletedUseCase.kt
 */
class CleanupSoftDeletedUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke() {
        sessionRepository.hardDeleteAllSoftDeleted()
    }
}
```

This is called in the Application class during startup:

```kotlin
// In OneclawApplication.kt
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { /* ... */ }

        // Cleanup any sessions that were soft-deleted but not hard-deleted
        // (e.g., app was killed during undo window)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val cleanup: CleanupSoftDeletedUseCase = get()
            cleanup()
        }
    }
}
```

## UI Layer

### Navigation

The session list is not a standalone screen. It lives inside the Navigation Drawer of the main chat screen. The drawer is opened by tapping the hamburger icon.

```kotlin
// No new routes needed for session list -- it's part of the drawer.
// Session rename is done via a dialog, not a separate route.
```

### UI State Definitions

#### SessionListUiState

```kotlin
data class SessionListUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val isLoading: Boolean = true,

    // Selection mode
    val isSelectionMode: Boolean = false,
    val selectedSessionIds: Set<String> = emptySet(),

    // Undo state
    val undoState: UndoState? = null,

    // Rename dialog
    val renameDialog: RenameDialogState? = null,

    // General
    val errorMessage: String? = null
)

data class SessionListItem(
    val id: String,
    val title: String,
    val agentName: String,             // Resolved from agent ID
    val lastMessagePreview: String?,
    val relativeTime: String,          // "2 min ago", "Yesterday", "Feb 20"
    val isActive: Boolean,             // Request in-flight
    val isSelected: Boolean            // In selection mode
)

data class UndoState(
    val deletedSessionIds: List<String>,
    val message: String                // "Session deleted" or "3 sessions deleted"
)

data class RenameDialogState(
    val sessionId: String,
    val currentTitle: String
)
```

### SessionListViewModel

```kotlin
class SessionListViewModel(
    private val sessionRepository: SessionRepository,
    private val agentRepository: AgentRepository,
    private val deleteSessionUseCase: DeleteSessionUseCase,
    private val batchDeleteSessionsUseCase: BatchDeleteSessionsUseCase,
    private val renameSessionUseCase: RenameSessionUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(SessionListUiState())
    val uiState: StateFlow<SessionListUiState> = _uiState.asStateFlow()

    // Job for the undo timeout -- cancelled if user taps undo
    private var undoJob: Job? = null

    // Cache of agent names for display
    private val agentNameCache = mutableMapOf<String, String>()

    init {
        loadSessions()
        loadAgentNames()
    }

    private fun loadSessions() {
        viewModelScope.launch {
            sessionRepository.getAllSessions().collect { sessions ->
                val items = sessions.map { session ->
                    SessionListItem(
                        id = session.id,
                        title = session.title,
                        agentName = agentNameCache[session.currentAgentId] ?: "Agent",
                        lastMessagePreview = session.lastMessagePreview,
                        relativeTime = formatRelativeTime(session.updatedAt),
                        isActive = session.isActive,
                        isSelected = session.id in _uiState.value.selectedSessionIds
                    )
                }
                _uiState.update { it.copy(sessions = items, isLoading = false) }
            }
        }
    }

    private fun loadAgentNames() {
        viewModelScope.launch {
            agentRepository.getAllAgents().collect { agents ->
                agentNameCache.clear()
                agents.forEach { agentNameCache[it.id] = it.name }
                // Re-emit sessions to update agent names
                _uiState.update { state ->
                    state.copy(
                        sessions = state.sessions.map { item ->
                            item.copy(agentName = agentNameCache[item.id] ?: item.agentName)
                        }
                    )
                }
            }
        }
    }

    // --- Delete ---

    fun deleteSession(sessionId: String) {
        viewModelScope.launch {
            when (deleteSessionUseCase(sessionId)) {
                is AppResult.Success -> {
                    startUndoTimer(listOf(sessionId), "Session deleted")
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to delete session.") }
                }
            }
        }
    }

    fun deleteSelectedSessions() {
        val ids = _uiState.value.selectedSessionIds.toList()
        if (ids.isEmpty()) return

        viewModelScope.launch {
            when (batchDeleteSessionsUseCase(ids)) {
                is AppResult.Success -> {
                    exitSelectionMode()
                    startUndoTimer(ids, "${ids.size} sessions deleted")
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to delete sessions.") }
                }
            }
        }
    }

    private fun startUndoTimer(deletedIds: List<String>, message: String) {
        // Cancel any existing undo timer
        undoJob?.cancel()

        _uiState.update {
            it.copy(undoState = UndoState(deletedSessionIds = deletedIds, message = message))
        }

        undoJob = viewModelScope.launch {
            delay(5_000)  // 5 second undo window
            // Undo window expired -- hard delete
            deletedIds.forEach { id ->
                sessionRepository.hardDeleteSession(id)
            }
            _uiState.update { it.copy(undoState = null) }
        }
    }

    fun undoDelete() {
        val undoState = _uiState.value.undoState ?: return
        undoJob?.cancel()

        viewModelScope.launch {
            if (undoState.deletedSessionIds.size == 1) {
                sessionRepository.restoreSession(undoState.deletedSessionIds.first())
            } else {
                sessionRepository.restoreSessions(undoState.deletedSessionIds)
            }
            _uiState.update { it.copy(undoState = null) }
        }
    }

    // --- Selection mode ---

    fun enterSelectionMode(sessionId: String) {
        _uiState.update {
            it.copy(
                isSelectionMode = true,
                selectedSessionIds = setOf(sessionId)
            )
        }
    }

    fun toggleSelection(sessionId: String) {
        _uiState.update { state ->
            val newSelection = if (sessionId in state.selectedSessionIds) {
                state.selectedSessionIds - sessionId
            } else {
                state.selectedSessionIds + sessionId
            }
            // Exit selection mode if nothing selected
            if (newSelection.isEmpty()) {
                state.copy(isSelectionMode = false, selectedSessionIds = emptySet())
            } else {
                state.copy(selectedSessionIds = newSelection)
            }
        }
    }

    fun selectAll() {
        _uiState.update { state ->
            state.copy(selectedSessionIds = state.sessions.map { it.id }.toSet())
        }
    }

    fun exitSelectionMode() {
        _uiState.update { it.copy(isSelectionMode = false, selectedSessionIds = emptySet()) }
    }

    // --- Rename ---

    fun showRenameDialog(sessionId: String, currentTitle: String) {
        _uiState.update {
            it.copy(renameDialog = RenameDialogState(sessionId, currentTitle))
        }
    }

    fun dismissRenameDialog() {
        _uiState.update { it.copy(renameDialog = null) }
    }

    fun renameSession(sessionId: String, newTitle: String) {
        viewModelScope.launch {
            when (renameSessionUseCase(sessionId, newTitle)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(renameDialog = null) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = "Failed to rename session.") }
                }
            }
        }
    }

    // --- Helpers ---

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * Format a timestamp into a relative time string.
     */
    private fun formatRelativeTime(timestamp: Long): String {
        val now = System.currentTimeMillis()
        val diff = now - timestamp

        return when {
            diff < 60_000 -> "Just now"
            diff < 3_600_000 -> "${diff / 60_000} min ago"
            diff < 86_400_000 -> {
                val hours = diff / 3_600_000
                if (hours == 1L) "1 hour ago" else "$hours hours ago"
            }
            diff < 172_800_000 -> "Yesterday"
            else -> {
                val sdf = java.text.SimpleDateFormat("MMM d", java.util.Locale.getDefault())
                sdf.format(java.util.Date(timestamp))
            }
        }
    }
}
```

### Screen Composable Outlines

#### SessionDrawerContent

The session list is rendered as the content of a `ModalNavigationDrawer`. It is part of the chat screen layout.

```kotlin
/**
 * Content of the Navigation Drawer showing the session list.
 * Located in: feature/session/SessionDrawerContent.kt
 */
@Composable
fun SessionDrawerContent(
    viewModel: SessionListViewModel,
    onNewConversation: () -> Unit,
    onSessionClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(modifier = modifier.fillMaxHeight()) {
        // "New conversation" button at top
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onNewConversation() }
                .padding(16.dp),
            tonalElevation = 1.dp,
            shape = MaterialTheme.shapes.medium
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "New conversation")
                Spacer(modifier = Modifier.width(12.dp))
                Text("New conversation", style = MaterialTheme.typography.bodyLarge)
            }
        }

        // Selection mode toolbar
        if (uiState.isSelectionMode) {
            SelectionToolbar(
                selectedCount = uiState.selectedSessionIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { viewModel.deleteSelectedSessions() },
                onCancel = { viewModel.exitSelectionMode() }
            )
        }

        // Session list
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else if (uiState.sessions.isEmpty()) {
            EmptySessionsMessage()
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(
                    items = uiState.sessions,
                    key = { it.id }
                ) { session ->
                    SwipeToDismissItem(
                        onDismiss = { viewModel.deleteSession(session.id) },
                        enabled = !uiState.isSelectionMode
                    ) {
                        SessionListItemRow(
                            item = session,
                            isSelectionMode = uiState.isSelectionMode,
                            onClick = {
                                if (uiState.isSelectionMode) {
                                    viewModel.toggleSelection(session.id)
                                } else {
                                    onSessionClick(session.id)
                                }
                            },
                            onLongClick = {
                                if (!uiState.isSelectionMode) {
                                    viewModel.enterSelectionMode(session.id)
                                }
                            },
                            onRename = {
                                viewModel.showRenameDialog(session.id, session.title)
                            }
                        )
                    }
                }
            }
        }
    }

    // Rename dialog
    uiState.renameDialog?.let { renameState ->
        RenameSessionDialog(
            currentTitle = renameState.currentTitle,
            onConfirm = { newTitle -> viewModel.renameSession(renameState.sessionId, newTitle) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }
}
```

#### Integration with Chat Screen

```kotlin
/**
 * In ChatScreen (RFC-001), the drawer wraps the chat content:
 */
@Composable
fun ChatScreen(/* ... */) {
    val drawerState = rememberDrawerState(DrawerValue.Closed)

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    viewModel = sessionListViewModel,
                    onNewConversation = { /* create new session */ },
                    onSessionClick = { sessionId -> /* navigate to session */ }
                )
            }
        }
    ) {
        // Chat content (messages, input, top bar with hamburger)
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { /* open drawer */ }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    // ...
                )
            }
        ) {
            // Chat messages and input
        }
    }

    // Undo Snackbar (shown at bottom of chat screen, not inside drawer)
    val undoState = sessionListViewModel.uiState.collectAsStateWithLifecycle().value.undoState
    if (undoState != null) {
        LaunchedEffect(undoState) {
            val result = snackbarHostState.showSnackbar(
                message = undoState.message,
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                sessionListViewModel.undoDelete()
            }
        }
    }
}
```

#### SessionListItemRow

```kotlin
@Composable
fun SessionListItemRow(
    item: SessionListItem,
    isSelectionMode: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onRename: () -> Unit
) {
    ListItem(
        headlineContent = {
            Text(
                text = item.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodyLarge
            )
        },
        supportingContent = {
            if (item.lastMessagePreview != null) {
                Text(
                    text = item.lastMessagePreview,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        trailingContent = {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = item.relativeTime,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                // Agent badge
                Surface(
                    shape = MaterialTheme.shapes.small,
                    color = MaterialTheme.colorScheme.secondaryContainer
                ) {
                    Text(
                        text = item.agentName,
                        style = MaterialTheme.typography.labelSmall,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }
        },
        leadingContent = if (isSelectionMode) {
            {
                Checkbox(
                    checked = item.isSelected,
                    onCheckedChange = { onClick() }
                )
            }
        } else if (item.isActive) {
            {
                // Pulsing dot indicator for active sessions
                ActiveIndicatorDot()
            }
        } else null,
        modifier = Modifier
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongClick
            )
    )
}
```

#### RenameSessionDialog

```kotlin
@Composable
fun RenameSessionDialog(
    currentTitle: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var title by remember { mutableStateOf(currentTitle) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Session") },
        text = {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(title) },
                enabled = title.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

## Koin Dependency Injection

```kotlin
// Additions to existing Koin modules

// DatabaseModule.kt
val databaseModule = module {
    // ... existing database setup ...
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    // ... other DAOs ...
}

// RepositoryModule.kt
val repositoryModule = module {
    single<SessionRepository> { SessionRepositoryImpl(get()) }  // SessionDao
    // ... other repositories ...
}

// FeatureModule.kt -- Session feature
val featureModule = module {
    // Session Use Cases
    factory { CreateSessionUseCase(get()) }                      // SessionRepository
    factory { DeleteSessionUseCase(get()) }                      // SessionRepository
    factory { BatchDeleteSessionsUseCase(get()) }                // SessionRepository
    factory { RenameSessionUseCase(get()) }                      // SessionRepository
    factory { GenerateTitleUseCase(get(), get(), get(), get()) }  // SessionRepository, ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory
    factory { CleanupSoftDeletedUseCase(get()) }                 // SessionRepository

    // Session ViewModel
    viewModel { SessionListViewModel(get(), get(), get(), get(), get()) }
    // SessionRepository, AgentRepository, DeleteSessionUseCase,
    // BatchDeleteSessionsUseCase, RenameSessionUseCase
}
```

## Data Flow Examples

### Flow 1: Lazy Session Creation + Title Generation

```
1. User opens app -> empty chat screen, no session in DB yet
   -> ChatViewModel holds pendingSession = null

2. User types "Help me write a Python script to parse CSV files" and taps send
   -> ChatViewModel: session does not exist yet
   -> CreateSessionUseCase(agentId = "agent-general-assistant")
      -> Creates Session in DB with title = "New Conversation"
      -> Returns session with generated ID
   -> ChatViewModel stores session ID

3. Immediately after session creation:
   -> GenerateTitleUseCase.generateTruncatedTitle("Help me write a Python script to parse CSV files")
      -> Truncates to "Help me write a Python script to parse..." (Phase 1)
   -> SessionRepository.updateTitle(sessionId, truncatedTitle)
   -> Session list in drawer shows: "Help me write a Python script to parse..."

4. Message is sent to AI model, streaming response arrives...

5. After first AI response is complete:
   -> GenerateTitleUseCase.generateAiTitle(
        sessionId = sessionId,
        firstUserMessage = "Help me write a Python script to parse CSV files",
        firstAiResponse = "I'll help you write a Python script...",
        currentModelId = "gpt-4o",
        currentProviderId = "provider-openai"
      )
   -> resolveLightweightModel():
      -> LIGHTWEIGHT_MODELS[OPENAI] = "gpt-4o-mini"
      -> Check: does "gpt-4o-mini" exist in models table for "provider-openai"? -> Yes
      -> Use "gpt-4o-mini"
   -> Build prompt and send to OpenAI
   -> Response: "Python CSV Parser Script"
   -> SessionRepository.setGeneratedTitle(sessionId, "Python CSV Parser Script")

6. Session list updates: title now shows "Python CSV Parser Script"
```

### Flow 2: Delete with Undo

```
1. User opens drawer, sees session list
2. User swipes left on "Python CSV Parser Script"
   -> SessionListViewModel.deleteSession(sessionId)
   -> DeleteSessionUseCase: softDeleteSession(sessionId)
      -> DB: UPDATE sessions SET deleted_at = now WHERE id = sessionId
   -> Session disappears from list (Flow filters out deleted_at IS NOT NULL)
   -> startUndoTimer(5 seconds)
   -> Snackbar appears: "Session deleted [Undo]"

3a. User taps "Undo" within 5 seconds:
   -> SessionListViewModel.undoDelete()
   -> undoJob cancelled
   -> SessionRepository.restoreSession(sessionId)
      -> DB: UPDATE sessions SET deleted_at = NULL WHERE id = sessionId
   -> Session reappears in list

3b. 5 seconds pass without undo:
   -> undoJob fires
   -> SessionRepository.hardDeleteSession(sessionId)
      -> DB: DELETE FROM sessions WHERE id = sessionId
      -> CASCADE: all messages for this session are deleted
   -> Snackbar dismissed
   -> Session permanently gone
```

### Flow 3: Batch Delete with Undo

```
1. User long-presses a session -> enters selection mode
2. User taps 3 more sessions to select (total 4)
3. User taps "Delete" in toolbar
   -> SessionListViewModel.deleteSelectedSessions()
   -> BatchDeleteSessionsUseCase([id1, id2, id3, id4])
      -> DB: UPDATE sessions SET deleted_at = now WHERE id IN (id1, id2, id3, id4)
   -> All 4 sessions disappear from list
   -> Exit selection mode
   -> Snackbar: "4 sessions deleted [Undo]"

4a. Undo -> all 4 restored
4b. Timeout -> all 4 hard deleted
```

### Flow 4: App Killed During Undo Window

```
1. User swipes to delete a session
2. Soft-delete applied, undo timer starts
3. App is killed (force stop, crash, etc.)

On next app launch:
1. OneclawApplication.onCreate()
2. CleanupSoftDeletedUseCase()
   -> SessionRepository.hardDeleteAllSoftDeleted()
   -> DB: DELETE FROM sessions WHERE deleted_at IS NOT NULL
3. Any sessions that were soft-deleted are now permanently gone
```

### Flow 5: Agent Deletion Fallback

```
1. User has 3 sessions using "Code Helper" agent
2. User deletes "Code Helper" agent (from RFC-002)
   -> DeleteAgentUseCase calls SessionRepository.updateAgentForSessions(
        oldAgentId = "code-helper-uuid",
        newAgentId = "agent-general-assistant"
      )
   -> DB: UPDATE sessions SET current_agent_id = 'agent-general-assistant'
          WHERE current_agent_id = 'code-helper-uuid'
3. All 3 sessions now reference General Assistant
4. Session list updates agent badges accordingly
```

## Error Handling

### Error Scenarios and User-Facing Messages

| Scenario | ErrorCode | User Message | UI Behavior |
|----------|-----------|--------------|-------------|
| Session creation fails (DB error) | STORAGE_ERROR | "Failed to create session. Please try again." | Error toast, message not sent |
| Session deletion fails (DB error) | STORAGE_ERROR | "Failed to delete session." | Error toast, session stays in list |
| Rename with empty title | VALIDATION_ERROR | "Session title cannot be empty." | Inline error in dialog |
| Rename with title too long | VALIDATION_ERROR | "Session title is too long (max 200 characters)." | Inline error in dialog |
| AI title generation fails | -- (silent) | -- | Truncated title kept, no error shown |
| AI title generation offline | -- (silent) | -- | Skipped entirely, truncated title kept |
| Session resume fails (corrupted) | STORAGE_ERROR | "This session could not be loaded." | Error dialog with option to delete |

### Title Generation Error Handling

AI title generation is designed to be resilient:
1. If the lightweight model doesn't exist in DB -> fall back to current model
2. If the API call fails -> keep truncated title silently
3. If the response is empty or unparseable -> keep truncated title
4. If the app is killed during generation -> truncated title persists (already saved)
5. No retries -- to avoid unnecessary API costs

## Implementation Steps

### Phase 1: Data Layer
1. [ ] Update `Session` domain model in `core/model/Session.kt` (add `lastMessagePreview`, `deletedAt`)
2. [ ] Create `SessionEntity` in `data/local/entity/SessionEntity.kt`
3. [ ] Create `SessionDao` in `data/local/dao/SessionDao.kt`
4. [ ] Create entity-domain mapper in `data/local/mapper/SessionMapper.kt`
5. [ ] Update `sessions` table schema in `AppDatabase` (add new columns + indexes)

### Phase 2: Repository
6. [ ] Update `SessionRepository` interface in `core/repository/SessionRepository.kt`
7. [ ] Implement `SessionRepositoryImpl` in `data/repository/SessionRepositoryImpl.kt`

### Phase 3: Title Generation
8. [ ] Add `generateSimpleCompletion()` to `ModelApiAdapter` interface
9. [ ] Implement `generateSimpleCompletion()` in `OpenAiAdapter`
10. [ ] Implement `generateSimpleCompletion()` in `AnthropicAdapter`
11. [ ] Implement `generateSimpleCompletion()` in `GeminiAdapter`
12. [ ] Implement `GenerateTitleUseCase` with Phase 1 (truncation) and Phase 2 (AI) logic
13. [ ] Implement lightweight model resolution with DB verification

### Phase 4: Use Cases
14. [ ] Implement `CreateSessionUseCase`
15. [ ] Implement `DeleteSessionUseCase`
16. [ ] Implement `BatchDeleteSessionsUseCase`
17. [ ] Implement `RenameSessionUseCase`
18. [ ] Implement `CleanupSoftDeletedUseCase`
19. [ ] Add cleanup call in `OneclawApplication.onCreate()`

### Phase 5: UI Layer
20. [ ] Create `SessionListUiState`, `SessionListItem`, `UndoState`, `RenameDialogState`
21. [ ] Implement `SessionListViewModel`
22. [ ] Implement `SessionDrawerContent` (Compose)
23. [ ] Implement `SessionListItemRow` with swipe-to-dismiss
24. [ ] Implement selection mode (long-press, checkboxes, toolbar)
25. [ ] Implement `RenameSessionDialog` (Compose)
26. [ ] Implement Snackbar undo integration
27. [ ] Integrate drawer with `ChatScreen` layout (ModalNavigationDrawer)

### Phase 6: DI & Integration
28. [ ] Update Koin modules (DatabaseModule, RepositoryModule, FeatureModule)
29. [ ] End-to-end testing: create session -> title gen -> rename -> delete -> undo
30. [ ] Test batch delete + undo
31. [ ] Test app restart cleanup of soft-deleted sessions
32. [ ] Test AI title generation with lightweight model resolution

## Testing Strategy

### Unit Tests
- `SessionRepositoryImpl`: CRUD, soft-delete/restore/hard-delete, agent fallback
- `CreateSessionUseCase`: Default agent, timestamp generation
- `DeleteSessionUseCase`: Soft-delete, not-found error
- `BatchDeleteSessionsUseCase`: Multiple IDs, empty list validation
- `RenameSessionUseCase`: Validation (empty, too long), successful rename
- `GenerateTitleUseCase.generateTruncatedTitle()`: Short message, long message at word boundary, no good word boundary
- `GenerateTitleUseCase.resolveLightweightModel()`: Model exists in DB, model doesn't exist, unknown provider type
- `SessionListViewModel`: Delete + undo flow, selection mode, rename
- Entity-domain mappers: All fields including nullable fields (`deletedAt`, `lastMessagePreview`)
- `formatRelativeTime()`: Various time deltas

### Integration Tests (Instrumented)
- Full session lifecycle: Create -> send message -> title generated -> rename -> delete -> undo -> hard delete
- Soft-delete cleanup on app restart
- CASCADE delete: Verify messages are deleted when session is hard-deleted
- AI title generation with real (mocked) API call
- Agent fallback: Delete agent -> verify sessions updated

### UI Tests
- Drawer opens on hamburger tap
- Session list renders correctly with all fields
- Swipe-to-delete animation
- Snackbar appears and undo works
- Selection mode: long-press enters, checkboxes appear, delete works
- Rename dialog: opens, validates, saves
- Empty state message when no sessions

### Edge Cases
- Create and immediately delete (before first message)
- Delete during active request (is_active = true)
- Rename to maximum length (200 chars)
- Very long session list (1000+ sessions) scroll performance
- Multiple rapid delete-undo cycles
- Delete session, undo, delete again
- AI title generation while offline
- App killed during AI title generation in-flight
- Batch delete all sessions, undo
- Session with 10,000+ messages (list render performance)

## Security Considerations

1. **No sensitive data in sessions**: Session metadata (title, agent ID, timestamps) is not sensitive.
2. **Message content in preview**: `lastMessagePreview` is truncated and stored in the sessions table. This avoids needing to query the messages table for list rendering, but the preview is still user content. Same security level as messages.
3. **AI title generation**: The first user message and AI response are sent to the model for title generation. This is the same model the user is already using, so no new data exposure.
4. **Soft-delete window**: During the ~5 second undo window, the session data is still in the DB (just marked). It is not encrypted differently during this window.

## Dependencies

### Depends On
- **RFC-000 (Overall Architecture)**: Session domain model, project structure, Room database
- **RFC-002 (Agent Management)**: `AgentConstants.GENERAL_ASSISTANT_ID` for default agent; `AgentRepository` for agent name lookup
- **RFC-003 (Provider Management)**: `ProviderRepository`, `ApiKeyStorage`, `ModelApiAdapterFactory` for AI title generation

### Depended On By
- **RFC-001 (Chat Interaction)**: Uses `CreateSessionUseCase` for lazy creation; uses `GenerateTitleUseCase` for title generation after first AI response; uses `SessionRepository` for message stats updates; integrates `SessionDrawerContent` in chat layout

## Differences from RFC-000

This RFC introduces the following changes that should be reflected in RFC-000:

1. **`Session` model updated**: Added `lastMessagePreview: String?`, `deletedAt: Long?`.
2. **`sessions` table updated**: Added `last_message_preview`, `deleted_at` columns + indexes.
3. **`SessionRepository` interface expanded**: Added soft-delete, restore, batch operations, rename, message stats, agent switch, active flag methods.
4. **`ModelApiAdapter` interface expanded**: Added `generateSimpleCompletion()` method.

## Open Questions

- [x] ~~Soft-delete implementation: DB field vs ViewModel-only?~~ **Decision: DB `deleted_at` field** for reliability across app restarts.
- [x] ~~AI title generation prompt?~~ **Decision: Simple prompt asking for 5-10 word title.**
- [x] ~~Lightweight model selection?~~ **Decision: Hardcoded mapping verified against DB; fallback to current model.**
- [ ] Should the drawer show date group headers ("Today", "Yesterday", "Last week")? The UI Design Spec shows them as optional. For V1, a flat list without date headers is simpler. Can be added later.
- [ ] Should session list support pull-to-refresh? Only useful when sync (FEAT-007) is implemented. Defer to FEAT-007.

## References

- [FEAT-005 PRD](../../prd/features/FEAT-005-session.md) -- Functional requirements
- [UI Design Spec](../../design/ui-design-spec.md) -- Navigation Drawer layout, Session list items
- [RFC-000 Overall Architecture](../architecture/RFC-000-overall-architecture.md) -- Session model, DB schema
- [RFC-002 Agent Management](RFC-002-agent-management.md) -- Agent fallback, AgentConstants
- [RFC-003 Provider Management](RFC-003-provider-management.md) -- ModelApiAdapter, ProviderRepository

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
