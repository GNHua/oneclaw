package com.tomandy.palmclaw.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.agent.profile.AgentProfileEntry
import com.tomandy.palmclaw.agent.profile.AgentProfileParser
import com.tomandy.palmclaw.agent.profile.AgentProfileRepository
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.llm.LlmProvider
import com.tomandy.palmclaw.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

class AgentProfilesViewModel(
    private val agentProfileRepository: AgentProfileRepository,
    private val modelPreferences: ModelPreferences,
    private val toolRegistry: ToolRegistry,
    private val skillRepository: SkillRepository,
    private val llmClientProvider: LlmClientProvider,
    private val userAgentsDir: File
) : ViewModel() {

    val profiles: StateFlow<List<AgentProfileEntry>> = agentProfileRepository.profiles

    init {
        agentProfileRepository.reload()
    }

    fun getAvailableToolNames(): List<String> {
        return toolRegistry.getToolDefinitions().map { it.name }.sorted()
    }

    fun getAvailableSkillNames(): List<String> {
        skillRepository.reload()
        return skillRepository.skills.value.map { it.metadata.name }.sorted()
    }

    suspend fun getAvailableModels(): List<Pair<String, LlmProvider>> {
        return llmClientProvider.getAvailableModels()
    }

    fun saveProfile(
        name: String,
        description: String,
        systemPrompt: String,
        model: String?,
        allowedTools: List<String>?,
        enabledSkills: List<String>?
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val content = buildAgentMd(name, description, model, allowedTools, enabledSkills, systemPrompt)
                AgentProfileParser.parse(content)
                userAgentsDir.mkdirs()
                File(userAgentsDir, "$name.md").writeText(content)
                agentProfileRepository.reload()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save agent profile: ${e.message}")
            }
        }
    }

    fun deleteProfile(profileName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val agentFile = File(userAgentsDir, "$profileName.md")
            if (agentFile.exists()) {
                agentFile.delete()
            }
            agentProfileRepository.reload()
            if (modelPreferences.getActiveAgent() == profileName) {
                modelPreferences.saveActiveAgent(null)
            }
        }
    }

    fun findProfile(name: String): AgentProfileEntry? {
        return agentProfileRepository.findByName(name)
    }

    fun loadRawContent(profile: AgentProfileEntry): String? {
        return agentProfileRepository.loadRawContent(profile)
    }

    private fun buildAgentMd(
        name: String,
        description: String,
        model: String?,
        allowedTools: List<String>?,
        enabledSkills: List<String>?,
        systemPrompt: String
    ): String = buildString {
        appendLine("---")
        appendLine("name: $name")
        appendLine("description: $description")
        if (model != null) appendLine("model: $model")
        if (allowedTools != null) {
            appendLine("allowed-tools: [${allowedTools.joinToString(", ") { "\"$it\"" }}]")
        }
        if (enabledSkills != null) {
            appendLine("enabled-skills: [${enabledSkills.joinToString(", ") { "\"$it\"" }}]")
        }
        appendLine("---")
        appendLine()
        append(systemPrompt)
    }

    companion object {
        private const val TAG = "AgentProfilesVM"
    }
}
