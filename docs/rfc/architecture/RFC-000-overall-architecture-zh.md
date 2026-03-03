# RFC-000: 整体架构

## 文档信息
- **RFC 编号**: RFC-000
- **关联 PRD**: 全部（FEAT-001 至 FEAT-009）
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
OneClaw 是一个 Android 应用，作为移动端 AI Agent 运行环境。在实现各个功能之前，我们需要确定基础技术决策：技术栈、架构模式、模块结构、核心数据模型和项目目录布局。所有后续的功能 RFC 都将建立在这些决策之上。

### 目标
1. 定义整个项目的技术栈
2. 定义整体架构模式和分层
3. 定义模块结构和模块间依赖关系
4. 定义跨模块共享的核心数据模型
5. 定义项目目录结构
6. 提供足够的细节，使 AI 辅助代码生成能产出一致、可复现的结果

### 非目标
- 任何特定功能的详细实现（在 RFC-001 至 RFC-005 中覆盖）
- UI/UX 设计规范（在 [UI 设计规范](../../design/ui-design-spec-zh.md) 和 PRD 中覆盖）
- 测试实现细节（在测试策略文档中覆盖）

## 技术栈

### 核心技术

| 技术 | 版本 | 用途 | 选择理由 |
|------|------|------|---------|
| Kotlin | 2.0.x | 主要语言 | Android 官方语言。现代、表达力强、空安全。 |
| Jetpack Compose | 1.6.x+ | UI 框架 | 现代声明式 UI。非常适合包含动态内容的聊天界面。比 XML 布局样板代码少。 |
| Kotlin Coroutines | 1.8.x | 异步编程 | Kotlin 原生异步。对顺序异步操作（API 调用、数据库查询、工具执行）语法简洁。 |
| Kotlin Flow | （Coroutines 的一部分） | 响应式流 | 流式 API 响应、实时 UI 更新、状态管理。 |
| Room | 2.6.x | 本地数据库（SQLite） | Jetpack 官方 ORM。类型安全查询、迁移支持、Flow 集成。 |
| OkHttp | 4.12.x | HTTP 客户端 | 行业标准。SSE 支持用于流式传输。拦截器用于认证头。 |
| Retrofit | 2.9.x | REST API 客户端 | 基于 OkHttp。类型安全的 API 定义。成熟生态。 |
| Koin | 3.5.x | 依赖注入 | 纯 Kotlin DSL，配置简单。无注解处理，无编译时开销。AI 易于生成正确代码。运行时依赖解析，可通过测试验证。 |
| Jetpack Navigation Compose | 2.7.x | 页面导航 | 官方 Compose 导航。类型安全路由。 |
| Android Keystore + EncryptedSharedPreferences | （Android SDK） | 密钥存储 | 加密存储 API key。支持硬件级安全的设备上有硬件保护。 |
| Kotlinx Serialization | 最新 | JSON 序列化 | 解析 API 响应，序列化工具参数和结果。Kotlin 原生方案。 |
| Coil | 3.x | 图片加载 | Compose 原生图片加载（为未来多模态支持准备）。轻量。 |
| compose-markdown (Mikepenz) | 最新 | Markdown 渲染 | 在 AI 响应中渲染 Markdown（代码块、表格、链接）。最活跃的 Compose Markdown 库。详见 RFC-001。 |

### 构建与工具

| 工具 | 版本 | 用途 |
|------|------|------|
| Gradle | 8.x | 构建系统 |
| Android Gradle Plugin | 8.x | Android 构建 |
| Min SDK | 26（Android 8.0） | 最低支持版本 |
| Target SDK | 35（Android 15） | 目标版本 |
| Compile SDK | 35 | 编译版本 |
| Kotlin Compiler Plugin | Compose Compiler | Compose 支持 |

### 关键库选择说明

#### 为什么选 Koin 而不是 Hilt
- **简单**：纯 Kotlin DSL，无注解，无代码生成。配置就是普通 Kotlin 代码。
- **AI 友好**：更简单的代码更容易被 AI 正确生成，也更容易让人类审查。
- **编译时间**：无注解处理步骤，构建更快。
- **项目规模足够**：运行时 DI 解析对这个复杂度的应用完全够用。
- **测试**：Koin 提供 `checkModules()` 在测试时验证所有依赖是否正确解析。

#### 为什么选 OkHttp + Retrofit 而不是 Ktor
- **生态成熟度**：大部分 Android 网络示例、教程和 StackOverflow 答案都使用 Retrofit。
- **SSE 支持**：OkHttp 对 SSE（Server-Sent Events）有可靠支持，用于流式 AI 响应。
- **AI 代码生成**：AI 模型在 Retrofit 模式上有更多训练数据，生成的代码更可靠。
- **拦截器模式**：清晰地注入 API key 和处理不同 provider 的认证。

#### 为什么选 Kotlinx Serialization 而不是 Gson
- **Kotlin 原生**：与 Kotlin 数据类原生配合，包括默认值和可空类型。
- **无反射**：编译时序列化，性能更好。
- **跨平台就绪**：如果未来考虑 KMP（Kotlin 跨平台）。

## 架构模式

### 整体：Clean Architecture + MVVM

应用遵循 Clean Architecture 的三层结构，UI 层结合 MVVM。

```
┌────────────────────────────────────────────────────┐
│                    UI 层                            │
│  （Compose 页面、ViewModels、UI State）              │
│                                                     │
│  Screen ←→ ViewModel ←→ UI State                   │
├────────────────────────────────────────────────────┤
│                   领域层                             │
│  （Use Cases、领域模型、Repository 接口）             │
│                                                     │
│  UseCase → Repository Interface                     │
├────────────────────────────────────────────────────┤
│                    数据层                            │
│  （Repository 实现、本地数据库、远程 API、工具）       │
│                                                     │
│  Repository Impl → DAO / API Service / Tool Engine │
└────────────────────────────────────────────────────┘
```

### 分层规则

#### UI 层
- **包含**：Compose 页面、ViewModels、UI state 数据类、UI 特定的映射器
- **依赖**：仅依赖领域层
- **禁止**：直接访问数据源（数据库、API、文件系统）
- **模式**：MVVM 单向数据流
  - ViewModel 向页面暴露 `StateFlow<UiState>`
  - 页面向 ViewModel 发送事件/操作
  - ViewModel 调用 UseCases，更新状态

#### 领域层
- **包含**：Use cases、领域模型数据类、repository 接口
- **依赖**：不依赖任何东西（这是最内层）
- **禁止**：包含 Android 特定导入（无 `Context`、无 `Activity`、无 Android SDK）
- **目的**：纯业务逻辑，无需 Android 框架即可测试

#### 数据层
- **包含**：Repository 实现、Room DAOs 和实体、Retrofit API 服务、数据映射器
- **依赖**：领域层（实现 repository 接口）
- **禁止**：向领域层暴露数据层特定类型（例如 Room 实体留在数据层；领域层得到领域模型）

### 数据流示例：用户发送消息

```
1. ChatScreen：用户点击发送按钮
   → ChatViewModel.sendMessage(text)

2. ChatViewModel：
   → 更新 UI 状态显示用户消息 + 加载指示器
   → 调用 SendMessageUseCase(sessionId, text)

3. SendMessageUseCase：
   → 通过 AgentRepository 获取当前 Agent 配置
   → 通过 ProviderRepository 获取 provider/模型配置
   → 通过 MessageRepository 保存用户消息
   → 调用 ModelApiService.sendToModel(messages, tools, systemPrompt)

4. ModelApiService（实现）：
   → 为特定 provider 格式化请求（OpenAI/Anthropic/Gemini 适配器）
   → 通过 Retrofit/OkHttp 发送 HTTP 请求
   → 通过 SSE 接收流式响应
   → 以 Flow<StreamChunk> 发射响应块

5. SendMessageUseCase：
   → 收集流式块
   → 如果块包含工具调用 → 委托给 ToolExecutionEngine
   → ToolExecutionEngine 运行工具，返回结果
   → 结果发送回模型（循环继续）
   → 通过 MessageRepository 保存 AI 响应和工具调用记录

6. ChatViewModel：
   → 从 UseCase 收集更新
   → 逐步更新 UI 状态（流式文本、工具调用状态）

7. ChatScreen：
   → 根据 UI 状态变化重组
   → 用户看到流式响应和工具调用指示
```

## 模块结构

### 功能模块

应用按功能模块组织。每个功能模块包含自己的 UI、ViewModel 和功能特定的 use cases。共享的领域模型和 repository 接口放在核心模块中。

```
┌─────────────────────────────────────────────┐
│                  App 模块                    │
│  （Application 类、Navigation、DI 设置）      │
├─────────────────────────────────────────────┤
│                  功能模块                     │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐      │
│  │  Chat   │ │  Agent  │ │ Provider │      │
│  │ (001)   │ │ (002)   │ │  (003)   │      │
│  └────┬────┘ └────┬────┘ └────┬─────┘      │
│  ┌────┴────┐ ┌────┴────┐ ┌────┴─────┐      │
│  │  Tool   │ │ Session │ │ Settings │      │
│  │ (004)   │ │ (005)   │ │  (009)   │      │
│  └────┬────┘ └────┬────┘ └────┬─────┘      │
│       │           │           │              │
├───────┴───────────┴───────────┴──────────────┤
│                  Core 模块                    │
│  （领域模型、Repository 接口、                  │
│   共享工具类、基类）                            │
├──────────────────────────────────────────────┤
│                  Data 模块                    │
│  （Room 数据库、Retrofit 服务、Repository       │
│   实现、Provider 适配器）                       │
└──────────────────────────────────────────────┘
```

### 模块依赖关系

```
App 模块
  ├── 依赖：所有功能模块、Core、Data
  │
功能模块（chat、agent、provider、tool、session、settings）
  ├── 依赖：Core 模块
  ├── 不直接依赖：其他功能模块
  │   （通信通过 Core 的领域层进行）
  │
Core 模块
  ├── 依赖：无（纯 Kotlin）
  │
Data 模块
  ├── 依赖：Core 模块
```

### 模块间通信

功能模块之间不直接依赖。它们通过以下方式通信：
1. **Core 中的共享领域模型**（例如 `Session`、`Agent`、`Message` 在 Core 中定义）
2. **Core 中的 Repository 接口**（例如 `AgentRepository` 接口在 Core 中，实现在 Data 中）
3. **App 模块中的 Navigation**（App 模块知道所有功能模块并设置导航）
4. **Koin DI** 在运行时解析跨模块依赖

## 核心数据模型

这些是在 Core 模块中定义的共享领域模型，跨多个功能使用。

### Agent

```kotlin
data class Agent(
    val id: String,                    // UUID
    val name: String,                  // 显示名称
    val description: String?,          // 可选描述
    val systemPrompt: String,          // System prompt 文本
    val toolIds: List<String>,         // 该 Agent 可用的工具 ID 列表
    val preferredProviderId: String?,  // 可选首选 provider
    val preferredModelId: String?,     // 可选首选模型
    val isBuiltIn: Boolean,            // 是否为内置 Agent
    val createdAt: Long,               // 时间戳（毫秒）
    val updatedAt: Long                // 时间戳（毫秒）
)
```

### Provider

```kotlin
data class Provider(
    val id: String,                    // UUID
    val name: String,                  // 显示名称
    val type: ProviderType,            // API 协议格式：OPENAI, ANTHROPIC, GEMINI
    val apiBaseUrl: String,            // API 请求的基础 URL
    val isPreConfigured: Boolean,      // true = 内置模板，false = 用户创建
    val isActive: Boolean,             // 是否已启用
    val createdAt: Long,
    val updatedAt: Long
)
// 注意：API key 不存储在 Provider 领域模型或 Room 数据库中。
// 它们通过 ApiKeyStorage 单独存储在 EncryptedSharedPreferences 中。
// 访问 provider 的 API key 请使用：ApiKeyStorage.getApiKey(providerId)

enum class ProviderType {
    OPENAI,     // OpenAI 兼容 API 格式（也用于兼容 OpenAI 的自定义端点）
    ANTHROPIC,  // Anthropic API 格式
    GEMINI      // Google Gemini API 格式
}
// 注意：没有 CUSTOM 类型。`type` 字段表示 API 协议格式，而非服务身份。
// 用户创建的提供商根据端点兼容的 API 格式选择 OPENAI、ANTHROPIC 或 GEMINI。
// `isPreConfigured` 字段区分内置模板和用户创建的提供商。
// 详见 RFC-003。
```

### Model

```kotlin
data class AiModel(
    val id: String,                    // 模型标识符（例如 "gpt-4o"）
    val displayName: String?,          // 人类友好名称
    val providerId: String,            // 所属 provider
    val isDefault: Boolean,            // 是否为全局默认
    val source: ModelSource            // 模型添加方式
)

enum class ModelSource {
    DYNAMIC,   // 从 provider API 获取
    PRESET,    // 预配置兜底
    MANUAL     // 用户手动添加
}
```

### Session

```kotlin
data class Session(
    val id: String,                    // UUID
    val title: String,                 // 显示标题
    val currentAgentId: String,        // 当前会话的 Agent
    val messageCount: Int,             // 消息数量
    val lastMessagePreview: String?,   // 截断的最后消息文本，用于列表显示
    val isActive: Boolean,             // 是否有进行中的请求
    val deletedAt: Long?,              // 软删除时间戳（null = 未删除）
    val createdAt: Long,
    val updatedAt: Long                // 最后活动时间戳
)
```

### ResolvedModel

```kotlin
/**
 * 将 AiModel 与其 Provider 配对，用于模型解析结果。
 * 由 ResolveModelUseCase（RFC-002）使用，一起返回已解析的 model + provider。
 */
data class ResolvedModel(
    val model: AiModel,
    val provider: Provider
)
```

### Message

```kotlin
data class Message(
    val id: String,                    // UUID
    val sessionId: String,             // 所属会话
    val type: MessageType,             // USER, AI_RESPONSE, TOOL_CALL, TOOL_RESULT, ERROR, SYSTEM
    val content: String,               // 消息文本内容
    val thinkingContent: String?,      // AI 思考/推理内容（AI_RESPONSE 类型）
    val toolCallId: String?,           // 工具调用 ID（TOOL_CALL 和 TOOL_RESULT 类型）
    val toolName: String?,             // 工具名称（TOOL_CALL 类型）
    val toolInput: String?,            // 工具输入 JSON（TOOL_CALL 类型）
    val toolOutput: String?,           // 工具输出 JSON（TOOL_RESULT 类型）
    val toolStatus: ToolCallStatus?,   // 工具执行状态
    val toolDurationMs: Long?,         // 工具执行时长
    val tokenCountInput: Int?,         // 输入 token 数（如可用）
    val tokenCountOutput: Int?,        // 输出 token 数（如可用）
    val modelId: String?,              // 生成此消息的模型（AI_RESPONSE）
    val providerId: String?,           // 使用的 provider
    val createdAt: Long                // 时间戳
)

enum class MessageType {
    USER,          // 用户文字消息
    AI_RESPONSE,   // AI 文字响应（可能包含思考内容）
    TOOL_CALL,     // AI 请求工具调用
    TOOL_RESULT,   // 工具执行返回的结果
    ERROR,         // 错误消息（API 失败、工具失败等）
    SYSTEM         // 系统消息（例如"已切换到 Agent X"）
}

enum class ToolCallStatus {
    PENDING,       // 工具调用已请求，尚未执行
    EXECUTING,     // 工具正在运行
    SUCCESS,       // 工具成功完成
    ERROR,         // 工具失败
    TIMEOUT        // 工具超时
}
```

### ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,                  // 唯一工具名称（snake_case）
    val description: String,           // 人类可读描述
    val parametersSchema: ToolParametersSchema, // 结构化参数 schema（详见 RFC-004）
    val requiredPermissions: List<String>,  // 所需 Android 权限
    val timeoutSeconds: Int            // 最大执行时间
)

data class ToolParametersSchema(
    val properties: Map<String, ToolParameter>,   // 参数名 -> 定义
    val required: List<String> = emptyList()       // 必填参数名称
)

data class ToolParameter(
    val type: String,                  // "string", "integer", "number", "boolean", "object", "array"
    val description: String,           // 人类可读描述
    val enum: List<String>? = null,    // 允许的值（如果有限制）
    val default: Any? = null           // 默认值（如果可选）
)
```

### ToolResult

```kotlin
data class ToolResult(
    val status: ToolResultStatus,      // SUCCESS 或 ERROR
    val result: String?,               // 结果数据（成功时）
    val errorType: String?,            // 错误类型（错误时）
    val errorMessage: String?          // 错误消息（错误时）
)

enum class ToolResultStatus {
    SUCCESS, ERROR
}
```

## 数据库 Schema

使用以下表的 Room 数据库。Room 实体在数据层中，与领域模型相互映射。

### 表

#### agents
```sql
CREATE TABLE agents (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    tool_ids TEXT NOT NULL,           -- 工具 ID 字符串的 JSON 数组
    preferred_provider_id TEXT,
    preferred_model_id TEXT,
    is_built_in INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

#### providers
```sql
CREATE TABLE providers (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,               -- "OPENAI", "ANTHROPIC", "GEMINI"
    api_base_url TEXT NOT NULL,
    is_pre_configured INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
-- 注意：API key 不存储在此表中。
-- 它们通过 ApiKeyStorage 类存储在 EncryptedSharedPreferences（Android Keystore 支持）中，
-- 以 provider ID 作为键。
```

#### models
```sql
CREATE TABLE models (
    id TEXT NOT NULL,
    display_name TEXT,
    provider_id TEXT NOT NULL,
    is_default INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,             -- "DYNAMIC", "PRESET", "MANUAL"
    PRIMARY KEY (id, provider_id),
    FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);
```

#### sessions
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

#### messages
```sql
CREATE TABLE messages (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL,               -- "USER", "AI_RESPONSE", "TOOL_CALL", "TOOL_RESULT", "ERROR", "SYSTEM"
    content TEXT NOT NULL,
    thinking_content TEXT,
    tool_call_id TEXT,
    tool_name TEXT,
    tool_input TEXT,                  -- JSON 字符串
    tool_output TEXT,                 -- JSON 字符串
    tool_status TEXT,                 -- "PENDING", "EXECUTING", "SUCCESS", "ERROR", "TIMEOUT"
    tool_duration_ms INTEGER,
    token_count_input INTEGER,
    token_count_output INTEGER,
    model_id TEXT,
    provider_id TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_session_id ON messages(session_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);
```

#### app_settings
```sql
CREATE TABLE app_settings (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
```

## Repository 接口

在 Core 模块中定义，在 Data 模块中实现。

```kotlin
interface AgentRepository {
    fun getAllAgents(): Flow<List<Agent>>
    suspend fun getAgentById(id: String): Agent?
    suspend fun createAgent(agent: Agent): Agent
    suspend fun updateAgent(agent: Agent): AppResult<Unit>
    suspend fun deleteAgent(id: String): AppResult<Unit>
    suspend fun getBuiltInAgents(): List<Agent>
}
// 详见 RFC-002 了解 Agent 管理的完整细节，包括内置 Agent 种子数据、
// 克隆操作和模型解析逻辑。

interface ProviderRepository {
    fun getAllProviders(): Flow<List<Provider>>
    fun getActiveProviders(): Flow<List<Provider>>
    suspend fun getProviderById(id: String): Provider?
    suspend fun createProvider(provider: Provider)
    suspend fun updateProvider(provider: Provider)
    suspend fun deleteProvider(id: String): AppResult<Unit>
    suspend fun setProviderActive(id: String, isActive: Boolean)
    suspend fun getModelsForProvider(providerId: String): List<AiModel>
    suspend fun fetchModelsFromApi(providerId: String): AppResult<List<AiModel>>
    suspend fun addManualModel(providerId: String, modelId: String, displayName: String?): AppResult<Unit>
    suspend fun deleteManualModel(providerId: String, modelId: String): AppResult<Unit>
    suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult>
    fun getGlobalDefaultModel(): Flow<AiModel?>
    suspend fun setGlobalDefaultModel(modelId: String, providerId: String)
}
// 详见 RFC-003 了解提供商管理的完整细节，包括预配置提供商种子数据、
// 模型列表获取、手动模型操作和连接测试。

interface SessionRepository {
    fun getAllSessions(): Flow<List<Session>>
    suspend fun getSessionById(id: String): Session?
    suspend fun createSession(session: Session): Session
    suspend fun updateSession(session: Session)
    suspend fun softDeleteSession(id: String)
    suspend fun softDeleteSessions(ids: List<String>)
    suspend fun restoreSession(id: String)
    suspend fun restoreSessions(ids: List<String>)
    suspend fun hardDeleteSession(id: String)
    suspend fun hardDeleteAllSoftDeleted()
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)
    suspend fun updateTitle(id: String, title: String)
    suspend fun setGeneratedTitle(id: String, title: String)
    suspend fun updateMessageStats(id: String, count: Int, preview: String?)
    suspend fun setActive(id: String, isActive: Boolean)
    suspend fun updateCurrentAgent(id: String, agentId: String)
}
// 软删除：Session 有 deleted_at 字段。softDeleteSession 设置它；restoreSession 清除它。
// hardDeleteSession 永久删除会话及其消息（CASCADE）。
// hardDeleteAllSoftDeleted：应用启动时调用，清理处于软删除状态的会话。
// updateAgentForSessions：将引用 oldAgentId 的所有会话更新为使用 newAgentId。
//   供 DeleteAgentUseCase（RFC-002）在删除 Agent 时回退到通用助手。
// 详见 RFC-005 了解会话管理的完整细节。

interface MessageRepository {
    fun getMessagesForSession(sessionId: String): Flow<List<Message>>
    suspend fun addMessage(message: Message): Message
    suspend fun updateMessage(message: Message)
    suspend fun deleteMessagesForSession(sessionId: String)
    suspend fun getMessageCount(sessionId: String): Int
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>  // 非响应式快照，用于构建 API 请求（RFC-001）
    suspend fun deleteMessage(id: String)                               // 删除单条消息；用于重新生成和重试（RFC-001）
}

interface SettingsRepository {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean
    suspend fun setBoolean(key: String, value: Boolean)
}
```

## Provider 适配器模式

不同 AI provider 有不同的 API 格式。我们使用适配器模式进行抽象。

```kotlin
// 所有 provider 适配器的核心接口
interface ModelApiAdapter {
    // 发送带流式的聊天完成请求
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent>

    // 获取可用模型
    suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>>

    // 测试连接
    suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult>

    // 将工具定义转换为 provider 特定格式
    fun formatToolDefinitions(tools: List<ToolDefinition>): Any

    // 发送简单（非流式）聊天补全请求。
    // 用于标题生成等轻量级任务（RFC-005）。
    // 完整流式实现在 RFC-001 中。
    suspend fun generateSimpleCompletion(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = 100
    ): AppResult<String>
}

// 流式响应期间发射的流事件
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolCallStart(val toolCallId: String, val toolName: String) : StreamEvent()
    data class ToolCallDelta(val toolCallId: String, val argumentsDelta: String) : StreamEvent()
    data class ToolCallEnd(val toolCallId: String) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent()
    data class Error(val message: String, val code: String?) : StreamEvent()
    object Done : StreamEvent()
}

// 实现（每个都接收 OkHttpClient 用于 HTTP 调用）
class OpenAiAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }
class AnthropicAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }
class GeminiAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }

// 获取正确适配器的工厂
class ModelApiAdapterFactory(private val okHttpClient: OkHttpClient) {
    fun getAdapter(providerType: ProviderType): ModelApiAdapter {
        return when (providerType) {
            ProviderType.OPENAI -> OpenAiAdapter(okHttpClient)
            ProviderType.ANTHROPIC -> AnthropicAdapter(okHttpClient)
            ProviderType.GEMINI -> GeminiAdapter(okHttpClient)
            // 注意：没有 CUSTOM 类型。用户创建的端点选择 OPENAI/ANTHROPIC/GEMINI 作为协议格式。
        }
    }
}
```

## 工具执行引擎

```kotlin
// 所有工具必须实现的接口
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any?>): ToolResult
}

// 工具注册表
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<ToolDefinition> = tools.values.map { it.definition }

    fun getToolsByIds(ids: List<String>): List<ToolDefinition> =
        ids.mapNotNull { tools[it]?.definition }
}

// 工具执行引擎
class ToolExecutionEngine(
    private val registry: ToolRegistry,
    private val permissionChecker: PermissionChecker
) {
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolIds: List<String>
    ): ToolResult {
        // 1. 检查工具是否存在且可用
        val tool = registry.getTool(toolName)
            ?: return ToolResult.error("tool_not_found", "工具 '$toolName' 未找到")

        if (toolName !in availableToolIds) {
            return ToolResult.error("tool_not_available", "工具 '$toolName' 对此 Agent 不可用")
        }

        // 2. 检查权限
        val missingPermissions = permissionChecker.getMissingPermissions(tool.definition.requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val granted = permissionChecker.requestPermissions(missingPermissions)
            if (!granted) {
                return ToolResult.error("permission_denied", "所需权限被拒绝")
            }
        }

        // 3. 带超时执行
        return try {
            withTimeout(tool.definition.timeoutSeconds * 1000L) {
                tool.execute(parameters)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "工具执行超时，超过 ${tool.definition.timeoutSeconds} 秒")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "工具执行失败：${e.message}")
        }
    }
}
```

## 项目目录结构

```
app/
├── build.gradle.kts
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/oneclaw/shadow/
│   │   │   │
│   │   │   ├── OneclawApplication.kt          # Application 类，Koin 初始化
│   │   │   ├── MainActivity.kt                # 单 Activity（Compose）
│   │   │   │
│   │   │   ├── core/                           # Core 模块
│   │   │   │   ├── model/                      # 领域模型
│   │   │   │   │   ├── Agent.kt
│   │   │   │   │   ├── Provider.kt
│   │   │   │   │   ├── AiModel.kt
│   │   │   │   │   ├── Session.kt
│   │   │   │   │   ├── Message.kt
│   │   │   │   │   ├── ToolDefinition.kt
│   │   │   │   │   ├── ToolResult.kt
│   │   │   │   │   ├── ResolvedModel.kt
│   │   │   │   │   └── AgentConstants.kt        # GENERAL_ASSISTANT_ID 常量（RFC-002）
│   │   │   │   ├── repository/                 # Repository 接口
│   │   │   │   │   ├── AgentRepository.kt
│   │   │   │   │   ├── ProviderRepository.kt
│   │   │   │   │   ├── SessionRepository.kt
│   │   │   │   │   ├── MessageRepository.kt
│   │   │   │   │   └── SettingsRepository.kt
│   │   │   │   └── util/                       # 共享工具类
│   │   │   │       ├── Result.kt               # Result 包装器
│   │   │   │       └── DateTimeUtils.kt
│   │   │   │
│   │   │   ├── data/                           # Data 模块
│   │   │   │   ├── local/                      # 本地数据源
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── AppDatabase.kt      # Room 数据库定义
│   │   │   │   │   │   └── Converters.kt       # 类型转换器
│   │   │   │   │   ├── dao/                    # Room DAOs
│   │   │   │   │   │   ├── AgentDao.kt
│   │   │   │   │   │   ├── ProviderDao.kt
│   │   │   │   │   │   ├── ModelDao.kt
│   │   │   │   │   │   ├── SessionDao.kt
│   │   │   │   │   │   ├── MessageDao.kt
│   │   │   │   │   │   └── SettingsDao.kt
│   │   │   │   │   ├── entity/                 # Room 实体
│   │   │   │   │   │   ├── AgentEntity.kt
│   │   │   │   │   │   ├── ProviderEntity.kt
│   │   │   │   │   │   ├── ModelEntity.kt
│   │   │   │   │   │   ├── SessionEntity.kt
│   │   │   │   │   │   ├── MessageEntity.kt
│   │   │   │   │   │   └── SettingsEntity.kt
│   │   │   │   │   └── mapper/                 # 实体 <-> 领域模型映射器
│   │   │   │   │       ├── AgentMapper.kt
│   │   │   │   │       ├── ProviderMapper.kt
│   │   │   │   │       ├── SessionMapper.kt
│   │   │   │   │       └── MessageMapper.kt
│   │   │   │   ├── remote/                     # 远程数据源
│   │   │   │   │   ├── adapter/                # Provider API 适配器
│   │   │   │   │   │   ├── ModelApiAdapter.kt          # 接口
│   │   │   │   │   │   ├── ModelApiAdapterFactory.kt
│   │   │   │   │   │   ├── OpenAiAdapter.kt
│   │   │   │   │   │   ├── AnthropicAdapter.kt
│   │   │   │   │   │   ├── GeminiAdapter.kt
│   │   │   │   │   │   └── ApiMessage.kt       # ApiMessage sealed class、ApiToolCall（RFC-001）
│   │   │   │   │   ├── dto/                    # 数据传输对象
│   │   │   │   │   │   ├── openai/
│   │   │   │   │   │   ├── anthropic/
│   │   │   │   │   │   └── gemini/
│   │   │   │   │   └── sse/                    # SSE 流式支持
│   │   │   │   │       └── SseParser.kt        # ResponseBody.asSseFlow() 工具方法（RFC-001）
│   │   │   │   ├── repository/                 # Repository 实现
│   │   │   │   │   ├── AgentRepositoryImpl.kt
│   │   │   │   │   ├── ProviderRepositoryImpl.kt
│   │   │   │   │   ├── SessionRepositoryImpl.kt
│   │   │   │   │   ├── MessageRepositoryImpl.kt
│   │   │   │   │   └── SettingsRepositoryImpl.kt
│   │   │   │   └── security/                   # API key 存储
│   │   │   │       └── ApiKeyStorage.kt        # EncryptedSharedPreferences 封装
│   │   │   │
│   │   │   ├── tool/                           # Tool 模块
│   │   │   │   ├── engine/
│   │   │   │   │   ├── Tool.kt                 # Tool 接口
│   │   │   │   │   ├── ToolRegistry.kt
│   │   │   │   │   ├── ToolExecutionEngine.kt
│   │   │   │   │   ├── ToolSchemaSerializer.kt  # ToolParametersSchema -> JSON Schema（RFC-004）
│   │   │   │   │   └── PermissionChecker.kt
│   │   │   │   └── builtin/                    # 内置工具实现
│   │   │   │       ├── GetCurrentTimeTool.kt
│   │   │   │       ├── ReadFileTool.kt
│   │   │   │       ├── WriteFileTool.kt
│   │   │   │       └── HttpRequestTool.kt
│   │   │   │
│   │   │   ├── feature/                        # 功能模块（UI + ViewModels）
│   │   │   │   ├── chat/                       # FEAT-001
│   │   │   │   │   ├── ChatScreen.kt
│   │   │   │   │   ├── ChatViewModel.kt
│   │   │   │   │   ├── ChatUiState.kt          # ChatUiState、ChatMessageItem、ActiveToolCall
│   │   │   │   │   ├── ChatEvent.kt            # Sealed class：StreamingText、ThinkingText、ToolCallStarted 等
│   │   │   │   │   ├── components/             # 聊天特定 UI 组件
│   │   │   │   │   │   ├── ChatTopBar.kt
│   │   │   │   │   │   ├── ChatInput.kt
│   │   │   │   │   │   ├── MessageList.kt
│   │   │   │   │   │   ├── UserMessageBubble.kt
│   │   │   │   │   │   ├── AiMessageBubble.kt
│   │   │   │   │   │   ├── ThinkingBlock.kt
│   │   │   │   │   │   ├── ToolCallCard.kt
│   │   │   │   │   │   ├── ToolResultCard.kt
│   │   │   │   │   │   ├── ErrorMessageCard.kt
│   │   │   │   │   │   ├── SystemMessageCard.kt
│   │   │   │   │   │   ├── EmptyChatState.kt
│   │   │   │   │   │   └── StreamingCursor.kt
│   │   │   │   │   └── usecase/                # 聊天特定 use cases
│   │   │   │   │       ├── SendMessageUseCase.kt
│   │   │   │   │       └── MessageToApiMapper.kt  # Message -> ApiMessage 转换
│   │   │   │   │
│   │   │   │   ├── agent/                      # FEAT-002
│   │   │   │   │   ├── AgentListScreen.kt
│   │   │   │   │   ├── AgentDetailScreen.kt
│   │   │   │   │   ├── AgentSelectorSheet.kt    # 聊天中切换 Agent 的底部弹出
│   │   │   │   │   ├── AgentListViewModel.kt
│   │   │   │   │   ├── AgentDetailViewModel.kt
│   │   │   │   │   ├── AgentUiState.kt
│   │   │   │   │   ├── AgentValidator.kt        # Agent 字段输入验证
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── CreateAgentUseCase.kt
│   │   │   │   │       ├── CloneAgentUseCase.kt
│   │   │   │   │       ├── DeleteAgentUseCase.kt
│   │   │   │   │       ├── GetAgentToolsUseCase.kt
│   │   │   │   │       └── ResolveModelUseCase.kt
│   │   │   │   │
│   │   │   │   ├── provider/                   # FEAT-003
│   │   │   │   │   ├── ProviderListScreen.kt
│   │   │   │   │   ├── ProviderDetailScreen.kt
│   │   │   │   │   ├── SetupScreen.kt          # 首次设置
│   │   │   │   │   ├── ProviderListViewModel.kt
│   │   │   │   │   ├── ProviderDetailViewModel.kt
│   │   │   │   │   ├── ProviderUiState.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── TestConnectionUseCase.kt
│   │   │   │   │       ├── FetchModelsUseCase.kt
│   │   │   │   │       └── SetDefaultModelUseCase.kt
│   │   │   │   │
│   │   │   │   ├── session/                    # FEAT-005
│   │   │   │   │   ├── SessionDrawerContent.kt # 抽屉 composable（会话列表）
│   │   │   │   │   ├── SessionListViewModel.kt
│   │   │   │   │   ├── SessionUiState.kt
│   │   │   │   │   ├── components/
│   │   │   │   │   │   ├── SessionListItemRow.kt
│   │   │   │   │   │   └── RenameSessionDialog.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── CreateSessionUseCase.kt
│   │   │   │   │       ├── DeleteSessionUseCase.kt
│   │   │   │   │       ├── BatchDeleteSessionsUseCase.kt
│   │   │   │   │       ├── RenameSessionUseCase.kt
│   │   │   │   │       ├── GenerateTitleUseCase.kt
│   │   │   │   │       └── CleanupSoftDeletedUseCase.kt
│   │   │   │   │
│   │   │   │   └── settings/                   # FEAT-009
│   │   │   │       ├── SettingsScreen.kt
│   │   │   │       ├── SettingsViewModel.kt
│   │   │   │       └── SettingsUiState.kt
│   │   │   │
│   │   │   ├── navigation/                     # 应用导航
│   │   │   │   ├── NavGraph.kt
│   │   │   │   └── Routes.kt
│   │   │   │
│   │   │   ├── di/                             # Koin 依赖注入模块
│   │   │   │   ├── AppModule.kt                # 应用级依赖
│   │   │   │   ├── DatabaseModule.kt           # Room 数据库、DAOs
│   │   │   │   ├── NetworkModule.kt            # OkHttp、Retrofit
│   │   │   │   ├── RepositoryModule.kt         # Repository 绑定
│   │   │   │   ├── ToolModule.kt               # Tool 注册表、工具
│   │   │   │   └── FeatureModule.kt            # ViewModels、UseCases
│   │   │   │
│   │   │   └── ui/                             # 共享 UI 组件和主题
│   │   │       ├── theme/
│   │   │       │   ├── Theme.kt
│   │   │       │   ├── Color.kt
│   │   │       │   ├── Typography.kt
│   │   │       │   └── Shape.kt
│   │   │       └── components/                 # 共享/可复用 UI 组件
│   │   │           ├── LoadingIndicator.kt
│   │   │           ├── ErrorMessage.kt
│   │   │           └── ConfirmationDialog.kt
│   │   │
│   │   └── res/
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   ├── colors.xml
│   │       │   └── themes.xml
│   │       ├── values-zh/
│   │       │   └── strings.xml                 # 中文翻译
│   │       ├── drawable/
│   │       └── mipmap/
│   │
│   ├── test/                                   # 单元测试
│   │   └── kotlin/com/oneclaw/shadow/
│   │       ├── core/
│   │       ├── data/
│   │       ├── tool/
│   │       └── feature/
│   │
│   └── androidTest/                            # 插桩测试
│       └── kotlin/com/oneclaw/shadow/
│           ├── data/
│           └── feature/
```

## Koin 依赖注入配置

```kotlin
// AppModule.kt - 应用级依赖
val appModule = module {
    single { ApiKeyStorage(get()) }  // 用于 API key 的 EncryptedSharedPreferences
    single { ModelApiAdapterFactory(get()) }  // get() = NetworkModule 中的 OkHttpClient
}

// DatabaseModule.kt
val databaseModule = module {
    single { Room.databaseBuilder(get(), AppDatabase::class.java, "oneclaw.db").build() }
    single { get<AppDatabase>().agentDao() }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().modelDao() }
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().settingsDao() }
}

// NetworkModule.kt
val networkModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)    // 流式传输需要更长时间
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

// RepositoryModule.kt
val repositoryModule = module {
    single<AgentRepository> { AgentRepositoryImpl(get()) }
    single<ProviderRepository> { ProviderRepositoryImpl(get(), get(), get(), get()) }
    single<SessionRepository> { SessionRepositoryImpl(get()) }
    single<MessageRepository> { MessageRepositoryImpl(get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
}

// ToolModule.kt
val toolModule = module {
    single { ToolRegistry().apply {
        register(GetCurrentTimeTool())
        register(ReadFileTool())
        register(WriteFileTool())
        register(HttpRequestTool(get()))  // 需要 OkHttpClient
    }}
    single { PermissionChecker(get()) }
    single { ToolExecutionEngine(get(), get()) }
}

// FeatureModule.kt
val featureModule = module {
    // Chat
    factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    // SendMessageUseCase 参数：AgentRepository, SessionRepository, MessageRepository,
    //   ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory, ToolExecutionEngine, ToolRegistry
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    // ChatViewModel 参数：SendMessageUseCase, SessionRepository, MessageRepository,
    //   AgentRepository, CreateSessionUseCase, GenerateTitleUseCase

    // Agent
    factory { CreateAgentUseCase(get()) }                    // AgentRepository
    factory { CloneAgentUseCase(get()) }                     // AgentRepository
    factory { DeleteAgentUseCase(get(), get()) }             // AgentRepository, SessionRepository
    factory { GetAgentToolsUseCase(get(), get()) }           // AgentRepository, ToolRegistry
    factory { ResolveModelUseCase(get(), get()) }            // AgentRepository, ProviderRepository
    viewModel { AgentListViewModel(get(), get()) }           // AgentRepository, ToolRegistry
    viewModel { AgentDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    // AgentDetailViewModel 参数：AgentRepository, ProviderRepository, ToolRegistry,
    //   CreateAgentUseCase, CloneAgentUseCase, DeleteAgentUseCase, SavedStateHandle

    // Provider
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }
    viewModel { ProviderListViewModel(get(), get()) }        // ProviderRepository, ApiKeyStorage
    viewModel { ProviderDetailViewModel(get(), get(), get(), get(), get(), get()) }
    // ProviderDetailViewModel 参数：ProviderRepository, ApiKeyStorage,
    //   TestConnectionUseCase, FetchModelsUseCase, SetDefaultModelUseCase, SavedStateHandle

    // Session
    factory { CreateSessionUseCase(get()) }
    factory { DeleteSessionUseCase(get()) }
    factory { BatchDeleteSessionsUseCase(get()) }
    factory { RenameSessionUseCase(get()) }
    factory { GenerateTitleUseCase(get(), get(), get(), get()) }  // SessionRepository, ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory
    factory { CleanupSoftDeletedUseCase(get()) }
    viewModel { SessionListViewModel(get(), get(), get(), get(), get()) }  // SessionRepository, AgentRepository, DeleteSessionUseCase, BatchDeleteSessionsUseCase, RenameSessionUseCase

    // Settings
    viewModel { SettingsViewModel(get()) }
}

// Application 类
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OneclawApplication)
            modules(
                appModule,
                databaseModule,
                networkModule,
                repositoryModule,
                toolModule,
                featureModule
            )
        }

        // 清理已软删除但未硬删除的会话
        // （例如应用在撤销窗口期间被杀死）。详见 RFC-005。
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val cleanup: CleanupSoftDeletedUseCase = get()
            cleanup()
        }
    }
}
```

## 导航结构

```kotlin
// Routes.kt
sealed class Route(val path: String) {
    object SessionList : Route("sessions")
    object Chat : Route("chat/{sessionId}") {
        fun create(sessionId: String) = "chat/$sessionId"
    }
    object NewChat : Route("chat/new")
    object AgentList : Route("agents")
    object AgentDetail : Route("agents/{agentId}") {
        fun create(agentId: String) = "agents/$agentId"
    }
    object AgentCreate : Route("agents/create")
    object ProviderList : Route("providers")
    object ProviderDetail : Route("providers/{providerId}") {
        fun create(providerId: String) = "providers/$providerId"
    }
    object Setup : Route("setup")
    object Settings : Route("settings")
}

// 导航流程：
// 应用首次启动（未配置 provider）→ 欢迎/设置页面（可跳过）
//   - 用户可以设置 provider，或点击"稍后设置"直接进入聊天
//   - 欢迎页面仅在首次启动时显示；之后始终进入聊天
// 应用后续启动 → Chat/new（空聊天，首页）
// 用户在未配置 provider 时尝试发消息：
//   - 显示内联错误，提示配置 provider，附带设置链接
// 聊天页面有：左侧抽屉（会话列表），右侧设置
// 抽屉 → NewChat / Chat(sessionId)
// Chat → AgentSwitcher（通过顶栏 Agent 名称触发的底部弹出）
// Settings → ProviderList / ProviderDetail / AgentList / AgentDetail
// AgentList → AgentDetail / AgentCreate
// ProviderList → ProviderDetail
//
// 详细页面布局见 UI 设计规范（docs/design/ui-design-spec-zh.md）
```

## 错误处理策略

### Result 包装器

```kotlin
// 整个应用中用于可能失败的操作
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(
        val exception: Exception? = null,
        val message: String,
        val code: ErrorCode = ErrorCode.UNKNOWN
    ) : AppResult<Nothing>()
}

enum class ErrorCode {
    NETWORK_ERROR,
    AUTH_ERROR,
    TIMEOUT_ERROR,
    VALIDATION_ERROR,
    STORAGE_ERROR,
    PERMISSION_ERROR,
    PROVIDER_ERROR,
    TOOL_ERROR,
    UNKNOWN
}
```

### 按层错误处理

1. **数据层**：捕获异常，用适当的 `ErrorCode` 包装为 `AppResult.Error`
2. **领域层**：传递 `AppResult`，可能添加业务逻辑验证错误
3. **UI 层**：ViewModel 将 `AppResult.Error` 转换为 UI 状态中面向用户的错误消息

## 实现阶段

### 阶段 1：项目基础
1. 创建 Android 项目，正确的包名和 SDK 版本
2. 设置 Gradle 依赖（技术栈中列出的所有库）
3. 设置 Koin 依赖注入模块（初始为空）
4. 创建 Room 数据库，包含所有实体类和 DAOs
5. 在 Core 中创建所有领域模型
6. 在 Core 中创建所有 repository 接口
7. 在 Data 中创建 repository 实现（基本 CRUD）
8. 设置 Compose 主题（颜色、排版、形状）
9. 设置导航图，使用占位页面
10. 验证应用能编译和运行

### 阶段 2：Provider 和模型（FEAT-003）
1. 实现 provider 适配器模式（OpenAI、Anthropic、Gemini 适配器）
2. 实现 API key 加密
3. 实现 provider 管理页面（列表、详情、设置）
4. 实现模型列表获取（动态 + 预设兜底）
5. 实现连接测试
6. 实现首次设置流程
7. 验证：用户可以添加 provider、测试连接、查看模型

### 阶段 3：工具系统（FEAT-004）
1. 实现 Tool 接口和 ToolRegistry
2. 实现带超时和错误处理的 ToolExecutionEngine
3. 实现内置工具（get_current_time、read_file、write_file、http_request）
4. 实现权限检查器
5. 验证：工具可以注册、调用、返回结果

### 阶段 4：Agent 管理（FEAT-002）
1. 实现内置通用助手 Agent
2. 实现 Agent 管理页面（列表、详情、创建）
3. 实现克隆功能
4. 实现 Agent 配置中的工具选择
5. 验证：用户可以创建/编辑/克隆/删除 Agent，选择工具

### 阶段 5：会话管理（FEAT-005）
1. 实现会话列表页面（首页）
2. 实现会话创建、删除（滑动 + 撤销）、批量删除
3. 实现标题生成（阶段一：截取，阶段二：AI 生成）
4. 实现手动标题编辑
5. 验证：用户可以创建/恢复/删除会话，标题自动生成

### 阶段 6：对话交互（FEAT-001）
1. 实现聊天页面和消息列表
2. 实现消息输入和发送
3. 实现流式响应渲染
4. 实现工具调用展示（简洁和详细模式）
5. 实现思考块展示（折叠/可展开）
6. 实现 Markdown 渲染
7. 实现停止生成
8. 实现消息复制
9. 实现聊天中的 Agent 切换
10. 实现自动滚动和滚动到底部
11. 验证：完整对话流程，包括流式传输、工具调用和 Agent 切换

### 阶段 7：打磨和集成
1. 完整流程的端到端测试
2. 错误处理优化
3. 性能优化（懒加载、列表性能）
4. 离线行为验证
5. UI 打磨（动画、过渡、空状态）

## 性能指南

### 内存
- 所有列表使用 `LazyColumn`（会话列表、消息列表、Agent 列表）
- 打开会话时懒加载消息历史（分页，而非一次全部加载）
- 大的工具结果持久化后从内存中释放

### 网络
- 流式响应：块到达时即处理，不缓冲完整响应
- 模型列表获取：缓存结果，仅在用户明确操作或定期计划时刷新
- 连接测试：10 秒超时

### UI
- 使用 `remember` 和 `derivedStateOf` 最小化 Compose 重组
- 在传递给 Compose 的数据类上使用 `Immutable` 或 `Stable` 注解
- 消息列表：在 `LazyColumn` 项中使用 `key` 参数实现高效 diff

### 数据库
- 对 `messages.session_id` 和 `messages.created_at` 建索引以加快查询
- 使用 `Flow` 进行响应式查询（数据变化时会话列表自动更新）
- 多工具执行时批量插入工具调用结果

## 安全指南

1. **API key**：存储在由 Android Keystore 支持的 `EncryptedSharedPreferences` 中——不在 Room 数据库中。`ApiKeyStorage` 类提供 `getApiKey(providerId)` 和 `setApiKey(providerId, key)` 方法。API key 绝不属于 Provider 领域模型或数据库实体。绝不记录日志。除配置的 provider endpoint 外绝不发送到其他地方。
2. **数据库**：使用 Room 的标准 SQLite。数据库中不包含任何敏感密钥（API key 在 EncryptedSharedPreferences 中）。未来可考虑添加 SQLCipher 实现完整数据库加密。
3. **网络**：所有 provider API 调用通过 HTTPS。OkHttp 处理证书验证。
4. **日志**：绝不记录 API key、完整 API 响应（可能包含用户敏感数据）或访问本地文件的工具结果。

## UI 主题

应用使用从 Material Theme Builder 生成的 Material 3 主题。主题文件存储在 `docs/design/material-theme/` 中，构建时应复制到项目中。

### 主题概要
- **配色方案**：暖金色/琥珀色主色（`#6D5E0F`），绿色 tertiary 强调色
- **变体**：亮色、暗色、中对比度、高对比度（四种全部包含）
- **动态颜色**：Android 12+ 上启用（跟随用户壁纸）；旧设备回退到定义的配色方案
- **字体**：Roboto（通过 Google Fonts provider）
- **排版**：标准 Material 3 排版体系，所有层级应用 Roboto

### 主题文件
- `docs/design/material-theme/ui/theme/Color.kt` - 所有颜色定义
- `docs/design/material-theme/ui/theme/Theme.kt` - 带动态颜色支持的主题组合
- `docs/design/material-theme/ui/theme/Type.kt` - 排版定义
- `docs/design/material-theme/res/values-v23/font_certs.xml` - Google Fonts 证书

### 集成方式
创建 Android 项目时：
1. 将主题文件复制到 `app/src/main/kotlin/com/oneclaw/shadow/ui/theme/`
2. 将包名从 `com.example.compose` 更新为 `com.oneclaw.shadow.ui.theme`
3. 将 `font_certs.xml` 复制到 `app/src/main/res/values-v23/`

## 开放问题

- [x] ~~是否使用 Gradle 多模块还是单模块？~~ **决定：使用单 Gradle 模块加包分隔。** 更简单的构建配置，更易于 AI 代码生成，对项目规模足够。层级边界通过包结构（`core/`、`data/`、`feature/`、`tool/`）和代码审查来维护。
- [ ] 所有库的确切版本应在项目创建时固定。
- [ ] 发布版本的 ProGuard/R8 规则需要定义。

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|---------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
| 2026-02-27 | 0.2 | 更新导航流程：聊天为首页，会话列表在抽屉中，设置在右侧；添加 UI 设计规范引用 | - |
| 2026-02-27 | 0.3 | API key 从 Room 数据库移至 EncryptedSharedPreferences（ApiKeyStorage）；移除 Provider 模型和 providers 表中的 api_key 字段；设置页面可跳过（仅首次启动时显示，用户可跳过进入聊天） | - |
| 2026-02-27 | 0.4 | 移除 ProviderType.CUSTOM；type 现在表示 API 协议格式（OPENAI、ANTHROPIC、GEMINI）；isPreConfigured 区分内置和用户创建；详见 RFC-003 | - |
| 2026-02-27 | 0.5 | AgentRepository：updateAgent/deleteAgent 现在返回 AppResult<Unit>；getBuiltInAgents 现为 suspend；SessionRepository：新增 updateAgentForSessions() 用于 Agent 删除回退；详见 RFC-002 | - |
| 2026-02-27 | 0.6 | Session 模型：添加 lastMessagePreview、deletedAt 字段；sessions 表：添加新列 + 索引；SessionRepository：扩展软删除/恢复/批量/重命名/消息统计/活跃标志/Agent切换方法；ModelApiAdapter：添加 generateSimpleCompletion()；添加 ResolvedModel 数据类；更新 session 功能目录结构和 Koin 配置；添加应用启动时 CleanupSoftDeletedUseCase；详见 RFC-005 | - |
| 2026-02-27 | 0.7 | MessageRepository：添加 getMessagesSnapshot() 和 deleteMessage()；chat 功能目录结构扩展（ChatEvent、ChatTopBar、MessageList、UserMessageBubble、AiMessageBubble、ThinkingBlock、ToolCallCard、ToolResultCard、ErrorMessageCard、SystemMessageCard、EmptyChatState、StreamingCursor、MessageToApiMapper）；data/remote/ 添加 ApiMessage.kt 和 SseParser.kt；Koin 配置：更新 SendMessageUseCase（8 依赖）和 ChatViewModel（6 依赖）；技术栈添加 compose-markdown；详见 RFC-001 | - |
| 2026-02-27 | 0.8 | 跨文档一致性修复：ProviderRepository 更新匹配 RFC-003（Result->AppResult，新增 getActiveProviders/setProviderActive/addManualModel/deleteManualModel，deleteProvider 返回 AppResult<Unit>）；ModelApiAdapter 的 listModels/testConnection 返回 AppResult；ModelApiAdapterFactory 接收 OkHttpClient（匹配 RFC-003）；ToolDefinition.parametersSchema 从 String 改为 ToolParametersSchema（匹配 RFC-004）；新增 ToolParametersSchema/ToolParameter 数据类；Koin FeatureModule：新增 GetAgentToolsUseCase 和 ResolveModelUseCase，修正 AgentListViewModel/AgentDetailViewModel/ProviderListViewModel/ProviderDetailViewModel 依赖数量（匹配 RFC-002/RFC-003）；目录树：新增 ModelDao.kt、AgentConstants.kt、AgentSelectorSheet.kt、AgentValidator.kt、GetAgentToolsUseCase.kt、ResolveModelUseCase.kt、ToolSchemaSerializer.kt；DatabaseModule：新增 modelDao() | - |
