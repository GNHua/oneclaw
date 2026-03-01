package com.oneclaw.shadow.bridge

interface BridgeMessageObserver {
    suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long = System.currentTimeMillis(),
        timeoutMs: Long = 300_000
    ): BridgeMessage
}
