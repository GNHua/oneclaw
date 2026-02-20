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
./gradlew :core-agent:test --tests "com.tomandy.oneclaw.agent.AgentCoordinatorTest"

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
| `lib-device-control` | Accessibility Service-based screen observation and interaction |
| `plugin-manager` | Built-in and user plugin management |
| `lib-web` | Web search (`web_search`, `web_fetch`) via Tavily or Brave; credential-gated |
| `lib-qrcode` | QR code scan and generate tools |
| `lib-location` | GPS location, nearby places search, directions URL; requires Maps API key for `search_nearby` |
| `lib-notification-media` | Notification list/inspect/dismiss + media playback control; requires Notification Listener Service |
| `lib-pdf` | PDF info, text extraction, page rendering tools |
| `lib-camera` | Headless photo capture via CameraX |
| `lib-sms-phone` | SMS send/list/search, phone dial, call log |
| `lib-voice-memo` | Audio recording and transcription via OpenAI Whisper |

### Dependency Injection

Uses **Koin**. Modules defined in:
- `app/.../di/AppModule.kt` -- singletons (database, DAOs, preferences, clients, registries)
- `app/.../di/ViewModelModule.kt` -- ViewModel factories with parameter injection
- `lib-scheduler/.../di/SchedulerModule.kt` -- scheduler components

All Koin modules registered in `OneClawApp.onCreate()`.

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

### Agent Delegation

`DelegateAgentPlugin` allows mid-conversation sub-agent execution. The LLM can invoke a different agent profile (e.g., a JavaScript-specialist agent) as a sub-task, then resume the main conversation. Only profiles other than `main` are available as delegation targets.

### Two-Tier Tool Activation

1. **Always-on tools** -- plugins with no category (WorkspacePlugin, MemoryPlugin, SchedulerPlugin, ConfigPlugin, SearchPlugin, DelegateAgentPlugin, ActivateToolsPlugin, QrCodePlugin) are always visible to the LLM
2. **On-demand categories** (e.g., `gmail`, `calendar`, `web`, `location`, `phone`, `camera`, `device_control`) -- hidden until LLM calls `activate_tools` meta-tool mid-conversation
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

Two kinds of plugins register tools in `ToolRegistry`:

**Kotlin native plugins** (registered in `PluginCoordinator.registerBuiltInPlugins()`):
- `WorkspacePlugin` -- file ops, exec, javascript_eval (always-on)
- `MemoryPlugin` -- full-text search across workspace memory files (always-on)
- `SchedulerPlugin` -- cron-based scheduled tasks (always-on)
- `ConfigPlugin` -- runtime config introspection (always-on)
- `SearchPlugin` -- `search_conversations` for conversation history search (always-on)
- `DelegateAgentPlugin` -- mid-conversation sub-agent execution (always-on)
- `ActivateToolsPlugin` -- meta-tool for two-tier dynamic tool activation (always-on)
- `QrCodePlugin` -- QR scan and generate (always-on)
- `DeviceControlPlugin` -- screen observation, tap, type, swipe via Accessibility Service (category: `device_control`)
- `WebPlugin` -- web search and fetch via Tavily/Brave (category: `web`)
- `LocationPlugin` -- GPS location, nearby places, directions (category: `location`)
- `NotificationPlugin` -- list/inspect/dismiss notifications (category: `notifications`)
- `MediaControlPlugin` -- play/pause/skip/stop media (category: `media_control`)
- `PdfToolsPlugin` -- PDF info, text extraction, page rendering (category: `pdf`)
- `SmsPhonePlugin` -- SMS send/list/search, phone dial, call log (category: `phone`)
- `CameraPlugin` -- headless photo capture via CameraX (category: `camera`)
- `VoiceMemoPlugin` -- audio recording, transcription via OpenAI Whisper (category: `voice_memo`)
- `InstallPluginTool` -- lets the LLM install new user plugins at runtime

**JS plugins** -- `plugin.json` + `plugin.js`, loaded from `assets/plugins/` (built-in) or `workspace/plugins/` (user), wrapped in `JsPlugin`. 15 built-in JS plugins: Google Workspace suite (Gmail, Gmail Settings, Calendar, Contacts, Tasks, Drive, Docs, Sheets, Slides, Forms) authenticated via Google Sign-In, plus `time`, `web-fetch` (raw HTTP), `image-gen`, `notion`, and `smart-home`.

QuickJS host bindings exposed as `oneclaw.*` namespace: `fs`, `http`, `credentials`, `notifications`, `env`, `log`.

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

### Media Attachments

Chat messages support images, audio, video, and document/PDF attachments. Media paths are stored as JSON arrays on `MessageEntity` (`imagePaths`, `audioPaths`, `videoPaths`, `documentPaths`). Input sources include gallery picker, camera capture, and audio recording with speech-to-text.

### Google Sign-In

`CompositeGoogleAuthProvider` (in `app`) is the active `GoogleAuthProvider` binding. It delegates to BYOK OAuth (`OAuthGoogleAuthManager`) first, falling back to Play Services (`GoogleAuthManager`). Manages scopes for Gmail, Calendar, Tasks, Contacts, Drive, Docs, Sheets, Presentations, and Forms. Tokens are used by the Google Workspace JS plugins.

### Memory Bootstrap

`MemoryBootstrap` loads memory context at session start (MEMORY.md + today's/yesterday's daily memory files, character-limited) and injects it into the system prompt. This is separate from the `MemoryPlugin` search tool which the LLM uses mid-conversation.

### Backup & Restore

`BackupManager` (`app/.../backup/`) exports conversations, messages, cronjobs, execution logs, preferences, and optionally media as a ZIP archive. Corresponding `BackupScreen` in settings UI.

### Database Schema

Room database (`AppDatabase`, version 11). Key entities:
- `ConversationEntity` -- id, title, timestamps, messageCount, lastMessagePreview
- `MessageEntity` -- id, conversationId, role, content, timestamp, tool fields (toolCallId, toolName, toolCalls JSON), media attachment paths (imagePaths, audioPaths, videoPaths, documentPaths as JSON arrays)

Separate `CronjobDatabase` in lib-scheduler: `CronjobEntity` + `ExecutionLog`.

### Slash Commands

Handled in `ChatViewModel.sendMessage()` before normal message flow. Built-in: `/summarize`. Skill-based commands routed via `SlashCommandRouter`. The command infrastructure lives in `app/.../command/`.

## Code Conventions

- Kotlin source files go in `src/main/java/` (Android convention, not `src/main/kotlin/`)
- Package: `com.tomandy.oneclaw`
- Composables: small, focused, in `ui/` subpackages
- ViewModels: business logic, no direct Compose dependencies
- `core-agent` module must not depend on Android framework classes beyond `android.util.Log`
