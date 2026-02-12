package com.tomandy.palmclaw.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.agent.AgentCoordinator
import com.tomandy.palmclaw.agent.AgentState
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.LlmProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import java.util.UUID

/**
 * ViewModel for managing chat screen state and orchestrating message flow.
 *
 * This is the state management hub that connects:
 * - AgentCoordinator for AI processing
 * - Database DAOs for persistence
 * - UI components for reactive updates
 *
 * @param agentCoordinator The agent coordinator for AI processing
 * @param messageDao DAO for message persistence
 * @param conversationDao DAO for conversation management
 * @param conversationId Optional conversation ID (creates new if null)
 */
class ChatViewModel(
    private val agentCoordinator: AgentCoordinator,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val modelPreferences: ModelPreferences,
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

    init {
        // Load messages from database
        viewModelScope.launch {
            messageDao.getMessages(_conversationId.value).collect { msgs ->
                _messages.value = msgs
            }
        }

        // Observe agent state
        viewModelScope.launch {
            agentCoordinator.state.collect { state ->
                _agentState.value = state
            }
        }

        // Create conversation if it doesn't exist
        viewModelScope.launch {
            val existing = conversationDao.getConversation(_conversationId.value).firstOrNull()
            if (existing == null) {
                conversationDao.insert(
                    ConversationEntity(
                        id = _conversationId.value,
                        title = "New Conversation",
                        createdAt = System.currentTimeMillis(),
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    /**
     * Sends a message to the AI agent and handles the response.
     *
     * Flow:
     * 1. Save user message to database
     * 2. Execute agent coordinator
     * 3. Save assistant response to database
     * 4. Update conversation timestamp
     * 5. Handle errors with user feedback
     *
     * @param text The user's message text
     */
    fun sendMessage(text: String) {
        if (text.isBlank()) return

        viewModelScope.launch {
            _isProcessing.value = true
            _error.value = null

            try {
                // Save user message
                val userMessage = MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = _conversationId.value,
                    role = "user",
                    content = text,
                    timestamp = System.currentTimeMillis()
                )
                messageDao.insert(userMessage)

                // Update conversation timestamp
                conversationDao.getConversation(_conversationId.value).firstOrNull()?.let { conv ->
                    conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                }

                // Get the currently selected model (global)
                val selectedModel = modelPreferences.getSelectedModel()
                    ?: modelPreferences.getModel(getCurrentProvider())

                // Execute agent with selected model
                val result = agentCoordinator.execute(
                    userMessage = text,
                    systemPrompt = "You are a helpful AI assistant for Android. Be concise and accurate.",
                    model = selectedModel
                )

                result.fold(
                    onSuccess = { response ->
                        // Save assistant message
                        val assistantMessage = MessageEntity(
                            id = UUID.randomUUID().toString(),
                            conversationId = _conversationId.value,
                            role = "assistant",
                            content = response,
                            timestamp = System.currentTimeMillis()
                        )
                        messageDao.insert(assistantMessage)

                        // Update conversation
                        conversationDao.getConversation(_conversationId.value).firstOrNull()?.let { conv ->
                            conversationDao.update(conv.copy(updatedAt = System.currentTimeMillis()))
                        }
                    },
                    onFailure = { e ->
                        _error.value = e.message ?: "Unknown error occurred"
                    }
                )
            } catch (e: Exception) {
                _error.value = e.message ?: "Unknown error occurred"
            } finally {
                _isProcessing.value = false
            }
        }
    }

    /**
     * Clears the current error state.
     * Typically called after displaying error to user.
     */
    fun clearError() {
        _error.value = null
    }

    /**
     * Cancels the current AI request in progress.
     */
    fun cancelRequest() {
        agentCoordinator.cancel()
        _isProcessing.value = false
    }

    /**
     * Creates a new conversation and resets the agent state.
     * Switches to the new conversation ID and clears history.
     */
    fun newConversation() {
        val newId = UUID.randomUUID().toString()
        _conversationId.value = newId
        agentCoordinator.reset()

        viewModelScope.launch {
            conversationDao.insert(
                ConversationEntity(
                    id = newId,
                    title = "New Conversation",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )
        }
    }
}
