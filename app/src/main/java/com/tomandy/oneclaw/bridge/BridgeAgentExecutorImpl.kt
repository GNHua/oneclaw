package com.tomandy.oneclaw.bridge

import android.content.Context
import com.tomandy.oneclaw.service.ChatExecutionService

class BridgeAgentExecutorImpl(private val context: Context) : BridgeAgentExecutor {

    override suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String>
    ) {
        ChatExecutionService.startExecution(
            context = context,
            conversationId = conversationId,
            userMessage = userMessage,
            imagePaths = imagePaths
        )
    }
}
