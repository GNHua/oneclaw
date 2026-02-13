package com.tomandy.palmclaw.ui.chat

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.agent.AgentCoordinator
import com.tomandy.palmclaw.agent.AgentState
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.llm.Message
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.launch
import java.util.UUID

class ChatViewModel(
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val messageStore: MessageStore,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val modelPreferences: ModelPreferences,
    private val conversationPreferences: ConversationPreferences,
    private val getCurrentClient: () -> LlmClient,
    private val getCurrentProvider: () -> LlmProvider,
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

    private var agentCoordinator = createAgentCoordinator(_conversationId.value)
    private var agentStateJob: Job? = null

    private fun createAgentCoordinator(convId: String): AgentCoordinator {
        return AgentCoordinator(
            clientProvider = getCurrentClient,
            toolRegistry = toolRegistry,
            toolExecutor = toolExecutor,
            messageStore = messageStore,
            conversationId = convId,
            scope = viewModelScope
        )
    }

    init {
        // Use flatMapLatest so message collection reacts to conversation ID changes
        viewModelScope.launch {
            @Suppress("OPT_IN_USAGE")
            _conversationId.flatMapLatest { id ->
                messageDao.getMessages(id)
            }.collect { msgs ->
                _messages.value = msgs
            }
        }

        // Observe agent state
        observeAgentState()

        // Persist active ID and seed history if conversation already exists
        viewModelScope.launch {
            val id = _conversationId.value
            conversationPreferences.setActiveConversationId(id)
            val existing = conversationDao.getConversationOnce(id)
            if (existing != null) {
                seedAgentHistory(id)
            }
        }
    }

    private fun observeAgentState() {
        agentStateJob?.cancel()
        agentStateJob = viewModelScope.launch {
            agentCoordinator.state.collect { state ->
                _agentState.value = state
            }
        }
    }

    private suspend fun seedAgentHistory(convId: String) {
        val dbMessages = messageDao.getMessagesOnce(convId)
        val history = dbMessages
            .filter { it.role == "user" || it.role == "assistant" }
            .map { Message(role = it.role, content = it.content) }
        agentCoordinator.seedHistory(history)
    }

    fun sendMessage(text: String) {
        Log.d("ChatViewModel", "sendMessage called with text: $text")
        if (text.isBlank()) return

        viewModelScope.launch {
            Log.d("ChatViewModel", "Starting message processing")
            _isProcessing.value = true
            _error.value = null

            try {
                val convId = _conversationId.value

                // Ensure conversation exists in DB before inserting message (foreign key)
                var conv = conversationDao.getConversationOnce(convId)
                if (conv == null) {
                    val title = text.take(50).let {
                        if (text.length > 50) "$it..." else it
                    }
                    conv = ConversationEntity(
                        id = convId,
                        title = title,
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis(),
                        messageCount = 1,
                        lastMessagePreview = text.take(100)
                    )
                    conversationDao.insert(conv)
                } else {
                    conversationDao.update(conv.copy(
                        updatedAt = System.currentTimeMillis(),
                        messageCount = conv.messageCount + 1,
                        lastMessagePreview = text.take(100)
                    ))
                }

                // Save user message
                val userMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = convId,
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
                messageDao.insert(userMessage)

                // Get the currently selected model (global)
                val selectedModel = modelPreferences.getSelectedModel()
                    ?: modelPreferences.getModel(getCurrentProvider())

                Log.d("ChatViewModel", "Selected model: $selectedModel")

                // Execute agent with selected model and max iterations
                val maxIterations = modelPreferences.getMaxIterations()
                val result = agentCoordinator.execute(
                    userMessage = text,
                    systemPrompt = "You are a helpful AI assistant for Android. Be concise and accurate.",
                    model = selectedModel,
                    maxIterations = maxIterations
                )

                result.fold(
                    onSuccess = { response ->
                        Log.d("ChatViewModel", "Agent success, response length: ${response.length}")
                        // Save assistant message
                        val assistantMessage = MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = convId,
                            role = "assistant",
                            content = response,
                            timestamp = System.currentTimeMillis()
                        )
                        messageDao.insert(assistantMessage)

                        // Update conversation denormalized fields
                        conversationDao.getConversationOnce(convId)?.let { c ->
                            conversationDao.update(c.copy(
                                updatedAt = System.currentTimeMillis(),
                                messageCount = c.messageCount + 1,
                                lastMessagePreview = response.take(100)
                            ))
                        }
                    },
                    onFailure = { e ->
                        Log.e("ChatViewModel", "Agent failure: ${e.message}", e)
                        _error.value = e.message ?: "Unknown error occurred"
                    }
                )
            } catch (e: Exception) {
                Log.e("ChatViewModel", "Exception in sendMessage: ${e.message}", e)
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    fun clearError() {
        _error.value = null
    }

    fun cancelRequest() {
        agentCoordinator.cancel()
        _isProcessing.value = false
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
        // Cancel any in-progress request
        agentCoordinator.cancel()

        // Update conversation ID (flatMapLatest will handle message reload)
        _conversationId.value = convId
        conversationPreferences.setActiveConversationId(convId)

        // Create new agent coordinator for this conversation
        agentCoordinator = createAgentCoordinator(convId)
        observeAgentState()

        // Seed agent history from DB
        seedAgentHistory(convId)

        _isProcessing.value = false
        _error.value = null
    }
}
