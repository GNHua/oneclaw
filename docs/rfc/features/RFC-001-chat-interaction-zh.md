# RFC-001: 对话交互

## 文档信息
- **RFC 编号**: RFC-001
- **关联 PRD**: [FEAT-001 (对话交互)](../../prd/features/FEAT-001-chat-zh.md)
- **关联设计**: [UI 设计规范](../../design/ui-design-spec-zh.md)（第 1-2 节：聊天界面）
- **关联架构**: [RFC-000 (总体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **依赖**: [RFC-002 (Agent 管理)](RFC-002-agent-management-zh.md)、[RFC-003 (Provider 管理)](RFC-003-provider-management-zh.md)、[RFC-004 (工具系统)](RFC-004-tool-system-zh.md)、[RFC-005 (会话管理)](RFC-005-session-management-zh.md)
- **被依赖**: 无（这是顶层功能）
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
对话交互是 OneClawShadow 的核心用户面向功能。它编排完整的对话循环：用户发送消息，应用解析当前 Agent 的配置，通过流式 API 将消息发送给 AI 模型，实时渲染响应，处理工具调用（在本地执行工具，将结果发回模型），并重复此过程直到模型产生最终文本响应。本 RFC 涵盖完整的聊天流程，包括流式 SSE 解析、工具调用循环、消息持久化、Markdown 渲染、思考块显示、Agent 切换、停止生成、复制、重新生成，以及聊天界面布局与导航抽屉（RFC-005）和 Agent 选择器（RFC-002）的集成。

### 目标
1. 实现完整的发送消息 -> 流式响应 -> 工具调用循环 -> 渲染周期
2. 实现所有 3 种 Provider 类型（OpenAI、Anthropic、Gemini）的 SSE 流式响应解析
3. 实现工具调用执行循环，支持并行工具执行和 100 轮安全上限
4. 实现消息列表中的实时流式文本渲染
5. 实现思考块显示（默认折叠，可展开）
6. 使用第三方库实现 AI 响应的 Markdown 渲染
7. 实现停止生成（取消进行中的请求，保存部分文本）
8. 实现消息复制（长按上下文菜单）
9. 实现重新生成（重新发送最后一条用户消息，替换最后的 AI 响应）
10. 实现对话中的 Agent 切换（RFC-002 AgentSelectorSheet 集成）
11. 实现带重试的错误显示（聊天中的 ERROR 消息类型）
12. 实现自动滚动行为（底部跟随，手动滚动时停止，滚动到底部 FAB）
13. 实现 ChatScreen 布局，集成抽屉、顶部栏、消息列表和输入框
14. 提供足够的实现细节以支持 AI 辅助代码生成

### 非目标
- 会话创建、删除和列表（RFC-005）
- Agent CRUD 操作（RFC-002）
- Provider/模型配置（RFC-003）
- 工具实现细节（RFC-004）
- 对话内消息搜索
- 消息编辑（编辑已发送的消息）
- 消息分支（多个响应版本）
- 语音输入 / 文字转语音
- 图片 / 多模态输入
- 消息反馈或评分
- 导出对话

## 技术方案

### 架构概览

```
+--------------------------------------------------------------------------+
|                              UI 层                                        |
|  ChatScreen                                                               |
|    |-- TopAppBar（汉堡菜单、Agent 选择器、设置）                             |
|    |-- ModalNavigationDrawer（来自 RFC-005 的 SessionDrawerContent）         |
|    |-- MessageList（LazyColumn）                                           |
|    |     |-- MessageBubble（用户 / AI + Markdown）                          |
|    |     |-- ToolCallCard（紧凑 / 展开）                                    |
|    |     |-- ThinkingBlock（折叠 / 展开）                                   |
|    |     |-- ErrorMessage（带重试按钮）                                      |
|    |     |-- SystemMessage（Agent 切换指示器）                               |
|    |-- ChatInput（文本输入框 + 发送按钮）                                    |
|    |-- ScrollToBottomFAB                                                   |
|    |-- SnackbarHost（用于会话撤销、错误提示）                                 |
|                                                                            |
|  ChatViewModel                                                             |
|    |-- uiState: StateFlow<ChatUiState>                                     |
|    |-- sendMessage(), stopGeneration(), regenerate(), switchAgent()         |
+--------------------------------------------------------------------------+
|                            领域层                                          |
|  SendMessageUseCase  -> 返回 Flow<ChatEvent>                               |
|  StopGenerationUseCase                                                     |
|  RegenerateUseCase                                                         |
|  SwitchAgentUseCase                                                        |
|       |                                                                    |
|       v                                                                    |
|  AgentRepository, SessionRepository, MessageRepository,                    |
|  ProviderRepository, ApiKeyStorage, ToolExecutionEngine                    |
+--------------------------------------------------------------------------+
|                             数据层                                         |
|  ModelApiAdapter (OpenAI/Anthropic/Gemini) -- SSE 流式传输                  |
|  ToolExecutionEngine -- 工具执行                                            |
|  MessageDao, SessionDao -- 持久化                                          |
+--------------------------------------------------------------------------+
```

### 核心组件

1. **SendMessageUseCase**
   - 职责：编排完整的消息 -> 流式传输 -> 工具调用循环 -> 保存周期
   - 输出：`Flow<ChatEvent>` 供 ViewModel 收集
   - 依赖：AgentRepository、SessionRepository、MessageRepository、ProviderRepository、ApiKeyStorage、ModelApiAdapterFactory、ToolExecutionEngine

2. **ChatViewModel**
   - 职责：管理 UI 状态，收集 ChatEvent flow，处理用户操作
   - 状态：`StateFlow<ChatUiState>` 驱动 Compose UI
   - 持有当前请求的 `Job` 用于取消（停止生成）

3. **ModelApiAdapter SSE 解析**
   - 职责：将 Provider 特定的 SSE 流解析为统一的 `Flow<StreamEvent>`
   - 每个适配器（OpenAI、Anthropic、Gemini）处理自己的 SSE 格式

4. **ChatScreen**
   - 职责：集成所有聊天 UI 组件的顶层 Composable
   - 集成：ModalNavigationDrawer（RFC-005）、AgentSelectorSheet（RFC-002）

## 数据模型

### ChatEvent（领域层）

由 `SendMessageUseCase` 发出并由 `ChatViewModel` 收集的事件。

```kotlin
/**
 * 发送消息流程中发出的事件。
 * 位于：feature/chat/ChatEvent.kt
 */
sealed class ChatEvent {
    /** AI 响应的增量文本。 */
    data class StreamingText(val text: String) : ChatEvent()

    /** 增量思考/推理文本。 */
    data class ThinkingText(val text: String) : ChatEvent()

    /** AI 请求工具调用；即将执行。 */
    data class ToolCallStarted(
        val toolCallId: String,
        val toolName: String
    ) : ChatEvent()

    /** 工具调用参数正在流式传输（增量）。 */
    data class ToolCallArgumentsDelta(
        val toolCallId: String,
        val delta: String
    ) : ChatEvent()

    /** 工具执行完成。 */
    data class ToolCallCompleted(
        val toolCallId: String,
        val toolName: String,
        val result: ToolResult
    ) : ChatEvent()

    /** 新的工具调用轮次开始（工具已执行，将结果发回模型）。 */
    data class ToolRoundStarting(val round: Int) : ChatEvent()

    /** 完整的 AI 响应已完成（没有更多工具调用）。 */
    data class ResponseComplete(
        val message: Message,
        val usage: TokenUsage?
    ) : ChatEvent()

    /** 来自 API 的 Token 使用信息。 */
    data class TokenUsage(
        val inputTokens: Int,
        val outputTokens: Int
    )

    /** 发生了错误。 */
    data class Error(
        val message: String,
        val errorCode: ErrorCode,
        val isRetryable: Boolean
    ) : ChatEvent()
}
```

### ChatUiState

```kotlin
/**
 * 聊天界面的 UI 状态。
 * 位于：feature/chat/ChatUiState.kt
 */
data class ChatUiState(
    // 会话
    val sessionId: String? = null,          // null = 新对话（尚未持久化）
    val sessionTitle: String = "New Conversation",

    // Agent
    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val currentAgentName: String = "General Assistant",

    // 消息
    val messages: List<ChatMessageItem> = emptyList(),

    // 流式状态
    val isStreaming: Boolean = false,
    val streamingText: String = "",         // 累积的流式文本（当前响应）
    val streamingThinkingText: String = "", // 累积的思考文本
    val activeToolCalls: List<ActiveToolCall> = emptyList(),  // 当前正在执行的工具

    // 输入
    val inputText: String = "",
    val canSend: Boolean = true,            // 流式传输中或未配置 Provider 时为 false

    // 滚动
    val shouldAutoScroll: Boolean = true,

    // Agent 选择器
    val showAgentSelector: Boolean = false,

    // 错误
    val errorMessage: String? = null,

    // Provider 状态
    val hasConfiguredProvider: Boolean = false
)

/**
 * 消息列表中的单个条目。可以是用户消息、AI 响应、工具调用、
 * 工具结果、错误或系统消息。
 */
data class ChatMessageItem(
    val id: String,
    val type: MessageType,
    val content: String,
    val thinkingContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolDurationMs: Long? = null,
    val modelId: String? = null,
    val isRetryable: Boolean = false,       // 对于 ERROR 类型：显示重试按钮
    val timestamp: Long = 0
)

/**
 * 表示当前正在执行的工具调用。
 */
data class ActiveToolCall(
    val toolCallId: String,
    val toolName: String,
    val arguments: String = "",             // 累积的参数 JSON
    val status: ToolCallStatus = ToolCallStatus.PENDING
)
```

### 消息到 API 的转换

存储在数据库中的消息需要在发送给模型时转换为 Provider 特定的 API 格式。此转换在每个 `ModelApiAdapter` 实现内部处理。

```kotlin
/**
 * 用于 API 的消息中间表示。
 * 适配器将其转换为各自 Provider 特定的格式。
 * 位于：data/remote/adapter/ApiMessage.kt
 */
sealed class ApiMessage {
    data class User(val content: String) : ApiMessage()

    data class Assistant(
        val content: String,
        val thinkingContent: String? = null,
        val toolCalls: List<ApiToolCall>? = null
    ) : ApiMessage()

    data class ToolResult(
        val toolCallId: String,
        val toolName: String,
        val content: String
    ) : ApiMessage()
}

data class ApiToolCall(
    val id: String,
    val name: String,
    val arguments: String   // JSON 字符串
)
```

**从领域 `Message` 到 `ApiMessage` 的转换：**

```kotlin
/**
 * 将领域 Message 列表转换为 ApiMessage 列表以发送给模型。
 * ERROR 和 SYSTEM 消息被排除（它们是仅用于 UI 的标记）。
 * 
 * 工具调用及其结果必须正确分组：
 * - 带有工具调用的 AI_RESPONSE 后面跟着 TOOL_RESULT 消息
 * - 这些被转换为 Assistant(toolCalls) + ToolResult 对
 *
 * 位于：feature/chat/usecase/MessageToApiMapper.kt
 */
fun List<Message>.toApiMessages(): List<ApiMessage> {
    return this
        .filter { it.type != MessageType.ERROR && it.type != MessageType.SYSTEM }
        .map { message ->
            when (message.type) {
                MessageType.USER -> ApiMessage.User(content = message.content)

                MessageType.AI_RESPONSE -> {
                    // 检查此 AI 响应是否有关联的工具调用
                    // （工具调用是此消息之后的单独 TOOL_CALL 消息）
                    ApiMessage.Assistant(
                        content = message.content,
                        thinkingContent = message.thinkingContent
                    )
                }

                MessageType.TOOL_CALL -> {
                    // 转换为前一个 Assistant 消息的 toolCalls 的一部分
                    // 这由下面的分组逻辑处理
                    ApiMessage.Assistant(
                        content = "",
                        toolCalls = listOf(
                            ApiToolCall(
                                id = message.toolCallId ?: "",
                                name = message.toolName ?: "",
                                arguments = message.toolInput ?: "{}"
                            )
                        )
                    )
                }

                MessageType.TOOL_RESULT -> ApiMessage.ToolResult(
                    toolCallId = message.toolCallId ?: "",
                    toolName = message.toolName ?: "",
                    content = message.toolOutput ?: ""
                )

                else -> null  // ERROR, SYSTEM -- 跳过
            }
        }
        .filterNotNull()
}
```

**注意**：将 AI_RESPONSE + TOOL_CALL 消息实际分组为带有 `toolCalls` 的单个 Assistant 消息由每个适配器处理，因为分组格式因 Provider 而异。上面的映射器是简化视图；完整实现将 AI_RESPONSE 之后的连续 TOOL_CALL 消息分组到该响应的 `toolCalls` 列表中。

## SendMessageUseCase

这是编排整个聊天流程的核心用例。

```kotlin
/**
 * 编排完整的发送消息 -> 流式响应 -> 工具调用循环 -> 保存周期。
 *
 * 位于：feature/chat/usecase/SendMessageUseCase.kt
 */
class SendMessageUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory,
    private val toolExecutionEngine: ToolExecutionEngine,
    private val toolRegistry: ToolRegistry
) {
    companion object {
        const val MAX_TOOL_ROUNDS = 100
    }

    /**
     * 发送用户消息并获取 AI 响应。
     *
     * @param sessionId 会话 ID（必须已存在于数据库中）
     * @param userText 用户的消息文本
     * @param agentId 当前 Agent ID
     * @return ChatEvent 的 Flow，供 ViewModel 收集
     */
    fun execute(
        sessionId: String,
        userText: String,
        agentId: String
    ): Flow<ChatEvent> = channelFlow {

        // 1. 解析 Agent 配置
        val agent = agentRepository.getAgentById(agentId)
            ?: run {
                send(ChatEvent.Error("Agent not found.", ErrorCode.VALIDATION_ERROR, false))
                return@channelFlow
            }

        // 2. 解析模型和 Provider
        val resolved = resolveModel(agent)
            ?: run {
                send(ChatEvent.Error(
                    "No model configured. Please set up a provider in Settings.",
                    ErrorCode.VALIDATION_ERROR,
                    false
                ))
                return@channelFlow
            }
        val (model, provider) = resolved
        val apiKey = apiKeyStorage.getApiKey(provider.id)
            ?: run {
                send(ChatEvent.Error(
                    "API key not configured for ${provider.name}.",
                    ErrorCode.AUTH_ERROR,
                    false
                ))
                return@channelFlow
            }

        // 3. 将用户消息保存到数据库
        val userMessage = messageRepository.addMessage(Message(
            id = "",
            sessionId = sessionId,
            type = MessageType.USER,
            content = userText,
            thinkingContent = null,
            toolCallId = null,
            toolName = null,
            toolInput = null,
            toolOutput = null,
            toolStatus = null,
            toolDurationMs = null,
            tokenCountInput = null,
            tokenCountOutput = null,
            modelId = null,
            providerId = null,
            createdAt = 0
        ))

        // 4. 更新会话消息统计
        sessionRepository.updateMessageStats(
            id = sessionId,
            count = messageRepository.getMessageCount(sessionId),
            preview = userText.take(100)
        )
        sessionRepository.setActive(sessionId, true)

        // 5. 获取 Agent 的工具定义
        val agentToolDefs = if (agent.toolIds.isNotEmpty()) {
            toolRegistry.getToolsByIds(agent.toolIds)
        } else null

        // 6. 工具调用循环
        var round = 0
        try {
            while (round < MAX_TOOL_ROUNDS) {
                // 加载此会话的所有消息（完整历史）
                val allMessages = messageRepository.getMessagesSnapshot(sessionId)
                val apiMessages = allMessages.toApiMessages()

                // 获取适配器
                val adapter = adapterFactory.getAdapter(provider.type)

                // 本轮累积的响应
                var accumulatedText = ""
                var accumulatedThinking = ""
                val pendingToolCalls = mutableListOf<PendingToolCall>()
                var usage: ChatEvent.TokenUsage? = null

                // 流式接收响应
                adapter.sendMessageStream(
                    apiBaseUrl = provider.apiBaseUrl,
                    apiKey = apiKey,
                    modelId = model.id,
                    messages = apiMessages,
                    tools = agentToolDefs,
                    systemPrompt = agent.systemPrompt
                ).collect { event ->
                    when (event) {
                        is StreamEvent.TextDelta -> {
                            accumulatedText += event.text
                            send(ChatEvent.StreamingText(event.text))
                        }
                        is StreamEvent.ThinkingDelta -> {
                            accumulatedThinking += event.text
                            send(ChatEvent.ThinkingText(event.text))
                        }
                        is StreamEvent.ToolCallStart -> {
                            pendingToolCalls.add(PendingToolCall(
                                id = event.toolCallId,
                                name = event.toolName,
                                arguments = StringBuilder()
                            ))
                            send(ChatEvent.ToolCallStarted(event.toolCallId, event.toolName))
                        }
                        is StreamEvent.ToolCallDelta -> {
                            val tc = pendingToolCalls.find { it.id == event.toolCallId }
                            tc?.arguments?.append(event.argumentsDelta)
                            send(ChatEvent.ToolCallArgumentsDelta(event.toolCallId, event.argumentsDelta))
                        }
                        is StreamEvent.ToolCallEnd -> {
                            // 工具调用完全接收；将在流结束后执行
                        }
                        is StreamEvent.Usage -> {
                            usage = ChatEvent.TokenUsage(event.inputTokens, event.outputTokens)
                        }
                        is StreamEvent.Error -> {
                            throw ApiException(event.message, event.code)
                        }
                        is StreamEvent.Done -> {
                            // 本轮流式传输完成
                        }
                    }
                }

                // 保存 AI 响应消息
                val aiMessage = messageRepository.addMessage(Message(
                    id = "",
                    sessionId = sessionId,
                    type = MessageType.AI_RESPONSE,
                    content = accumulatedText,
                    thinkingContent = accumulatedThinking.ifEmpty { null },
                    toolCallId = null,
                    toolName = null,
                    toolInput = null,
                    toolOutput = null,
                    toolStatus = null,
                    toolDurationMs = null,
                    tokenCountInput = usage?.inputTokens,
                    tokenCountOutput = usage?.outputTokens,
                    modelId = model.id,
                    providerId = provider.id,
                    createdAt = 0
                ))

                // 检查是否有工具调用需要执行
                if (pendingToolCalls.isEmpty()) {
                    // 没有工具调用 -- 响应完成
                    sessionRepository.updateMessageStats(
                        id = sessionId,
                        count = messageRepository.getMessageCount(sessionId),
                        preview = accumulatedText.take(100)
                    )
                    send(ChatEvent.ResponseComplete(aiMessage, usage))
                    break
                }

                // 保存工具调用消息
                for (tc in pendingToolCalls) {
                    messageRepository.addMessage(Message(
                        id = "",
                        sessionId = sessionId,
                        type = MessageType.TOOL_CALL,
                        content = "",
                        thinkingContent = null,
                        toolCallId = tc.id,
                        toolName = tc.name,
                        toolInput = tc.arguments.toString(),
                        toolOutput = null,
                        toolStatus = ToolCallStatus.PENDING,
                        toolDurationMs = null,
                        tokenCountInput = null,
                        tokenCountOutput = null,
                        modelId = null,
                        providerId = null,
                        createdAt = 0
                    ))
                }

                // 并行执行所有工具调用
                val toolResults = coroutineScope {
                    pendingToolCalls.map { tc ->
                        async {
                            val startTime = System.currentTimeMillis()
                            val params = try {
                                Json.decodeFromString<Map<String, Any?>>(tc.arguments.toString())
                            } catch (e: Exception) {
                                emptyMap()
                            }

                            val result = toolExecutionEngine.executeTool(
                                toolName = tc.name,
                                parameters = params,
                                availableToolIds = agent.toolIds
                            )
                            val duration = System.currentTimeMillis() - startTime

                            ToolCallResult(
                                toolCallId = tc.id,
                                toolName = tc.name,
                                result = result,
                                durationMs = duration
                            )
                        }
                    }.awaitAll()
                }

                // 保存工具结果消息并发出事件
                for (tr in toolResults) {
                    messageRepository.addMessage(Message(
                        id = "",
                        sessionId = sessionId,
                        type = MessageType.TOOL_RESULT,
                        content = "",
                        thinkingContent = null,
                        toolCallId = tr.toolCallId,
                        toolName = tr.toolName,
                        toolInput = null,
                        toolOutput = tr.result.result ?: tr.result.errorMessage ?: "",
                        toolStatus = if (tr.result.status == ToolResultStatus.SUCCESS)
                            ToolCallStatus.SUCCESS else ToolCallStatus.ERROR,
                        toolDurationMs = tr.durationMs,
                        tokenCountInput = null,
                        tokenCountOutput = null,
                        modelId = null,
                        providerId = null,
                        createdAt = 0
                    ))

                    send(ChatEvent.ToolCallCompleted(tr.toolCallId, tr.toolName, tr.result))
                }

                // 准备下一轮
                round++
                if (round < MAX_TOOL_ROUNDS) {
                    send(ChatEvent.ToolRoundStarting(round))
                }
            }

            // 如果耗尽所有轮次
            if (round >= MAX_TOOL_ROUNDS) {
                send(ChatEvent.Error(
                    "Reached maximum tool call rounds ($MAX_TOOL_ROUNDS). Stopping.",
                    ErrorCode.TOOL_ERROR,
                    false
                ))
            }
        } catch (e: CancellationException) {
            // 用户停止生成 -- 保存部分响应
            // （部分文本已通过 ChatEvent.StreamingText 累积在 accumulatedText 中）
            // ViewModel 在取消时处理保存部分文本
            throw e  // 重新抛出以正确取消协程
        } catch (e: ApiException) {
            val errorCode = mapApiError(e)
            val isRetryable = errorCode != ErrorCode.AUTH_ERROR
            send(ChatEvent.Error(e.message ?: "API request failed.", errorCode, isRetryable))
        } catch (e: Exception) {
            send(ChatEvent.Error(
                "An unexpected error occurred: ${e.message}",
                ErrorCode.UNKNOWN,
                true
            ))
        } finally {
            sessionRepository.setActive(sessionId, false)
        }
    }

    /**
     * 解析此 Agent 使用的模型和 Provider。
     * Agent 首选模型/Provider -> 全局默认 -> null（错误）
     */
    private suspend fun resolveModel(agent: Agent): Pair<AiModel, Provider>? {
        // 首先尝试 Agent 的首选模型/Provider
        if (agent.preferredModelId != null && agent.preferredProviderId != null) {
            val provider = providerRepository.getProviderById(agent.preferredProviderId)
            if (provider != null && provider.isActive) {
                val models = providerRepository.getModelsForProvider(provider.id)
                val model = models.find { it.id == agent.preferredModelId }
                if (model != null) {
                    return Pair(model, provider)
                }
            }
        }

        // 回退到全局默认
        // getGlobalDefaultModel 返回 Flow；我们取当前值
        val defaultModel = providerRepository.getGlobalDefaultModel()
            .first()  // 从 Flow 取当前值
            ?: return null

        val provider = providerRepository.getProviderById(defaultModel.providerId)
            ?: return null

        if (!provider.isActive) return null

        return Pair(defaultModel, provider)
    }

    private fun mapApiError(e: ApiException): ErrorCode {
        return when {
            e.code == "401" || e.code == "403" -> ErrorCode.AUTH_ERROR
            e.code == "429" -> ErrorCode.TIMEOUT_ERROR  // 频率限制
            e.code?.startsWith("5") == true -> ErrorCode.PROVIDER_ERROR
            else -> ErrorCode.NETWORK_ERROR
        }
    }

    private data class PendingToolCall(
        val id: String,
        val name: String,
        val arguments: StringBuilder
    )

    private data class ToolCallResult(
        val toolCallId: String,
        val toolName: String,
        val result: ToolResult,
        val durationMs: Long
    )
}

/**
 * API 流式传输期间发生 API 错误时抛出的异常。
 */
class ApiException(message: String, val code: String? = null) : Exception(message)
```

### MessageRepository 扩展

`SendMessageUseCase` 需要一个快照（非 Flow）方法来获取某一时刻的所有消息。

```kotlin
// 添加到 MessageRepository 接口（core/repository/MessageRepository.kt）
interface MessageRepository {
    // ... 现有方法 ...

    /**
     * 获取会话所有消息的快照（非响应式）。
     * 供 SendMessageUseCase 构建 API 请求使用。
     */
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>
}
```

## ChatViewModel

```kotlin
/**
 * 聊天界面的 ViewModel。
 *
 * 位于：feature/chat/ChatViewModel.kt
 */
class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val generateTitleUseCase: GenerateTitleUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // 当前流式 job -- 持有以支持取消
    private var streamingJob: Job? = null

    // 跟踪是否为首条消息（用于标题生成）
    private var isFirstMessage = true
    private var firstUserMessageText: String? = null

    /**
     * 使用已有会话初始化或开始新对话。
     */
    fun initialize(sessionId: String? = null) {
        if (sessionId != null) {
            loadSession(sessionId)
        } else {
            // 新对话 -- 数据库中尚无会话（延迟创建）
            isFirstMessage = true
            _uiState.update {
                it.copy(
                    sessionId = null,
                    sessionTitle = "New Conversation",
                    currentAgentId = AgentConstants.GENERAL_ASSISTANT_ID,
                    currentAgentName = "General Assistant"
                )
            }
        }
        checkProviderStatus()
    }

    private fun loadSession(sessionId: String) {
        viewModelScope.launch {
            val session = sessionRepository.getSessionById(sessionId) ?: return@launch
            val agent = agentRepository.getAgentById(session.currentAgentId)

            _uiState.update {
                it.copy(
                    sessionId = session.id,
                    sessionTitle = session.title,
                    currentAgentId = session.currentAgentId,
                    currentAgentName = agent?.name ?: "Agent"
                )
            }

            // 加载消息
            messageRepository.getMessagesForSession(sessionId).collect { messages ->
                val items = messages.map { it.toChatMessageItem() }
                _uiState.update { it.copy(messages = items) }
            }

            isFirstMessage = false  // 已有会话
        }
    }

    private fun checkProviderStatus() {
        viewModelScope.launch {
            providerRepository.getAllProviders().collect { providers ->
                val hasActive = providers.any { it.isActive }
                _uiState.update { it.copy(hasConfiguredProvider = hasActive) }
            }
        }
    }

    // --- 用户操作 ---

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        if (_uiState.value.isStreaming) return

        viewModelScope.launch {
            // 清空输入
            _uiState.update { it.copy(inputText = "") }

            // 延迟会话创建
            var sessionId = _uiState.value.sessionId
            if (sessionId == null) {
                val session = createSessionUseCase(
                    agentId = _uiState.value.currentAgentId
                )
                sessionId = session.id
                _uiState.update { it.copy(sessionId = sessionId) }

                // 第一阶段标题生成
                val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(text)
                sessionRepository.updateTitle(sessionId, truncatedTitle)
                _uiState.update { it.copy(sessionTitle = truncatedTitle) }

                firstUserMessageText = text
                isFirstMessage = true
            }

            // 立即将用户消息添加到 UI
            val userItem = ChatMessageItem(
                id = java.util.UUID.randomUUID().toString(),
                type = MessageType.USER,
                content = text,
                timestamp = System.currentTimeMillis()
            )
            _uiState.update {
                it.copy(
                    messages = it.messages + userItem,
                    isStreaming = true,
                    streamingText = "",
                    streamingThinkingText = "",
                    activeToolCalls = emptyList(),
                    canSend = false
                )
            }

            // 开始流式传输
            streamingJob = viewModelScope.launch {
                var accumulatedText = ""
                var accumulatedThinking = ""

                try {
                    sendMessageUseCase.execute(
                        sessionId = sessionId,
                        userText = text,
                        agentId = _uiState.value.currentAgentId
                    ).collect { event ->
                        when (event) {
                            is ChatEvent.StreamingText -> {
                                accumulatedText += event.text
                                _uiState.update { it.copy(streamingText = accumulatedText) }
                            }
                            is ChatEvent.ThinkingText -> {
                                accumulatedThinking += event.text
                                _uiState.update { it.copy(streamingThinkingText = accumulatedThinking) }
                            }
                            is ChatEvent.ToolCallStarted -> {
                                _uiState.update { state ->
                                    state.copy(activeToolCalls = state.activeToolCalls + ActiveToolCall(
                                        toolCallId = event.toolCallId,
                                        toolName = event.toolName,
                                        status = ToolCallStatus.EXECUTING
                                    ))
                                }
                            }
                            is ChatEvent.ToolCallArgumentsDelta -> {
                                _uiState.update { state ->
                                    state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                        if (tc.toolCallId == event.toolCallId) {
                                            tc.copy(arguments = tc.arguments + event.delta)
                                        } else tc
                                    })
                                }
                            }
                            is ChatEvent.ToolCallCompleted -> {
                                _uiState.update { state ->
                                    state.copy(activeToolCalls = state.activeToolCalls.map { tc ->
                                        if (tc.toolCallId == event.toolCallId) {
                                            tc.copy(status = if (event.result.status == ToolResultStatus.SUCCESS)
                                                ToolCallStatus.SUCCESS else ToolCallStatus.ERROR)
                                        } else tc
                                    })
                                }
                            }
                            is ChatEvent.ToolRoundStarting -> {
                                // 为下一轮重置流式状态
                                accumulatedText = ""
                                accumulatedThinking = ""
                                _uiState.update {
                                    it.copy(
                                        streamingText = "",
                                        streamingThinkingText = "",
                                        activeToolCalls = emptyList()
                                    )
                                }
                            }
                            is ChatEvent.ResponseComplete -> {
                                finishStreaming(sessionId)
                            }
                            is ChatEvent.Error -> {
                                handleError(sessionId, event)
                            }
                        }
                    }
                } catch (e: CancellationException) {
                    // 用户停止生成 -- 保存部分文本
                    if (accumulatedText.isNotBlank()) {
                        messageRepository.addMessage(Message(
                            id = "",
                            sessionId = sessionId,
                            type = MessageType.AI_RESPONSE,
                            content = accumulatedText,
                            thinkingContent = accumulatedThinking.ifEmpty { null },
                            toolCallId = null, toolName = null, toolInput = null,
                            toolOutput = null, toolStatus = null, toolDurationMs = null,
                            tokenCountInput = null, tokenCountOutput = null,
                            modelId = null, providerId = null,
                            createdAt = 0
                        ))
                    }
                    finishStreaming(sessionId)
                }
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
    }

    fun regenerate() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        // 找到最后一条用户消息并移除其后的所有内容
        val lastUserIndex = messages.indexOfLast { it.type == MessageType.USER }
        if (lastUserIndex < 0) return

        val lastUserText = messages[lastUserIndex].content
        val sessionId = _uiState.value.sessionId ?: return

        viewModelScope.launch {
            // 从数据库中删除最后一条用户消息之后的消息
            val messagesToRemove = messages.drop(lastUserIndex + 1)
            for (msg in messagesToRemove) {
                messageRepository.deleteMessage(msg.id)
            }

            // 更新 UI
            _uiState.update {
                it.copy(messages = messages.take(lastUserIndex + 1))
            }

            // 重新发送
            _uiState.update { it.copy(inputText = "") }
            streamWithExistingMessage(sessionId, lastUserText)
        }
    }

    private fun streamWithExistingMessage(sessionId: String, userText: String) {
        _uiState.update {
            it.copy(
                isStreaming = true,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                canSend = false
            )
        }

        streamingJob = viewModelScope.launch {
            var accumulatedText = ""
            var accumulatedThinking = ""

            try {
                sendMessageUseCase.execute(
                    sessionId = sessionId,
                    userText = userText,
                    agentId = _uiState.value.currentAgentId
                ).collect { event ->
                    // 与 sendMessage() 相同的事件处理
                    // （在实际实现中提取为共享方法）
                    handleChatEvent(event, sessionId, { accumulatedText += it; accumulatedText },
                        { accumulatedThinking += it; accumulatedThinking })
                }
            } catch (e: CancellationException) {
                if (accumulatedText.isNotBlank()) {
                    savePartialResponse(sessionId, accumulatedText, accumulatedThinking)
                }
                finishStreaming(sessionId)
            }
        }
    }

    fun switchAgent(newAgentId: String) {
        val sessionId = _uiState.value.sessionId ?: return
        if (_uiState.value.isStreaming) return

        viewModelScope.launch {
            val agent = agentRepository.getAgentById(newAgentId) ?: return@launch

            // 更新会话的当前 Agent
            sessionRepository.updateCurrentAgent(sessionId, newAgentId)

            // 插入系统消息
            messageRepository.addMessage(Message(
                id = "",
                sessionId = sessionId,
                type = MessageType.SYSTEM,
                content = "Switched to ${agent.name}",
                thinkingContent = null,
                toolCallId = null, toolName = null, toolInput = null,
                toolOutput = null, toolStatus = null, toolDurationMs = null,
                tokenCountInput = null, tokenCountOutput = null,
                modelId = null, providerId = null,
                createdAt = 0
            ))

            _uiState.update {
                it.copy(
                    currentAgentId = newAgentId,
                    currentAgentName = agent.name,
                    showAgentSelector = false
                )
            }
        }
    }

    fun toggleAgentSelector() {
        _uiState.update { it.copy(showAgentSelector = !it.showAgentSelector) }
    }

    fun dismissAgentSelector() {
        _uiState.update { it.copy(showAgentSelector = false) }
    }

    fun setAutoScroll(enabled: Boolean) {
        _uiState.update { it.copy(shouldAutoScroll = enabled) }
    }

    fun retryLastMessage() {
        val messages = _uiState.value.messages
        // 移除最后一条 ERROR 消息，然后重新发送
        val lastError = messages.lastOrNull { it.type == MessageType.ERROR }
        if (lastError != null) {
            viewModelScope.launch {
                messageRepository.deleteMessage(lastError.id)
            }
        }
        regenerate()
    }

    fun copyMessageToClipboard(content: String) {
        // 由 Compose 层使用 ClipboardManager 处理
        // ViewModel 仅暴露操作；实际剪贴板访问在 composable 中
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // --- 私有辅助方法 ---

    private suspend fun finishStreaming(sessionId: String) {
        _uiState.update {
            it.copy(
                isStreaming = false,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                canSend = true
            )
        }

        // 从数据库重新加载消息以获取最终状态
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }

        // 标题生成（第二阶段）-- 仅对首条消息
        if (isFirstMessage && firstUserMessageText != null) {
            isFirstMessage = false
            val aiResponse = messages.lastOrNull { it.type == MessageType.AI_RESPONSE }
            if (aiResponse != null) {
                // 即发即忘 -- 标题生成是非阻塞的
                viewModelScope.launch {
                    generateTitleUseCase.generateAiTitle(
                        sessionId = sessionId,
                        firstUserMessage = firstUserMessageText!!,
                        firstAiResponse = aiResponse.content,
                        currentModelId = aiResponse.modelId ?: "",
                        currentProviderId = aiResponse.providerId ?: ""
                    )
                    // 重新加载会话标题
                    val session = sessionRepository.getSessionById(sessionId)
                    if (session != null) {
                        _uiState.update { it.copy(sessionTitle = session.title) }
                    }
                }
            }
        }
    }

    private suspend fun handleError(sessionId: String, error: ChatEvent.Error) {
        // 将错误作为消息保存到数据库
        messageRepository.addMessage(Message(
            id = "",
            sessionId = sessionId,
            type = MessageType.ERROR,
            content = error.message,
            thinkingContent = null,
            toolCallId = null, toolName = null, toolInput = null,
            toolOutput = null, toolStatus = null, toolDurationMs = null,
            tokenCountInput = null, tokenCountOutput = null,
            modelId = null, providerId = null,
            createdAt = 0
        ))

        _uiState.update {
            it.copy(
                isStreaming = false,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                canSend = true
            )
        }

        // 重新加载消息
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }
    }

    private suspend fun savePartialResponse(
        sessionId: String, text: String, thinking: String
    ) {
        messageRepository.addMessage(Message(
            id = "",
            sessionId = sessionId,
            type = MessageType.AI_RESPONSE,
            content = text,
            thinkingContent = thinking.ifEmpty { null },
            toolCallId = null, toolName = null, toolInput = null,
            toolOutput = null, toolStatus = null, toolDurationMs = null,
            tokenCountInput = null, tokenCountOutput = null,
            modelId = null, providerId = null,
            createdAt = 0
        ))
    }
}

// 将领域 Message 转换为 UI ChatMessageItem 的扩展函数
fun Message.toChatMessageItem(): ChatMessageItem = ChatMessageItem(
    id = id,
    type = type,
    content = content,
    thinkingContent = thinkingContent,
    toolCallId = toolCallId,
    toolName = toolName,
    toolInput = toolInput,
    toolOutput = toolOutput,
    toolStatus = toolStatus,
    toolDurationMs = toolDurationMs,
    modelId = modelId,
    isRetryable = type == MessageType.ERROR,
    timestamp = createdAt
)
```

## 其他用例

### RegenerateUseCase

注意：重新生成逻辑直接在 `ChatViewModel.regenerate()` 中处理，因为它涉及与重新发送紧密耦合的 UI 状态操作（从列表中移除消息）。无需单独的用例。

### SwitchAgentUseCase

类似地，Agent 切换在 `ChatViewModel.switchAgent()` 中处理，因为它涉及会话更新和系统消息插入，与 UI 状态紧密耦合。无需单独的用例。

### MessageRepository 扩展

```kotlin
// 添加到 MessageRepository 接口
interface MessageRepository {
    // ... 现有方法 ...

    /**
     * 获取会话所有消息的非响应式快照。
     */
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>

    /**
     * 根据 ID 删除单条消息。
     * 用于重新生成（移除最后的 AI 响应）和重试（移除错误消息）。
     */
    suspend fun deleteMessage(id: String)
}
```

## SSE 流式传输实现

每个 Provider 适配器必须实现 `sendMessageStream()` 来将其特定的 SSE 格式解析为 `Flow<StreamEvent>`。

### OpenAI SSE 格式

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant","content":"Hello"},"index":0}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"read_file","arguments":""}}]},"index":0}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"index":0}]}

data: [DONE]
```

### Anthropic SSE 格式

```
event: message_start
data: {"type":"message_start","message":{"id":"msg_xxx","type":"message","role":"assistant","content":[],"model":"claude-sonnet-4-20250514"}}

event: content_block_start
data: {"type":"content_block_start","index":0,"content_block":{"type":"thinking","thinking":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":0,"delta":{"type":"thinking_delta","thinking":"Let me..."}}

event: content_block_start
data: {"type":"content_block_start","index":1,"content_block":{"type":"text","text":""}}

event: content_block_delta
data: {"type":"content_block_delta","index":1,"delta":{"type":"text_delta","text":"Hello"}}

event: content_block_start
data: {"type":"content_block_start","index":2,"content_block":{"type":"tool_use","id":"toolu_xxx","name":"read_file","input":{}}}

event: content_block_delta
data: {"type":"content_block_delta","index":2,"delta":{"type":"input_json_delta","partial_json":"{\"path\":"}}

event: message_delta
data: {"type":"message_delta","delta":{"stop_reason":"end_turn"},"usage":{"output_tokens":42}}

event: message_stop
data: {"type":"message_stop"}
```

### Gemini SSE 格式

```
data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}]}

data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"/test.txt"}}}],"role":"model"},"index":0}]}

data: {"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":42}}
```

### SSE 解析器工具类

```kotlin
/**
 * 通用 SSE 行解析器。从 OkHttp ResponseBody 读取并发出 SSE 事件。
 *
 * 位于：data/remote/sse/SseParser.kt
 */
fun ResponseBody.asSseFlow(): Flow<SseEvent> = callbackFlow {
    val reader = source().buffer()
    try {
        var eventType: String? = null
        var data = StringBuilder()

        while (!reader.exhausted()) {
            val line = reader.readUtf8Line() ?: break

            when {
                line.startsWith("event:") -> {
                    eventType = line.removePrefix("event:").trim()
                }
                line.startsWith("data:") -> {
                    data.append(line.removePrefix("data:").trim())
                }
                line.isEmpty() -> {
                    // 空行 = 事件结束
                    if (data.isNotEmpty()) {
                        trySend(SseEvent(type = eventType, data = data.toString()))
                        eventType = null
                        data = StringBuilder()
                    }
                }
            }
        }
    } catch (e: Exception) {
        if (e !is CancellationException) {
            close(e)
        }
    } finally {
        reader.close()
        close()
    }
}

data class SseEvent(
    val type: String?,     // 事件类型（例如 Anthropic 的 "message_start"）
    val data: String       // 事件数据（JSON 字符串）
)
```

每个适配器随后收集 `SseEvent` 并根据 Provider 特定的 JSON 格式将其映射为 `StreamEvent`。

## UI 层

### ChatScreen

```kotlin
/**
 * 顶层聊天界面 composable。
 * 位于：feature/chat/ChatScreen.kt
 */
@Composable
fun ChatScreen(
    viewModel: ChatViewModel,
    sessionListViewModel: SessionListViewModel,
    onNavigateToSettings: () -> Unit,
    onNavigateToSession: (String) -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val sessionListState by sessionListViewModel.uiState.collectAsStateWithLifecycle()
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val clipboardManager = LocalClipboardManager.current

    // 新内容到达时自动滚动
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (uiState.shouldAutoScroll && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // 检测手动滚动以禁用自动滚动
    val isAtBottom by remember {
        derivedStateOf {
            val lastVisible = listState.layoutInfo.visibleItemsInfo.lastOrNull()
            lastVisible?.index == listState.layoutInfo.totalItemsCount - 1
        }
    }

    LaunchedEffect(isAtBottom) {
        viewModel.setAutoScroll(isAtBottom)
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                SessionDrawerContent(
                    viewModel = sessionListViewModel,
                    onNewConversation = {
                        viewModel.initialize(null)
                        scope.launch { drawerState.close() }
                    },
                    onSessionClick = { sessionId ->
                        viewModel.initialize(sessionId)
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Scaffold(
            topBar = {
                ChatTopBar(
                    agentName = uiState.currentAgentName,
                    onMenuClick = { scope.launch { drawerState.open() } },
                    onAgentClick = { viewModel.toggleAgentSelector() },
                    onSettingsClick = onNavigateToSettings
                )
            },
            snackbarHost = { SnackbarHost(snackbarHostState) },
            bottomBar = {
                ChatInput(
                    text = uiState.inputText,
                    onTextChange = { viewModel.updateInputText(it) },
                    onSend = { viewModel.sendMessage() },
                    onStop = { viewModel.stopGeneration() },
                    isStreaming = uiState.isStreaming,
                    canSend = uiState.canSend && uiState.hasConfiguredProvider
                )
            }
        ) { padding ->
            Box(modifier = Modifier.padding(padding)) {
                if (uiState.messages.isEmpty() && !uiState.isStreaming) {
                    // 空状态
                    EmptyChatState()
                } else {
                    // 消息列表
                    MessageList(
                        messages = uiState.messages,
                        streamingText = uiState.streamingText,
                        streamingThinkingText = uiState.streamingThinkingText,
                        activeToolCalls = uiState.activeToolCalls,
                        isStreaming = uiState.isStreaming,
                        listState = listState,
                        onCopy = { content ->
                            clipboardManager.setText(AnnotatedString(content))
                        },
                        onRetry = { viewModel.retryLastMessage() },
                        onRegenerate = { viewModel.regenerate() }
                    )
                }

                // 滚动到底部 FAB
                if (!uiState.shouldAutoScroll && uiState.messages.isNotEmpty()) {
                    FloatingActionButton(
                        onClick = {
                            viewModel.setAutoScroll(true)
                            scope.launch {
                                listState.animateScrollToItem(
                                    listState.layoutInfo.totalItemsCount - 1
                                )
                            }
                        },
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp),
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Icon(Icons.Default.KeyboardArrowDown, "Scroll to bottom")
                    }
                }
            }
        }
    }

    // Agent 选择器底部弹出
    if (uiState.showAgentSelector) {
        AgentSelectorSheet(
            onAgentSelected = { agentId -> viewModel.switchAgent(agentId) },
            onDismiss = { viewModel.dismissAgentSelector() }
        )
    }

    // 未配置 Provider 警告
    if (!uiState.hasConfiguredProvider) {
        LaunchedEffect(Unit) {
            val result = snackbarHostState.showSnackbar(
                message = "No provider configured. Set up in Settings.",
                actionLabel = "Settings",
                duration = SnackbarDuration.Indefinite
            )
            if (result == SnackbarResult.ActionPerformed) {
                onNavigateToSettings()
            }
        }
    }

    // 会话撤销 Snackbar
    sessionListState.undoState?.let { undoState ->
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

### ChatTopBar

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatTopBar(
    agentName: String,
    onMenuClick: () -> Unit,
    onAgentClick: () -> Unit,
    onSettingsClick: () -> Unit
) {
    TopAppBar(
        navigationIcon = {
            IconButton(onClick = onMenuClick) {
                Icon(Icons.Default.Menu, contentDescription = "Menu")
            }
        },
        title = {
            // 居中的 Agent 选择器
            Row(
                modifier = Modifier
                    .clickable { onAgentClick() }
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = agentName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Icon(
                    Icons.Default.ArrowDropDown,
                    contentDescription = "Switch agent",
                    modifier = Modifier.size(20.dp)
                )
            }
        },
        actions = {
            IconButton(onClick = onSettingsClick) {
                Icon(Icons.Default.Settings, contentDescription = "Settings")
            }
        }
    )
}
```

### ChatInput

```kotlin
@Composable
fun ChatInput(
    text: String,
    onTextChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit,
    isStreaming: Boolean,
    canSend: Boolean
) {
    Surface(
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.weight(1f),
                placeholder = { Text("Message") },
                shape = MaterialTheme.shapes.extraLarge,  // 药丸形状
                maxLines = 6,
                enabled = !isStreaming
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isStreaming) {
                // 停止按钮
                IconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                // 发送按钮
                IconButton(
                    onClick = onSend,
                    enabled = canSend && text.isNotBlank(),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.primary
                    )
                ) {
                    Icon(Icons.Default.Send, contentDescription = "Send")
                }
            }
        }
    }
}
```

### MessageList

```kotlin
@Composable
fun MessageList(
    messages: List<ChatMessageItem>,
    streamingText: String,
    streamingThinkingText: String,
    activeToolCalls: List<ActiveToolCall>,
    isStreaming: Boolean,
    listState: LazyListState,
    onCopy: (String) -> Unit,
    onRetry: () -> Unit,
    onRegenerate: () -> Unit
) {
    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = messages,
            key = { it.id }
        ) { message ->
            when (message.type) {
                MessageType.USER -> UserMessageBubble(
                    content = message.content,
                    onCopy = { onCopy(message.content) }
                )
                MessageType.AI_RESPONSE -> AiMessageBubble(
                    content = message.content,
                    thinkingContent = message.thinkingContent,
                    modelId = message.modelId,
                    isLastAiMessage = message == messages.lastOrNull { it.type == MessageType.AI_RESPONSE },
                    onCopy = { onCopy(message.content) },
                    onRegenerate = onRegenerate
                )
                MessageType.TOOL_CALL -> ToolCallCard(
                    toolName = message.toolName ?: "Unknown tool",
                    toolInput = message.toolInput,
                    status = message.toolStatus ?: ToolCallStatus.PENDING
                )
                MessageType.TOOL_RESULT -> ToolResultCard(
                    toolName = message.toolName ?: "Unknown tool",
                    toolOutput = message.toolOutput,
                    status = message.toolStatus ?: ToolCallStatus.SUCCESS,
                    durationMs = message.toolDurationMs
                )
                MessageType.ERROR -> ErrorMessageCard(
                    content = message.content,
                    isRetryable = message.isRetryable,
                    onRetry = onRetry
                )
                MessageType.SYSTEM -> SystemMessageCard(
                    content = message.content
                )
            }
        }

        // 流式 AI 响应（进行中）
        if (isStreaming && (streamingText.isNotEmpty() || streamingThinkingText.isNotEmpty())) {
            item(key = "streaming") {
                AiMessageBubble(
                    content = streamingText,
                    thinkingContent = streamingThinkingText.ifEmpty { null },
                    modelId = null,
                    isLastAiMessage = false,
                    onCopy = { onCopy(streamingText) },
                    onRegenerate = {},
                    isStreaming = true
                )
            }
        }

        // 活跃的工具调用（进行中）
        if (activeToolCalls.isNotEmpty()) {
            items(activeToolCalls, key = { it.toolCallId }) { tc ->
                ToolCallCard(
                    toolName = tc.toolName,
                    toolInput = tc.arguments,
                    status = tc.status
                )
            }
        }
    }
}
```

### 消息气泡组件

```kotlin
@Composable
fun UserMessageBubble(
    content: String,
    onCopy: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.End
    ) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(16.dp, 16.dp, 4.dp, 16.dp),
            modifier = Modifier
                .widthIn(max = 300.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = onCopy
                )
        ) {
            Text(
                text = content,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(12.dp),
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
fun AiMessageBubble(
    content: String,
    thinkingContent: String?,
    modelId: String?,
    isLastAiMessage: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    isStreaming: Boolean = false
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        // 思考块（默认折叠）
        if (!thinkingContent.isNullOrBlank()) {
            ThinkingBlock(content = thinkingContent)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // 带 Markdown 渲染的 AI 响应
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLow,
            shape = RoundedCornerShape(16.dp, 16.dp, 16.dp, 4.dp),
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(
                    onClick = {},
                    onLongClick = onCopy
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (content.isNotEmpty()) {
                    // 通过第三方库进行 Markdown 渲染
                    MarkdownText(
                        markdown = content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isStreaming) {
                    // 流式光标
                    StreamingCursor()
                }
            }
        }

        // 操作按钮行（仅对已完成的最后一条 AI 消息）
        if (!isStreaming && isLastAiMessage && content.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // 复制按钮
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 重新生成按钮
                IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Regenerate",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // 模型标签
                if (modelId != null) {
                    Text(
                        text = modelId,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .align(Alignment.CenterVertically)
                            .padding(start = 8.dp)
                    )
                }
            }
        }
    }
}
```

### ThinkingBlock

```kotlin
@Composable
fun ThinkingBlock(content: String) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded },
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "Thinking...",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (expanded) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = content,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
```

### ToolCallCard 和 ToolResultCard

```kotlin
@Composable
fun ToolCallCard(
    toolName: String,
    toolInput: String?,
    status: ToolCallStatus
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态图标
            when (status) {
                ToolCallStatus.PENDING, ToolCallStatus.EXECUTING -> {
                    CircularProgressIndicator(
                        modifier = Modifier.size(16.dp),
                        strokeWidth = 2.dp
                    )
                }
                ToolCallStatus.SUCCESS -> {
                    Icon(Icons.Default.CheckCircle, "Success",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.primary)
                }
                ToolCallStatus.ERROR -> {
                    Icon(Icons.Default.Error, "Error",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
                ToolCallStatus.TIMEOUT -> {
                    Icon(Icons.Default.Timer, "Timeout",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error)
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = toolName,
                style = MaterialTheme.typography.labelMedium,
                fontFamily = FontFamily.Monospace
            )
        }
    }
}

@Composable
fun ToolResultCard(
    toolName: String,
    toolOutput: String?,
    status: ToolCallStatus,
    durationMs: Long?
) {
    var expanded by remember { mutableStateOf(false) }

    Surface(
        color = if (status == ToolCallStatus.SUCCESS)
            MaterialTheme.colorScheme.surfaceContainerLow
        else
            MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f),
        shape = MaterialTheme.shapes.small,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 2.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "$toolName result",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (durationMs != null) {
                    Text(
                        text = " (${durationMs}ms)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                Icon(
                    if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                    contentDescription = "Toggle",
                    modifier = Modifier.size(16.dp)
                )
            }

            if (expanded && !toolOutput.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = toolOutput,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    maxLines = 20,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}
```

### ErrorMessageCard 和 SystemMessageCard

```kotlin
@Composable
fun ErrorMessageCard(
    content: String,
    isRetryable: Boolean,
    onRetry: () -> Unit
) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Error,
                contentDescription = "Error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = content,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.weight(1f)
            )
            if (isRetryable) {
                TextButton(onClick = onRetry) {
                    Text("Retry")
                }
            }
        }
    }
}

@Composable
fun SystemMessageCard(content: String) {
    Text(
        text = content,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    )
}
```

### EmptyChatState

```kotlin
@Composable
fun EmptyChatState() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "How can I help you today?",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
```

### StreamingCursor

```kotlin
@Composable
fun StreamingCursor() {
    val infiniteTransition = rememberInfiniteTransition(label = "cursor")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 0f,
        animationSpec = infiniteRepeatable(
            animation = tween(500),
            repeatMode = RepeatMode.Reverse
        ),
        label = "cursorAlpha"
    )

    Text(
        text = "|",
        style = MaterialTheme.typography.bodyLarge,
        color = MaterialTheme.colorScheme.primary.copy(alpha = alpha)
    )
}
```

## Koin 依赖注入

```kotlin
// 添加到 FeatureModule.kt
val featureModule = module {
    // ... 现有条目 ...

    // 聊天
    factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    // AgentRepository, SessionRepository, MessageRepository, ProviderRepository,
    // ApiKeyStorage, ModelApiAdapterFactory, ToolExecutionEngine, ToolRegistry
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    // SendMessageUseCase, SessionRepository, MessageRepository, AgentRepository,
    // CreateSessionUseCase, GenerateTitleUseCase
}
```

## 数据流示例

### 流程 1：普通消息的流式响应（无工具调用）

```
1. 用户输入 "What is the capital of France?" 并点击发送
2. ChatViewModel.sendMessage()
   -> 延迟会话创建：CreateSessionUseCase("agent-general-assistant")
   -> 创建会话 ID "session-123"
   -> 第一阶段标题："What is the capital of France?"
   -> UI：用户消息出现，输入清空，isStreaming = true
3. SendMessageUseCase.execute("session-123", "What is the capital of...", "agent-general-assistant")
   -> 解析 Agent -> General Assistant
   -> 解析模型 -> gpt-4o via provider-openai
   -> 将用户消息保存到数据库
   -> 设置会话为活跃
   -> 调用 OpenAiAdapter.sendMessageStream(...)
4. SSE 事件到达：
   -> TextDelta("The") -> ChatEvent.StreamingText("The")
   -> TextDelta(" capital") -> ChatEvent.StreamingText(" capital")
   -> TextDelta(" of France is Paris.") -> ChatEvent.StreamingText(" of France is Paris.")
   -> Usage(input=15, output=8)
   -> Done
5. ChatViewModel 收集事件：
   -> streamingText 累积："The" -> "The capital" -> "The capital of France is Paris."
   -> UI 实时更新，用户逐字看到文本出现
6. 流结束：
   -> AI 消息保存到数据库
   -> 会话消息统计更新
   -> 发出 ChatEvent.ResponseComplete
   -> finishStreaming：isStreaming = false，重新加载消息
7. 第二阶段标题生成异步触发：
   -> gpt-4o-mini 生成标题 "Capital of France"
   -> 会话标题更新
```

### 流程 2：带工具调用循环的消息

```
1. 用户发送 "What time is it in Tokyo right now?"
2. SendMessageUseCase 开始...
3. 第 0 轮：模型流式响应
   -> TextDelta("Let me check the current time.")
   -> ToolCallStart("call_1", "get_current_time")
   -> ToolCallDelta("call_1", '{"timezone":"Asia/Tokyo"}')
   -> ToolCallEnd("call_1")
   -> Done
4. AI 响应保存到数据库（部分文本 + 工具调用标记）
5. 工具调用作为 TOOL_CALL 消息保存到数据库
6. ToolExecutionEngine.executeTool("get_current_time", {timezone: "Asia/Tokyo"}, [...])
   -> 返回：ToolResult(SUCCESS, "2026-02-27T22:30:00+09:00")
7. 工具结果作为 TOOL_RESULT 消息保存到数据库
8. 发出 ChatEvent.ToolCallCompleted
9. 第 1 轮：使用完整历史（包括工具结果）再次调用模型
   -> TextDelta("The current time in Tokyo is 10:30 PM on February 27, 2026.")
   -> Done（没有更多工具调用）
10. 最终 AI 响应保存
11. 发出 ChatEvent.ResponseComplete
```

### 流程 3：停止生成

```
1. 用户发送长查询，AI 开始流式传输长响应
2. 用户点击"停止"按钮
3. ChatViewModel.stopGeneration() -> streamingJob.cancel()
4. CancellationException 在 ViewModel 中被捕获
5. 累积的文本（"The answer involves several factors..."）保存到数据库
6. 调用 finishStreaming()
7. 用户在聊天中看到部分 AI 响应
8. 用户可以发送新消息
```

### 流程 4：重新生成

```
1. 用户对 AI 的最后响应不满意
2. 用户点击最后一条 AI 消息上的重新生成按钮
3. ChatViewModel.regenerate()
   -> 找到最后一条用户消息："Explain quantum computing"
   -> 移除其后的所有消息（AI 响应 + 任何工具调用/结果）
   -> 从数据库中删除这些消息
   -> 调用 streamWithExistingMessage()
4. 使用相同的用户消息发送新的流式请求
5. 新的 AI 响应流式传入
6. 旧响应已消失；新响应取而代之
```

### 流程 5：Agent 切换

```
1. 用户正在与 General Assistant 聊天
2. 用户点击顶部栏中的 Agent 名称 -> AgentSelectorSheet 打开
3. 用户选择 "Code Helper"
4. ChatViewModel.switchAgent("code-helper-id")
   -> SessionRepository.updateCurrentAgent("session-123", "code-helper-id")
   -> MessageRepository.addMessage(SYSTEM, "Switched to Code Helper")
   -> UI 更新：顶部栏中的 Agent 名称，聊天中的系统消息
5. 用户发送下一条消息 -> 使用 Code Helper 的 systemPrompt、工具和模型
6. 历史保留：Code Helper 可以看到所有之前的消息
```

## 错误处理

### 错误场景和用户可见消息

| 场景 | ErrorCode | 用户消息 | UI 行为 |
|------|-----------|---------|---------|
| 未配置 Provider | VALIDATION_ERROR | "No provider configured. Set up in Settings." | 带设置链接的 Snackbar |
| API 密钥缺失 | AUTH_ERROR | "API key not configured for [Provider]." | ERROR 消息，不可重试 |
| API 密钥无效（401） | AUTH_ERROR | "API key is invalid. Please check your settings." | ERROR 消息，不可重试 |
| 频率限制（429） | TIMEOUT_ERROR | "Rate limited. Please wait and try again." | ERROR 消息，可重试 |
| 服务器错误（5xx） | PROVIDER_ERROR | "Server error. Please try again." | ERROR 消息，可重试 |
| 网络错误 | NETWORK_ERROR | "Network error. Check your connection." | ERROR 消息，可重试 |
| 工具执行失败 | TOOL_ERROR | （工具结果显示错误） | 带错误状态的 ToolResultCard；模型决定如何继续 |
| 达到最大工具轮次 | TOOL_ERROR | "Reached maximum tool call rounds (100)." | ERROR 消息，不可重试 |
| 超出上下文窗口 | PROVIDER_ERROR | "Conversation too long. Start a new conversation." | ERROR 消息，不可重试 |
| Agent 未找到 | VALIDATION_ERROR | "Agent not found." | ERROR 消息，不可重试 |

## 实现步骤

### 阶段 1：领域层
1. [ ] 创建 `ChatEvent` sealed class
2. [ ] 创建 `ApiMessage` sealed class 和 `ApiToolCall` data class
3. [ ] 创建 `MessageToApiMapper`（Message -> ApiMessage 转换）
4. [ ] 创建 `ApiException` 类
5. [ ] 向 `MessageRepository` 接口添加 `getMessagesSnapshot()` 和 `deleteMessage()`
6. [ ] 在 `MessageRepositoryImpl` 中实现 `getMessagesSnapshot()` 和 `deleteMessage()`

### 阶段 2：SSE 流式传输
7. [ ] 实现 `SseParser` 工具类（ResponseBody -> Flow<SseEvent>）
8. [ ] 在 `OpenAiAdapter.sendMessageStream()` 中实现 OpenAI SSE -> StreamEvent 映射
9. [ ] 在 `AnthropicAdapter.sendMessageStream()` 中实现 Anthropic SSE -> StreamEvent 映射
10. [ ] 在 `GeminiAdapter.sendMessageStream()` 中实现 Gemini SSE -> StreamEvent 映射
11. [ ] 在每个适配器中实现消息到 API 格式的转换

### 阶段 3：SendMessageUseCase
12. [ ] 实现包含完整工具调用循环的 `SendMessageUseCase`
13. [ ] 实现模型解析（Agent 首选 -> 全局默认）
14. [ ] 通过 `coroutineScope + async` 实现并行工具执行
15. [ ] 测试：普通消息流程（无工具）
16. [ ] 测试：工具调用循环（1 轮、2 轮、并行工具）
17. [ ] 测试：达到最大工具轮次

### 阶段 4：ChatViewModel
18. [ ] 实现包含所有状态管理的 `ChatViewModel`
19. [ ] 实现带延迟会话创建的 `sendMessage()`
20. [ ] 实现带部分文本保存的 `stopGeneration()`
21. [ ] 实现 `regenerate()`（删除 + 重新发送）
22. [ ] 实现带系统消息的 `switchAgent()`
23. [ ] 实现标题生成集成（第一阶段 + 第二阶段）
24. [ ] 实现 `retryLastMessage()`

### 阶段 5：UI 组件
25. [ ] 实现 `ChatScreen` 布局（Scaffold + Drawer + TopBar + Input）
26. [ ] 实现带 Agent 选择器触发器的 `ChatTopBar`
27. [ ] 实现 `ChatInput`（药丸形文本输入框 + 发送/停止按钮）
28. [ ] 实现 `MessageList`（包含所有消息类型的 LazyColumn）
29. [ ] 实现 `UserMessageBubble`
30. [ ] 实现带 Markdown 渲染的 `AiMessageBubble`（第三方库集成）
31. [ ] 实现 `ThinkingBlock`（折叠/可展开）
32. [ ] 实现 `ToolCallCard` 和 `ToolResultCard`
33. [ ] 实现带重试按钮的 `ErrorMessageCard`
34. [ ] 实现 `SystemMessageCard`
35. [ ] 实现 `EmptyChatState`
36. [ ] 实现 `StreamingCursor` 动画
37. [ ] 实现自动滚动行为（底部跟随，手动滚动时停止，FAB）
38. [ ] 实现消息气泡的长按复制

### 阶段 6：集成
39. [ ] 将 AgentSelectorSheet（RFC-002）集成到 ChatScreen
40. [ ] 将 SessionDrawerContent（RFC-005）集成到 ChatScreen
41. [ ] 更新 Koin 模块（SendMessageUseCase、ChatViewModel）
42. [ ] 更新导航以使用 ChatScreen 作为主页
43. [ ] 端到端测试：完整的流式传输 + 工具调用对话
44. [ ] 端到端测试：停止生成 + 部分保存
45. [ ] 端到端测试：重新生成
46. [ ] 端到端测试：对话中途 Agent 切换
47. [ ] 端到端测试：错误 + 重试
48. [ ] 性能测试：长对话滚动

## 测试策略

### 单元测试
- `SendMessageUseCase`：正常流程、工具调用循环、最大轮次、取消、各种错误
- `MessageToApiMapper`：所有消息类型、ERROR/SYSTEM 排除、工具调用分组
- `ChatViewModel`：所有用户操作的状态转换、流式文本累积、停止、重新生成、Agent 切换
- `SseParser`：格式正确的 SSE、格式错误的行、空事件
- 每个适配器的 SSE -> StreamEvent 映射：所有事件类型、边界情况

### 集成测试（Instrumented）
- 使用模拟 API 的完整消息发送和接收
- 使用真实 ToolExecutionEngine 的工具调用执行
- 会话创建流程（延迟创建 + 标题生成）
- 消息持久化：发送 -> 杀掉应用 -> 重新打开 -> 消息完整
- 重新生成：消息正确移除和重新创建

### UI 测试
- 发送按钮的启用/禁用状态
- 流式传输期间停止按钮出现
- 自动滚动跟随新内容
- 手动滚动禁用自动滚动 + FAB 出现
- 长按复制功能
- 重新生成按钮在最后一条 AI 消息上可见
- 带重试按钮的错误消息
- Agent 切换系统消息出现
- 无消息时的空状态
- 思考块折叠/展开

### 边界情况
- 未配置 Provider 时发送消息
- 使用无效 API 密钥发送消息
- 非常长的消息（10,000+ 字符）
- 快速连续发送（防抖）
- 发送后立即停止生成（在任何响应之前）
- 工具执行期间停止生成
- 流式传输时重新生成（应被禁用）
- 流式传输时切换 Agent（应被禁用）
- 工具返回非常大的输出（100KB+ 由工具截断）
- 流式传输期间网络断开
- 流式传输期间应用被杀掉（此情况下不应保存部分文本）
- 达到 100 轮工具调用
- 带格式错误 JSON 参数的工具调用
- 模型返回空响应

## 安全考虑

1. **API 密钥**：仅在适配器内部用于 HTTP 请求。从不记录日志，从不存储在消息中。
2. **用户消息**：本地存储在 Room 中。仅发送到用户配置的 Provider 端点。
3. **工具结果**：可能包含本地文件内容或 HTTP 响应数据。存储在数据库中。作为上下文发回给模型。
4. **剪贴板**：消息复制使用系统剪贴板。用户知道他们正在复制。
5. **系统提示注入**：Agent 系统提示由用户控制（他们创建 Agent）。没有外部注入风险。

## 依赖

### 依赖
- **RFC-000（总体架构）**：项目结构、核心模型、数据库
- **RFC-002（Agent 管理）**：AgentRepository、AgentSelectorSheet、AgentConstants
- **RFC-003（Provider 管理）**：ProviderRepository、ApiKeyStorage、ModelApiAdapterFactory、ModelApiAdapter
- **RFC-004（工具系统）**：ToolExecutionEngine、ToolRegistry、ToolDefinition、ToolResult
- **RFC-005（会话管理）**：SessionRepository、CreateSessionUseCase、GenerateTitleUseCase、SessionDrawerContent

### 被依赖
- 无（这是集成所有其他功能的顶层功能）

## 第三方库

| 库 | 用途 | 备注 |
|---|------|------|
| compose-markdown (Mikepenz) | AI 消息中的 Markdown 渲染 | 最活跃的 Compose Markdown 库。支持代码块、表格、链接。如果在实现时发现更好的选项可以替换。 |

## 待解决问题

- [ ] 确切的 compose-markdown 库版本和 API 应在实现时验证
- [ ] V1 中是否应该为代码块添加语法高亮？（compose-markdown 通过 highlight.js 集成支持，但增加复杂度）
- [ ] 超出上下文窗口时的自动压缩对话历史（未来改进 -- V1 发送完整历史，如果太长则显示错误）

## 未来改进

- [ ] **自动压缩**：接近上下文窗口限制时自动摘要/截断旧消息
- [ ] **消息编辑**：编辑之前发送的用户消息并从该点重新生成
- [ ] **消息分支**：保留多个响应版本，允许用户在它们之间导航
- [ ] **语音输入**：消息输入的语音转文字
- [ ] **图片输入**：支持视觉能力模型的多模态支持
- [ ] **代码块复制**：代码块上的一键复制按钮
- [ ] **语法高亮**：按语言着色的代码块
- [ ] **消息搜索**：在对话历史中搜索
- [ ] **消息反馈**：对 AI 响应的点赞/点踩
- [ ] **导出对话**：将对话保存为 Markdown/PDF

## 参考资料

- [FEAT-001 PRD](../../prd/features/FEAT-001-chat-zh.md) -- 功能需求
- [UI 设计规范](../../design/ui-design-spec-zh.md) -- 聊天界面布局
- [RFC-000 总体架构](../architecture/RFC-000-overall-architecture-zh.md) -- 核心模型、项目结构
- [RFC-002 Agent 管理](RFC-002-agent-management-zh.md) -- AgentSelectorSheet、Agent 解析
- [RFC-003 Provider 管理](RFC-003-provider-management-zh.md) -- ModelApiAdapter、Provider 适配器
- [RFC-004 工具系统](RFC-004-tool-system-zh.md) -- ToolExecutionEngine、内置工具
- [RFC-005 会话管理](RFC-005-session-management-zh.md) -- 延迟会话创建、标题生成、抽屉

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
