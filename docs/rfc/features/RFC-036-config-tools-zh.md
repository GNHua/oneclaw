# RFC-036: 配置管理工具

## 文档信息
- **RFC ID**: RFC-036
- **相关 PRD**: [FEAT-036 (配置管理工具)](../../prd/features/FEAT-036-config-tools.md)
- **相关架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **相关 RFC**: [RFC-004 (工具系统)](RFC-004-tool-system.md), [RFC-002 (Agent)](RFC-002-agent.md), [RFC-003 (Provider)](RFC-003-provider.md)
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClaw 目前需要用户导航到各个设置界面来配置 provider、模型、agent、主题、工具状态和环境变量。应用已经拥有用于创建 agent（`create_agent`）和管理定时任务（RFC-027）的工具，但缺少管理其余配置的工具。这意味着用户必须在聊天界面和设置 UI 之间频繁切换来调整配置。

本 RFC 新增 17 个内置工具，使 AI agent 能够通过对话读取和修改所有应用配置，唯一例外是 API key/认证凭证管理，出于安全原因该功能仅保留在 UI 中。

### 目标

1. 添加 provider 管理工具（list、create、update、delete）
2. 添加模型管理工具（list、fetch、set default、add/delete manual）
3. 添加 agent 管理工具（list、update、delete）
4. 添加应用设置工具（get、set -- 包括主题）
5. 添加工具状态管理工具（list、enable/disable）
6. 添加环境变量管理工具（list、set、delete）
7. 将所有工具以 BUILTIN 类型注册到 ToolModule

### 非目标

- API key 或认证凭证管理（硬性安全约束）
- Skill 管理（独立功能）
- Memory 管理（独立功能）
- Session 管理（独立功能）
- 备份/同步管理（独立功能）

## 技术设计

### 架构概览

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

### 核心组件

**新增：**
1. `ListProvidersTool` -- 列出所有 provider
2. `CreateProviderTool` -- 创建新 provider
3. `UpdateProviderTool` -- 局部更新 provider
4. `DeleteProviderTool` -- 删除 provider
5. `ListModelsTool` -- 列出某个 provider 的模型
6. `FetchModelsTool` -- 从 API 刷新模型列表
7. `SetDefaultModelTool` -- 设置全局默认模型
8. `AddModelTool` -- 手动添加模型
9. `DeleteModelTool` -- 删除手动添加的模型
10. `ListAgentsTool` -- 列出所有 agent
11. `UpdateAgentTool` -- 局部更新 agent
12. `DeleteAgentTool` -- 删除 agent
13. `GetConfigTool` -- 读取应用配置项
14. `SetConfigTool` -- 写入应用配置项（包括主题）
15. `ListToolStatesTool` -- 列出工具及其启用/禁用状态
16. `SetToolEnabledTool` -- 启用/禁用某个工具或工具组
17. `ManageEnvVarTool` -- 列出、设置或删除环境变量

**修改：**
18. `ToolModule` -- 注册全部 17 个新工具

**复用（不变）：**
19. `ProviderRepository` -- 现有 provider/模型数据访问
20. `AgentRepository` -- 现有 agent 数据访问
21. `SettingsRepository` -- 现有设置数据访问
22. `ThemeManager` -- 现有主题管理
23. `ToolEnabledStateStore` -- 现有工具状态持久化
24. `EnvironmentVariableStore` -- 现有环境变量存储
25. `ApiKeyStorage` -- 现有 API key 存储（只读访问：`hasApiKey`）

## 详细设计

### 目录结构（新增及修改的文件）

```
app/src/main/kotlin/com/oneclaw/shadow/
├── tool/
│   └── builtin/
│       └── config/
│           ├── ListProvidersTool.kt        # 新增
│           ├── CreateProviderTool.kt       # 新增
│           ├── UpdateProviderTool.kt       # 新增
│           ├── DeleteProviderTool.kt       # 新增
│           ├── ListModelsTool.kt           # 新增
│           ├── FetchModelsTool.kt          # 新增
│           ├── SetDefaultModelTool.kt      # 新增
│           ├── AddModelTool.kt             # 新增
│           ├── DeleteModelTool.kt          # 新增
│           ├── ListAgentsTool.kt           # 新增
│           ├── UpdateAgentTool.kt          # 新增
│           ├── DeleteAgentTool.kt          # 新增
│           ├── GetConfigTool.kt            # 新增
│           ├── SetConfigTool.kt            # 新增
│           ├── ListToolStatesTool.kt       # 新增
│           ├── SetToolEnabledTool.kt       # 新增
│           └── ManageEnvVarTool.kt         # 新增
└── di/
    └── ToolModule.kt                       # 修改

app/src/test/kotlin/com/oneclaw/shadow/
└── tool/
    └── builtin/
        └── config/
            ├── ListProvidersToolTest.kt     # 新增
            ├── CreateProviderToolTest.kt    # 新增
            ├── UpdateProviderToolTest.kt    # 新增
            ├── DeleteProviderToolTest.kt    # 新增
            ├── ListModelsToolTest.kt        # 新增
            ├── FetchModelsToolTest.kt       # 新增
            ├── SetDefaultModelToolTest.kt   # 新增
            ├── AddModelToolTest.kt          # 新增
            ├── DeleteModelToolTest.kt       # 新增
            ├── ListAgentsToolTest.kt        # 新增
            ├── UpdateAgentToolTest.kt       # 新增
            ├── DeleteAgentToolTest.kt       # 新增
            ├── GetConfigToolTest.kt         # 新增
            ├── SetConfigToolTest.kt         # 新增
            ├── ListToolStatesToolTest.kt    # 新增
            ├── SetToolEnabledToolTest.kt    # 新增
            └── ManageEnvVarToolTest.kt      # 新增
```

所有配置工具放置在 `tool/builtin/` 下的 `config/` 子目录中，以保持内置目录的整洁。

---

## Provider 工具

### ListProvidersTool

```kotlin
/**
 * 位于：tool/builtin/config/ListProvidersTool.kt
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
 * 位于：tool/builtin/config/CreateProviderTool.kt
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
 * 位于：tool/builtin/config/UpdateProviderTool.kt
 *
 * 局部更新语义：只更改已提供的字段。
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
 * 位于：tool/builtin/config/DeleteProviderTool.kt
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

## 模型工具

### ListModelsTool

```kotlin
/**
 * 位于：tool/builtin/config/ListModelsTool.kt
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
 * 位于：tool/builtin/config/FetchModelsTool.kt
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
 * 位于：tool/builtin/config/SetDefaultModelTool.kt
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
 * 位于：tool/builtin/config/AddModelTool.kt
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
 * 位于：tool/builtin/config/DeleteModelTool.kt
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

        // 确认是 MANUAL 类型的模型
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

## Agent 工具

### ListAgentsTool

```kotlin
/**
 * 位于：tool/builtin/config/ListAgentsTool.kt
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
 * 位于：tool/builtin/config/UpdateAgentTool.kt
 *
 * 局部更新语义。内置 agent 不可修改。
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
 * 位于：tool/builtin/config/DeleteAgentTool.kt
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

## 设置工具

### 已知配置键

以下配置键已被识别并经过验证：

| 键 | 允许的值 | 描述 |
|-----|---------------|-------------|
| `theme_mode` | `system`, `light`, `dark` | 应用主题模式 |

其他键可以作为自由格式字符串存储而不经过验证（为未来扩展性保留）。

### GetConfigTool

```kotlin
/**
 * 位于：tool/builtin/config/GetConfigTool.kt
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
 * 位于：tool/builtin/config/SetConfigTool.kt
 *
 * 对于已知键（例如 theme_mode），验证值并
 * 应用副作用（例如 ThemeManager.setThemeMode）。
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

        // 验证已知键的值
        val allowedValues = KNOWN_KEY_VALUES[key]
        if (allowedValues != null && value !in allowedValues) {
            return ToolResult.error(
                "validation_error",
                "Invalid value '$value' for key '$key'. Allowed values: ${allowedValues.joinToString(", ")}."
            )
        }

        // 对已知键应用副作用
        when (key) {
            "theme_mode" -> {
                val mode = ThemeMode.fromKey(value)
                themeManager.setThemeMode(mode)
                return ToolResult.success("Theme mode set to '$value'. The change has been applied.")
            }
        }

        // 通用键值存储
        settingsRepository.setString(key, value)
        return ToolResult.success("Configuration '$key' set to '$value'.")
    }
}
```

---

## 工具状态工具

### ListToolStatesTool

```kotlin
/**
 * 位于：tool/builtin/config/ListToolStatesTool.kt
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
 * 位于：tool/builtin/config/SetToolEnabledTool.kt
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

## 环境变量工具

### ManageEnvVarTool

```kotlin
/**
 * 位于：tool/builtin/config/ManageEnvVarTool.kt
 *
 * 将 list、set、delete 操作合并为一个工具，
 * 因为环境变量是简单的键值对。
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

## ToolModule 变更

```kotlin
// 在 ToolModule.kt 中 -- 添加到现有模块

// RFC-036：配置管理工具
// Provider 工具
single { ListProvidersTool(get(), get()) }
single { CreateProviderTool(get()) }
single { UpdateProviderTool(get()) }
single { DeleteProviderTool(get()) }

// 模型工具
single { ListModelsTool(get()) }
single { FetchModelsTool(get(), get()) }
single { SetDefaultModelTool(get()) }
single { AddModelTool(get()) }
single { DeleteModelTool(get()) }

// Agent 工具
single { ListAgentsTool(get()) }
single { UpdateAgentTool(get()) }
single { DeleteAgentTool(get()) }

// 设置工具
single { GetConfigTool(get()) }
single { SetConfigTool(get(), get()) }

// 工具状态工具
// 注意：这些工具需要 ToolRegistry 本身，会产生循环依赖。
// 通过延迟注入解决：在创建后注入 ToolRegistry。
single { ListToolStatesTool(get(), get()) }
single { SetToolEnabledTool(get(), get()) }

// 环境变量工具
single { ManageEnvVarTool(get()) }

// 在 ToolRegistry.apply 块中：
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

// 工具状态工具单独注册（在 ToolRegistry 可用之后），
// 以避免循环依赖问题。
try { register(get<ListToolStatesTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register list_tool_states: ${e.message}") }

try { register(get<SetToolEnabledTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register set_tool_enabled: ${e.message}") }
```

### 循环依赖说明

`ListToolStatesTool` 和 `SetToolEnabledTool` 依赖于 `ToolRegistry`，而 `ToolRegistry` 正是它们被注册进去的对象。这与 Koin 的 `single` 作用域产生了循环依赖。

**解决方案**：在 `ToolRegistry.apply` 块内使用 `get()`，此时 ToolRegistry 单例已经在构建中。工具在注册时被实例化，此时 ToolRegistry 实例已存在（即 `this` 接收者）。工具通过 Koin 的正常解析接收 ToolRegistry 引用，该解析返回正在构建的同一个单例。

或者，显式传递 ToolRegistry 实例：

```kotlin
single {
    ToolRegistry().apply {
        // ... 其他注册 ...

        // 工具状态工具接收 `this`（正在构建的 ToolRegistry）
        val listToolStates = ListToolStatesTool(this, get())
        val setToolEnabled = SetToolEnabledTool(this, get())
        try { register(listToolStates, ToolSourceInfo.BUILTIN) } catch (e: Exception) { ... }
        try { register(setToolEnabled, ToolSourceInfo.BUILTIN) } catch (e: Exception) { ... }
    }
}
```

### 所需导入

所有工具需要以下通用导入：

```kotlin
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
```

各工具的额外导入：

| 工具 | 额外导入 |
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

## 实施计划

### 阶段 1：Provider 和模型工具（6 个工具）

1. 创建 `tool/builtin/config/` 包目录
2. 实现 `ListProvidersTool`、`CreateProviderTool`、`UpdateProviderTool`、`DeleteProviderTool`
3. 实现 `ListModelsTool`、`FetchModelsTool`、`SetDefaultModelTool`、`AddModelTool`、`DeleteModelTool`
4. 在 `ToolModule` 中注册所有工具
5. 编写单元测试

### 阶段 2：Agent 工具（3 个工具）

1. 实现 `ListAgentsTool`、`UpdateAgentTool`、`DeleteAgentTool`
2. 在 `ToolModule` 中注册
3. 编写单元测试

### 阶段 3：设置与工具状态工具（4 个工具）

1. 实现 `GetConfigTool`、`SetConfigTool`
2. 实现 `ListToolStatesTool`、`SetToolEnabledTool`
3. 处理 ToolRegistry 的循环依赖
4. 在 `ToolModule` 中注册
5. 编写单元测试

### 阶段 4：环境变量工具（1 个工具）

1. 实现 `ManageEnvVarTool`
2. 在 `ToolModule` 中注册
3. 编写单元测试

### 阶段 5：集成测试

1. 运行 Layer 1A 测试（`./gradlew test`）
2. 在设备上进行手动测试

## 数据模型

无数据模型变更。所有工具使用现有的 repository、DAO 和存储类。

## API 设计

### Provider 工具

```
Tool: list_providers
Parameters: (none)
Returns: 格式化的所有 provider 列表，包含 id、name、type、url、active、key 状态

Tool: create_provider
Parameters:
  - name: string (required) -- Provider 显示名称
  - type: string (required) -- OPENAI, ANTHROPIC, 或 GEMINI
  - api_base_url: string (required) -- API 基础 URL
Returns: 创建的 provider ID 及 API key 设置提醒

Tool: update_provider
Parameters:
  - provider_id: string (required) -- 要更新的 provider ID
  - name: string (optional) -- 新的显示名称
  - api_base_url: string (optional) -- 新的 API 基础 URL
  - is_active: boolean (optional) -- 激活状态
Returns: 确认信息及已更改字段列表

Tool: delete_provider
Parameters:
  - provider_id: string (required) -- 要删除的 provider ID
Returns: 确认信息
```

### 模型工具

```
Tool: list_models
Parameters:
  - provider_id: string (required) -- provider 的 ID
Returns: 格式化的模型列表，包含 id、name、source、default、context window

Tool: fetch_models
Parameters:
  - provider_id: string (required) -- provider 的 ID
Returns: 已拉取的模型列表

Tool: set_default_model
Parameters:
  - provider_id: string (required) -- provider 的 ID
  - model_id: string (required) -- 模型 ID
Returns: 确认信息

Tool: add_model
Parameters:
  - provider_id: string (required) -- provider 的 ID
  - model_id: string (required) -- 模型标识符
  - display_name: string (optional) -- 人类可读名称
Returns: 确认信息

Tool: delete_model
Parameters:
  - provider_id: string (required) -- provider 的 ID
  - model_id: string (required) -- 模型 ID
Returns: 确认信息
```

### Agent 工具

```
Tool: list_agents
Parameters: (none)
Returns: 格式化的所有 agent 详情列表

Tool: update_agent
Parameters:
  - agent_id: string (required) -- 要更新的 agent ID
  - name: string (optional) -- 新名称
  - description: string (optional) -- 新描述
  - system_prompt: string (optional) -- 新系统提示词
  - preferred_provider_id: string (optional) -- 新的首选 provider
  - preferred_model_id: string (optional) -- 新的首选模型
Returns: 确认信息及已更改字段列表

Tool: delete_agent
Parameters:
  - agent_id: string (required) -- agent 的 ID
Returns: 确认信息
```

### 设置工具

```
Tool: get_config
Parameters:
  - key: string (required) -- 配置键
Returns: 当前值，或 "not set" 及已知键列表

Tool: set_config
Parameters:
  - key: string (required) -- 配置键
  - value: string (required) -- 配置值
Returns: 确认信息
```

### 工具状态工具

```
Tool: list_tool_states
Parameters: (none)
Returns: 按组织的所有工具及其启用/禁用状态

Tool: set_tool_enabled
Parameters:
  - name: string (required) -- 工具或组名称
  - enabled: boolean (required) -- 启用或禁用
  - type: string (optional, default: "tool") -- "tool" 或 "group"
Returns: 确认信息
```

### 环境变量工具

```
Tool: manage_env_var
Parameters:
  - action: string (required) -- "list"、"set" 或 "delete"
  - key: string (conditional) -- "set" 和 "delete" 时必填
  - value: string (conditional) -- "set" 时必填
Returns: 因操作而异
```

## 错误处理

| 错误 | 原因 | 错误类型 | 处理方式 |
|-------|-------|------------|----------|
| 实体未找到 | 无效的 provider/agent/model/tool ID | `not_found` | 返回描述性错误信息 |
| 验证错误 | 缺少必填参数、值无效 | `validation_error` | 返回错误及有效选项 |
| 权限拒绝 | 修改内置 agent 或删除预配置 provider | `permission_denied` | 返回说明限制原因的错误 |
| 需要 API key | 未设置 API key 时调用 `fetch_models` | `api_key_required` | 返回错误及设置说明 |
| 网络错误 | `fetch_models` 中的 API 调用失败 | `fetch_failed` | 返回附带原始消息的错误 |
| 添加/删除/更新失败 | Repository 操作失败 | `*_failed` | 返回 AppResult.Error 中的错误 |

## 安全考量

1. **API Key 保护**：没有工具会读取、写入或传输 API key。`list_providers` 工具只报告是否已配置 key（布尔值），而不是 key 的值。`ApiKeyStorage` 仅以只读方式使用（仅调用 `hasApiKey`）。

2. **环境变量脱敏**：`manage_env_var` 使用 `action=list` 时只显示键名，不显示值。值存储在 `EncryptedSharedPreferences` 中。

3. **内置实体保护**：内置 agent 不可修改或删除。预配置 provider 不可删除。

4. **输入验证**：所有字符串参数均经过 trim 处理。枚举参数对照允许值进行验证。强制执行长度限制（agent name：100，system prompt：50,000）。

## 性能

| 工具 | 预期耗时 | 备注 |
|------|--------------|-------|
| list_providers | < 50ms | Room 查询 + SharedPrefs 检查 |
| create_provider | < 50ms | Room 插入 |
| update_provider | < 50ms | Room 查询 + 更新 |
| delete_provider | < 50ms | Room 级联删除 |
| list_models | < 50ms | Room 查询 |
| fetch_models | < 30s | 网络 API 调用 |
| set_default_model | < 50ms | Room 查询 + 更新 |
| add_model | < 50ms | Room 插入 |
| delete_model | < 50ms | Room 删除 |
| list_agents | < 50ms | Room 查询 |
| update_agent | < 50ms | Room 查询 + 更新 |
| delete_agent | < 50ms | Room 删除 |
| get_config | < 10ms | Room 查询 |
| set_config | < 20ms | Room 写入 + 主题应用 |
| list_tool_states | < 20ms | 内存中 registry + SharedPrefs |
| set_tool_enabled | < 10ms | SharedPrefs 写入 |
| manage_env_var | < 10ms | EncryptedSharedPreferences |

## 测试策略

### 单元测试

**Provider 工具测试：**
- `ListProvidersToolTest`: testListProviders_empty, testListProviders_withProviders, testListProviders_showsApiKeyStatus
- `CreateProviderToolTest`: testCreateProvider_success, testCreateProvider_missingName, testCreateProvider_invalidType, testCreateProvider_missingUrl
- `UpdateProviderToolTest`: testUpdateProvider_partialUpdate, testUpdateProvider_noChanges, testUpdateProvider_notFound, testUpdateProvider_emptyName
- `DeleteProviderToolTest`: testDeleteProvider_success, testDeleteProvider_notFound, testDeleteProvider_preConfigured

**模型工具测试：**
- `ListModelsToolTest`: testListModels_empty, testListModels_withModels, testListModels_providerNotFound
- `FetchModelsToolTest`: testFetchModels_success, testFetchModels_noApiKey, testFetchModels_providerNotFound, testFetchModels_networkError
- `SetDefaultModelToolTest`: testSetDefault_success, testSetDefault_modelNotFound, testSetDefault_providerNotFound
- `AddModelToolTest`: testAddModel_success, testAddModel_providerNotFound, testAddModel_missingModelId
- `DeleteModelToolTest`: testDeleteModel_success, testDeleteModel_notManual, testDeleteModel_notFound

**Agent 工具测试：**
- `ListAgentsToolTest`: testListAgents_empty, testListAgents_withAgents, testListAgents_showsBuiltInFlag
- `UpdateAgentToolTest`: testUpdateAgent_partialUpdate, testUpdateAgent_builtIn, testUpdateAgent_notFound, testUpdateAgent_emptyName, testUpdateAgent_longSystemPrompt
- `DeleteAgentToolTest`: testDeleteAgent_success, testDeleteAgent_builtIn, testDeleteAgent_notFound

**设置工具测试：**
- `GetConfigToolTest`: testGetConfig_knownKey, testGetConfig_unknownKey, testGetConfig_notSet
- `SetConfigToolTest`: testSetConfig_theme, testSetConfig_invalidTheme, testSetConfig_customKey

**工具状态工具测试：**
- `ListToolStatesToolTest`: testListToolStates_empty, testListToolStates_withTools, testListToolStates_groupDisabled
- `SetToolEnabledToolTest`: testSetToolEnabled_tool, testSetToolEnabled_group, testSetToolEnabled_toolNotFound

**环境变量工具测试：**
- `ManageEnvVarToolTest`: testManageEnvVar_list_empty, testManageEnvVar_list_withVars, testManageEnvVar_set, testManageEnvVar_delete, testManageEnvVar_invalidAction, testManageEnvVar_setMissingKey

### 手动测试（Layer 2）

1. 通过聊天创建 provider，验证其出现在设置 UI 中
2. 为已配置 API key 的 provider 拉取模型
3. 通过聊天设置默认模型，验证下一次对话使用该模型
4. 通过 `create_agent` 创建 agent，通过 `update_agent` 更新，验证 agent 列表中的变更
5. 通过聊天切换主题，验证即时视觉变化
6. 通过聊天禁用某个工具，验证下一次 agent 响应中不再提供该工具
7. 通过聊天设置环境变量，验证其在 JS 工具执行中可访问

## 备选方案考量

### 1. 单一大型工具 + action 参数

**方案**：一个 `app_config` 工具，带有 `action` 参数和条件子参数。
**拒绝原因**：对 AI 工具调用来说过于复杂。基于 action 的条件必填参数使 schema 存在歧义。具有清晰、专注 schema 的独立工具更容易被模型正确使用。

### 2. REST 风格的通用 CRUD 工具

**方案**：一个通用的 `crud` 工具，带有 `entity_type`、`action` 和 `data` 参数。
**拒绝原因**：过度抽象，损失了类型安全性并使参数验证更困难。专用工具提供更好的错误信息和更清晰的描述。

### 3. 仅设置工具（无 Provider/Agent/Model 管理）

**方案**：仅为简单设置添加 get/set config 工具。
**拒绝原因**：不满足"通过提示词 100% 配置应用"的需求。Provider、模型和 agent 管理对完整配置来说是必不可少的。

## 依赖关系

### 外部依赖

- 无（所有操作使用现有内部组件）

### 内部依赖

- `tool/engine/` 中的 `Tool` 接口
- `core/repository/` 中的 `ProviderRepository`
- `core/repository/` 中的 `AgentRepository`
- `core/repository/` 中的 `SettingsRepository`
- `core/theme/` 中的 `ThemeManager`
- `tool/engine/` 中的 `ToolRegistry`
- `tool/engine/` 中的 `ToolEnabledStateStore`
- `tool/js/` 中的 `EnvironmentVariableStore`
- `data/security/` 中的 `ApiKeyStorage`

## 未来扩展

- **Session 管理工具**：列出、重命名、删除聊天 session
- **使用统计工具**：读取每个模型的 token 使用量
- **备份/同步工具**：触发备份、配置同步
- **Provider 连接测试工具**：测试 API 连通性
- **Skill 管理工具**：列出、启用/禁用、导入/导出 skill
- **Memory 管理工具**：读取/写入/清除 memory 条目

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
