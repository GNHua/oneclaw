package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.skill.SkillRepository
import java.util.UUID

class ScheduledAgentExecutor(
    private val database: AppDatabase,
    private val llmClientProvider: LlmClientProvider,
    private val toolRegistry: ToolRegistry,
    private val toolExecutor: ToolExecutor,
    private val messageStore: MessageStore,
    private val modelPreferences: ModelPreferences,
    private val skillRepository: SkillRepository
) : AgentExecutor {

    override suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long,
        conversationId: String?
    ): Result<String> {
        return try {
            skillRepository.reload()

            // Use a temporary conversation for agent execution
            // (keeps intermediate tool calls out of the user's conversation)
            val tempConversationId = "scheduled_${cronjobId}_${System.currentTimeMillis()}"

            // Create a temporary ConversationEntity so that intermediate messages
            // (tool calls, tool results) satisfy the foreign key constraint.
            val conversationDao = database.conversationDao()
            conversationDao.insert(
                ConversationEntity(
                    id = tempConversationId,
                    title = "[Scheduled] ${instruction.take(40)}",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis(),
                    messageCount = 0,
                    lastMessagePreview = ""
                )
            )

            // Create agent coordinator for this execution
            val coordinator = AgentCoordinator(
                clientProvider = { llmClientProvider.getCurrentLlmClient() },
                toolRegistry = toolRegistry,
                toolExecutor = toolExecutor,
                messageStore = messageStore,
                conversationId = tempConversationId
            )

            // Execute with scheduled context
            val context = ExecutionContext.Scheduled(
                cronjobId = cronjobId,
                triggerTime = triggerTime
            )

            // Get preferences
            val model = modelPreferences.getSelectedModel() ?: ""
            val temperature = modelPreferences.getTemperature()

            val result = coordinator.execute(
                userMessage = instruction,
                systemPrompt = AgentCoordinator.TOOL_AWARE_SYSTEM_PROMPT,
                model = model,
                temperature = temperature,
                context = context
            )

            // Clean up temporary conversation (cascade deletes its messages)
            conversationDao.deleteById(tempConversationId)

            // Post result to the original conversation
            if (conversationId != null && result.isSuccess) {
                val summary = result.getOrNull() ?: ""
                messageStore.insert(
                    MessageRecord(
                        id = UUID.randomUUID().toString(),
                        conversationId = conversationId,
                        role = "assistant",
                        content = "[Scheduled Task] $instruction\n\n$summary"
                    )
                )
                // Update conversation metadata so the new message is visible
                conversationDao.getConversationOnce(conversationId)?.let { conv ->
                    conversationDao.update(conv.copy(
                        updatedAt = System.currentTimeMillis(),
                        messageCount = conv.messageCount + 1,
                        lastMessagePreview = summary.take(100)
                    ))
                }
            }

            result
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
