# 架构概览

OneClaw 基于 Clean Architecture 原则构建，将关注点分离到多模块 Gradle 项目的不同层次中。

## 模块结构

项目包含两个 Gradle 模块：

- **`:app`**（包名 `com.oneclaw.shadow`）-- 主 Android 应用
- **`:bridge`**（包名 `com.oneclaw.shadow.bridge`）-- 独立的 Android 库，用于多渠道消息通信

## Clean Architecture 层次

`:app` 模块按照四个层次组织：

### Core 层（`core/`）

最内层，包含纯领域逻辑，不依赖任何 Android 框架。

- **`model/`** -- 领域模型：`Agent`、`AiModel`、`Message`、`Session`、`Provider`、`ScheduledTask`、`TaskExecutionRecord`、`ToolDefinition`、`ToolResult`、`SkillDefinition`、`FileInfo`、`FileContent`、`Attachment`、`Citation`
- **`repository/`** -- Repository 接口：`AgentRepository`、`AttachmentRepository`、`FileRepository`、`MessageRepository`、`ProviderRepository`、`ScheduledTaskRepository`、`SessionRepository`、`SettingsRepository`、`TaskExecutionRecordRepository`
- **`util/`** -- 用于错误处理的 `AppResult<T>` 密封类，以及 `ErrorCode` 枚举

### Data 层（`data/`）

实现 Repository 接口并提供数据访问能力。

- **`local/entity/`** -- Room 数据库实体
- **`local/dao/`** -- 用于数据库操作的 Room DAO
- **`local/db/`** -- `AppDatabase` 定义及迁移脚本
- **`local/mapper/`** -- 实体与领域模型之间的映射器
- **`remote/adapter/`** -- `ModelApiAdapter` 接口，包含 `OpenAiAdapter`、`AnthropicAdapter`、`GeminiAdapter`
- **`remote/dto/`** -- 各 Provider 专用的 DTO（`openai/`、`anthropic/`、`gemini/`）
- **`remote/sse/`** -- 用于 Server-Sent Events 流式传输的 `SseParser`
- **`repository/`** -- Repository 实现（如 `SessionRepositoryImpl`、`MessageRepositoryImpl`）
- **`security/`** -- 基于 `EncryptedSharedPreferences` 的 `EncryptedKeyStorage`
- **`storage/`** -- 文件存储工具
- **`sync/`** -- Google Drive 备份与数据同步

### Feature 层（`feature/`）

每个功能模块是一个垂直切片，包含界面、ViewModel、UI 状态和用例。

| 功能模块 | 描述 |
|---------|------|
| `agent/` | Agent 管理（创建、编辑、删除 AI 角色） |
| `bridge/` | Bridge 集成（应用侧 bridge 连接） |
| `chat/` | 聊天交互（主对话界面） |
| `file/` | 文件浏览与预览 |
| `memory/` | 记忆管理界面与嵌入引擎 |
| `provider/` | Provider 与模型配置 |
| `schedule/` | 定时任务管理与闹钟系统 |
| `search/` | 跨会话、记忆、日志的统一搜索 |
| `session/` | 会话生命周期管理 |
| `settings/` | 应用设置、Google 授权、数据备份 |
| `skill/` | 技能编辑器与管理 |
| `tool/` | 工具管理界面 |
| `usage/` | Token 使用量统计 |

### Tool 层（`tool/`）

AI 工具执行系统，与 Feature 层分离。

- **`engine/`** -- `Tool` 接口、`ToolRegistry`、`ToolExecutionEngine`、`ToolEnabledStateStore`
- **`builtin/`** -- 39 个 Kotlin 内置工具（参见 [Tool Reference](Tool-Reference.md)）
- **`builtin/config/`** -- 配置类工具（providers、models、agents、环境变量）
- **`js/`** -- QuickJS JavaScript 引擎集成（`JsExecutionEngine`、`JsTool`、`UserToolManager`、bridge API）
- **`skill/`** -- `SkillRegistry`、`SkillFileParser`，用于技能管理
- **`browser/`** -- `WebViewManager`、`BrowserContentExtractor`、`BrowserScreenshotCapture`

## 数据流

```
用户输入
    |
    v
ChatScreen (Composable)
    |
    v
ChatViewModel (StateFlow<ChatUiState>)
    |
    v
SendMessageUseCase
    |
    +---> MessageRepository.insert(userMessage)
    |
    +---> ModelApiAdapterFactory.getAdapter(providerType)
    |         |
    |         v
    |     ModelApiAdapter.sendMessageStream()
    |         |
    |         v
    |     Flow<StreamEvent>（SSE 解析）
    |         |
    |         +---> TextDelta -> 累积响应内容
    |         +---> ToolCallStart/Delta/End -> 解析工具调用
    |         +---> Usage -> 记录 Token 用量
    |         +---> Done -> 完成处理
    |
    +---> ToolExecutionEngine.execute(toolCall)
    |         |
    |         v
    |     ToolRegistry.getTool(name)
    |         |
    |         v
    |     Tool.execute(parameters) -> ToolResult
    |
    +---> MessageRepository.insert(assistantMessage)
    |
    v
ChatUiState 通过 StateFlow 更新
    |
    v
UI 重新组合
```

## 核心设计模式

### Repository Pattern

`core/repository/` 中的 Repository 接口定义契约，`data/repository/` 中的实现将 Room DAO 与 API 适配器结合使用。这样可以在不修改业务逻辑的前提下替换数据源。

### Adapter Pattern

`ModelApiAdapter` 抽象了不同 AI Provider API 之间的差异。`ModelApiAdapterFactory` 根据 `ProviderType`（OPENAI、ANTHROPIC、GEMINI）创建对应的适配器。每个适配器负责处理：

- 请求格式化（各 Provider 的 JSON Schema 不同）
- SSE 流解析（各 Provider 的事件格式不同）
- 工具定义格式化（functions 与 tools 的差异）
- 模型列表获取与连接测试

### Use Case Pattern

业务逻辑封装在 `feature/*/usecase/` 下的用例类中，每个用例职责单一：

- `SendMessageUseCase` -- 编排完整的消息-响应周期
- `AutoCompactUseCase` -- 在达到限制时压缩消息历史
- `CreateAgentUseCase` -- 校验并持久化新 Agent

### 密封类错误处理

`AppResult<T>` 在所有可能失败的操作中统一使用：

```kotlin
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val code: ErrorCode, val message: String) : AppResult<Nothing>()
}
```

## 依赖注入

使用 Koin 进行依赖注入，在 `di/` 中定义了八个模块：

| 模块 | 提供内容 |
|------|---------|
| `appModule` | 应用级单例 |
| `bridgeModule` | Bridge 集成组件 |
| `databaseModule` | Room 数据库、DAO |
| `featureModule` | ViewModel |
| `memoryModule` | 嵌入引擎、搜索引擎、记忆注入器 |
| `networkModule` | OkHttpClient、API 适配器工厂 |
| `repositoryModule` | Repository 实现 |
| `toolModule` | 工具注册表、执行引擎、内置工具 |

## 导航

Compose Navigation 配合密封类 `Route` 定义所有导航目标：

- 聊天界面（主界面）
- 会话抽屉
- Agent 列表/详情
- Provider 列表/详情/配置
- 设置界面
- 定时任务列表/详情/编辑
- 文件浏览/预览
- 工具管理
- 技能编辑器/管理
- 使用量统计
- 记忆查看器
- Bridge 设置

## 数据库 Schema

Room 数据库（`AppDatabase`）包含以下实体：

| 实体 | 描述 |
|------|------|
| `SessionEntity` | 对话会话，包含标题、时间戳、软删除标志 |
| `MessageEntity` | 聊天消息，包含角色、内容、Token 用量、模型信息 |
| `AgentEntity` | AI Agent 配置，包含系统提示词 |
| `ProviderEntity` | API Provider 配置 |
| `AiModelEntity` | 每个 Provider 下的可用模型 |
| `ScheduledTaskEntity` | 定时任务定义 |
| `TaskExecutionRecordEntity` | 任务执行历史记录 |
| `AttachmentEntity` | 与消息关联的文件附件 |
| `MemoryIndexEntity` | 用于记忆搜索的向量嵌入 |

数据库迁移以增量方式处理，确保应用更新时用户数据不丢失。
