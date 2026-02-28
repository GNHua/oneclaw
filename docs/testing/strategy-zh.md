# 测试策略：OneClawShadow

## 文档信息
- **关联架构**: [RFC-000 (总体架构)](../rfc/architecture/RFC-000-overall-architecture-zh.md)
- **关联 RFC**: RFC-001 至 RFC-005
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27
- **状态**: 草稿

## 概述

### 背景
OneClawShadow 采用文档驱动开发方式，AI 从 RFC 生成代码。自动化测试对于验证生成的代码是否符合规范至关重要。本文档定义了两层测试策略：第一层（全自动，无需人工交互）和第二层（AI 控制模拟器进行视觉验证）。

### 目标
1. 定义 AI 可以自主执行的全面测试策略
2. 第一层：通过 Gradle 命令运行的单元测试、Compose UI 测试和截图测试
3. 第二层：AI 控制真实模拟器进行基于 adb 的视觉验证
4. 提供足够的细节，使 AI 无需人工干预即可生成测试代码并执行测试
5. 使用真实 API key（从环境变量获取）进行端到端验证 -- 绝不硬编码，绝不发送给模型

### 测试原则
- **AI 可执行**：每个测试都可以通过 shell 命令触发
- **快速反馈**：单元测试在秒级完成
- **视觉验证**：截图测试和 adb 截图捕获 UI 回归
- **真实 API 验证**：第二层使用真实 Provider API 进行最终验证
- **代码中无密钥**：API key 仅从环境变量读取

## 技术栈

### 测试库

| 库 | 版本 | 用途 | 层级 |
|---|------|------|------|
| JUnit 5 | 5.10.x | 测试框架 | 两层 |
| MockK | 1.13.x | Kotlin mock 库 | L1 |
| Turbine | 1.0.x | Flow 测试 | L1 |
| Kotlin Coroutines Test | 1.8.x | 协程测试（runTest、TestDispatcher） | L1 |
| Roborazzi | 1.20.x+ | JVM 截图测试（基于 Robolectric） | L1 |
| Robolectric | 4.12.x | JVM 上的 Android 框架（Roborazzi 依赖） | L1 |
| Compose UI Test | (Jetpack) | Compose 组件测试 | L1 |
| adb | (Android SDK) | 设备控制、截图、应用安装 | L2 |

### 模拟器配置

| 属性 | 值 |
|------|---|
| AVD 名称 | Medium Phone API 36.1 |
| AVD ID | Medium_Phone_API_36.1 |
| Android 版本 | 16.0 (Baklava) |
| API 级别 | 36.1 |
| 分辨率 (px) | 1080 x 2400 |
| 分辨率 (dp) | 412 x 915 |
| 密度 | 420 dpi |
| ABI | arm64-v8a |

### 环境变量

API key 作为环境变量存储在开发者的机器上。它们**绝不硬编码**在源代码、测试代码或配置文件中。它们**绝不发送给 AI 模型** -- 仅在运行时由应用或测试工具使用。

| 变量 | Provider | 用途 |
|------|----------|------|
| `ONECLAW_OPENAI_API_KEY` | OpenAI | 第二层端到端测试 |
| `ONECLAW_ANTHROPIC_API_KEY` | Anthropic | 第二层端到端测试的**主要** key |
| `ONECLAW_GEMINI_API_KEY` | Google Gemini | 第二层端到端测试 |

**API key 如何传递到模拟器（第二层）：**
```bash
# 从本地环境读取，通过 adb 注入模拟器
adb shell "setprop oneclaw.test.anthropic_key $(printenv ONECLAW_ANTHROPIC_API_KEY)"
```
或者，对于需要 key 的 instrumented 测试：
```bash
# 作为 instrumentation 参数传递
adb shell am instrument \
  -e ANTHROPIC_API_KEY "$(printenv ONECLAW_ANTHROPIC_API_KEY)" \
  -w com.oneclaw.shadow.test/androidx.test.runner.AndroidJUnitRunner
```
或者，在第二层 adb UI 测试中，AI 将 key 输入到应用的 Provider 设置界面：
```bash
# AI 从环境读取 key，通过 adb 输入到文本框
API_KEY=$(printenv ONECLAW_ANTHROPIC_API_KEY)
adb shell input text "$API_KEY"
```

## 测试目录结构

```
app/src/
├── test/kotlin/com/oneclaw/shadow/        # 第一层：单元测试（JVM）
│   ├── core/
│   │   └── model/                          # 领域模型测试（如有）
│   ├── data/
│   │   ├── repository/                     # Repository 实现测试
│   │   │   ├── AgentRepositoryImplTest.kt
│   │   │   ├── ProviderRepositoryImplTest.kt
│   │   │   ├── SessionRepositoryImplTest.kt
│   │   │   └── MessageRepositoryImplTest.kt
│   │   └── remote/
│   │       ├── adapter/                    # API 适配器测试
│   │       │   ├── OpenAiAdapterTest.kt
│   │       │   ├── AnthropicAdapterTest.kt
│   │       │   └── GeminiAdapterTest.kt
│   │       └── sse/
│   │           └── SseParserTest.kt
│   ├── tool/
│   │   ├── engine/
│   │   │   ├── ToolExecutionEngineTest.kt
│   │   │   └── ToolRegistryTest.kt
│   │   └── builtin/
│   │       ├── GetCurrentTimeToolTest.kt
│   │       ├── ReadFileToolTest.kt
│   │       ├── WriteFileToolTest.kt
│   │       └── HttpRequestToolTest.kt
│   ├── feature/
│   │   ├── chat/
│   │   │   ├── ChatViewModelTest.kt
│   │   │   ├── ChatEventTest.kt
│   │   │   └── usecase/
│   │   │       ├── SendMessageUseCaseTest.kt
│   │   │       └── MessageToApiMapperTest.kt
│   │   ├── agent/
│   │   │   ├── AgentListViewModelTest.kt
│   │   │   ├── AgentDetailViewModelTest.kt
│   │   │   └── usecase/
│   │   │       ├── CreateAgentUseCaseTest.kt
│   │   │       ├── CloneAgentUseCaseTest.kt
│   │   │       └── DeleteAgentUseCaseTest.kt
│   │   ├── provider/
│   │   │   ├── ProviderListViewModelTest.kt
│   │   │   ├── ProviderDetailViewModelTest.kt
│   │   │   └── usecase/
│   │   │       ├── TestConnectionUseCaseTest.kt
│   │   │       └── FetchModelsUseCaseTest.kt
│   │   └── session/
│   │       ├── SessionListViewModelTest.kt
│   │       └── usecase/
│   │           ├── CreateSessionUseCaseTest.kt
│   │           ├── DeleteSessionUseCaseTest.kt
│   │           ├── GenerateTitleUseCaseTest.kt
│   │           └── CleanupSoftDeletedUseCaseTest.kt
│   ├── screenshot/                         # Roborazzi 截图测试
│   │   ├── ChatScreenScreenshotTest.kt
│   │   ├── AgentScreenScreenshotTest.kt
│   │   ├── ProviderScreenScreenshotTest.kt
│   │   └── SessionDrawerScreenshotTest.kt
│   └── testutil/                           # 共享测试工具
│       ├── TestDataFactory.kt              # 领域模型构建器
│       ├── FakeRepositories.kt             # 内存 Repository 实现
│       ├── FakeModelApiAdapter.kt          # Mock API 适配器
│       ├── FakeToolExecutionEngine.kt      # Mock 工具引擎
│       └── MainDispatcherRule.kt           # 协程测试 JUnit 规则
│
├── androidTest/kotlin/com/oneclaw/shadow/  # 第一层：Instrumented 测试
│   ├── data/local/
│   │   ├── dao/
│   │   │   ├── AgentDaoTest.kt
│   │   │   ├── ProviderDaoTest.kt
│   │   │   ├── ModelDaoTest.kt
│   │   │   ├── SessionDaoTest.kt
│   │   │   ├── MessageDaoTest.kt
│   │   │   └── SettingsDaoTest.kt
│   │   └── db/
│   │       └── MigrationTest.kt            # 未来：数据库迁移测试
│   ├── feature/
│   │   ├── chat/
│   │   │   └── ChatScreenTest.kt           # Compose UI 测试
│   │   ├── agent/
│   │   │   └── AgentScreenTest.kt
│   │   ├── provider/
│   │   │   └── ProviderScreenTest.kt
│   │   └── session/
│   │       └── SessionDrawerTest.kt
│   └── testutil/
│       └── TestDatabaseHelper.kt
│
└── test/resources/                         # 测试资源
    └── screenshots/                        # Roborazzi 基准截图
        └── (自动生成的 .png 文件)
```

## 共享测试工具

### TestDataFactory

集中管理的工厂类，用于在测试中创建领域模型实例。所有字段都有合理的默认值，可通过命名参数覆盖。

```kotlin
/**
 * 位于：test/kotlin/com/oneclaw/shadow/testutil/TestDataFactory.kt
 */
object TestDataFactory {

    // --- Agent ---
    fun agent(
        id: String = "agent-test",
        name: String = "Test Agent",
        description: String? = "A test agent",
        systemPrompt: String = "You are a helpful assistant.",
        toolIds: List<String> = listOf("get_current_time", "read_file"),
        preferredProviderId: String? = null,
        preferredModelId: String? = null,
        isBuiltIn: Boolean = false,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Agent(id, name, description, systemPrompt, toolIds,
        preferredProviderId, preferredModelId, isBuiltIn, createdAt, updatedAt)

    fun generalAssistant() = agent(
        id = "agent-general-assistant",
        name = "General Assistant",
        systemPrompt = "You are a helpful, harmless, and honest assistant.",
        toolIds = listOf("get_current_time", "read_file", "write_file", "http_request"),
        isBuiltIn = true
    )

    // --- Provider ---
    fun provider(
        id: String = "provider-test",
        name: String = "Test Provider",
        type: ProviderType = ProviderType.ANTHROPIC,
        apiBaseUrl: String = "https://api.anthropic.com",
        isPreConfigured: Boolean = false,
        isActive: Boolean = true,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Provider(id, name, type, apiBaseUrl, isPreConfigured, isActive, createdAt, updatedAt)

    fun anthropicProvider() = provider(
        id = "provider-anthropic",
        name = "Anthropic",
        type = ProviderType.ANTHROPIC,
        apiBaseUrl = "https://api.anthropic.com",
        isPreConfigured = true
    )

    fun openAiProvider() = provider(
        id = "provider-openai",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true
    )

    fun geminiProvider() = provider(
        id = "provider-gemini",
        name = "Google Gemini",
        type = ProviderType.GEMINI,
        apiBaseUrl = "https://generativelanguage.googleapis.com",
        isPreConfigured = true
    )

    // --- Model ---
    fun model(
        id: String = "claude-sonnet-4-20250514",
        displayName: String? = "Claude Sonnet 4",
        providerId: String = "provider-anthropic",
        isDefault: Boolean = false,
        source: ModelSource = ModelSource.PRESET
    ) = AiModel(id, displayName, providerId, isDefault, source)

    // --- Session ---
    fun session(
        id: String = "session-test",
        title: String = "Test Session",
        currentAgentId: String = "agent-general-assistant",
        messageCount: Int = 0,
        lastMessagePreview: String? = null,
        isActive: Boolean = false,
        deletedAt: Long? = null,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Session(id, title, currentAgentId, messageCount,
        lastMessagePreview, isActive, deletedAt, createdAt, updatedAt)

    // --- Message ---
    fun userMessage(
        id: String = "msg-user-1",
        sessionId: String = "session-test",
        content: String = "Hello, how are you?",
        createdAt: Long = 1000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.USER,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun aiMessage(
        id: String = "msg-ai-1",
        sessionId: String = "session-test",
        content: String = "I'm doing well, thank you!",
        thinkingContent: String? = null,
        modelId: String? = "claude-sonnet-4-20250514",
        providerId: String? = "provider-anthropic",
        tokenCountInput: Int? = 10,
        tokenCountOutput: Int? = 20,
        createdAt: Long = 2000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.AI_RESPONSE,
        content = content, thinkingContent = thinkingContent,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = tokenCountInput, tokenCountOutput = tokenCountOutput,
        modelId = modelId, providerId = providerId, createdAt = createdAt
    )

    fun toolCallMessage(
        id: String = "msg-toolcall-1",
        sessionId: String = "session-test",
        toolCallId: String = "call_123",
        toolName: String = "get_current_time",
        toolInput: String = """{"timezone":"UTC"}""",
        createdAt: Long = 3000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.TOOL_CALL,
        content = "", thinkingContent = null,
        toolCallId = toolCallId, toolName = toolName, toolInput = toolInput,
        toolOutput = null, toolStatus = ToolCallStatus.PENDING, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun toolResultMessage(
        id: String = "msg-toolresult-1",
        sessionId: String = "session-test",
        toolCallId: String = "call_123",
        toolName: String = "get_current_time",
        toolOutput: String = "2026-02-27T15:30:00Z",
        toolStatus: ToolCallStatus = ToolCallStatus.SUCCESS,
        durationMs: Long = 50L,
        createdAt: Long = 4000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.TOOL_RESULT,
        content = "", thinkingContent = null,
        toolCallId = toolCallId, toolName = toolName, toolInput = null,
        toolOutput = toolOutput, toolStatus = toolStatus, toolDurationMs = durationMs,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun errorMessage(
        id: String = "msg-error-1",
        sessionId: String = "session-test",
        content: String = "API request failed.",
        createdAt: Long = 5000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.ERROR,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun systemMessage(
        id: String = "msg-system-1",
        sessionId: String = "session-test",
        content: String = "Switched to Code Helper",
        createdAt: Long = 6000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.SYSTEM,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    // --- ToolResult ---
    fun toolResultSuccess(
        result: String = "Operation completed."
    ) = ToolResult(
        status = ToolResultStatus.SUCCESS,
        result = result,
        errorType = null,
        errorMessage = null
    )

    fun toolResultError(
        errorType: String = "execution_error",
        errorMessage: String = "Something went wrong."
    ) = ToolResult(
        status = ToolResultStatus.ERROR,
        result = null,
        errorType = errorType,
        errorMessage = errorMessage
    )
}
```

### MainDispatcherRule

```kotlin
/**
 * JUnit 5 扩展，将 Dispatchers.Main 替换为 TestDispatcher。
 * 位于：test/kotlin/com/oneclaw/shadow/testutil/MainDispatcherRule.kt
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext?) {
        Dispatchers.setMain(dispatcher)
    }
    override fun afterEach(context: ExtensionContext?) {
        Dispatchers.resetMain()
    }
}
```

### FakeRepositories

内存中的 Repository 接口实现，用于单元测试。当测试需要一个可工作的 Repository 时（例如测试需要读写的 UseCase），这比 MockK 更简单快速。

```kotlin
/**
 * 用于测试的内存 MessageRepository。
 * 位于：test/kotlin/com/oneclaw/shadow/testutil/FakeRepositories.kt
 */
class FakeMessageRepository : MessageRepository {
    private val messages = mutableListOf<Message>()
    private val _flow = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessagesForSession(sessionId: String): Flow<List<Message>> =
        _flow.map { it.filter { m -> m.sessionId == sessionId } }

    override suspend fun addMessage(message: Message): Message {
        val saved = message.copy(
            id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
            createdAt = if (message.createdAt == 0L) System.currentTimeMillis() else message.createdAt
        )
        messages.add(saved)
        _flow.value = messages.toList()
        return saved
    }

    override suspend fun updateMessage(message: Message) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) { messages[index] = message; _flow.value = messages.toList() }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.removeAll { it.sessionId == sessionId }
        _flow.value = messages.toList()
    }

    override suspend fun getMessageCount(sessionId: String): Int =
        messages.count { it.sessionId == sessionId }

    override suspend fun getMessagesSnapshot(sessionId: String): List<Message> =
        messages.filter { it.sessionId == sessionId }

    override suspend fun deleteMessage(id: String) {
        messages.removeAll { it.id == id }
        _flow.value = messages.toList()
    }
}

// 其他 Repository 的类似 Fake 实现...
// 每个都在 MutableList 中存储数据，通过 MutableStateFlow 发出更新。
```

## 第一层：自动化测试

### 1A. 单元测试（JVM）

运行命令：`./gradlew test`

在 JVM 上运行，不需要 Android 设备。测试业务逻辑、状态管理、数据映射和解析。

#### 功能：对话交互（RFC-001）

**SendMessageUseCaseTest**

| 测试 | 描述 | 方法 |
|------|------|------|
| `execute normal message returns streaming events` | 发送用户消息，mock 适配器返回文本增量，验证 ChatEvent 序列 | Mock 适配器发出 TextDelta + Done；收集 flow；断言 StreamingText 事件 + ResponseComplete |
| `execute with tool calls loops correctly` | 模型返回工具调用，工具执行，模型再次调用 | Mock 适配器：第 0 轮发出 ToolCallStart + Done；mock ToolEngine 返回成功；第 1 轮发出 TextDelta + Done；验证 2 轮 |
| `execute with parallel tool calls executes all` | 模型同时返回 3 个工具调用 | Mock 适配器发出 3 个 ToolCallStart；验证 3 个 ToolCallCompleted 事件 |
| `execute max rounds reached emits error` | 100+ 轮工具调用 | Mock 适配器始终返回工具调用；验证 Error 事件，TOOL_ERROR |
| `execute with no provider emits error` | 没有解析到模型 | Mock resolveModel 返回 null；验证 Error，VALIDATION_ERROR |
| `execute with missing api key emits error` | Provider 存在但无 key | Mock apiKeyStorage 返回 null；验证 Error，AUTH_ERROR |
| `execute with API 401 emits auth error` | API 返回 401 | Mock 适配器发出 StreamEvent.Error(401)；验证 ChatEvent.Error，AUTH_ERROR，不可重试 |
| `execute with API 429 emits retryable error` | 频率限制 | Mock 适配器发出 StreamEvent.Error(429)；验证 ChatEvent.Error，可重试 |
| `execute with network error emits retryable error` | 网络故障 | Mock 适配器抛出 IOException；验证 ChatEvent.Error，NETWORK_ERROR，可重试 |
| `execute saves user message to DB` | 验证持久化 | 执行后，检查 FakeMessageRepository 有 USER 消息 |
| `execute saves AI response to DB` | 验证持久化 | 流完成后，检查 FakeMessageRepository 有 AI_RESPONSE 消息 |
| `execute saves tool call and result to DB` | 验证工具持久化 | 工具执行后，检查数据库有 TOOL_CALL 和 TOOL_RESULT 消息 |
| `execute updates session stats after response` | 会话预览已更新 | ResponseComplete 后，验证 sessionRepository.updateMessageStats 被调用 |
| `execute resolves agent preferred model first` | Agent 有首选模型 | 设置 agent.preferredModelId；验证使用该模型而非全局默认 |
| `execute falls back to global default model` | Agent 无偏好 | Agent preferred = null；验证使用全局默认模型 |
| `cancellation does not emit error` | 用户停止生成 | 取消 flow 收集；验证无 Error 事件，CancellationException 被重新抛出 |

**MessageToApiMapperTest**

| 测试 | 描述 |
|------|------|
| `maps USER message to ApiMessage.User` | |
| `maps AI_RESPONSE message to ApiMessage.Assistant` | |
| `maps TOOL_CALL message to ApiMessage.Assistant with toolCalls` | |
| `maps TOOL_RESULT message to ApiMessage.ToolResult` | |
| `excludes ERROR messages` | ERROR 类型不在输出中 |
| `excludes SYSTEM messages` | SYSTEM 类型不在输出中 |
| `preserves message order` | |
| `handles empty message list` | 返回空列表 |
| `handles messages with null optional fields` | toolCallId、toolName 等为 null |

**ChatViewModelTest**

| 测试 | 描述 |
|------|------|
| `initialize with null sessionId starts new conversation` | uiState 有 null sessionId，默认 Agent |
| `initialize with sessionId loads existing session` | uiState 从 session + messages 填充 |
| `sendMessage clears input text` | inputText 变为 "" |
| `sendMessage creates session on first message` | 延迟创建：sessionId 从 null 变为非 null |
| `sendMessage sets isStreaming true` | 流式传输期间，isStreaming = true |
| `sendMessage accumulates streaming text` | StreamingText 事件到达时 streamingText 增长 |
| `sendMessage accumulates thinking text` | streamingThinkingText 增长 |
| `sendMessage finishes streaming on ResponseComplete` | isStreaming = false，消息重新加载 |
| `sendMessage triggers title generation on first message` | 首次 ResponseComplete 后调用 generateTitleUseCase |
| `sendMessage does not trigger title on subsequent messages` | 仅首条消息触发标题 |
| `stopGeneration cancels streaming job` | streamingJob 被取消，部分文本被保存 |
| `regenerate removes messages after last user message` | lastUserIndex 之后的消息从数据库和 UI 中删除 |
| `regenerate re-sends with same user text` | SendMessageUseCase 使用相同文本再次调用 |
| `switchAgent updates session and inserts system message` | SessionRepository.updateCurrentAgent 被调用；SYSTEM 消息被添加 |
| `switchAgent blocked during streaming` | 如果 isStreaming，switchAgent 不执行任何操作 |
| `retryLastMessage removes error and regenerates` | ERROR 消息被删除，然后调用 regenerate |
| `setAutoScroll updates shouldAutoScroll` | |
| `checkProviderStatus updates hasConfiguredProvider` | |

**SseParserTest**

| 测试 | 描述 |
|------|------|
| `parses single data line` | `data: {"text":"hi"}` -> SseEvent(null, `{"text":"hi"}`) |
| `parses event type + data` | `event: message_start\ndata: {...}` -> SseEvent("message_start", ...) |
| `handles multiple events separated by blank lines` | 发出多个 SseEvent |
| `handles data: [DONE]` | 作为 SseEvent 发出，data 为 "[DONE]" |
| `ignores comment lines (starting with :)` | 以 `:` 开头的行被跳过 |
| `handles empty data field` | `data: ` -> SseEvent(null, "") |
| `handles malformed lines gracefully` | 意外格式不会崩溃 |

**适配器 SSE 映射测试**（每个适配器一个测试类）

| 测试（每个适配器） | 描述 |
|-------------------|------|
| `maps text content to TextDelta` | |
| `maps thinking content to ThinkingDelta` | （仅 Anthropic） |
| `maps tool call start to ToolCallStart` | |
| `maps tool call arguments to ToolCallDelta` | |
| `maps tool call end to ToolCallEnd` | |
| `maps usage to Usage event` | |
| `maps error to Error event` | |
| `maps stream end to Done` | |
| `handles unexpected JSON fields gracefully` | 额外字段不会崩溃 |

#### 功能：Agent 管理（RFC-002）

**CreateAgentUseCaseTest**

| 测试 | 描述 |
|------|------|
| `creates agent with valid data` | 返回创建的 Agent |
| `generates UUID for new agent` | Agent ID 非空 |
| `validates empty name returns error` | 空名称返回 AppResult.Error |
| `validates empty system prompt returns error` | 空 prompt 返回 AppResult.Error |

**CloneAgentUseCaseTest**

| 测试 | 描述 |
|------|------|
| `clones agent with new ID` | 新 Agent 有不同 ID，相同内容 |
| `cloned agent name has "Copy of" prefix` | |
| `cloned agent is not built-in` | isBuiltIn = false |

**DeleteAgentUseCaseTest**

| 测试 | 描述 |
|------|------|
| `deletes non-built-in agent` | Agent 从 Repository 中移除 |
| `rejects deleting built-in agent` | 返回错误 |
| `updates sessions referencing deleted agent to General Assistant` | sessionRepository.updateAgentForSessions 被调用 |

#### 功能：Provider 管理（RFC-003）

**TestConnectionUseCaseTest**

| 测试 | 描述 |
|------|------|
| `returns success on valid connection` | Mock 适配器返回成功 |
| `returns error when no API key` | apiKeyStorage 返回 null |
| `returns error on network failure` | Mock 适配器抛出异常 |

**FetchModelsUseCaseTest**

| 测试 | 描述 |
|------|------|
| `fetches and saves models` | Mock 适配器返回模型列表；验证保存到 Repository |
| `handles fetch failure gracefully` | 返回错误，不崩溃 |

#### 功能：工具系统（RFC-004）

**ToolExecutionEngineTest**

| 测试 | 描述 |
|------|------|
| `executes registered tool successfully` | 返回 ToolResult(SUCCESS) |
| `returns error for unregistered tool` | tool_not_found 错误 |
| `returns error when tool not in available list` | tool_not_available 错误 |
| `returns timeout error when tool exceeds timeout` | 工具执行超时 |
| `returns permission_denied when permission not granted` | Mock 权限被拒绝 |
| `catches tool execution exceptions` | 工具抛出异常；返回 execution_error |

**ToolRegistryTest**

| 测试 | 描述 |
|------|------|
| `registers and retrieves tool by name` | |
| `getAllTools returns all registered tool definitions` | |
| `getToolsByIds returns matching tools` | |
| `getTool returns null for unknown name` | |

**内置工具测试**（每个工具一个类）

| 测试 | 描述 |
|------|------|
| GetCurrentTimeTool: `returns current time in default timezone` | |
| GetCurrentTimeTool: `returns time in specified timezone` | |
| GetCurrentTimeTool: `returns error for invalid timezone` | |
| ReadFileTool: `reads existing file content` | （mock 文件系统） |
| ReadFileTool: `returns error for non-existent file` | |
| WriteFileTool: `writes content to file` | |
| WriteFileTool: `returns error on write failure` | |
| HttpRequestTool: `performs GET request and returns response` | （MockWebServer） |
| HttpRequestTool: `truncates response over 100KB` | |
| HttpRequestTool: `returns error on network failure` | |
| HttpRequestTool: `respects timeout` | |

#### 功能：会话管理（RFC-005）

**CreateSessionUseCaseTest**

| 测试 | 描述 |
|------|------|
| `creates session with agent ID` | Session 创建在 Repository 中 |
| `generates UUID for session ID` | |

**DeleteSessionUseCaseTest**

| 测试 | 描述 |
|------|------|
| `soft deletes session` | deletedAt 被设置 |
| `restore undoes soft delete` | deletedAt 被清除 |

**GenerateTitleUseCaseTest**

| 测试 | 描述 |
|------|------|
| `generateTruncatedTitle truncates at 50 chars` | |
| `generateAiTitle calls adapter and updates session` | Mock 适配器返回标题 |
| `generateAiTitle uses lightweight model` | 验证 Anthropic 使用 claude-haiku-4-20250414 |
| `generateAiTitle falls back to current model if lightweight not found` | |

**CleanupSoftDeletedUseCaseTest**

| 测试 | 描述 |
|------|------|
| `hard deletes all soft-deleted sessions` | |

**SessionListViewModelTest**

| 测试 | 描述 |
|------|------|
| `loads sessions from repository` | uiState 有会话列表 |
| `deleteSession triggers soft delete + undo state` | |
| `undoDelete restores session` | |
| `batchDelete deletes multiple sessions` | |
| `renameSession updates title` | |

### 1B. Instrumented 测试（Android 设备）

运行命令：`./gradlew connectedAndroidTest`

需要运行中的模拟器或连接的设备。

#### Room DAO 测试

每个 DAO 测试使用内存数据库验证 CRUD 操作。

**AgentDaoTest**

| 测试 | 描述 |
|------|------|
| `insert and query agent` | |
| `update agent` | |
| `delete agent` | |
| `query built-in agents` | |
| `getAllAgents returns Flow that updates on insert` | |

**ProviderDaoTest**

| 测试 | 描述 |
|------|------|
| `insert and query provider` | |
| `query active providers` | |
| `cascade delete removes models` | |

**SessionDaoTest**

| 测试 | 描述 |
|------|------|
| `insert and query session` | |
| `soft delete sets deletedAt` | |
| `query excludes soft-deleted sessions` | |
| `hard delete removes session` | |
| `cascade delete removes messages` | |
| `query ordered by updatedAt descending` | |

**MessageDaoTest**

| 测试 | 描述 |
|------|------|
| `insert and query messages for session` | |
| `delete single message` | |
| `delete all messages for session` | |
| `getMessageCount returns correct count` | |
| `getMessagesSnapshot returns non-reactive list` | |

**ModelDaoTest**

| 测试 | 描述 |
|------|------|
| `insert and query models for provider` | |
| `query default model` | |
| `set default model` | |

#### Compose UI 测试

测试 Composable 行为（非视觉外观 -- 那是截图测试的工作）。

**ChatScreenTest**

| 测试 | 描述 |
|------|------|
| `empty state shows greeting text` | 显示 "How can I help you today?" |
| `send button disabled when input empty` | |
| `send button disabled when no provider configured` | |
| `send button enabled when text entered and provider exists` | |
| `stop button appears during streaming` | |
| `user message bubble appears after send` | |
| `thinking block is collapsed by default` | |
| `thinking block expands on click` | |
| `error message shows retry button when retryable` | |
| `system message displayed centered` | |
| `agent name displayed in top bar` | |

### 1C. 截图测试（Roborazzi）

运行命令：`./gradlew recordRoborazziDebug`（记录基准）/ `./gradlew verifyRoborazziDebug`（对比）

Roborazzi 在 JVM 上（通过 Robolectric）捕获 Compose 组件的截图。AI 可以查看生成的 PNG 文件来验证视觉正确性。

**需要捕获的截图：**

| 组件 | 变体 |
|------|------|
| EmptyChatState | 默认 |
| UserMessageBubble | 短文本、长文本 |
| AiMessageBubble | 纯文本、带思考块、带模型标签 |
| ThinkingBlock | 折叠、展开 |
| ToolCallCard | 待执行、执行中、成功、错误 |
| ToolResultCard | 成功折叠、成功展开、错误 |
| ErrorMessageCard | 可重试、不可重试 |
| SystemMessageCard | 默认 |
| ChatInput | 空、有文本、流式传输（停止按钮） |
| ChatTopBar | 默认 |
| StreamingCursor | 默认 |
| AgentSelectorSheet | 有 3 个 Agent |
| SessionDrawerContent | 有 5 个会话、空 |
| ProviderListScreen | 有 3 个 Provider |

**截图测试结构：**
```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ChatComponentScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState() {
        composeTestRule.setContent {
            OneClawShadowTheme {
                EmptyChatState()
            }
        }
        composeTestRule.onRoot().captureRoboImage("EmptyChatState.png")
    }

    @Test
    fun userMessageBubble_shortText() {
        composeTestRule.setContent {
            OneClawShadowTheme {
                UserMessageBubble(
                    content = "Hello!",
                    onCopy = {}
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("UserMessageBubble_short.png")
    }

    // ... 更多截图测试
}
```

**基准工作流程：**
1. 首次运行：`./gradlew recordRoborazziDebug` 在 `test/resources/screenshots/` 中生成基准 PNG
2. AI 审查每个 PNG 确认正确性
3. 后续运行：`./gradlew verifyRoborazziDebug` 与基准对比
4. 如果测试失败，AI 可以查看基准和实际 PNG 来判断变更是否有意为之

## 第二层：adb 视觉验证

### 前提条件

1. 模拟器 `Medium_Phone_API_36.1` 正在运行（由用户启动或通过 `emulator -avd Medium_Phone_API_36.1`）
2. `adb devices` 显示模拟器
3. 环境变量已设置：`ONECLAW_ANTHROPIC_API_KEY`、`ONECLAW_OPENAI_API_KEY`、`ONECLAW_GEMINI_API_KEY`
4. 应用 APK 已构建：`./gradlew assembleDebug`

### adb 命令参考

```bash
# 检查模拟器是否已连接
adb devices

# 安装应用
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 截图
adb exec-out screencap -p > /tmp/screen.png

# 点击坐标 (x, y)，单位像素
adb shell input tap 540 1200

# 滑动 (x1 y1 x2 y2 时长ms)
adb shell input swipe 540 1800 540 600 300

# 输入文本
adb shell input text "Hello"

# 按返回键
adb shell input keyevent KEYCODE_BACK

# 按 Home 键
adb shell input keyevent KEYCODE_HOME

# 启动应用
adb shell am start -n com.oneclaw.shadow/.MainActivity

# 清除应用数据（全新启动）
adb shell pm clear com.oneclaw.shadow

# 长按（带持续时间的点击）
adb shell input swipe 540 1200 540 1200 1000

# 获取当前 Activity（验证显示的是哪个界面）
adb shell dumpsys activity activities | head -20
```

### 坐标映射（1080x2400 px, 420 dpi）

关键 UI 元素位置（近似值，可能需要调整）：

| 元素 | 近似位置 (px) | 备注 |
|------|--------------|------|
| 汉堡菜单（左上） | (80, 120) | 打开抽屉 |
| Agent 名称（顶部居中） | (540, 120) | 打开 Agent 选择器 |
| 设置齿轮（右上） | (1000, 120) | 打开设置 |
| 输入文本框 | (440, 2250) | 点击聚焦 |
| 发送按钮 | (980, 2250) | 输入框右侧 |
| 停止按钮（流式传输时） | (980, 2250) | 与发送按钮相同位置 |
| 滚动到底部 FAB | (980, 2100) | 右下角 |
| 抽屉中第一个会话 | (400, 300) | 抽屉打开后 |
| 抽屉中"新对话" | (400, 200) | 抽屉顶部 |

**注意**：这些是估计值。AI 应先截图分析，再确定精确的点击坐标。

### 验证流程

每个流程遵循模式：通过 adb 执行操作 -> 截图 -> AI 分析截图进行验证。

#### 流程 1：首次启动 + Provider 设置

```
目标：验证欢迎界面，跳过进入聊天，然后设置 Anthropic Provider。

步骤：
1. adb shell pm clear com.oneclaw.shadow      # 全新安装状态
2. adb shell am start -n com.oneclaw.shadow/.MainActivity
3. 截图 -> 验证：显示欢迎/设置界面
4. 点击"跳过"按钮
5. 截图 -> 验证：空聊天界面显示 "How can I help you today?"
6. 点击设置齿轮（右上）
7. 截图 -> 验证：设置界面
8. 点击"Manage Providers"
9. 截图 -> 验证：Provider 列表，显示预配置的 Provider
10. 点击"Anthropic"
11. 截图 -> 验证：Anthropic Provider 详情界面
12. 点击 API key 字段，从 $ONECLAW_ANTHROPIC_API_KEY 输入 key
13. 点击"Test Connection"
14. 截图 -> 验证：连接成功指示器
15. 导航回聊天
16. 截图 -> 验证：聊天界面，发送按钮应该可用
```

#### 流程 2：发送消息 + 流式响应

```
目标：发送简单消息并验证流式响应出现。

前提条件：Provider 已配置（流程 1 完成）。

步骤：
1. 点击输入文本框
2. 输入 "What is 2 + 2? Answer in one word."
3. 点击发送按钮
4. 等待 1 秒
5. 截图 -> 验证：用户消息气泡可见，流式传输进行中（显示停止按钮）
6. 等待 5 秒（让响应完成）
7. 截图 -> 验证：AI 响应气泡包含 "Four" 或类似内容，发送按钮恢复
8. 验证：会话标题应该已更新（在顶部栏或抽屉中可见）
```

#### 流程 3：工具调用交互

```
目标：触发工具调用并验证工具调用/结果卡片出现。

步骤：
1. 点击输入框
2. 输入 "What time is it right now?"
3. 点击发送
4. 等待 3 秒
5. 截图 -> 验证：ToolCallCard 可见，显示 "get_current_time"
6. 等待 5 秒（让完整响应完成）
7. 截图 -> 验证：ToolResultCard 包含时间，AI 响应总结了时间
```

#### 流程 4：会话管理

```
目标：验证抽屉显示会话，创建新对话，在它们之间切换。

步骤：
1. 点击汉堡菜单（左上）
2. 截图 -> 验证：抽屉打开，至少列出 1 个会话
3. 点击"新对话"
4. 截图 -> 验证：空聊天界面
5. 输入并发送消息（"Hello from session 2"）
6. 等待响应
7. 点击汉堡菜单
8. 截图 -> 验证：抽屉显示 2 个会话
9. 点击第一个会话
10. 截图 -> 验证：加载了之前的对话及其消息
```

#### 流程 5：Agent 切换

```
目标：创建自定义 Agent 并在对话中切换到它。

前提条件：当前会话中至少有一条消息。

步骤：
1. 导航到设置 -> Agent 管理
2. 点击"创建 Agent"
3. 填写：名称 "Code Helper"，系统提示 "You are a coding assistant."
4. 保存
5. 导航回聊天
6. 点击顶部栏中的 Agent 名称
7. 截图 -> 验证：Agent 选择器底部弹出，有 "General Assistant" 和 "Code Helper"
8. 点击 "Code Helper"
9. 截图 -> 验证：聊天中系统消息 "Switched to Code Helper"，顶部栏 Agent 名称已更新
```

#### 流程 6：错误处理

```
目标：验证 API key 无效时的错误显示。

步骤：
1. 导航到设置 -> Providers -> Anthropic
2. 将 API key 改为 "invalid-key-12345"
3. 保存并返回聊天
4. 发送一条消息
5. 等待 5 秒
6. 截图 -> 验证：聊天中错误消息卡片，显示 "API key is invalid" 或类似内容，重试按钮可见
7. 返回，修复 API key
8. 回到聊天，点击重试
9. 截图 -> 验证：重试后新响应开始流式传输
```

#### 流程 7：停止生成

```
目标：验证停止生成保存部分文本，并且 UI 完全恢复。

步骤：
1. 发送会产生长响应的消息："Write a 500 word essay about the ocean."
2. 等待 2 秒（部分响应应该正在流式传输）
3. 点击停止按钮
4. 截图 -> 验证以下所有内容：
   - 停止按钮已切换回发送按钮（关键：UI 不得停留在流式传输状态）
   - 流式光标消失
   - 部分 AI 响应文本保留在聊天中
5. 发送另一条消息验证聊天仍然正常工作

关键检查：如果点击停止后停止按钮仍然可见，这是一个 bug。
修复方法是在 ChatViewModel 的 CancellationException catch 块中，
用 withContext(NonCancellable) {} 包裹 savePartialResponse() 和 finishStreaming()。
```

#### 流程 8：键盘弹出不推动顶部栏

```
目标：验证软键盘只调整输入区域，不影响整个屏幕。

步骤：
1. 打开任意聊天对话
2. 点击消息输入框使其获得焦点
3. 等待软键盘出现
4. 截图 -> 验证以下所有内容：
   - 顶部应用栏完整可见（左侧汉堡图标，右侧设置齿轮）
   - 汉堡图标和设置齿轮均未被隐藏或裁剪
   - 输入框在键盘正上方可见
   - 消息列表在顶部栏和输入框之间仍然可见

关键检查：如果键盘弹出后汉堡图标或设置齿轮被推出屏幕，这是一个 bug。
修复方法是在 ChatScreen.kt 的 Scaffold 上设置
contentWindowInsets = WindowInsets.ime.union(WindowInsets.navigationBars)。
```

### 第二层 AI 验证方法

对于每张截取的截图：
1. AI 使用 Read 工具读取 PNG 文件
2. AI 分析图片内容验证预期的 UI 元素
3. AI 报告 PASS/FAIL 并附说明
4. 如果 FAIL，AI 建议纠正措施

截图保存到项目根目录的 `screenshots/layer2/` 目录。这些 PNG 文件被 gitignore（仅通过 `.gitkeep` 跟踪目录结构）。
```bash
adb exec-out screencap -p > screenshots/layer2/flow1-step3-welcome.png
adb exec-out screencap -p > screenshots/layer2/flow1-step5-empty-chat.png
# 等等
```

## 执行命令汇总

```bash
# --- 第一层 ---

# 运行所有单元测试（JVM，不需要设备）
./gradlew test

# 运行特定测试类
./gradlew test --tests "com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCaseTest"

# 运行所有 instrumented 测试（需要模拟器）
./gradlew connectedAndroidTest

# 记录 Roborazzi 截图基准
./gradlew recordRoborazziDebug

# 验证截图与基准对比
./gradlew verifyRoborazziDebug

# 生成覆盖率报告
./gradlew jacocoTestReport
# 报告位于：app/build/reports/jacoco/jacocoTestReport/html/index.html

# --- 第二层 ---

# 构建 debug APK
./gradlew assembleDebug

# 安装到模拟器
adb install -r app/build/outputs/apk/debug/app-debug.apk

# 截图
adb exec-out screencap -p > screenshots/layer2/screenshot.png

# 完整第二层流程由 AI 使用 adb 命令交互式执行
```

## 覆盖率目标

| 类别 | 目标 | 理由 |
|------|------|------|
| 领域层（UseCase、Mapper） | > 90% | 核心业务逻辑，必须可靠 |
| ViewModel 层 | > 85% | 状态管理，驱动 UI |
| 数据层（Repository） | > 80% | 数据访问、持久化 |
| 适配器（SSE 解析） | > 80% | 流式传输功能的关键 |
| 工具实现 | > 80% | 工具执行必须可靠 |
| UI 层（Composable） | > 60% | 更多由截图 + adb 测试覆盖 |
| **总体** | **> 80%** | |

## 测试执行顺序

验证构建时，测试应按以下顺序执行：

1. `./gradlew test` -- 快速，捕获大多数问题
2. `./gradlew connectedAndroidTest` -- 在设备上验证数据库和 UI 逻辑
3. `./gradlew verifyRoborazziDebug` -- 捕获视觉回归
4. 第二层 adb 流程 -- 最终视觉和集成验证

如果步骤 1 失败，先修复再继续。每一步递增地建立信心。

## 参考资料

- [RFC-000 总体架构](../rfc/architecture/RFC-000-overall-architecture-zh.md) -- 项目结构、技术栈
- [RFC-001 对话交互](../rfc/features/RFC-001-chat-interaction-zh.md) -- 聊天流程、测试场景
- [RFC-002 Agent 管理](../rfc/features/RFC-002-agent-management-zh.md) -- Agent CRUD 测试
- [RFC-003 Provider 管理](../rfc/features/RFC-003-provider-management-zh.md) -- Provider 测试
- [RFC-004 工具系统](../rfc/features/RFC-004-tool-system-zh.md) -- 工具执行测试
- [RFC-005 会话管理](../rfc/features/RFC-005-session-management-zh.md) -- 会话生命周期测试

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|---------|--------|
| 2026-02-27 | 0.1 | 初始版本：两层测试策略，覆盖所有 5 个功能的完整测试目录 | - |
