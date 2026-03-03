# RFC-002: Agent Management

## Document Information
- **RFC ID**: RFC-002
- **Related PRD**: [FEAT-002 (Agent Management)](../../prd/features/FEAT-002-agent.md)
- **Related Design**: [UI Design Spec](../../design/ui-design-spec.md) (Sections 5, 6; Agent Selector in Section 2)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-003 (Provider Management)](RFC-003-provider-management.md), [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Depended On By**: RFC-001 (Chat Interaction), RFC-005 (Session Management)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
Agent Management defines how users create, configure, and manage AI agents in OneClaw. An Agent is a pre-configured AI persona with a name, description, system prompt, tool set, and optional preferred model/provider. The app ships with a built-in "General Assistant" agent that is the default for all new sessions. Users can clone built-in agents and create fully custom agents. Agents can be switched mid-conversation.

This RFC covers agent data persistence, CRUD operations, the built-in agent seeding mechanism, the clone operation, model resolution logic, and the agent management UI. The actual agent switching within an active chat session (updating session state, inserting system messages) is deferred to RFC-005 (Session Management) and RFC-001 (Chat Interaction). The tool selection UI references tools from the `ToolRegistry` defined in RFC-004.

### Goals
1. Implement agent data persistence (Room entity, DAO, repository)
2. Implement built-in "General Assistant" agent seeding via Room `onCreate` callback
3. Implement agent CRUD operations (create, read, update, delete) with business rule enforcement
4. Implement agent clone operation
5. Implement model resolution logic (agent preferred -> global default)
6. Implement agent management UI (list screen, detail/edit screen, create screen)
7. Implement agent selector bottom sheet for chat screen (used by RFC-001)
8. Provide enough implementation detail for AI-assisted code generation

### Non-Goals
- Agent switching logic in active chat session (RFC-001/RFC-005 handles session state update)
- Agent switch system message in chat (RFC-001)
- Agent-specific conversation history
- Agent sharing / import / export
- Sub-agent / multi-agent orchestration
- Agent versioning
- Agent icons / avatars
- Agent scheduling or automation

## Technical Design

### Architecture Overview

```
+-----------------------------------------------------------------+
|                         UI Layer                                 |
|  AgentListScreen    AgentDetailScreen    AgentSelectorSheet      |
|       |                   |                    |                 |
|       v                   v                    v                 |
|  AgentListViewModel  AgentDetailViewModel  (shared ViewModel)    |
+-----------------------------------------------------------------+
|                       Domain Layer                               |
|  CreateAgentUseCase  CloneAgentUseCase  DeleteAgentUseCase       |
|  GetAgentToolsUseCase  ResolveModelUseCase                       |
|       |                                                          |
|       v                                                          |
|  AgentRepository (interface)                                     |
+-----------------------------------------------------------------+
|                        Data Layer                                |
|  AgentRepositoryImpl                                             |
|       |           |              |                               |
|       v           v              v                               |
|  AgentDao    ToolRegistry   ProviderRepository                   |
|             (for tool list)  (for model picker)                  |
+-----------------------------------------------------------------+
```

### Core Components

1. **AgentRepositoryImpl**
   - Responsibility: Agent CRUD operations, business rule enforcement (built-in protection, deletion fallback)
   - Dependencies: AgentDao
   - Interface: Implements `AgentRepository` from Core module

2. **Built-in Agent Seeder**
   - Responsibility: Inserts the "General Assistant" agent into Room on first DB creation
   - Mechanism: Room `RoomDatabase.Callback.onCreate` (same callback as provider seeding from RFC-003)
   - Fixed ID: `agent-general-assistant`

3. **Use Cases**
   - `CreateAgentUseCase`: Validates and persists a new custom agent
   - `CloneAgentUseCase`: Creates an editable copy of any agent (built-in or custom)
   - `DeleteAgentUseCase`: Deletes custom agent, updates sessions referencing it
   - `GetAgentToolsUseCase`: Resolves an agent's tool IDs to `ToolDefinition` objects from `ToolRegistry`
   - `ResolveModelUseCase`: Resolves which model/provider to use (agent preferred -> global default)

4. **AgentSelectorSheet**
   - Responsibility: Bottom sheet composable showing agent list for mid-conversation switching
   - Used by: Chat screen (RFC-001)
   - This RFC defines the composable; RFC-001 integrates it into chat flow

## Data Model

### Domain Models

The `Agent` model is already declared in RFC-000. This section provides the complete definition used in this RFC.

#### Agent (No Change from RFC-000)

```kotlin
data class Agent(
    val id: String,                    // UUID or fixed ID for built-in
    val name: String,                  // Display name (e.g., "General Assistant")
    val description: String?,          // Optional description
    val systemPrompt: String,          // System prompt text
    val toolIds: List<String>,         // Tool names this agent can use (e.g., ["read_file", "write_file"])
    val preferredProviderId: String?,  // Optional preferred provider
    val preferredModelId: String?,     // Optional preferred model
    val isBuiltIn: Boolean,            // Whether this is a built-in agent (read-only)
    val createdAt: Long,               // Timestamp millis
    val updatedAt: Long                // Timestamp millis
)
```

**Notes on `toolIds`:**
- These are tool **names** (e.g., `"read_file"`, `"http_request"`), not arbitrary IDs. They correspond to `ToolDefinition.name` in the `ToolRegistry` (see RFC-004).
- An empty list means the agent has no tools (chat-only mode).
- When displaying tools in the UI, the list of available tools comes from `ToolRegistry.getAllToolDefinitions()`.
- When building the API request, tools are resolved via `ToolRegistry.getToolDefinitionsByNames(agent.toolIds)`.

**Notes on `preferredProviderId` / `preferredModelId`:**
- Both are optional. When null, the global default model/provider is used.
- When set, both must be set together (a model without a provider is meaningless).
- The preferred model references `AiModel.id` and `Provider.id` from RFC-003.

### Room Entity

#### AgentEntity

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
    val toolIds: String,               // JSON array string: ["read_file", "write_file"]
    @ColumnInfo(name = "preferred_provider_id")
    val preferredProviderId: String?,
    @ColumnInfo(name = "preferred_model_id")
    val preferredModelId: String?,
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)
```

### Database Schema

```sql
CREATE TABLE agents (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    tool_ids TEXT NOT NULL,           -- JSON array of tool name strings
    preferred_provider_id TEXT,
    preferred_model_id TEXT,
    is_built_in INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

This matches the schema already declared in RFC-000.

### Type Converter

The `tool_ids` field is stored as a JSON array string in Room. A type converter handles serialization:

```kotlin
// In Converters.kt (shared type converters for AppDatabase)
class Converters {

    @TypeConverter
    fun fromStringList(value: List<String>): String {
        return Json.encodeToString(value)
    }

    @TypeConverter
    fun toStringList(value: String): List<String> {
        return try {
            Json.decodeFromString(value)
        } catch (e: Exception) {
            emptyList()
        }
    }
}
```

**Note:** This type converter is used by the entity-domain mapper, not directly by Room. The entity stores `toolIds` as a raw `String` (JSON), and the mapper converts to/from `List<String>`. This avoids Room needing to understand the `List<String>` type directly.

### Entity-Domain Mappers

```kotlin
// AgentMapper.kt
fun AgentEntity.toDomain(): Agent = Agent(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = try {
        Json.decodeFromString<List<String>>(toolIds)
    } catch (e: Exception) {
        emptyList()
    },
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)

fun Agent.toEntity(): AgentEntity = AgentEntity(
    id = id,
    name = name,
    description = description,
    systemPrompt = systemPrompt,
    toolIds = Json.encodeToString(toolIds),
    preferredProviderId = preferredProviderId,
    preferredModelId = preferredModelId,
    isBuiltIn = isBuiltIn,
    createdAt = createdAt,
    updatedAt = updatedAt
)
```

## DAO Interface

### AgentDao

```kotlin
@Dao
interface AgentDao {

    /**
     * Get all agents, ordered: built-in first, then custom by updated_at descending.
     */
    @Query("SELECT * FROM agents ORDER BY is_built_in DESC, updated_at DESC")
    fun getAllAgents(): Flow<List<AgentEntity>>

    @Query("SELECT * FROM agents WHERE id = :id")
    suspend fun getAgentById(id: String): AgentEntity?

    @Query("SELECT * FROM agents WHERE is_built_in = 1")
    suspend fun getBuiltInAgents(): List<AgentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAgent(agent: AgentEntity)

    @Update
    suspend fun updateAgent(agent: AgentEntity)

    /**
     * Delete a custom (non-built-in) agent. Returns the number of rows affected.
     * Built-in agents are protected by the WHERE clause.
     */
    @Query("DELETE FROM agents WHERE id = :id AND is_built_in = 0")
    suspend fun deleteCustomAgent(id: String): Int

    /**
     * Count agents. Used to verify seeding.
     */
    @Query("SELECT COUNT(*) FROM agents")
    suspend fun getAgentCount(): Int
}
```

## Built-in Agent Seeding

### Strategy: Room Database Callback

The "General Assistant" agent is inserted alongside the pre-configured providers in the same `AppDatabaseCallback.onCreate` that was defined in RFC-003. This ensures all seed data is created atomically on first database creation.

### Implementation

```kotlin
// In AppDatabaseCallback (extends the existing callback from RFC-003)
class AppDatabaseCallback : RoomDatabase.Callback() {

    override fun onCreate(db: SupportSQLiteDatabase) {
        super.onCreate(db)

        val now = System.currentTimeMillis()

        // --- Provider seeding (from RFC-003) ---
        // ... existing provider INSERT statements ...

        // --- Model seeding (from RFC-003) ---
        // ... existing model INSERT statements ...

        // --- Agent seeding (RFC-002) ---
        val allToolIds = """["get_current_time","read_file","write_file","http_request"]"""
        val systemPrompt = """You are a helpful AI assistant. You can help with a wide range of tasks including answering questions, writing, analysis, and more. You have access to tools that allow you to read/write files, make HTTP requests, and check the current time. Use tools when they would help accomplish the user's request."""

        db.execSQL(
            """INSERT INTO agents (id, name, description, system_prompt, tool_ids, preferred_provider_id, preferred_model_id, is_built_in, created_at, updated_at)
               VALUES ('agent-general-assistant', 'General Assistant', 'A general-purpose helpful AI assistant with access to all built-in tools', '$systemPrompt', '$allToolIds', NULL, NULL, 1, $now, $now)"""
        )
    }
}
```

### Design Decisions

- **Fixed ID**: `agent-general-assistant` -- deterministic, referenced by deletion fallback logic, consistent with provider IDs (`provider-openai`, etc.)
- **All tools enabled**: The General Assistant has access to all 4 built-in tools. Users who want a restricted tool set should clone and customize.
- **No preferred model**: The General Assistant uses the global default model, making it model-agnostic out of the box.
- **System prompt**: A concise, general-purpose prompt that mentions available tool capabilities. This guides the model to use tools when appropriate.
- **Immutable**: Built-in agents cannot be edited or deleted. The DAO's `deleteCustomAgent` query has `is_built_in = 0` in the WHERE clause.

### Constants

```kotlin
/**
 * Well-known IDs for built-in agents.
 * Located in: core/model/AgentConstants.kt
 */
object AgentConstants {
    const val GENERAL_ASSISTANT_ID = "agent-general-assistant"
}
```

This constant is used by:
- `DeleteAgentUseCase`: When a deleted agent was in use by sessions, those sessions fall back to `GENERAL_ASSISTANT_ID`
- `SessionRepository`: New sessions default to `GENERAL_ASSISTANT_ID`
- Future agent seeding: To check if the built-in agent already exists

## Repository Implementation

### AgentRepository Interface

Defined in Core module. Updated from RFC-000 with minor refinements.

```kotlin
interface AgentRepository {
    /**
     * Observe all agents. Built-in first, then custom sorted by updated_at descending.
     */
    fun getAllAgents(): Flow<List<Agent>>

    /**
     * Get a single agent by ID. Returns null if not found.
     */
    suspend fun getAgentById(id: String): Agent?

    /**
     * Create a new custom agent. The agent.isBuiltIn must be false.
     * Returns the created agent with timestamps set.
     */
    suspend fun createAgent(agent: Agent): Agent

    /**
     * Update an existing custom agent. Built-in agents cannot be updated.
     * Returns AppResult.Error if the agent is built-in.
     */
    suspend fun updateAgent(agent: Agent): AppResult<Unit>

    /**
     * Delete a custom agent by ID. Built-in agents cannot be deleted.
     * Returns AppResult.Error if the agent is built-in or not found.
     */
    suspend fun deleteAgent(id: String): AppResult<Unit>

    /**
     * Get all built-in agents.
     */
    suspend fun getBuiltInAgents(): List<Agent>
}
```

### AgentRepositoryImpl

```kotlin
class AgentRepositoryImpl(
    private val agentDao: AgentDao
) : AgentRepository {

    override fun getAllAgents(): Flow<List<Agent>> {
        return agentDao.getAllAgents().map { entities ->
            entities.map { it.toDomain() }
        }
    }

    override suspend fun getAgentById(id: String): Agent? {
        return agentDao.getAgentById(id)?.toDomain()
    }

    override suspend fun createAgent(agent: Agent): Agent {
        val now = System.currentTimeMillis()
        val newAgent = agent.copy(
            id = if (agent.id.isBlank()) java.util.UUID.randomUUID().toString() else agent.id,
            isBuiltIn = false,  // Enforce: only custom agents can be created via this method
            createdAt = now,
            updatedAt = now
        )
        agentDao.insertAgent(newAgent.toEntity())
        return newAgent
    }

    override suspend fun updateAgent(agent: Agent): AppResult<Unit> {
        // Verify the agent exists and is not built-in
        val existing = agentDao.getAgentById(agent.id)
            ?: return AppResult.Error(
                message = "Agent not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (existing.isBuiltIn) {
            return AppResult.Error(
                message = "Built-in agents cannot be edited.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val updated = agent.copy(updatedAt = System.currentTimeMillis())
        agentDao.updateAgent(updated.toEntity())
        return AppResult.Success(Unit)
    }

    override suspend fun deleteAgent(id: String): AppResult<Unit> {
        val deleted = agentDao.deleteCustomAgent(id)
        return if (deleted > 0) {
            AppResult.Success(Unit)
        } else {
            // Either not found or built-in
            val exists = agentDao.getAgentById(id)
            if (exists != null && exists.isBuiltIn) {
                AppResult.Error(
                    message = "Built-in agents cannot be deleted.",
                    code = ErrorCode.VALIDATION_ERROR
                )
            } else {
                AppResult.Error(
                    message = "Agent not found.",
                    code = ErrorCode.VALIDATION_ERROR
                )
            }
        }
    }

    override suspend fun getBuiltInAgents(): List<Agent> {
        return agentDao.getBuiltInAgents().map { it.toDomain() }
    }
}
```

## Use Cases

### CreateAgentUseCase

```kotlin
/**
 * Validates and creates a new custom agent.
 * Located in: feature/agent/usecase/CreateAgentUseCase.kt
 */
class CreateAgentUseCase(
    private val agentRepository: AgentRepository
) {
    suspend operator fun invoke(
        name: String,
        description: String?,
        systemPrompt: String,
        toolIds: List<String>,
        preferredProviderId: String?,
        preferredModelId: String?
    ): AppResult<Agent> {
        // Validation
        if (name.isBlank()) {
            return AppResult.Error(
                message = "Agent name cannot be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        if (systemPrompt.isBlank()) {
            return AppResult.Error(
                message = "System prompt cannot be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }
        // If one of provider/model is set, both must be set
        if ((preferredProviderId != null) != (preferredModelId != null)) {
            return AppResult.Error(
                message = "Preferred provider and model must both be set or both be empty.",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val agent = Agent(
            id = "",  // Will be generated by repository
            name = name.trim(),
            description = description?.trim()?.ifBlank { null },
            systemPrompt = systemPrompt.trim(),
            toolIds = toolIds,
            preferredProviderId = preferredProviderId,
            preferredModelId = preferredModelId,
            isBuiltIn = false,
            createdAt = 0,  // Will be set by repository
            updatedAt = 0   // Will be set by repository
        )

        val created = agentRepository.createAgent(agent)
        return AppResult.Success(created)
    }
}
```

### CloneAgentUseCase

```kotlin
/**
 * Creates an editable copy of any agent (built-in or custom).
 * Located in: feature/agent/usecase/CloneAgentUseCase.kt
 */
class CloneAgentUseCase(
    private val agentRepository: AgentRepository
) {
    suspend operator fun invoke(agentId: String): AppResult<Agent> {
        val original = agentRepository.getAgentById(agentId)
            ?: return AppResult.Error(
                message = "Agent not found.",
                code = ErrorCode.VALIDATION_ERROR
            )

        val clone = Agent(
            id = "",  // Will be generated by repository
            name = "${original.name} (Copy)",
            description = original.description,
            systemPrompt = original.systemPrompt,
            toolIds = original.toolIds,
            preferredProviderId = original.preferredProviderId,
            preferredModelId = original.preferredModelId,
            isBuiltIn = false,  // Clone is always custom
            createdAt = 0,
            updatedAt = 0
        )

        val created = agentRepository.createAgent(clone)
        return AppResult.Success(created)
    }
}
```

### DeleteAgentUseCase

```kotlin
/**
 * Deletes a custom agent and handles session fallback.
 * Located in: feature/agent/usecase/DeleteAgentUseCase.kt
 */
class DeleteAgentUseCase(
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository
) {
    suspend operator fun invoke(agentId: String): AppResult<Unit> {
        // First, update any sessions using this agent to fall back to General Assistant
        sessionRepository.updateAgentForSessions(
            oldAgentId = agentId,
            newAgentId = AgentConstants.GENERAL_ASSISTANT_ID
        )

        // Then delete the agent
        return agentRepository.deleteAgent(agentId)
    }
}
```

**Note:** `SessionRepository.updateAgentForSessions()` is a new method that needs to be added. It will be fully specified in RFC-005 (Session Management), but the signature is defined here for RFC-002's use:

```kotlin
// Addition to SessionRepository interface (core/repository/SessionRepository.kt)
interface SessionRepository {
    // ... existing methods ...

    /**
     * Update all sessions that reference oldAgentId to use newAgentId instead.
     * Used when an agent is deleted to fall back to General Assistant.
     */
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)
}
```

### GetAgentToolsUseCase

```kotlin
/**
 * Resolves an agent's tool IDs to full ToolDefinition objects.
 * Used by the chat system to get tool definitions for API calls.
 * Located in: feature/agent/usecase/GetAgentToolsUseCase.kt
 */
class GetAgentToolsUseCase(
    private val agentRepository: AgentRepository,
    private val toolRegistry: ToolRegistry
) {
    suspend operator fun invoke(agentId: String): List<ToolDefinition> {
        val agent = agentRepository.getAgentById(agentId) ?: return emptyList()
        return toolRegistry.getToolDefinitionsByNames(agent.toolIds)
    }
}
```

### ResolveModelUseCase

```kotlin
/**
 * Resolves which model and provider to use for a given agent.
 * Resolution order:
 *   1. Agent's preferred model/provider (if set)
 *   2. Global default model/provider
 *
 * Located in: feature/agent/usecase/ResolveModelUseCase.kt
 */
class ResolveModelUseCase(
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository
) {
    /**
     * @return Pair of (AiModel, Provider) or null if no model is configured anywhere
     */
    suspend operator fun invoke(agentId: String): AppResult<ResolvedModel> {
        val agent = agentRepository.getAgentById(agentId)
            ?: return AppResult.Error(
                message = "Agent not found.",
                code = ErrorCode.VALIDATION_ERROR
            )

        // 1. Try agent's preferred model/provider
        if (agent.preferredProviderId != null && agent.preferredModelId != null) {
            val provider = providerRepository.getProviderById(agent.preferredProviderId)
            if (provider != null && provider.isActive) {
                val models = providerRepository.getModelsForProvider(provider.id)
                val model = models.find { it.id == agent.preferredModelId }
                if (model != null) {
                    return AppResult.Success(
                        ResolvedModel(model = model, provider = provider)
                    )
                }
            }
            // Agent's preferred model/provider is invalid (deleted/inactive) -- fall through to global default
        }

        // 2. Try global default
        val defaultModel = providerRepository.getGlobalDefaultModel().first()
        if (defaultModel != null) {
            val provider = providerRepository.getProviderById(defaultModel.providerId)
            if (provider != null && provider.isActive) {
                return AppResult.Success(
                    ResolvedModel(model = defaultModel, provider = provider)
                )
            }
        }

        // No model configured
        return AppResult.Error(
            message = "No model configured. Please set up a provider in Settings.",
            code = ErrorCode.VALIDATION_ERROR
        )
    }
}

/**
 * Result of model resolution.
 * Located in: core/model/ResolvedModel.kt
 */
data class ResolvedModel(
    val model: AiModel,
    val provider: Provider
)
```

## UI Layer

### Navigation Routes

```kotlin
// In Routes.kt (additions for agent feature)
object AgentList : Route("agents")
object AgentDetail : Route("agents/{agentId}") {
    fun create(agentId: String) = "agents/$agentId"
}
object AgentCreate : Route("agents/create")
```

### UI State Definitions

#### AgentListUiState

```kotlin
data class AgentListUiState(
    val agents: List<AgentListItem> = emptyList(),
    val isLoading: Boolean = true
)

data class AgentListItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val toolCount: Int
)
```

#### AgentDetailUiState

```kotlin
data class AgentDetailUiState(
    // Agent data
    val agentId: String? = null,
    val isBuiltIn: Boolean = false,
    val isNewAgent: Boolean = false,     // true when creating new agent

    // Form fields
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val selectedToolIds: List<String> = emptyList(),
    val preferredProviderId: String? = null,
    val preferredModelId: String? = null,

    // Available tools (from ToolRegistry)
    val availableTools: List<ToolOptionItem> = emptyList(),

    // Available models (from all active providers)
    val availableModels: List<ModelOptionItem> = emptyList(),

    // UI state
    val hasUnsavedChanges: Boolean = false,
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val successMessage: String? = null,
    val showDeleteDialog: Boolean = false,
    val navigateBack: Boolean = false    // Signal to pop navigation
)

/**
 * A tool option shown in the tool selection list.
 */
data class ToolOptionItem(
    val name: String,              // Tool name (e.g., "read_file")
    val description: String,       // Tool description
    val isSelected: Boolean        // Whether this tool is enabled for the agent
)

/**
 * A model option shown in the preferred model picker.
 */
data class ModelOptionItem(
    val modelId: String,
    val modelDisplayName: String?,
    val providerId: String,
    val providerName: String
)
```

#### AgentSelectorUiState

```kotlin
/**
 * State for the agent selector bottom sheet in chat screen.
 */
data class AgentSelectorUiState(
    val agents: List<AgentSelectorItem> = emptyList(),
    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val isLoading: Boolean = true
)

data class AgentSelectorItem(
    val id: String,
    val name: String,
    val description: String?,
    val isBuiltIn: Boolean,
    val isSelected: Boolean        // true if this is the current agent
)
```

### ViewModels

#### AgentListViewModel

```kotlin
class AgentListViewModel(
    private val agentRepository: AgentRepository,
    private val toolRegistry: ToolRegistry
) : ViewModel() {

    private val _uiState = MutableStateFlow(AgentListUiState())
    val uiState: StateFlow<AgentListUiState> = _uiState.asStateFlow()

    init {
        loadAgents()
    }

    private fun loadAgents() {
        viewModelScope.launch {
            agentRepository.getAllAgents().collect { agents ->
                val items = agents.map { agent ->
                    AgentListItem(
                        id = agent.id,
                        name = agent.name,
                        description = agent.description,
                        isBuiltIn = agent.isBuiltIn,
                        toolCount = agent.toolIds.size
                    )
                }
                _uiState.update { it.copy(agents = items, isLoading = false) }
            }
        }
    }
}
```

#### AgentDetailViewModel

```kotlin
class AgentDetailViewModel(
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val toolRegistry: ToolRegistry,
    private val createAgentUseCase: CreateAgentUseCase,
    private val cloneAgentUseCase: CloneAgentUseCase,
    private val deleteAgentUseCase: DeleteAgentUseCase,
    savedStateHandle: SavedStateHandle
) : ViewModel() {

    // agentId is null for create mode, non-null for view/edit mode
    private val agentId: String? = savedStateHandle["agentId"]
    private val isCreateMode = agentId == null

    private val _uiState = MutableStateFlow(AgentDetailUiState(isNewAgent = isCreateMode))
    val uiState: StateFlow<AgentDetailUiState> = _uiState.asStateFlow()

    // Snapshot of original agent data for change detection
    private var originalAgent: Agent? = null

    init {
        loadAvailableTools()
        loadAvailableModels()
        if (!isCreateMode) {
            loadAgent(agentId!!)
        } else {
            _uiState.update { it.copy(isLoading = false) }
        }
    }

    private fun loadAgent(id: String) {
        viewModelScope.launch {
            val agent = agentRepository.getAgentById(id)
            if (agent == null) {
                _uiState.update {
                    it.copy(isLoading = false, errorMessage = "Agent not found.")
                }
                return@launch
            }

            originalAgent = agent
            _uiState.update {
                it.copy(
                    agentId = agent.id,
                    isBuiltIn = agent.isBuiltIn,
                    name = agent.name,
                    description = agent.description ?: "",
                    systemPrompt = agent.systemPrompt,
                    selectedToolIds = agent.toolIds,
                    preferredProviderId = agent.preferredProviderId,
                    preferredModelId = agent.preferredModelId,
                    isLoading = false
                )
            }
        }
    }

    private fun loadAvailableTools() {
        val tools = toolRegistry.getAllToolDefinitions().map { toolDef ->
            ToolOptionItem(
                name = toolDef.name,
                description = toolDef.description,
                isSelected = false  // Updated when agent is loaded
            )
        }
        _uiState.update { it.copy(availableTools = tools) }
    }

    private fun loadAvailableModels() {
        viewModelScope.launch {
            providerRepository.getActiveProviders().collect { providers ->
                val modelOptions = mutableListOf<ModelOptionItem>()
                for (provider in providers) {
                    val models = providerRepository.getModelsForProvider(provider.id)
                    for (model in models) {
                        modelOptions.add(
                            ModelOptionItem(
                                modelId = model.id,
                                modelDisplayName = model.displayName,
                                providerId = provider.id,
                                providerName = provider.name
                            )
                        )
                    }
                }
                _uiState.update { it.copy(availableModels = modelOptions) }
            }
        }
    }

    // --- Form field updates ---

    fun updateName(name: String) {
        _uiState.update { it.copy(name = name, hasUnsavedChanges = true) }
    }

    fun updateDescription(description: String) {
        _uiState.update { it.copy(description = description, hasUnsavedChanges = true) }
    }

    fun updateSystemPrompt(prompt: String) {
        _uiState.update { it.copy(systemPrompt = prompt, hasUnsavedChanges = true) }
    }

    fun toggleTool(toolName: String) {
        _uiState.update { state ->
            val newToolIds = if (toolName in state.selectedToolIds) {
                state.selectedToolIds - toolName
            } else {
                state.selectedToolIds + toolName
            }
            state.copy(selectedToolIds = newToolIds, hasUnsavedChanges = true)
        }
    }

    fun setPreferredModel(providerId: String?, modelId: String?) {
        _uiState.update {
            it.copy(
                preferredProviderId = providerId,
                preferredModelId = modelId,
                hasUnsavedChanges = true
            )
        }
    }

    fun clearPreferredModel() {
        setPreferredModel(null, null)
    }

    // --- Actions ---

    fun saveAgent() {
        val state = _uiState.value
        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            if (isCreateMode) {
                val result = createAgentUseCase(
                    name = state.name,
                    description = state.description.ifBlank { null },
                    systemPrompt = state.systemPrompt,
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId
                )
                when (result) {
                    is AppResult.Success -> {
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                hasUnsavedChanges = false,
                                successMessage = "Agent created.",
                                navigateBack = true
                            )
                        }
                    }
                    is AppResult.Error -> {
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = result.message)
                        }
                    }
                }
            } else {
                // Update existing agent
                val updated = Agent(
                    id = state.agentId!!,
                    name = state.name.trim(),
                    description = state.description.trim().ifBlank { null },
                    systemPrompt = state.systemPrompt.trim(),
                    toolIds = state.selectedToolIds,
                    preferredProviderId = state.preferredProviderId,
                    preferredModelId = state.preferredModelId,
                    isBuiltIn = false,
                    createdAt = originalAgent?.createdAt ?: 0,
                    updatedAt = 0  // Will be set by repository
                )
                when (val result = agentRepository.updateAgent(updated)) {
                    is AppResult.Success -> {
                        originalAgent = updated
                        _uiState.update {
                            it.copy(
                                isSaving = false,
                                hasUnsavedChanges = false,
                                successMessage = "Agent saved."
                            )
                        }
                    }
                    is AppResult.Error -> {
                        _uiState.update {
                            it.copy(isSaving = false, errorMessage = result.message)
                        }
                    }
                }
            }
        }
    }

    fun cloneAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = cloneAgentUseCase(id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            successMessage = "Agent cloned.",
                            navigateBack = true
                            // The cloned agent will appear in the list
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update { it.copy(errorMessage = result.message) }
                }
            }
        }
    }

    fun showDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = true) }
    }

    fun dismissDeleteConfirmation() {
        _uiState.update { it.copy(showDeleteDialog = false) }
    }

    fun deleteAgent() {
        val id = _uiState.value.agentId ?: return
        viewModelScope.launch {
            when (val result = deleteAgentUseCase(id)) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            showDeleteDialog = false,
                            successMessage = "Agent deleted.",
                            navigateBack = true
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(showDeleteDialog = false, errorMessage = result.message)
                    }
                }
            }
        }
    }

    fun clearError() {
        _uiState.update { it.copy(errorMessage = null) }
    }

    fun clearSuccess() {
        _uiState.update { it.copy(successMessage = null) }
    }
}
```

### Screen Composable Outlines

The following are structural outlines. Full Compose code will be generated during implementation, referencing the [UI Design Spec](../../design/ui-design-spec.md) for exact spacing, colors, and styling.

#### AgentListScreen

```kotlin
@Composable
fun AgentListScreen(
    viewModel: AgentListViewModel = koinViewModel(),
    onAgentClick: (String) -> Unit,       // Navigate to agent detail
    onCreateAgent: () -> Unit,            // Navigate to create agent
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Agents") },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    IconButton(onClick = onCreateAgent) {
                        Icon(Icons.Default.Add, "Create Agent")
                    }
                }
            )
        }
    ) { padding ->
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else {
            LazyColumn(contentPadding = padding) {
                // Built-in agents section
                val builtIn = uiState.agents.filter { it.isBuiltIn }
                if (builtIn.isNotEmpty()) {
                    stickyHeader { SectionHeader("BUILT-IN") }
                    items(builtIn, key = { it.id }) { agent ->
                        AgentListItem(
                            agent = agent,
                            onClick = { onAgentClick(agent.id) }
                        )
                    }
                }

                // Custom agents section
                val custom = uiState.agents.filter { !it.isBuiltIn }
                if (custom.isNotEmpty()) {
                    stickyHeader { SectionHeader("CUSTOM") }
                    items(custom, key = { it.id }) { agent ->
                        SwipeToActionItem(
                            onDelete = { /* show confirmation */ },
                            onClone = { /* clone */ }
                        ) {
                            AgentListItem(
                                agent = agent,
                                onClick = { onAgentClick(agent.id) }
                            )
                        }
                    }
                }

                // Empty state for custom agents
                if (custom.isEmpty()) {
                    item {
                        EmptyStateMessage("No custom agents yet. Tap + to create one.")
                    }
                }
            }
        }
    }
}
```

#### AgentDetailScreen

```kotlin
@Composable
fun AgentDetailScreen(
    viewModel: AgentDetailViewModel = koinViewModel(),
    onNavigateBack: () -> Unit,
    onCloneNavigate: (String) -> Unit      // Navigate to the cloned agent's detail
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle navigation signals
    LaunchedEffect(uiState.navigateBack) {
        if (uiState.navigateBack) onNavigateBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when {
                            uiState.isNewAgent -> "Create Agent"
                            uiState.isBuiltIn -> uiState.name
                            else -> "Edit Agent"
                        }
                    )
                },
                navigationIcon = { BackButton(onNavigateBack) },
                actions = {
                    // Save button: visible for new + custom agents, disabled until changes
                    if (!uiState.isBuiltIn) {
                        TextButton(
                            onClick = { viewModel.saveAgent() },
                            enabled = uiState.hasUnsavedChanges && !uiState.isSaving
                        ) {
                            Text("Save")
                        }
                    }
                }
            )
        },
        snackbarHost = { /* For success/error messages */ }
    ) { padding ->
        if (uiState.isLoading) {
            CenteredLoadingIndicator()
        } else {
            LazyColumn(
                contentPadding = padding,
                modifier = Modifier.fillMaxSize()
            ) {
                // Name field
                item {
                    OutlinedTextField(
                        value = uiState.name,
                        onValueChange = { viewModel.updateName(it) },
                        label = { Text("Name") },
                        readOnly = uiState.isBuiltIn,
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Description field
                item {
                    OutlinedTextField(
                        value = uiState.description,
                        onValueChange = { viewModel.updateDescription(it) },
                        label = { Text("Description (optional)") },
                        readOnly = uiState.isBuiltIn,
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // System Prompt field (large text area)
                item {
                    OutlinedTextField(
                        value = uiState.systemPrompt,
                        onValueChange = { viewModel.updateSystemPrompt(it) },
                        label = { Text("System Prompt") },
                        readOnly = uiState.isBuiltIn,
                        minLines = 5,
                        maxLines = 15,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // Tool Selection section
                item { SectionHeader("TOOLS") }
                items(uiState.availableTools, key = { it.name }) { tool ->
                    ToolCheckboxItem(
                        toolName = tool.name,
                        toolDescription = tool.description,
                        isChecked = tool.name in uiState.selectedToolIds,
                        onCheckedChange = { viewModel.toggleTool(tool.name) },
                        enabled = !uiState.isBuiltIn
                    )
                }

                // Preferred Model section
                item { SectionHeader("PREFERRED MODEL (optional)") }
                item {
                    PreferredModelSelector(
                        currentProviderId = uiState.preferredProviderId,
                        currentModelId = uiState.preferredModelId,
                        availableModels = uiState.availableModels,
                        onSelect = { providerId, modelId ->
                            viewModel.setPreferredModel(providerId, modelId)
                        },
                        onClear = { viewModel.clearPreferredModel() },
                        enabled = !uiState.isBuiltIn
                    )
                }

                // Action buttons
                item {
                    ActionButtonsSection(
                        isBuiltIn = uiState.isBuiltIn,
                        isNewAgent = uiState.isNewAgent,
                        onClone = { viewModel.cloneAgent() },
                        onDelete = { viewModel.showDeleteConfirmation() }
                    )
                }
            }
        }

        // Delete confirmation dialog
        if (uiState.showDeleteDialog) {
            ConfirmationDialog(
                title = "Delete Agent",
                message = "This agent will be permanently removed. Any sessions using this agent will switch to General Assistant.",
                confirmText = "Delete",
                onConfirm = { viewModel.deleteAgent() },
                onDismiss = { viewModel.dismissDeleteConfirmation() }
            )
        }
    }
}
```

#### AgentSelectorSheet

The agent selector is a bottom sheet shown when the user taps the agent name in the chat screen's top app bar. This composable is defined here in RFC-002 but integrated into the chat flow by RFC-001.

```kotlin
/**
 * Bottom sheet for selecting an agent in the chat screen.
 * Located in: feature/agent/AgentSelectorSheet.kt
 *
 * Used by ChatScreen (RFC-001) via:
 *   ChatViewModel.showAgentSelector() -> shows this sheet
 *   ChatViewModel.switchAgent(agentId) -> updates session's current agent
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentSelectorSheet(
    agents: List<AgentSelectorItem>,
    currentAgentId: String,
    onSelectAgent: (String) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(bottom = 24.dp)) {
            // Title
            Text(
                text = "Select an Agent",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 16.dp)
            )

            // Agent list
            LazyColumn {
                items(agents, key = { it.id }) { agent ->
                    ListItem(
                        headlineContent = { Text(agent.name) },
                        supportingContent = agent.description?.let { { Text(it, maxLines = 1) } },
                        leadingContent = if (agent.isBuiltIn) {
                            { Badge { Text("Built-in") } }
                        } else null,
                        trailingContent = if (agent.id == currentAgentId) {
                            { Icon(Icons.Default.Check, "Current") }
                        } else null,
                        modifier = Modifier.clickable {
                            if (agent.id != currentAgentId) {
                                onSelectAgent(agent.id)
                            }
                            onDismiss()
                        }
                    )
                }
            }
        }
    }
}
```

## Koin Dependency Injection

```kotlin
// Additions to existing Koin modules

// DatabaseModule.kt -- AgentDao registration
val databaseModule = module {
    // ... existing database setup with AppDatabaseCallback ...
    single { get<AppDatabase>().agentDao() }
    // ... other DAOs ...
}

// RepositoryModule.kt
val repositoryModule = module {
    single<AgentRepository> { AgentRepositoryImpl(get()) }  // AgentDao
    // ... other repositories ...
}

// FeatureModule.kt -- Agent feature
val featureModule = module {
    // Agent Use Cases
    factory { CreateAgentUseCase(get()) }                    // AgentRepository
    factory { CloneAgentUseCase(get()) }                     // AgentRepository
    factory { DeleteAgentUseCase(get(), get()) }             // AgentRepository, SessionRepository
    factory { GetAgentToolsUseCase(get(), get()) }           // AgentRepository, ToolRegistry
    factory { ResolveModelUseCase(get(), get()) }            // AgentRepository, ProviderRepository

    // Agent ViewModels
    viewModel { AgentListViewModel(get(), get()) }           // AgentRepository, ToolRegistry
    viewModel { AgentDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    // AgentRepository, ProviderRepository, ToolRegistry, CreateAgentUseCase,
    // CloneAgentUseCase, DeleteAgentUseCase, SavedStateHandle
}
```

## Data Flow Examples

### Flow 1: User Creates a Custom Agent

```
1. User navigates: Settings > Manage Agents > [+] Create
2. AgentDetailScreen opens in create mode (agentId = null)
   -> AgentDetailViewModel: isCreateMode = true
   -> Loads available tools from ToolRegistry
   -> Loads available models from ProviderRepository

3. User fills in:
   - Name: "Writing Helper"
   - Description: "Helps with writing and editing"
   - System Prompt: "You are a writing assistant..."
   - Tools: [x] get_current_time, [ ] read_file, [ ] write_file, [x] http_request
   - Preferred Model: Not set (uses global default)

4. User taps "Save"
   -> AgentDetailViewModel.saveAgent()
   -> CreateAgentUseCase validates fields
   -> AgentRepository.createAgent(agent)
      -> Generates UUID, sets timestamps
      -> AgentDao.insertAgent(entity)
   -> UI shows "Agent created." toast
   -> Navigates back to agent list

5. Agent list auto-updates (Flow from AgentDao.getAllAgents())
   -> "Writing Helper" appears in CUSTOM section
```

### Flow 2: User Clones a Built-in Agent

```
1. User navigates: Settings > Manage Agents > General Assistant
2. AgentDetailScreen opens in view mode
   -> All fields read-only (isBuiltIn = true)
   -> No Save button, no Delete button
   -> Clone button visible

3. User taps "Clone"
   -> AgentDetailViewModel.cloneAgent()
   -> CloneAgentUseCase("agent-general-assistant")
      -> Reads original agent
      -> Creates new Agent with name "General Assistant (Copy)", isBuiltIn = false
      -> AgentRepository.createAgent(clone)
   -> UI shows "Agent cloned." toast
   -> Navigates back to agent list

4. Agent list shows the clone in CUSTOM section
5. User taps the clone to edit it
   -> All fields now editable
   -> User modifies system prompt and tool set
   -> User taps "Save"
```

### Flow 3: User Deletes an Agent In Use by a Session

```
1. User has an active session using "Data Analyst" agent
2. User navigates: Settings > Manage Agents > Data Analyst > Delete
3. Confirmation dialog: "This agent will be permanently removed.
   Any sessions using this agent will switch to General Assistant."

4. User taps "Delete"
   -> DeleteAgentUseCase("data-analyst-uuid")
   -> SessionRepository.updateAgentForSessions(
        oldAgentId = "data-analyst-uuid",
        newAgentId = "agent-general-assistant"
      )
      -> Updates sessions table: SET current_agent_id = 'agent-general-assistant'
         WHERE current_agent_id = 'data-analyst-uuid'
   -> AgentRepository.deleteAgent("data-analyst-uuid")
      -> AgentDao.deleteCustomAgent()

5. User returns to the active session
   -> Session now shows "General Assistant" as current agent
   -> Conversation history is unchanged
   -> Next message uses General Assistant's system prompt and tool set
```

### Flow 4: Model Resolution for Sending a Message

```
1. User sends a message in a session using "Code Helper" agent
2. SendMessageUseCase (RFC-001) needs to know which model/provider to use
3. Calls ResolveModelUseCase("code-helper-uuid")

4. ResolveModelUseCase checks:
   a. Agent has preferredProviderId = "provider-openai", preferredModelId = "gpt-4o"
   b. Provider "provider-openai" exists and is active? -> Yes
   c. Model "gpt-4o" exists for this provider? -> Yes
   d. Returns ResolvedModel(model=gpt-4o, provider=OpenAI)

5. If the agent had no preferred model:
   a. Falls back to global default model
   b. If global default is "claude-sonnet-4-20250514" on "provider-anthropic"
   c. Returns ResolvedModel(model=Claude Sonnet 4, provider=Anthropic)

6. If neither is set:
   a. Returns AppResult.Error("No model configured. Please set up a provider in Settings.")
   b. Chat shows inline error
```

## Error Handling

### Error Scenarios and User-Facing Messages

| Scenario | ErrorCode | User Message | UI Behavior |
|----------|-----------|--------------|-------------|
| Name is empty on save | VALIDATION_ERROR | "Agent name cannot be empty." | Form field validation error |
| System prompt is empty on save | VALIDATION_ERROR | "System prompt cannot be empty." | Form field validation error |
| Provider set without model (or vice versa) | VALIDATION_ERROR | "Preferred provider and model must both be set or both be empty." | Form validation error |
| Edit built-in agent | VALIDATION_ERROR | "Built-in agents cannot be edited." | Save button not visible for built-in |
| Delete built-in agent | VALIDATION_ERROR | "Built-in agents cannot be deleted." | Delete button not visible for built-in |
| Agent not found | VALIDATION_ERROR | "Agent not found." | Navigate back with error |
| No model configured (send message) | VALIDATION_ERROR | "No model configured. Please set up a provider in Settings." | Inline error in chat |
| Save fails (DB error) | STORAGE_ERROR | "Failed to save agent. Please try again." | Error toast/snackbar |
| Delete fails (DB error) | STORAGE_ERROR | "Failed to delete agent. Please try again." | Error toast/snackbar |
| Agent's preferred model was deleted | -- (silent fallback) | -- | ResolveModelUseCase falls through to global default |

### Validation Rules

```kotlin
/**
 * Agent validation logic, used before save.
 * Located in: feature/agent/AgentValidator.kt
 */
object AgentValidator {

    fun validateName(name: String): String? {
        return when {
            name.isBlank() -> "Agent name cannot be empty."
            name.length > 100 -> "Agent name is too long (max 100 characters)."
            else -> null
        }
    }

    fun validateSystemPrompt(prompt: String): String? {
        return when {
            prompt.isBlank() -> "System prompt cannot be empty."
            prompt.length > 50_000 -> "System prompt is too long (max 50,000 characters)."
            else -> null
        }
    }

    fun validatePreferredModel(providerId: String?, modelId: String?): String? {
        return if ((providerId != null) != (modelId != null)) {
            "Preferred provider and model must both be set or both be empty."
        } else null
    }
}
```

## Implementation Steps

### Phase 1: Data Layer
1. [ ] Create `AgentEntity` in `data/local/entity/AgentEntity.kt`
2. [ ] Create `AgentDao` in `data/local/dao/AgentDao.kt`
3. [ ] Create entity-domain mapper in `data/local/mapper/AgentMapper.kt`
4. [ ] Create `AgentConstants` in `core/model/AgentConstants.kt` (fixed ID constant)
5. [ ] Create `ResolvedModel` data class in `core/model/ResolvedModel.kt`
6. [ ] Add agent seeding SQL to `AppDatabaseCallback.onCreate()` (extending RFC-003's callback)
7. [ ] Register `AgentEntity` and `AgentDao` in `AppDatabase`

### Phase 2: Repository
8. [ ] Update `AgentRepository` interface in `core/repository/AgentRepository.kt`
9. [ ] Implement `AgentRepositoryImpl` in `data/repository/AgentRepositoryImpl.kt`
10. [ ] Add `updateAgentForSessions()` to `SessionRepository` interface (signature only; implementation in RFC-005)

### Phase 3: Use Cases
11. [ ] Implement `CreateAgentUseCase` in `feature/agent/usecase/`
12. [ ] Implement `CloneAgentUseCase`
13. [ ] Implement `DeleteAgentUseCase`
14. [ ] Implement `GetAgentToolsUseCase`
15. [ ] Implement `ResolveModelUseCase`
16. [ ] Create `AgentValidator` in `feature/agent/AgentValidator.kt`

### Phase 4: UI Layer
17. [ ] Create `AgentListUiState`, `AgentDetailUiState`, `AgentSelectorUiState` in `feature/agent/AgentUiState.kt`
18. [ ] Create `ToolOptionItem`, `ModelOptionItem`, `AgentSelectorItem` data classes
19. [ ] Implement `AgentListViewModel`
20. [ ] Implement `AgentDetailViewModel`
21. [ ] Implement `AgentListScreen` (Compose)
22. [ ] Implement `AgentDetailScreen` (Compose) -- handles both create and edit modes
23. [ ] Implement `AgentSelectorSheet` (Compose) -- bottom sheet for chat screen
24. [ ] Register navigation routes for agent screens in NavGraph

### Phase 5: DI & Integration
25. [ ] Update Koin modules (DatabaseModule, RepositoryModule, FeatureModule)
26. [ ] End-to-end testing: create agent -> edit -> clone -> delete -> fallback
27. [ ] Test agent selector bottom sheet with agent list rendering
28. [ ] Test model resolution (agent preferred -> global default -> error)

## Testing Strategy

### Unit Tests
- `AgentRepositoryImpl`: CRUD operations, built-in protection (cannot edit/delete), create generates UUID
- `CreateAgentUseCase`: Validation (empty name, empty prompt, provider/model pair), successful creation
- `CloneAgentUseCase`: Clone copies all fields, clone name has "(Copy)" suffix, clone is not built-in
- `DeleteAgentUseCase`: Session fallback is called before deletion, built-in rejection
- `GetAgentToolsUseCase`: Resolves tool IDs to definitions, handles unknown tool names
- `ResolveModelUseCase`: Agent preferred > global default > error, handles deleted provider/model
- `AgentValidator`: Name validation, prompt validation, model pair validation
- `AgentDetailViewModel`: State updates for all form fields, save, clone, delete
- Entity-domain mappers: Correct field mapping, JSON serialization of toolIds

### Integration Tests (Instrumented)
- Database seeding: Verify "General Assistant" agent exists on first launch with correct ID, name, prompt, tools
- Agent seeding + provider seeding run together in same callback without conflict
- Full CRUD flow: Create -> Read -> Update -> Delete
- Delete agent with active session -> verify session agent changes to General Assistant

### UI Tests
- Agent list shows built-in section first, then custom
- Built-in agent detail shows read-only fields, Clone button, no Save/Delete
- Custom agent detail shows editable fields, Save/Clone/Delete buttons
- Create agent form validation (empty name, empty prompt)
- Tool checkbox toggles correctly
- Agent selector bottom sheet renders all agents with current selection
- Delete confirmation dialog appears and works

### Edge Cases
- Create agent with maximum length name (100 chars) and prompt (50,000 chars)
- Delete all custom agents (only built-in remains)
- Clone an agent, then delete the original
- Clone an agent that has a preferred model, then delete that provider
- Agent's preferred model references a deleted/inactive provider (should fall back silently)
- Create two agents with the same name
- Agent with empty tool set (chat-only mode)

### Layer 2 Visual Verification Flows

Each flow is independent. State the preconditions before running.
Screenshot after each numbered step that says "Screenshot".

---

#### Flow 2-1: Agent List — Built-in Agents Appear on First Launch

**Precondition:** App installed. Navigate: Settings -> Manage Agents.

```
Goal: Verify the agent list shows the built-in "General Assistant" agent in the BUILT-IN section.

Steps:
1. Open Settings screen.
2. Tap "Manage Agents".
3. Screenshot -> Verify:
   - "BUILT-IN" section header visible.
   - "General Assistant" entry visible with a "Built-in" chip label.
   - Tool count shown below the agent name.
   - No "CUSTOM" section (or empty CUSTOM section).
```

---

#### Flow 2-2: View Built-in Agent — Read-Only

**Precondition:** Agent list visible (Flow 2-1 passed).

```
Goal: Verify built-in agent detail is read-only (no edit, no delete).

Steps:
1. Tap "General Assistant" in the agent list.
2. Screenshot -> Verify:
   - Name field is NOT editable (no cursor, grayed out or read-only style).
   - System prompt field is NOT editable.
   - Tool checkboxes are NOT tappable.
   - "Clone" button is visible.
   - No "Save" button.
   - No "Delete Agent" button.
```

---

#### Flow 2-3: Create a Custom Agent

**Precondition:** Agent list visible.

```
Goal: Verify a new custom agent can be created and appears in the CUSTOM section.

Steps:
1. Tap the "+" (create) button on the agent list screen.
2. Screenshot -> Verify: "Create Agent" form shown; all fields empty; Save button disabled.
3. Enter name: "Test Agent".
4. Enter system prompt: "You are a test assistant."
5. Screenshot -> Verify: Save button is now enabled.
6. Tap "Save".
7. Screenshot -> Verify:
   - Returned to agent list.
   - "CUSTOM" section now visible.
   - "Test Agent" entry appears in the CUSTOM section.
```

---

#### Flow 2-4: Edit a Custom Agent

**Precondition:** "Test Agent" exists (Flow 2-3 passed).

```
Goal: Verify custom agent fields are editable and changes persist.

Steps:
1. Tap "Test Agent" in the CUSTOM section.
2. Screenshot -> Verify: "Edit Agent" form with editable fields; Save button disabled (no changes yet).
3. Change the name to "Test Agent v2".
4. Screenshot -> Verify: Save button is now enabled.
5. Tap "Save".
6. Screenshot -> Verify: Agent list shows "Test Agent v2" in CUSTOM section.
```

---

#### Flow 2-5: Clone a Built-in Agent

**Precondition:** Agent list visible.

```
Goal: Verify cloning creates a new custom agent with the correct name prefix.

Steps:
1. Tap "General Assistant".
2. Tap "Clone".
3. Screenshot -> Verify:
   - Returned to agent list (or edit form of cloned agent).
   - "CUSTOM" section shows a new entry with name starting with "Copy of" (e.g., "Copy of General Assistant").
4. Tap the cloned agent.
5. Screenshot -> Verify: All fields match the original but the agent is now editable (Save/Delete buttons visible).
```

---

#### Flow 2-6: Delete a Custom Agent — Confirmation Dialog

**Precondition:** At least one custom agent exists (Flow 2-3 or 2-5 passed).

```
Goal: Verify deleting a custom agent shows a confirmation dialog and removes it on confirm.

Steps:
1. Tap a custom agent (e.g., "Test Agent v2").
2. Tap "Delete Agent".
3. Screenshot -> Verify: Confirmation dialog appears with a warning message.
   Expected: Dialog mentions that sessions using this agent will fall back to General Assistant.
4. Tap "Cancel".
5. Screenshot -> Verify: Agent still exists in the list (deletion cancelled).
6. Re-open the agent detail and tap "Delete Agent" again.
7. Tap "Confirm" (or "Delete") in the dialog.
8. Screenshot -> Verify: Agent removed from the CUSTOM section in the list.
```

---

#### Flow 2-7: Agent Selector in Chat — Switch Agent

**Precondition:** At least one custom agent exists. A provider with a valid API key is configured.

```
Goal: Verify the agent selector bottom sheet appears and switching agents works.

Steps:
1. Navigate to the Chat screen.
2. Note the current agent name in the top bar (e.g., "General Assistant").
3. Tap the agent name / dropdown chevron in the top bar.
4. Screenshot -> Verify:
   - Bottom sheet appears with a list of all agents.
   - Current agent is highlighted/selected.
5. Tap a different agent (e.g., a custom agent).
6. Screenshot -> Verify:
   - Bottom sheet dismissed.
   - Top bar agent name updated to the new agent.
   - A system message "Switched to [agent name]" appears in the chat.
```

## Security Considerations

1. **No sensitive data in agents**: Agent data (name, prompt, tool list) is not sensitive. No encryption needed.
2. **Built-in agent immutability**: Enforced at both DAO level (WHERE clause) and repository level (explicit check).
3. **Tool set enforcement**: The tool set is a list of names, not executable code. Actual tool execution goes through `ToolExecutionEngine` which re-validates tool availability (see RFC-004).
4. **Preferred model validation**: The `ResolveModelUseCase` validates that the provider is active and the model exists before returning. Invalid preferences are silently skipped in favor of the global default.

## Dependencies

### Depends On
- **RFC-000 (Overall Architecture)**: Agent domain model, project structure, Koin setup, Room database
- **RFC-003 (Provider Management)**: Provider/model data for preferred model picker and model resolution; `ProviderRepository` interface
- **RFC-004 (Tool System)**: `ToolRegistry` for available tool list and tool definition lookup

### Depended On By
- **RFC-001 (Chat Interaction)**: Uses `AgentSelectorSheet` for mid-conversation switching; uses `ResolveModelUseCase` for model selection; uses `GetAgentToolsUseCase` for tool definitions in API calls
- **RFC-005 (Session Management)**: Sessions reference `currentAgentId`; implements `updateAgentForSessions()` for deletion fallback

## Differences from RFC-000

This RFC introduces the following additions/changes that should be reflected in RFC-000:

1. **`AgentConstants` added**: New file `core/model/AgentConstants.kt` with `GENERAL_ASSISTANT_ID` constant.
2. **`ResolvedModel` added**: New data class `core/model/ResolvedModel.kt`.
3. **`AgentRepository` interface updated**: Return types refined (`AppResult<Unit>` for update/delete).
4. **`SessionRepository.updateAgentForSessions()` added**: New method for deletion fallback (implemented in RFC-005).
5. **Agent seeding added to `AppDatabaseCallback`**: The existing callback now also seeds the General Assistant agent.

## Open Questions

- [x] ~~Should built-in agents be stored in Room DB or defined in code?~~ **Decision: Room DB via seed data**, consistent with provider seeding. Fixed ID `agent-general-assistant`.
- [x] ~~What is the General Assistant's system prompt?~~ **Decision: See seeding section above.**
- [x] ~~What is the General Assistant's fixed ID?~~ **Decision: `agent-general-assistant`**
- [ ] Should there be a max number of custom agents? For V1, no limit. May revisit if performance is a concern with very large agent lists.

## References

- [FEAT-002 PRD](../../prd/features/FEAT-002-agent.md) -- Functional requirements
- [UI Design Spec](../../design/ui-design-spec.md) -- Visual specifications for Sections 5, 6, and agent selector in Section 2
- [RFC-000 Overall Architecture](../architecture/RFC-000-overall-architecture.md) -- Project structure and data models
- [RFC-003 Provider Management](RFC-003-provider-management.md) -- Provider/model data, ProviderRepository
- [RFC-004 Tool System](RFC-004-tool-system.md) -- ToolRegistry, ToolDefinition

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
