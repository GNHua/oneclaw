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
import com.tomandy.palmclaw.security.CredentialVault
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
    private val skillRepository: SkillRepository
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

    private fun registerInstallPluginTool() {
        val installTool = InstallPluginTool(userPluginManager)
        val loadedPlugin = LoadedPlugin(
            metadata = InstallPluginTool.metadata(),
            instance = installTool
        )
        toolRegistry.registerPlugin(loadedPlugin)
    }
}
