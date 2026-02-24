package com.tomandy.oneclaw.bridge

import com.tomandy.oneclaw.data.ConversationPreferences
import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.scheduler.TaskCompletionNotifier
import java.util.UUID

class BridgeTaskCompletionNotifier(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao,
    private val conversationPreferences: ConversationPreferences
) : TaskCompletionNotifier {

    override suspend fun onTaskCompleted(instruction: String, summary: String) {
        val content = "[Scheduled Task] $instruction\n\n$summary"
        insertIntoActiveConversation(instruction, summary)
        BridgeBroadcaster.broadcast(content)
    }

    override suspend fun onTaskFailed(instruction: String, error: String) {
        val errorSummary = "Error: $error"
        val content = "[Scheduled Task Failed] $instruction\n\n$errorSummary"
        insertIntoActiveConversation(instruction, errorSummary)
        BridgeBroadcaster.broadcast(content)
    }

    private suspend fun insertIntoActiveConversation(instruction: String, result: String) {
        val conversationId = conversationPreferences.getActiveConversationId() ?: return
        if (conversationDao.getConversationOnce(conversationId) == null) return

        val now = System.currentTimeMillis()

        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "user",
                content = "[Scheduled Task] $instruction",
                timestamp = now
            )
        )

        messageDao.insert(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = "assistant",
                content = result,
                timestamp = now + 1
            )
        )

        val messageCount = messageDao.getMessageCount(conversationId)
        val conversation = conversationDao.getConversationOnce(conversationId) ?: return
        conversationDao.update(
            conversation.copy(
                updatedAt = now + 1,
                messageCount = messageCount,
                lastMessagePreview = result.take(100)
            )
        )
    }
}
