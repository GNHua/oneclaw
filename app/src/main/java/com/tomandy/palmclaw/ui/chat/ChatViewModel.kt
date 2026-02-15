package com.tomandy.palmclaw.ui.chat

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.agent.AgentState
import com.tomandy.palmclaw.data.ConversationPreferences
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.service.ChatExecutionService
import com.tomandy.palmclaw.service.ChatExecutionTracker
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val appContext: Context,
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
    }

    fun sendMessage(text: String) {
        Log.d("ChatViewModel", "sendMessage called with text: $text")
        if (text.isBlank()) return

        // Handle /summarize command
        if (text.trim().equals("/summarize", ignoreCase = true)) {
            handleSummarizeCommand()
            return
        }

        viewModelScope.launch {
            ChatExecutionTracker.clearError(_conversationId.value)

            val convId = _conversationId.value

            // Ensure conversation exists in DB (foreign key for messages)
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

            // Persist user message (always -- so it shows in the chat immediately)
            val userMessage = MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = convId,
                role = "user",
                content = text,
                timestamp = System.currentTimeMillis()
            )
            messageDao.insert(userMessage)

            // If already processing, inject into the running loop; otherwise start new execution
            if (_isProcessing.value) {
                Log.d("ChatViewModel", "Injecting message into active loop: $text")
                ChatExecutionService.injectMessage(appContext, convId, text)
            } else {
                ChatExecutionService.startExecution(appContext, convId, text)
            }
        }
    }

    private fun handleSummarizeCommand() {
        if (_isProcessing.value) {
            _error.value = "Cannot summarize while a message is being processed."
            return
        }

        viewModelScope.launch {
            val convId = _conversationId.value
            ChatExecutionTracker.clearError(convId)
            ChatExecutionService.startSummarization(appContext, convId)
        }
    }

    fun clearError() {
        ChatExecutionTracker.clearError(_conversationId.value)
    }

    fun cancelRequest() {
        ChatExecutionService.cancelExecutionDirect(_conversationId.value)
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
