package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.agent.profile.AgentProfileRepository
import com.tomandy.oneclaw.data.AppDatabase
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.scheduler.AgentExecutor
import com.tomandy.oneclaw.service.MemoryBootstrap
import com.tomandy.oneclaw.skill.SkillRepository
import com.tomandy.oneclaw.skill.SystemPromptBuilder
import java.io.File
import java.util.UUID

class ScheduledAgentExecutor(
    private val database: AppDatabase,
    private val llmClientProvider: LlmClientProvider,
    private val toolRegistry: ToolRegistry,
    private val messageStore: MessageStore,
    private val modelPreferences: ModelPreferences,
    private val skillRepository: SkillRepository,
    private val agentProfileRepository: AgentProfileRepository,
    private val filesDir: File
) : AgentExecutor {

    override suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long,
        conversationId: String?
    ): Result<String> {
        return try {
            skillRepository.reload()
            agentProfileRepository.reload()

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

            // Resolve active agent profile for prompt and model
            val activeAgentName = modelPreferences.getActiveAgent()
            val profile = activeAgentName?.let {
                agentProfileRepository.findByName(it)
            } ?: agentProfileRepository.findByName("main")

            // Create per-coordinator snapshot to avoid conflicts with concurrent executions
            val snapshotRegistry = toolRegistry.snapshot()
            val snapshotToolExecutor = ToolExecutor(snapshotRegistry, messageStore)

            val coordinator = AgentCoordinator(
                clientProvider = { llmClientProvider.getCurrentLlmClient() },
                toolRegistry = snapshotRegistry,
                toolExecutor = snapshotToolExecutor,
                messageStore = messageStore,
                conversationId = tempConversationId
            )

            try {
                // Execute with scheduled context
                val context = ExecutionContext.Scheduled(
                    cronjobId = cronjobId,
                    triggerTime = triggerTime
                )

                // Get preferences
                val model = profile?.model
                    ?: modelPreferences.getSelectedModel() ?: ""
                val temperature = profile?.temperature ?: modelPreferences.getTemperature()
                val maxIter = profile?.maxIterations ?: modelPreferences.getMaxIterations()

                // Build full system prompt with skills and memory
                val basePrompt = profile?.systemPrompt
                    ?: modelPreferences.getSystemPrompt()
                val enabledSkills = skillRepository.getEnabledSkills()
                val workspaceRoot = File(filesDir, "workspace")
                val memoryContext = MemoryBootstrap.loadMemoryContext(workspaceRoot)
                val systemPrompt = SystemPromptBuilder.buildFullSystemPrompt(
                    basePrompt = basePrompt,
                    skills = enabledSkills,
                    memoryContext = memoryContext
                )

                val result = coordinator.execute(
                    userMessage = instruction,
                    systemPrompt = systemPrompt,
                    model = model,
                    maxIterations = if (maxIter >= 500) Int.MAX_VALUE else maxIter,
                    temperature = temperature,
                    context = context
                )

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
            } finally {
                coordinator.cleanup()
                conversationDao.deleteById(tempConversationId)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
