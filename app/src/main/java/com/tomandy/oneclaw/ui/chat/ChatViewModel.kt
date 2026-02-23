package com.tomandy.oneclaw.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.oneclaw.agent.AgentState
import com.tomandy.oneclaw.data.ConversationPreferences
import com.tomandy.oneclaw.agent.profile.AgentProfileEntry
import com.tomandy.oneclaw.agent.profile.AgentProfileRepository
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.llm.NetworkConfig
import com.tomandy.oneclaw.service.ChatExecutionManager
import com.tomandy.oneclaw.service.ChatExecutionService
import com.tomandy.oneclaw.service.ChatExecutionTracker
import com.tomandy.oneclaw.skill.SkillRepository
import com.tomandy.oneclaw.skill.SlashCommandRouter
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val conversationPreferences: ConversationPreferences,
    private val modelPreferences: ModelPreferences,
    private val appContext: Context,
    private val executionManager: ChatExecutionManager,
    private val slashCommandRouter: SlashCommandRouter,
    private val skillRepository: SkillRepository,
    private val agentProfileRepository: AgentProfileRepository,
    conversationId: String? = null
) : ViewModel() {

    private val _conversationId = MutableStateFlow(
        conversationId ?: UUID.randomUUID().toString()
    )
    val conversationId: StateFlow<String> = _conversationId.asStateFlow()

    private val _messages = MutableStateFlow<List<MessageEntity>>(emptyList())
    val messages: StateFlow<List<MessageEntity>> = _messages.asStateFlow()

    private val _isProcessing = MutableStateFlow(false)
    val isProcessing: StateFlow<Boolean> = _isProcessing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _agentState = MutableStateFlow<AgentState>(AgentState.Idle)
    val agentState: StateFlow<AgentState> = _agentState.asStateFlow()

    val uiEvents: SharedFlow<ChatExecutionTracker.UiEvent> = ChatExecutionTracker.uiEvents

    val agentProfiles: StateFlow<List<AgentProfileEntry>> = agentProfileRepository.profiles

    private val _currentProfileId = MutableStateFlow<String?>(null)
    val currentProfileId: StateFlow<String?> = _currentProfileId.asStateFlow()

    init {
        agentProfileRepository.reload()

        // Observe messages from DB (reactive -- updates when service writes new rows)
        viewModelScope.launch {
            @Suppress("OPT_IN_USAGE")
            _conversationId.flatMapLatest { id ->
                messageDao.getMessages(id)
            }.collect { msgs ->
                _messages.value = msgs
            }
        }

        // Observe processing state from the service tracker
        viewModelScope.launch {
            combine(_conversationId, ChatExecutionTracker.activeConversations) { convId, active ->
                convId in active
            }.collect { processing ->
                _isProcessing.value = processing
            }
        }

        // Observe agent state from the service tracker
        viewModelScope.launch {
            combine(_conversationId, ChatExecutionTracker.agentStates) { convId, states ->
                states[convId] ?: AgentState.Idle
            }.collect { state ->
                _agentState.value = state
            }
        }

        // Observe errors from the service tracker
        viewModelScope.launch {
            combine(_conversationId, ChatExecutionTracker.errors) { convId, errors ->
                errors[convId]
            }.collect { error ->
                _error.value = error
            }
        }

        // Persist active conversation ID
        viewModelScope.launch {
            conversationPreferences.setActiveConversationId(_conversationId.value)
        }

        // Load the global active agent profile
        _currentProfileId.value = modelPreferences.getActiveAgent()
    }

    fun sendMessage(text: String, imagePaths: List<String> = emptyList(), audioPaths: List<String> = emptyList(), videoPaths: List<String> = emptyList(), documentMetas: List<Triple<String, String, String>> = emptyList()) {
        Log.d("ChatViewModel", "sendMessage called with text: $text, images: ${imagePaths.size}, audios: ${audioPaths.size}, videos: ${videoPaths.size}, docs: ${documentMetas.size}")
        if (text.isBlank() && imagePaths.isEmpty() && audioPaths.isEmpty() && videoPaths.isEmpty() && documentMetas.isEmpty()) return

        // Handle /summarize command
        if (imagePaths.isEmpty() && text.trim().equals("/summarize", ignoreCase = true)) {
            handleSummarizeCommand(text)
            return
        }

        // Handle skill slash commands
        val parsedCommand = slashCommandRouter.parse(text)
        if (parsedCommand != null) {
            val skill = slashCommandRouter.resolve(parsedCommand)
            if (skill != null) {
                val body = skillRepository.loadBody(skill)
                if (body == null) {
                    _error.value = "Failed to load skill: ${skill.metadata.name}"
                    return
                }
                val location = skill.filePath ?: "skills/${skill.metadata.name}/SKILL.md"
                val baseDir = skill.baseDir ?: "skills/${skill.metadata.name}"
                val skillMessage = buildString {
                    appendLine("<skill name=\"${skill.metadata.name}\" location=\"$location\">")
                    appendLine("References are relative to $baseDir.")
                    appendLine()
                    appendLine(body)
                    appendLine("</skill>")
                    if (parsedCommand.arguments.isNotBlank()) {
                        appendLine()
                        append(parsedCommand.arguments)
                    }
                }
                sendMessageInternal(text, skillMessage, imagePaths, audioPaths, videoPaths, documentMetas)
                return
            }
        }

        sendMessageInternal(text, text, imagePaths, audioPaths, videoPaths, documentMetas)
    }

    /**
     * Send a message to the agent.
     *
     * @param displayText Text shown in the chat UI (what the user typed)
     * @param executionText Text sent to the agent (may include skill context)
     * @param imagePaths File paths of attached images
     * @param audioPaths File paths of attached audio files
     * @param videoPaths File paths of attached video files
     */
    private fun sendMessageInternal(
        displayText: String,
        executionText: String,
        imagePaths: List<String> = emptyList(),
        audioPaths: List<String> = emptyList(),
        videoPaths: List<String> = emptyList(),
        documentMetas: List<Triple<String, String, String>> = emptyList()
    ) {
        viewModelScope.launch {
            ChatExecutionTracker.clearError(_conversationId.value)

            val convId = _conversationId.value

            // Ensure conversation exists in DB (foreign key for messages)
            var conv = conversationDao.getConversationOnce(convId)
            if (conv == null) {
                val title = displayText.take(50).let {
                    if (displayText.length > 50) "$it..." else it
                }
                conv = ConversationEntity(
                    id = convId,
                    title = title,
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    messageCount = 1,
                    lastMessagePreview = displayText.take(100)
                )
                conversationDao.insert(conv)
            } else {
                conversationDao.update(conv.copy(
                    updatedAt = System.currentTimeMillis(),
                    messageCount = conv.messageCount + 1,
                    lastMessagePreview = displayText.take(100)
                ))
            }

            // Serialize image paths to JSON if present
            val imagePathsJson = if (imagePaths.isNotEmpty()) {
                NetworkConfig.json.encodeToString(
                    ListSerializer(String.serializer()), imagePaths
                )
            } else null

            // Serialize audio paths to JSON if present
            val audioPathsJson = if (audioPaths.isNotEmpty()) {
                NetworkConfig.json.encodeToString(
                    ListSerializer(String.serializer()), audioPaths
                )
            } else null

            // Serialize video paths to JSON if present
            val videoPathsJson = if (videoPaths.isNotEmpty()) {
                NetworkConfig.json.encodeToString(
                    ListSerializer(String.serializer()), videoPaths
                )
            } else null

            // Serialize document metadata to JSON if present
            // Format: [{"path":"...","name":"...","mimeType":"..."},...]
            val documentPathsJson = if (documentMetas.isNotEmpty()) {
                kotlinx.serialization.json.buildJsonArray {
                    documentMetas.forEach { (path, name, mimeType) ->
                        add(kotlinx.serialization.json.buildJsonObject {
                            put("path", kotlinx.serialization.json.JsonPrimitive(path))
                            put("name", kotlinx.serialization.json.JsonPrimitive(name))
                            put("mimeType", kotlinx.serialization.json.JsonPrimitive(mimeType))
                        })
                    }
                }.toString()
            } else null

            // Persist user message (always -- so it shows in the chat immediately)
            val userMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = "user",
                content = displayText,
                timestamp = System.currentTimeMillis(),
                imagePaths = imagePathsJson,
                audioPaths = audioPathsJson,
                videoPaths = videoPathsJson,
                documentPaths = documentPathsJson
            )
            messageDao.insert(userMessage)

            // If already processing, inject into the running loop; otherwise start new execution
            if (_isProcessing.value) {
                Log.d("ChatViewModel", "Injecting message into active loop: $executionText")
                ChatExecutionService.injectMessage(appContext, convId, executionText)
            } else {
                ChatExecutionService.startExecution(
                    context = appContext,
                    conversationId = convId,
                    userMessage = executionText,
                    imagePaths = imagePaths,
                    audioPaths = audioPaths,
                    videoPaths = videoPaths,
                    documentPaths = documentMetas.map { it.first },
                    documentNames = documentMetas.map { it.second },
                    documentMimeTypes = documentMetas.map { it.third }
                )
            }
        }
    }

    fun setAgentProfile(profileId: String?) {
        modelPreferences.saveActiveAgent(profileId)
        _currentProfileId.value = profileId
    }

    fun refreshActiveAgent() {
        _currentProfileId.value = modelPreferences.getActiveAgent()
        agentProfileRepository.reload()
    }

    private fun handleSummarizeCommand(text: String) {
        if (_isProcessing.value) {
            _error.value = "Cannot summarize while a message is being processed."
            return
        }

        viewModelScope.launch {
            val convId = _conversationId.value
            ChatExecutionTracker.clearError(convId)

            // Check conversation exists and has enough messages
            val conv = conversationDao.getConversationOnce(convId) ?: return@launch
            val msgCount = messageDao.getMessageCount(convId)
            if (msgCount <= 2) {
                _error.value = "Not enough messages to summarize."
                return@launch
            }

            // Persist the command as a user message so it shows in chat
            messageDao.insert(
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
            )

            ChatExecutionService.startSummarization(appContext, convId)
        }
    }

    fun clearError() {
        ChatExecutionTracker.clearError(_conversationId.value)
    }

    fun cancelRequest() {
        executionManager.cancelExecution(_conversationId.value)
    }

    fun newConversation() {
        viewModelScope.launch {
            // Clean up current conversation if it has no messages
            val currentId = _conversationId.value
            val msgCount = messageDao.getMessageCount(currentId)
            if (msgCount == 0) {
                conversationDao.deleteById(currentId)
            }

            // Switch to new conversation (DB entry created lazily on first message)
            val newId = UUID.randomUUID().toString()
            switchToConversation(newId)
        }
    }

    fun loadConversation(convId: String) {
        viewModelScope.launch {
            // Clean up current conversation if empty
            val currentId = _conversationId.value
            if (currentId != convId) {
                val msgCount = messageDao.getMessageCount(currentId)
                if (msgCount == 0) {
                    conversationDao.deleteById(currentId)
                }
            }

            switchToConversation(convId)
        }
    }

    private suspend fun switchToConversation(convId: String) {
        _conversationId.value = convId
        conversationPreferences.setActiveConversationId(convId)
    }
}
