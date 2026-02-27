# RFC-000: Overall Architecture

## Document Information
- **RFC ID**: RFC-000
- **Related PRD**: All (FEAT-001 through FEAT-009)
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
OneClawShadow is an Android app that serves as a mobile AI Agent runtime environment. Before implementing individual features, we need to establish the foundational technical decisions: technology stack, architecture pattern, module structure, core data models, and project directory layout. All subsequent feature RFCs will build on top of these decisions.

### Goals
1. Define the technology stack for the entire project
2. Define the overall architecture pattern and layering
3. Define the module structure and inter-module dependencies
4. Define core data models shared across modules
5. Define the project directory structure
6. Provide enough detail for AI-assisted code generation to produce consistent, reproducible results

### Non-Goals
- Detailed implementation of any specific feature (covered in RFC-001 through RFC-005)
- UI/UX design specifications (covered in [UI Design Spec](../../design/ui-design-spec.md) and PRDs)
- Testing implementation details (covered in testing strategy docs)

## Technology Stack

### Core Technologies

| Technology | Version | Purpose | Why This Choice |
|-----------|---------|---------|-----------------|
| Kotlin | 2.0.x | Primary language | Android official language. Modern, expressive, null-safe. |
| Jetpack Compose | 1.6.x+ | UI framework | Modern declarative UI. Ideal for chat interfaces with dynamic content. Less boilerplate than XML layouts. |
| Kotlin Coroutines | 1.8.x | Asynchronous programming | Native Kotlin async. Clean syntax for sequential async operations (API calls, DB queries, tool execution). |
| Kotlin Flow | (part of Coroutines) | Reactive streams | Streaming API responses, real-time UI updates, state management. |
| Room | 2.6.x | Local database (SQLite) | Jetpack official ORM. Type-safe queries, migration support, Flow integration. |
| OkHttp | 4.12.x | HTTP client | Industry standard. SSE support for streaming. Interceptors for auth headers. |
| Retrofit | 2.9.x | REST API client | Built on OkHttp. Type-safe API definitions. Mature ecosystem. |
| Koin | 3.5.x | Dependency injection | Pure Kotlin DSL, simple to configure. No annotation processing, no compile-time overhead. Easy for AI to generate correct code. Runtime dependency resolution with test verification. |
| Jetpack Navigation Compose | 2.7.x | Screen navigation | Official Compose navigation. Type-safe routes. |
| Android Keystore + EncryptedSharedPreferences | (Android SDK) | Secrets storage | Encrypt API keys at rest. Hardware-backed security on supported devices. |
| Gson / Kotlinx Serialization | Latest | JSON serialization | Parse API responses, serialize tool parameters and results. Kotlinx Serialization preferred for Kotlin-native approach. |
| Coil | 3.x | Image loading | Compose-native image loading (for future multimodal support). Lightweight. |
| compose-markdown (Mikepenz) | Latest | Markdown rendering | Render AI responses as Markdown (code blocks, tables, links). Most active Compose Markdown library. See RFC-001. |

### Build & Tooling

| Tool | Version | Purpose |
|------|---------|---------|
| Gradle | 8.x | Build system |
| Android Gradle Plugin | 8.x | Android build |
| Min SDK | 26 (Android 8.0) | Minimum supported version |
| Target SDK | 35 (Android 15) | Target version |
| Compile SDK | 35 | Compile version |
| Kotlin Compiler Plugin | Compose Compiler | Compose support |

### Key Library Choices Explained

#### Why Koin over Hilt
- **Simplicity**: Pure Kotlin DSL, no annotations, no code generation. Configuration is plain Kotlin code.
- **AI-friendly**: Simpler code is easier for AI to generate correctly and for humans to review.
- **Compile time**: No annotation processing step, faster builds.
- **Sufficient for project scale**: Runtime DI resolution is fine for an app of this complexity.
- **Testing**: Koin provides `checkModules()` to verify all dependencies resolve correctly at test time.

#### Why OkHttp + Retrofit over Ktor
- **Ecosystem maturity**: Most Android networking examples, tutorials, and StackOverflow answers use Retrofit.
- **SSE support**: OkHttp has solid SSE (Server-Sent Events) support for streaming AI responses.
- **AI code generation**: AI models have more training data on Retrofit patterns, resulting in more reliable generated code.
- **Interceptor pattern**: Clean way to inject API keys and handle auth across different providers.

#### Why Kotlinx Serialization over Gson
- **Kotlin-native**: Works with Kotlin data classes natively, including default values and nullable types.
- **No reflection**: Compile-time serialization, better performance.
- **Multiplatform ready**: If we ever consider KMP (Kotlin Multiplatform) in the future.

## Architecture Pattern

### Overall: Clean Architecture + MVVM

The app follows Clean Architecture with three main layers, combined with MVVM for the UI layer.

```
┌────────────────────────────────────────────────────┐
│                    UI Layer                         │
│  (Compose Screens, ViewModels, UI State)           │
│                                                     │
│  Screen ←→ ViewModel ←→ UI State                   │
├────────────────────────────────────────────────────┤
│                  Domain Layer                       │
│  (Use Cases, Domain Models, Repository Interfaces) │
│                                                     │
│  UseCase → Repository Interface                     │
├────────────────────────────────────────────────────┤
│                   Data Layer                        │
│  (Repository Impl, Local DB, Remote API, Tools)    │
│                                                     │
│  Repository Impl → DAO / API Service / Tool Engine │
└────────────────────────────────────────────────────┘
```

### Layer Rules

#### UI Layer
- **Contains**: Compose screens, ViewModels, UI state data classes, UI-specific mappers
- **Depends on**: Domain layer only
- **Never**: Directly accesses data sources (DB, API, file system)
- **Pattern**: MVVM with unidirectional data flow
  - ViewModel exposes `StateFlow<UiState>` to the screen
  - Screen sends events/actions to ViewModel
  - ViewModel calls UseCases, updates state

#### Domain Layer
- **Contains**: Use cases, domain model data classes, repository interfaces
- **Depends on**: Nothing (this is the innermost layer)
- **Never**: Contains Android-specific imports (no `Context`, no `Activity`, no Android SDK)
- **Purpose**: Pure business logic, testable without Android framework

#### Data Layer
- **Contains**: Repository implementations, Room DAOs and entities, Retrofit API services, data mappers
- **Depends on**: Domain layer (implements repository interfaces)
- **Never**: Exposes data-layer-specific types to the domain (e.g., Room entities stay in data layer; domain gets domain models)

### Data Flow Example: User Sends a Message

```
1. ChatScreen: User taps send button
   → ChatViewModel.sendMessage(text)

2. ChatViewModel: 
   → Updates UI state to show user message + loading indicator
   → Calls SendMessageUseCase(sessionId, text)

3. SendMessageUseCase:
   → Gets current Agent's config via AgentRepository
   → Gets provider/model config via ProviderRepository
   → Saves user message via MessageRepository
   → Calls ModelApiService.sendToModel(messages, tools, systemPrompt)

4. ModelApiService (implementation):
   → Formats request for the specific provider (OpenAI/Anthropic/Gemini adapter)
   → Sends HTTP request via Retrofit/OkHttp
   → Receives streaming response via SSE
   → Emits response chunks as Flow<StreamChunk>

5. SendMessageUseCase:
   → Collects streaming chunks
   → If chunk contains tool call → delegates to ToolExecutionEngine
   → ToolExecutionEngine runs tool, returns result
   → Result sent back to model (loop continues)
   → Saves AI response and tool call records via MessageRepository

6. ChatViewModel:
   → Collects updates from UseCase
   → Updates UI state progressively (streaming text, tool call status)

7. ChatScreen:
   → Recomposes based on UI state changes
   → User sees streaming response and tool call indicators
```

## Module Structure

### Feature Modules

The app is organized by feature modules. Each feature module contains its own UI, ViewModel, and feature-specific use cases. Shared domain models and repository interfaces live in a core module.

```
┌─────────────────────────────────────────────┐
│                  App Module                  │
│  (Application class, Navigation, DI setup)  │
├─────────────────────────────────────────────┤
│                Feature Modules               │
│  ┌─────────┐ ┌─────────┐ ┌──────────┐      │
│  │  Chat   │ │  Agent  │ │ Provider │      │
│  │ (001)   │ │ (002)   │ │  (003)   │      │
│  └────┬────┘ └────┬────┘ └────┬─────┘      │
│  ┌────┴────┐ ┌────┴────┐ ┌────┴─────┐      │
│  │  Tool   │ │ Session │ │ Settings │      │
│  │ (004)   │ │ (005)   │ │  (009)   │      │
│  └────┬────┘ └────┬────┘ └────┬─────┘      │
│       │           │           │              │
├───────┴───────────┴───────────┴──────────────┤
│                  Core Module                  │
│  (Domain models, Repository interfaces,      │
│   shared utilities, base classes)            │
├──────────────────────────────────────────────┤
│                  Data Module                  │
│  (Room DB, Retrofit services, Repository     │
│   implementations, Provider adapters)        │
└──────────────────────────────────────────────┘
```

### Module Dependencies

```
App Module
  ├── depends on: all Feature Modules, Core, Data
  │
Feature Modules (chat, agent, provider, tool, session, settings)
  ├── depends on: Core Module
  ├── does NOT depend on: other Feature Modules directly
  │   (communication goes through Core's domain layer)
  │
Core Module
  ├── depends on: nothing (pure Kotlin)
  │
Data Module
  ├── depends on: Core Module
```

### Inter-Module Communication

Feature modules do not depend on each other directly. They communicate through:
1. **Shared domain models** in Core (e.g., `Session`, `Agent`, `Message` are defined in Core)
2. **Repository interfaces** in Core (e.g., `AgentRepository` interface in Core, implementation in Data)
3. **Navigation** in App module (App module knows all feature modules and sets up navigation)
4. **Koin DI** resolves cross-module dependencies at runtime

## Core Data Models

These are the shared domain models defined in the Core module. They are used across multiple features.

### Agent

```kotlin
data class Agent(
    val id: String,                    // UUID
    val name: String,                  // Display name
    val description: String?,          // Optional description
    val systemPrompt: String,          // System prompt text
    val toolIds: List<String>,         // IDs of tools this agent can use
    val preferredProviderId: String?,  // Optional preferred provider
    val preferredModelId: String?,     // Optional preferred model
    val isBuiltIn: Boolean,            // Whether this is a built-in agent
    val createdAt: Long,               // Timestamp millis
    val updatedAt: Long                // Timestamp millis
)
```

### Provider

```kotlin
data class Provider(
    val id: String,                    // UUID
    val name: String,                  // Display name
    val type: ProviderType,            // OPENAI, ANTHROPIC, GEMINI (API protocol format)
    val apiBaseUrl: String,            // Base URL for API requests
    val isPreConfigured: Boolean,      // true = built-in template, false = user-created
    val isActive: Boolean,             // Whether this provider is enabled
    val createdAt: Long,
    val updatedAt: Long
)
// NOTE: API keys are NOT stored in the Provider domain model or in Room.
// They are stored separately in EncryptedSharedPreferences via ApiKeyStorage.
// To access the API key for a provider, use: ApiKeyStorage.getApiKey(providerId)

enum class ProviderType {
    OPENAI,     // OpenAI-compatible API format (also used for custom OpenAI-compatible endpoints)
    ANTHROPIC,  // Anthropic API format
    GEMINI      // Google Gemini API format
}
// NOTE: There is no CUSTOM type. The `type` field represents the API protocol format,
// not the service identity. User-created providers pick OPENAI, ANTHROPIC, or GEMINI
// based on which API format their endpoint is compatible with.
// The `isPreConfigured` field distinguishes built-in templates from user-created providers.
// See RFC-003 for full details.
```

### Model

```kotlin
data class AiModel(
    val id: String,                    // Model identifier (e.g., "gpt-4o")
    val displayName: String?,          // Human-friendly name
    val providerId: String,            // Which provider this belongs to
    val isDefault: Boolean,            // Whether this is the global default
    val source: ModelSource            // How this model was added
)

enum class ModelSource {
    DYNAMIC,   // Fetched from provider API
    PRESET,    // Pre-configured fallback
    MANUAL     // User-added
}
```

### Session

```kotlin
data class Session(
    val id: String,                    // UUID
    val title: String,                 // Display title
    val currentAgentId: String,        // Current agent for this session
    val messageCount: Int,             // Number of messages
    val lastMessagePreview: String?,   // Truncated last message text for list display
    val isActive: Boolean,             // Whether a request is in-flight
    val deletedAt: Long?,              // Soft-delete timestamp (null = not deleted)
    val createdAt: Long,
    val updatedAt: Long                // Last activity timestamp
)
```

### ResolvedModel

```kotlin
/**
 * Pairs an AiModel with its Provider for model resolution result.
 * Used by ResolveModelUseCase (RFC-002) to return the resolved model + provider together.
 */
data class ResolvedModel(
    val model: AiModel,
    val provider: Provider
)
```

### Message

```kotlin
data class Message(
    val id: String,                    // UUID
    val sessionId: String,             // Which session this belongs to
    val type: MessageType,             // USER, AI_RESPONSE, TOOL_CALL, TOOL_RESULT, ERROR, SYSTEM
    val content: String,               // Message text content
    val thinkingContent: String?,      // AI thinking/reasoning content (for AI_RESPONSE type)
    val toolCallId: String?,           // Tool call ID (for TOOL_CALL and TOOL_RESULT types)
    val toolName: String?,             // Tool name (for TOOL_CALL type)
    val toolInput: String?,            // Tool input JSON (for TOOL_CALL type)
    val toolOutput: String?,           // Tool output JSON (for TOOL_RESULT type)
    val toolStatus: ToolCallStatus?,   // Tool execution status
    val toolDurationMs: Long?,         // Tool execution duration
    val tokenCountInput: Int?,         // Input tokens (if available)
    val tokenCountOutput: Int?,        // Output tokens (if available)
    val modelId: String?,              // Which model generated this (for AI_RESPONSE)
    val providerId: String?,           // Which provider was used
    val createdAt: Long                // Timestamp
)

enum class MessageType {
    USER,          // User's text message
    AI_RESPONSE,   // AI's text response (may include thinking content)
    TOOL_CALL,     // AI requested a tool call
    TOOL_RESULT,   // Result returned from tool execution
    ERROR,         // Error message (API failure, tool failure, etc.)
    SYSTEM         // System message (e.g., "Switched to Agent X")
}

enum class ToolCallStatus {
    PENDING,       // Tool call requested, not yet executed
    EXECUTING,     // Tool is currently running
    SUCCESS,       // Tool completed successfully
    ERROR,         // Tool failed
    TIMEOUT        // Tool timed out
}
```

### ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,                  // Unique tool name (snake_case)
    val description: String,           // Human-readable description
    val parametersSchema: ToolParametersSchema, // Structured parameter schema (see RFC-004)
    val requiredPermissions: List<String>,  // Android permissions needed
    val timeoutSeconds: Int            // Max execution time
)

data class ToolParametersSchema(
    val properties: Map<String, ToolParameter>,   // Parameter name -> definition
    val required: List<String> = emptyList()       // Names of required parameters
)

data class ToolParameter(
    val type: String,                  // "string", "integer", "number", "boolean", "object", "array"
    val description: String,           // Human-readable description
    val enum: List<String>? = null,    // Allowed values (if restricted)
    val default: Any? = null           // Default value (if optional)
)
```

### ToolResult

```kotlin
data class ToolResult(
    val status: ToolResultStatus,      // SUCCESS or ERROR
    val result: String?,               // Result data (for success)
    val errorType: String?,            // Error type (for error)
    val errorMessage: String?          // Error message (for error)
)

enum class ToolResultStatus {
    SUCCESS, ERROR
}
```

## Database Schema

Room database with the following tables. Room entities are in the Data layer and map to/from domain models.

### Tables

#### agents
```sql
CREATE TABLE agents (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    description TEXT,
    system_prompt TEXT NOT NULL,
    tool_ids TEXT NOT NULL,           -- JSON array of tool ID strings
    preferred_provider_id TEXT,
    preferred_model_id TEXT,
    is_built_in INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
```

#### providers
```sql
CREATE TABLE providers (
    id TEXT PRIMARY KEY NOT NULL,
    name TEXT NOT NULL,
    type TEXT NOT NULL,               -- "OPENAI", "ANTHROPIC", "GEMINI"
    api_base_url TEXT NOT NULL,
    is_pre_configured INTEGER NOT NULL DEFAULT 0,
    is_active INTEGER NOT NULL DEFAULT 1,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
-- NOTE: API keys are NOT stored in this table.
-- They are stored in EncryptedSharedPreferences (Android Keystore-backed)
-- via the ApiKeyStorage class, keyed by provider ID.
```

#### models
```sql
CREATE TABLE models (
    id TEXT NOT NULL,
    display_name TEXT,
    provider_id TEXT NOT NULL,
    is_default INTEGER NOT NULL DEFAULT 0,
    source TEXT NOT NULL,             -- "DYNAMIC", "PRESET", "MANUAL"
    PRIMARY KEY (id, provider_id),
    FOREIGN KEY (provider_id) REFERENCES providers(id) ON DELETE CASCADE
);
```

#### sessions
```sql
CREATE TABLE sessions (
    id TEXT PRIMARY KEY NOT NULL,
    title TEXT NOT NULL,
    current_agent_id TEXT NOT NULL,
    message_count INTEGER NOT NULL DEFAULT 0,
    last_message_preview TEXT,
    is_active INTEGER NOT NULL DEFAULT 0,
    deleted_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);

CREATE INDEX idx_sessions_updated_at ON sessions(updated_at);
CREATE INDEX idx_sessions_deleted_at ON sessions(deleted_at);
```

#### messages
```sql
CREATE TABLE messages (
    id TEXT PRIMARY KEY NOT NULL,
    session_id TEXT NOT NULL,
    type TEXT NOT NULL,               -- "USER", "AI_RESPONSE", "TOOL_CALL", "TOOL_RESULT", "ERROR", "SYSTEM"
    content TEXT NOT NULL,
    thinking_content TEXT,
    tool_call_id TEXT,
    tool_name TEXT,
    tool_input TEXT,                  -- JSON string
    tool_output TEXT,                 -- JSON string
    tool_status TEXT,                 -- "PENDING", "EXECUTING", "SUCCESS", "ERROR", "TIMEOUT"
    tool_duration_ms INTEGER,
    token_count_input INTEGER,
    token_count_output INTEGER,
    model_id TEXT,
    provider_id TEXT,
    created_at INTEGER NOT NULL,
    FOREIGN KEY (session_id) REFERENCES sessions(id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_session_id ON messages(session_id);
CREATE INDEX idx_messages_created_at ON messages(created_at);
```

#### app_settings
```sql
CREATE TABLE app_settings (
    key TEXT PRIMARY KEY NOT NULL,
    value TEXT NOT NULL
);
```

## Repository Interfaces

Defined in the Core module, implemented in the Data module.

```kotlin
interface AgentRepository {
    fun getAllAgents(): Flow<List<Agent>>
    suspend fun getAgentById(id: String): Agent?
    suspend fun createAgent(agent: Agent): Agent
    suspend fun updateAgent(agent: Agent): AppResult<Unit>
    suspend fun deleteAgent(id: String): AppResult<Unit>
    suspend fun getBuiltInAgents(): List<Agent>
}
// See RFC-002 for full details on Agent management, including built-in agent seeding,
// clone operation, and model resolution logic.

interface ProviderRepository {
    fun getAllProviders(): Flow<List<Provider>>
    fun getActiveProviders(): Flow<List<Provider>>
    suspend fun getProviderById(id: String): Provider?
    suspend fun createProvider(provider: Provider)
    suspend fun updateProvider(provider: Provider)
    suspend fun deleteProvider(id: String): AppResult<Unit>
    suspend fun setProviderActive(id: String, isActive: Boolean)
    suspend fun getModelsForProvider(providerId: String): List<AiModel>
    suspend fun fetchModelsFromApi(providerId: String): AppResult<List<AiModel>>
    suspend fun addManualModel(providerId: String, modelId: String, displayName: String?): AppResult<Unit>
    suspend fun deleteManualModel(providerId: String, modelId: String): AppResult<Unit>
    suspend fun testConnection(providerId: String): AppResult<ConnectionTestResult>
    fun getGlobalDefaultModel(): Flow<AiModel?>
    suspend fun setGlobalDefaultModel(modelId: String, providerId: String)
}
// See RFC-003 for full details on provider management, including pre-configured
// provider seeding, model list fetching, manual model operations, and connection testing.

interface SessionRepository {
    fun getAllSessions(): Flow<List<Session>>
    suspend fun getSessionById(id: String): Session?
    suspend fun createSession(session: Session): Session
    suspend fun updateSession(session: Session)
    suspend fun softDeleteSession(id: String)
    suspend fun softDeleteSessions(ids: List<String>)
    suspend fun restoreSession(id: String)
    suspend fun restoreSessions(ids: List<String>)
    suspend fun hardDeleteSession(id: String)
    suspend fun hardDeleteAllSoftDeleted()
    suspend fun updateAgentForSessions(oldAgentId: String, newAgentId: String)
    suspend fun updateTitle(id: String, title: String)
    suspend fun setGeneratedTitle(id: String, title: String)
    suspend fun updateMessageStats(id: String, count: Int, preview: String?)
    suspend fun setActive(id: String, isActive: Boolean)
    suspend fun updateCurrentAgent(id: String, agentId: String)
}
// Soft-delete: Sessions have a deleted_at field. softDeleteSession sets it; restoreSession clears it.
// hardDeleteSession permanently removes the session and its messages (CASCADE).
// hardDeleteAllSoftDeleted: Called on app startup to clean up sessions left in soft-deleted state.
// updateAgentForSessions: Updates all sessions referencing oldAgentId to use newAgentId.
//   Used by DeleteAgentUseCase (RFC-002) for fallback to General Assistant.
// See RFC-005 for full details on session management.

interface MessageRepository {
    fun getMessagesForSession(sessionId: String): Flow<List<Message>>
    suspend fun addMessage(message: Message): Message
    suspend fun updateMessage(message: Message)
    suspend fun deleteMessagesForSession(sessionId: String)
    suspend fun getMessageCount(sessionId: String): Int
    suspend fun getMessagesSnapshot(sessionId: String): List<Message>  // Non-reactive snapshot for building API requests (RFC-001)
    suspend fun deleteMessage(id: String)                               // Delete single message; used by regenerate and retry (RFC-001)
}

interface SettingsRepository {
    suspend fun getString(key: String): String?
    suspend fun setString(key: String, value: String)
    suspend fun getBoolean(key: String, default: Boolean = false): Boolean
    suspend fun setBoolean(key: String, value: Boolean)
}
```

## Provider Adapter Pattern

Different AI providers have different API formats. We use an adapter pattern to abstract this.

```kotlin
// Core interface for all provider adapters
interface ModelApiAdapter {
    // Send a chat completion request with streaming
    fun sendMessageStream(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        messages: List<ApiMessage>,
        tools: List<ToolDefinition>?,
        systemPrompt: String?
    ): Flow<StreamEvent>

    // Fetch available models
    suspend fun listModels(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<List<AiModel>>

    // Test connection
    suspend fun testConnection(
        apiBaseUrl: String,
        apiKey: String
    ): AppResult<ConnectionTestResult>

    // Convert tool definitions to provider-specific format
    fun formatToolDefinitions(tools: List<ToolDefinition>): Any

    // Send a simple (non-streaming) chat completion request.
    // Used for lightweight tasks like title generation (RFC-005).
    // Full streaming implementation in RFC-001.
    suspend fun generateSimpleCompletion(
        apiBaseUrl: String,
        apiKey: String,
        modelId: String,
        prompt: String,
        maxTokens: Int = 100
    ): AppResult<String>
}

// Stream events emitted during a streaming response
sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolCallStart(val toolCallId: String, val toolName: String) : StreamEvent()
    data class ToolCallDelta(val toolCallId: String, val argumentsDelta: String) : StreamEvent()
    data class ToolCallEnd(val toolCallId: String) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent()
    data class Error(val message: String, val code: String?) : StreamEvent()
    object Done : StreamEvent()
}

// Implementations (each takes OkHttpClient for HTTP calls)
class OpenAiAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }
class AnthropicAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }
class GeminiAdapter(client: OkHttpClient) : ModelApiAdapter { /* ... */ }

// Factory to get the right adapter
class ModelApiAdapterFactory(private val okHttpClient: OkHttpClient) {
    fun getAdapter(providerType: ProviderType): ModelApiAdapter {
        return when (providerType) {
            ProviderType.OPENAI -> OpenAiAdapter(okHttpClient)
            ProviderType.ANTHROPIC -> AnthropicAdapter(okHttpClient)
            ProviderType.GEMINI -> GeminiAdapter(okHttpClient)
        }
    }
}
```

## Tool Execution Engine

```kotlin
// Tool interface that all tools must implement
interface Tool {
    val definition: ToolDefinition
    suspend fun execute(parameters: Map<String, Any?>): ToolResult
}

// Tool registry
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()

    fun register(tool: Tool) {
        tools[tool.definition.name] = tool
    }

    fun getTool(name: String): Tool? = tools[name]

    fun getAllTools(): List<ToolDefinition> = tools.values.map { it.definition }

    fun getToolsByIds(ids: List<String>): List<ToolDefinition> =
        ids.mapNotNull { tools[it]?.definition }
}

// Tool execution engine
class ToolExecutionEngine(
    private val registry: ToolRegistry,
    private val permissionChecker: PermissionChecker
) {
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolIds: List<String>
    ): ToolResult {
        // 1. Check tool exists and is available
        val tool = registry.getTool(toolName)
            ?: return ToolResult.error("tool_not_found", "Tool '$toolName' not found")

        if (toolName !in availableToolIds) {
            return ToolResult.error("tool_not_available", "Tool '$toolName' is not available for this agent")
        }

        // 2. Check permissions
        val missingPermissions = permissionChecker.getMissingPermissions(tool.definition.requiredPermissions)
        if (missingPermissions.isNotEmpty()) {
            val granted = permissionChecker.requestPermissions(missingPermissions)
            if (!granted) {
                return ToolResult.error("permission_denied", "Required permissions were denied")
            }
        }

        // 3. Execute with timeout
        return try {
            withTimeout(tool.definition.timeoutSeconds * 1000L) {
                tool.execute(parameters)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "Tool execution timed out after ${tool.definition.timeoutSeconds}s")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Tool execution failed: ${e.message}")
        }
    }
}
```

## Project Directory Structure

```
app/
├── build.gradle.kts
├── src/
│   ├── main/
│   │   ├── AndroidManifest.xml
│   │   ├── kotlin/com/oneclaw/shadow/
│   │   │   │
│   │   │   ├── OneclawApplication.kt          # Application class, Koin initialization
│   │   │   ├── MainActivity.kt                # Single activity (Compose)
│   │   │   │
│   │   │   ├── core/                           # Core Module
│   │   │   │   ├── model/                      # Domain models
│   │   │   │   │   ├── Agent.kt
│   │   │   │   │   ├── Provider.kt
│   │   │   │   │   ├── AiModel.kt
│   │   │   │   │   ├── Session.kt
│   │   │   │   │   ├── Message.kt
│   │   │   │   │   ├── ToolDefinition.kt
│   │   │   │   │   ├── ToolResult.kt
│   │   │   │   │   ├── ResolvedModel.kt
│   │   │   │   │   └── AgentConstants.kt        # GENERAL_ASSISTANT_ID constant (RFC-002)
│   │   │   │   ├── repository/                 # Repository interfaces
│   │   │   │   │   ├── AgentRepository.kt
│   │   │   │   │   ├── ProviderRepository.kt
│   │   │   │   │   ├── SessionRepository.kt
│   │   │   │   │   ├── MessageRepository.kt
│   │   │   │   │   └── SettingsRepository.kt
│   │   │   │   └── util/                       # Shared utilities
│   │   │   │       ├── Result.kt               # Result wrapper
│   │   │   │       └── DateTimeUtils.kt
│   │   │   │
│   │   │   ├── data/                           # Data Module
│   │   │   │   ├── local/                      # Local data sources
│   │   │   │   │   ├── db/
│   │   │   │   │   │   ├── AppDatabase.kt      # Room database definition
│   │   │   │   │   │   └── Converters.kt       # Type converters
│   │   │   │   │   ├── dao/                    # Room DAOs
│   │   │   │   │   │   ├── AgentDao.kt
│   │   │   │   │   │   ├── ProviderDao.kt
│   │   │   │   │   │   ├── ModelDao.kt
│   │   │   │   │   │   ├── SessionDao.kt
│   │   │   │   │   │   ├── MessageDao.kt
│   │   │   │   │   │   └── SettingsDao.kt
│   │   │   │   │   ├── entity/                 # Room entities
│   │   │   │   │   │   ├── AgentEntity.kt
│   │   │   │   │   │   ├── ProviderEntity.kt
│   │   │   │   │   │   ├── ModelEntity.kt
│   │   │   │   │   │   ├── SessionEntity.kt
│   │   │   │   │   │   ├── MessageEntity.kt
│   │   │   │   │   │   └── SettingsEntity.kt
│   │   │   │   │   └── mapper/                 # Entity <-> Domain model mappers
│   │   │   │   │       ├── AgentMapper.kt
│   │   │   │   │       ├── ProviderMapper.kt
│   │   │   │   │       ├── SessionMapper.kt
│   │   │   │   │       └── MessageMapper.kt
│   │   │   │   ├── remote/                     # Remote data sources
│   │   │   │   │   ├── adapter/                # Provider API adapters
│   │   │   │   │   │   ├── ModelApiAdapter.kt          # Interface
│   │   │   │   │   │   ├── ModelApiAdapterFactory.kt
│   │   │   │   │   │   ├── OpenAiAdapter.kt
│   │   │   │   │   │   ├── AnthropicAdapter.kt
│   │   │   │   │   │   ├── GeminiAdapter.kt
│   │   │   │   │   │   └── ApiMessage.kt       # ApiMessage sealed class, ApiToolCall (RFC-001)
│   │   │   │   │   ├── dto/                    # Data transfer objects
│   │   │   │   │   │   ├── openai/
│   │   │   │   │   │   ├── anthropic/
│   │   │   │   │   │   └── gemini/
│   │   │   │   │   └── sse/                    # SSE streaming support
│   │   │   │   │       └── SseParser.kt        # ResponseBody.asSseFlow() utility (RFC-001)
│   │   │   │   ├── repository/                 # Repository implementations
│   │   │   │   │   ├── AgentRepositoryImpl.kt
│   │   │   │   │   ├── ProviderRepositoryImpl.kt
│   │   │   │   │   ├── SessionRepositoryImpl.kt
│   │   │   │   │   ├── MessageRepositoryImpl.kt
│   │   │   │   │   └── SettingsRepositoryImpl.kt
│   │   │   │   └── security/                   # API key storage
│   │   │   │       └── ApiKeyStorage.kt        # EncryptedSharedPreferences wrapper
│   │   │   │
│   │   │   ├── tool/                           # Tool Module
│   │   │   │   ├── engine/
│   │   │   │   │   ├── Tool.kt                 # Tool interface
│   │   │   │   │   ├── ToolRegistry.kt
│   │   │   │   │   ├── ToolExecutionEngine.kt
│   │   │   │   │   ├── ToolSchemaSerializer.kt  # ToolParametersSchema -> JSON Schema (RFC-004)
│   │   │   │   │   └── PermissionChecker.kt
│   │   │   │   └── builtin/                    # Built-in tool implementations
│   │   │   │       ├── GetCurrentTimeTool.kt
│   │   │   │       ├── ReadFileTool.kt
│   │   │   │       ├── WriteFileTool.kt
│   │   │   │       └── HttpRequestTool.kt
│   │   │   │
│   │   │   ├── feature/                        # Feature Modules (UI + ViewModels)
│   │   │   │   ├── chat/                       # FEAT-001
│   │   │   │   │   ├── ChatScreen.kt
│   │   │   │   │   ├── ChatViewModel.kt
│   │   │   │   │   ├── ChatUiState.kt          # ChatUiState, ChatMessageItem, ActiveToolCall
│   │   │   │   │   ├── ChatEvent.kt            # Sealed class: StreamingText, ThinkingText, ToolCallStarted, etc.
│   │   │   │   │   ├── components/             # Chat-specific UI components
│   │   │   │   │   │   ├── ChatTopBar.kt
│   │   │   │   │   │   ├── ChatInput.kt
│   │   │   │   │   │   ├── MessageList.kt
│   │   │   │   │   │   ├── UserMessageBubble.kt
│   │   │   │   │   │   ├── AiMessageBubble.kt
│   │   │   │   │   │   ├── ThinkingBlock.kt
│   │   │   │   │   │   ├── ToolCallCard.kt
│   │   │   │   │   │   ├── ToolResultCard.kt
│   │   │   │   │   │   ├── ErrorMessageCard.kt
│   │   │   │   │   │   ├── SystemMessageCard.kt
│   │   │   │   │   │   ├── EmptyChatState.kt
│   │   │   │   │   │   └── StreamingCursor.kt
│   │   │   │   │   └── usecase/                # Chat-specific use cases
│   │   │   │   │       ├── SendMessageUseCase.kt
│   │   │   │   │       └── MessageToApiMapper.kt  # Message -> ApiMessage conversion
│   │   │   │   │
│   │   │   │   ├── agent/                      # FEAT-002
│   │   │   │   │   ├── AgentListScreen.kt
│   │   │   │   │   ├── AgentDetailScreen.kt
│   │   │   │   │   ├── AgentSelectorSheet.kt    # Bottom sheet for chat agent switching
│   │   │   │   │   ├── AgentListViewModel.kt
│   │   │   │   │   ├── AgentDetailViewModel.kt
│   │   │   │   │   ├── AgentUiState.kt
│   │   │   │   │   ├── AgentValidator.kt        # Input validation for agent fields
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── CreateAgentUseCase.kt
│   │   │   │   │       ├── CloneAgentUseCase.kt
│   │   │   │   │       ├── DeleteAgentUseCase.kt
│   │   │   │   │       ├── GetAgentToolsUseCase.kt
│   │   │   │   │       └── ResolveModelUseCase.kt
│   │   │   │   │
│   │   │   │   ├── provider/                   # FEAT-003
│   │   │   │   │   ├── ProviderListScreen.kt
│   │   │   │   │   ├── ProviderDetailScreen.kt
│   │   │   │   │   ├── SetupScreen.kt          # First-time setup
│   │   │   │   │   ├── ProviderListViewModel.kt
│   │   │   │   │   ├── ProviderDetailViewModel.kt
│   │   │   │   │   ├── ProviderUiState.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── TestConnectionUseCase.kt
│   │   │   │   │       ├── FetchModelsUseCase.kt
│   │   │   │   │       └── SetDefaultModelUseCase.kt
│   │   │   │   │
│   │   │   │   ├── session/                    # FEAT-005
│   │   │   │   │   ├── SessionDrawerContent.kt # Drawer composable (session list)
│   │   │   │   │   ├── SessionListViewModel.kt
│   │   │   │   │   ├── SessionUiState.kt
│   │   │   │   │   ├── components/
│   │   │   │   │   │   ├── SessionListItemRow.kt
│   │   │   │   │   │   └── RenameSessionDialog.kt
│   │   │   │   │   └── usecase/
│   │   │   │   │       ├── CreateSessionUseCase.kt
│   │   │   │   │       ├── DeleteSessionUseCase.kt
│   │   │   │   │       ├── BatchDeleteSessionsUseCase.kt
│   │   │   │   │       ├── RenameSessionUseCase.kt
│   │   │   │   │       ├── GenerateTitleUseCase.kt
│   │   │   │   │       └── CleanupSoftDeletedUseCase.kt
│   │   │   │   │
│   │   │   │   └── settings/                   # FEAT-009
│   │   │   │       ├── SettingsScreen.kt
│   │   │   │       ├── SettingsViewModel.kt
│   │   │   │       └── SettingsUiState.kt
│   │   │   │
│   │   │   ├── navigation/                     # App navigation
│   │   │   │   ├── NavGraph.kt
│   │   │   │   └── Routes.kt
│   │   │   │
│   │   │   ├── di/                             # Koin dependency injection modules
│   │   │   │   ├── AppModule.kt                # Application-level dependencies
│   │   │   │   ├── DatabaseModule.kt           # Room database, DAOs
│   │   │   │   ├── NetworkModule.kt            # OkHttp, Retrofit
│   │   │   │   ├── RepositoryModule.kt         # Repository bindings
│   │   │   │   ├── ToolModule.kt               # Tool registry, tools
│   │   │   │   └── FeatureModule.kt            # ViewModels, UseCases
│   │   │   │
│   │   │   └── ui/                             # Shared UI components and theme
│   │   │       ├── theme/
│   │   │       │   ├── Theme.kt
│   │   │       │   ├── Color.kt
│   │   │       │   ├── Typography.kt
│   │   │       │   └── Shape.kt
│   │   │       └── components/                 # Shared/reusable UI components
│   │   │           ├── LoadingIndicator.kt
│   │   │           ├── ErrorMessage.kt
│   │   │           └── ConfirmationDialog.kt
│   │   │
│   │   └── res/
│   │       ├── values/
│   │       │   ├── strings.xml
│   │       │   ├── colors.xml
│   │       │   └── themes.xml
│   │       ├── values-zh/
│   │       │   └── strings.xml                 # Chinese translations
│   │       ├── drawable/
│   │       └── mipmap/
│   │
│   ├── test/                                   # Unit tests
│   │   └── kotlin/com/oneclaw/shadow/
│   │       ├── core/
│   │       ├── data/
│   │       ├── tool/
│   │       └── feature/
│   │
│   └── androidTest/                            # Instrumented tests
│       └── kotlin/com/oneclaw/shadow/
│           ├── data/
│           └── feature/
```

## Koin Dependency Injection Setup

```kotlin
// AppModule.kt - Application-level dependencies
val appModule = module {
    single { ApiKeyStorage(get()) }  // EncryptedSharedPreferences for API keys
    single { ModelApiAdapterFactory(get()) }  // get() = OkHttpClient from NetworkModule
}

// DatabaseModule.kt
val databaseModule = module {
    single { Room.databaseBuilder(get(), AppDatabase::class.java, "oneclaw.db").build() }
    single { get<AppDatabase>().agentDao() }
    single { get<AppDatabase>().providerDao() }
    single { get<AppDatabase>().modelDao() }
    single { get<AppDatabase>().sessionDao() }
    single { get<AppDatabase>().messageDao() }
    single { get<AppDatabase>().settingsDao() }
}

// NetworkModule.kt
val networkModule = module {
    single {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)    // Longer for streaming
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

// RepositoryModule.kt
val repositoryModule = module {
    single<AgentRepository> { AgentRepositoryImpl(get()) }
    single<ProviderRepository> { ProviderRepositoryImpl(get(), get(), get(), get()) }
    single<SessionRepository> { SessionRepositoryImpl(get()) }
    single<MessageRepository> { MessageRepositoryImpl(get()) }
    single<SettingsRepository> { SettingsRepositoryImpl(get()) }
}

// ToolModule.kt
val toolModule = module {
    single { ToolRegistry().apply {
        register(GetCurrentTimeTool())
        register(ReadFileTool())
        register(WriteFileTool())
        register(HttpRequestTool(get()))  // Needs OkHttpClient
    }}
    single { PermissionChecker(get()) }
    single { ToolExecutionEngine(get(), get()) }
}

// FeatureModule.kt
val featureModule = module {
    // Chat
    factory { SendMessageUseCase(get(), get(), get(), get(), get(), get(), get(), get()) }
    // SendMessageUseCase params: AgentRepository, SessionRepository, MessageRepository,
    //   ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory, ToolExecutionEngine, ToolRegistry
    viewModel { ChatViewModel(get(), get(), get(), get(), get(), get()) }
    // ChatViewModel params: SendMessageUseCase, SessionRepository, MessageRepository,
    //   AgentRepository, CreateSessionUseCase, GenerateTitleUseCase

    // Agent
    factory { CreateAgentUseCase(get()) }                    // AgentRepository
    factory { CloneAgentUseCase(get()) }                     // AgentRepository
    factory { DeleteAgentUseCase(get(), get()) }             // AgentRepository, SessionRepository
    factory { GetAgentToolsUseCase(get(), get()) }           // AgentRepository, ToolRegistry
    factory { ResolveModelUseCase(get(), get()) }            // AgentRepository, ProviderRepository
    viewModel { AgentListViewModel(get(), get()) }           // AgentRepository, ToolRegistry
    viewModel { AgentDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
    // AgentDetailViewModel params: AgentRepository, ProviderRepository, ToolRegistry,
    //   CreateAgentUseCase, CloneAgentUseCase, DeleteAgentUseCase, SavedStateHandle

    // Provider
    factory { TestConnectionUseCase(get()) }
    factory { FetchModelsUseCase(get()) }
    factory { SetDefaultModelUseCase(get()) }
    viewModel { ProviderListViewModel(get(), get()) }        // ProviderRepository, ApiKeyStorage
    viewModel { ProviderDetailViewModel(get(), get(), get(), get(), get(), get()) }
    // ProviderDetailViewModel params: ProviderRepository, ApiKeyStorage,
    //   TestConnectionUseCase, FetchModelsUseCase, SetDefaultModelUseCase, SavedStateHandle

    // Session
    factory { CreateSessionUseCase(get()) }
    factory { DeleteSessionUseCase(get()) }
    factory { BatchDeleteSessionsUseCase(get()) }
    factory { RenameSessionUseCase(get()) }
    factory { GenerateTitleUseCase(get(), get(), get(), get()) }  // SessionRepository, ProviderRepository, ApiKeyStorage, ModelApiAdapterFactory
    factory { CleanupSoftDeletedUseCase(get()) }
    viewModel { SessionListViewModel(get(), get(), get(), get(), get()) }  // SessionRepository, AgentRepository, DeleteSessionUseCase, BatchDeleteSessionsUseCase, RenameSessionUseCase

    // Settings
    viewModel { SettingsViewModel(get()) }
}

// Application class
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin {
            androidContext(this@OneclawApplication)
            modules(
                appModule,
                databaseModule,
                networkModule,
                repositoryModule,
                toolModule,
                featureModule
            )
        }

        // Cleanup any sessions that were soft-deleted but not hard-deleted
        // (e.g., app was killed during undo window). See RFC-005.
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            val cleanup: CleanupSoftDeletedUseCase = get()
            cleanup()
        }
    }
}
```

## Navigation Structure

```kotlin
// Routes.kt
sealed class Route(val path: String) {
    object SessionList : Route("sessions")
    object Chat : Route("chat/{sessionId}") {
        fun create(sessionId: String) = "chat/$sessionId"
    }
    object NewChat : Route("chat/new")
    object AgentList : Route("agents")
    object AgentDetail : Route("agents/{agentId}") {
        fun create(agentId: String) = "agents/$agentId"
    }
    object AgentCreate : Route("agents/create")
    object ProviderList : Route("providers")
    object ProviderDetail : Route("providers/{providerId}") {
        fun create(providerId: String) = "providers/$providerId"
    }
    object Setup : Route("setup")          // First-launch welcome, skippable
    object Settings : Route("settings")
}

// Navigation flow:
// App first launch (no provider configured) → Welcome/Setup screen (skippable)
//   - User can set up a provider OR tap "Skip for now" to go to Chat
//   - Welcome screen is only shown once on first launch; after that, always Chat
// App subsequent launches → Chat/new (empty chat, home screen)
// When user tries to send a message with no provider configured:
//   - Show inline error prompting to configure a provider, with link to Settings
// Chat screen has: Drawer (session list) on left, Settings on right
// Drawer → NewChat / Chat(sessionId)
// Chat → AgentSwitcher (bottom sheet via top bar agent name)
// Settings → ProviderList / ProviderDetail / AgentList / AgentDetail
// AgentList → AgentDetail / AgentCreate
// ProviderList → ProviderDetail
//
// See UI Design Spec (docs/design/ui-design-spec.md) for detailed screen layouts
```

## Error Handling Strategy

### Result Wrapper

```kotlin
// Used throughout the app for operations that can fail
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(
        val exception: Exception? = null,
        val message: String,
        val code: ErrorCode = ErrorCode.UNKNOWN
    ) : AppResult<Nothing>()
}

enum class ErrorCode {
    NETWORK_ERROR,
    AUTH_ERROR,
    TIMEOUT_ERROR,
    VALIDATION_ERROR,
    STORAGE_ERROR,
    PERMISSION_ERROR,
    PROVIDER_ERROR,
    TOOL_ERROR,
    UNKNOWN
}
```

### Error Handling by Layer

1. **Data Layer**: Catches exceptions, wraps in `AppResult.Error` with appropriate `ErrorCode`
2. **Domain Layer**: Passes `AppResult` through, may add business logic validation errors
3. **UI Layer**: ViewModel translates `AppResult.Error` into user-facing error messages in UI state

## Implementation Phases

### Phase 1: Project Foundation
1. Create Android project with correct package name and SDK versions
2. Set up Gradle dependencies (all libraries listed in tech stack)
3. Set up Koin dependency injection modules (empty initially)
4. Create Room database with all entity classes and DAOs
5. Create all domain models in Core
6. Create all repository interfaces in Core
7. Create repository implementations in Data (basic CRUD)
8. Set up Compose theme (colors, typography, shapes)
9. Set up navigation graph with placeholder screens
10. Verify the app builds and runs

### Phase 2: Provider & Model (FEAT-003)
1. Implement provider adapter pattern (OpenAI, Anthropic, Gemini adapters)
2. Implement ApiKeyStorage (EncryptedSharedPreferences wrapper)
3. Implement provider management screens (list, detail)
4. Implement model list fetching (dynamic + preset fallback)
5. Implement connection testing
6. Implement skippable welcome/setup screen (shown once on first launch; "Skip for now" goes to chat)
7. Verify: user can add a provider, test connection, see models

### Phase 3: Tool System (FEAT-004)
1. Implement Tool interface and ToolRegistry
2. Implement ToolExecutionEngine with timeout and error handling
3. Implement built-in tools (get_current_time, read_file, write_file, http_request)
4. Implement permission checker
5. Verify: tools can be registered, invoked, return results

### Phase 4: Agent Management (FEAT-002)
1. Implement built-in General Assistant agent
2. Implement agent management screens (list, detail, create)
3. Implement clone functionality
4. Implement tool selection in agent config
5. Verify: user can create/edit/clone/delete agents, select tools

### Phase 5: Session Management (FEAT-005)
1. Implement session list screen (home screen)
2. Implement session creation, deletion (swipe + undo), batch delete
3. Implement title generation (Phase 1: truncated, Phase 2: AI-generated)
4. Implement manual title editing
5. Verify: user can create/resume/delete sessions, titles auto-generate

### Phase 6: Chat Interaction (FEAT-001)
1. Implement chat screen with message list
2. Implement message input and send
3. Implement streaming response rendering
4. Implement tool call display (compact and detailed modes)
5. Implement thinking block display (collapsed/expandable)
6. Implement Markdown rendering
7. Implement stop generation
8. Implement message copying
9. Implement agent switching in chat
10. Implement auto-scroll and scroll-to-bottom
11. Verify: full conversation flow with streaming, tool calls, and agent switching

### Phase 7: Polish & Integration
1. End-to-end testing of complete flows
2. Error handling refinement
3. Performance optimization (lazy loading, list performance)
4. Offline behavior verification
5. UI polish (animations, transitions, empty states)

## Performance Guidelines

### Memory
- Use `LazyColumn` for all lists (session list, message list, agent list)
- Lazy-load message history when opening a session (paginated, not all at once)
- Release large tool results from memory after they are persisted

### Network
- Streaming responses: process chunks as they arrive, don't buffer entire response
- Model list fetch: cache results, refresh only on explicit user action or periodic schedule
- Connection test: 10-second timeout

### UI
- Use `remember` and `derivedStateOf` to minimize recompositions in Compose
- Use `Immutable` or `Stable` annotations on data classes passed to Compose
- Message list: use `key` parameter in `LazyColumn` items for efficient diffing

### Database
- Index `messages.session_id` and `messages.created_at` for fast queries
- Use `Flow` for reactive queries (session list auto-updates when data changes)
- Batch inserts for tool call results when multiple tools execute

## Security Guidelines

1. **API keys**: Stored in `EncryptedSharedPreferences` backed by Android Keystore -- NOT in the Room database. The `ApiKeyStorage` class provides `getApiKey(providerId)` and `setApiKey(providerId, key)`. API keys are never part of the Provider domain model or database entity. Never logged. Never sent anywhere except the configured provider endpoint.
2. **Database**: Use Room's standard SQLite. The database contains NO sensitive secrets (API keys are in EncryptedSharedPreferences). For future consideration, SQLCipher can be added for full DB encryption.
3. **Network**: All provider API calls over HTTPS. OkHttp handles certificate verification.
4. **Logs**: Never log API keys, full API responses (may contain sensitive user data), or tool results that access local files.

## UI Theme

The app uses a Material 3 theme generated from Material Theme Builder. The theme files are stored in `/material-theme/` and should be copied into the project at build time.

### Theme Summary
- **Color scheme**: Warm gold/amber primary (`#6D5E0F`), with green tertiary accent
- **Variants**: Light, Dark, Medium Contrast, High Contrast (all four included)
- **Dynamic Color**: Enabled on Android 12+ (follows user wallpaper); falls back to the defined color scheme on older devices
- **Font**: Roboto (via Google Fonts provider)
- **Typography**: Standard Material 3 typography scale with Roboto applied to all levels

### Theme Files
- `material-theme/ui/theme/Color.kt` - All color definitions
- `material-theme/ui/theme/Theme.kt` - Theme composition with dynamic color support
- `material-theme/ui/theme/Type.kt` - Typography definitions
- `material-theme/res/values-v23/font_certs.xml` - Google Fonts certificate

### Integration
When creating the Android project:
1. Copy theme files to `app/src/main/kotlin/com/oneclaw/shadow/ui/theme/`
2. Update package name from `com.example.compose` to `com.oneclaw.shadow.ui.theme`
3. Copy `font_certs.xml` to `app/src/main/res/values-v23/`

## Open Questions

- [x] ~~Should we use Gradle multi-module or single-module?~~ **Decision: Single Gradle module with package separation.** Simpler build config, easier for AI code generation, sufficient for project scale. Layer boundaries enforced by package structure (`core/`, `data/`, `feature/`, `tool/`) and code review.
- [ ] Exact versions of all libraries should be pinned when project is created.
- [ ] ProGuard/R8 rules for release builds need to be defined.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Updated navigation flow: chat is home screen, session list in drawer, settings on right; added UI Design Spec reference | - |
| 2026-02-27 | 0.3 | API keys moved from Room DB to EncryptedSharedPreferences (ApiKeyStorage); removed api_key field from Provider model and providers table; Setup screen is skippable (shown once on first launch, user can skip to chat) |
| 2026-02-27 | 0.4 | Removed ProviderType.CUSTOM; type now represents API protocol format (OPENAI, ANTHROPIC, GEMINI); isPreConfigured distinguishes built-in vs user-created; see RFC-003 for details | - |
| 2026-02-27 | 0.5 | AgentRepository: updateAgent/deleteAgent now return AppResult<Unit>; getBuiltInAgents is now suspend; SessionRepository: added updateAgentForSessions() for agent deletion fallback; see RFC-002 for details | - |
| 2026-02-27 | 0.6 | Session model: added lastMessagePreview, deletedAt fields; sessions table: added new columns + indexes; SessionRepository: expanded with soft-delete/restore/batch/rename/messageStats/active/agentSwitch methods; ModelApiAdapter: added generateSimpleCompletion(); added ResolvedModel data class; updated session feature directory structure and Koin config; added CleanupSoftDeletedUseCase on app startup; see RFC-005 for details | - |
| 2026-02-27 | 0.7 | MessageRepository: added getMessagesSnapshot() and deleteMessage(); chat feature directory structure expanded (ChatEvent, ChatTopBar, MessageList, UserMessageBubble, AiMessageBubble, ThinkingBlock, ToolCallCard, ToolResultCard, ErrorMessageCard, SystemMessageCard, EmptyChatState, StreamingCursor, MessageToApiMapper); added ApiMessage.kt and SseParser.kt to data/remote/; Koin config: updated SendMessageUseCase (8 deps) and ChatViewModel (6 deps); added compose-markdown to tech stack; see RFC-001 for details | - |
| 2026-02-27 | 0.8 | Cross-consistency fixes: ProviderRepository updated to match RFC-003 (Result->AppResult, added getActiveProviders/setProviderActive/addManualModel/deleteManualModel, deleteProvider returns AppResult<Unit>); ModelApiAdapter listModels/testConnection return AppResult; ModelApiAdapterFactory takes OkHttpClient (matches RFC-003); ToolDefinition.parametersSchema changed from String to ToolParametersSchema (matches RFC-004); added ToolParametersSchema/ToolParameter data classes; Koin FeatureModule: added GetAgentToolsUseCase and ResolveModelUseCase, fixed AgentListViewModel/AgentDetailViewModel/ProviderListViewModel/ProviderDetailViewModel dependency counts (matches RFC-002/RFC-003); Directory tree: added ModelDao.kt, AgentConstants.kt, AgentSelectorSheet.kt, AgentValidator.kt, GetAgentToolsUseCase.kt, ResolveModelUseCase.kt, ToolSchemaSerializer.kt; DatabaseModule: added modelDao() | - |
