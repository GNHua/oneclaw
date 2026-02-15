package com.tomandy.palmclaw.plugin

import com.tomandy.palmclaw.data.ModelPreferences
import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.llm.LlmClientProvider
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

class ConfigPlugin(
    private val llmClientProvider: LlmClientProvider,
    private val modelPreferences: ModelPreferences
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {
        // No initialization needed -- dependencies injected via constructor
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "configure_app" -> configureApp(arguments)
            "get_app_config" -> getAppConfig()
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun configureApp(arguments: JsonObject): ToolResult {
        val setting = arguments["setting"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: setting")
        val value = arguments["value"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: value")

        return when (setting) {
            "model" -> changeModel(value)
            "max_iterations" -> changeMaxIterations(value)
            else -> ToolResult.Failure(
                "Unknown setting: $setting. Supported settings: model, max_iterations"
            )
        }
    }

    private suspend fun changeModel(value: String): ToolResult {
        val available = llmClientProvider.getAvailableModels()
        if (available.isEmpty()) {
            return ToolResult.Failure("No models available. No API keys are configured.")
        }

        // Exact match first
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

        // Fuzzy match: check if input is a substring of any model name
        val fuzzyMatches = available.filter { (model, _) ->
            model.contains(value, ignoreCase = true) || value.contains(model, ignoreCase = true)
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

    private fun changeMaxIterations(value: String): ToolResult {
        val intValue = value.toIntOrNull()
            ?: return ToolResult.Failure(
                "Invalid value \"$value\". max_iterations must be an integer."
            )

        if (intValue !in 1..500) {
            return ToolResult.Failure(
                "max_iterations must be between 1 and 500. Got: $intValue"
            )
        }

        val oldValue = modelPreferences.getMaxIterations()
        modelPreferences.saveMaxIterations(intValue)
        return ToolResult.Success(
            "max_iterations changed from $oldValue to $intValue. " +
                "The change takes effect on the next message."
        )
    }

    private suspend fun getAppConfig(): ToolResult {
        val currentModel = modelPreferences.getSelectedModel() ?: "not set (using provider default)"
        val currentProvider = llmClientProvider.selectedProvider.value.displayName
        val maxIterations = modelPreferences.getMaxIterations()
        val available = llmClientProvider.getAvailableModels()

        val modelList = if (available.isEmpty()) {
            "  (no API keys configured)"
        } else {
            available.joinToString("\n") { (model, provider) ->
                "  - $model (${provider.displayName})"
            }
        }

        return ToolResult.Success(
            """Current configuration:
                |  Model: $currentModel
                |  Provider: $currentProvider
                |  Max iterations: $maxIterations
                |
                |Available models:
                |$modelList
            """.trimMargin()
        )
    }
}
