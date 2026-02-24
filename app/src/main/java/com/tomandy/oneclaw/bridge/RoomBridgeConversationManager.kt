package com.tomandy.oneclaw.bridge

import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import java.util.UUID

class RoomBridgeConversationManager(
    private val conversationDao: ConversationDao,
    private val messageDao: MessageDao
) : BridgeConversationManager {

    override suspend fun createNewConversation(): String {
        val conversationId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        conversationDao.insert(
            ConversationEntity(
                id = conversationId,
                title = "New Conversation",
                createdAt = now,
                updatedAt = now,
                messageCount = 0,
                lastMessagePreview = ""
            )
        )
        return conversationId
    }

    override suspend fun createConversation(conversationId: String, title: String) {
        val now = System.currentTimeMillis()
        conversationDao.insert(
            ConversationEntity(
                id = conversationId,
                title = title,
                createdAt = now,
                updatedAt = now,
                messageCount = 0,
                lastMessagePreview = ""
            )
        )
    }

    override suspend fun conversationExists(conversationId: String): Boolean {
        return conversationDao.getConversationOnce(conversationId) != null
    }

    override suspend fun insertUserMessage(
        conversationId: String,
        content: String,
        imagePaths: List<String>
    ) {
        val now = System.currentTimeMillis()

        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = content,
                timestamp = now
            )
        )

        // Update conversation metadata
        val conversation = conversationDao.getConversationOnce(conversationId) ?: return
        val messageCount = messageDao.getMessageCount(conversationId)
        conversationDao.insert(
            conversation.copy(
                updatedAt = now,
                messageCount = messageCount,
                lastMessagePreview = content.take(100)
            )
        )
    }

    override suspend fun updateConversationTimestamp(conversationId: String) {
        val conversation = conversationDao.getConversationOnce(conversationId) ?: return
        conversationDao.insert(conversation.copy(updatedAt = System.currentTimeMillis()))
    }
}
