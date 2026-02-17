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
| `lib-workspace` | Sandboxed workspace tools (file ops, exec, memory search) |
| `skill-engine` | Skill loading, slash command routing, system prompt augmentation |
| `lib-scheduler` | WorkManager/AlarmManager-based scheduled task execution |
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
3. `ChatExecutionService` resolves active agent profile, builds system prompt (base + skills XML block + summary)
4. `ChatExecutionService` creates `AgentCoordinator` with `toolFilter` from profile's `allowedTools`
5. `AgentCoordinator.execute()` -> `ReActLoop.step()` -> `LlmClient.complete()` (iterates with tool calls)
6. Final response persisted to DB -> UI updates reactively via Room `Flow` observation

### Services & Background Execution

`ChatExecutionService` is a **foreground service** (keeps process alive during LLM calls). It supports:
- `ACTION_EXECUTE` -- run agent loop
- `ACTION_CANCEL` -- stop execution
- `ACTION_INJECT` -- add user message mid-loop (message injection into running ReAct loop)
- `ACTION_SUMMARIZE` -- force conversation summarization

State bridged to UI via `ChatExecutionTracker` (singleton with `StateFlow`s).

### Agent Profiles

Markdown files with YAML frontmatter defining persona, model, and tool/skill access.

- Bundled: `assets/agents/*.md`, User: `workspace/agents/*.md`
- Frontmatter fields: `name`, `description`, `model`, `allowedTools` (whitelist), `enabledSkills` (whitelist)
- Body = system prompt text
- Active profile selected globally via `ModelPreferences.getActiveAgent()`
- Profile's `allowedTools` becomes `toolFilter` passed to `AgentCoordinator`
- Profile's `model` overrides global model selection
- Loaded/merged by `AgentProfileRepository` (bundled + user, user overrides)

### Two-Tier Tool Activation

1. **Core tools** (`category = "core"`) -- always visible to LLM (e.g., read_file, write_file, exec, search_memory, activate_tools)
2. **On-demand categories** (e.g., `gmail`, `calendar`, `scheduler`) -- hidden until LLM calls `activate_tools` meta-tool mid-conversation
3. Once activated, a category stays active for the rest of the conversation (`AgentCoordinator.activeCategories`)
4. Agent profile's `allowedTools` further restricts the final tool set

### Workspace & Memory

Workspace root: `context.filesDir/workspace/` (Android internal storage, no permissions needed).

`WorkspacePlugin` tools: `read_file`, `write_file`, `edit_file`, `list_files`, `exec`, `javascript_eval`
`MemoryPlugin` tools: `search_memory` (full-text search across `MEMORY.md` and `memory/*.md`)

Path security: `resolveSafePath()` rejects absolute paths and validates canonical paths stay within workspace root.

### Skill Engine

Skills are markdown files with YAML frontmatter. Only metadata appears in the system prompt (as XML `<available_skills>` block via `SystemPromptBuilder`). Full skill body is injected on-demand when user invokes the slash command.

- Bundled: `assets/skills/*.md`, User: `workspace/skills/*.md` (user overrides bundled)
- `SlashCommandRouter` resolves `/skill-name` to skill body injection
- Enable/disable per skill via `SkillPreferences`

### LLM Client Layer

Three implementations of `LlmClient` in `core-agent`:
- `OpenAiClient` -- Retrofit-based, supports custom base URLs (OpenAI-compatible APIs)
- `AnthropicClient` -- Official `anthropic-java` SDK
- `GeminiClient` -- Official `google-genai` SDK

Managed by `LlmClientProvider` (in `app` module) which handles API key loading, provider switching, and model selection.

### Configuration Storage

- `ModelPreferences` (SharedPreferences) -- selected model, per-provider models, max iterations, active agent
- `ConversationPreferences` (SharedPreferences) -- active conversation ID
- `CredentialVault` (EncryptedSharedPreferences) -- API keys and base URLs per provider

### Plugin System

Plugins are JavaScript files executed via QuickJS (`plugin-runtime`). Each plugin can register tools in `ToolRegistry`. Two plugin sources:
- **Kotlin native plugins** (WorkspacePlugin, MemoryPlugin, SchedulerPlugin, etc.) -- implement `Plugin` interface, registered via `PluginCoordinator.registerBuiltInPlugins()`
- **JS plugins** -- `plugin.json` + `plugin.js`, loaded from `assets/plugins/` (built-in) or `workspace/plugins/` (user), wrapped in `JsPlugin`

QuickJS host bindings exposed as `palmclaw.*` namespace: `fs`, `http`, `credentials`, `notifications`, `env`, `log`.

### Conversation Summarization

- Auto-triggers when prompt tokens exceed 80% of context window
- Splits history: old messages summarized, recent messages kept
- Summary stored as meta message (`role = "meta"`, `toolName = "summary"`), prepended to system prompt on subsequent turns
- Pre-summarization callback (`onBeforeSummarize`) flushes important context to memory
- Force via `/summarize` command or `summarize_conversation` tool

### Meta Messages

`MessageEntity` with `role = "meta"` are system-level chat entries. Discriminated by `toolName`:
- `"stopped"` -- user cancelled execution
- `"summary"` -- conversation summarization result
- `"command"` -- slash command feedback

Meta messages are naturally excluded from LLM history (the history builder only picks up `user`/`assistant`/`stopped`).

### Database Schema

Room database (`AppDatabase`, version 11). Key entities:
- `ConversationEntity` -- id, title, timestamps, messageCount, lastMessagePreview
- `MessageEntity` -- id, conversationId, role, content, timestamp, tool fields (toolCallId, toolName, toolCalls JSON), media attachment paths (imagePaths, audioPaths, videoPaths, documentPaths as JSON arrays)

Separate `CronjobDatabase` in lib-scheduler: `CronjobEntity` + `ExecutionLog`.

### Slash Commands

Handled in `ChatViewModel.sendMessage()` before normal message flow. Built-in: `/summarize`. Skill-based commands routed via `SlashCommandRouter`. The command infrastructure lives in `app/.../command/`.

## Code Conventions

- Kotlin source files go in `src/main/java/` (Android convention, not `src/main/kotlin/`)
- Package: `com.tomandy.palmclaw`
- Composables: small, focused, in `ui/` subpackages
- ViewModels: business logic, no direct Compose dependencies
- `core-agent` module must not depend on Android framework classes beyond `android.util.Log`
