package com.oneclaw.shadow.feature.bridge

import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import java.util.UUID

class BridgeConversationManagerImpl(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository
) : BridgeConversationManager {

    override suspend fun getActiveConversationId(): String? {
        return sessionRepository.getMostRecentSessionId()
    }

    override suspend fun createNewConversation(): String {
        val agentId = resolveAgentId()
        val now = System.currentTimeMillis()
        val session = Session(
            id = UUID.randomUUID().toString(),
            title = "Bridge Conversation",
            currentAgentId = agentId,
            messageCount = 0,
            lastMessagePreview = null,
            isActive = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now
        )
        val created = sessionRepository.createSession(session)
        return created.id
    }

    override suspend fun createConversation(conversationId: String, title: String) {
        val agentId = resolveAgentId()
        val now = System.currentTimeMillis()
        val session = Session(
            id = conversationId,
            title = title,
            currentAgentId = agentId,
            messageCount = 0,
            lastMessagePreview = null,
            isActive = false,
            deletedAt = null,
            createdAt = now,
            updatedAt = now
        )
        sessionRepository.createSession(session)
    }

    override suspend fun conversationExists(conversationId: String): Boolean {
        return sessionRepository.getSessionById(conversationId) != null
    }

    override suspend fun insertUserMessage(
        conversationId: String,
        content: String,
        imagePaths: List<String>
    ) {
        val message = Message(
            id = "",
            sessionId = conversationId,
            type = MessageType.USER,
            content = content,
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
        )
        messageRepository.addMessage(message)
    }

    override suspend fun updateConversationTimestamp(conversationId: String) {
        val session = sessionRepository.getSessionById(conversationId) ?: return
        sessionRepository.updateSession(session.copy(updatedAt = System.currentTimeMillis()))
    }

    private suspend fun resolveAgentId(): String {
        val builtIn = agentRepository.getBuiltInAgents()
        return builtIn.firstOrNull()?.id ?: "default"
    }
}
