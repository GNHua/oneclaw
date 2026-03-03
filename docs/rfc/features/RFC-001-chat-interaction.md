# RFC-001: Chat Interaction

## Document Information
- **RFC ID**: RFC-001
- **Related PRD**: [FEAT-001 (Chat Interaction)](../../prd/features/FEAT-001-chat.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md) (Sections 1-2: Chat Screen)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-002 (Agent Management)](RFC-002-agent-management.md), [RFC-003 (Provider Management)](RFC-003-provider-management.md), [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-005 (Session Management)](RFC-005-session-management.md)
- **Depended On By**: None (this is the top-level feature)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27 (updated with implementation fixes from Layer 2 testing)
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
Chat Interaction is the core user-facing feature of OneClaw. It orchestrates the full conversation loop: user sends a message, the app resolves the current Agent's configuration, sends the message to an AI model via streaming API, renders the response in real-time, handles tool calls (execute tools locally, send results back to the model), and repeats until the model produces a final text response. This RFC covers the complete chat flow including streaming SSE parsing, tool call loop, message persistence, Markdown rendering, thinking block display, agent switching, stop generation, copy, regenerate, and the chat screen layout integrating with the Navigation Drawer (RFC-005) and Agent Selector (RFC-002).

### Goals
1. Implement the full send-message -> stream-response -> tool-call-loop -> render cycle
2. Implement SSE streaming response parsing for all 3 provider types (OpenAI, Anthropic, Gemini)
3. Implement the tool call execution loop with parallel tool execution and a 100-round safety limit
4. Implement real-time streaming text rendering in the message list
5. Implement thinking block display (collapsed by default, expandable)
6. Implement Markdown rendering for AI responses using a third-party library
7. Implement stop generation (cancel in-flight request, save partial text)
8. Implement message copy (long-press context menu)
9. Implement regenerate (re-send last user message, replace last AI response)
10. Implement Agent switching within a conversation (RFC-002 AgentSelectorSheet integration)
11. Implement error display with retry (ERROR message type in chat)
12. Implement auto-scroll behavior (follow at bottom, stop on manual scroll, scroll-to-bottom FAB)
13. Implement the ChatScreen layout integrating drawer, top bar, message list, and input
14. Provide enough implementation detail for AI-assisted code generation

### Non-Goals
- Session creation, deletion, and listing (RFC-005)
- Agent CRUD operations (RFC-002)
- Provider/model configuration (RFC-003)
- Tool implementation details (RFC-004)
- Message search within a conversation
- Message editing (edit a sent message)
- Message branching (multiple response versions)
- Voice input / text-to-speech
- Image / multimodal input
- Message reactions or ratings
- Export conversation

## Technical Design

### Architecture Overview

```
+--------------------------------------------------------------------------+
|                              UI Layer                                      |
|  ChatScreen                                                                |
|    |-- TopAppBar (hamburger, agent selector, settings)                     |
|    |-- ModalNavigationDrawer (SessionDrawerContent from RFC-005)           |
|    |-- MessageList (LazyColumn)                                            |
|    |     |-- MessageBubble (user / AI with Markdown)                       |
|    |     |-- ToolCallCard (compact / expanded)                             |
|    |     |-- ThinkingBlock (collapsed / expanded)                          |
|    |     |-- ErrorMessage (with Retry button)                              |
|    |     |-- SystemMessage (agent switch indicator)                        |
|    |-- ChatInput (text field + send button)                                |
|    |-- ScrollToBottomFAB                                                   |
|    |-- SnackbarHost (for session undo, errors)                             |
|                                                                            |
|  ChatViewModel                                                             |
|    |-- uiState: StateFlow<ChatUiState>                                     |
|    |-- sendMessage(), stopGeneration(), regenerate(), switchAgent()         |
+--------------------------------------------------------------------------+
|                            Domain Layer                                    |
|  SendMessageUseCase  -> returns Flow<ChatEvent>                            |
|  StopGenerationUseCase                                                     |
|  RegenerateUseCase                                                         |
|  SwitchAgentUseCase                                                        |
|       |                                                                    |
|       v                                                                    |
|  AgentRepository, SessionRepository, MessageRepository,                    |
|  ProviderRepository, ApiKeyStorage, ToolExecutionEngine                    |
+--------------------------------------------------------------------------+
|                             Data Layer                                     |
|  ModelApiAdapter (OpenAI/Anthropic/Gemini) -- SSE streaming                |
|  ToolExecutionEngine -- tool execution                                     |
|  MessageDao, SessionDao -- persistence                                     |
+--------------------------------------------------------------------------+
```

### Core Components

1. **SendMessageUseCase**
   - Responsibility: Orchestrate the full message -> stream -> tool-call-loop -> save cycle
   - Output: `Flow<ChatEvent>` for ViewModel to collect
   - Dependencies: AgentRepository, SessionRepository, MessageRepository, ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory, ToolExecutionEngine

2. **ChatViewModel**
   - Responsibility: Manage UI state, collect ChatEvent flow, handle user actions
   - State: `StateFlow<ChatUiState>` drives the Compose UI
   - Holds the current request `Job` for cancellation (stop generation)

3. **ModelApiAdapter SSE Parsing**
   - Responsibility: Parse provider-specific SSE streams into unified `Flow<StreamEvent>`
   - Each adapter (OpenAI, Anthropic, Gemini) handles its own SSE format

4. **ChatScreen**
   - Responsibility: Top-level composable integrating all chat UI components
   - Integrates: ModalNavigationDrawer (RFC-005), AgentSelectorSheet (RFC-002)

## Data Model

### ChatEvent (Domain Layer)

Events emitted by `SendMessageUseCase` and collected by `ChatViewModel`.

```kotlin
/**
 * Events emitted during the send-message flow.
 * Located in: feature/chat/ChatEvent.kt
 */
sealed class ChatEvent {
    /** Incremental text from the AI response. */
    data class StreamingText(val text: String) : ChatEvent()

    /** Incremental thinking/reasoning text. */
    data class ThinkingText(val text: String) : ChatEvent()

    /** AI requested a tool call; about to execute. */
    data class ToolCallStarted(
        val toolCallId: String,
        val toolName: String
    ) : ChatEvent()

    /** Tool call arguments being streamed (incremental). */
    data class ToolCallArgumentsDelta(
        val toolCallId: String,
        val delta: String
    ) : ChatEvent()

    /** Tool execution completed. */
    data class ToolCallCompleted(
        val toolCallId: String,
        val toolName: String,
        val result: ToolResult
    ) : ChatEvent()

    /** A new tool-call round is starting (tools executed, sending results back to model). */
    data class ToolRoundStarting(val round: Int) : ChatEvent()

    /** The full AI response is complete (no more tool calls). */
    data class ResponseComplete(
        val message: Message,
        val usage: TokenUsage?
    ) : ChatEvent()

    /** Token usage information from the API. */
    data class TokenUsage(
        val inputTokens: Int,
        val outputTokens: Int
    )

    /** An error occurred. */
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
 * UI state for the chat screen.
 * Located in: feature/chat/ChatUiState.kt
 */
data class ChatUiState(
    // Session
    val sessionId: String? = null,          // null = new conversation (not yet persisted)
    val sessionTitle: String = "New Conversation",

    // Agent
    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val currentAgentName: String = "General Assistant",

    // Messages
    val messages: List<ChatMessageItem> = emptyList(),

    // Streaming state
    val isStreaming: Boolean = false,
    val streamingText: String = "",         // Accumulated streaming text (current response)
    val streamingThinkingText: String = "", // Accumulated thinking text
    val activeToolCalls: List<ActiveToolCall> = emptyList(),  // Currently executing tools

    // Input
    val inputText: String = "",
    val canSend: Boolean = true,            // false when streaming or no provider configured

    // Scroll
    val shouldAutoScroll: Boolean = true,

    // Agent selector
    val showAgentSelector: Boolean = false,

    // Error
    val errorMessage: String? = null,

    // Provider status
    val hasConfiguredProvider: Boolean = false
)

/**
 * A single item in the message list. Can be a user message, AI response, tool call,
 * tool result, error, or system message.
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
    val isRetryable: Boolean = false,       // For ERROR type: show Retry button
    val timestamp: Long = 0
)

/**
 * Represents a tool call that is currently in progress.
 */
data class ActiveToolCall(
    val toolCallId: String,
    val toolName: String,
    val arguments: String = "",             // Accumulated argument JSON
    val status: ToolCallStatus = ToolCallStatus.PENDING
)
```

### Message to API Conversion

Messages stored in the DB need to be converted to the provider-specific API format when sending to the model. This conversion is handled inside each `ModelApiAdapter` implementation.

```kotlin
/**
 * Intermediate representation of a message for the API.
 * Adapters convert this to their provider-specific format.
 * Located in: data/remote/adapter/ApiMessage.kt
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
    val arguments: String   // JSON string
)
```

**Conversion from domain `Message` to `ApiMessage`:**

```kotlin
/**
 * Convert a list of domain Messages to ApiMessages for sending to the model.
 * ERROR and SYSTEM messages are excluded (they are UI-only markers).
 * 
 * Tool calls and their results must be grouped correctly:
 * - An AI_RESPONSE with tool calls is followed by TOOL_RESULT messages
 * - These are converted to Assistant(toolCalls) + ToolResult pairs
 *
 * Located in: feature/chat/usecase/MessageToApiMapper.kt
 */
fun List<Message>.toApiMessages(): List<ApiMessage> {
    return this
        .filter { it.type != MessageType.ERROR && it.type != MessageType.SYSTEM }
        .map { message ->
            when (message.type) {
                MessageType.USER -> ApiMessage.User(content = message.content)

                MessageType.AI_RESPONSE -> {
                    // Check if this AI response has associated tool calls
                    // (tool calls are separate TOOL_CALL messages following this one)
                    ApiMessage.Assistant(
                        content = message.content,
                        thinkingContent = message.thinkingContent
                    )
                }

                MessageType.TOOL_CALL -> {
                    // Converted to part of the preceding Assistant message's toolCalls
                    // This is handled by the grouping logic below
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

                else -> null  // ERROR, SYSTEM -- skip
            }
        }
        .filterNotNull()
}
```

**Note**: The actual grouping of AI_RESPONSE + TOOL_CALL messages into a single Assistant message with `toolCalls` is handled by each adapter, since the grouping format differs per provider. The mapper above is a simplified view; the full implementation groups consecutive TOOL_CALL messages after an AI_RESPONSE into that response's `toolCalls` list.

## SendMessageUseCase

This is the core use case that orchestrates the entire chat flow.

```kotlin
/**
 * Orchestrates the full send-message -> stream-response -> tool-call-loop -> save cycle.
 *
 * Located in: feature/chat/usecase/SendMessageUseCase.kt
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
     * Send a user message and get the AI response.
     *
     * @param sessionId The session ID (must already exist in DB)
     * @param userText The user's message text
     * @param agentId The current agent ID
     * @return Flow of ChatEvents for the ViewModel to collect
     */
    fun execute(
        sessionId: String,
        userText: String,
        agentId: String
    ): Flow<ChatEvent> = channelFlow {

        // 1. Resolve agent configuration
        val agent = agentRepository.getAgentById(agentId)
            ?: run {
                send(ChatEvent.Error("Agent not found.", ErrorCode.VALIDATION_ERROR, false))
                return@channelFlow
            }

        // 2. Resolve model and provider
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

        // 3. Save user message to DB
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

        // 4. Update session message stats
        sessionRepository.updateMessageStats(
            id = sessionId,
            count = messageRepository.getMessageCount(sessionId),
            preview = userText.take(100)
        )
        sessionRepository.setActive(sessionId, true)

        // 5. Get agent's tool definitions
        val agentToolDefs = if (agent.toolIds.isNotEmpty()) {
            toolRegistry.getToolsByIds(agent.toolIds)
        } else null

        // 6. Tool call loop
        var round = 0
        try {
            while (round < MAX_TOOL_ROUNDS) {
                // Load all messages for this session (full history)
                val allMessages = messageRepository.getMessagesSnapshot(sessionId)
                val apiMessages = allMessages.toApiMessages()

                // Get the adapter
                val adapter = adapterFactory.getAdapter(provider.type)

                // Accumulated response for this round
                var accumulatedText = ""
                var accumulatedThinking = ""
                val pendingToolCalls = mutableListOf<PendingToolCall>()
                var usage: ChatEvent.TokenUsage? = null

                // Stream the response
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
                            // Tool call fully received; will execute after stream completes
                        }
                        is StreamEvent.Usage -> {
                            usage = ChatEvent.TokenUsage(event.inputTokens, event.outputTokens)
                        }
                        is StreamEvent.Error -> {
                            throw ApiException(event.message, event.code)
                        }
                        is StreamEvent.Done -> {
                            // Stream complete for this round
                        }
                    }
                }

                // Save AI response message
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

                // Check if there are tool calls to execute
                if (pendingToolCalls.isEmpty()) {
                    // No tool calls -- response is complete
                    sessionRepository.updateMessageStats(
                        id = sessionId,
                        count = messageRepository.getMessageCount(sessionId),
                        preview = accumulatedText.take(100)
                    )
                    send(ChatEvent.ResponseComplete(aiMessage, usage))
                    break
                }

                // Save tool call messages
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

                // Execute all tool calls in parallel
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

                // Save tool result messages and emit events
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

                // Prepare for next round
                round++
                if (round < MAX_TOOL_ROUNDS) {
                    send(ChatEvent.ToolRoundStarting(round))
                }
            }

            // If we exhausted all rounds
            if (round >= MAX_TOOL_ROUNDS) {
                send(ChatEvent.Error(
                    "Reached maximum tool call rounds ($MAX_TOOL_ROUNDS). Stopping.",
                    ErrorCode.TOOL_ERROR,
                    false
                ))
            }
        } catch (e: CancellationException) {
            // User stopped generation -- save partial response
            // (partial text is already accumulated in accumulatedText via ChatEvent.StreamingText)
            // The ViewModel handles saving partial text on cancellation
            throw e  // Re-throw to properly cancel the coroutine
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
     * Resolve the model and provider to use for this agent.
     * Agent preferred model/provider -> Global default -> null (error)
     */
    private suspend fun resolveModel(agent: Agent): Pair<AiModel, Provider>? {
        // Try agent's preferred model/provider first
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

        // Fall back to global default
        // getGlobalDefaultModel returns a Flow; we take the current value
        val defaultModel = providerRepository.getGlobalDefaultModel()
            .first()  // Take current value from Flow
            ?: return null

        val provider = providerRepository.getProviderById(defaultModel.providerId)
            ?: return null

        if (!provider.isActive) return null

        return Pair(defaultModel, provider)
    }

    private fun mapApiError(e: ApiException): ErrorCode {
        return when {
            e.code == "401" || e.code == "403" -> ErrorCode.AUTH_ERROR
            e.code == "429" -> ErrorCode.TIMEOUT_ERROR  // Rate limited
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
 * Exception thrown when an API error occurs during streaming.
 */
class ApiException(message: String, val code: String? = null) : Exception(message)
```

### MessageRepository Extension

`SendMessageUseCase` needs a snapshot (non-Flow) method to get all messages at a point in time.

```kotlin
// Addition to MessageRepository interface (core/repository/MessageRepository.kt)
interface MessageRepository {
    // ... existing methods ...

    /**
     * Get a snapshot of all messages for a session (non-reactive).
     * Used by SendMessageUseCase to build the API request.
     */
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>
}
```

### ID Generation in Repository Implementations

**Critical implementation note (discovered in Layer 2 testing):**

When `addMessage()` receives a `Message` with `id = ""` (blank), the repository implementation MUST generate a UUID before persisting. Similarly, `createSession()` must generate a UUID when given a blank ID. Failing to do so causes all records to share the same empty-string primary key and overwrite each other.

```kotlin
// MessageRepositoryImpl.addMessage() — correct pattern
override suspend fun addMessage(message: Message): Message {
    val id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id
    val createdAt = if (message.createdAt == 0L) System.currentTimeMillis() else message.createdAt
    val entity = message.copy(id = id, createdAt = createdAt).toEntity()
    messageDao.insertMessage(entity)
    return message.copy(id = id, createdAt = createdAt)
}

// SessionRepositoryImpl.createSession() — correct pattern
override suspend fun createSession(session: Session): Session {
    val id = if (session.id.isBlank()) UUID.randomUUID().toString() else session.id
    val now = System.currentTimeMillis()
    val createdAt = if (session.createdAt == 0L) now else session.createdAt
    val entity = session.copy(id = id, createdAt = createdAt, updatedAt = now).toEntity()
    sessionDao.insertSession(entity)
    return session.copy(id = id, createdAt = createdAt)
}
```

All callers pass `id = ""` and `createdAt = 0` as a convention; the repository is responsible for filling them in.

## ChatViewModel

```kotlin
/**
 * ViewModel for the chat screen.
 *
 * Located in: feature/chat/ChatViewModel.kt
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

    // Current streaming job -- held for cancellation
    private var streamingJob: Job? = null

    // Track if this is the first message (for title generation)
    private var isFirstMessage = true
    private var firstUserMessageText: String? = null

    /**
     * Initialize with an existing session or start fresh.
     */
    fun initialize(sessionId: String? = null) {
        if (sessionId != null) {
            loadSession(sessionId)
        } else {
            // New conversation -- no session in DB yet (lazy creation)
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

            // Load messages
            messageRepository.getMessagesForSession(sessionId).collect { messages ->
                val items = messages.map { it.toChatMessageItem() }
                _uiState.update { it.copy(messages = items) }
            }

            isFirstMessage = false  // Existing session
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

    // --- User Actions ---

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        if (_uiState.value.isStreaming) return

        viewModelScope.launch {
            // Clear input
            _uiState.update { it.copy(inputText = "") }

            // Lazy session creation
            var sessionId = _uiState.value.sessionId
            if (sessionId == null) {
                val session = createSessionUseCase(
                    agentId = _uiState.value.currentAgentId
                )
                sessionId = session.id
                _uiState.update { it.copy(sessionId = sessionId) }

                // Phase 1 title generation
                val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(text)
                sessionRepository.updateTitle(sessionId, truncatedTitle)
                _uiState.update { it.copy(sessionTitle = truncatedTitle) }

                firstUserMessageText = text
                isFirstMessage = true
            }

            // Add user message to UI immediately
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

            // Start streaming
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
                                // Reset streaming state for next round
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
                    // User stopped generation -- save partial text.
                    // IMPORTANT: wrap in withContext(NonCancellable) so that suspend
                    // calls (savePartialResponse, finishStreaming) are not immediately
                    // re-cancelled by the already-cancelled coroutine context.
                    withContext(NonCancellable) {
                        if (accumulatedText.isNotBlank()) {
                            savePartialResponse(sessionId, accumulatedText, accumulatedThinking)
                        }
                        finishStreaming(sessionId)
                    }
                }
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
        // streamingJob.cancel() throws CancellationException into the streaming coroutine.
        // The catch block MUST use withContext(NonCancellable) { ... } when calling any
        // suspend functions (savePartialResponse, finishStreaming). Without NonCancellable,
        // the already-cancelled context causes those suspend calls to immediately throw
        // CancellationException again, so finishStreaming() never runs, leaving isStreaming=true
        // and the stop button visible forever. (Bug found in Layer 2 testing.)
    }

    fun regenerate() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return

        // Find the last user message and remove everything after it
        val lastUserIndex = messages.indexOfLast { it.type == MessageType.USER }
        if (lastUserIndex < 0) return

        val lastUserText = messages[lastUserIndex].content
        val sessionId = _uiState.value.sessionId ?: return

        viewModelScope.launch {
            // Delete messages after the last user message from DB
            val messagesToRemove = messages.drop(lastUserIndex + 1)
            for (msg in messagesToRemove) {
                messageRepository.deleteMessage(msg.id)
            }

            // Update UI
            _uiState.update {
                it.copy(messages = messages.take(lastUserIndex + 1))
            }

            // Re-send
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
                    // Same event handling as sendMessage()
                    // (extracted to a shared method in actual implementation)
                    handleChatEvent(event, sessionId, { accumulatedText += it; accumulatedText },
                        { accumulatedThinking += it; accumulatedThinking })
                }
            } catch (e: CancellationException) {
                withContext(NonCancellable) {
                    if (accumulatedText.isNotBlank()) {
                        savePartialResponse(sessionId, accumulatedText, accumulatedThinking)
                    }
                    finishStreaming(sessionId)
                }
            }
        }
    }

    fun switchAgent(newAgentId: String) {
        val sessionId = _uiState.value.sessionId ?: return
        if (_uiState.value.isStreaming) return

        viewModelScope.launch {
            val agent = agentRepository.getAgentById(newAgentId) ?: return@launch

            // Update session's current agent
            sessionRepository.updateCurrentAgent(sessionId, newAgentId)

            // Insert system message
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
        // Remove the last ERROR message, then re-send
        val lastError = messages.lastOrNull { it.type == MessageType.ERROR }
        if (lastError != null) {
            viewModelScope.launch {
                messageRepository.deleteMessage(lastError.id)
            }
        }
        regenerate()
    }

    fun copyMessageToClipboard(content: String) {
        // Handled by the Compose layer using ClipboardManager
        // ViewModel just exposes the action; actual clipboard access is in the composable
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    // --- Private helpers ---

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

        // Reload messages from DB to get the final state
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }

        // Title generation (Phase 2) -- only for first message
        if (isFirstMessage && firstUserMessageText != null) {
            isFirstMessage = false
            val aiResponse = messages.lastOrNull { it.type == MessageType.AI_RESPONSE }
            if (aiResponse != null) {
                // Fire and forget -- title generation is non-blocking
                viewModelScope.launch {
                    generateTitleUseCase.generateAiTitle(
                        sessionId = sessionId,
                        firstUserMessage = firstUserMessageText!!,
                        firstAiResponse = aiResponse.content,
                        currentModelId = aiResponse.modelId ?: "",
                        currentProviderId = aiResponse.providerId ?: ""
                    )
                    // Reload session title
                    val session = sessionRepository.getSessionById(sessionId)
                    if (session != null) {
                        _uiState.update { it.copy(sessionTitle = session.title) }
                    }
                }
            }
        }
    }

    private suspend fun handleError(sessionId: String, error: ChatEvent.Error) {
        // Save error as a message in DB
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

        // Reload messages
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

// Extension to convert domain Message to UI ChatMessageItem
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

## Additional Use Cases

### RegenerateUseCase

Note: Regeneration logic is handled directly in `ChatViewModel.regenerate()` since it involves UI state manipulation (removing messages from the list) tightly coupled with the re-send. No separate use case needed.

### SwitchAgentUseCase

Similarly, Agent switching is handled in `ChatViewModel.switchAgent()` since it involves both session update and system message insertion, tightly coupled with UI state. No separate use case needed.

### MessageRepository Extension

```kotlin
// Additions to MessageRepository interface
interface MessageRepository {
    // ... existing methods ...

    /**
     * Get a non-reactive snapshot of all messages for a session.
     */
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>

    /**
     * Delete a single message by ID.
     * Used by regenerate (remove last AI response) and retry (remove error message).
     */
    suspend fun deleteMessage(id: String)
}
```

## SSE Streaming Implementation

Each provider adapter must implement `sendMessageStream()` to parse its specific SSE format into `Flow<StreamEvent>`.

### OpenAI SSE Format

```
data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"role":"assistant","content":"Hello"},"index":0}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"tool_calls":[{"index":0,"id":"call_xxx","function":{"name":"read_file","arguments":""}}]},"index":0}]}

data: {"id":"chatcmpl-xxx","object":"chat.completion.chunk","choices":[{"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{\"path\":"}}]},"index":0}]}

data: [DONE]
```

### Anthropic SSE Format

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

### Gemini SSE Format

```
data: {"candidates":[{"content":{"parts":[{"text":"Hello"}],"role":"model"},"index":0}]}

data: {"candidates":[{"content":{"parts":[{"functionCall":{"name":"read_file","args":{"path":"/test.txt"}}}],"role":"model"},"index":0}]}

data: {"candidates":[{"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":10,"candidatesTokenCount":42}}
```

### SSE Parser Utility

```kotlin
/**
 * Generic SSE line parser. Reads from an OkHttp ResponseBody and emits SSE events.
 *
 * Located in: data/remote/sse/SseParser.kt
 *
 * IMPORTANT IMPLEMENTATION NOTES (from Layer 2 bug fixes):
 *
 * 1. Use `channelFlow` + `withContext(Dispatchers.IO)` + `byteStream().bufferedReader()`.
 *    DO NOT use `callbackFlow` + `source().buffer()` — on a non-IO dispatcher,
 *    `source.exhausted()` returns true immediately (reads 0 lines).
 *
 * 2. Do NOT call `awaitClose()` after `withContext`. The `channelFlow` completes
 *    automatically when all producers inside it finish. Adding `awaitClose()` keeps
 *    the flow open indefinitely after the stream ends, causing `isStreaming` to stay
 *    `true` forever.
 *
 * 3. Adapters MUST call `body.asSseFlow().collect { ... }` directly inside `flow { }`.
 *    DO NOT wrap the collect in `withContext(Dispatchers.IO) { ... }` — emitting from
 *    a non-flow dispatcher inside a `flow { }` builder violates the flow invariant and
 *    causes events to be silently dropped.
 */
fun ResponseBody.asSseFlow(): Flow<SseEvent> = channelFlow {
    withContext(Dispatchers.IO) {
        val reader = byteStream().bufferedReader(Charsets.UTF_8)
        try {
            var eventType: String? = null
            val dataBuilder = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!
                when {
                    l.startsWith("event:") -> {
                        eventType = l.removePrefix("event:").trim()
                    }
                    l.startsWith("data:") -> {
                        dataBuilder.append(l.removePrefix("data:").trim())
                    }
                    l.isEmpty() -> {
                        if (dataBuilder.isNotEmpty()) {
                            send(SseEvent(type = eventType, data = dataBuilder.toString()))
                            eventType = null
                            dataBuilder.clear()
                        }
                    }
                }
            }
            // Flush remaining data if stream ends without trailing newline
            if (dataBuilder.isNotEmpty()) {
                send(SseEvent(type = eventType, data = dataBuilder.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        } finally {
            reader.close()
        }
    }
    // DO NOT call awaitClose() here — it would keep the channelFlow open forever
}

data class SseEvent(
    val type: String?,     // Event type (e.g., "message_start" for Anthropic)
    val data: String       // Event data (JSON string)
)
```

Each adapter then collects `SseEvent`s and maps them to `StreamEvent`s based on the provider-specific JSON format.

**Adapter collect pattern** — use this inside each adapter's `flow { }` builder:

```kotlin
// CORRECT: collect directly; asSseFlow() handles IO internally
body.asSseFlow().collect { sseEvent ->
    // process event and emit StreamEvent
}

// WRONG: do not wrap in withContext — emit() from IO dispatcher violates flow invariant
withContext(Dispatchers.IO) {
    body.asSseFlow().collect { sseEvent ->
        emit(StreamEvent.TextDelta(...))  // THIS BREAKS: emit called from wrong context
    }
}
```

## UI Layer

### ChatScreen

```kotlin
/**
 * Top-level chat screen composable.
 * Located in: feature/chat/ChatScreen.kt
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

    // Auto-scroll when new content arrives
    LaunchedEffect(uiState.messages.size, uiState.streamingText) {
        if (uiState.shouldAutoScroll && uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(listState.layoutInfo.totalItemsCount - 1)
        }
    }

    // Detect manual scroll to disable auto-scroll
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
                    // Empty state
                    EmptyChatState()
                } else {
                    // Message list
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

                // Scroll to bottom FAB
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

    // Agent selector bottom sheet
    if (uiState.showAgentSelector) {
        AgentSelectorSheet(
            onAgentSelected = { agentId -> viewModel.switchAgent(agentId) },
            onDismiss = { viewModel.dismissAgentSelector() }
        )
    }

    // No provider configured warning
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

    // Session undo snackbar
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
            // Agent selector in center
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
                shape = MaterialTheme.shapes.extraLarge,  // Pill shape
                maxLines = 6,
                enabled = !isStreaming
            )

            Spacer(modifier = Modifier.width(8.dp))

            if (isStreaming) {
                // Stop button
                IconButton(
                    onClick = onStop,
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Icon(Icons.Default.Stop, contentDescription = "Stop")
                }
            } else {
                // Send button
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

        // Streaming AI response (in progress)
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

        // Active tool calls (in progress)
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

### Message Bubble Components

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
        // Thinking block (collapsed by default)
        if (!thinkingContent.isNullOrBlank()) {
            ThinkingBlock(content = thinkingContent)
            Spacer(modifier = Modifier.height(4.dp))
        }

        // AI response with Markdown rendering
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
                    // Markdown rendering via third-party library
                    MarkdownText(
                        markdown = content,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (isStreaming) {
                    // Streaming cursor
                    StreamingCursor()
                }
            }
        }

        // Action buttons row (only for completed, last AI message)
        if (!isStreaming && isLastAiMessage && content.isNotEmpty()) {
            Row(
                modifier = Modifier.padding(start = 8.dp, top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Copy button
                IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.ContentCopy,
                        contentDescription = "Copy",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Regenerate button
                IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Default.Refresh,
                        contentDescription = "Regenerate",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                // Model badge
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

### ToolCallCard and ToolResultCard

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
            // Status icon
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

### ErrorMessageCard and SystemMessageCard

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

## Koin Dependency Injection

```kotlin
// Additions to FeatureModule.kt
val featureModule = module {
    // ... existing entries ...

    // Chat
    factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    // AgentRepository, SessionRepository, MessageRepository, ProviderRepository,
    // ApiKeyStorage, ModelApiAdapterFactory, ToolExecutionEngine, ToolRegistry
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    // SendMessageUseCase, SessionRepository, MessageRepository, AgentRepository,
    // CreateSessionUseCase, GenerateTitleUseCase
}
```

## Data Flow Examples

### Flow 1: Normal Message with Streaming Response (No Tool Calls)

```
1. User types "What is the capital of France?" and taps send
2. ChatViewModel.sendMessage()
   -> Lazy session creation: CreateSessionUseCase("agent-general-assistant")
   -> Session created with ID "session-123"
   -> Phase 1 title: "What is the capital of France?"
   -> UI: user message appears, input cleared, isStreaming = true
3. SendMessageUseCase.execute("session-123", "What is the capital of...", "agent-general-assistant")
   -> Resolve Agent -> General Assistant
   -> Resolve Model -> gpt-4o via provider-openai
   -> Save user message to DB
   -> Set session active
   -> Call OpenAiAdapter.sendMessageStream(...)
4. SSE events arrive:
   -> TextDelta("The") -> ChatEvent.StreamingText("The")
   -> TextDelta(" capital") -> ChatEvent.StreamingText(" capital")
   -> TextDelta(" of France is Paris.") -> ChatEvent.StreamingText(" of France is Paris.")
   -> Usage(input=15, output=8)
   -> Done
5. ChatViewModel collects events:
   -> streamingText accumulates: "The" -> "The capital" -> "The capital of France is Paris."
   -> UI updates in real-time, user sees text appear character by character
6. Stream ends:
   -> AI message saved to DB
   -> Session message stats updated
   -> ChatEvent.ResponseComplete emitted
   -> finishStreaming: isStreaming = false, reload messages
7. Phase 2 title generation fires async:
   -> gpt-4o-mini generates title "Capital of France"
   -> Session title updated
```

### Flow 2: Message with Tool Call Loop

```
1. User sends "What time is it in Tokyo right now?"
2. SendMessageUseCase begins...
3. Round 0: Model streams response
   -> TextDelta("Let me check the current time.")
   -> ToolCallStart("call_1", "get_current_time")
   -> ToolCallDelta("call_1", '{"timezone":"Asia/Tokyo"}')
   -> ToolCallEnd("call_1")
   -> Done
4. AI response saved to DB (partial text + tool call indicator)
5. Tool call saved to DB as TOOL_CALL message
6. ToolExecutionEngine.executeTool("get_current_time", {timezone: "Asia/Tokyo"}, [...])
   -> Returns: ToolResult(SUCCESS, "2026-02-27T22:30:00+09:00")
7. Tool result saved to DB as TOOL_RESULT message
8. ChatEvent.ToolCallCompleted emitted
9. Round 1: Model called again with full history (including tool result)
   -> TextDelta("The current time in Tokyo is 10:30 PM on February 27, 2026.")
   -> Done (no more tool calls)
10. Final AI response saved
11. ChatEvent.ResponseComplete emitted
```

### Flow 3: Stop Generation

```
1. User sends a long query, AI starts streaming a long response
2. User taps "Stop" button
3. ChatViewModel.stopGeneration() -> streamingJob.cancel()
4. CancellationException caught in ViewModel
5. Accumulated text ("The answer involves several factors...") saved to DB
6. finishStreaming() called
7. User sees partial AI response in the chat
8. User can send a new message
```

### Flow 4: Regenerate

```
1. User is unhappy with AI's last response
2. User taps Regenerate button on the last AI message
3. ChatViewModel.regenerate()
   -> Finds last user message: "Explain quantum computing"
   -> Removes all messages after it (the AI response + any tool calls/results)
   -> Deletes those messages from DB
   -> Calls streamWithExistingMessage()
4. New streaming request sent with same user message
5. New AI response streams in
6. Old response is gone; new one takes its place
```

### Flow 5: Agent Switch

```
1. User is chatting with General Assistant
2. User taps agent name in top bar -> AgentSelectorSheet opens
3. User selects "Code Helper"
4. ChatViewModel.switchAgent("code-helper-id")
   -> SessionRepository.updateCurrentAgent("session-123", "code-helper-id")
   -> MessageRepository.addMessage(SYSTEM, "Switched to Code Helper")
   -> UI updates: agent name in top bar, system message in chat
5. User sends next message -> uses Code Helper's systemPrompt, tools, and model
6. History is preserved: Code Helper sees all previous messages
```

## Error Handling

### Error Scenarios and User-Facing Messages

| Scenario | ErrorCode | User Message | UI Behavior |
|----------|-----------|--------------|-------------|
| No provider configured | VALIDATION_ERROR | "No provider configured. Set up in Settings." | Snackbar with Settings link |
| API key missing | AUTH_ERROR | "API key not configured for [Provider]." | ERROR message, not retryable |
| API key invalid (401) | AUTH_ERROR | "API key is invalid. Please check your settings." | ERROR message, not retryable |
| Rate limited (429) | TIMEOUT_ERROR | "Rate limited. Please wait and try again." | ERROR message, retryable |
| Server error (5xx) | PROVIDER_ERROR | "Server error. Please try again." | ERROR message, retryable |
| Network error | NETWORK_ERROR | "Network error. Check your connection." | ERROR message, retryable |
| Tool execution failed | TOOL_ERROR | (tool result shows error) | ToolResultCard with error status; model decides how to proceed |
| Max tool rounds reached | TOOL_ERROR | "Reached maximum tool call rounds (100)." | ERROR message, not retryable |
| Context window exceeded | PROVIDER_ERROR | "Conversation too long. Start a new conversation." | ERROR message, not retryable |
| Agent not found | VALIDATION_ERROR | "Agent not found." | ERROR message, not retryable |

## Implementation Steps

### Phase 1: Domain Layer
1. [ ] Create `ChatEvent` sealed class
2. [ ] Create `ApiMessage` sealed class and `ApiToolCall` data class
3. [ ] Create `MessageToApiMapper` (Message -> ApiMessage conversion)
4. [ ] Create `ApiException` class
5. [ ] Add `getMessagesSnapshot()` and `deleteMessage()` to `MessageRepository` interface
6. [ ] Implement `getMessagesSnapshot()` and `deleteMessage()` in `MessageRepositoryImpl`

### Phase 2: SSE Streaming
7. [ ] Implement `SseParser` utility (ResponseBody -> Flow<SseEvent>)
8. [ ] Implement OpenAI SSE -> StreamEvent mapping in `OpenAiAdapter.sendMessageStream()`
9. [ ] Implement Anthropic SSE -> StreamEvent mapping in `AnthropicAdapter.sendMessageStream()`
10. [ ] Implement Gemini SSE -> StreamEvent mapping in `GeminiAdapter.sendMessageStream()`
11. [ ] Implement message-to-API-format conversion in each adapter

### Phase 3: SendMessageUseCase
12. [ ] Implement `SendMessageUseCase` with full tool call loop
13. [ ] Implement model resolution (agent preferred -> global default)
14. [ ] Implement parallel tool execution via `coroutineScope + async`
15. [ ] Test: normal message flow (no tools)
16. [ ] Test: tool call loop (1 round, 2 rounds, parallel tools)
17. [ ] Test: max tool rounds reached

### Phase 4: ChatViewModel
18. [ ] Implement `ChatViewModel` with all state management
19. [ ] Implement `sendMessage()` with lazy session creation
20. [ ] Implement `stopGeneration()` with partial text save
21. [ ] Implement `regenerate()` (delete + re-send)
22. [ ] Implement `switchAgent()` with system message
23. [ ] Implement title generation integration (Phase 1 + Phase 2)
24. [ ] Implement `retryLastMessage()`

### Phase 5: UI Components
25. [ ] Implement `ChatScreen` layout (Scaffold + Drawer + TopBar + Input)
26. [ ] Implement `ChatTopBar` with agent selector trigger
27. [ ] Implement `ChatInput` (pill-shaped text field + send/stop button)
28. [ ] Implement `MessageList` (LazyColumn with all message types)
29. [ ] Implement `UserMessageBubble`
30. [ ] Implement `AiMessageBubble` with Markdown rendering (third-party library integration)
31. [ ] Implement `ThinkingBlock` (collapsed/expandable)
32. [ ] Implement `ToolCallCard` and `ToolResultCard`
33. [ ] Implement `ErrorMessageCard` with Retry button
34. [ ] Implement `SystemMessageCard`
35. [ ] Implement `EmptyChatState`
36. [ ] Implement `StreamingCursor` animation
37. [ ] Implement auto-scroll behavior (follow at bottom, stop on manual scroll, FAB)
38. [ ] Implement long-press copy on message bubbles

### Phase 6: Integration
39. [ ] Integrate AgentSelectorSheet (RFC-002) with ChatScreen
40. [ ] Integrate SessionDrawerContent (RFC-005) with ChatScreen
41. [ ] Update Koin modules (SendMessageUseCase, ChatViewModel)
42. [ ] Update navigation to use ChatScreen as home
43. [ ] End-to-end test: full conversation with streaming + tool calls
44. [ ] End-to-end test: stop generation + partial save
45. [ ] End-to-end test: regenerate
46. [ ] End-to-end test: agent switch mid-conversation
47. [ ] End-to-end test: error + retry
48. [ ] Performance test: long conversation scroll

## Testing Strategy

### Unit Tests
- `SendMessageUseCase`: Normal flow, tool call loop, max rounds, cancellation, various errors
- `MessageToApiMapper`: All message types, ERROR/SYSTEM exclusion, tool call grouping
- `ChatViewModel`: State transitions for all user actions, streaming text accumulation, stop, regenerate, agent switch
- `SseParser`: Well-formed SSE, malformed lines, empty events
- Each adapter's SSE -> StreamEvent mapping: All event types, edge cases

### Integration Tests (Instrumented)
- Full message send and receive with mocked API
- Tool call execution with real ToolExecutionEngine
- Session creation flow (lazy creation + title generation)
- Message persistence: send -> kill app -> reopen -> messages intact
- Regenerate: messages correctly removed and re-created

### UI Tests
- Send button enabled/disabled states
- Stop button appears during streaming
- Auto-scroll follows new content
- Manual scroll disables auto-scroll + FAB appears
- Long-press copy works
- Regenerate button visible on last AI message
- Error message with Retry button
- Agent switch system message appears
- Empty state when no messages
- Thinking block collapse/expand

### Edge Cases
- Send message with no provider configured
- Send message with invalid API key
- Very long message (10,000+ characters)
- Rapid send-send-send (debounce)
- Stop generation immediately after sending (before any response)
- Stop generation during tool execution
- Regenerate while streaming (should be disabled)
- Switch agent while streaming (should be disabled)
- Tool returns very large output (100KB+ truncated by tool)
- Network disconnect during streaming
- App killed during streaming (partial text should not be saved in this case)
- 100 tool call rounds reached
- Tool call with malformed JSON arguments
- Model returns empty response

### Layer 2 Visual Verification Flows

Each flow is independent. Run them in any order. Preconditions must be satisfied before starting a flow.

---

**Flow 1-1: Send message — streaming response appears**

Preconditions: App installed fresh. At least one provider configured with a valid API key and a default model set.

1. Launch the app. Verify the chat screen opens (TopAppBar visible, input field at bottom).
2. Tap the input field and type: `Hello, who are you?`
3. Tap the Send button (paper plane icon).
4. Screenshot: capture the chat screen within 2 seconds of tapping Send. Verify: user message bubble appears right-aligned, streaming AI bubble appears below it with a blinking cursor, Stop button (square icon) is visible in the input area.
5. Wait for streaming to complete (Stop button disappears, Send button reappears).
6. Screenshot: capture the final state. Verify: AI response bubble shows non-empty text, action row (copy icon, regenerate icon, model badge) is visible below the AI bubble.

---

**Flow 1-2: Streaming completes — action row and model badge appear**

Preconditions: A completed AI message already exists in the current session (can reuse session from Flow 1-1, or start fresh and wait for any message to complete).

1. Open the app to a session that has at least one completed AI message.
2. Screenshot: capture the last AI message bubble. Verify: below the bubble there is a row containing at minimum a copy icon and a regenerate icon. A model badge (text showing model name) is also visible to the right.
3. Verify the Stop button is NOT visible — Send button is visible instead.

---

**Flow 1-3: Stop generation — button reverts, partial text preserved**

Preconditions: Valid API key configured. A model that produces long responses (e.g., ask it to write a detailed essay).

1. Open the app to a fresh chat (no messages).
2. Type: `Write a 500-word essay about the history of the internet.` and tap Send.
3. Immediately after the AI begins streaming (streaming bubble appears), tap the Stop button.
4. Screenshot immediately after tapping Stop. Verify: Stop button changes back to Send button within 1 second.
5. Wait 1 second. Screenshot again. Verify: the partial AI response text (whatever was streamed before Stop) is visible as a completed message bubble, and the input field is enabled.

---

**Flow 1-4: Regenerate response**

Preconditions: A session with at least one completed AI response. No streaming in progress.

1. Open the app to a session with a completed AI message.
2. Locate the last AI message bubble. Verify the regenerate icon (circular arrows) is visible in the action row.
3. Tap the regenerate icon.
4. Screenshot within 2 seconds. Verify: the previous AI response is removed, a streaming bubble with blinking cursor appears in its place, the Stop button is visible.
5. Wait for streaming to complete.
6. Screenshot. Verify: a new AI response bubble is shown, the action row (copy, regenerate, model badge) is visible again.

---

**Flow 1-5: Keyboard appears — TopAppBar stays visible**

Preconditions: Any session open (empty or with messages).

1. Open the app to the chat screen. Verify the TopAppBar (hamburger menu, agent name, settings icon) is fully visible at the top.
2. Tap the input text field to bring up the software keyboard.
3. Screenshot with keyboard open. Verify: the TopAppBar is still fully visible at the top of the screen — it must not be pushed off-screen or obscured by the keyboard.
4. Verify the input field and Send button are visible above the keyboard.

---

**Flow 1-6: Long-press copy on user message**

Preconditions: A session with at least one user message.

1. Open the app to a session containing a user message bubble.
2. Long-press the user message bubble.
3. Screenshot immediately after long-press. Verify: a system copy action is triggered (either a toast "Copied", a snackbar, or the OS clipboard popup appears — exact appearance depends on Android version).
4. Open any other app with a text field (e.g., the address bar of a browser or a notes app). Paste. Verify the pasted text matches the original message content.

---

**Flow 1-7: Tool call loop — ToolCallCard and ToolResultCard visible**

Preconditions: An agent with the `get_current_time` tool enabled is configured as default (or selected). Valid API key set.

1. Open the app to a fresh chat. Verify the agent name in the TopAppBar includes a tool-enabled agent.
2. Type: `What time is it right now?` and tap Send.
3. Screenshot within 3 seconds. Verify: below the streaming AI bubble, a ToolCallCard appears showing the tool name (`get_current_time`) and a spinning progress indicator.
4. Wait for tool execution to complete.
5. Screenshot. Verify: the ToolCallCard now shows a checkmark icon (SUCCESS), and a ToolResultCard appears below it showing the tool result output (a timestamp string).
6. Wait for the final AI response to stream and complete.
7. Screenshot. Verify: the final AI message mentions the current time, and the action row is visible.

---

**Flow 1-8: Error message — error card and Retry button visible**

Preconditions: A provider is configured with an INVALID API key (e.g., set the key to `invalid-key-123`).

1. Open the app to a fresh chat session.
2. Type: `Hello` and tap Send.
3. Wait up to 10 seconds for the error response.
4. Screenshot. Verify: an error card (red/error-colored surface) appears in the message list. The error text mentions authentication or API key. A "Retry" button is visible inside the error card.
5. Tap the Retry button.
6. Screenshot within 2 seconds. Verify: the error card disappears and a new streaming attempt begins (Stop button visible) — OR the same error card reappears if the key is still invalid. Either outcome is acceptable as long as Retry triggers a new attempt.

---

**Flow 1-9: Thinking block — collapse and expand**

Preconditions: An Anthropic provider configured with a model that supports extended thinking (e.g., `claude-opus-4-5-20251101`). Valid API key. Extended thinking enabled in agent or model settings.

1. Open the app to a fresh chat.
2. Type: `Solve this step by step: What is 17 × 23?` and tap Send.
3. Wait for the response to complete.
4. Screenshot. Verify: above the AI response bubble, a "Thinking..." collapsed block is visible (shows a chevron icon and the label "Thinking..."). The block is collapsed by default — the thinking content is not shown.
5. Tap the "Thinking..." block header.
6. Screenshot immediately after tapping. Verify: the block expands, showing the model's internal reasoning text in a smaller font below the "Thinking..." label.
7. Tap the block header again. Verify the block collapses back.

---

## Security Considerations

1. **API keys**: Only used inside adapters for HTTP requests. Never logged, never stored in messages.
2. **User messages**: Stored locally in Room. Sent only to the user's configured provider endpoint.
3. **Tool results**: May contain local file contents or HTTP response data. Stored in DB. Sent back to the model as context.
4. **Clipboard**: Message copy uses system clipboard. User is aware they are copying.
5. **System prompt injection**: Agent system prompts are controlled by the user (they create agents). No risk of external injection.

## Dependencies

### Depends On
- **RFC-000 (Overall Architecture)**: Project structure, core models, database
- **RFC-002 (Agent Management)**: AgentRepository, AgentSelectorSheet, AgentConstants
- **RFC-003 (Provider Management)**: ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory, ModelApiAdapter
- **RFC-004 (Tool System)**: ToolExecutionEngine, ToolRegistry, ToolDefinition, ToolResult
- **RFC-005 (Session Management)**: SessionRepository, CreateSessionUseCase, GenerateTitleUseCase, SessionDrawerContent

### Depended On By
- None (this is the top-level feature integrating all others)

## Third-Party Libraries

| Library | Purpose | Notes |
|---------|---------|-------|
| compose-markdown (Mikepenz) | Markdown rendering in AI messages | Most active Compose Markdown library. Supports code blocks, tables, links. Can be replaced if a better option is found at implementation time. |

## Open Questions

- [ ] Exact compose-markdown library version and API should be verified at implementation time
- [ ] Should we add syntax highlighting for code blocks in V1? (compose-markdown supports it via highlight.js integration, but adds complexity)
- [ ] Auto compaction of conversation history when context window is exceeded (Future improvement -- V1 sends full history and shows error if too long)

## Future Improvements

- [ ] **Auto compaction**: Automatically summarize/truncate old messages when approaching context window limits
- [ ] **Message editing**: Edit a previously sent user message and re-generate from that point
- [ ] **Message branching**: Keep multiple response versions, allow user to navigate between them
- [ ] **Voice input**: Speech-to-text for message input
- [ ] **Image input**: Multimodal support for vision-capable models
- [ ] **Code block copy**: One-tap copy button on code blocks
- [ ] **Syntax highlighting**: Color-coded code blocks by language
- [ ] **Message search**: Search within conversation history
- [ ] **Message reactions**: Thumbs up/down on AI responses
- [ ] **Export conversation**: Save conversation as Markdown/PDF

## References

- [FEAT-001 PRD](../../prd/features/FEAT-001-chat.md) -- Functional requirements
- [UI Design Spec](../../design/ui-design-spec.md) -- Chat screen layout
- [RFC-000 Overall Architecture](../architecture/RFC-000-overall-architecture.md) -- Core models, project structure
- [RFC-002 Agent Management](RFC-002-agent-management.md) -- AgentSelectorSheet, agent resolution
- [RFC-003 Provider Management](RFC-003-provider-management.md) -- ModelApiAdapter, provider adapters
- [RFC-004 Tool System](RFC-004-tool-system.md) -- ToolExecutionEngine, built-in tools
- [RFC-005 Session Management](RFC-005-session-management.md) -- Lazy session creation, title generation, drawer

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
