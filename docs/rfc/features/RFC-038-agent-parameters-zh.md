# RFC-038: Agent 模型参数（温度与最大迭代次数）

## 文档信息
- **RFC ID**: RFC-038
- **关联 PRD**: [FEAT-038（Agent 模型参数）](../../prd/features/FEAT-038-agent-parameters.md)
- **扩展自**: [RFC-002（Agent 管理）](RFC-002-agent-management.md)、[RFC-020（Agent 增强）](RFC-020-agent-enhancement.md)
- **依赖**: [RFC-002（Agent 管理）](RFC-002-agent-management.md)、[RFC-001（对话交互）](RFC-001-chat-interaction.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景

目前，OneClaw 的 Agent 无法控制模型采样参数或工具调用的迭代次数上限：

1. **温度（Temperature）** 不可配置——每个 Provider 使用其自身的默认值（通常约为 1.0）。无论 Agent 的用途如何，所有 Agent 生成的输出随机程度相同。
2. **最大迭代次数（Max iterations）** 在 `SendMessageUseCase` 中硬编码为 `MAX_TOOL_ROUNDS = 100`。每个 Agent 都受限于同一上限，即使一个简单的问答 Agent 本应更早停止。

用户需要对每个 Agent 单独控制这些参数，以便使 Agent 的行为与其预期用途相匹配。

### 目标

1. 在 Agent 领域模型和 Room 实体中添加 `temperature: Float?` 和 `maxIterations: Int?` 字段
2. 通过 `ModelApiAdapter.sendMessageStream()` 接口将温度参数传递给全部三个 Provider 适配器
3. 在 `SendMessageUseCase` 中以 Agent 的 maxIterations 作为工具循环上限
4. 在 Agent 详情页面添加两个参数的 UI 控件
5. 提供从 v7 到 v8 的 Room 数据库迁移

### 非目标

- 添加其他采样参数（top_p、frequency_penalty、presence_penalty）
- 单条消息级别的温度覆盖
- 最大输出 token 数 / 响应长度控制
- 温度预设 UI（可后续添加）
- 修改 `create_agent` 工具以支持这些参数

## 技术设计

### 架构概览

变更涉及四个层次：数据模型层、数据层（Room + mapper）、API 适配器层、功能层（UI + ViewModel），以及 Chat 用例层。

```
┌─────────────────────────────────────────────────┐
│                    UI Layer                       │
│                                                   │
│  AgentDetailScreen.kt                            │
│  ├── TemperatureField     (new composable)       │
│  └── MaxIterationsField   (new composable)       │
│                                                   │
├─────────────────────────────────────────────────┤
│                 ViewModel Layer                    │
│                                                   │
│  AgentDetailViewModel.kt                         │
│  ├── updateTemperature(Float?)                   │
│  └── updateMaxIterations(Int?)                   │
│                                                   │
├─────────────────────────────────────────────────┤
│                 UseCase Layer                      │
│                                                   │
│  SendMessageUseCase.kt                           │
│  ├── Use agent.maxIterations as loop cap         │
│  └── Pass agent.temperature to adapter           │
│                                                   │
├─────────────────────────────────────────────────┤
│                 API Adapter Layer                  │
│                                                   │
│  ModelApiAdapter.sendMessageStream()             │
│  ├── New parameter: temperature: Float?          │
│  │                                               │
│  ├── OpenAiAdapter     → "temperature": 0.7      │
│  ├── AnthropicAdapter  → "temperature": 0.7      │
│  └── GeminiAdapter     → "temperature": 0.7      │
│                                                   │
├─────────────────────────────────────────────────┤
│                 Data Layer                         │
│                                                   │
│  Agent.kt         (+ temperature, maxIterations) │
│  AgentEntity.kt   (+ temperature, max_iterations)│
│  AgentMapper.kt   (map new fields)               │
│  AppDatabase.kt   (v7 → v8 migration)            │
└─────────────────────────────────────────────────┘
```

### 核心组件

#### 1. 领域模型变更

**文件**: `core/model/Agent.kt`

```kotlin
data class Agent(
    val id: String,
    val name: String,
    val description: String?,
    val systemPrompt: String,
    val preferredProviderId: String?,
    val preferredModelId: String?,
    val temperature: Float?,        // NEW: 0.0-2.0, null = provider default
    val maxIterations: Int?,        // NEW: 1-100, null = global default (100)
    val isBuiltIn: Boolean,
    val createdAt: Long,
    val updatedAt: Long
)
```

#### 2. Room 实体变更

**文件**: `data/local/entity/AgentEntity.kt`

```kotlin
@Entity(tableName = "agents")
data class AgentEntity(
    @PrimaryKey
    val id: String,
    val name: String,
    val description: String?,
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String,
    @ColumnInfo(name = "tool_ids")
    val toolIds: String,
    @ColumnInfo(name = "preferred_provider_id")
    val preferredProviderId: String?,
    @ColumnInfo(name = "preferred_model_id")
    val preferredModelId: String?,
    @ColumnInfo(name = "temperature")           // NEW
    val temperature: Float?,
    @ColumnInfo(name = "max_iterations")        // NEW
    val maxIterations: Int?,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

#### 3. Room 数据库迁移（v7 -> v8）

**文件**: `data/local/db/AppDatabase.kt`

```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN temperature REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE agents ADD COLUMN max_iterations INTEGER DEFAULT NULL")
    }
}
```

在 database builder 中注册：
```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "oneclaw_shadow.db")
    // ... existing migrations ...
    .addMigrations(MIGRATION_7_8)
    .build()
```

将数据库版本更新为 8，并更新种子数据回调以包含新列（默认值为 NULL）。

#### 4. Mapper 变更

**文件**: `data/local/mapper/AgentMapper.kt`

```kotlin
fun AgentEntity.toDomain(): Agent = Agent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    temperature = temperature,              // NEW
    maxIterations = maxIterations,          // NEW
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Agent.toEntity(): AgentEntity = AgentEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = "[]",
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    temperature = temperature,              // NEW
    maxIterations = maxIterations,          // NEW
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)
```

#### 5. ModelApiAdapter 接口变更

**文件**: `data/remote/adapter/ModelApiAdapter.kt`

在 `sendMessageStream()` 中添加可选的 `temperature` 参数：

```kotlin
interface ModelApiAdapter {
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?,
        temperature: Float? = null      // NEW
    ): Flow<StreamEvent>

    // ... other methods unchanged ...
}
```

#### 6. OpenAI 适配器变更

**文件**: `data/remote/adapter/OpenAiAdapter.kt`

更新 `sendMessageStream()` 签名以接受 `temperature` 并传递给 `buildOpenAiRequest()`：

```kotlin
override fun sendMessageStream(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    temperature: Float?             // NEW
): Flow<StreamEvent> = channelFlow {
    // ... existing code ...
    val requestBody = buildOpenAiRequest(modelId, apiMessages, formattedTools, temperature)
    // ...
}

private fun buildOpenAiRequest(
    modelId: String,
    messages: List<JsonObject>,
    tools: List<JsonObject>?,
    temperature: Float?             // NEW
): String {
    return buildJsonObject {
        put("model", modelId)
        put("stream", true)
        // ... existing fields ...
        if (temperature != null) {
            put("temperature", temperature.toDouble())
        }
    }.toString()
}
```

#### 7. Anthropic 适配器变更

**文件**: `data/remote/adapter/AnthropicAdapter.kt`

采用相同模式——添加 `temperature` 参数并将其包含在请求 JSON 中：

```kotlin
override fun sendMessageStream(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    temperature: Float?             // NEW
): Flow<StreamEvent> = channelFlow {
    // ...
    val requestBody = buildAnthropicRequest(modelId, apiMessages, formattedTools, systemPrompt, temperature)
    // ...
}

private fun buildAnthropicRequest(
    modelId: String,
    messages: List<JsonObject>,
    tools: List<JsonObject>?,
    systemPrompt: String?,
    temperature: Float?             // NEW
): String {
    return buildJsonObject {
        put("model", modelId)
        put("max_tokens", 16000)
        put("stream", true)
        // ... existing fields ...
        if (temperature != null) {
            put("temperature", temperature.toDouble())
        }
    }.toString()
}
```

**注意**：Anthropic 的扩展思考模式对温度有约束（启用思考模式时温度必须为 1.0）。若适配器检测到思考模式已启用且温度设置为非 1.0 的值，应忽略该温度或记录警告日志。在 V1 阶段，由于当前实现始终启用思考模式，适配器应仅在思考模式禁用时应用温度设置，或在文档中说明此限制。

#### 8. Gemini 适配器变更

**文件**: `data/remote/adapter/GeminiAdapter.kt`

采用相同模式：

```kotlin
override fun sendMessageStream(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    temperature: Float?             // NEW
): Flow<StreamEvent> = channelFlow {
    // ...
    val requestBody = buildGeminiRequest(modelId, apiMessages, formattedTools, systemPrompt, temperature)
    // ...
}

private fun buildGeminiRequest(
    modelId: String,
    messages: List<JsonObject>,
    tools: List<JsonObject>?,
    systemPrompt: String?,
    temperature: Float?             // NEW
): String {
    return buildJsonObject {
        // ... existing fields ...
        putJsonObject("generationConfig") {
            put("maxOutputTokens", 8192)
            if (temperature != null) {
                put("temperature", temperature.toDouble())
            }
        }
    }.toString()
}
```

#### 9. SendMessageUseCase 变更

**文件**: `feature/chat/usecase/SendMessageUseCase.kt`

两项变更：

1. 使用 `agent.maxIterations` 作为循环上限
2. 将 `agent.temperature` 传递给适配器

```kotlin
fun execute(
    sessionId: String,
    userText: String,
    agentId: String,
    pendingMessages: Channel<String> = Channel(Channel.UNLIMITED)
): Flow<ChatEvent> = channelFlow {
    // 1. Resolve agent
    val agent = agentRepository.getAgentById(agentId) ?: run { ... }

    // Determine effective max iterations
    val effectiveMaxRounds = agent.maxIterations ?: MAX_TOOL_ROUNDS

    // ... existing setup code ...

    while (round < effectiveMaxRounds) {       // CHANGED: was MAX_TOOL_ROUNDS
        // ...
        adapter.sendMessageStream(
            apiBaseUrl = provider.apiBaseUrl,
            apiKey = apiKey,
            modelId = model.id,
            messages = apiMessages,
            tools = agentToolDefs,
            systemPrompt = effectiveSystemPrompt,
            temperature = agent.temperature    // NEW
        ).collect { event -> ... }
        // ...
        round++
        if (round < effectiveMaxRounds) {      // CHANGED: was MAX_TOOL_ROUNDS
            send(ChatEvent.ToolRoundStarting(round))
        }
    }

    if (round >= effectiveMaxRounds) {         // CHANGED: was MAX_TOOL_ROUNDS
        send(ChatEvent.Error(
            "Reached maximum tool call rounds ($effectiveMaxRounds). Stopping.",
            ErrorCode.TOOL_ERROR, false
        ))
    }
}
```

#### 10. AgentDetailUiState 变更

**文件**: `feature/agent/AgentUiState.kt`

```kotlin
data class AgentDetailUiState(
    val agentId: String? = null,
    val isBuiltIn: Boolean = false,
    val isNewAgent: Boolean = false,

    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null,
    val temperature: Float? = null,            // NEW
    val maxIterations: Int? = null,            // NEW

    // Snapshot of persisted values used to derive hasUnsavedChanges
    val savedName: String = "",
    val savedDescription: String = "",
    val savedSystemPrompt: String = "",
    val savedPreferredProviderId: String? = null,
    val savedPreferredModelId: String? = null,
    val savedTemperature: Float? = null,       // NEW
    val savedMaxIterations: Int? = null,       // NEW

    val availableModels: List<ModelOptionItem> = emptyList(),

    val generatePrompt: String = "",
    val isGenerating: Boolean = false,

    // Validation errors
    val temperatureError: String? = null,      // NEW
    val maxIterationsError: String? = null,    // NEW

    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showDeleteDialog: Boolean = false,
    val navigateBack: Boolean = false
) {
    val hasUnsavedChanges: Boolean
        get() = if (isNewAgent) {
            name.isNotBlank()
        } else {
            name != savedName ||
            description != savedDescription ||
            systemPrompt != savedSystemPrompt ||
            preferredProviderId != savedPreferredProviderId ||
            preferredModelId != savedPreferredModelId ||
            temperature != savedTemperature ||           // NEW
            maxIterations != savedMaxIterations          // NEW
        }
}
```

#### 11. AgentDetailViewModel 变更

**文件**: `feature/agent/AgentDetailViewModel.kt`

添加更新与校验方法：

```kotlin
fun updateTemperature(value: Float?) {
    val error = if (value != null && (value < 0f || value > 2f)) {
        "Temperature must be between 0.0 and 2.0"
    } else null
    _uiState.update { it.copy(temperature = value, temperatureError = error) }
}

fun updateMaxIterations(value: Int?) {
    val error = if (value != null && (value < 1 || value > 100)) {
        "Max iterations must be between 1 and 100"
    } else null
    _uiState.update { it.copy(maxIterations = value, maxIterationsError = error) }
}
```

更新 `loadAgent()` 以填充新字段：

```kotlin
private fun loadAgent(agentId: String) {
    viewModelScope.launch {
        val agent = agentRepository.getAgentById(agentId)
        if (agent != null) {
            _uiState.update {
                it.copy(
                    // ... existing fields ...
                    temperature = agent.temperature,
                    maxIterations = agent.maxIterations,
                    savedTemperature = agent.temperature,
                    savedMaxIterations = agent.maxIterations,
                    // ...
                )
            }
        }
    }
}
```

更新 `saveAgent()` 以包含新字段，并在校验出错时阻止保存：

```kotlin
fun saveAgent() {
    val state = _uiState.value
    if (state.temperatureError != null || state.maxIterationsError != null) return

    val agent = Agent(
        // ... existing fields ...
        temperature = state.temperature,
        maxIterations = state.maxIterations,
        // ...
    )
    // ... existing save logic ...
}
```

#### 12. AgentDetailScreen UI 变更

**文件**: `feature/agent/AgentDetailScreen.kt`

在"首选模型"下拉框之后新增两个 Composable：

```kotlin
// Temperature field
@Composable
private fun TemperatureField(
    temperature: Float?,
    error: String?,
    onUpdate: (Float?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "TEMPERATURE (optional)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (temperature != null) {
            // Slider + value display
            Row(verticalAlignment = Alignment.CenterVertically) {
                Slider(
                    value = temperature,
                    onValueChange = { onUpdate(it) },
                    valueRange = 0f..2f,
                    steps = 19, // 0.0, 0.1, 0.2, ..., 2.0
                    enabled = enabled,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "%.1f".format(temperature),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            TextButton(
                onClick = { onUpdate(null) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Clear")
            }
        } else {
            OutlinedButton(
                onClick = { onUpdate(1.0f) }, // Set to 1.0 as starting value
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Set temperature (provider default when not set)")
            }
        }

        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

// Max iterations field
@Composable
private fun MaxIterationsField(
    maxIterations: Int?,
    error: String?,
    onUpdate: (Int?) -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = "MAX ITERATIONS (optional)",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (maxIterations != null) {
            OutlinedTextField(
                value = maxIterations.toString(),
                onValueChange = { text ->
                    val parsed = text.filter { it.isDigit() }.toIntOrNull()
                    onUpdate(parsed)
                },
                label = { Text("Max iterations (1-100)") },
                enabled = enabled,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error != null,
                supportingText = error?.let { { Text(it) } },
                modifier = Modifier.fillMaxWidth()
            )
            TextButton(
                onClick = { onUpdate(null) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Clear")
            }
        } else {
            OutlinedButton(
                onClick = { onUpdate(10) }, // Set to 10 as a reasonable starting value
                enabled = enabled,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Set max iterations (default: 100 when not set)")
            }
        }
    }
}
```

将两个组件集成到 `AgentDetailScreen` 的 LazyColumn 中：

```kotlin
// After PreferredModelDropdown item
item {
    TemperatureField(
        temperature = uiState.temperature,
        error = uiState.temperatureError,
        onUpdate = { viewModel.updateTemperature(it) },
        enabled = !uiState.isBuiltIn,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    )
}

item { Spacer(modifier = Modifier.height(16.dp)) }

item {
    MaxIterationsField(
        maxIterations = uiState.maxIterations,
        error = uiState.maxIterationsError,
        onUpdate = { viewModel.updateMaxIterations(it) },
        enabled = !uiState.isBuiltIn,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)
    )
}
```

### 数据模型

#### 变更摘要

| 组件 | 字段 | 类型 | 默认值 | 列名 |
|-----------|-------|------|---------|-------------|
| `Agent` | `temperature` | `Float?` | `null` | - |
| `Agent` | `maxIterations` | `Int?` | `null` | - |
| `AgentEntity` | `temperature` | `Float?` | `null` | `temperature` |
| `AgentEntity` | `maxIterations` | `Int?` | `null` | `max_iterations` |

Room 列类型：temperature 使用 `REAL`，max_iterations 使用 `INTEGER`。

### API 设计

#### 修改后的接口

```kotlin
// ModelApiAdapter.kt -- only sendMessageStream changes
fun sendMessageStream(
    apiBaseUrl: String,
    apiKey: String,
    modelId: String,
    messages: List<ApiMessage>,
    tools: List<ToolDefinition>?,
    systemPrompt: String?,
    temperature: Float? = null      // NEW -- default null preserves backward compat
): Flow<StreamEvent>
```

默认值 `null` 确保所有现有调用方（例如 `generateSimpleCompletion` 内部用法）无需修改即可继续正常工作。

### UI 层设计

#### AgentDetailScreen 布局（更新后）

```
┌──────────────────────────────────┐
│ <- Edit Agent              [Save]│
├──────────────────────────────────┤
│ GENERATE FROM PROMPT             │  (create mode only)
│ ┌──────────────────────────────┐ │
│ │ Describe the agent...        │ │
│ └──────────────────────────────┘ │
│                     [Generate]   │
├──────────────────────────────────┤
│ ┌──────────────────────────────┐ │
│ │ Name                         │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │ Description (optional)       │ │
│ └──────────────────────────────┘ │
│ ┌──────────────────────────────┐ │
│ │ System Prompt *              │ │
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│ PREFERRED MODEL (optional)       │
│ ┌──────────────────────────────┐ │
│ │ Using global default       v │ │
│ └──────────────────────────────┘ │
├──────────────────────────────────┤
│ TEMPERATURE (optional)           │
│ [0.0 ========|======== 2.0] 0.7 │
│ Provider default when not set    │
│                        [Clear]   │
├──────────────────────────────────┤
│ MAX ITERATIONS (optional)        │
│ ┌──────────────────────────────┐ │
│ │ 10                           │ │
│ └──────────────────────────────┘ │
│ Default: 100 when not set        │
│                        [Clear]   │
├──────────────────────────────────┤
│ [      Clone Agent             ] │
│ [      Delete Agent            ] │
└──────────────────────────────────┘
```

### 依赖注入

无需新增 Koin 模块注册。现有的 ViewModel 和 UseCase 注册已包含所有必要依赖。唯一的变更是 `SendMessageUseCase` 从已获取的 `Agent` 对象中读取两个额外字段。

## 实现步骤

### 第一阶段：数据层（模型 + 实体 + 迁移 + mapper）
1. [ ] 在 `Agent.kt` 中添加 `temperature: Float?` 和 `maxIterations: Int?`
2. [ ] 在 `AgentEntity.kt` 中添加 `temperature: Float?` 和 `maxIterations: Int?` 列
3. [ ] 在 `AppDatabase.kt` 中创建 `MIGRATION_7_8`（ALTER TABLE ADD COLUMN x2）
4. [ ] 将数据库版本更新为 8
5. [ ] 在 database builder 中注册迁移
6. [ ] 更新种子数据回调 INSERT 语句以包含新列（默认值为 NULL）
7. [ ] 更新 `AgentMapper.kt` 以映射两个新字段
8. [ ] 为 mapper 添加单元测试，覆盖 null 和非 null 值

### 第二阶段：API 适配器层（温度透传）
1. [ ] 在 `ModelApiAdapter.sendMessageStream()` 中添加 `temperature: Float? = null` 参数
2. [ ] 更新 `OpenAiAdapter.sendMessageStream()` 和 `buildOpenAiRequest()` 以接受并传递 temperature
3. [ ] 更新 `AnthropicAdapter.sendMessageStream()` 和 `buildAnthropicRequest()` 以接受并传递 temperature
4. [ ] 更新 `GeminiAdapter.sendMessageStream()` 和 `buildGeminiRequest()` 以接受并传递 temperature
5. [ ] 添加单元测试，验证非 null 时 temperature 包含在请求 JSON 中
6. [ ] 添加单元测试，验证 null 时 temperature 被省略

### 第三阶段：Chat 管道（SendMessageUseCase）
1. [ ] 将循环条件从 `MAX_TOOL_ROUNDS` 改为 `agent.maxIterations ?: MAX_TOOL_ROUNDS`
2. [ ] 将 `agent.temperature` 作为 temperature 参数传递给 `adapter.sendMessageStream()`
3. [ ] 更新达到最大轮次时的错误信息，使用有效上限值
4. [ ] 添加自定义 maxIterations 行为的单元测试
5. [ ] 添加单元测试，验证 temperature 被正确转发给适配器

### 第四阶段：UI 层（AgentDetailScreen + ViewModel）
1. [ ] 在 `AgentDetailUiState` 中添加 `temperature`、`maxIterations`、`savedTemperature`、`savedMaxIterations`、`temperatureError`、`maxIterationsError`
2. [ ] 更新 `hasUnsavedChanges` 以包含 temperature 和 maxIterations 的比较
3. [ ] 在 `AgentDetailViewModel` 中添加 `updateTemperature(Float?)` 和 `updateMaxIterations(Int?)`
4. [ ] 更新 `loadAgent()` 以从 agent 中填充新字段
5. [ ] 更新 `saveAgent()` 以包含新字段并在校验出错时阻止保存
6. [ ] 创建 `TemperatureField` Composable
7. [ ] 创建 `MaxIterationsField` Composable
8. [ ] 将两个 Composable 集成到 `AgentDetailScreen` 的 LazyColumn 中
9. [ ] 添加 ViewModel 单元测试，覆盖更新与校验逻辑
10. [ ] 添加 ViewModel 单元测试，覆盖包含新字段的保存逻辑

## 测试策略

### 单元测试

**数据层：**
- `AgentMapper`：验证 `toDomain()` 和 `toEntity()` 在 null 和非 null 两种情况下正确映射 temperature 和 maxIterations
- Room 迁移测试：验证 MIGRATION_7_8 在不丢失数据的前提下添加两个新列

**API 适配器：**
- `OpenAiAdapter`：验证 `buildOpenAiRequest()` 在设置时包含 `"temperature": 0.7`，为 null 时省略
- `AnthropicAdapter`：验证 `buildAnthropicRequest()` 在设置时包含 temperature，为 null 时省略
- `GeminiAdapter`：验证 `buildGeminiRequest()` 在设置时将 temperature 包含在 `generationConfig` 中，为 null 时省略

**SendMessageUseCase：**
- 验证设置 `agent.maxIterations` 后循环在指定轮次停止
- 验证 `agent.maxIterations` 为 null 时循环使用 `MAX_TOOL_ROUNDS`
- 验证 `agent.temperature` 被传递给适配器调用
- 验证未设置时 null temperature 被传递给适配器

**AgentDetailViewModel：**
- `updateTemperature(0.7f)`：状态更新，无错误
- `updateTemperature(2.5f)`：状态更新，设置错误
- `updateTemperature(null)`：状态清除为 null，无错误
- `updateMaxIterations(10)`：状态更新，无错误
- `updateMaxIterations(0)`：状态更新，设置错误
- `updateMaxIterations(101)`：状态更新，设置错误
- `updateMaxIterations(null)`：状态清除为 null，无错误
- 存在校验错误时 `saveAgent()` 不执行保存
- `hasUnsavedChanges` 能检测到 temperature 变化
- `hasUnsavedChanges` 能检测到 maxIterations 变化

### 集成测试

- 创建 temperature = 0.5、maxIterations = 10 的 Agent，重新加载，验证值已持久化
- 更新 Agent 以清除 temperature（设为 null），重新加载，验证为 null

### 手动测试

- 打开内置 General Assistant 的 Agent 详情——两个字段应显示"未设置"或默认状态
- 创建新 Agent，将 temperature 设为 0.3、max iterations 设为 5，保存后重新打开，验证值正确
- 编辑自定义 Agent，通过滑块调整 temperature，验证数值实时更新
- 编辑自定义 Agent，将 max iterations 设为 0——应显示校验错误，保存被阻止
- 克隆一个 temperature 为 0.8 的 Agent——克隆后的 Agent 应具有相同的 temperature 0.8
- 使用 temperature 为 0.2 的 Agent 发起对话——验证 API 请求中包含 temperature（查看 logcat）
- 使用 maxIterations 为 3 的 Agent 发起对话——验证工具调用循环在 3 轮后停止

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
