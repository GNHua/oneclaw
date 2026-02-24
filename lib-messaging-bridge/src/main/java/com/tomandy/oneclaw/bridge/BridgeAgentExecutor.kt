package com.tomandy.oneclaw.bridge

/**
 * Interface for triggering agent execution.
 * The app module implements this by calling ChatExecutionService.startExecution().
 */
interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}
