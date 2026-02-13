package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.PalmClawApp
import com.tomandy.palmclaw.scheduler.AgentExecutor
import java.util.UUID

/**
 * Implementation of AgentExecutor for executing scheduled tasks.
 *
 * This bridges the scheduler library with the app's agent system.
 */
class ScheduledAgentExecutor(
    private val app: PalmClawApp
) : AgentExecutor {

    override suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long
    ): Result<String> {
        return try {
            // Create a temporary conversation for this scheduled task
            val conversationId = "scheduled_${cronjobId}_${System.currentTimeMillis()}"

            // Create agent coordinator for this execution
            val coordinator = AgentCoordinator(
                clientProvider = { app.getCurrentLlmClient() },
                toolRegistry = app.toolRegistry,
                toolExecutor = app.toolExecutor,
                messageStore = app.messageStore,
                conversationId = conversationId
            )

            // Execute with scheduled context
            val context = ExecutionContext.Scheduled(
                cronjobId = cronjobId,
                triggerTime = triggerTime
            )

            // Get the selected model from preferences
            val model = app.modelPreferences.getSelectedModel() ?: ""

            coordinator.execute(
                userMessage = instruction,
                systemPrompt = AgentCoordinator.TOOL_AWARE_SYSTEM_PROMPT,
                model = model,
                context = context
            )

        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
