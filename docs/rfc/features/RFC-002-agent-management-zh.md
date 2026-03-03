# RFC-002: Agent 管理

## 文档信息
- **RFC 编号**: RFC-002
- **关联 PRD**: [FEAT-002 (Agent 管理)](../../prd/features/FEAT-002-agent-zh.md)
- **关联设计**: [UI 设计规范](../../design/ui-design-spec-zh.md)（第 5、6 节；第 2 节中的 Agent 选择器）
- **关联架构**: [RFC-000 (总体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **依赖**: [RFC-003 (Provider 管理)](RFC-003-provider-management-zh.md)、[RFC-004 (工具系统)](RFC-004-tool-system-zh.md)
- **被依赖**: RFC-001 (对话交互)、RFC-005 (会话管理)
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
Agent 管理定义了用户如何在 OneClaw 中创建、配置和管理 AI Agent。Agent 是一个预配置的 AI 角色，具有名称、描述、system prompt、工具集和可选的首选模型/provider。应用内置一个"通用助手"Agent 作为所有新会话的默认值。用户可以克隆内置 Agent 并创建完全自定义的 Agent。Agent 可以在对话中途切换。

本 RFC 涵盖 Agent 数据持久化、CRUD 操作、内置 Agent 种子数据机制、克隆操作、模型解析逻辑以及 Agent 管理 UI。在活跃聊天会话中的实际 Agent 切换（更新会话状态、插入系统消息）推迟到 RFC-005（会话管理）和 RFC-001（对话交互）。工具选择 UI 引用 RFC-004 中定义的 `ToolRegistry` 的工具。

### 目标
1. 实现 Agent 数据持久化（Room 实体、DAO、Repository）
2. 通过 Room `onCreate` 回调实现内置"通用助手"Agent 种子数据
3. 实现 Agent CRUD 操作（创建、读取、更新、删除）及业务规则执行
4. 实现 Agent 克隆操作
5. 实现模型解析逻辑（Agent 首选 -> 全局默认）
6. 实现 Agent 管理 UI（列表界面、详情/编辑界面、创建界面）
7. 实现聊天界面的 Agent 选择器底部弹出（供 RFC-001 使用）
8. 提供足够的实现细节以支持 AI 辅助代码生成

### 非目标
- 活跃聊天会话中的 Agent 切换逻辑（RFC-001/RFC-005 处理会话状态更新）
- 聊天中的 Agent 切换系统消息（RFC-001）
- Agent 专属对话历史
- Agent 分享/导入/导出
- Sub-agent / Multi-agent 编排
- Agent 版本管理
- Agent 图标/头像
- Agent 定时调度或自动化

## 技术方案

### 架构概览

```
+-----------------------------------------------------------------+
|                         UI 层                                    |
|  AgentListScreen    AgentDetailScreen    AgentSelectorSheet      |
|       |                   |                    |                 |
|       v                   v                    v                 |
|  AgentListViewModel  AgentDetailViewModel  (共享 ViewModel)      |
+-----------------------------------------------------------------+
|                       领域层                                     |
|  CreateAgentUseCase  CloneAgentUseCase  DeleteAgentUseCase       |
|  GetAgentToolsUseCase  ResolveModelUseCase                       |
|       |                                                          |
|       v                                                          |
|  AgentRepository (接口)                                          |
+-----------------------------------------------------------------+
|                        数据层                                    |
|  AgentRepositoryImpl                                             |
|       |           |              |                               |
|       v           v              v                               |
|  AgentDao    ToolRegistry   ProviderRepository                   |
|             (工具列表)      (模型选择器)                          |
+-----------------------------------------------------------------+
```

### 核心组件

1. **AgentRepositoryImpl**
   - 职责：Agent CRUD 操作、业务规则执行（内置保护、删除回退）
   - 依赖：AgentDao
   - 接口：实现 Core 模块中的 `AgentRepository`

2. **内置 Agent 种子数据**
   - 职责：在首次创建数据库时将"通用助手"Agent 插入 Room
   - 机制：Room `RoomDatabase.Callback.onCreate`（与 RFC-003 中的 provider 种子数据使用相同回调）
   - 固定 ID：`agent-general-assistant`

3. **Use Cases**
   - `CreateAgentUseCase`：验证并持久化新的自定义 Agent
   - `CloneAgentUseCase`：创建任意 Agent（内置或自定义）的可编辑副本
   - `DeleteAgentUseCase`：删除自定义 Agent，更新引用它的会话
   - `GetAgentToolsUseCase`：将 Agent 的 tool ID 解析为 `ToolRegistry` 中的 `ToolDefinition` 对象
   - `ResolveModelUseCase`：解析使用哪个模型/provider（Agent 首选 -> 全局默认）

4. **AgentSelectorSheet**
   - 职责：显示 Agent 列表的底部弹出组件，用于对话中途切换
   - 使用方：聊天界面（RFC-001）
   - 本 RFC 定义该组件；RFC-001 将其集成到聊天流程中

## 数据模型

### 领域模型

`Agent` 模型已在 RFC-000 中声明。本节提供本 RFC 使用的完整定义。

#### Agent（与 RFC-000 一致）

```kotlin
data class Agent(
    val id: String,                    // UUID 或内置的固定 ID
    val name: String,                  // 显示名称（如"通用助手"）
    val description: String?,          // 可选描述
    val systemPrompt: String,          // System prompt 文本
    val toolIds: List<String>,         // 该 Agent 可用的工具名称（如 ["read_file", "write_file"]）
    val preferredProviderId: String?,  // 可选首选 provider
    val preferredModelId: String?,     // 可选首选模型
    val isBuiltIn: Boolean,            // 是否为内置 Agent（只读）
    val createdAt: Long,               // 时间戳（毫秒）
    val updatedAt: Long                // 时间戳（毫秒）
)
```

**关于 `toolIds` 的说明：**
- 这些是工具**名称**（如 `"read_file"`、`"http_request"`），不是任意 ID。它们对应 `ToolRegistry` 中的 `ToolDefinition.name`（见 RFC-004）。
- 空列表表示该 Agent 没有工具（纯聊天模式）。
- 在 UI 中显示工具时，可用工具列表来自 `ToolRegistry.getAllToolDefinitions()`。
- 构建 API 请求时，通过 `ToolRegistry.getToolDefinitionsByNames(agent.toolIds)` 解析工具。

**关于 `preferredProviderId` / `preferredModelId` 的说明：**
- 两者都是可选的。为 null 时使用全局默认模型/provider。
- 设置时两者必须同时设置（没有 provider 的模型没有意义）。
- 首选模型引用 RFC-003 中的 `AiModel.id` 和 `Provider.id`。

### Room 实体

#### AgentEntity

```kotlin
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String,
    @ColumnInfo(name = "tool_ids")
    val toolIds: String,               // JSON 数组字符串：["read_file", "write_file"]
    @ColumnInfo(name = "preferred_provider_id")
    val preferredProviderId: String?,
    @ColumnInfo(name = "preferred_model_id")
    val preferredModelId: String?,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### 数据库 Schema

```sql
CREATE TABLE agents (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    tool_ids TEXT NOT NULL,           -- 工具名称的 JSON 数组
    preferred_provider_id TEXT,
    preferred_model_id TEXT,
    is_built_in INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

与 RFC-000 中已声明的 schema 一致。

### 类型转换器

`tool_ids` 字段在 Room 中存储为 JSON 数组字符串。类型转换器处理序列化：

```kotlin
// 在 Converters.kt 中（AppDatabase 的共享类型转换器）
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

**注意：** 该类型转换器由实体-领域模型映射器使用，而非直接由 Room 使用。实体将 `toolIds` 存储为原始 `String`（JSON），映射器负责 `List<String>` 的转换。这避免了 Room 需要直接理解 `List<String>` 类型。

### 实体-领域模型映射器

```kotlin
// AgentMapper.kt
fun AgentEntity.toDomain(): Agent = Agent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = try {
        Json.decodeFromString<List<String>>(toolIds)
    } catch (e: Exception) {
        emptyList()
    },
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Agent.toEntity(): AgentEntity = AgentEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = Json.encodeToString(toolIds),
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)
```

## DAO 接口

### AgentDao

```kotlin
@Dao
interface AgentDao {

    /**
     * 获取所有 Agent，排序：内置优先，然后按 updated_at 降序。
     */
    @Query("SELECT * FROM agents ORDER BY is_built_in DESC, updated_at DESC")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE is_built_in = 1")
    suspend fun getBuiltInAgents(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    /**
     * 删除自定义（非内置）Agent。返回受影响的行数。
     * 内置 Agent 受 WHERE 子句保护。
     */
    @Query("DELETE FROM agents WHERE id = :id AND is_built_in = 0")
    suspend fun deleteCustomAgent(id: String): Int

    /**
     * 统计 Agent 数量。用于验证种子数据。
     */
    @Query("SELECT COUNT(*) FROM agents")
    suspend fun getAgentCount(): Int
}
```

## 内置 Agent 种子数据

### 策略：Room 数据库回调

"通用助手"Agent 与预配置的 provider 一起在同一个 `AppDatabaseCallback.onCreate` 中插入（该回调在 RFC-003 中定义）。这确保所有种子数据在首次创建数据库时原子性地创建。

### 实现

```kotlin
// 在 AppDatabaseCallback 中（扩展 RFC-003 中已有的回调）
class AppDatabaseCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val now = System.currentTimeMillis()

        // --- Provider 种子数据（来自 RFC-003）---
        // ... 已有的 provider INSERT 语句 ...

        // --- Model 种子数据（来自 RFC-003）---
        // ... 已有的 model INSERT 语句 ...

        // --- Agent 种子数据（RFC-002）---
        val allToolIds = """["get_current_time","read_file","write_file","http_request"]"""
        val systemPrompt = """You are a helpful AI assistant. You can help with a wide range of tasks including answering questions, writing, analysis, and more. You have access to tools that allow you to read/write files, make HTTP requests, and check the current time. Use tools when they would help accomplish the user's request."""

        db.execSQL(
            """INSERT INTO agents (id, name, description, system_prompt, tool_ids, preferred_provider_id, preferred_model_id, is_built_in, created_at, updated_at)
               VALUES ('agent-general-assistant', 'General Assistant', 'A general-purpose helpful AI assistant with access to all built-in tools', '$systemPrompt', '$allToolIds', NULL, NULL, 1, $now, $now)"""
        )
    }
}
```

### 设计决策

- **固定 ID**：`agent-general-assistant` -- 确定性 ID，被删除回退逻辑引用，与 provider ID 风格一致（`provider-openai` 等）
- **启用所有工具**：通用助手可访问全部 4 个内置工具。需要限制工具集的用户应克隆后自定义。
- **无首选模型**：通用助手使用全局默认模型，开箱即用时与模型无关。
- **System prompt**：简洁的通用 prompt，提及可用的工具能力。引导模型在适当时使用工具。
- **不可变**：内置 Agent 不可编辑或删除。DAO 的 `deleteCustomAgent` 查询在 WHERE 子句中包含 `is_built_in = 0`。

### 常量

```kotlin
/**
 * 内置 Agent 的已知 ID。
 * 位于：core/model/AgentConstants.kt
 */
object AgentConstants {
    const val GENERAL_ASSISTANT_ID = "agent-general-assistant"
}
```

该常量的使用场景：
- `DeleteAgentUseCase`：当被删除的 Agent 正在被会话使用时，这些会话回退到 `GENERAL_ASSISTANT_ID`
- `SessionRepository`：新会话默认使用 `GENERAL_ASSISTANT_ID`
- 未来的 Agent 种子数据：检查内置 Agent 是否已存在

## Repository 实现

### AgentRepository 接口

定义在 Core 模块中。在 RFC-000 基础上进行了小幅优化。

```kotlin
interface AgentRepository {
    /**
     * 观察所有 Agent。内置优先，然后自定义按 updated_at 降序。
     */
    fun getAllAgents(): Flow<List<Agent>>

    /**
     * 按 ID 获取单个 Agent。未找到返回 null。
     */
    suspend fun getAgentById(id: String): Agent?

    /**
     * 创建新的自定义 Agent。agent.isBuiltIn 必须为 false。
     * 返回已设置时间戳的创建后 Agent。
     */
    suspend fun createAgent(agent: Agent): Agent

    /**
     * 更新已有的自定义 Agent。内置 Agent 不可更新。
     * 如果 Agent 是内置的，返回 AppResult.Error。
     */
    suspend fun updateAgent(agent: Agent): AppResult<Unit>

    /**
     * 按 ID 删除自定义 Agent。内置 Agent 不可删除。
     * 如果 Agent 是内置的或未找到，返回 AppResult.Error。
     */
    suspend fun deleteAgent(id: String): AppResult<Unit>

    /**
     * 获取所有内置 Agent。
     */
    suspend fun getBuiltInAgents(): List<Agent>
}
```

### AgentRepositoryImpl

```kotlin
class AgentRepositoryImpl(
    private val agentDao: AgentDao
) : AgentRepository {

    override fun getAllAgents(): Flow<List<Agent>> {
        return agentDao.getAllAgents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAgentById(id: String): Agent? {
        return agentDao.getAgentById(id)?.toDomain()
    }

    override suspend fun createAgent(agent: Agent): Agent {
        val now = System.currentTimeMillis()
        val newAgent = agent.copy(
            id = if (agent.id.isBlank()) java.util.UUID.randomUUID().toString() else agent.id,
            isBuiltIn = false,  // 强制：只有自定义 Agent 可通过此方法创建
            createdAt = now,
            updatedAt = now
        )
        agentDao.insertAgent(newAgent.toEntity())
        return newAgent
    }

    override suspend fun updateAgent(agent: Agent): AppResult<Unit> {
        // 验证 Agent 存在且非内置
        val existing = agentDao.getAgentById(agent.id)
            ?: return AppResult.Error(
                message = "Agent not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (existing.isBuiltIn) {
            return AppResult.Error(
                message = "Built-in agents cannot be edited.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val updated = agent.copy(updatedAt = System.currentTimeMillis())
        agentDao.updateAgent(updated.toEntity())
        return AppResult.Success(Unit)
    }

    override suspend fun deleteAgent(id: String): AppResult<Unit> {
        val deleted = agentDao.deleteCustomAgent(id)
        return if (deleted > 0) {
            AppResult.Success(Unit)
        } else {
            // 未找到或是内置
            val exists = agentDao.getAgentById(id)
            if (exists != null && exists.isBuiltIn) {
                AppResult.Error(
                    message = "Built-in agents cannot be deleted.",
                    code = ErrorCode.VALIDATION_ERROR
                )
            } else {
                AppResult.Error(
                    message = "Agent not found.",
                    code = ErrorCode.VALIDATION_ERROR
                )
            }
        }
    }

    override suspend fun getBuiltInAgents(): List<Agent> {
        return agentDao.getBuiltInAgents().map { it.toDomain() }
    }
}
```

## Use Cases

### CreateAgentUseCase

```kotlin
/**
 * 验证并创建新的自定义 Agent。
 * 位于：feature/agent/usecase/CreateAgentUseCase.kt
 */
class CreateAgentUseCase(
    private val agentRepository: AgentRepository
) {
    suspend operator fun invoke(
        name: String,
        description: String?,
        systemPrompt: String,
        toolIds: List<String>,
        preferredProviderId: String?,
        preferredModelId: String?
    ): AppResult<Agent> {
        // 验证
        if (name.isBlank()) {
            return AppResult.Error(
                message = "Agent name cannot be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (systemPrompt.isBlank()) {
            return AppResult.Error(
                message = "System prompt cannot be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        // 如果设置了 provider/model 其中之一，两者必须同时设置
        if ((preferredProviderId != null) != (preferredModelId != null)) {
            return AppResult.Error(
                message = "Preferred provider and model must both be set or both be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val agent = Agent(
            id = "",  // 由 repository 生成
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            systemPrompt = systemPrompt.trim(),
            toolIds = toolIds,
            preferredProviderId = preferredProviderId,
            preferredModelId = preferredModelId,
            isBuiltIn = false,
            createdAt = 0,  // 由 repository 设置
            updatedAt = 0   // 由 repository 设置
        )

        val created = agentRepository.createAgent(agent)
        return AppResult.Success(created)
    }
}
```

### CloneAgentUseCase

```kotlin
/**
 * 创建任意 Agent（内置或自定义）的可编辑副本。
 * 位于：feature/agent/usecase/CloneAgentUseCase.kt
 */
class CloneAgentUseCase(
    private val agentRepository: AgentRepository
) {
    suspend operator fun invoke(agentId: String): AppResult<Agent> {
        val original = agentRepository.getAgentById(agentId)
            ?: return AppResult.Error(
                message = "Agent not found.",
                code = ErrorCode.VALIDATION_ERROR
            )

        val clone = Agent(
            id = "",  // 由 repository 生成
            name = "${original.name} (Copy)",
            description = original.description,
            systemPrompt = original.systemPrompt,
            toolIds = original.toolIds,
            preferredProviderId = original.preferredProviderId,
            preferredModelId = original.preferredModelId,
            isBuiltIn = false,  // 副本始终是自定义的
            createdAt = 0,
            updatedAt = 0
        )

        val created = agentRepository.createAgent(clone)
        return AppResult.Success(created)
    }
}
```

### DeleteAgentUseCase

```kotlin
/**
 * 删除自定义 Agent 并处理会话回退。
 * 位于：feature/agent/usecase/DeleteAgentUseCase.kt
 */
class DeleteAgentUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(agentId: String): AppResult<Unit> {
        // 首先，将使用该 Agent 的所有会话更新为回退到通用助手
        sessionRepository.updateAgentForSessions(
            oldAgentId = agentId,
            newAgentId = AgentConstants.GENERAL_ASSISTANT_ID
        )

        // 然后删除 Agent
        return agentRepository.deleteAgent(agentId)
    }
}
```

**注意：** `SessionRepository.updateAgentForSessions()` 是一个新方法，需要添加。它将在 RFC-005（会话管理）中完整定义，但签名在此处定义供 RFC-002 使用：

```kotlin
// 添加到 SessionRepository 接口（core/repository/SessionRepository.kt）
interface SessionRepository {
    // ... 已有方法 ...

    /**
     * 将引用 oldAgentId 的所有会话更新为使用 newAgentId。
     * 在 Agent 被删除时用于回退到通用助手。
     */
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)
}
```

### GetAgentToolsUseCase

```kotlin
/**
 * 将 Agent 的 tool ID 解析为完整的 ToolDefinition 对象。
 * 供聊天系统获取 API 调用所需的工具定义。
 * 位于：feature/agent/usecase/GetAgentToolsUseCase.kt
 */
class GetAgentToolsUseCase(
    private val agentRepository: AgentRepository,
    private val toolRegistry: ToolRegistry
) {
    suspend operator fun invoke(agentId: String): List<ToolDefinition> {
        val agent = agentRepository.getAgentById(agentId) ?: return emptyList()
        return toolRegistry.getToolDefinitionsByNames(agent.toolIds)
    }
}
```

### ResolveModelUseCase

```kotlin
/**
 * 解析给定 Agent 应使用的模型和 provider。
 * 解析顺序：
 *   1. Agent 的首选模型/provider（如已设置）
 *   2. 全局默认模型/provider
 *
 * 位于：feature/agent/usecase/ResolveModelUseCase.kt
 */
class ResolveModelUseCase(
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository
) {
    /**
     * @return (AiModel, Provider) 的组合，如果未配置任何模型则返回 null
     */
    suspend operator fun invoke(agentId: String): AppResult<ResolvedModel> {
        val agent = agentRepository.getAgentById(agentId)
            ?: return AppResult.Error(
                message = "Agent not found.",
                code = ErrorCode.VALIDATION_ERROR
            )

        // 1. 尝试 Agent 的首选模型/provider
        if (agent.preferredProviderId != null && agent.preferredModelId != null) {
            val provider = providerRepository.getProviderById(agent.preferredProviderId)
            if (provider != null && provider.isActive) {
                val models = providerRepository.getModelsForProvider(provider.id)
                val model = models.find { it.id == agent.preferredModelId }
                if (model != null) {
                    return AppResult.Success(
                        ResolvedModel(model = model, provider = provider)
                    )
                }
            }
            // Agent 的首选模型/provider 无效（已删除/已停用）-- 降级到全局默认
        }

        // 2. 尝试全局默认
        val defaultModel = providerRepository.getGlobalDefaultModel().first()
        if (defaultModel != null) {
            val provider = providerRepository.getProviderById(defaultModel.providerId)
            if (provider != null && provider.isActive) {
                return AppResult.Success(
                    ResolvedModel(model = defaultModel, provider = provider)
                )
            }
        }

        // 未配置模型
        return AppResult.Error(
            message = "No model configured. Please set up a provider in Settings.",
            code = ErrorCode.VALIDATION_ERROR
        )
    }
}

/**
 * 模型解析的结果。
 * 位于：core/model/ResolvedModel.kt
 */
data class ResolvedModel(
    val model: AiModel,
    val provider: Provider
)
```

## UI 层

### 导航路由

```kotlin
// 在 Routes.kt 中（Agent 功能的补充）
object AgentList : Route("agents")
object AgentDetail : Route("agents/{agentId}") {
    fun create(agentId: String) = "agents/$agentId"
}
object AgentCreate : Route("agents/create")
```

### UI 状态定义

#### AgentListUiState

```kotlin
data class AgentListUiState(
    val agents: List<AgentListItem> = emptyList(),
    val isLoading: Boolean = true
)

data class AgentListItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val toolCount: Int
)
```

#### AgentDetailUiState

```kotlin
data class AgentDetailUiState(
    // Agent 数据
    val agentId: String? = null,
    val isBuiltIn: Boolean = false,
    val isNewAgent: Boolean = false,     // 创建新 Agent 时为 true

    // 表单字段
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val selectedToolIds: List<String> = emptyList(),
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null,

    // 可用工具（来自 ToolRegistry）
    val availableTools: List<ToolOptionItem> = emptyList(),

    // 可用模型（来自所有活跃 provider）
    val availableModels: List<ModelOptionItem> = emptyList(),

    // UI 状态
    val hasUnsavedChanges: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showDeleteDialog: Boolean = false,
    val navigateBack: Boolean = false    // 弹出导航的信号
)

/**
 * 工具选择列表中显示的工具选项。
 */
data class ToolOptionItem(
    val name: String,              // 工具名称（如 "read_file"）
    val description: String,       // 工具描述
    val isSelected: Boolean        // 该工具是否对此 Agent 启用
)

/**
 * 首选模型选择器中显示的模型选项。
 */
data class ModelOptionItem(
    val modelId: String,
    val modelDisplayName: String?,
    val providerId: String,
    val providerName: String
)
```

#### AgentSelectorUiState

```kotlin
/**
 * 聊天界面中 Agent 选择器底部弹出的状态。
 */
data class AgentSelectorUiState(
    val agents: List<AgentSelectorItem> = emptyList(),
    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val isLoading: Boolean = true
)

data class AgentSelectorItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val isSelected: Boolean        // 如果是当前 Agent 则为 true
)
```

### ViewModels

#### AgentListViewModel

```kotlin
class AgentListViewModel(
    private val agentRepository: AgentRepository,
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            agentRepository.getAllAgents().collect { agents ->
                val items = agents.map { agent ->
                    AgentListItem(
                        id = agent.id,
                        name = agent.name,
                        description = agent.description,
                        isBuiltIn = agent.isBuiltIn,
                        toolCount = agent.toolIds.size
                    )
                }
                _uiState.update { it.copy(agents = items, isLoading = false) }
            }
        }
    }
}
```

#### AgentDetailViewModel

```kotlin
class AgentDetailViewModel(
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val toolRegistry: ToolRegistry,
    private val createAgentUseCase: CreateAgentUseCase,
    private val cloneAgentUseCase: CloneAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // agentId 为 null 表示创建模式，非 null 表示查看/编辑模式
    private val agentId: String? = savedStateHandle["agentId"]
    private val isCreateMode = agentId == null

    private val _uiState = MutableStateFlow(AgentDetailUiState(isNewAgent = isCreateMode))
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    // 原始 Agent 数据的快照，用于变更检测
    private var originalAgent: Agent? = null

    init {
        loadAvailableTools()
        loadAvailableModels()
        if (!isCreateMode) {
            loadAgent(agentId!!)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun loadAgent(id: String) {
        viewModelScope.launch {
            val agent = agentRepository.getAgentById(id)
            if (agent == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Agent not found.")
                }
                return@launch
            }

            originalAgent = agent
            _uiState.update {
                it.copy(
                    agentId = agent.id,
                    isBuiltIn = agent.isBuiltIn,
                    name = agent.name,
                    description = agent.description ?: "",
                    systemPrompt = agent.systemPrompt,
                    selectedToolIds = agent.toolIds,
                    preferredProviderId = agent.preferredProviderId,
                    preferredModelId = agent.preferredModelId,
                    isLoading = false
                )
            }
        }
    }

    private fun loadAvailableTools() {
        val tools = toolRegistry.getAllToolDefinitions().map { toolDef ->
            ToolOptionItem(
                name = toolDef.name,
                description = toolDef.description,
                isSelected = false  // Agent 加载后更新
            )
        }
        _uiState.update { it.copy(availableTools = tools) }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            providerRepository.getActiveProviders().collect { providers ->
                val modelOptions = mutableListOf<ModelOptionItem>()
                for (provider in providers) {
                    val models = providerRepository.getModelsForProvider(provider.id)
                    for (model in models) {
                        modelOptions.add(
                            ModelOptionItem(
                                modelId = model.id,
                                modelDisplayName = model.displayName,
                                providerId = provider.id,
                                providerName = provider.name
                            )
                        )
                    }
                }
                _uiState.update { it.copy(availableModels = modelOptions) }
            }
        }
    }

    // --- 表单字段更新 ---

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, hasUnsavedChanges = true) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description, hasUnsavedChanges = true) }
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.update { it.copy(systemPrompt = prompt, hasUnsavedChanges = true) }
    }

    fun toggleTool(toolName: String) {
        _uiState.update { state ->
            val newToolIds = if (toolName in state.selectedToolIds) {
                state.selectedToolIds - toolName
            } else {
                state.selectedToolIds + toolName
            }
            state.copy(selectedToolIds = newToolIds, hasUnsavedChanges = true)
        }
    }

    fun setPreferredModel(providerId: String?, modelId: String?) {
        _uiState.update {
            it.copy(
                preferredProviderId = providerId,
                preferredModelId = modelId,
                hasUnsavedChanges = true
            )
        }
    }

    fun clearPreferredModel() {
        setPreferredModel(null, null)
    }

    // --- 操作 ---

    fun saveAgent() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            if (isCreateMode) {
                val result = createAgentUseCase(
                    name = state.name,
                    description = state.description.ifBlank { null },
                    systemPrompt = state.systemPrompt,
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId
                )
                when (result) {
                    is AppResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                hasUnsavedChanges = false,
                                successMessage = "Agent created.",
                                navigateBack = true
                            )
                        }
                    }
                    is AppResult.Error -> {
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = result.message)
                        }
                    }
                }
            } else {
                // 更新已有 Agent
                val updated = Agent(
                    id = state.agentId!!,
                    name = state.name.trim(),
                    description = state.description.trim().ifBlank { null },
                    systemPrompt = state.systemPrompt.trim(),
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId,
                    isBuiltIn = false,
                    createdAt = originalAgent?.createdAt ?: 0,
                    updatedAt = 0  // 由 repository 设置
                )
                when (val result = agentRepository.updateAgent(updated)) {
                    is AppResult.Success -> {
                        originalAgent = updated
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                hasUnsavedChanges = false,
                                successMessage = "Agent saved."
                            )
                        }
                    }
                    is AppResult.Error -> {
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = result.message)
                        }
                    }
                }
            }
        }
    }

    fun cloneAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = cloneAgentUseCase(id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            successMessage = "Agent cloned.",
                            navigateBack = true
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun deleteAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = deleteAgentUseCase(id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            showDeleteDialog = false,
                            successMessage = "Agent deleted.",
                            navigateBack = true
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(showDeleteDialog = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
```

### 界面组件概要

以下为结构概要。完整的 Compose 代码将在实现阶段生成，参考 [UI 设计规范](../../design/ui-design-spec-zh.md) 获取具体间距、颜色和样式。

#### AgentListScreen

```kotlin
@Composable
fun AgentListScreen(
    viewModel: AgentListViewModel = koinViewModel(),
    onAgentClick: (String) -> Unit,       // 导航到 Agent 详情
    onCreateAgent: () -> Unit,            // 导航到创建 Agent
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    IconButton(onClick = onCreateAgent) {
                        Icon(Icons.Default.Add, "Create Agent")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else {
            LazyColumn(contentPadding = padding) {
                // 内置 Agent 区域
                val builtIn = uiState.agents.filter { it.isBuiltIn }
                if (builtIn.isNotEmpty()) {
                    stickyHeader { SectionHeader("BUILT-IN") }
                    items(builtIn, key = { it.id }) { agent ->
                        AgentListItem(
                            agent = agent,
                            onClick = { onAgentClick(agent.id) }
                        )
                    }
                }

                // 自定义 Agent 区域
                val custom = uiState.agents.filter { !it.isBuiltIn }
                if (custom.isNotEmpty()) {
                    stickyHeader { SectionHeader("CUSTOM") }
                    items(custom, key = { it.id }) { agent ->
                        SwipeToActionItem(
                            onDelete = { /* 显示确认 */ },
                            onClone = { /* 克隆 */ }
                        ) {
                            AgentListItem(
                                agent = agent,
                                onClick = { onAgentClick(agent.id) }
                            )
                        }
                    }
                }

                // 自定义 Agent 的空状态
                if (custom.isEmpty()) {
                    item {
                        EmptyStateMessage("No custom agents yet. Tap + to create one.")
                    }
                }
            }
        }
    }
}
```

#### AgentDetailScreen

```kotlin
@Composable
fun AgentDetailScreen(
    viewModel: AgentDetailViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onCloneNavigate: (String) -> Unit      // 导航到克隆 Agent 的详情
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // 处理导航信号
    LaunchedEffect(uiState.navigateBack) {
        if (uiState.navigateBack) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.isNewAgent -> "Create Agent"
                            uiState.isBuiltIn -> uiState.name
                            else -> "Edit Agent"
                        }
                    )
                },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    // Save 按钮：新建和自定义 Agent 可见，有变更时启用
                    if (!uiState.isBuiltIn) {
                        TextButton(
                            onClick = { viewModel.saveAgent() },
                            enabled = uiState.hasUnsavedChanges && !uiState.isSaving
                        ) {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { /* 用于成功/错误消息 */ }
    ) { padding ->
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize()
            ) {
                // 名称字段
                item {
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Name") },
                        readOnly = uiState.isBuiltIn,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 描述字段
                item {
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.updateDescription(it) },
                        label = { Text("Description (optional)") },
                        readOnly = uiState.isBuiltIn,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // System Prompt 字段（大文本区域）
                item {
                    OutlinedTextField(
                        value = uiState.systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        label = { Text("System Prompt") },
                        readOnly = uiState.isBuiltIn,
                        minLines = 5,
                        maxLines = 15,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 工具选择区域
                item { SectionHeader("TOOLS") }
                items(uiState.availableTools, key = { it.name }) { tool ->
                    ToolCheckboxItem(
                        toolName = tool.name,
                        toolDescription = tool.description,
                        isChecked = tool.name in uiState.selectedToolIds,
                        onCheckedChange = { viewModel.toggleTool(tool.name) },
                        enabled = !uiState.isBuiltIn
                    )
                }

                // 首选模型区域
                item { SectionHeader("PREFERRED MODEL (optional)") }
                item {
                    PreferredModelSelector(
                        currentProviderId = uiState.preferredProviderId,
                        currentModelId = uiState.preferredModelId,
                        availableModels = uiState.availableModels,
                        onSelect = { providerId, modelId ->
                            viewModel.setPreferredModel(providerId, modelId)
                        },
                        onClear = { viewModel.clearPreferredModel() },
                        enabled = !uiState.isBuiltIn
                    )
                }

                // 操作按钮
                item {
                    ActionButtonsSection(
                        isBuiltIn = uiState.isBuiltIn,
                        isNewAgent = uiState.isNewAgent,
                        onClone = { viewModel.cloneAgent() },
                        onDelete = { viewModel.showDeleteConfirmation() }
                    )
                }
            }
        }

        // 删除确认对话框
        if (uiState.showDeleteDialog) {
            ConfirmationDialog(
                title = "Delete Agent",
                message = "This agent will be permanently removed. Any sessions using this agent will switch to General Assistant.",
                confirmText = "Delete",
                onConfirm = { viewModel.deleteAgent() },
                onDismiss = { viewModel.dismissDeleteConfirmation() }
            )
        }
    }
}
```

#### AgentSelectorSheet

Agent 选择器是一个底部弹出，当用户点击聊天界面顶部应用栏中的 Agent 名称时显示。该组件在 RFC-002 中定义，由 RFC-001 集成到聊天流程中。

```kotlin
/**
 * 聊天界面中选择 Agent 的底部弹出。
 * 位于：feature/agent/AgentSelectorSheet.kt
 *
 * 由 ChatScreen（RFC-001）使用：
 *   ChatViewModel.showAgentSelector() -> 显示此弹出
 *   ChatViewModel.switchAgent(agentId) -> 更新会话的当前 Agent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSelectorSheet(
    agents: List<AgentSelectorItem>,
    currentAgentId: String,
    onSelectAgent: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // 标题
            Text(
                text = "Select an Agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Agent 列表
            LazyColumn {
                items(agents, key = { it.id }) { agent ->
                    ListItem(
                        headlineContent = { Text(agent.name) },
                        supportingContent = agent.description?.let { { Text(it, maxLines = 1) } },
                        leadingContent = if (agent.isBuiltIn) {
                            { Badge { Text("Built-in") } }
                        } else null,
                        trailingContent = if (agent.id == currentAgentId) {
                            { Icon(Icons.Default.Check, "Current") }
                        } else null,
                        modifier = Modifier.clickable {
                            if (agent.id != currentAgentId) {
                                onSelectAgent(agent.id)
                            }
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
```

## Koin 依赖注入

```kotlin
// 对已有 Koin 模块的补充

// DatabaseModule.kt -- AgentDao 注册
val databaseModule = module {
    // ... 已有的数据库设置和 AppDatabaseCallback ...
    single { get<AppDatabase>().agentDao() }
    // ... 其他 DAO ...
}

// RepositoryModule.kt
val repositoryModule = module {
    single<AgentRepository> { AgentRepositoryImpl(get()) }  // AgentDao
    // ... 其他 repository ...
}

// FeatureModule.kt -- Agent 功能
val featureModule = module {
    // Agent Use Cases
    factory { CreateAgentUseCase(get()) }                    // AgentRepository
    factory { CloneAgentUseCase(get()) }                     // AgentRepository
    factory { DeleteAgentUseCase(get(), get()) }             // AgentRepository, SessionRepository
    factory { GetAgentToolsUseCase(get(), get()) }           // AgentRepository, ToolRegistry
    factory { ResolveModelUseCase(get(), get()) }            // AgentRepository, ProviderRepository

    // Agent ViewModels
    viewModel { AgentListViewModel(get(), get()) }           // AgentRepository, ToolRegistry
    viewModel { AgentDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    // AgentRepository, ProviderRepository, ToolRegistry, CreateAgentUseCase,
    // CloneAgentUseCase, DeleteAgentUseCase, SavedStateHandle
}
```

## 数据流示例

### 流程 1：用户创建自定义 Agent

```
1. 用户导航：设置 > 管理 Agents > [+] 创建
2. AgentDetailScreen 以创建模式打开（agentId = null）
   -> AgentDetailViewModel: isCreateMode = true
   -> 从 ToolRegistry 加载可用工具
   -> 从 ProviderRepository 加载可用模型

3. 用户填写：
   - 名称："Writing Helper"
   - 描述："Helps with writing and editing"
   - System Prompt："You are a writing assistant..."
   - 工具：[x] get_current_time, [ ] read_file, [ ] write_file, [x] http_request
   - 首选模型：未设置（使用全局默认）

4. 用户点击"Save"
   -> AgentDetailViewModel.saveAgent()
   -> CreateAgentUseCase 验证字段
   -> AgentRepository.createAgent(agent)
      -> 生成 UUID，设置时间戳
      -> AgentDao.insertAgent(entity)
   -> UI 显示"Agent created." toast
   -> 导航回 Agent 列表

5. Agent 列表自动更新（来自 AgentDao.getAllAgents() 的 Flow）
   -> "Writing Helper"出现在 CUSTOM 区域
```

### 流程 2：用户克隆内置 Agent

```
1. 用户导航：设置 > 管理 Agents > General Assistant
2. AgentDetailScreen 以查看模式打开
   -> 所有字段只读（isBuiltIn = true）
   -> 无 Save 按钮，无 Delete 按钮
   -> Clone 按钮可见

3. 用户点击"Clone"
   -> AgentDetailViewModel.cloneAgent()
   -> CloneAgentUseCase("agent-general-assistant")
      -> 读取原始 Agent
      -> 创建新 Agent，名称为"General Assistant (Copy)"，isBuiltIn = false
      -> AgentRepository.createAgent(clone)
   -> UI 显示"Agent cloned." toast
   -> 导航回 Agent 列表

4. Agent 列表在 CUSTOM 区域显示克隆
5. 用户点击克隆进行编辑
   -> 所有字段现在可编辑
   -> 用户修改 system prompt 和工具集
   -> 用户点击"Save"
```

### 流程 3：用户删除正在被会话使用的 Agent

```
1. 用户有一个使用"Data Analyst"Agent 的活跃会话
2. 用户导航：设置 > 管理 Agents > Data Analyst > Delete
3. 确认对话框："This agent will be permanently removed.
   Any sessions using this agent will switch to General Assistant."

4. 用户点击"Delete"
   -> DeleteAgentUseCase("data-analyst-uuid")
   -> SessionRepository.updateAgentForSessions(
        oldAgentId = "data-analyst-uuid",
        newAgentId = "agent-general-assistant"
      )
      -> 更新 sessions 表：SET current_agent_id = 'agent-general-assistant'
         WHERE current_agent_id = 'data-analyst-uuid'
   -> AgentRepository.deleteAgent("data-analyst-uuid")
      -> AgentDao.deleteCustomAgent()

5. 用户返回活跃会话
   -> 会话现在显示"General Assistant"为当前 Agent
   -> 对话历史不变
   -> 下一条消息使用 General Assistant 的 system prompt 和工具集
```

### 流程 4：发送消息时的模型解析

```
1. 用户在使用"Code Helper"Agent 的会话中发送消息
2. SendMessageUseCase（RFC-001）需要知道使用哪个模型/provider
3. 调用 ResolveModelUseCase("code-helper-uuid")

4. ResolveModelUseCase 检查：
   a. Agent 有 preferredProviderId = "provider-openai"，preferredModelId = "gpt-4o"
   b. Provider "provider-openai"存在且活跃？-> 是
   c. 该 provider 下有模型"gpt-4o"？-> 是
   d. 返回 ResolvedModel(model=gpt-4o, provider=OpenAI)

5. 如果 Agent 没有首选模型：
   a. 降级到全局默认模型
   b. 如果全局默认是"provider-anthropic"上的"claude-sonnet-4-20250514"
   c. 返回 ResolvedModel(model=Claude Sonnet 4, provider=Anthropic)

6. 如果都未设置：
   a. 返回 AppResult.Error("No model configured. Please set up a provider in Settings.")
   b. 聊天显示内联错误
```

## 错误处理

### 错误场景和面向用户的消息

| 场景 | ErrorCode | 用户消息 | UI 行为 |
|------|-----------|---------|---------|
| 保存时名称为空 | VALIDATION_ERROR | "Agent name cannot be empty." | 表单字段验证错误 |
| 保存时 system prompt 为空 | VALIDATION_ERROR | "System prompt cannot be empty." | 表单字段验证错误 |
| 设置了 provider 但没设置 model（或反过来） | VALIDATION_ERROR | "Preferred provider and model must both be set or both be empty." | 表单验证错误 |
| 编辑内置 Agent | VALIDATION_ERROR | "Built-in agents cannot be edited." | 内置 Agent 不显示 Save 按钮 |
| 删除内置 Agent | VALIDATION_ERROR | "Built-in agents cannot be deleted." | 内置 Agent 不显示 Delete 按钮 |
| Agent 未找到 | VALIDATION_ERROR | "Agent not found." | 带错误导航返回 |
| 未配置模型（发送消息） | VALIDATION_ERROR | "No model configured. Please set up a provider in Settings." | 聊天中的内联错误 |
| 保存失败（DB 错误） | STORAGE_ERROR | "Failed to save agent. Please try again." | 错误 toast/snackbar |
| 删除失败（DB 错误） | STORAGE_ERROR | "Failed to delete agent. Please try again." | 错误 toast/snackbar |
| Agent 的首选模型已被删除 | --（静默降级） | -- | ResolveModelUseCase 降级到全局默认 |

### 验证规则

```kotlin
/**
 * Agent 验证逻辑，在保存前使用。
 * 位于：feature/agent/AgentValidator.kt
 */
object AgentValidator {

    fun validateName(name: String): String? {
        return when {
            name.isBlank() -> "Agent name cannot be empty."
            name.length > 100 -> "Agent name is too long (max 100 characters)."
            else -> null
        }
    }

    fun validateSystemPrompt(prompt: String): String? {
        return when {
            prompt.isBlank() -> "System prompt cannot be empty."
            prompt.length > 50_000 -> "System prompt is too long (max 50,000 characters)."
            else -> null
        }
    }

    fun validatePreferredModel(providerId: String?, modelId: String?): String? {
        return if ((providerId != null) != (modelId != null)) {
            "Preferred provider and model must both be set or both be empty."
        } else null
    }
}
```

## 实现步骤

### 阶段 1：数据层
1. [ ] 创建 `AgentEntity`，位于 `data/local/entity/AgentEntity.kt`
2. [ ] 创建 `AgentDao`，位于 `data/local/dao/AgentDao.kt`
3. [ ] 创建实体-领域模型映射器，位于 `data/local/mapper/AgentMapper.kt`
4. [ ] 创建 `AgentConstants`，位于 `core/model/AgentConstants.kt`（固定 ID 常量）
5. [ ] 创建 `ResolvedModel` 数据类，位于 `core/model/ResolvedModel.kt`
6. [ ] 将 Agent 种子数据 SQL 添加到 `AppDatabaseCallback.onCreate()`（扩展 RFC-003 的回调）
7. [ ] 在 `AppDatabase` 中注册 `AgentEntity` 和 `AgentDao`

### 阶段 2：Repository
8. [ ] 更新 `AgentRepository` 接口，位于 `core/repository/AgentRepository.kt`
9. [ ] 实现 `AgentRepositoryImpl`，位于 `data/repository/AgentRepositoryImpl.kt`
10. [ ] 将 `updateAgentForSessions()` 添加到 `SessionRepository` 接口（仅签名；实现在 RFC-005）

### 阶段 3：Use Cases
11. [ ] 实现 `CreateAgentUseCase`，位于 `feature/agent/usecase/`
12. [ ] 实现 `CloneAgentUseCase`
13. [ ] 实现 `DeleteAgentUseCase`
14. [ ] 实现 `GetAgentToolsUseCase`
15. [ ] 实现 `ResolveModelUseCase`
16. [ ] 创建 `AgentValidator`，位于 `feature/agent/AgentValidator.kt`

### 阶段 4：UI 层
17. [ ] 创建 `AgentListUiState`、`AgentDetailUiState`、`AgentSelectorUiState`，位于 `feature/agent/AgentUiState.kt`
18. [ ] 创建 `ToolOptionItem`、`ModelOptionItem`、`AgentSelectorItem` 数据类
19. [ ] 实现 `AgentListViewModel`
20. [ ] 实现 `AgentDetailViewModel`
21. [ ] 实现 `AgentListScreen`（Compose）
22. [ ] 实现 `AgentDetailScreen`（Compose）-- 同时处理创建和编辑模式
23. [ ] 实现 `AgentSelectorSheet`（Compose）-- 聊天界面的底部弹出
24. [ ] 在 NavGraph 中注册 Agent 界面的导航路由

### 阶段 5：DI 和集成
25. [ ] 更新 Koin 模块（DatabaseModule、RepositoryModule、FeatureModule）
26. [ ] 端到端测试：创建 Agent -> 编辑 -> 克隆 -> 删除 -> 回退
27. [ ] 测试 Agent 选择器底部弹出的 Agent 列表渲染
28. [ ] 测试模型解析（Agent 首选 -> 全局默认 -> 错误）

## 测试策略

### 单元测试
- `AgentRepositoryImpl`：CRUD 操作、内置保护（不可编辑/删除）、创建时生成 UUID
- `CreateAgentUseCase`：验证（空名称、空 prompt、provider/model 配对）、成功创建
- `CloneAgentUseCase`：克隆复制所有字段、克隆名称有"(Copy)"后缀、克隆非内置
- `DeleteAgentUseCase`：删除前调用会话回退、内置拒绝
- `GetAgentToolsUseCase`：将 tool ID 解析为定义、处理未知工具名称
- `ResolveModelUseCase`：Agent 首选 > 全局默认 > 错误，处理已删除的 provider/model
- `AgentValidator`：名称验证、prompt 验证、模型配对验证
- `AgentDetailViewModel`：所有表单字段的状态更新、保存、克隆、删除
- 实体-领域模型映射器：正确的字段映射、toolIds 的 JSON 序列化

### 集成测试（仪器化）
- 数据库种子数据：验证首次启动时"通用助手"Agent 存在，且具有正确的 ID、名称、prompt、工具
- Agent 种子数据和 provider 种子数据在同一回调中运行且不冲突
- 完整 CRUD 流程：创建 -> 读取 -> 更新 -> 删除
- 删除有活跃会话的 Agent -> 验证会话 Agent 变更为通用助手

### UI 测试
- Agent 列表先显示内置区域，后显示自定义
- 内置 Agent 详情显示只读字段、Clone 按钮、无 Save/Delete
- 自定义 Agent 详情显示可编辑字段、Save/Clone/Delete 按钮
- 创建 Agent 表单验证（空名称、空 prompt）
- 工具复选框正确切换
- Agent 选择器底部弹出渲染所有 Agent 并显示当前选择
- 删除确认对话框出现并正常工作

### 边界情况
- 用最大长度名称（100 字符）和 prompt（50,000 字符）创建 Agent
- 删除所有自定义 Agent（仅剩内置）
- 克隆一个 Agent，然后删除原始
- 克隆一个有首选模型的 Agent，然后删除该 provider
- Agent 的首选模型引用已删除/已停用的 provider（应静默降级）
- 创建两个同名 Agent
- 工具集为空的 Agent（纯聊天模式）

### 第二层视觉验证流程

每个流程相互独立，运行前请确认前置条件。
每个标注"截图"的步骤后截图并验证。

---

#### 流程 2-1：Agent 列表 — 内置 Agent 在首次启动时显示

**前置条件：** 应用已安装。通过 设置 -> 管理 Agent 导航。

```
目标：验证 Agent 列表的 BUILT-IN 区显示内置"General Assistant" Agent。

步骤：
1. 打开设置界面。
2. 点击"管理 Agent"。
3. 截图 -> 验证：
   - "BUILT-IN"区域标题可见。
   - "General Assistant"条目可见，带有"Built-in" chip 标签。
   - Agent 名称下方显示工具数量。
   - 无"CUSTOM"区（或 CUSTOM 区为空）。
```

---

#### 流程 2-2：查看内置 Agent — 只读

**前置条件：** Agent 列表可见（流程 2-1 通过）。

```
目标：验证内置 Agent 详情为只读（不可编辑、不可删除）。

步骤：
1. 点击"General Assistant"。
2. 截图 -> 验证：
   - 名称字段不可编辑（无光标、置灰或只读样式）。
   - 系统 prompt 字段不可编辑。
   - 工具复选框不可点击。
   - "Clone"按钮可见。
   - 无"Save"按钮。
   - 无"Delete Agent"按钮。
```

---

#### 流程 2-3：创建自定义 Agent

**前置条件：** Agent 列表可见。

```
目标：验证可创建新的自定义 Agent 并在 CUSTOM 区显示。

步骤：
1. 点击 Agent 列表界面的"+"（创建）按钮。
2. 截图 -> 验证：显示"Create Agent"表单；所有字段为空；Save 按钮禁用。
3. 输入名称："Test Agent"。
4. 输入系统 prompt："You are a test assistant."
5. 截图 -> 验证：Save 按钮现在已启用。
6. 点击"Save"。
7. 截图 -> 验证：
   - 返回 Agent 列表。
   - "CUSTOM"区现在可见。
   - "Test Agent"条目出现在 CUSTOM 区。
```

---

#### 流程 2-4：编辑自定义 Agent

**前置条件：** "Test Agent"已存在（流程 2-3 通过）。

```
目标：验证自定义 Agent 字段可编辑且修改能持久化。

步骤：
1. 点击 CUSTOM 区的"Test Agent"。
2. 截图 -> 验证："Edit Agent"表单显示可编辑字段；Save 按钮禁用（未作修改）。
3. 将名称改为"Test Agent v2"。
4. 截图 -> 验证：Save 按钮已启用。
5. 点击"Save"。
6. 截图 -> 验证：Agent 列表 CUSTOM 区显示"Test Agent v2"。
```

---

#### 流程 2-5：克隆内置 Agent

**前置条件：** Agent 列表可见。

```
目标：验证克隆会创建带正确名称前缀的新自定义 Agent。

步骤：
1. 点击"General Assistant"。
2. 点击"Clone"。
3. 截图 -> 验证：
   - 返回 Agent 列表（或显示克隆 Agent 的编辑表单）。
   - CUSTOM 区出现名称以"Copy of"开头的新条目（如"Copy of General Assistant"）。
4. 点击该克隆 Agent。
5. 截图 -> 验证：所有字段与原始 Agent 一致，但现在可编辑（可见 Save/Delete 按钮）。
```

---

#### 流程 2-6：删除自定义 Agent — 确认对话框

**前置条件：** 至少存在一个自定义 Agent（流程 2-3 或 2-5 通过）。

```
目标：验证删除自定义 Agent 时出现确认对话框，确认后从列表移除。

步骤：
1. 点击自定义 Agent（如"Test Agent v2"）。
2. 点击"Delete Agent"。
3. 截图 -> 验证：确认对话框出现，包含警告消息。
   预期：对话框说明使用该 Agent 的 session 将回退至 General Assistant。
4. 点击"Cancel"。
5. 截图 -> 验证：Agent 仍在列表中（删除已取消）。
6. 重新打开 Agent 详情，再次点击"Delete Agent"。
7. 在对话框中点击"Confirm"（或"Delete"）。
8. 截图 -> 验证：CUSTOM 区该 Agent 已移除。
```

---

#### 流程 2-7：聊天中的 Agent 选择器 — 切换 Agent

**前置条件：** 至少存在一个自定义 Agent。已配置带有效 API key 的提供商。

```
目标：验证 Agent 选择器底部弹出层出现且切换 Agent 正常工作。

步骤：
1. 导航至聊天界面。
2. 记录顶部栏当前 Agent 名称（如"General Assistant"）。
3. 点击顶部栏的 Agent 名称/下拉箭头。
4. 截图 -> 验证：
   - 底部弹出层出现，显示所有 Agent 列表。
   - 当前 Agent 高亮/已选中。
5. 点击另一个 Agent（如自定义 Agent）。
6. 截图 -> 验证：
   - 底部弹出层关闭。
   - 顶部栏 Agent 名称更新为新 Agent。
   - 聊天中出现系统消息"Switched to [Agent 名称]"。
```

## 安全考虑

1. **Agent 中无敏感数据**：Agent 数据（名称、prompt、工具列表）不是敏感信息。无需加密。
2. **内置 Agent 不可变性**：在 DAO 层（WHERE 子句）和 repository 层（显式检查）双重保证。
3. **工具集执行**：工具集是名称列表，不是可执行代码。实际工具执行通过 `ToolExecutionEngine` 进行，该引擎会重新验证工具可用性（见 RFC-004）。
4. **首选模型验证**：`ResolveModelUseCase` 在返回前验证 provider 是否活跃、模型是否存在。无效的偏好设置会被静默跳过，降级到全局默认。

## 依赖关系

### 依赖
- **RFC-000（总体架构）**：Agent 领域模型、项目结构、Koin 设置、Room 数据库
- **RFC-003（Provider 管理）**：Provider/模型数据用于首选模型选择器和模型解析；`ProviderRepository` 接口
- **RFC-004（工具系统）**：`ToolRegistry` 用于可用工具列表和工具定义查找

### 被依赖
- **RFC-001（对话交互）**：使用 `AgentSelectorSheet` 进行对话中途切换；使用 `ResolveModelUseCase` 选择模型；使用 `GetAgentToolsUseCase` 获取 API 调用的工具定义
- **RFC-005（会话管理）**：会话引用 `currentAgentId`；实现 `updateAgentForSessions()` 用于删除回退

## 与 RFC-000 的差异

本 RFC 引入以下需要反映到 RFC-000 的新增/变更：

1. **新增 `AgentConstants`**：新文件 `core/model/AgentConstants.kt`，包含 `GENERAL_ASSISTANT_ID` 常量。
2. **新增 `ResolvedModel`**：新数据类 `core/model/ResolvedModel.kt`。
3. **更新 `AgentRepository` 接口**：细化返回类型（update/delete 返回 `AppResult<Unit>`）。
4. **新增 `SessionRepository.updateAgentForSessions()`**：用于删除回退的新方法（在 RFC-005 中实现）。
5. **Agent 种子数据添加到 `AppDatabaseCallback`**：已有回调现在还包含通用助手 Agent 的种子数据。

## 开放问题

- [x] ~~内置 Agent 应存储在 Room DB 还是在代码中定义？~~ **决定：通过种子数据存入 Room DB**，与 provider 种子数据一致。固定 ID `agent-general-assistant`。
- [x] ~~通用助手的 system prompt 是什么？~~ **决定：见上方种子数据部分。**
- [x] ~~通用助手的固定 ID 是什么？~~ **决定：`agent-general-assistant`**
- [ ] 是否应限制自定义 Agent 的最大数量？V1 不限制。如果大量 Agent 列表出现性能问题，可能需要重新考虑。

## 参考资料

- [FEAT-002 PRD](../../prd/features/FEAT-002-agent-zh.md) -- 功能需求
- [UI 设计规范](../../design/ui-design-spec-zh.md) -- 第 5、6 节的视觉规范，以及第 2 节中的 Agent 选择器
- [RFC-000 总体架构](../architecture/RFC-000-overall-architecture-zh.md) -- 项目结构和数据模型
- [RFC-003 Provider 管理](RFC-003-provider-management-zh.md) -- Provider/模型数据、ProviderRepository
- [RFC-004 工具系统](RFC-004-tool-system-zh.md) -- ToolRegistry、ToolDefinition

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|---------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
