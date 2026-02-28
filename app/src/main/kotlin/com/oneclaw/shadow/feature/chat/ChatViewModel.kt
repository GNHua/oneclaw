package com.oneclaw.shadow.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val generateTitleUseCase: GenerateTitleUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private var isFirstMessage = true
    private var firstUserMessageText: String? = null

    init {
        initialize(null)
        checkProviderStatus()
    }

    fun initialize(sessionId: String?) {
        if (sessionId != null) {
            loadSession(sessionId)
        } else {
            isFirstMessage = true
            firstUserMessageText = null
            _uiState.update {
                it.copy(
                    sessionId = null,
                    sessionTitle = "New Conversation",
                    currentAgentId = AgentConstants.GENERAL_ASSISTANT_ID,
                    currentAgentName = "General Assistant",
                    messages = emptyList(),
                    isStreaming = false,
                    streamingText = "",
                    streamingThinkingText = "",
                    activeToolCalls = emptyList(),
                    inputText = "",
                    canSend = true
                )
            }
        }
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
                    currentAgentName = agent?.name ?: "General Assistant"
                )
            }
            messageRepository.getMessagesForSession(sessionId).collect { messages ->
                _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }
            }
            isFirstMessage = false
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

    fun updateInputText(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isStreaming) return

        viewModelScope.launch {
            _uiState.update { it.copy(inputText = "") }

            // Lazy session creation
            var sessionId = _uiState.value.sessionId
            if (sessionId == null) {
                val session = createSessionUseCase(agentId = _uiState.value.currentAgentId)
                sessionId = session.id
                _uiState.update { it.copy(sessionId = sessionId) }

                // Phase 1 title: truncated user text
                val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(text)
                sessionRepository.updateTitle(sessionId, truncatedTitle)
                _uiState.update { it.copy(sessionTitle = truncatedTitle) }

                firstUserMessageText = text
                isFirstMessage = true
            }

            // Add user message to UI immediately
            val tempId = java.util.UUID.randomUUID().toString()
            val userItem = ChatMessageItem(
                id = tempId, type = MessageType.USER, content = text,
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

            val finalSessionId = sessionId
            streamingJob = viewModelScope.launch {
                var accumulatedText = ""
                var accumulatedThinking = ""

                try {
                    sendMessageUseCase.execute(
                        sessionId = finalSessionId,
                        userText = text,
                        agentId = _uiState.value.currentAgentId
                    ).collect { event ->
                        handleChatEvent(event, finalSessionId, accumulatedText, accumulatedThinking) { newText, newThinking ->
                            accumulatedText = newText
                            accumulatedThinking = newThinking
                        }
                    }
                } catch (e: CancellationException) {
                    withContext(NonCancellable) {
                        if (accumulatedText.isNotBlank()) {
                            savePartialResponse(finalSessionId, accumulatedText, accumulatedThinking)
                        }
                        finishStreaming(finalSessionId)
                    }
                }
            }
        }
    }

    private suspend fun handleChatEvent(
        event: ChatEvent,
        sessionId: String,
        currentText: String,
        currentThinking: String,
        update: (String, String) -> Unit
    ) {
        when (event) {
            is ChatEvent.StreamingText -> {
                val newText = currentText + event.text
                update(newText, currentThinking)
                _uiState.update { it.copy(streamingText = newText) }
            }
            is ChatEvent.ThinkingText -> {
                val newThinking = currentThinking + event.text
                update(currentText, newThinking)
                _uiState.update { it.copy(streamingThinkingText = newThinking) }
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
                        if (tc.toolCallId == event.toolCallId) tc.copy(arguments = tc.arguments + event.delta)
                        else tc
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
                update("", "")
                _uiState.update {
                    it.copy(streamingText = "", streamingThinkingText = "", activeToolCalls = emptyList())
                }
                // Reload from DB so tool call/result messages appear before next round starts
                val messages = messageRepository.getMessagesSnapshot(sessionId)
                _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }
            }
            is ChatEvent.ResponseComplete -> {
                finishStreaming(sessionId)
            }
            is ChatEvent.CompactStarted -> {
                _uiState.update { it.copy(isCompacting = true) }
            }
            is ChatEvent.CompactCompleted -> {
                _uiState.update { it.copy(isCompacting = false) }
                // No Snackbar needed on success; fallback (didCompact=false) is silent
            }
            is ChatEvent.Error -> {
                handleError(sessionId, event)
            }
        }
    }

    fun stopGeneration() {
        streamingJob?.cancel()
    }

    fun regenerate() {
        val messages = _uiState.value.messages
        if (messages.isEmpty()) return
        val lastUserIndex = messages.indexOfLast { it.type == MessageType.USER }
        if (lastUserIndex < 0) return
        val lastUserText = messages[lastUserIndex].content
        val sessionId = _uiState.value.sessionId ?: return

        viewModelScope.launch {
            val messagesToRemove = messages.drop(lastUserIndex + 1)
            for (msg in messagesToRemove) {
                messageRepository.deleteMessage(msg.id)
            }
            _uiState.update { it.copy(messages = messages.take(lastUserIndex + 1)) }
            streamWithExistingMessage(sessionId, lastUserText)
        }
    }

    private fun streamWithExistingMessage(sessionId: String, userText: String) {
        _uiState.update {
            it.copy(
                isStreaming = true, streamingText = "", streamingThinkingText = "",
                activeToolCalls = emptyList(), canSend = false
            )
        }
        streamingJob = viewModelScope.launch {
            var accumulatedText = ""
            var accumulatedThinking = ""
            try {
                sendMessageUseCase.execute(
                    sessionId = sessionId, userText = userText,
                    agentId = _uiState.value.currentAgentId
                ).collect { event ->
                    handleChatEvent(event, sessionId, accumulatedText, accumulatedThinking) { newText, newThinking ->
                        accumulatedText = newText
                        accumulatedThinking = newThinking
                    }
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
        if (_uiState.value.isStreaming) return
        val sessionId = _uiState.value.sessionId
        viewModelScope.launch {
            val agent = agentRepository.getAgentById(newAgentId) ?: return@launch
            if (sessionId != null) {
                sessionRepository.updateCurrentAgent(sessionId, newAgentId)
                messageRepository.addMessage(Message(
                    id = "", sessionId = sessionId, type = MessageType.SYSTEM,
                    content = "Switched to ${agent.name}",
                    thinkingContent = null, toolCallId = null, toolName = null,
                    toolInput = null, toolOutput = null, toolStatus = null, toolDurationMs = null,
                    tokenCountInput = null, tokenCountOutput = null,
                    modelId = null, providerId = null, createdAt = 0
                ))
            }
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
        val lastError = messages.lastOrNull { it.type == MessageType.ERROR }
        if (lastError != null) {
            viewModelScope.launch {
                messageRepository.deleteMessage(lastError.id)
            }
        }
        regenerate()
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    private suspend fun finishStreaming(sessionId: String) {
        _uiState.update {
            it.copy(
                isStreaming = false, streamingText = "", streamingThinkingText = "",
                activeToolCalls = emptyList(), canSend = true
            )
        }
        // Reload messages from DB
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }

        // Phase 2 title generation: only for first message
        if (isFirstMessage && firstUserMessageText != null) {
            isFirstMessage = false
            val aiResponse = messages.lastOrNull { it.type == MessageType.AI_RESPONSE }
            if (aiResponse != null) {
                viewModelScope.launch {
                    generateTitleUseCase.generateAiTitle(
                        sessionId = sessionId,
                        firstUserMessage = firstUserMessageText!!,
                        firstAiResponse = aiResponse.content,
                        currentModelId = aiResponse.modelId ?: "",
                        currentProviderId = aiResponse.providerId ?: ""
                    )
                    val session = sessionRepository.getSessionById(sessionId)
                    if (session != null) {
                        _uiState.update { it.copy(sessionTitle = session.title) }
                    }
                }
            }
        }
    }

    private suspend fun handleError(sessionId: String, error: ChatEvent.Error) {
        messageRepository.addMessage(Message(
            id = "", sessionId = sessionId, type = MessageType.ERROR,
            content = error.message, thinkingContent = null,
            toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
            toolStatus = null, toolDurationMs = null, tokenCountInput = null,
            tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
        ))
        _uiState.update {
            it.copy(
                isStreaming = false, streamingText = "", streamingThinkingText = "",
                activeToolCalls = emptyList(), canSend = true
            )
        }
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        _uiState.update { it.copy(messages = messages.map { m -> m.toChatMessageItem() }) }
    }

    private suspend fun savePartialResponse(sessionId: String, text: String, thinking: String) {
        messageRepository.addMessage(Message(
            id = "", sessionId = sessionId, type = MessageType.AI_RESPONSE,
            content = text, thinkingContent = thinking.ifEmpty { null },
            toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
            toolStatus = null, toolDurationMs = null, tokenCountInput = null,
            tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
        ))
    }
}

fun Message.toChatMessageItem(): ChatMessageItem = ChatMessageItem(
    id = id, type = type, content = content, thinkingContent = thinkingContent,
    toolCallId = toolCallId, toolName = toolName, toolInput = toolInput, toolOutput = toolOutput,
    toolStatus = toolStatus, toolDurationMs = toolDurationMs, modelId = modelId,
    isRetryable = type == MessageType.ERROR, timestamp = createdAt
)
