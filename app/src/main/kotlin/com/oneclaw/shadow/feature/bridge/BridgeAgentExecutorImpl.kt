package com.oneclaw.shadow.feature.bridge

import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import kotlinx.coroutines.flow.collect

class BridgeAgentExecutorImpl(
    private val sendMessageUseCase: SendMessageUseCase,
    private val agentRepository: AgentRepository
) : BridgeAgentExecutor {

    override suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String>
    ) {
        val agentId = resolveAgentId()
        sendMessageUseCase.execute(
            sessionId = conversationId,
            userText = userMessage,
            agentId = agentId
        ).collect()
    }

    private suspend fun resolveAgentId(): String {
        val builtIn = agentRepository.getBuiltInAgents()
        return builtIn.firstOrNull()?.id
            ?: agentRepository.getAllAgents()
                .let { flow ->
                    var result: String? = null
                    flow.collect { agents -> result = agents.firstOrNull()?.id }
                    result
                }
            ?: "default"
    }
}
