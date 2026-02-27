package com.tomandy.oneclaw.bridge

import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.service.ChatExecutionTracker
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout

class RoomBridgeMessageObserver(
    private val messageDao: MessageDao
) : BridgeMessageObserver {

    override suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long,
        timeoutMs: Long
    ): BridgeMessage = withTimeout(timeoutMs) {
        // Track whether we've seen execution finish so we can give the Room
        // Flow a few extra emissions to deliver the final message before
        // falling back to the "[Execution stopped]" placeholder.
        var executionDoneSeenCount = 0

        combine(
            messageDao.getMessages(conversationId),
            ChatExecutionTracker.activeConversations
        ) { messages, activeConversations ->
            val executionDone = conversationId !in activeConversations

            // Find the last assistant message that is a final response
            // (not an intermediate tool-call message)
            val finalMessage = messages.lastOrNull { msg ->
                msg.role == "assistant" &&
                    msg.timestamp > afterTimestamp &&
                    msg.toolCalls.isNullOrBlank() &&
                    msg.content.isNotBlank()
            }

            if (finalMessage != null) {
                BridgeMessage(
                    content = finalMessage.content,
                    timestamp = finalMessage.timestamp
                )
            } else if (executionDone) {
                // Execution finished but no clean final message found yet.
                // The Room Flow may not have propagated the final insert, so
                // wait for a few more emissions before giving up.
                executionDoneSeenCount++
                if (executionDoneSeenCount <= 3) {
                    null // Give Room Flow time to catch up
                } else {
                    // Fall back to any assistant content, or a cancellation notice.
                    val lastAssistant = messages.lastOrNull { msg ->
                        msg.role == "assistant" &&
                            msg.timestamp > afterTimestamp &&
                            msg.content.isNotBlank()
                    }
                    BridgeMessage(
                        content = lastAssistant?.content ?: "[Execution stopped]",
                        timestamp = lastAssistant?.timestamp ?: System.currentTimeMillis()
                    )
                }
            } else {
                null // Still executing, keep waiting
            }
        }
            .first { it != null }!!
    }
}
