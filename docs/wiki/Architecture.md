# Architecture Overview

OneClaw is built with Clean Architecture principles, separating concerns into distinct layers within a multi-module Gradle project.

## Module Structure

The project contains two Gradle modules:

- **`:app`** (package `com.oneclaw.shadow`) -- The main Android application
- **`:bridge`** (package `com.oneclaw.shadow.bridge`) -- Standalone Android library for multi-channel messaging

## Clean Architecture Layers

The `:app` module is organized into four layers:

### Core Layer (`core/`)

The innermost layer containing pure domain logic with no Android framework dependencies.

- **`model/`** -- Domain models: `Agent`, `AiModel`, `Message`, `Session`, `Provider`, `ScheduledTask`, `TaskExecutionRecord`, `ToolDefinition`, `ToolResult`, `SkillDefinition`, `FileInfo`, `FileContent`, `Attachment`, `Citation`
- **`repository/`** -- Repository interfaces: `AgentRepository`, `AttachmentRepository`, `FileRepository`, `MessageRepository`, `ProviderRepository`, `ScheduledTaskRepository`, `SessionRepository`, `SettingsRepository`, `TaskExecutionRecordRepository`
- **`util/`** -- `AppResult<T>` sealed class for error handling, `ErrorCode` enum

### Data Layer (`data/`)

Implements repository interfaces and provides data access.

- **`local/entity/`** -- Room database entities
- **`local/dao/`** -- Room DAOs for database operations
- **`local/db/`** -- `AppDatabase` definition and migrations
- **`local/mapper/`** -- Mappers between entities and domain models
- **`remote/adapter/`** -- `ModelApiAdapter` interface with `OpenAiAdapter`, `AnthropicAdapter`, `GeminiAdapter`
- **`remote/dto/`** -- Provider-specific DTOs (`openai/`, `anthropic/`, `gemini/`)
- **`remote/sse/`** -- `SseParser` for Server-Sent Events streaming
- **`repository/`** -- Repository implementations (e.g., `SessionRepositoryImpl`, `MessageRepositoryImpl`)
- **`security/`** -- `EncryptedKeyStorage` using `EncryptedSharedPreferences`
- **`storage/`** -- File storage utilities
- **`sync/`** -- Google Drive backup and data synchronization

### Feature Layer (`feature/`)

Each feature is a vertical slice containing screens, ViewModels, UI state, and use cases.

| Feature | Description |
|---------|-------------|
| `agent/` | Agent management (create, edit, delete AI personas) |
| `bridge/` | Bridge integration (app-side bridge wiring) |
| `chat/` | Chat interaction (main conversation UI) |
| `file/` | File browsing and preview |
| `memory/` | Memory management UI and embedding engine |
| `provider/` | Provider/model configuration |
| `schedule/` | Scheduled task management and alarm system |
| `search/` | Unified search across sessions, memory, logs |
| `session/` | Session lifecycle management |
| `settings/` | App settings, Google auth, data backup |
| `skill/` | Skill editor and management |
| `tool/` | Tool management UI |
| `usage/` | Token usage statistics |

### Tool Layer (`tool/`)

The AI tool execution system, separate from the feature layer.

- **`engine/`** -- `Tool` interface, `ToolRegistry`, `ToolExecutionEngine`, `ToolEnabledStateStore`
- **`builtin/`** -- 39 built-in tools in Kotlin (see [Tool Reference](Tool-Reference.md))
- **`builtin/config/`** -- Configuration tools (providers, models, agents, env vars)
- **`js/`** -- QuickJS JavaScript engine integration (`JsExecutionEngine`, `JsTool`, `UserToolManager`, bridge APIs)
- **`skill/`** -- `SkillRegistry`, `SkillFileParser` for skill management
- **`browser/`** -- `WebViewManager`, `BrowserContentExtractor`, `BrowserScreenshotCapture`

## Data Flow

```
User Input
    |
    v
ChatScreen (Composable)
    |
    v
ChatViewModel (StateFlow<ChatUiState>)
    |
    v
SendMessageUseCase
    |
    +---> MessageRepository.insert(userMessage)
    |
    +---> ModelApiAdapterFactory.getAdapter(providerType)
    |         |
    |         v
    |     ModelApiAdapter.sendMessageStream()
    |         |
    |         v
    |     Flow<StreamEvent> (SSE parsing)
    |         |
    |         +---> TextDelta -> accumulate response
    |         +---> ToolCallStart/Delta/End -> parse tool call
    |         +---> Usage -> track tokens
    |         +---> Done -> finalize
    |
    +---> ToolExecutionEngine.execute(toolCall)
    |         |
    |         v
    |     ToolRegistry.getTool(name)
    |         |
    |         v
    |     Tool.execute(parameters) -> ToolResult
    |
    +---> MessageRepository.insert(assistantMessage)
    |
    v
ChatUiState updated via StateFlow
    |
    v
UI recomposes
```

## Key Design Patterns

### Repository Pattern

Repository interfaces in `core/repository/` define the contract. Implementations in `data/repository/` combine Room DAOs with API adapters. This allows swapping data sources without changing business logic.

### Adapter Pattern

`ModelApiAdapter` abstracts the differences between AI provider APIs. `ModelApiAdapterFactory` creates the correct adapter based on `ProviderType` (OPENAI, ANTHROPIC, GEMINI). Each adapter handles:

- Request formatting (different JSON schemas per provider)
- SSE stream parsing (different event formats)
- Tool definition formatting (functions vs tools)
- Model listing and connection testing

### Use Case Pattern

Business logic is encapsulated in use case classes within `feature/*/usecase/`. Each use case has a single responsibility:

- `SendMessageUseCase` -- orchestrates the full message-response cycle
- `AutoCompactUseCase` -- compresses message history when limits are reached
- `CreateAgentUseCase` -- validates and persists new agents

### Sealed Class Error Handling

`AppResult<T>` is used throughout for fallible operations:

```kotlin
sealed class AppResult<out T> {
    data class Success<T>(val data: T) : AppResult<T>()
    data class Error(val code: ErrorCode, val message: String) : AppResult<Nothing>()
}
```

## Dependency Injection

Koin is used for DI with eight modules defined in `di/`:

| Module | Provides |
|--------|----------|
| `appModule` | Application-level singletons |
| `bridgeModule` | Bridge integration components |
| `databaseModule` | Room database, DAOs |
| `featureModule` | ViewModels |
| `memoryModule` | Embedding engine, search engine, memory injector |
| `networkModule` | OkHttpClient, API adapter factory |
| `repositoryModule` | Repository implementations |
| `toolModule` | Tool registry, execution engine, built-in tools |

## Navigation

Compose Navigation with a sealed `Route` class defines all destinations:

- Chat screen (main)
- Session drawer
- Agent list/detail
- Provider list/detail/setup
- Settings screens
- Scheduled task list/detail/edit
- File browser/preview
- Tool management
- Skill editor/management
- Usage statistics
- Memory viewer
- Bridge settings

## Database Schema

Room database (`AppDatabase`) with the following entities:

| Entity | Description |
|--------|-------------|
| `SessionEntity` | Conversation sessions with title, timestamps, soft-delete |
| `MessageEntity` | Chat messages with role, content, token usage, model info |
| `AgentEntity` | AI agent configurations with system prompts |
| `ProviderEntity` | API provider configurations |
| `AiModelEntity` | Available models per provider |
| `ScheduledTaskEntity` | Scheduled task definitions |
| `TaskExecutionRecordEntity` | Task execution history |
| `AttachmentEntity` | File attachments linked to messages |
| `MemoryIndexEntity` | Vector embeddings for memory search |

Database migrations are handled incrementally to preserve user data across app updates.
