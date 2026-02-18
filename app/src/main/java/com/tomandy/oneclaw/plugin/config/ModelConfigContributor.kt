package com.tomandy.oneclaw.plugin.config

import com.tomandy.oneclaw.data.ModelPreferences
import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.llm.LlmClientProvider
import com.tomandy.oneclaw.plugin.ConfigContributor
import com.tomandy.oneclaw.plugin.ConfigEntry
import com.tomandy.oneclaw.plugin.ConfigType

class ModelConfigContributor(
    private val llmClientProvider: LlmClientProvider,
    private val modelPreferences: ModelPreferences
) : ConfigContributor {

    override fun contribute(): List<ConfigEntry> = listOf(
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
}
