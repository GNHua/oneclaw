package com.tomandy.palmclaw.plugin

import android.content.Context
import com.tomandy.palmclaw.agent.ToolRegistry
import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginEngine
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.llm.LlmClientProvider
import com.tomandy.palmclaw.pluginmanager.BuiltInPluginManager
import com.tomandy.palmclaw.pluginmanager.InstallPluginTool
import com.tomandy.palmclaw.pluginmanager.PluginPreferences
import com.tomandy.palmclaw.pluginmanager.UserPluginManager
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPlugin
import com.tomandy.palmclaw.scheduler.plugin.SchedulerPluginMetadata
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.dao.MessageDao
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.skill.SkillPreferences
import com.tomandy.palmclaw.skill.SkillRepository
import com.tomandy.palmclaw.workspace.WorkspacePlugin
import com.tomandy.palmclaw.workspace.WorkspacePluginMetadata

class PluginCoordinator(
    private val context: Context,
    private val pluginEngine: PluginEngine,
    private val toolRegistry: ToolRegistry,
    private val pluginPreferences: PluginPreferences,
    private val credentialVault: CredentialVault,
    private val builtInPluginManager: BuiltInPluginManager,
    private val userPluginManager: UserPluginManager,
    private val llmClientProvider: LlmClientProvider,
    private val modelPreferences: ModelPreferences,
    private val skillRepository: SkillRepository,
    private val skillPreferences: SkillPreferences,
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) {
    suspend fun initializePlugins() {
        registerBuiltInPlugins()
        registerInstallPluginTool()
        builtInPluginManager.loadBuiltInPlugins()
        userPluginManager.loadAllUserPlugins()
        skillRepository.loadAll()
    }

    fun setPluginEnabled(pluginId: String, enabled: Boolean) {
        pluginPreferences.setPluginEnabled(pluginId, enabled)
        if (enabled) {
            pluginEngine.getLoadedPlugin(pluginId)?.let { loadedPlugin ->
                toolRegistry.registerPlugin(loadedPlugin)
            }
        } else {
            toolRegistry.unregisterPlugin(pluginId)
        }
    }

    private suspend fun registerBuiltInPlugins() {
        try {
            val schedulerPlugin = SchedulerPlugin()
            val pluginContext = PluginContext.create(
                androidContext = context,
                pluginId = "scheduler",
                credentialVault = credentialVault
            )

            schedulerPlugin.onLoad(pluginContext)

            val loadedPlugin = LoadedPlugin(
                metadata = SchedulerPluginMetadata.get(),
                instance = schedulerPlugin
            )

            toolRegistry.registerPlugin(loadedPlugin)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register WorkspacePlugin
        try {
            val workspacePlugin = WorkspacePlugin()
            val workspaceContext = PluginContext.create(
                androidContext = context,
                pluginId = "workspace",
                credentialVault = credentialVault
            )
            workspacePlugin.onLoad(workspaceContext)
            toolRegistry.registerPlugin(
                LoadedPlugin(
                    metadata = WorkspacePluginMetadata.get(),
                    instance = workspacePlugin
                )
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Register config plugin
        val configRegistry = buildConfigRegistry()
        val configPlugin = ConfigPlugin(configRegistry)
        toolRegistry.registerPlugin(
            LoadedPlugin(
                metadata = ConfigPluginMetadata.get(),
                instance = configPlugin
            )
        )

        // Register search plugin
        val searchPlugin = SearchPlugin(messageDao, conversationDao)
        toolRegistry.registerPlugin(
            LoadedPlugin(
                metadata = SearchPluginMetadata.get(),
                instance = searchPlugin
            )
        )
    }

    private fun buildConfigRegistry(): ConfigRegistry {
        val registry = ConfigRegistry()

        registry.register(
            ConfigEntry(
                key = "model",
                displayName = "LLM Model",
                description = "Active model for LLM completions. Supports fuzzy matching.",
                type = ConfigType.StringType,
                getter = {
                    val model = modelPreferences.getSelectedModel()
                        ?: "not set (using provider default)"
                    val provider = llmClientProvider.selectedProvider.value.displayName
                    val available = llmClientProvider.getAvailableModels()
                    val modelList = available.joinToString(", ") { it.first }
                    "$model (provider: $provider)\n    Available: $modelList"
                },
                setter = {},
                customHandler = { value -> handleModelChange(value) }
            )
        )

        registry.register(
            ConfigEntry(
                key = "max_iterations",
                displayName = "Max Iterations",
                description = "Maximum ReAct loop iterations per message (1-500, default 200).",
                type = ConfigType.IntType(min = 1, max = 500),
                getter = { modelPreferences.getMaxIterations().toString() },
                setter = { modelPreferences.saveMaxIterations(it.toInt()) }
            )
        )

        registry.register(
            ConfigEntry(
                key = "temperature",
                displayName = "Temperature",
                description = "LLM sampling temperature. Lower = more deterministic, higher = more creative (0.0-2.0, default 0.7).",
                type = ConfigType.StringType,
                getter = { modelPreferences.getTemperature().toString() },
                setter = {},
                customHandler = { value ->
                    val floatVal = value.toFloatOrNull()
                        ?: return@ConfigEntry ToolResult.Failure(
                            "Invalid value \"$value\" for temperature. Expected a number."
                        )
                    if (floatVal !in 0f..2f) {
                        return@ConfigEntry ToolResult.Failure(
                            "temperature must be between 0.0 and 2.0. Got: $floatVal"
                        )
                    }
                    modelPreferences.saveTemperature(floatVal)
                    ToolResult.Success("Temperature changed to $floatVal. Takes effect on the next message.")
                }
            )
        )

        registry.register(
            ConfigEntry(
                key = "system_prompt",
                displayName = "System Prompt",
                description = "Base system prompt sent to the LLM. Defines the assistant's persona and behavior.",
                type = ConfigType.StringType,
                getter = { modelPreferences.getSystemPrompt() },
                setter = { modelPreferences.saveSystemPrompt(it) }
            )
        )

        registry.register(
            ConfigEntry(
                key = "plugins",
                displayName = "Plugins",
                description = "Enable/disable plugins. Set value as \"plugin_id:true\" or \"plugin_id:false\".",
                type = ConfigType.StringType,
                getter = {
                    val all = pluginEngine.getAllPlugins()
                    if (all.isEmpty()) {
                        "No plugins loaded."
                    } else {
                        all.joinToString("\n    ") { p ->
                            val enabled = pluginPreferences.isPluginEnabled(p.metadata.id)
                            "${p.metadata.id}: ${if (enabled) "enabled" else "disabled"} - ${p.metadata.name}"
                        }
                    }
                },
                setter = {},
                customHandler = { value -> handlePluginToggle(value) }
            )
        )

        registry.register(
            ConfigEntry(
                key = "skills",
                displayName = "Skills",
                description = "Enable/disable skills. Set value as \"skill_name:true\" or \"skill_name:false\".",
                type = ConfigType.StringType,
                getter = {
                    val all = skillRepository.skills.value
                    if (all.isEmpty()) {
                        "No skills loaded."
                    } else {
                        all.joinToString("\n    ") { s ->
                            val enabled = skillPreferences.isSkillEnabled(s.metadata.name)
                            "${s.metadata.name}: ${if (enabled) "enabled" else "disabled"} - ${s.metadata.description}"
                        }
                    }
                },
                setter = {},
                customHandler = { value -> handleSkillToggle(value) }
            )
        )

        return registry
    }

    private suspend fun handleModelChange(value: String): ToolResult {
        val available = llmClientProvider.getAvailableModels()
        if (available.isEmpty()) {
            return ToolResult.Failure("No models available. No API keys are configured.")
        }

        val exactMatch = available.find { (model, _) -> model == value }
        if (exactMatch != null) {
            val oldModel = modelPreferences.getSelectedModel() ?: "default"
            llmClientProvider.setModelAndProvider(exactMatch.first)
            return ToolResult.Success(
                "Model changed from $oldModel to ${exactMatch.first} " +
                    "(provider: ${exactMatch.second.displayName}). " +
                    "The change takes effect on the next message."
            )
        }

        val fuzzyMatches = available.filter { (model, _) ->
            model.contains(value, ignoreCase = true) ||
                value.contains(model, ignoreCase = true)
        }

        if (fuzzyMatches.size == 1) {
            val match = fuzzyMatches.first()
            val oldModel = modelPreferences.getSelectedModel() ?: "default"
            llmClientProvider.setModelAndProvider(match.first)
            return ToolResult.Success(
                "Model changed from $oldModel to ${match.first} " +
                    "(provider: ${match.second.displayName}). " +
                    "The change takes effect on the next message."
            )
        }

        if (fuzzyMatches.size > 1) {
            val options = fuzzyMatches.joinToString(", ") { it.first }
            return ToolResult.Failure(
                "Ambiguous model name \"$value\". Multiple matches: $options. " +
                    "Please use the exact model name."
            )
        }

        val allModels = available.joinToString(", ") { it.first }
        return ToolResult.Failure(
            "Unknown model \"$value\". Available models: $allModels"
        )
    }

    private fun handlePluginToggle(value: String): ToolResult {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) {
            return ToolResult.Failure(
                "Invalid format. Use \"plugin_id:true\" or \"plugin_id:false\"."
            )
        }
        val pluginId = parts[0].trim()
        val enabled = when (parts[1].trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> return ToolResult.Failure(
                "Invalid value \"${parts[1]}\". Use true/false."
            )
        }
        val plugin = pluginEngine.getLoadedPlugin(pluginId)
            ?: return ToolResult.Failure("Unknown plugin: \"$pluginId\".")
        setPluginEnabled(pluginId, enabled)
        val state = if (enabled) "enabled" else "disabled"
        return ToolResult.Success(
            "Plugin \"${plugin.metadata.name}\" ($pluginId) $state. Takes effect on the next message."
        )
    }

    private fun handleSkillToggle(value: String): ToolResult {
        val parts = value.split(":", limit = 2)
        if (parts.size != 2) {
            return ToolResult.Failure(
                "Invalid format. Use \"skill_name:true\" or \"skill_name:false\"."
            )
        }
        val skillName = parts[0].trim()
        val enabled = when (parts[1].trim().lowercase()) {
            "true", "1", "yes", "on" -> true
            "false", "0", "no", "off" -> false
            else -> return ToolResult.Failure(
                "Invalid value \"${parts[1]}\". Use true/false."
            )
        }
        val skill = skillRepository.skills.value.find {
            it.metadata.name.equals(skillName, ignoreCase = true)
        } ?: return ToolResult.Failure("Unknown skill: \"$skillName\".")
        skillPreferences.setSkillEnabled(skill.metadata.name, enabled)
        val state = if (enabled) "enabled" else "disabled"
        return ToolResult.Success(
            "Skill \"${skill.metadata.name}\" $state. Takes effect on the next message."
        )
    }

    private fun registerInstallPluginTool() {
        val installTool = InstallPluginTool(userPluginManager)
        val loadedPlugin = LoadedPlugin(
            metadata = InstallPluginTool.metadata(),
            instance = installTool
        )
        toolRegistry.registerPlugin(loadedPlugin)
    }
}
