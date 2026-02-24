package com.tomandy.oneclaw.bridge

import com.tomandy.oneclaw.data.dao.MessageDao
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.withTimeout

class RoomBridgeMessageObserver(
    private val messageDao: MessageDao
) : BridgeMessageObserver {

    override suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long,
        timeoutMs: Long
    ): BridgeMessage = withTimeout(timeoutMs) {
        messageDao.getMessages(conversationId)
            .map { messages ->
                messages.lastOrNull { msg ->
                    msg.role == "assistant" && msg.timestamp > afterTimestamp
                }
            }
            .filterNotNull()
            .first()
            .let { entity ->
                BridgeMessage(
                    content = entity.content,
                    timestamp = entity.timestamp
                )
            }
    }
}
