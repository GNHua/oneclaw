package com.tomandy.palmclaw.plugin

import android.util.Log
import com.tomandy.palmclaw.agent.AgentCoordinator
import com.tomandy.palmclaw.agent.MessageStore
import com.tomandy.palmclaw.agent.ToolExecutor
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.agent.profile.AgentProfileRepository
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.service.MemoryBootstrap
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.skill.SystemPromptBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

class DelegateAgentPlugin(
    private val agentProfileRepository: AgentProfileRepository,
    private val llmClientProvider: LlmClientProvider,
    private val toolRegistry: ToolRegistry,
    private val messageStore: MessageStore,
    private val modelPreferences: ModelPreferences,
    private val database: AppDatabase,
    private val skillRepository: SkillRepository,
    private val filesDir: File
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "delegate_to_agent" -> delegateToAgent(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun delegateToAgent(arguments: JsonObject): ToolResult {
        val agentName = arguments["agent"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: agent")
        val task = arguments["task"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: task")

        Log.d(TAG, "Delegating to agent '$agentName': ${task.take(100)}")

        agentProfileRepository.reload()
        val profile = agentProfileRepository.findByName(agentName)
            ?: return ToolResult.Failure("Agent profile '$agentName' not found")

        val tempConversationId = "delegate_${agentName}_${System.currentTimeMillis()}"
        val conversationDao = database.conversationDao()
        conversationDao.insert(
            ConversationEntity(
                id = tempConversationId,
                title = "[Delegation] $agentName: ${task.take(40)}",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                messageCount = 0,
                lastMessagePreview = ""
            )
        )

        try {
            // Create isolated tool registry to avoid conflicts with parent agent
            val toolFilter = profile.allowedTools?.toSet()
            val subToolRegistry = toolRegistry.copyFiltered { tool ->
                tool.definition.name != "delegate_to_agent" &&
                    (toolFilter == null || tool.definition.name in toolFilter)
            }
            val subToolExecutor = ToolExecutor(subToolRegistry, messageStore)

            val selectedModel = profile.model
                ?: modelPreferences.getSelectedModel() ?: ""

            val coordinator = AgentCoordinator(
                clientProvider = { llmClientProvider.getCurrentLlmClient() },
                toolRegistry = subToolRegistry,
                toolExecutor = subToolExecutor,
                messageStore = messageStore,
                conversationId = tempConversationId,
                toolFilter = toolFilter
            )

            val systemPrompt = buildSubAgentSystemPrompt(profile.systemPrompt)

            val result = coordinator.execute(
                userMessage = task,
                systemPrompt = systemPrompt,
                model = selectedModel,
                maxIterations = modelPreferences.getMaxIterations().coerceAtMost(50),
                temperature = modelPreferences.getTemperature()
            )

            coordinator.cleanup()

            return result.fold(
                onSuccess = { response ->
                    Log.d(TAG, "Agent '$agentName' completed successfully")
                    ToolResult.Success(response)
                },
                onFailure = { error ->
                    Log.e(TAG, "Agent '$agentName' failed: ${error.message}", error)
                    ToolResult.Failure("Agent '$agentName' failed: ${error.message}", error)
                }
            )
        } finally {
            // TODO: Before deleting, collect sub-agent's intermediate messages
            //  (tool calls, tool results, reasoning) and return them alongside the
            //  final result so the UI can show them in a collapsible block.
            conversationDao.deleteById(tempConversationId)
        }
    }

    private fun buildSubAgentSystemPrompt(basePrompt: String): String {
        skillRepository.reload()
        val enabledSkills = skillRepository.getEnabledSkills()
        val workspaceRoot = File(filesDir, "workspace")
        val memoryContext = MemoryBootstrap.loadMemoryContext(workspaceRoot)
        return SystemPromptBuilder.buildFullSystemPrompt(
            basePrompt = basePrompt,
            skills = enabledSkills,
            memoryContext = memoryContext
        )
    }

    companion object {
        private const val TAG = "DelegateAgentPlugin"
    }
}
