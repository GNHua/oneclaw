package com.tomandy.oneclaw.bridge

/**
 * Interface for observing agent responses.
 * The app module implements this by watching MessageDao's Room Flow.
 */
interface BridgeMessageObserver {
    /**
     * Suspend until the next assistant message appears in the conversation
     * after the given timestamp. Times out after [timeoutMs].
     */
    suspend fun awaitNextAssistantMessage(
        conversationId: String,
        afterTimestamp: Long = System.currentTimeMillis(),
        timeoutMs: Long = 300_000
    ): BridgeMessage
}
