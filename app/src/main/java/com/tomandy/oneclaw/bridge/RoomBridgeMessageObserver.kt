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
        // Combine message updates with execution state.
        // Only return a result when:
        // 1. There is a final assistant message (no toolCalls, non-empty content)
        // 2. OR execution has finished (conversation no longer active)
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
                // Execution finished but no clean final message found.
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
            } else {
                null // Still executing, keep waiting
            }
        }
            .first { it != null }!!
    }
}
