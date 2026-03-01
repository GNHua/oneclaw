# RFC-036: Configuration Management Tools

## Document Information
- **RFC ID**: RFC-036
- **Related PRD**: [FEAT-036 (Configuration Management Tools)](../../prd/features/FEAT-036-config-tools.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-002 (Agent)](RFC-002-agent.md), [RFC-003 (Provider)](RFC-003-provider.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClawShadow currently requires users to navigate to various Settings screens to configure providers, models, agents, themes, tool states, and environment variables. The app already has tools for creating agents (`create_agent`) and managing scheduled tasks (RFC-027), but lacks tools for managing the rest of the configuration. This means users must constantly switch between the chat and Settings UI to adjust configuration.

This RFC adds 17 built-in tools that enable the AI agent to read and modify all app configuration through conversation, with the sole exception of API key/authentication credential management which remains UI-only for security.

### Goals

1. Add provider management tools (list, create, update, delete)
2. Add model management tools (list, fetch, set default, add/delete manual)
3. Add agent management tools (list, update, delete)
4. Add app settings tools (get, set -- including theme)
5. Add tool state management tools (list, enable/disable)
6. Add environment variable management tool (list, set, delete)
7. Register all tools in ToolModule as BUILTIN

### Non-Goals

- API key or authentication credential management (hard security constraint)
- Skill management (separate feature)
- Memory management (separate feature)
- Session management (separate feature)
- Backup/sync management (separate feature)

## Technical Design

### Architecture Overview

```
+------------------------------------------------------------------+
|                    Chat Layer (RFC-001)                            |
|  SendMessageUseCase                                               |
|       |  tool call: list_providers / update_agent / set_config    |
|       v                                                           |
+------------------------------------------------------------------+
|                  Tool Execution Engine (RFC-004)                   |
|       |                                                           |
|       v                                                           |
|  +---------------------------------------------------------------+|
|  |                   ToolRegistry                                 ||
|  |                                                                ||
|  |  Provider Tools -----> ProviderRepository                      ||
|  |                   +--> ApiKeyStorage (read-only: hasKey check) ||
|  |                                                                ||
|  |  Model Tools -------> ProviderRepository                      ||
|  |                                                                ||
|  |  Agent Tools -------> AgentRepository                          ||
|  |                                                                ||
|  |  Settings Tools ----> SettingsRepository + ThemeManager        ||
|  |                                                                ||
|  |  Tool State Tools --> ToolEnabledStateStore + ToolRegistry     ||
|  |                                                                ||
|  |  Env Var Tool ------> EnvironmentVariableStore                 ||
|  +---------------------------------------------------------------+|
+------------------------------------------------------------------+
```

### Core Components

**New:**
1. `ListProvidersTool` -- list all providers
2. `CreateProviderTool` -- create a new provider
3. `UpdateProviderTool` -- partial update a provider
4. `DeleteProviderTool` -- delete a provider
5. `ListModelsTool` -- list models for a provider
6. `FetchModelsTool` -- refresh models from API
7. `SetDefaultModelTool` -- set global default model
8. `AddModelTool` -- add a manual model
9. `DeleteModelTool` -- delete a manual model
10. `ListAgentsTool` -- list all agents
11. `UpdateAgentTool` -- partial update an agent
12. `DeleteAgentTool` -- delete an agent
13. `GetConfigTool` -- read an app setting
14. `SetConfigTool` -- write an app setting (including theme)
15. `ListToolStatesTool` -- list tools with enabled/disabled status
16. `SetToolEnabledTool` -- enable/disable a tool or group
17. `ManageEnvVarTool` -- list, set, or delete environment variables

**Modified:**
18. `ToolModule` -- register all 17 new tools

**Reused (unchanged):**
19. `ProviderRepository` -- existing provider/model data access
20. `AgentRepository` -- existing agent data access
21. `SettingsRepository` -- existing settings data access
22. `ThemeManager` -- existing theme management
23. `ToolEnabledStateStore` -- existing tool state persistence
24. `EnvironmentVariableStore` -- existing env var storage
25. `ApiKeyStorage` -- existing API key storage (read-only access: `hasApiKey`)

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/kotlin/com/oneclaw/shadow/
├── tool/
│   └── builtin/
│       └── config/
│           ├── ListProvidersTool.kt        # NEW
│           ├── CreateProviderTool.kt       # NEW
│           ├── UpdateProviderTool.kt       # NEW
│           ├── DeleteProviderTool.kt       # NEW
│           ├── ListModelsTool.kt           # NEW
│           ├── FetchModelsTool.kt          # NEW
│           ├── SetDefaultModelTool.kt      # NEW
│           ├── AddModelTool.kt             # NEW
│           ├── DeleteModelTool.kt          # NEW
│           ├── ListAgentsTool.kt           # NEW
│           ├── UpdateAgentTool.kt          # NEW
│           ├── DeleteAgentTool.kt          # NEW
│           ├── GetConfigTool.kt            # NEW
│           ├── SetConfigTool.kt            # NEW
│           ├── ListToolStatesTool.kt       # NEW
│           ├── SetToolEnabledTool.kt       # NEW
│           └── ManageEnvVarTool.kt         # NEW
└── di/
    └── ToolModule.kt                       # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
└── tool/
    └── builtin/
        └── config/
            ├── ListProvidersToolTest.kt     # NEW
            ├── CreateProviderToolTest.kt    # NEW
            ├── UpdateProviderToolTest.kt    # NEW
            ├── DeleteProviderToolTest.kt    # NEW
            ├── ListModelsToolTest.kt        # NEW
            ├── FetchModelsToolTest.kt       # NEW
            ├── SetDefaultModelToolTest.kt   # NEW
            ├── AddModelToolTest.kt          # NEW
            ├── DeleteModelToolTest.kt       # NEW
            ├── ListAgentsToolTest.kt        # NEW
            ├── UpdateAgentToolTest.kt       # NEW
            ├── DeleteAgentToolTest.kt       # NEW
            ├── GetConfigToolTest.kt         # NEW
            ├── SetConfigToolTest.kt         # NEW
            ├── ListToolStatesToolTest.kt    # NEW
            ├── SetToolEnabledToolTest.kt    # NEW
            └── ManageEnvVarToolTest.kt      # NEW
```

All config tools are placed in a `config/` subdirectory under `tool/builtin/` to keep the builtin directory organized.

---

## Provider Tools

### ListProvidersTool

```kotlin
/**
 * Located in: tool/builtin/config/ListProvidersTool.kt
 */
class ListProvidersTool(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage
) : Tool {

    override val definition = ToolDefinition(
        name = "list_providers",
        description = "List all configured AI providers with their details including " +
            "ID, name, type, API base URL, active status, and whether an API key is set.",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providers = providerRepository.getAllProviders().first()
        if (providers.isEmpty()) {
            return ToolResult.success("No providers configured.")
        }

        val sb = StringBuilder("Found ${providers.size} provider(s):\n")
        providers.forEachIndexed { index, provider ->
            val hasKey = apiKeyStorage.hasApiKey(provider.id)
            sb.append("\n${index + 1}. [id: ${provider.id}] ${provider.name}")
            sb.append("\n   Type: ${provider.type}")
            sb.append("\n   API Base URL: ${provider.apiBaseUrl}")
            sb.append("\n   Active: ${provider.isActive}")
            sb.append("\n   Pre-configured: ${provider.isPreConfigured}")
            sb.append("\n   API Key: ${if (hasKey) "configured" else "NOT SET"}")
            sb.append("\n")
        }
        return ToolResult.success(sb.toString())
    }
}
```

### CreateProviderTool

```kotlin
/**
 * Located in: tool/builtin/config/CreateProviderTool.kt
 */
class CreateProviderTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "create_provider",
        description = "Create a new AI provider. After creation, the user must set the API key " +
            "in Settings > Providers. Supported types: OPENAI, ANTHROPIC, GEMINI.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Display name for the provider (e.g., 'My OpenAI')"
                ),
                "type" to ToolParameter(
                    type = "string",
                    description = "Provider type",
                    enum = listOf("OPENAI", "ANTHROPIC", "GEMINI")
                ),
                "api_base_url" to ToolParameter(
                    type = "string",
                    description = "API base URL (e.g., 'https://api.openai.com/v1')"
                )
            ),
            required = listOf("name", "type", "api_base_url")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = (parameters["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'name' is required.")
        }

        val typeStr = (parameters["type"] as? String)?.trim()?.uppercase()
        val type = try {
            ProviderType.valueOf(typeStr ?: "")
        } catch (e: IllegalArgumentException) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'type' must be one of: OPENAI, ANTHROPIC, GEMINI."
            )
        }

        val apiBaseUrl = (parameters["api_base_url"] as? String)?.trim()
        if (apiBaseUrl.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'api_base_url' is required.")
        }

        val now = System.currentTimeMillis()
        val provider = Provider(
            id = java.util.UUID.randomUUID().toString(),
            name = name,
            type = type,
            apiBaseUrl = apiBaseUrl,
            isPreConfigured = false,
            isActive = false,
            createdAt = now,
            updatedAt = now
        )

        providerRepository.createProvider(provider)

        return ToolResult.success(
            "Provider '${provider.name}' created successfully (ID: ${provider.id}). " +
            "Please go to Settings > Providers to set the API key before using this provider."
        )
    }
}
```

### UpdateProviderTool

```kotlin
/**
 * Located in: tool/builtin/config/UpdateProviderTool.kt
 *
 * Partial update semantics: only provided fields are changed.
 */
class UpdateProviderTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "update_provider",
        description = "Update an existing provider's configuration. Only provided fields are changed; " +
            "omitted fields retain their current values. Cannot change provider type.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider to update"
                ),
                "name" to ToolParameter(
                    type = "string",
                    description = "New display name"
                ),
                "api_base_url" to ToolParameter(
                    type = "string",
                    description = "New API base URL"
                ),
                "is_active" to ToolParameter(
                    type = "boolean",
                    description = "Whether the provider is active"
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

        val existing = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        val changes = mutableListOf<String>()

        val newName = (parameters["name"] as? String)?.trim()
        if (newName != null && newName != existing.name) {
            if (newName.isEmpty()) {
                return ToolResult.error("validation_error", "Provider name cannot be empty.")
            }
            changes.add("name ('${existing.name}' -> '$newName')")
        }

        val newUrl = (parameters["api_base_url"] as? String)?.trim()
        if (newUrl != null && newUrl != existing.apiBaseUrl) {
            if (newUrl.isEmpty()) {
                return ToolResult.error("validation_error", "API base URL cannot be empty.")
            }
            changes.add("api_base_url ('${existing.apiBaseUrl}' -> '$newUrl')")
        }

        val newIsActive = parameters["is_active"] as? Boolean
        if (newIsActive != null && newIsActive != existing.isActive) {
            changes.add("is_active (${existing.isActive} -> $newIsActive)")
        }

        if (changes.isEmpty()) {
            return ToolResult.success("No changes to apply. Provider '${existing.name}' is unchanged.")
        }

        val updated = existing.copy(
            name = newName?.ifEmpty { existing.name } ?: existing.name,
            apiBaseUrl = newUrl?.ifEmpty { existing.apiBaseUrl } ?: existing.apiBaseUrl,
            isActive = newIsActive ?: existing.isActive,
            updatedAt = System.currentTimeMillis()
        )

        providerRepository.updateProvider(updated)

        return ToolResult.success(
            "Provider '${updated.name}' updated successfully. Changed: ${changes.joinToString(", ")}."
        )
    }
}
```

### DeleteProviderTool

```kotlin
/**
 * Located in: tool/builtin/config/DeleteProviderTool.kt
 */
class DeleteProviderTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "delete_provider",
        description = "Delete a provider and all its associated models. " +
            "Pre-configured providers cannot be deleted, only deactivated.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider to delete"
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

        val existing = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        if (existing.isPreConfigured) {
            return ToolResult.error(
                "permission_denied",
                "Pre-configured provider '${existing.name}' cannot be deleted. " +
                "Use update_provider to deactivate it instead."
            )
        }

        return when (val result = providerRepository.deleteProvider(providerId)) {
            is AppResult.Success -> ToolResult.success(
                "Provider '${existing.name}' and all its associated models have been deleted."
            )
            is AppResult.Error -> ToolResult.error("deletion_failed", "Failed to delete provider: ${result.message}")
        }
    }
}
```

---

## Model Tools

### ListModelsTool

```kotlin
/**
 * Located in: tool/builtin/config/ListModelsTool.kt
 */
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
                    description = "ID of the provider whose models to list"
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
```

### FetchModelsTool

```kotlin
/**
 * Located in: tool/builtin/config/FetchModelsTool.kt
 */
class FetchModelsTool(
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage
) : Tool {

    override val definition = ToolDefinition(
        name = "fetch_models",
        description = "Fetch and refresh the model list from the provider's API. " +
            "Requires an API key to be configured for the provider.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider to fetch models from"
                )
            ),
            required = listOf("provider_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providerId = (parameters["provider_id"] as? String)?.trim()
        if (providerId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'provider_id' is required.")
        }

        val provider = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        if (!apiKeyStorage.hasApiKey(providerId)) {
            return ToolResult.error(
                "api_key_required",
                "API key not configured for provider '${provider.name}'. " +
                "Please set it in Settings > Providers before fetching models."
            )
        }

        return when (val result = providerRepository.fetchModelsFromApi(providerId)) {
            is AppResult.Success -> {
                val models = result.data
                ToolResult.success(
                    "Successfully fetched ${models.size} model(s) from '${provider.name}':\n" +
                    models.joinToString("\n") { "- ${it.displayName ?: it.id}" }
                )
            }
            is AppResult.Error -> ToolResult.error(
                "fetch_failed",
                "Failed to fetch models from '${provider.name}': ${result.message}"
            )
        }
    }
}
```

### SetDefaultModelTool

```kotlin
/**
 * Located in: tool/builtin/config/SetDefaultModelTool.kt
 */
class SetDefaultModelTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "set_default_model",
        description = "Set the global default AI model used for conversations.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider that owns the model"
                ),
                "model_id" to ToolParameter(
                    type = "string",
                    description = "ID of the model to set as default"
                )
            ),
            required = listOf("provider_id", "model_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providerId = (parameters["provider_id"] as? String)?.trim()
        if (providerId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'provider_id' is required.")
        }

        val modelId = (parameters["model_id"] as? String)?.trim()
        if (modelId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'model_id' is required.")
        }

        val provider = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        val models = providerRepository.getModelsForProvider(providerId)
        val model = models.find { it.id == modelId }
            ?: return ToolResult.error(
                "not_found",
                "Model '$modelId' not found for provider '${provider.name}'. " +
                "Use list_models to see available models."
            )

        providerRepository.setGlobalDefaultModel(modelId = modelId, providerId = providerId)

        return ToolResult.success(
            "Global default model set to '${model.displayName ?: model.id}' (provider: ${provider.name})."
        )
    }
}
```

### AddModelTool

```kotlin
/**
 * Located in: tool/builtin/config/AddModelTool.kt
 */
class AddModelTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "add_model",
        description = "Add a model manually to a provider. Useful for models not returned by the API.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider to add the model to"
                ),
                "model_id" to ToolParameter(
                    type = "string",
                    description = "The model identifier (e.g., 'gpt-4-turbo', 'claude-sonnet-4-20250514')"
                ),
                "display_name" to ToolParameter(
                    type = "string",
                    description = "Optional human-readable display name for the model"
                )
            ),
            required = listOf("provider_id", "model_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providerId = (parameters["provider_id"] as? String)?.trim()
        if (providerId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'provider_id' is required.")
        }

        val modelId = (parameters["model_id"] as? String)?.trim()
        if (modelId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'model_id' is required.")
        }

        val displayName = (parameters["display_name"] as? String)?.trim()

        val provider = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        return when (val result = providerRepository.addManualModel(providerId, modelId, displayName)) {
            is AppResult.Success -> ToolResult.success(
                "Model '${displayName ?: modelId}' added to provider '${provider.name}'."
            )
            is AppResult.Error -> ToolResult.error("add_failed", "Failed to add model: ${result.message}")
        }
    }
}
```

### DeleteModelTool

```kotlin
/**
 * Located in: tool/builtin/config/DeleteModelTool.kt
 */
class DeleteModelTool(
    private val providerRepository: ProviderRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "delete_model",
        description = "Delete a manually-added model from a provider. " +
            "Only MANUAL models can be deleted; DYNAMIC and PRESET models are managed by the system.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the provider that owns the model"
                ),
                "model_id" to ToolParameter(
                    type = "string",
                    description = "ID of the model to delete"
                )
            ),
            required = listOf("provider_id", "model_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val providerId = (parameters["provider_id"] as? String)?.trim()
        if (providerId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'provider_id' is required.")
        }

        val modelId = (parameters["model_id"] as? String)?.trim()
        if (modelId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'model_id' is required.")
        }

        val provider = providerRepository.getProviderById(providerId)
            ?: return ToolResult.error("not_found", "Provider not found with ID '$providerId'.")

        // Check that it's a MANUAL model
        val models = providerRepository.getModelsForProvider(providerId)
        val model = models.find { it.id == modelId }
            ?: return ToolResult.error("not_found", "Model '$modelId' not found for provider '${provider.name}'.")

        if (model.source != ModelSource.MANUAL) {
            return ToolResult.error(
                "permission_denied",
                "Model '${model.displayName ?: model.id}' is a ${model.source} model and cannot be deleted. " +
                "Only MANUAL models can be deleted."
            )
        }

        return when (val result = providerRepository.deleteManualModel(providerId, modelId)) {
            is AppResult.Success -> ToolResult.success(
                "Model '${model.displayName ?: model.id}' deleted from provider '${provider.name}'."
            )
            is AppResult.Error -> ToolResult.error("deletion_failed", "Failed to delete model: ${result.message}")
        }
    }
}
```

---

## Agent Tools

### ListAgentsTool

```kotlin
/**
 * Located in: tool/builtin/config/ListAgentsTool.kt
 */
class ListAgentsTool(
    private val agentRepository: AgentRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "list_agents",
        description = "List all configured AI agents with their details including " +
            "ID, name, description, whether it is built-in, and preferred provider/model.",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val agents = agentRepository.getAllAgents().first()
        if (agents.isEmpty()) {
            return ToolResult.success("No agents configured.")
        }

        val sb = StringBuilder("Found ${agents.size} agent(s):\n")
        agents.forEachIndexed { index, agent ->
            sb.append("\n${index + 1}. [id: ${agent.id}] ${agent.name}")
            if (agent.isBuiltIn) sb.append(" (built-in)")
            if (!agent.description.isNullOrBlank()) {
                sb.append("\n   Description: ${agent.description}")
            }
            sb.append("\n   System Prompt: ${agent.systemPrompt.take(100)}${if (agent.systemPrompt.length > 100) "..." else ""}")
            if (agent.preferredProviderId != null) {
                sb.append("\n   Preferred Provider ID: ${agent.preferredProviderId}")
            }
            if (agent.preferredModelId != null) {
                sb.append("\n   Preferred Model ID: ${agent.preferredModelId}")
            }
            sb.append("\n")
        }
        return ToolResult.success(sb.toString())
    }
}
```

### UpdateAgentTool

```kotlin
/**
 * Located in: tool/builtin/config/UpdateAgentTool.kt
 *
 * Partial update semantics. Built-in agents cannot be modified.
 */
class UpdateAgentTool(
    private val agentRepository: AgentRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "update_agent",
        description = "Update an existing agent's configuration. Only provided fields are changed. " +
            "Built-in agents cannot be modified.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "agent_id" to ToolParameter(
                    type = "string",
                    description = "ID of the agent to update"
                ),
                "name" to ToolParameter(
                    type = "string",
                    description = "New agent name (max 100 characters)"
                ),
                "description" to ToolParameter(
                    type = "string",
                    description = "New agent description"
                ),
                "system_prompt" to ToolParameter(
                    type = "string",
                    description = "New system prompt (max 50,000 characters)"
                ),
                "preferred_provider_id" to ToolParameter(
                    type = "string",
                    description = "ID of the preferred provider (empty string to clear)"
                ),
                "preferred_model_id" to ToolParameter(
                    type = "string",
                    description = "ID of the preferred model (empty string to clear)"
                )
            ),
            required = listOf("agent_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val agentId = (parameters["agent_id"] as? String)?.trim()
        if (agentId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'agent_id' is required.")
        }

        val existing = agentRepository.getAgentById(agentId)
            ?: return ToolResult.error("not_found", "Agent not found with ID '$agentId'.")

        if (existing.isBuiltIn) {
            return ToolResult.error(
                "permission_denied",
                "Built-in agent '${existing.name}' cannot be modified."
            )
        }

        val changes = mutableListOf<String>()

        val newName = parameters["name"] as? String
        if (newName != null) {
            val trimmed = newName.trim()
            if (trimmed.isEmpty()) {
                return ToolResult.error("validation_error", "Agent name cannot be empty.")
            }
            if (trimmed.length > 100) {
                return ToolResult.error("validation_error", "Agent name must be 100 characters or less.")
            }
            if (trimmed != existing.name) changes.add("name")
        }

        val newDescription = parameters["description"] as? String
        if (newDescription != null && newDescription.trim() != (existing.description ?: "")) {
            changes.add("description")
        }

        val newSystemPrompt = parameters["system_prompt"] as? String
        if (newSystemPrompt != null) {
            val trimmed = newSystemPrompt.trim()
            if (trimmed.isEmpty()) {
                return ToolResult.error("validation_error", "System prompt cannot be empty.")
            }
            if (trimmed.length > 50_000) {
                return ToolResult.error("validation_error", "System prompt must be 50,000 characters or less.")
            }
            if (trimmed != existing.systemPrompt) changes.add("system_prompt")
        }

        val newProviderId = parameters["preferred_provider_id"] as? String
        if (newProviderId != null) changes.add("preferred_provider_id")

        val newModelId = parameters["preferred_model_id"] as? String
        if (newModelId != null) changes.add("preferred_model_id")

        if (changes.isEmpty()) {
            return ToolResult.success("No changes to apply. Agent '${existing.name}' is unchanged.")
        }

        val updated = existing.copy(
            name = (parameters["name"] as? String)?.trim() ?: existing.name,
            description = if (parameters.containsKey("description"))
                (parameters["description"] as? String)?.trim()?.ifBlank { null }
            else existing.description,
            systemPrompt = (parameters["system_prompt"] as? String)?.trim() ?: existing.systemPrompt,
            preferredProviderId = if (parameters.containsKey("preferred_provider_id"))
                (newProviderId?.trim()?.ifEmpty { null })
            else existing.preferredProviderId,
            preferredModelId = if (parameters.containsKey("preferred_model_id"))
                (newModelId?.trim()?.ifEmpty { null })
            else existing.preferredModelId,
            updatedAt = System.currentTimeMillis()
        )

        return when (val result = agentRepository.updateAgent(updated)) {
            is AppResult.Success -> ToolResult.success(
                "Agent '${updated.name}' updated successfully. Changed: ${changes.joinToString(", ")}."
            )
            is AppResult.Error -> ToolResult.error("update_failed", "Failed to update agent: ${result.message}")
        }
    }
}
```

### DeleteAgentTool

```kotlin
/**
 * Located in: tool/builtin/config/DeleteAgentTool.kt
 */
class DeleteAgentTool(
    private val agentRepository: AgentRepository
) : Tool {

    override val definition = ToolDefinition(
        name = "delete_agent",
        description = "Delete a custom agent. Built-in agents cannot be deleted.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "agent_id" to ToolParameter(
                    type = "string",
                    description = "ID of the agent to delete"
                )
            ),
            required = listOf("agent_id")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val agentId = (parameters["agent_id"] as? String)?.trim()
        if (agentId.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'agent_id' is required.")
        }

        val existing = agentRepository.getAgentById(agentId)
            ?: return ToolResult.error("not_found", "Agent not found with ID '$agentId'.")

        if (existing.isBuiltIn) {
            return ToolResult.error(
                "permission_denied",
                "Built-in agent '${existing.name}' cannot be deleted."
            )
        }

        return when (val result = agentRepository.deleteAgent(agentId)) {
            is AppResult.Success -> ToolResult.success(
                "Agent '${existing.name}' has been deleted."
            )
            is AppResult.Error -> ToolResult.error("deletion_failed", "Failed to delete agent: ${result.message}")
        }
    }
}
```

---

## Settings Tools

### Known Config Keys

The following config keys are recognized and validated:

| Key | Allowed Values | Description |
|-----|---------------|-------------|
| `theme_mode` | `system`, `light`, `dark` | App theme mode |

Additional keys can be stored as free-form strings without validation (for future extensibility).

### GetConfigTool

```kotlin
/**
 * Located in: tool/builtin/config/GetConfigTool.kt
 */
class GetConfigTool(
    private val settingsRepository: SettingsRepository
) : Tool {

    companion object {
        val KNOWN_KEYS = mapOf(
            "theme_mode" to "App theme mode (system/light/dark)"
        )
    }

    override val definition = ToolDefinition(
        name = "get_config",
        description = "Read an app configuration setting. " +
            "Known keys: theme_mode (system/light/dark). " +
            "Returns the current value or 'not set' if the key has no value.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "key" to ToolParameter(
                    type = "string",
                    description = "The configuration key to read"
                )
            ),
            required = listOf("key")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val key = (parameters["key"] as? String)?.trim()
        if (key.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'key' is required.")
        }

        val value = settingsRepository.getString(key)
        val knownInfo = KNOWN_KEYS[key]

        return if (value != null) {
            val desc = if (knownInfo != null) " ($knownInfo)" else ""
            ToolResult.success("$key$desc = $value")
        } else {
            val sb = StringBuilder("Key '$key' is not set.")
            if (knownInfo == null) {
                sb.append("\n\nKnown configuration keys:\n")
                KNOWN_KEYS.forEach { (k, desc) ->
                    sb.append("- $k: $desc\n")
                }
            }
            ToolResult.success(sb.toString())
        }
    }
}
```

### SetConfigTool

```kotlin
/**
 * Located in: tool/builtin/config/SetConfigTool.kt
 *
 * For known keys (e.g., theme_mode), validates the value and
 * applies side effects (e.g., ThemeManager.setThemeMode).
 */
class SetConfigTool(
    private val settingsRepository: SettingsRepository,
    private val themeManager: ThemeManager
) : Tool {

    companion object {
        val KNOWN_KEY_VALUES = mapOf(
            "theme_mode" to listOf("system", "light", "dark")
        )
    }

    override val definition = ToolDefinition(
        name = "set_config",
        description = "Set an app configuration value. " +
            "For theme_mode: allowed values are 'system', 'light', 'dark'. " +
            "Changes take effect immediately.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "key" to ToolParameter(
                    type = "string",
                    description = "The configuration key to set"
                ),
                "value" to ToolParameter(
                    type = "string",
                    description = "The value to set"
                )
            ),
            required = listOf("key", "value")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val key = (parameters["key"] as? String)?.trim()
        if (key.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'key' is required.")
        }

        val value = (parameters["value"] as? String)?.trim()
        if (value.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'value' is required and cannot be empty.")
        }

        // Validate known keys
        val allowedValues = KNOWN_KEY_VALUES[key]
        if (allowedValues != null && value !in allowedValues) {
            return ToolResult.error(
                "validation_error",
                "Invalid value '$value' for key '$key'. Allowed values: ${allowedValues.joinToString(", ")}."
            )
        }

        // Apply side effects for known keys
        when (key) {
            "theme_mode" -> {
                val mode = ThemeMode.fromKey(value)
                themeManager.setThemeMode(mode)
                return ToolResult.success("Theme mode set to '$value'. The change has been applied.")
            }
        }

        // Generic key-value storage
        settingsRepository.setString(key, value)
        return ToolResult.success("Configuration '$key' set to '$value'.")
    }
}
```

---

## Tool State Tools

### ListToolStatesTool

```kotlin
/**
 * Located in: tool/builtin/config/ListToolStatesTool.kt
 */
class ListToolStatesTool(
    private val toolRegistry: ToolRegistry,
    private val toolEnabledStateStore: ToolEnabledStateStore
) : Tool {

    override val definition = ToolDefinition(
        name = "list_tool_states",
        description = "List all registered tools with their enabled/disabled status, " +
            "organized by group. Shows tool name, group, and whether it is enabled.",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val groups = toolRegistry.getToolGroups()
        if (groups.isEmpty()) {
            return ToolResult.success("No tools registered.")
        }

        val sb = StringBuilder("Registered tools:\n")
        groups.forEach { group ->
            val groupEnabled = toolEnabledStateStore.isGroupEnabled(group.name)
            sb.append("\n[Group: ${group.name}] ${if (groupEnabled) "ENABLED" else "DISABLED"}")
            group.tools.forEach { toolInfo ->
                val toolEnabled = toolEnabledStateStore.isToolEnabled(toolInfo.name)
                val effective = toolEnabledStateStore.isToolEffectivelyEnabled(toolInfo.name, group.name)
                val status = when {
                    !groupEnabled -> "DISABLED (group disabled)"
                    !toolEnabled -> "DISABLED"
                    else -> "ENABLED"
                }
                sb.append("\n  - ${toolInfo.name}: $status")
            }
            sb.append("\n")
        }
        return ToolResult.success(sb.toString())
    }
}
```

### SetToolEnabledTool

```kotlin
/**
 * Located in: tool/builtin/config/SetToolEnabledTool.kt
 */
class SetToolEnabledTool(
    private val toolRegistry: ToolRegistry,
    private val toolEnabledStateStore: ToolEnabledStateStore
) : Tool {

    override val definition = ToolDefinition(
        name = "set_tool_enabled",
        description = "Enable or disable a specific tool or tool group. " +
            "When a group is disabled, all tools in that group are effectively disabled.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Name of the tool or group to enable/disable"
                ),
                "enabled" to ToolParameter(
                    type = "boolean",
                    description = "Whether to enable (true) or disable (false)"
                ),
                "type" to ToolParameter(
                    type = "string",
                    description = "Whether 'name' refers to a 'tool' or a 'group'. Default: 'tool'.",
                    enum = listOf("tool", "group"),
                    default = "tool"
                )
            ),
            required = listOf("name", "enabled")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = (parameters["name"] as? String)?.trim()
        if (name.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'name' is required.")
        }

        val enabled = parameters["enabled"] as? Boolean
            ?: return ToolResult.error("validation_error", "Parameter 'enabled' is required and must be a boolean.")

        val type = (parameters["type"] as? String)?.trim()?.lowercase() ?: "tool"

        when (type) {
            "tool" -> {
                if (!toolRegistry.hasTool(name)) {
                    return ToolResult.error("not_found", "Tool '$name' is not registered.")
                }
                toolEnabledStateStore.setToolEnabled(name, enabled)
                return ToolResult.success(
                    "Tool '$name' has been ${if (enabled) "enabled" else "disabled"}."
                )
            }
            "group" -> {
                toolEnabledStateStore.setGroupEnabled(name, enabled)
                return ToolResult.success(
                    "Tool group '$name' has been ${if (enabled) "enabled" else "disabled"}. " +
                    "All tools in this group are now effectively ${if (enabled) "enabled" else "disabled"}."
                )
            }
            else -> {
                return ToolResult.error(
                    "validation_error",
                    "Parameter 'type' must be 'tool' or 'group'."
                )
            }
        }
    }
}
```

---

## Environment Variable Tool

### ManageEnvVarTool

```kotlin
/**
 * Located in: tool/builtin/config/ManageEnvVarTool.kt
 *
 * Combines list, set, and delete operations into a single tool
 * since env vars are simple key-value pairs.
 */
class ManageEnvVarTool(
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override val definition = ToolDefinition(
        name = "manage_env_var",
        description = "Manage JavaScript tool environment variables. " +
            "Actions: 'list' shows all variable keys, " +
            "'set' creates or updates a variable, " +
            "'delete' removes a variable. " +
            "Values are stored securely in encrypted preferences.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "action" to ToolParameter(
                    type = "string",
                    description = "The action to perform",
                    enum = listOf("list", "set", "delete")
                ),
                "key" to ToolParameter(
                    type = "string",
                    description = "Variable name (required for 'set' and 'delete')"
                ),
                "value" to ToolParameter(
                    type = "string",
                    description = "Variable value (required for 'set')"
                )
            ),
            required = listOf("action")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val action = (parameters["action"] as? String)?.trim()?.lowercase()

        return when (action) {
            "list" -> listEnvVars()
            "set" -> setEnvVar(parameters)
            "delete" -> deleteEnvVar(parameters)
            else -> ToolResult.error(
                "validation_error",
                "Parameter 'action' must be one of: list, set, delete."
            )
        }
    }

    private fun listEnvVars(): ToolResult {
        val keys = envVarStore.getKeys()
        if (keys.isEmpty()) {
            return ToolResult.success("No environment variables configured.")
        }
        val sb = StringBuilder("Environment variables (${keys.size}):\n")
        keys.sorted().forEach { key ->
            sb.append("- $key = ****\n")
        }
        sb.append("\nValues are masked for security. Use 'set' to update a value.")
        return ToolResult.success(sb.toString())
    }

    private fun setEnvVar(parameters: Map<String, Any?>): ToolResult {
        val key = (parameters["key"] as? String)?.trim()
        if (key.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'key' is required for 'set' action.")
        }
        val value = (parameters["value"] as? String)
        if (value == null) {
            return ToolResult.error("validation_error", "Parameter 'value' is required for 'set' action.")
        }
        envVarStore.set(key, value)
        return ToolResult.success("Environment variable '$key' has been set.")
    }

    private fun deleteEnvVar(parameters: Map<String, Any?>): ToolResult {
        val key = (parameters["key"] as? String)?.trim()
        if (key.isNullOrEmpty()) {
            return ToolResult.error("validation_error", "Parameter 'key' is required for 'delete' action.")
        }
        envVarStore.delete(key)
        return ToolResult.success("Environment variable '$key' has been deleted.")
    }
}
```

---

## ToolModule Changes

```kotlin
// In ToolModule.kt -- add to existing module

// RFC-036: Configuration management tools
// Provider tools
single { ListProvidersTool(get(), get()) }
single { CreateProviderTool(get()) }
single { UpdateProviderTool(get()) }
single { DeleteProviderTool(get()) }

// Model tools
single { ListModelsTool(get()) }
single { FetchModelsTool(get(), get()) }
single { SetDefaultModelTool(get()) }
single { AddModelTool(get()) }
single { DeleteModelTool(get()) }

// Agent tools
single { ListAgentsTool(get()) }
single { UpdateAgentTool(get()) }
single { DeleteAgentTool(get()) }

// Settings tools
single { GetConfigTool(get()) }
single { SetConfigTool(get(), get()) }

// Tool state tools
// Note: These need the ToolRegistry itself, which creates a circular dependency.
// Resolve by using lazy injection: inject ToolRegistry after creation.
single { ListToolStatesTool(get(), get()) }
single { SetToolEnabledTool(get(), get()) }

// Env var tool
single { ManageEnvVarTool(get()) }

// In ToolRegistry.apply block:
val configTools = listOf(
    get<ListProvidersTool>(),
    get<CreateProviderTool>(),
    get<UpdateProviderTool>(),
    get<DeleteProviderTool>(),
    get<ListModelsTool>(),
    get<FetchModelsTool>(),
    get<SetDefaultModelTool>(),
    get<AddModelTool>(),
    get<DeleteModelTool>(),
    get<ListAgentsTool>(),
    get<UpdateAgentTool>(),
    get<DeleteAgentTool>(),
    get<GetConfigTool>(),
    get<SetConfigTool>(),
    get<ManageEnvVarTool>()
)

configTools.forEach { tool ->
    try {
        register(tool, ToolSourceInfo.BUILTIN)
    } catch (e: Exception) {
        Log.e("ToolModule", "Failed to register ${tool.definition.name}: ${e.message}")
    }
}

// Tool state tools registered separately (after ToolRegistry is available)
// to avoid circular dependency issues.
try { register(get<ListToolStatesTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register list_tool_states: ${e.message}") }

try { register(get<SetToolEnabledTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register set_tool_enabled: ${e.message}") }
```

### Circular Dependency Note

`ListToolStatesTool` and `SetToolEnabledTool` depend on `ToolRegistry`, which is the same object they are registered into. This creates a circular dependency with Koin's `single` scope.

**Resolution**: Use `get()` inside the `ToolRegistry.apply` block, where the ToolRegistry singleton is already being constructed. The tools are instantiated at registration time, and at that point the ToolRegistry instance exists (it's the `this` receiver). The tools receive the ToolRegistry reference via Koin's normal resolution, which returns the same singleton being constructed.

Alternatively, pass the ToolRegistry instance explicitly:

```kotlin
single {
    ToolRegistry().apply {
        // ... other registrations ...

        // Tool state tools receive `this` (the ToolRegistry being built)
        val listToolStates = ListToolStatesTool(this, get())
        val setToolEnabled = SetToolEnabledTool(this, get())
        try { register(listToolStates, ToolSourceInfo.BUILTIN) } catch (e: Exception) { ... }
        try { register(setToolEnabled, ToolSourceInfo.BUILTIN) } catch (e: Exception) { ... }
    }
}
```

### Imports Required

All tools require these common imports:

```kotlin
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
```

Additional imports per tool:

| Tool | Additional Imports |
|------|-------------------|
| ListProvidersTool | `ProviderRepository`, `ApiKeyStorage`, `kotlinx.coroutines.flow.first` |
| CreateProviderTool | `ProviderRepository`, `Provider`, `ProviderType` |
| UpdateProviderTool | `ProviderRepository` |
| DeleteProviderTool | `ProviderRepository`, `AppResult` |
| ListModelsTool | `ProviderRepository` |
| FetchModelsTool | `ProviderRepository`, `ApiKeyStorage`, `AppResult` |
| SetDefaultModelTool | `ProviderRepository` |
| AddModelTool | `ProviderRepository`, `AppResult` |
| DeleteModelTool | `ProviderRepository`, `ModelSource`, `AppResult` |
| ListAgentsTool | `AgentRepository`, `kotlinx.coroutines.flow.first` |
| UpdateAgentTool | `AgentRepository`, `AppResult` |
| DeleteAgentTool | `AgentRepository`, `AppResult` |
| GetConfigTool | `SettingsRepository` |
| SetConfigTool | `SettingsRepository`, `ThemeManager`, `ThemeMode` |
| ListToolStatesTool | `ToolRegistry`, `ToolEnabledStateStore` |
| SetToolEnabledTool | `ToolRegistry`, `ToolEnabledStateStore` |
| ManageEnvVarTool | `EnvironmentVariableStore` |

## Implementation Plan

### Phase 1: Provider & Model Tools (6 tools)

1. Create `tool/builtin/config/` package directory
2. Implement `ListProvidersTool`, `CreateProviderTool`, `UpdateProviderTool`, `DeleteProviderTool`
3. Implement `ListModelsTool`, `FetchModelsTool`, `SetDefaultModelTool`, `AddModelTool`, `DeleteModelTool`
4. Register all in `ToolModule`
5. Write unit tests

### Phase 2: Agent Tools (3 tools)

1. Implement `ListAgentsTool`, `UpdateAgentTool`, `DeleteAgentTool`
2. Register in `ToolModule`
3. Write unit tests

### Phase 3: Settings & Tool State Tools (4 tools)

1. Implement `GetConfigTool`, `SetConfigTool`
2. Implement `ListToolStatesTool`, `SetToolEnabledTool`
3. Handle circular dependency for ToolRegistry
4. Register in `ToolModule`
5. Write unit tests

### Phase 4: Environment Variable Tool (1 tool)

1. Implement `ManageEnvVarTool`
2. Register in `ToolModule`
3. Write unit tests

### Phase 5: Integration Testing

1. Run Layer 1A tests (`./gradlew test`)
2. Manual testing on device

## Data Model

No data model changes. All tools use existing repositories, DAOs, and storage classes.

## API Design

### Provider Tools

```
Tool: list_providers
Parameters: (none)
Returns: Formatted list of all providers with id, name, type, url, active, key status

Tool: create_provider
Parameters:
  - name: string (required) -- Provider display name
  - type: string (required) -- OPENAI, ANTHROPIC, or GEMINI
  - api_base_url: string (required) -- API base URL
Returns: Created provider ID with API key setup reminder

Tool: update_provider
Parameters:
  - provider_id: string (required) -- ID of provider to update
  - name: string (optional) -- New display name
  - api_base_url: string (optional) -- New API base URL
  - is_active: boolean (optional) -- Active status
Returns: Confirmation with list of changed fields

Tool: delete_provider
Parameters:
  - provider_id: string (required) -- ID of provider to delete
Returns: Confirmation message
```

### Model Tools

```
Tool: list_models
Parameters:
  - provider_id: string (required) -- ID of provider
Returns: Formatted list of models with id, name, source, default, context window

Tool: fetch_models
Parameters:
  - provider_id: string (required) -- ID of provider
Returns: List of fetched models

Tool: set_default_model
Parameters:
  - provider_id: string (required) -- ID of provider
  - model_id: string (required) -- ID of model
Returns: Confirmation message

Tool: add_model
Parameters:
  - provider_id: string (required) -- ID of provider
  - model_id: string (required) -- Model identifier
  - display_name: string (optional) -- Human-readable name
Returns: Confirmation message

Tool: delete_model
Parameters:
  - provider_id: string (required) -- ID of provider
  - model_id: string (required) -- ID of model
Returns: Confirmation message
```

### Agent Tools

```
Tool: list_agents
Parameters: (none)
Returns: Formatted list of all agents with details

Tool: update_agent
Parameters:
  - agent_id: string (required) -- ID of agent to update
  - name: string (optional) -- New name
  - description: string (optional) -- New description
  - system_prompt: string (optional) -- New system prompt
  - preferred_provider_id: string (optional) -- New preferred provider
  - preferred_model_id: string (optional) -- New preferred model
Returns: Confirmation with list of changed fields

Tool: delete_agent
Parameters:
  - agent_id: string (required) -- ID of agent
Returns: Confirmation message
```

### Settings Tools

```
Tool: get_config
Parameters:
  - key: string (required) -- Config key
Returns: Current value or "not set" with list of known keys

Tool: set_config
Parameters:
  - key: string (required) -- Config key
  - value: string (required) -- Config value
Returns: Confirmation message
```

### Tool State Tools

```
Tool: list_tool_states
Parameters: (none)
Returns: All tools organized by group with enabled/disabled status

Tool: set_tool_enabled
Parameters:
  - name: string (required) -- Tool or group name
  - enabled: boolean (required) -- Enable or disable
  - type: string (optional, default: "tool") -- "tool" or "group"
Returns: Confirmation message
```

### Environment Variable Tool

```
Tool: manage_env_var
Parameters:
  - action: string (required) -- "list", "set", or "delete"
  - key: string (conditional) -- Required for "set" and "delete"
  - value: string (conditional) -- Required for "set"
Returns: Varies by action
```

## Error Handling

| Error | Cause | Error Type | Handling |
|-------|-------|------------|----------|
| Entity not found | Invalid provider/agent/model/tool ID | `not_found` | Return descriptive error message |
| Validation | Missing required param, invalid value | `validation_error` | Return error with valid options |
| Permission denied | Modify built-in agent or delete pre-configured provider | `permission_denied` | Return error explaining restriction |
| API key required | `fetch_models` without API key | `api_key_required` | Return error with setup instructions |
| Network error | API call failure in `fetch_models` | `fetch_failed` | Return error with original message |
| Add/delete/update failure | Repository operation failure | `*_failed` | Return error from AppResult.Error |

## Security Considerations

1. **API Key Protection**: No tool reads, writes, or transmits API keys. The `list_providers` tool only reports whether a key is configured (boolean), never the key value. `ApiKeyStorage` is used read-only (`hasApiKey` only).

2. **Environment Variable Masking**: `manage_env_var` with `action=list` shows keys only, not values. Values are stored in `EncryptedSharedPreferences`.

3. **Built-in Entity Protection**: Built-in agents cannot be modified or deleted. Pre-configured providers cannot be deleted.

4. **Input Validation**: All string parameters are trimmed. Enum parameters are validated against allowed values. Length limits are enforced (agent name: 100, system prompt: 50,000).

## Performance

| Tool | Expected Time | Notes |
|------|--------------|-------|
| list_providers | < 50ms | Room query + SharedPrefs check |
| create_provider | < 50ms | Room insert |
| update_provider | < 50ms | Room query + update |
| delete_provider | < 50ms | Room delete cascade |
| list_models | < 50ms | Room query |
| fetch_models | < 30s | Network API call |
| set_default_model | < 50ms | Room query + update |
| add_model | < 50ms | Room insert |
| delete_model | < 50ms | Room delete |
| list_agents | < 50ms | Room query |
| update_agent | < 50ms | Room query + update |
| delete_agent | < 50ms | Room delete |
| get_config | < 10ms | Room query |
| set_config | < 20ms | Room write + theme apply |
| list_tool_states | < 20ms | In-memory registry + SharedPrefs |
| set_tool_enabled | < 10ms | SharedPrefs write |
| manage_env_var | < 10ms | EncryptedSharedPreferences |

## Testing Strategy

### Unit Tests

**Provider Tool Tests:**
- `ListProvidersToolTest`: testListProviders_empty, testListProviders_withProviders, testListProviders_showsApiKeyStatus
- `CreateProviderToolTest`: testCreateProvider_success, testCreateProvider_missingName, testCreateProvider_invalidType, testCreateProvider_missingUrl
- `UpdateProviderToolTest`: testUpdateProvider_partialUpdate, testUpdateProvider_noChanges, testUpdateProvider_notFound, testUpdateProvider_emptyName
- `DeleteProviderToolTest`: testDeleteProvider_success, testDeleteProvider_notFound, testDeleteProvider_preConfigured

**Model Tool Tests:**
- `ListModelsToolTest`: testListModels_empty, testListModels_withModels, testListModels_providerNotFound
- `FetchModelsToolTest`: testFetchModels_success, testFetchModels_noApiKey, testFetchModels_providerNotFound, testFetchModels_networkError
- `SetDefaultModelToolTest`: testSetDefault_success, testSetDefault_modelNotFound, testSetDefault_providerNotFound
- `AddModelToolTest`: testAddModel_success, testAddModel_providerNotFound, testAddModel_missingModelId
- `DeleteModelToolTest`: testDeleteModel_success, testDeleteModel_notManual, testDeleteModel_notFound

**Agent Tool Tests:**
- `ListAgentsToolTest`: testListAgents_empty, testListAgents_withAgents, testListAgents_showsBuiltInFlag
- `UpdateAgentToolTest`: testUpdateAgent_partialUpdate, testUpdateAgent_builtIn, testUpdateAgent_notFound, testUpdateAgent_emptyName, testUpdateAgent_longSystemPrompt
- `DeleteAgentToolTest`: testDeleteAgent_success, testDeleteAgent_builtIn, testDeleteAgent_notFound

**Settings Tool Tests:**
- `GetConfigToolTest`: testGetConfig_knownKey, testGetConfig_unknownKey, testGetConfig_notSet
- `SetConfigToolTest`: testSetConfig_theme, testSetConfig_invalidTheme, testSetConfig_customKey

**Tool State Tool Tests:**
- `ListToolStatesToolTest`: testListToolStates_empty, testListToolStates_withTools, testListToolStates_groupDisabled
- `SetToolEnabledToolTest`: testSetToolEnabled_tool, testSetToolEnabled_group, testSetToolEnabled_toolNotFound

**Env Var Tool Tests:**
- `ManageEnvVarToolTest`: testManageEnvVar_list_empty, testManageEnvVar_list_withVars, testManageEnvVar_set, testManageEnvVar_delete, testManageEnvVar_invalidAction, testManageEnvVar_setMissingKey

### Manual Testing (Layer 2)

1. Create a provider via chat, verify it appears in Settings UI
2. Fetch models for a provider with API key configured
3. Set default model via chat, verify it's used in next conversation
4. Create an agent via `create_agent`, update it via `update_agent`, verify changes in Agents list
5. Switch theme via chat, verify immediate visual change
6. Disable a tool via chat, verify it's not offered in next agent response
7. Set an env var via chat, verify it's accessible in JS tool execution

## Alternatives Considered

### 1. Single Mega-Tool with Action Parameter

**Approach**: One `app_config` tool with an `action` parameter and conditional sub-parameters.
**Rejected**: Too complex for AI tool calling. Conditional required parameters based on action make the schema ambiguous. Individual tools with clear, focused schemas are easier for models to use correctly.

### 2. REST-Style Generic CRUD Tool

**Approach**: A generic `crud` tool with `entity_type`, `action`, and `data` parameters.
**Rejected**: Over-abstraction that loses type safety and makes parameter validation harder. Dedicated tools provide better error messages and clearer descriptions.

### 3. Settings-Only Tool (No Provider/Agent/Model Management)

**Approach**: Only add get/set config tools for simple settings.
**Rejected**: Does not meet the requirement of "configure the app 100% via prompt." Provider, model, and agent management are essential for full configuration.

## Dependencies

### External Dependencies

- None (all operations use existing internal components)

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `ProviderRepository` from `core/repository/`
- `AgentRepository` from `core/repository/`
- `SettingsRepository` from `core/repository/`
- `ThemeManager` from `core/theme/`
- `ToolRegistry` from `tool/engine/`
- `ToolEnabledStateStore` from `tool/engine/`
- `EnvironmentVariableStore` from `tool/js/`
- `ApiKeyStorage` from `data/security/`

## Future Extensions

- **Session management tools**: List, rename, delete chat sessions
- **Usage statistics tool**: Read token usage per model
- **Backup/sync tools**: Trigger backup, configure sync
- **Provider connection test tool**: Test API connectivity
- **Skill management tools**: List, enable/disable, import/export skills
- **Memory management tools**: Read/write/clear memory entries

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
