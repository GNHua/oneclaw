package com.oneclaw.shadow.bridge

interface BridgeAgentExecutor {
    suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String> = emptyList()
    )
}
