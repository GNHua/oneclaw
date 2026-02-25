package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.agent.profile.AgentProfileRepository
import com.tomandy.oneclaw.agent.profile.DEFAULT_MAX_ITERATIONS
import com.tomandy.oneclaw.agent.profile.DEFAULT_TEMPERATURE
import com.tomandy.oneclaw.data.AppDatabase
import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.llm.LlmProvider
import com.tomandy.oneclaw.scheduler.AgentExecutor
import com.tomandy.oneclaw.scheduler.TaskExecutionResult
import com.tomandy.oneclaw.service.MemoryBootstrap
import com.tomandy.oneclaw.skill.SkillRepository
import com.tomandy.oneclaw.skill.SystemPromptBuilder
import com.tomandy.oneclaw.skill.SlashCommandRouter
import java.io.File
import java.util.UUID

private val SKILL_REF_PATTERN = Regex("""/skill:\S+""")

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

    private val slashCommandRouter = SlashCommandRouter(skillRepository)

    /**
     * Expand /skill:name references in the instruction to their full skill body.
     * This mirrors what ChatViewModel does for interactive slash commands.
     */
    private fun resolveSkillReferences(instruction: String): String {
        val refs = SKILL_REF_PATTERN.findAll(instruction).toList()
        if (refs.isEmpty()) return instruction

        val skillBlocks = StringBuilder()
        for (ref in refs) {
            val command = slashCommandRouter.parse(ref.value) ?: continue
            val skill = slashCommandRouter.resolve(command) ?: continue
            val body = skillRepository.loadBody(skill) ?: continue
            val location = skill.filePath ?: "skills/${skill.metadata.name}/SKILL.md"
            val baseDir = skill.baseDir ?: "skills/${skill.metadata.name}"
            skillBlocks.appendLine("<skill name=\"${skill.metadata.name}\" location=\"$location\">")
            skillBlocks.appendLine("References are relative to $baseDir.")
            skillBlocks.appendLine()
            skillBlocks.appendLine(body)
            skillBlocks.appendLine("</skill>")
            skillBlocks.appendLine()
        }

        return if (skillBlocks.isEmpty()) {
            instruction
        } else {
            skillBlocks.toString() + instruction
        }
    }

    override suspend fun executeTask(
        instruction: String,
        cronjobId: String,
        triggerTime: Long,
        conversationId: String?,
        agentName: String?
    ): Result<TaskExecutionResult> {
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

            // Resolve agent profile: task-specific -> global active -> "main"
            val profile = agentName?.let {
                agentProfileRepository.findByName(it)
            } ?: modelPreferences.getActiveAgent()?.let {
                agentProfileRepository.findByName(it)
            } ?: agentProfileRepository.findByName("main")

            // Create per-coordinator snapshot to avoid conflicts with concurrent executions
            val snapshotRegistry = toolRegistry.snapshot()
            val snapshotToolExecutor = ToolExecutor(snapshotRegistry, messageStore)

            // Get preferences
            val model = profile?.model
                ?: modelPreferences.getSelectedModel() ?: ""

            val nativeWebSearchEnabled = when (modelPreferences.getWebSearchMode()) {
                ModelPreferences.WebSearchMode.PROVIDER_NATIVE -> true
                ModelPreferences.WebSearchMode.AUTO -> llmClientProvider.isNativeSearchSupported()
                ModelPreferences.WebSearchMode.TAVILY_BRAVE -> false
            }

            val coordinator = AgentCoordinator(
                clientProvider = { llmClientProvider.getCurrentLlmClient() },
                toolRegistry = snapshotRegistry,
                toolExecutor = snapshotToolExecutor,
                messageStore = messageStore,
                conversationId = tempConversationId,
                contextWindow = LlmProvider.getContextWindow(model),
                nativeWebSearchEnabled = nativeWebSearchEnabled
            )

            try {
                // Execute with scheduled context
                val context = ExecutionContext.Scheduled(
                    cronjobId = cronjobId,
                    triggerTime = triggerTime
                )
                val temperature = profile?.temperature ?: DEFAULT_TEMPERATURE
                val maxIter = profile?.maxIterations ?: DEFAULT_MAX_ITERATIONS

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

                // Resolve /skill:* references in the instruction so the agent
                // receives the full skill body instead of an opaque slash command.
                val resolvedInstruction = resolveSkillReferences(instruction)

                // Persist user message to the temp conversation
                messageStore.insert(
                    MessageRecord(
                        id = UUID.randomUUID().toString(),
                        conversationId = tempConversationId,
                        role = "user",
                        content = resolvedInstruction
                    )
                )

                val result = coordinator.execute(
                    userMessage = resolvedInstruction,
                    systemPrompt = systemPrompt,
                    model = model,
                    maxIterations = if (maxIter >= 500) Int.MAX_VALUE else maxIter,
                    temperature = temperature,
                    context = context
                )

                // Persist final assistant response to the temp conversation
                if (result.isSuccess) {
                    val summary = result.getOrNull() ?: ""
                    messageStore.insert(
                        MessageRecord(
                            id = UUID.randomUUID().toString(),
                            conversationId = tempConversationId,
                            role = "assistant",
                            content = summary
                        )
                    )
                }

                result.map { summary ->
                    TaskExecutionResult(
                        summary = summary,
                        conversationId = tempConversationId
                    )
                }
            } finally {
                coordinator.cleanup()
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }
}
