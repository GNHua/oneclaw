# RFC-005: 会话管理

## 文档信息
- **RFC 编号**: RFC-005
- **关联 PRD**: [FEAT-005 (会话管理)](../../prd/features/FEAT-005-session-zh.md)
- **关联设计**: [UI 设计规范](../../design/ui-design-spec-zh.md)（导航抽屉、第 1 节聊天界面空状态）
- **关联架构**: [RFC-000 (总体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **依赖**: [RFC-002 (Agent 管理)](RFC-002-agent-management-zh.md)、[RFC-003 (Provider 管理)](RFC-003-provider-management-zh.md)
- **被依赖**: RFC-001 (对话交互)
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
会话管理提供了创建、恢复、重命名和删除对话会话的基础设施。会话是对话的容器——它保存消息历史、当前 Agent 引用以及标题和时间戳等元数据。会话在导航抽屉中按最后活跃时间排序显示。本 RFC 涵盖会话数据持久化、延迟会话创建、两阶段标题生成（截断 + AI 生成）、软删除与撤销、批量删除以及抽屉中的会话列表 UI。

### 目标
1. 实现会话数据持久化（Room 实体、DAO、Repository），支持软删除
2. 实现延迟会话创建（首条消息发送前不创建 DB 记录）
3. 实现两阶段标题生成（即时截断 + 异步 AI 生成）
4. 实现轻量级模型选择及可用性验证，用于 AI 标题生成
5. 实现单条删除（滑动删除 + Snackbar 撤销，约 5 秒窗口）
6. 实现批量删除（选择模式 + Snackbar 撤销）
7. 实现会话重命名（手动编辑标题）
8. 实现导航抽屉中的会话列表 UI
9. 实现 `updateAgentForSessions()`，用于 Agent 删除回退（来自 RFC-002）
10. 提供足够的实现细节以支持 AI 辅助代码生成

### 非目标
- 聊天中的消息显示和渲染（RFC-001）
- 发送消息和流式响应（RFC-001）
- 会话内的 Agent 切换（RFC-001 集成 RFC-002 的 AgentSelectorSheet）
- 会话文件夹、标签或分组
- 会话搜索
- 会话置顶
- 会话导出
- 会话分享
- 数据同步 / 备份（FEAT-007）

## 技术方案

### 架构概览

```
+-----------------------------------------------------------------------+
|                           UI 层                                        |
|  SessionDrawerContent    SessionListViewModel    RenameDialog          |
|       |                       |                                        |
|       v                       v                                        |
|  (抽屉 composable)       SessionListViewModel                          |
+-----------------------------------------------------------------------+
|                         领域层                                          |
|  CreateSessionUseCase  DeleteSessionUseCase  BatchDeleteSessionsUseCase|
|  GenerateTitleUseCase  RenameSessionUseCase  CleanupSoftDeletedUseCase |
|       |                                                                |
|       v                                                                |
|  SessionRepository (接口)   MessageRepository (接口)                   |
+-----------------------------------------------------------------------+
|                          数据层                                         |
|  SessionRepositoryImpl                                                  |
|       |              |                |                                 |
|       v              v                v                                 |
|  SessionDao     MessageDao     ModelApiAdapterFactory                  |
|                                (用于 AI 标题生成)                      |
+-----------------------------------------------------------------------+
```

### 核心组件

1. **SessionRepositoryImpl**
   - 职责：会话 CRUD、软删除/恢复/硬删除、Agent 回退
   - 依赖：SessionDao、MessageDao

2. **延迟会话创建**
   - 职责：会话在首条消息发送前不持久化到数据库
   - 机制：ChatViewModel 持有一个内存中的"待创建会话"对象；`CreateSessionUseCase` 在首条消息发送时持久化它
   - 详见数据流部分

3. **GenerateTitleUseCase**
   - 职责：两阶段标题生成
   - 第一阶段：将首条用户消息截断到约 50 个字符（词边界处）
   - 第二阶段：异步请求轻量级模型生成更好的标题
   - 轻量级模型：硬编码映射，通过 DB 验证可用性

4. **软删除管理器**
   - 职责：标记会话为已删除，在撤销窗口后安排硬删除
   - 机制：sessions 表中的 `deleted_at` 字段 + `viewModelScope.launch` 配合 delay
   - 清理：应用启动时，硬删除所有 `deleted_at != null` 的会话

5. **SessionDrawerContent**
   - 职责：组合导航抽屉中显示会话列表的内容
   - 集成：滑动删除、选择模式、重命名对话框

## 数据模型

### 领域模型

`Session` 模型基于 RFC-000 更新，添加了 `deletedAt` 和 `lastMessagePreview`。

#### Session（已更新）

```kotlin
data class Session(
    val id: String,                    // UUID
    val title: String,                 // 显示标题
    val currentAgentId: String,        // 当前会话的 Agent
    val messageCount: Int,             // 消息数量
    val lastMessagePreview: String?,   // 截断的最后一条消息文本，用于列表显示
    val isActive: Boolean,             // 是否有进行中的请求
    val deletedAt: Long?,              // 软删除时间戳（null = 未删除）
    val createdAt: Long,
    val updatedAt: Long                // 最后活跃时间戳
)
```

**相对 RFC-000 的变更：**
- 添加 `lastMessagePreview`：截断的最后一条消息文本，用于抽屉中的会话列表显示。避免为列表渲染而关联消息表。
- 添加 `deletedAt`：可空时间戳，用于软删除支持。非空时，会话等待永久删除。

### Room 实体

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

### 数据库 Schema

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

**相对 RFC-000 schema 的变更：**
- 添加 `last_message_preview TEXT`
- 添加 `deleted_at INTEGER`（可空）
- 添加 `updated_at`（排序）和 `deleted_at`（过滤）的索引

### 实体-领域映射器

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

## DAO 接口

### SessionDao

```kotlin
@Dao
interface SessionDao {

    /**
     * 获取所有未删除的会话，按 updated_at 降序排列（最近优先）。
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
     * 软删除：设置 deleted_at 时间戳。
     */
    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id = :id")
    suspend fun softDeleteSession(id: String, deletedAt: Long)

    /**
     * 批量软删除。
     */
    @Query("UPDATE sessions SET deleted_at = :deletedAt WHERE id IN (:ids)")
    suspend fun softDeleteSessions(ids: List<String>, deletedAt: Long)

    /**
     * 恢复一个软删除的会话。
     */
    @Query("UPDATE sessions SET deleted_at = NULL WHERE id = :id")
    suspend fun restoreSession(id: String)

    /**
     * 批量恢复软删除的会话。
     */
    @Query("UPDATE sessions SET deleted_at = NULL WHERE id IN (:ids)")
    suspend fun restoreSessions(ids: List<String>)

    /**
     * 硬删除单个会话。CASCADE 删除其消息。
     */
    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun hardDeleteSession(id: String)

    /**
     * 硬删除所有软删除的会话（应用启动时清理）。
     */
    @Query("DELETE FROM sessions WHERE deleted_at IS NOT NULL")
    suspend fun hardDeleteAllSoftDeleted()

    /**
     * 更新所有引用特定 Agent 的会话的当前 Agent。
     * 用于 DeleteAgentUseCase（RFC-002），回退到通用助手。
     */
    @Query("UPDATE sessions SET current_agent_id = :newAgentId WHERE current_agent_id = :oldAgentId")
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)

    /**
     * 更新会话标题。
     */
    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateTitle(id: String, title: String, updatedAt: Long)

    /**
     * 设置 AI 生成的标题。
     */
    @Query("UPDATE sessions SET title = :title, updated_at = :updatedAt WHERE id = :id")
    suspend fun setGeneratedTitle(id: String, title: String, updatedAt: Long)

    /**
     * 更新消息数量和最后消息预览。
     */
    @Query("UPDATE sessions SET message_count = :count, last_message_preview = :preview, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateMessageStats(id: String, count: Int, preview: String?, updatedAt: Long)

    /**
     * 设置 is_active 标志（请求进行中）。
     */
    @Query("UPDATE sessions SET is_active = :isActive WHERE id = :id")
    suspend fun setActive(id: String, isActive: Boolean)

    /**
     * 更新特定会话的当前 Agent。
     */
    @Query("UPDATE sessions SET current_agent_id = :agentId, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateCurrentAgent(id: String, agentId: String, updatedAt: Long)

    /**
     * 获取所有会话数量（用于统计）。
     */
    @Query("SELECT COUNT(*) FROM sessions WHERE deleted_at IS NULL")
    suspend fun getSessionCount(): Int
}
```

## Repository 实现

### SessionRepository 接口

基于 RFC-000 更新，添加了软删除、恢复、批量操作和 Agent 回退。

```kotlin
interface SessionRepository {
    /**
     * 观察所有未删除的会话，按 updated_at 降序排列。
     */
    fun getAllSessions(): Flow<List<Session>>

    suspend fun getSessionById(id: String): Session?

    /**
     * 创建新会话。在首条消息发送时调用（延迟创建）。
     */
    suspend fun createSession(session: Session): Session

    suspend fun updateSession(session: Session)

    /**
     * 软删除会话。将 deleted_at 设为当前时间。
     * 会话从列表中隐藏但可以恢复。
     */
    suspend fun softDeleteSession(id: String)

    /**
     * 批量软删除会话。
     */
    suspend fun softDeleteSessions(ids: List<String>)

    /**
     * 恢复软删除的会话（撤销）。
     */
    suspend fun restoreSession(id: String)

    /**
     * 批量恢复软删除的会话（批量撤销）。
     */
    suspend fun restoreSessions(ids: List<String>)

    /**
     * 硬删除会话及其所有消息。
     */
    suspend fun hardDeleteSession(id: String)

    /**
     * 硬删除所有软删除的会话。在应用启动时调用。
     */
    suspend fun hardDeleteAllSoftDeleted()

    /**
     * 将所有引用 oldAgentId 的会话更新为使用 newAgentId。
     * 用于 DeleteAgentUseCase（RFC-002）。
     */
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)

    /**
     * 更新会话标题。
     */
    suspend fun updateTitle(id: String, title: String)

    /**
     * 设置 AI 生成的标题并标记为已生成。
     */
    suspend fun setGeneratedTitle(id: String, title: String)

    /**
     * 更新消息统计（数量 + 最后消息预览）。
     */
    suspend fun updateMessageStats(id: String, count: Int, preview: String?)

    /**
     * 设置会话的活跃/非活跃状态（请求进行中）。
     */
    suspend fun setActive(id: String, isActive: Boolean)

    /**
     * 切换会话的当前 Agent。
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
        // 消息通过外键 CASCADE 删除
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
 * 在数据库中创建新会话。在首条消息发送时调用。
 * 实现延迟会话创建：在此调用之前会话不存在于 DB 中。
 *
 * 位于：feature/session/usecase/CreateSessionUseCase.kt
 */
class CreateSessionUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(
        agentId: String = AgentConstants.GENERAL_ASSISTANT_ID
    ): Session {
        val session = Session(
            id = "",                // 由 repository 生成
            title = "New Conversation",   // 占位符，被第一阶段标题替换
            currentAgentId = agentId,
            messageCount = 0,
            lastMessagePreview = null,
            isActive = false,
            deletedAt = null,
            createdAt = 0,          // 由 repository 设置
            updatedAt = 0           // 由 repository 设置
        )
        return sessionRepository.createSession(session)
    }
}
```

### DeleteSessionUseCase

```kotlin
/**
 * 软删除单个会话。返回会话 ID 供撤销引用。
 *
 * 位于：feature/session/usecase/DeleteSessionUseCase.kt
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
 * 批量软删除多个会话。
 *
 * 位于：feature/session/usecase/BatchDeleteSessionsUseCase.kt
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
 * 重命名会话标题。验证标题非空。
 *
 * 位于：feature/session/usecase/RenameSessionUseCase.kt
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
 * 会话的两阶段标题生成。
 *
 * 第一阶段：从首条用户消息即时截断生成标题。
 * 第二阶段：使用轻量级模型异步生成 AI 标题。
 *
 * 位于：feature/session/usecase/GenerateTitleUseCase.kt
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
         * 每种 provider 类型的硬编码轻量级模型 ID。
         * 使用前会通过 DB 验证。
         */
        private val LIGHTWEIGHT_MODELS = mapOf(
            ProviderType.OPENAI to "gpt-4o-mini",
            ProviderType.ANTHROPIC to "claude-haiku-4-20250414",
            ProviderType.GEMINI to "gemini-2.0-flash"
        )
    }

    /**
     * 第一阶段：从首条用户消息生成截断标题。
     * 在首条消息发送时立即调用。
     * 这是同步且即时的。
     */
    fun generateTruncatedTitle(firstMessage: String): String {
        val trimmed = firstMessage.trim()
        if (trimmed.length <= TRUNCATED_TITLE_MAX_LENGTH) {
            return trimmed
        }

        // 在词边界处截断
        val truncated = trimmed.substring(0, TRUNCATED_TITLE_MAX_LENGTH)
        val lastSpace = truncated.lastIndexOf(' ')
        return if (lastSpace > TRUNCATED_TITLE_MAX_LENGTH / 2) {
            // 找到合理的词边界
            truncated.substring(0, lastSpace) + "..."
        } else {
            // 没有合适的词边界，直接截断
            truncated + "..."
        }
    }

    /**
     * 第二阶段：使用轻量级模型生成 AI 标题。
     * 在首条 AI 响应完成后异步调用。
     * 
     * @param sessionId 要更新的会话
     * @param firstUserMessage 用户的首条消息
     * @param firstAiResponse AI 的首条响应文本
     * @param currentModelId 对话使用的模型
     * @param currentProviderId 对话使用的 provider
     */
    suspend fun generateAiTitle(
        sessionId: String,
        firstUserMessage: String,
        firstAiResponse: String,
        currentModelId: String,
        currentProviderId: String
    ) {
        try {
            // 解析用于标题生成的 provider 和模型
            val provider = providerRepository.getProviderById(currentProviderId) ?: return
            val apiKey = apiKeyStorage.getApiKey(currentProviderId) ?: return
            val modelId = resolveLightweightModel(provider, currentModelId)

            // 构建标题生成 prompt
            val truncatedResponse = if (firstAiResponse.length > AI_RESPONSE_CONTEXT_MAX_LENGTH) {
                firstAiResponse.substring(0, AI_RESPONSE_CONTEXT_MAX_LENGTH) + "..."
            } else {
                firstAiResponse
            }

            val prompt = buildTitlePrompt(firstUserMessage, truncatedResponse)

            // 通过相应的 adapter 发送请求
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
                        .removeSurrounding("\"")  // 如果模型用引号包裹则移除
                        .take(200)                 // 安全限制
                    if (generatedTitle.isNotBlank()) {
                        sessionRepository.setGeneratedTitle(sessionId, generatedTitle)
                    }
                }
                is AppResult.Error -> {
                    // 静默失败——保留截断标题
                    // 不重试以避免不必要的 API 费用
                }
            }
        } catch (e: Exception) {
            // 静默失败
        }
    }

    /**
     * 解析用于标题生成的模型。
     * 
     * 策略：
     * 1. 查找此 provider 类型的硬编码轻量级模型
     * 2. 检查该模型是否实际存在于此 provider 的 DB 中
     * 3. 如果存在则使用；否则回退到当前对话模型
     */
    private suspend fun resolveLightweightModel(
        provider: Provider,
        currentModelId: String
    ): String {
        val lightweightId = LIGHTWEIGHT_MODELS[provider.type] ?: return currentModelId

        // 验证轻量级模型是否确实存在于此 provider
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

### ModelApiAdapter 扩展

`generateSimpleCompletion` 方法是 `ModelApiAdapter` 接口的新增方法，用于非流式单响应请求。它被标题生成使用，也可能被其他简单 API 调用使用。

```kotlin
// 添加到 ModelApiAdapter 接口 (data/remote/adapter/ModelApiAdapter.kt)
interface ModelApiAdapter {
    // ... RFC-003 中的现有方法 ...

    /**
     * 发送简单（非流式）聊天补全请求。
     * 用于标题生成等轻量级任务。
     * 
     * 实现：在 RFC-005 中用于标题生成。
     * 完整流式实现在 RFC-001 中。
     *
     * @param prompt 要发送的用户消息
     * @param maxTokens 响应中的最大 token 数
     * @return 助手的文本响应
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

#### OpenAI 实现

```kotlin
// 在 OpenAiAdapter 中
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
            // 解析：response.choices[0].message.content
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

#### Anthropic 实现

```kotlin
// 在 AnthropicAdapter 中
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
            // 解析：response.content[0].text
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

#### Gemini 实现

```kotlin
// 在 GeminiAdapter 中
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
            // 解析：response.candidates[0].content.parts[0].text
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
 * 硬删除所有软删除的会话。在应用启动时调用。
 *
 * 位于：feature/session/usecase/CleanupSoftDeletedUseCase.kt
 */
class CleanupSoftDeletedUseCase(
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke() {
        sessionRepository.hardDeleteAllSoftDeleted()
    }
}
```

在 Application 类的启动中调用：

```kotlin
// 在 OneclawApplication.kt 中
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { /* ... */ }

        // 清理已软删除但未硬删除的会话
        // （例如，应用在撤销窗口期间被杀死）
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val cleanup: CleanupSoftDeletedUseCase = get()
            cleanup()
        }
    }
}
```

## UI 层

### 导航

会话列表不是独立的界面。它位于主聊天界面的导航抽屉中。通过点击汉堡菜单图标打开抽屉。

```kotlin
// 会话列表不需要新的路由——它是抽屉的一部分。
// 会话重命名通过对话框完成，不是独立路由。
```

### UI State 定义

#### SessionListUiState

```kotlin
data class SessionListUiState(
    val sessions: List<SessionListItem> = emptyList(),
    val isLoading: Boolean = true,

    // 选择模式
    val isSelectionMode: Boolean = false,
    val selectedSessionIds: Set<String> = emptySet(),

    // 撤销状态
    val undoState: UndoState? = null,

    // 重命名对话框
    val renameDialog: RenameDialogState? = null,

    // 通用
    val errorMessage: String? = null
)

data class SessionListItem(
    val id: String,
    val title: String,
    val agentName: String,             // 从 agent ID 解析
    val lastMessagePreview: String?,
    val relativeTime: String,          // "2 min ago"、"Yesterday"、"Feb 20"
    val isActive: Boolean,             // 请求进行中
    val isSelected: Boolean            // 选择模式中
)

data class UndoState(
    val deletedSessionIds: List<String>,
    val message: String                // "Session deleted" 或 "3 sessions deleted"
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

    // 撤销超时的 Job——如果用户点击撤销则取消
    private var undoJob: Job? = null

    // Agent 名称缓存，用于显示
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
                // 重新发射会话以更新 agent 名称
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

    // --- 删除 ---

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
        // 取消任何现有的撤销计时器
        undoJob?.cancel()

        _uiState.update {
            it.copy(undoState = UndoState(deletedSessionIds = deletedIds, message = message))
        }

        undoJob = viewModelScope.launch {
            delay(5_000)  // 5 秒撤销窗口
            // 撤销窗口到期——硬删除
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

    // --- 选择模式 ---

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
            // 如果没有选中项则退出选择模式
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

    // --- 重命名 ---

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

    // --- 辅助方法 ---

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    /**
     * 将时间戳格式化为相对时间字符串。
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

### 界面 Composable 大纲

#### SessionDrawerContent

会话列表作为 `ModalNavigationDrawer` 的内容渲染。它是聊天界面布局的一部分。

```kotlin
/**
 * 导航抽屉的内容，显示会话列表。
 * 位于：feature/session/SessionDrawerContent.kt
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
        // 顶部"新对话"按钮
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

        // 选择模式工具栏
        if (uiState.isSelectionMode) {
            SelectionToolbar(
                selectedCount = uiState.selectedSessionIds.size,
                onSelectAll = { viewModel.selectAll() },
                onDelete = { viewModel.deleteSelectedSessions() },
                onCancel = { viewModel.exitSelectionMode() }
            )
        }

        // 会话列表
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

    // 重命名对话框
    uiState.renameDialog?.let { renameState ->
        RenameSessionDialog(
            currentTitle = renameState.currentTitle,
            onConfirm = { newTitle -> viewModel.renameSession(renameState.sessionId, newTitle) },
            onDismiss = { viewModel.dismissRenameDialog() }
        )
    }
}
```

#### 与聊天界面集成

```kotlin
/**
 * 在 ChatScreen（RFC-001）中，抽屉包裹聊天内容：
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
                    onNewConversation = { /* 创建新会话 */ },
                    onSessionClick = { sessionId -> /* 导航到会话 */ }
                )
            }
        }
    ) {
        // 聊天内容（消息、输入、带汉堡菜单的顶部栏）
        Scaffold(
            topBar = {
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { /* 打开抽屉 */ }) {
                            Icon(Icons.Default.Menu, "Menu")
                        }
                    },
                    // ...
                )
            }
        ) {
            // 聊天消息和输入
        }
    }

    // 撤销 Snackbar（显示在聊天界面底部，不在抽屉内）
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
                // Agent 标签
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
                // 活跃会话的脉动点指示器
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

## Koin 依赖注入

```kotlin
// 添加到现有 Koin 模块

// DatabaseModule.kt
val databaseModule = module {
    // ... 现有数据库设置 ...
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    // ... 其他 DAO ...
}

// RepositoryModule.kt
val repositoryModule = module {
    single<SessionRepository> { SessionRepositoryImpl(get()) }  // SessionDao
    // ... 其他 repository ...
}

// FeatureModule.kt -- Session 功能
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

## 数据流示例

### 流程 1：延迟会话创建 + 标题生成

```
1. 用户打开应用 -> 空聊天界面，DB 中没有会话
   -> ChatViewModel 持有 pendingSession = null

2. 用户输入"Help me write a Python script to parse CSV files"并点击发送
   -> ChatViewModel：会话尚不存在
   -> CreateSessionUseCase(agentId = "agent-general-assistant")
      -> 在 DB 中创建 Session，title = "New Conversation"
      -> 返回带有生成 ID 的 session
   -> ChatViewModel 存储 session ID

3. 会话创建后立即执行：
   -> GenerateTitleUseCase.generateTruncatedTitle("Help me write a Python script to parse CSV files")
      -> 截断为 "Help me write a Python script to parse..."（第一阶段）
   -> SessionRepository.updateTitle(sessionId, truncatedTitle)
   -> 抽屉中的会话列表显示："Help me write a Python script to parse..."

4. 消息发送到 AI 模型，流式响应到达...

5. 首条 AI 响应完成后：
   -> GenerateTitleUseCase.generateAiTitle(
        sessionId = sessionId,
        firstUserMessage = "Help me write a Python script to parse CSV files",
        firstAiResponse = "I'll help you write a Python script...",
        currentModelId = "gpt-4o",
        currentProviderId = "provider-openai"
      )
   -> resolveLightweightModel()：
      -> LIGHTWEIGHT_MODELS[OPENAI] = "gpt-4o-mini"
      -> 检查："gpt-4o-mini" 是否存在于 "provider-openai" 的 models 表中？-> 是
      -> 使用 "gpt-4o-mini"
   -> 构建 prompt 并发送到 OpenAI
   -> 响应："Python CSV Parser Script"
   -> SessionRepository.setGeneratedTitle(sessionId, "Python CSV Parser Script")

6. 会话列表更新：标题现在显示 "Python CSV Parser Script"
```

### 流程 2：删除与撤销

```
1. 用户打开抽屉，看到会话列表
2. 用户向左滑动 "Python CSV Parser Script"
   -> SessionListViewModel.deleteSession(sessionId)
   -> DeleteSessionUseCase：softDeleteSession(sessionId)
      -> DB：UPDATE sessions SET deleted_at = now WHERE id = sessionId
   -> 会话从列表消失（Flow 过滤掉 deleted_at IS NOT NULL）
   -> startUndoTimer(5 秒)
   -> Snackbar 出现："Session deleted [Undo]"

3a. 用户在 5 秒内点击 "Undo"：
   -> SessionListViewModel.undoDelete()
   -> undoJob 被取消
   -> SessionRepository.restoreSession(sessionId)
      -> DB：UPDATE sessions SET deleted_at = NULL WHERE id = sessionId
   -> 会话重新出现在列表中

3b. 5 秒过去没有撤销：
   -> undoJob 触发
   -> SessionRepository.hardDeleteSession(sessionId)
      -> DB：DELETE FROM sessions WHERE id = sessionId
      -> CASCADE：此会话的所有消息被删除
   -> Snackbar 消失
   -> 会话永久删除
```

### 流程 3：批量删除与撤销

```
1. 用户长按一个会话 -> 进入选择模式
2. 用户点击另外 3 个会话进行选择（共 4 个）
3. 用户点击工具栏中的"删除"
   -> SessionListViewModel.deleteSelectedSessions()
   -> BatchDeleteSessionsUseCase([id1, id2, id3, id4])
      -> DB：UPDATE sessions SET deleted_at = now WHERE id IN (id1, id2, id3, id4)
   -> 所有 4 个会话从列表消失
   -> 退出选择模式
   -> Snackbar："4 sessions deleted [Undo]"

4a. 撤销 -> 所有 4 个恢复
4b. 超时 -> 所有 4 个硬删除
```

### 流程 4：应用在撤销窗口期间被杀死

```
1. 用户滑动删除一个会话
2. 软删除已应用，撤销计时器开始
3. 应用被杀死（强制停止、崩溃等）

下次启动应用时：
1. OneclawApplication.onCreate()
2. CleanupSoftDeletedUseCase()
   -> SessionRepository.hardDeleteAllSoftDeleted()
   -> DB：DELETE FROM sessions WHERE deleted_at IS NOT NULL
3. 任何已软删除的会话现在被永久删除
```

### 流程 5：Agent 删除回退

```
1. 用户有 3 个使用"代码助手"Agent 的会话
2. 用户删除"代码助手"Agent（来自 RFC-002）
   -> DeleteAgentUseCase 调用 SessionRepository.updateAgentForSessions(
        oldAgentId = "code-helper-uuid",
        newAgentId = "agent-general-assistant"
      )
   -> DB：UPDATE sessions SET current_agent_id = 'agent-general-assistant'
          WHERE current_agent_id = 'code-helper-uuid'
3. 所有 3 个会话现在引用通用助手
4. 会话列表相应更新 Agent 标签
```

## 错误处理

### 错误场景和用户可见消息

| 场景 | ErrorCode | 用户消息 | UI 行为 |
|------|-----------|----------|---------|
| 会话创建失败（DB 错误） | STORAGE_ERROR | "Failed to create session. Please try again." | 错误 toast，消息未发送 |
| 会话删除失败（DB 错误） | STORAGE_ERROR | "Failed to delete session." | 错误 toast，会话留在列表中 |
| 重命名时标题为空 | VALIDATION_ERROR | "Session title cannot be empty." | 对话框中的内联错误 |
| 重命名时标题过长 | VALIDATION_ERROR | "Session title is too long (max 200 characters)." | 对话框中的内联错误 |
| AI 标题生成失败 | --（静默） | -- | 保留截断标题，不显示错误 |
| AI 标题生成时离线 | --（静默） | -- | 完全跳过，保留截断标题 |
| 会话恢复失败（损坏） | STORAGE_ERROR | "This session could not be loaded." | 错误对话框，带删除选项 |

### 标题生成错误处理

AI 标题生成设计为高容错性：
1. 如果轻量级模型在 DB 中不存在 -> 回退到当前模型
2. 如果 API 调用失败 -> 静默保留截断标题
3. 如果响应为空或无法解析 -> 保留截断标题
4. 如果应用在生成期间被杀死 -> 截断标题已保存，继续使用
5. 不重试——避免不必要的 API 费用

## 实施步骤

### 阶段 1：数据层
1. [ ] 更新 `core/model/Session.kt` 中的 `Session` 领域模型（添加 `lastMessagePreview`、`deletedAt`）
2. [ ] 在 `data/local/entity/SessionEntity.kt` 中创建 `SessionEntity`
3. [ ] 在 `data/local/dao/SessionDao.kt` 中创建 `SessionDao`
4. [ ] 在 `data/local/mapper/SessionMapper.kt` 中创建实体-领域映射器
5. [ ] 更新 `AppDatabase` 中的 `sessions` 表 schema（添加新列 + 索引）

### 阶段 2：Repository
6. [ ] 更新 `core/repository/SessionRepository.kt` 中的 `SessionRepository` 接口
7. [ ] 在 `data/repository/SessionRepositoryImpl.kt` 中实现 `SessionRepositoryImpl`

### 阶段 3：标题生成
8. [ ] 向 `ModelApiAdapter` 接口添加 `generateSimpleCompletion()`
9. [ ] 在 `OpenAiAdapter` 中实现 `generateSimpleCompletion()`
10. [ ] 在 `AnthropicAdapter` 中实现 `generateSimpleCompletion()`
11. [ ] 在 `GeminiAdapter` 中实现 `generateSimpleCompletion()`
12. [ ] 实现 `GenerateTitleUseCase`，包含第一阶段（截断）和第二阶段（AI）逻辑
13. [ ] 实现轻量级模型解析及 DB 验证

### 阶段 4：Use Cases
14. [ ] 实现 `CreateSessionUseCase`
15. [ ] 实现 `DeleteSessionUseCase`
16. [ ] 实现 `BatchDeleteSessionsUseCase`
17. [ ] 实现 `RenameSessionUseCase`
18. [ ] 实现 `CleanupSoftDeletedUseCase`
19. [ ] 在 `OneclawApplication.onCreate()` 中添加清理调用

### 阶段 5：UI 层
20. [ ] 创建 `SessionListUiState`、`SessionListItem`、`UndoState`、`RenameDialogState`
21. [ ] 实现 `SessionListViewModel`
22. [ ] 实现 `SessionDrawerContent`（Compose）
23. [ ] 实现带滑动删除的 `SessionListItemRow`
24. [ ] 实现选择模式（长按、复选框、工具栏）
25. [ ] 实现 `RenameSessionDialog`（Compose）
26. [ ] 实现 Snackbar 撤销集成
27. [ ] 将抽屉与 `ChatScreen` 布局集成（ModalNavigationDrawer）

### 阶段 6：DI 与集成
28. [ ] 更新 Koin 模块（DatabaseModule、RepositoryModule、FeatureModule）
29. [ ] 端到端测试：创建会话 -> 标题生成 -> 重命名 -> 删除 -> 撤销
30. [ ] 测试批量删除 + 撤销
31. [ ] 测试应用重启清理软删除的会话
32. [ ] 测试 AI 标题生成与轻量级模型解析

## 测试策略

### 单元测试
- `SessionRepositoryImpl`：CRUD、软删除/恢复/硬删除、Agent 回退
- `CreateSessionUseCase`：默认 agent、时间戳生成
- `DeleteSessionUseCase`：软删除、未找到错误
- `BatchDeleteSessionsUseCase`：多个 ID、空列表验证
- `RenameSessionUseCase`：验证（空、过长）、成功重命名
- `GenerateTitleUseCase.generateTruncatedTitle()`：短消息、长消息在词边界处、没有好的词边界
- `GenerateTitleUseCase.resolveLightweightModel()`：模型在 DB 中存在、模型不存在、未知 provider 类型
- `SessionListViewModel`：删除 + 撤销流程、选择模式、重命名
- 实体-领域映射器：所有字段，包括可空字段（`deletedAt`、`lastMessagePreview`）
- `formatRelativeTime()`：各种时间差

### 集成测试（Instrumented）
- 完整会话生命周期：创建 -> 发送消息 -> 生成标题 -> 重命名 -> 删除 -> 撤销 -> 硬删除
- 应用重启时的软删除清理
- CASCADE 删除：验证会话硬删除时消息也被删除
- 使用真实（mocked）API 调用的 AI 标题生成
- Agent 回退：删除 agent -> 验证会话已更新

### UI 测试
- 点击汉堡菜单打开抽屉
- 会话列表正确渲染所有字段
- 滑动删除动画
- Snackbar 出现且撤销有效
- 选择模式：长按进入、复选框出现、删除有效
- 重命名对话框：打开、验证、保存
- 无会话时的空状态消息

### 边界情况
- 创建后立即删除（在首条消息之前）
- 在活跃请求期间删除（is_active = true）
- 重命名为最大长度（200 字符）
- 非常长的会话列表（1000+ 会话）滚动性能
- 多次快速删除-撤销循环
- 删除会话、撤销、再次删除
- 离线时的 AI 标题生成
- 应用在 AI 标题生成进行中时被杀死
- 批量删除所有会话、撤销
- 有 10,000+ 条消息的会话（列表渲染性能）

## 安全考虑

1. **会话中无敏感数据**：会话元数据（标题、agent ID、时间戳）不是敏感信息。
2. **预览中的消息内容**：`lastMessagePreview` 被截断并存储在 sessions 表中。这避免了为列表渲染查询消息表，但预览仍然是用户内容。安全级别与消息相同。
3. **AI 标题生成**：首条用户消息和 AI 响应被发送到模型进行标题生成。这与用户已经在使用的模型相同，因此没有新的数据暴露。
4. **软删除窗口**：在约 5 秒的撤销窗口期间，会话数据仍在 DB 中（只是被标记）。在此窗口期间没有不同的加密方式。

## 依赖关系

### 依赖
- **RFC-000（总体架构）**：Session 领域模型、项目结构、Room 数据库
- **RFC-002（Agent 管理）**：`AgentConstants.GENERAL_ASSISTANT_ID` 用于默认 agent；`AgentRepository` 用于 agent 名称查询
- **RFC-003（Provider 管理）**：`ProviderRepository`、`ApiKeyStorage`、`ModelApiAdapterFactory` 用于 AI 标题生成

### 被依赖
- **RFC-001（对话交互）**：使用 `CreateSessionUseCase` 进行延迟创建；使用 `GenerateTitleUseCase` 在首条 AI 响应后生成标题；使用 `SessionRepository` 更新消息统计；在聊天布局中集成 `SessionDrawerContent`

## 与 RFC-000 的差异

本 RFC 引入以下变更，应反映在 RFC-000 中：

1. **`Session` 模型已更新**：添加 `lastMessagePreview: String?`、`deletedAt: Long?`。
2. **`sessions` 表已更新**：添加 `last_message_preview`、`deleted_at` 列 + 索引。
3. **`SessionRepository` 接口已扩展**：添加软删除、恢复、批量操作、重命名、消息统计、agent 切换、活跃标志方法。
4. **`ModelApiAdapter` 接口已扩展**：添加 `generateSimpleCompletion()` 方法。

## 开放问题

- [x] ~~软删除实现：DB 字段还是仅 ViewModel？~~ **决定：DB `deleted_at` 字段**，以确保应用重启后的可靠性。
- [x] ~~AI 标题生成 prompt？~~ **决定：简单 prompt，要求 5-10 个单词的标题。**
- [x] ~~轻量级模型选择？~~ **决定：硬编码映射通过 DB 验证；回退到当前模型。**
- [ ] 抽屉是否应该显示日期分组标题（"今天"、"昨天"、"上周"）？UI 设计规范中标为可选。V1 使用无日期标题的扁平列表更简单。可以后续添加。
- [ ] 会话列表是否支持下拉刷新？仅在同步（FEAT-007）实现时有用。推迟到 FEAT-007。

## 参考资料

- [FEAT-005 PRD](../../prd/features/FEAT-005-session-zh.md) -- 功能需求
- [UI 设计规范](../../design/ui-design-spec-zh.md) -- 导航抽屉布局、会话列表项
- [RFC-000 总体架构](../architecture/RFC-000-overall-architecture-zh.md) -- Session 模型、DB schema
- [RFC-002 Agent 管理](RFC-002-agent-management-zh.md) -- Agent 回退、AgentConstants
- [RFC-003 Provider 管理](RFC-003-provider-management-zh.md) -- ModelApiAdapter、ProviderRepository

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
