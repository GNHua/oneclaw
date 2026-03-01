package com.oneclaw.shadow.feature.bridge

import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.repository.MessageRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout

class BridgeMessageObserverImpl(
    private val messageRepository: MessageRepository
) : BridgeMessageObserver {

    override suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long,
        timeoutMs: Long
    ): BridgeMessage {
        return withTimeout(timeoutMs) {
            while (true) {
                val messages = messageRepository.getMessagesSnapshot(conversationId)
                val response = messages
                    .filter { it.type == MessageType.AI_RESPONSE && it.createdAt > afterTimestamp }
                    .maxByOrNull { it.createdAt }
                if (response != null) {
                    return@withTimeout BridgeMessage(
                        content = response.content,
                        timestamp = response.createdAt
                    )
                }
                delay(500)
            }
            @Suppress("UNREACHABLE_CODE")
            throw IllegalStateException("Should never reach here")
        }
    }
}
