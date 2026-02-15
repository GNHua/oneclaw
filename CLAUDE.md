# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build & Development Commands

```bash
# Build debug APK (do NOT install without user approval)
./gradlew assembleDebug

# Lint check (ktlint)
./gradlew ktlintCheck

# Auto-format
./gradlew ktlintFormat

# Run all unit tests
./gradlew test

# Run a single test class
./gradlew :core-agent:test --tests "com.tomandy.palmclaw.agent.AgentCoordinatorTest"

# Run Android instrumented tests (requires device/emulator)
./gradlew connectedAndroidTest
```

## Workflow Rules

- **Build after implementation** with `./gradlew assembleDebug`, but **do not install** the app until the user explicitly approves.
- **Plan files** go in `ai_docs/` as markdown (e.g., `ai_docs/2026-02-15-feature-name.md`).
- Use conventional commit messages.

## Architecture

### Multi-Module Structure

| Module | Purpose |
|--------|---------|
| `app` | Android application -- UI, DI, services, navigation |
| `core-agent` | ReAct agent loop, LLM clients, tool execution (no Android UI dependencies) |
| `lib-scheduler` | WorkManager-based scheduled task execution |
| `plugin-runtime` | QuickJS-based JavaScript plugin engine |
| `plugin-manager` | Built-in and user plugin management |

### Dependency Injection

Uses **Koin**. Modules defined in:
- `app/.../di/AppModule.kt` -- singletons (database, DAOs, preferences, clients, registries)
- `app/.../di/ViewModelModule.kt` -- ViewModel factories with parameter injection
- `lib-scheduler/.../di/SchedulerModule.kt` -- scheduler components

All Koin modules registered in `PalmClawApp.onCreate()`.

### Key Data Flow: Chat Message Execution

1. `ChatInput` (composable) -> `ChatViewModel.sendMessage()` -> inserts user `MessageEntity` to Room DB
2. `ChatViewModel` calls `ChatExecutionService.startExecution()` (foreground service via Intent)
3. `ChatExecutionService` creates `AgentCoordinator`, seeds conversation history from DB
4. `AgentCoordinator.execute()` -> `ReActLoop.step()` -> `LlmClient.complete()` (iterates with tool calls)
5. Final response persisted to DB -> UI updates reactively via Room `Flow` observation

### Services & Background Execution

`ChatExecutionService` is a **foreground service** (keeps process alive during LLM calls). It supports:
- `ACTION_EXECUTE` -- run agent loop
- `ACTION_CANCEL` -- stop execution
- `ACTION_INJECT` -- add user message mid-loop (message injection into running ReAct loop)
- `ACTION_SUMMARIZE` -- force conversation summarization

State bridged to UI via `ChatExecutionTracker` (singleton with `StateFlow`s).

### LLM Client Layer

Three implementations of `LlmClient` in `core-agent`:
- `OpenAiClient` -- Retrofit-based, supports custom base URLs (OpenAI-compatible APIs)
- `AnthropicClient` -- Official `anthropic-java` SDK
- `GeminiClient` -- Official `google-genai` SDK

Managed by `LlmClientProvider` (in `app` module) which handles API key loading, provider switching, and model selection.

### Configuration Storage

- `ModelPreferences` (SharedPreferences) -- selected model, per-provider models, max iterations
- `ConversationPreferences` (SharedPreferences) -- active conversation ID
- `CredentialVault` (EncryptedSharedPreferences) -- API keys and base URLs per provider

### Plugin System

Plugins are JavaScript files executed via QuickJS (`plugin-runtime`). Each plugin can register tools (function-calling) in `ToolRegistry`. The `ReActLoop` presents registered tools to the LLM which can invoke them during execution.

### Meta Messages

`MessageEntity` with `role = "meta"` are system-level chat entries. Discriminated by `toolName`:
- `"stopped"` -- user cancelled execution
- `"summary"` -- conversation summarization result
- `"command"` -- slash command feedback

Meta messages are naturally excluded from LLM history (the history builder only picks up `user`/`assistant`/`stopped`).

### Slash Commands

Handled in `ChatViewModel.sendMessage()` before normal message flow. Currently: `/summarize`. The command infrastructure lives in `app/.../command/`.

## Code Conventions

- Kotlin source files go in `src/main/java/` (Android convention, not `src/main/kotlin/`)
- Package: `com.tomandy.palmclaw`
- Composables: small, focused, in `ui/` subpackages
- ViewModels: business logic, no direct Compose dependencies
- `core-agent` module must not depend on Android framework classes beyond `android.util.Log`
