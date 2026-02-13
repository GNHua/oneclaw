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
        triggerTime: Long,
        conversationId: String?
    ): Result<String> {
        return try {
            // Use a temporary conversation for agent execution
            // (keeps intermediate tool calls out of the user's conversation)
            val tempConversationId = "scheduled_${cronjobId}_${System.currentTimeMillis()}"

            // Create agent coordinator for this execution
            val coordinator = AgentCoordinator(
                clientProvider = { app.getCurrentLlmClient() },
                toolRegistry = app.toolRegistry,
                toolExecutor = app.toolExecutor,
                messageStore = app.messageStore,
                conversationId = tempConversationId
            )

            // Execute with scheduled context
            val context = ExecutionContext.Scheduled(
                cronjobId = cronjobId,
                triggerTime = triggerTime
            )

            // Get the selected model from preferences
            val model = app.modelPreferences.getSelectedModel() ?: ""

            val result = coordinator.execute(
                userMessage = instruction,
                systemPrompt = AgentCoordinator.TOOL_AWARE_SYSTEM_PROMPT,
                model = model,
                context = context
            )

            // Post result to the original conversation
            if (conversationId != null && result.isSuccess) {
                val summary = result.getOrNull() ?: ""
                app.messageStore.insert(
                    MessageRecord(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = "[Scheduled Task] $instruction\n\n$summary"
                    )
                )
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
