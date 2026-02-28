package com.oneclaw.shadow.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import com.oneclaw.shadow.tool.skill.SkillRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
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
    private val generateTitleUseCase: GenerateTitleUseCase,
    private val appLifecycleObserver: AppLifecycleObserver,
    private val notificationHelper: NotificationHelper,
    private val skillRegistry: SkillRegistry? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private var isFirstMessage = true
    private var firstUserMessageText: String? = null

    private val pendingMessages = Channel<String>(Channel.UNLIMITED)

    init {
        initialize(null)
        checkProviderStatus()
        loadSkillsIntoState()
    }

    private fun loadSkillsIntoState() {
        if (skillRegistry == null) return
        val allSkills = skillRegistry.getAllSkills()
        _uiState.update { it.copy(allSkills = allSkills) }
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
                    inputText = ""
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
        // RFC-014: Detect slash command prefix for skill autocomplete
        updateSlashCommandState(text)
    }

    private fun updateSlashCommandState(text: String) {
        if (skillRegistry == null) return
        if (text.startsWith("/")) {
            val query = text.removePrefix("/").lowercase()
            val allSkills = skillRegistry.getAllSkills()
            val matches = if (query.isEmpty()) {
                allSkills
            } else {
                allSkills.filter { skill ->
                    skill.name.contains(query) || skill.displayName.lowercase().contains(query)
                }
            }
            _uiState.update {
                it.copy(
                    slashCommandState = SlashCommandState(
                        isActive = true,
                        query = query,
                        matchingSkills = matches
                    )
                )
            }
        } else {
            _uiState.update {
                it.copy(slashCommandState = SlashCommandState(isActive = false))
            }
        }
    }

    /**
     * Select a skill from the slash command popup.
     * Loads skill content directly and injects it as the user message (RFC-014 Trigger Path 1).
     */
    fun selectSkillFromSlashCommand(skill: SkillDefinition) {
        val message = loadSkillContent(skill)
        _uiState.update {
            it.copy(
                inputText = "",
                slashCommandState = SlashCommandState(isActive = false)
            )
        }
        sendTextMessage(message)
    }

    /**
     * Select a skill from the skill selection bottom sheet.
     * Loads skill content directly and injects it as the user message (RFC-014 Trigger Path 2).
     */
    fun selectSkillFromSheet(skill: SkillDefinition) {
        val message = loadSkillContent(skill)
        _uiState.update { it.copy(showSkillSheet = false) }
        sendTextMessage(message)
    }

    /**
     * Load the full prompt content for a skill, falling back to a natural language request
     * if the content cannot be loaded.
     */
    private fun loadSkillContent(skill: SkillDefinition): String {
        val registry = skillRegistry ?: return "Use the ${skill.displayName} skill."
        return when (val result = registry.loadSkillContent(skill.name)) {
            is AppResult.Success -> result.data
            is AppResult.Error -> "Use the ${skill.displayName} skill."
        }
    }

    fun dismissSlashCommand() {
        _uiState.update { it.copy(slashCommandState = SlashCommandState(isActive = false)) }
    }

    fun toggleSkillSheet() {
        _uiState.update { it.copy(showSkillSheet = !it.showSkillSheet) }
    }

    fun dismissSkillSheet() {
        _uiState.update { it.copy(showSkillSheet = false) }
    }

    /**
     * Send a text message directly (used by skill selection flows).
     */
    private fun sendTextMessage(text: String) {
        if (text.isBlank()) return

        if (_uiState.value.isStreaming) {
            viewModelScope.launch {
                val sessionId = _uiState.value.sessionId ?: return@launch
                messageRepository.addMessage(Message(
                    id = "", sessionId = sessionId, type = MessageType.USER,
                    content = text, thinkingContent = null,
                    toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                    toolStatus = null, toolDurationMs = null, tokenCountInput = null,
                    tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
                ))
                val tempId = java.util.UUID.randomUUID().toString()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatMessageItem(
                            id = tempId, type = MessageType.USER,
                            content = text, timestamp = System.currentTimeMillis()
                        ),
                        pendingCount = state.pendingCount + 1
                    )
                }
                pendingMessages.trySend(text)
            }
            return
        }

        // Use the normal sendMessage path by setting input text then calling sendMessage
        _uiState.update { it.copy(inputText = text) }
        sendMessage()
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return
        _uiState.update { it.copy(inputText = "") }

        if (_uiState.value.isStreaming) {
            // Queue path: save to DB immediately, signal the running loop
            viewModelScope.launch {
                val sessionId = _uiState.value.sessionId ?: return@launch
                messageRepository.addMessage(Message(
                    id = "", sessionId = sessionId, type = MessageType.USER,
                    content = text, thinkingContent = null,
                    toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                    toolStatus = null, toolDurationMs = null, tokenCountInput = null,
                    tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
                ))
                val tempId = java.util.UUID.randomUUID().toString()
                _uiState.update { state ->
                    state.copy(
                        messages = state.messages + ChatMessageItem(
                            id = tempId, type = MessageType.USER,
                            content = text, timestamp = System.currentTimeMillis()
                        ),
                        pendingCount = state.pendingCount + 1
                    )
                }
                pendingMessages.trySend(text)
            }
            return
        }

        // Non-streaming path: start a new loop (existing logic)
        viewModelScope.launch {
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
                    activeToolCalls = emptyList()
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
                        agentId = _uiState.value.currentAgentId,
                        pendingMessages = pendingMessages
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
                // RFC-008: Notify if app is in background
                if (!appLifecycleObserver.isInForeground) {
                    val preview = event.message.content
                    notificationHelper.sendTaskCompletedNotification(sessionId, preview)
                }
            }
            is ChatEvent.CompactStarted -> {
                _uiState.update { it.copy(isCompacting = true) }
            }
            is ChatEvent.CompactCompleted -> {
                _uiState.update { it.copy(isCompacting = false) }
                // No Snackbar needed on success; fallback (didCompact=false) is silent
            }
            is ChatEvent.UserMessageInjected -> {
                _uiState.update { it.copy(pendingCount = maxOf(0, it.pendingCount - 1)) }
            }
            is ChatEvent.Error -> {
                handleError(sessionId, event)
                // RFC-008: Notify if app is in background
                if (!appLifecycleObserver.isInForeground) {
                    notificationHelper.sendTaskFailedNotification(sessionId, event.message)
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
                activeToolCalls = emptyList()
            )
        }
        streamingJob = viewModelScope.launch {
            var accumulatedText = ""
            var accumulatedThinking = ""
            try {
                sendMessageUseCase.execute(
                    sessionId = sessionId, userText = userText,
                    agentId = _uiState.value.currentAgentId,
                    pendingMessages = pendingMessages
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
        // Handle queued messages that won't be processed (Stop was pressed)
        val abandonedTexts = mutableListOf<String>()
        while (true) {
            val text = pendingMessages.tryReceive().getOrNull() ?: break
            abandonedTexts.add(text)
        }
        if (abandonedTexts.isNotEmpty()) {
            messageRepository.addMessage(Message(
                id = "", sessionId = sessionId, type = MessageType.SYSTEM,
                content = "The user interrupted the previous response. " +
                    "The preceding queued message(s) were submitted before the interruption " +
                    "and can be ignored. Please respond to the user's next message.",
                thinkingContent = null,
                toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                toolStatus = null, toolDurationMs = null, tokenCountInput = null,
                tokenCountOutput = null, modelId = null, providerId = null, createdAt = 0
            ))
        }

        _uiState.update {
            it.copy(
                isStreaming = false, streamingText = "", streamingThinkingText = "",
                activeToolCalls = emptyList(), pendingCount = 0
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
                activeToolCalls = emptyList(), pendingCount = 0
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
    tokenCountInput = tokenCountInput,
    tokenCountOutput = tokenCountOutput,
    isRetryable = type == MessageType.ERROR, timestamp = createdAt
)
