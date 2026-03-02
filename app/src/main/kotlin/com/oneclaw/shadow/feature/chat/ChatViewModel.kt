package com.oneclaw.shadow.feature.chat

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.core.model.Attachment
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ProviderCapability
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.AttachmentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.local.AttachmentFileManager
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import com.oneclaw.shadow.feature.memory.trigger.MemoryTriggerManager
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
import java.io.File

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
    private val skillRegistry: SkillRegistry? = null,
    private val memoryTriggerManager: MemoryTriggerManager? = null,
    private val attachmentFileManager: AttachmentFileManager? = null,
    private val attachmentRepository: AttachmentRepository? = null
) : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var streamingJob: Job? = null
    private var loadSessionJob: Job? = null
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
        // RFC-023: Trigger session switch before loading new session
        val previousSessionId = _uiState.value.sessionId
        if (previousSessionId != null && previousSessionId != sessionId) {
            memoryTriggerManager?.onSessionSwitch(previousSessionId)
        }

        BridgeStateTracker.setActiveAppSession(sessionId)

        loadSessionJob?.cancel()
        loadSessionJob = null
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
        loadSessionJob = viewModelScope.launch {
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
                val messageIds = messages.map { it.id }
                val allAttachments = attachmentRepository?.getAttachmentsForMessages(messageIds) ?: emptyList()
                val attachmentsByMessage = allAttachments.groupBy { it.messageId }
                _uiState.update {
                    it.copy(messages = messages.map { m ->
                        m.toChatMessageItem(attachments = attachmentsByMessage[m.id] ?: emptyList())
                    })
                }
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

    fun selectSkillFromSlashCommand(skill: SkillDefinition) {
        val commandText = "/${skill.name} "
        _uiState.update {
            it.copy(
                inputText = commandText,
                slashCommandState = SlashCommandState(isActive = false)
            )
        }
    }

    fun selectSkillFromSheet(skill: SkillDefinition) {
        val commandText = "/${skill.name} "
        _uiState.update {
            it.copy(
                inputText = commandText,
                showSkillSheet = false
            )
        }
    }

    private fun buildSkillMessage(skill: SkillDefinition): String {
        return if (skill.parameters.isEmpty()) {
            "Use the ${skill.displayName} skill"
        } else {
            val paramsList = skill.parameters.joinToString(", ") { p ->
                if (p.required) p.name else "${p.name} (optional)"
            }
            "Use the ${skill.displayName} skill. Parameters: $paramsList"
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

    fun showAttachmentPicker() {
        _uiState.update { it.copy(showAttachmentPicker = true) }
    }

    fun hideAttachmentPicker() {
        _uiState.update { it.copy(showAttachmentPicker = false) }
    }

    fun addAttachment(uri: Uri) {
        val manager = attachmentFileManager ?: return
        viewModelScope.launch {
            val sessionId = _uiState.value.sessionId ?: run {
                val session = createSessionUseCase(agentId = _uiState.value.currentAgentId)
                _uiState.update { it.copy(sessionId = session.id) }
                val truncatedTitle = generateTitleUseCase.generateTruncatedTitle("attachment")
                sessionRepository.updateTitle(session.id, truncatedTitle)
                _uiState.update { it.copy(sessionTitle = truncatedTitle) }
                session.id
            }
            when (val result = manager.copyFromUri(uri, sessionId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + result.data) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(snackbarMessage = result.message) }
                }
            }
        }
    }

    fun addCameraPhoto(file: File) {
        val manager = attachmentFileManager ?: return
        viewModelScope.launch {
            val sessionId = _uiState.value.sessionId ?: run {
                val session = createSessionUseCase(agentId = _uiState.value.currentAgentId)
                _uiState.update { it.copy(sessionId = session.id) }
                session.id
            }
            when (val result = manager.copyFromCameraFile(file, sessionId)) {
                is AppResult.Success -> {
                    _uiState.update { it.copy(pendingAttachments = it.pendingAttachments + result.data) }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(snackbarMessage = result.message) }
                }
            }
        }
    }

    fun removeAttachment(id: String) {
        _uiState.update { it.copy(pendingAttachments = it.pendingAttachments.filter { a -> a.id != id }) }
    }

    fun openImageViewer(imagePath: String) {
        _uiState.update { it.copy(viewingImagePath = imagePath) }
    }

    fun closeImageViewer() {
        _uiState.update { it.copy(viewingImagePath = null) }
    }

    fun clearSnackbarMessage() {
        _uiState.update { it.copy(snackbarMessage = null) }
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
        val pendingAttachments = _uiState.value.pendingAttachments
        if (text.isBlank() && pendingAttachments.isEmpty()) return
        _uiState.update { it.copy(inputText = "", pendingAttachments = emptyList()) }

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
                val titleSource = text.ifBlank { pendingAttachments.firstOrNull()?.fileName ?: "attachment" }
                val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(titleSource)
                sessionRepository.updateTitle(sessionId, truncatedTitle)
                _uiState.update { it.copy(sessionTitle = truncatedTitle) }

                firstUserMessageText = text.ifBlank { titleSource }
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
                        pendingMessages = pendingMessages,
                        pendingAttachments = pendingAttachments
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
                val msgIds = messages.map { it.id }
                val attachments = attachmentRepository?.getAttachmentsForMessages(msgIds) ?: emptyList()
                val attachmentsByMsg = attachments.groupBy { it.messageId }
                _uiState.update {
                    it.copy(messages = messages.map { m ->
                        m.toChatMessageItem(attachments = attachmentsByMsg[m.id] ?: emptyList())
                    })
                }
            }
            is ChatEvent.ResponseComplete -> {
                _uiState.update { it.copy(isWebSearching = false, webSearchQuery = null) }
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
            is ChatEvent.WebSearchStarted -> {
                _uiState.update { it.copy(isWebSearching = true, webSearchQuery = event.query) }
            }
            is ChatEvent.Error -> {
                _uiState.update { it.copy(isWebSearching = false, webSearchQuery = null) }
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
                activeToolCalls = emptyList(), pendingCount = 0,
                isWebSearching = false, webSearchQuery = null
            )
        }
        // Reload messages from DB
        val messages = messageRepository.getMessagesSnapshot(sessionId)
        val messageIds = messages.map { it.id }
        val allAttachments = attachmentRepository?.getAttachmentsForMessages(messageIds) ?: emptyList()
        val attachmentsByMessage = allAttachments.groupBy { it.messageId }
        _uiState.update {
            it.copy(messages = messages.map { m ->
                m.toChatMessageItem(attachments = attachmentsByMessage[m.id] ?: emptyList())
            })
        }

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
        val messageIds = messages.map { it.id }
        val allAttachments = attachmentRepository?.getAttachmentsForMessages(messageIds) ?: emptyList()
        val attachmentsByMessage = allAttachments.groupBy { it.messageId }
        _uiState.update {
            it.copy(messages = messages.map { m ->
                m.toChatMessageItem(attachments = attachmentsByMessage[m.id] ?: emptyList())
            })
        }
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

fun Message.toChatMessageItem(attachments: List<Attachment> = emptyList()): ChatMessageItem = ChatMessageItem(
    id = id, type = type, content = content, thinkingContent = thinkingContent,
    toolCallId = toolCallId, toolName = toolName, toolInput = toolInput, toolOutput = toolOutput,
    toolStatus = toolStatus, toolDurationMs = toolDurationMs, modelId = modelId,
    tokenCountInput = tokenCountInput,
    tokenCountOutput = tokenCountOutput,
    isRetryable = type == MessageType.ERROR, timestamp = createdAt,
    citations = citations,
    attachments = attachments
)
