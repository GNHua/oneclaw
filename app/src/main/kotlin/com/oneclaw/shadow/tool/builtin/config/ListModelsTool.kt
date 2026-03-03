package com.oneclaw.shadow.tool.builtin.config

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.tool.engine.Tool

class ListModelsTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "list_models",
        description = "List all models for a specific provider, showing model ID, display name, " +
            "source (DYNAMIC/PRESET/MANUAL), whether it is the global default, and context window size.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider whose models to list. Must be obtained from list_providers (e.g. 'provider-anthropic', 'provider-openai', 'provider-gemini')"
                )
            ),
            required = listOf("provider_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providerId = (parameters["provider_id"] as? String)?.trim()
        if (providerId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'provider_id' is required.")
        }

        val provider = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        val models = providerRepository.getModelsForProvider(providerId)
        if (models.isEmpty()) {
            return ToolResult.success(
                "No models found for provider '${provider.name}'. " +
                "Use fetch_models to refresh the model list from the API, " +
                "or add_model to add a model manually."
            )
        }

        val sb = StringBuilder("Models for provider '${provider.name}' (${models.size}):\n")
        models.forEachIndexed { index, model ->
            sb.append("\n${index + 1}. ${model.displayName ?: model.id}")
            sb.append("\n   Model ID: ${model.id}")
            sb.append("\n   Source: ${model.source}")
            sb.append("\n   Default: ${model.isDefault}")
            if (model.contextWindowSize != null) {
                sb.append("\n   Context Window: ${model.contextWindowSize} tokens")
            }
            sb.append("\n")
        }
        return ToolResult.success(sb.toString())
    }
}
