# RFC-038: Agent Model Parameters (Temperature & Max Iterations)

## Document Information
- **RFC ID**: RFC-038
- **Related PRD**: [FEAT-038 (Agent Model Parameters)](../../prd/features/FEAT-038-agent-parameters.md)
- **Extends**: [RFC-002 (Agent Management)](RFC-002-agent-management.md), [RFC-020 (Agent Enhancement)](RFC-020-agent-enhancement.md)
- **Depends On**: [RFC-002 (Agent Management)](RFC-002-agent-management.md), [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

Currently, OneClaw agents have no control over model sampling parameters or tool-use iteration limits:

1. **Temperature** is not configurable -- each provider uses its own default (typically ~1.0). All agents produce output with the same level of randomness regardless of their purpose.
2. **Max iterations** is hardcoded to `MAX_TOOL_ROUNDS = 100` in `SendMessageUseCase`. Every agent gets the same cap, even if a simple Q&A agent should stop much sooner.

Users need per-agent control over these parameters to match each agent's behavior to its intended use case.

### Goals

1. Add `temperature: Float?` and `maxIterations: Int?` fields to the Agent domain model and Room entity
2. Pass temperature through the `ModelApiAdapter.sendMessageStream()` interface to all three provider adapters
3. Use the agent's max iterations as the tool-loop cap in `SendMessageUseCase`
4. Add UI controls on the Agent Detail screen for both parameters
5. Provide a Room migration from v7 to v8

### Non-Goals

- Adding other sampling parameters (top_p, frequency_penalty, presence_penalty)
- Per-message temperature override
- Max output tokens / response length control
- Temperature presets UI (can be added later)
- Changing the `create_agent` tool to accept these parameters

## Technical Design

### Architecture Overview

Changes span four layers: data model, data layer (Room + mapper), API adapter layer, feature layer (UI + ViewModel), and the chat use case.

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

### Core Components

#### 1. Domain Model Changes

**File**: `core/model/Agent.kt`

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

#### 2. Room Entity Changes

**File**: `data/local/entity/AgentEntity.kt`

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

#### 3. Room Migration (v7 -> v8)

**File**: `data/local/db/AppDatabase.kt`

```kotlin
val MIGRATION_7_8 = object : Migration(7, 8) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE agents ADD COLUMN temperature REAL DEFAULT NULL")
        db.execSQL("ALTER TABLE agents ADD COLUMN max_iterations INTEGER DEFAULT NULL")
    }
}
```

Register in database builder:
```kotlin
Room.databaseBuilder(context, AppDatabase::class.java, "oneclaw_shadow.db")
    // ... existing migrations ...
    .addMigrations(MIGRATION_7_8)
    .build()
```

Update database version to 8 and update the seed callback to include the new columns (with NULL defaults).

#### 4. Mapper Changes

**File**: `data/local/mapper/AgentMapper.kt`

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

#### 5. ModelApiAdapter Interface Change

**File**: `data/remote/adapter/ModelApiAdapter.kt`

Add an optional `temperature` parameter to `sendMessageStream()`:

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

#### 6. OpenAI Adapter Changes

**File**: `data/remote/adapter/OpenAiAdapter.kt`

Update `sendMessageStream()` signature to accept `temperature` and pass it to `buildOpenAiRequest()`:

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

#### 7. Anthropic Adapter Changes

**File**: `data/remote/adapter/AnthropicAdapter.kt`

Same pattern -- add `temperature` parameter and include in the request JSON:

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

**Note**: Anthropic's extended thinking mode has constraints on temperature (must be 1.0 when thinking is enabled). If the adapter detects thinking is enabled and temperature is set to a non-1.0 value, it should omit the temperature or log a warning. For V1, since thinking is always enabled in the current implementation, the adapter should only apply temperature when thinking is disabled, or document this limitation.

#### 8. Gemini Adapter Changes

**File**: `data/remote/adapter/GeminiAdapter.kt`

Same pattern:

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

#### 9. SendMessageUseCase Changes

**File**: `feature/chat/usecase/SendMessageUseCase.kt`

Two changes:

1. Use `agent.maxIterations` as the loop cap
2. Pass `agent.temperature` to the adapter

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

#### 10. AgentDetailUiState Changes

**File**: `feature/agent/AgentUiState.kt`

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

#### 11. AgentDetailViewModel Changes

**File**: `feature/agent/AgentDetailViewModel.kt`

Add update and validation methods:

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

Update `loadAgent()` to populate the new fields:

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

Update `saveAgent()` to include the new fields and block save on validation errors:

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

#### 12. AgentDetailScreen UI Changes

**File**: `feature/agent/AgentDetailScreen.kt`

Add two new composables after the Preferred Model dropdown:

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

Integrate into the LazyColumn in `AgentDetailScreen`:

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

### Data Model

#### Changes Summary

| Component | Field | Type | Default | Column Name |
|-----------|-------|------|---------|-------------|
| `Agent` | `temperature` | `Float?` | `null` | - |
| `Agent` | `maxIterations` | `Int?` | `null` | - |
| `AgentEntity` | `temperature` | `Float?` | `null` | `temperature` |
| `AgentEntity` | `maxIterations` | `Int?` | `null` | `max_iterations` |

Room column types: `REAL` for temperature, `INTEGER` for max_iterations.

### API Design

#### Modified Interface

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

The default value `null` ensures all existing callers (e.g., `generateSimpleCompletion` internal usage) continue to work without modification.

### UI Layer Design

#### AgentDetailScreen Layout (Updated)

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

### Dependency Injection

No new Koin module registrations needed. Existing ViewModel and UseCase registrations already receive all required dependencies. The only change is that `SendMessageUseCase` now reads two additional fields from the `Agent` object it already retrieves.

## Implementation Steps

### Phase 1: Data Layer (model + entity + migration + mapper)
1. [ ] Add `temperature: Float?` and `maxIterations: Int?` to `Agent.kt`
2. [ ] Add `temperature: Float?` and `maxIterations: Int?` columns to `AgentEntity.kt`
3. [ ] Create `MIGRATION_7_8` in `AppDatabase.kt` (ALTER TABLE ADD COLUMN x2)
4. [ ] Update database version to 8
5. [ ] Register migration in database builder
6. [ ] Update seed callback INSERT to include the new columns (NULL defaults)
7. [ ] Update `AgentMapper.kt` to map both new fields
8. [ ] Add unit tests for mapper with null and non-null values

### Phase 2: API Adapter Layer (temperature passthrough)
1. [ ] Add `temperature: Float? = null` parameter to `ModelApiAdapter.sendMessageStream()`
2. [ ] Update `OpenAiAdapter.sendMessageStream()` and `buildOpenAiRequest()` to accept and include temperature
3. [ ] Update `AnthropicAdapter.sendMessageStream()` and `buildAnthropicRequest()` to accept and include temperature
4. [ ] Update `GeminiAdapter.sendMessageStream()` and `buildGeminiRequest()` to accept and include temperature
5. [ ] Add unit tests verifying temperature is included in request JSON when non-null
6. [ ] Add unit tests verifying temperature is omitted when null

### Phase 3: Chat Pipeline (SendMessageUseCase)
1. [ ] Change loop condition from `MAX_TOOL_ROUNDS` to `agent.maxIterations ?: MAX_TOOL_ROUNDS`
2. [ ] Pass `agent.temperature` as the temperature parameter to `adapter.sendMessageStream()`
3. [ ] Update the max-rounds-reached error message to use the effective limit
4. [ ] Add unit tests for custom max iterations behavior
5. [ ] Add unit tests verifying temperature is forwarded to adapter

### Phase 4: UI Layer (AgentDetailScreen + ViewModel)
1. [ ] Add `temperature`, `maxIterations`, `savedTemperature`, `savedMaxIterations`, `temperatureError`, `maxIterationsError` to `AgentDetailUiState`
2. [ ] Update `hasUnsavedChanges` to include temperature and maxIterations comparison
3. [ ] Add `updateTemperature(Float?)` and `updateMaxIterations(Int?)` to `AgentDetailViewModel`
4. [ ] Update `loadAgent()` to populate new fields from agent
5. [ ] Update `saveAgent()` to include new fields and block on validation errors
6. [ ] Create `TemperatureField` composable
7. [ ] Create `MaxIterationsField` composable
8. [ ] Integrate both composables into `AgentDetailScreen` LazyColumn
9. [ ] Add ViewModel unit tests for update + validation
10. [ ] Add ViewModel unit tests for save with new fields

## Testing Strategy

### Unit Tests

**Data Layer:**
- `AgentMapper`: verify `toDomain()` and `toEntity()` map temperature and maxIterations correctly (null and non-null)
- Room migration test: verify MIGRATION_7_8 adds both columns without data loss

**API Adapters:**
- `OpenAiAdapter`: verify `buildOpenAiRequest()` includes `"temperature": 0.7` when set, omits when null
- `AnthropicAdapter`: verify `buildAnthropicRequest()` includes temperature when set, omits when null
- `GeminiAdapter`: verify `buildGeminiRequest()` includes temperature in `generationConfig` when set, omits when null

**SendMessageUseCase:**
- Verify loop stops after `agent.maxIterations` rounds when set
- Verify loop uses `MAX_TOOL_ROUNDS` when `agent.maxIterations` is null
- Verify `agent.temperature` is passed to adapter call
- Verify null temperature is passed to adapter when not set

**AgentDetailViewModel:**
- `updateTemperature(0.7f)`: state updates, no error
- `updateTemperature(2.5f)`: state updates, error set
- `updateTemperature(null)`: state clears to null, no error
- `updateMaxIterations(10)`: state updates, no error
- `updateMaxIterations(0)`: state updates, error set
- `updateMaxIterations(101)`: state updates, error set
- `updateMaxIterations(null)`: state clears to null, no error
- `saveAgent()` with validation errors: does not save
- `hasUnsavedChanges` detects temperature change
- `hasUnsavedChanges` detects maxIterations change

### Integration Tests

- Create agent with temperature = 0.5 and maxIterations = 10, reload, verify values persist
- Update agent to clear temperature (set to null), reload, verify null

### Manual Tests

- Open Agent Detail for built-in General Assistant -- both fields should show "not set" / default state
- Create new agent, set temperature to 0.3 and max iterations to 5, save, re-open, verify values
- Edit custom agent, change temperature via slider, verify value updates in real time
- Edit custom agent, set max iterations to 0 -- should show validation error, save blocked
- Clone an agent with temperature 0.8 -- cloned agent should have temperature 0.8
- Start chat with agent that has temperature 0.2 -- verify API request includes temperature (check logcat)
- Start chat with agent that has maxIterations 3 -- verify tool loop stops after 3 rounds

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
