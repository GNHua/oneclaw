package com.oneclaw.shadow.feature.agent.usecase

import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode

class CloneAgentUseCase(
    private val agentRepository: AgentRepository
) {
    suspend operator fun invoke(agentId: String): AppResult<Agent> {
        val original = agentRepository.getAgentById(agentId)
            ?: return AppResult.Error(
                message = "Agent not found.",
                code = ErrorCode.VALIDATION_ERROR
            )
        val clone = Agent(
            id = "",
            name = "Copy of ${original.name}",
            description = original.description,
            systemPrompt = original.systemPrompt,
            toolIds = original.toolIds,
            preferredProviderId = original.preferredProviderId,
            preferredModelId = original.preferredModelId,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0
        )
        val created = agentRepository.createAgent(clone)
        return AppResult.Success(created)
    }
}
