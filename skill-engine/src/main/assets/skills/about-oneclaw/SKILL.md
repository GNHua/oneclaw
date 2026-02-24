---
name: about-oneclaw
description: Comprehensive knowledge about OneClaw's architecture, features, tools, and source code
---

You now have detailed knowledge about OneClaw's architecture. Use this to answer questions about how OneClaw works, its features, and its source code. When needed, fetch actual source files from GitHub using `http_get`.

## Identity

OneClaw is an open-source Android AI assistant (Apache 2.0) at `https://github.com/GNHua/oneclaw`. It runs entirely on-device with no cloud backend and no root access required. The user provides their own LLM API key (OpenAI-compatible, Anthropic, Google Gemini, or Google Antigravity via OAuth). All data stays on-device. API keys are encrypted with hardware-backed Android KeyStore. No telemetry.

## Module Structure

| Module | Purpose | Key Entry Point |
|--------|---------|-----------------|
| `app` | Android app -- UI, DI, services, navigation | `app/src/main/java/com/tomandy/oneclaw/` |
| `core-agent` | ReAct agent loop, LLM clients, tool execution (no Android UI deps beyond `android.util.Log`) | `core-agent/src/main/java/com/tomandy/oneclaw/agent/` |
| `lib-workspace` | Sandboxed workspace tools (file ops, exec, memory search) | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/` |
| `skill-engine` | Skill loading, slash command routing, system prompt augmentation | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/` |
| `lib-scheduler` | WorkManager/AlarmManager-based scheduled task execution | `lib-scheduler/src/main/java/com/tomandy/oneclaw/scheduler/` |
| `plugin-runtime` | QuickJS-based JavaScript plugin engine | `plugin-runtime/src/main/java/com/tomandy/oneclaw/plugin/` |
| `plugin-manager` | Built-in and user plugin management | `plugin-manager/src/main/java/com/tomandy/oneclaw/pluginmanager/` |
| `lib-device-control` | Accessibility Service-based screen observation and interaction | `lib-device-control/src/main/java/com/tomandy/oneclaw/devicecontrol/` |
| `lib-web` | Web search (`web_search`, `web_fetch`) via Tavily or Brave; credential-gated | `lib-web/src/main/java/com/tomandy/oneclaw/web/` |
| `lib-qrcode` | QR code scan and generate tools | `lib-qrcode/src/main/java/com/tomandy/oneclaw/qrcode/` |
| `lib-location` | GPS location, nearby places search, directions URL; requires Maps API key for `search_nearby` | `lib-location/src/main/java/com/tomandy/oneclaw/location/` |
| `lib-notification-media` | Notification list/inspect/dismiss + media playback control; requires Notification Listener Service | `lib-notification-media/src/main/java/com/tomandy/oneclaw/notification/` |
| `lib-pdf` | PDF info, text extraction, page rendering tools | `lib-pdf/src/main/java/com/tomandy/oneclaw/pdf/` |
| `lib-camera` | Headless photo capture via CameraX | `lib-camera/src/main/java/com/tomandy/oneclaw/camera/` |
| `lib-sms-phone` | SMS send/list/search, phone dial, call log | `lib-sms-phone/src/main/java/com/tomandy/oneclaw/sms/` |
| `lib-messaging-bridge` | Telegram, Discord, WebChat messaging channels; scheduled task forwarding | `lib-messaging-bridge/src/main/java/com/tomandy/oneclaw/bridge/` |
| `lib-voice-memo` | Audio recording and transcription via OpenAI Whisper | `lib-voice-memo/src/main/java/com/tomandy/oneclaw/voicememo/` |

## Chat Execution Data Flow

1. `ChatInput` composable -> `ChatViewModel.sendMessage()` -> inserts user `MessageEntity` to Room DB
2. `ChatViewModel` calls `ChatExecutionService.startExecution()` (foreground service via Intent)
3. `ChatExecutionService` resolves active agent profile, builds system prompt (base text + skills XML block + conversation summary)
4. Creates `AgentCoordinator` with `toolFilter` from profile's `allowedTools`
5. `AgentCoordinator.execute()` -> `ReActLoop.step()` -> `LlmClient.complete()` (iterates with tool calls)
6. Final response persisted to DB -> UI updates reactively via Room `Flow` observation

Key files:
- `app/.../service/ChatExecutionService.kt` -- foreground service orchestration
- `app/.../viewmodel/ChatViewModel.kt` -- UI-layer message handling
- `core-agent/.../agent/AgentCoordinator.kt` -- tool filtering, category activation, loop control
- `core-agent/.../agent/ReActLoop.kt` -- single-step LLM call + tool execution
- `core-agent/.../agent/llm/LlmClient.kt` -- LLM abstraction interface

## Services & Background Execution

`ChatExecutionService` is a foreground service (keeps process alive during LLM calls). It supports:
- `ACTION_EXECUTE` -- run agent loop
- `ACTION_CANCEL` -- stop execution
- `ACTION_INJECT` -- add user message mid-loop (message injection into running ReAct loop)
- `ACTION_SUMMARIZE` -- force conversation summarization

State bridged to UI via `ChatExecutionTracker` (singleton with `StateFlow`s).

`AgentExecutionService` (in `lib-scheduler`) is a separate foreground service for scheduled task execution. Starts via `CronjobAlarmReceiver`, runs the agent loop, sends completion/error notifications, and calls `TaskCompletionNotifier` to forward results to messaging bridge channels.

`MessagingBridgeService` (in `lib-messaging-bridge`) is a foreground service that manages messaging channel lifecycles (Telegram, Discord, WebChat). Starts/stops channels based on preferences. Registers channels with `BridgeBroadcaster` for outbound broadcast.

## LLM Client Layer

Three implementations of `LlmClient` in `core-agent`:
- `OpenAiClient` -- Retrofit-based, supports custom base URLs (OpenAI-compatible APIs)
- `AnthropicClient` -- Official `anthropic-java` SDK
- `GeminiClient` -- Official `google-genai` SDK

Managed by `LlmClientProvider` (in `app` module) which handles API key loading, provider switching, and model selection.

## Agent Profiles

Markdown files with YAML frontmatter defining persona, model, and tool/skill access.

- Bundled: `assets/agents/*.md`, User: `workspace/agents/*.md`
- Frontmatter fields: `name`, `description`, `model`, `allowedTools` (whitelist), `enabledSkills` (whitelist)
- Body = system prompt text
- Active profile selected globally via `ModelPreferences.getActiveAgent()`
- Profile's `allowedTools` becomes `toolFilter` passed to `AgentCoordinator`
- Profile's `model` overrides global model selection
- Loaded/merged by `AgentProfileRepository` (bundled + user, user overrides)

## Agent Delegation

`DelegateAgentPlugin` allows mid-conversation sub-agent execution. The LLM can invoke a different agent profile (e.g., a JavaScript-specialist agent) as a sub-task, then resume the main conversation. Only profiles other than `main` are available as delegation targets. Creates an isolated sub-conversation with a temporary conversation ID. Tool registry excludes `delegate_to_agent` itself to prevent recursion. Timeout: 10 minutes, max 50 iterations.

## Two-Tier Tool Activation

1. **Always-on tools** -- plugins with no category (WorkspacePlugin, MemoryPlugin, SchedulerPlugin, ConfigPlugin, SearchPlugin, DelegateAgentPlugin, ActivateToolsPlugin, QrCodePlugin) are always visible to the LLM
2. **On-demand categories** (e.g., `gmail`, `calendar`, `web`, `location`, `phone`, `camera`, `device_control`) -- hidden until LLM calls `activate_tools` meta-tool mid-conversation
3. Once activated, a category stays active for the rest of the conversation (`AgentCoordinator.activeCategories`)
4. Agent profile's `allowedTools` further restricts the final tool set

## Complete Tool Inventory

### Always-on tools (no activation needed)

| Tool | Plugin | Description |
|------|--------|-------------|
| `read_file` | WorkspacePlugin | Read a file from the workspace |
| `write_file` | WorkspacePlugin | Write/create a file in the workspace |
| `edit_file` | WorkspacePlugin | Apply edits to an existing file |
| `list_files` | WorkspacePlugin | List files and directories |
| `exec` | WorkspacePlugin | Run a shell command in the workspace |
| `javascript_eval` | WorkspacePlugin | Evaluate JavaScript in QuickJS |
| `search_memory` | MemoryPlugin | Full-text search across memory files |
| `schedule_task` | SchedulerPlugin | Schedule one-time or recurring agent tasks |
| `list_scheduled_tasks` | SchedulerPlugin | List all scheduled tasks |
| `run_scheduled_task` | SchedulerPlugin | Run a scheduled task immediately |
| `cancel_scheduled_task` | SchedulerPlugin | Cancel (disable) a scheduled task |
| `update_scheduled_task` | SchedulerPlugin | Update an existing scheduled task |
| `delete_scheduled_task` | SchedulerPlugin | Permanently delete a scheduled task |
| `get_config` | ConfigPlugin | Read runtime configuration |
| `set_config` | ConfigPlugin | Update runtime configuration |
| `search_conversations` | SearchPlugin | Search conversation history |
| `delegate_agent` | DelegateAgentPlugin | Run a sub-task with a different agent profile |
| `activate_tools` | ActivateToolsPlugin | Activate an on-demand tool category |
| `scan_qr_code` | QrCodePlugin | Scan a QR code from an image |
| `generate_qr_code` | QrCodePlugin | Generate a QR code image |
| `http_get` | web-fetch (JS) | Fetch a URL via GET |
| `http_post` | web-fetch (JS) | POST JSON to a URL |
| `http_request` | web-fetch (JS) | Full HTTP request with custom method/headers |
| `get_current_datetime` | time (JS) | Get current date/time |
| `install_plugin` | InstallPluginTool | Install a JS plugin from source |

### On-demand categories (use `activate_tools` first)

| Category | Tools | Plugin |
|----------|-------|--------|
| `web` | `web_search`, `web_fetch` | WebPlugin |
| `device_control` | `observe_screen`, `tap`, `type_text`, `swipe`, `press_key`, `open_app` | DeviceControlPlugin |
| `location` | `get_location`, `search_nearby`, `get_directions_url` | LocationPlugin |
| `phone` | `send_sms`, `list_sms`, `search_sms`, `dial_phone`, `get_call_log` | SmsPhonePlugin |
| `camera` | `take_photo` | CameraPlugin |
| `voice_memo` | `record_audio`, `transcribe_audio` | VoiceMemoPlugin |
| `notifications` | `list_notifications`, `get_notification`, `dismiss_notification` | NotificationPlugin |
| `media_control` | `media_play_pause`, `media_next`, `media_previous`, `media_stop` | MediaControlPlugin |
| `pdf` | `pdf_info`, `pdf_extract_text`, `pdf_render_page` | PdfToolsPlugin |

### Google Workspace tools (via Google Sign-In)

| Plugin | Key Tools |
|--------|-----------|
| `gmail` | `gmail_search`, `gmail_read`, `gmail_send`, `gmail_reply`, `gmail_trash`, `gmail_label` |
| `gmail-settings` | `gmail_get_filters`, `gmail_create_filter`, `gmail_get_auto_reply`, `gmail_set_auto_reply` |
| `calendar` | `calendar_list_events`, `calendar_create_event`, `calendar_update_event`, `calendar_delete_event` |
| `contacts` | `contacts_search`, `contacts_create`, `contacts_update` |
| `tasks` | `tasks_list`, `tasks_create`, `tasks_complete`, `tasks_delete` |
| `drive` | `drive_search`, `drive_upload`, `drive_download`, `drive_create_folder` |
| `docs` | `docs_create`, `docs_read`, `docs_append` |
| `sheets` | `sheets_create`, `sheets_read`, `sheets_write`, `sheets_append` |
| `slides` | `slides_create`, `slides_add_slide`, `slides_read` |
| `forms` | `forms_create`, `forms_add_question`, `forms_get_responses` |

## Plugin System

Two kinds of plugins register tools in `ToolRegistry`:

**Kotlin native plugins** (registered in `PluginCoordinator.registerBuiltInPlugins()`):
- `WorkspacePlugin` -- file ops, exec, javascript_eval (always-on)
- `MemoryPlugin` -- full-text search across workspace memory files (always-on)
- `SchedulerPlugin` -- cron-based scheduled tasks (always-on)
- `ConfigPlugin` -- runtime config introspection and updates (always-on)
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

**JS plugins** -- `plugin.json` + `plugin.js`, loaded from `assets/plugins/` (built-in) or `workspace/plugins/` (user), wrapped in `JsPlugin`. 16 built-in JS plugins: Google Workspace suite (Gmail, Gmail Settings, Calendar, Contacts, Tasks, Drive, Docs, Sheets, Slides, Forms, Places) authenticated via Google Sign-In, plus `time`, `web-fetch` (raw HTTP), `image-gen`, `notion`, and `smart-home`.

QuickJS host bindings exposed as `oneclaw.*` namespace: `fs`, `http`, `credentials`, `notifications`, `env`, `log`.

## Workspace & Memory

Workspace root: `context.filesDir/workspace/` (Android internal storage, no permissions needed).

`WorkspacePlugin` tools: `read_file`, `write_file`, `edit_file`, `list_files`, `exec`, `javascript_eval`
`MemoryPlugin` tools: `search_memory` (full-text search across `MEMORY.md` and `memory/*.md`)

Path security: `resolveSafePath()` rejects absolute paths and validates canonical paths stay within workspace root.

### Memory Bootstrap

`MemoryBootstrap` loads memory context at session start and injects it into the system prompt:
1. Reads `MEMORY.md` (long-term) -- max 4,000 characters
2. Reads today's daily memory (`memory/YYYY-MM-DD.md`) -- max 4,000 characters
3. Reads yesterday's daily memory -- max 2,000 characters
4. Returns a formatted context block wrapped in `--- Your Memory ---` markers

This is separate from the `MemoryPlugin` `search_memory` tool which the LLM uses mid-conversation.

## Skill Engine

Skills are markdown files with YAML frontmatter. Only metadata appears in the system prompt (as XML `<available_skills>` block via `SystemPromptBuilder`). Full skill body is injected on-demand when user invokes the slash command.

- Bundled: `assets/skills/{name}/SKILL.md`, User: `workspace/skills/{name}/SKILL.md` (user overrides bundled)
- `SlashCommandRouter` resolves `/skill-name` to skill body injection
- Enable/disable per skill via `SkillPreferences`
- Skills with `disable-model-invocation: true` are excluded from the XML block (user-only invocation)

## Conversation Summarization

- Auto-triggers when prompt tokens exceed 80% of context window
- Splits history: old messages summarized, recent messages kept
- Summary stored as meta message (`role = "meta"`, `toolName = "summary"`), prepended to system prompt on subsequent turns
- Pre-summarization callback (`onBeforeSummarize`) flushes important context to memory
- Force via `/summarize` command or `summarize_conversation` tool

## Meta Messages

`MessageEntity` with `role = "meta"` are system-level chat entries. Discriminated by `toolName`:
- `"stopped"` -- user cancelled execution
- `"summary"` -- conversation summarization result
- `"command"` -- slash command feedback

Meta messages are naturally excluded from LLM history (the history builder only picks up `user`/`assistant`/`stopped`).

## Media Attachments

Chat messages support images, audio, video, and document/PDF attachments. Media paths are stored as JSON arrays on `MessageEntity` (`imagePaths`, `audioPaths`, `videoPaths`, `documentPaths`). Input sources include gallery picker, camera capture, and audio recording with speech-to-text.

## Messaging Bridge

`lib-messaging-bridge` provides external messaging channel support. Users can interact with OneClaw through Telegram, Discord, or a built-in WebChat server. The bridge runs as a foreground service (`MessagingBridgeService`) and manages channel lifecycles.

### Architecture

- `MessagingChannel` -- abstract base class for all channels (Telegram, Discord, WebChat)
- `BridgeAgentExecutor` -- interface for triggering agent execution from bridge messages
- `BridgeMessageObserver` -- interface for awaiting agent responses (implemented via Room Flow)
- `ConversationMapper` -- resolves the active conversation for bridge messages
- `BridgeBroadcaster` -- singleton that holds references to active channels and broadcasts messages to all of them
- `BridgePreferences` -- per-channel enable/disable, allowed user IDs, last chat ID tracking

### Channels

- **TelegramChannel** -- long-polls via Telegram Bot API, access control via allowed user IDs
- **DiscordChannel** -- connects via Discord Gateway WebSocket, access control via allowed user IDs
- **WebChatChannel** -- runs a NanoWSD WebSocket server with optional access token auth, serves an HTML chat UI

### Inbound Message Flow

1. External message arrives on a channel (Telegram poll, Discord gateway, WebSocket)
2. `processInboundMessage()` persists the chat ID and inserts a user message into the active conversation
3. `BridgeAgentExecutor` triggers agent execution
4. `BridgeMessageObserver` awaits the assistant response
5. Response is sent back to the originating channel
6. `/clear` command creates a new conversation

### Scheduled Task Forwarding

When a scheduled task completes, its results are forwarded to all active messaging channels:

1. `AgentExecutionService` calls `TaskCompletionNotifier` (interface in `lib-scheduler`)
2. `BridgeTaskCompletionNotifier` (in `app` module) implements the interface
3. It inserts the task title and result into the active conversation (as user + assistant messages) so the LLM has context for follow-up questions
4. It calls `BridgeBroadcaster.broadcast()` to send the result to all running channels

Key files:
- `lib-messaging-bridge/.../BridgeBroadcaster.kt` -- channel registry and broadcast
- `lib-messaging-bridge/.../service/MessagingBridgeService.kt` -- foreground service
- `lib-messaging-bridge/.../channel/MessagingChannel.kt` -- base channel class
- `lib-messaging-bridge/.../channel/telegram/TelegramChannel.kt` -- Telegram implementation
- `lib-messaging-bridge/.../channel/discord/DiscordChannel.kt` -- Discord implementation
- `lib-messaging-bridge/.../channel/webchat/WebChatChannel.kt` -- WebChat implementation
- `lib-scheduler/.../TaskCompletionNotifier.kt` -- callback interface
- `app/.../bridge/BridgeTaskCompletionNotifier.kt` -- bridges scheduler to messaging channels

## Google Sign-In & Antigravity

`OAuthGoogleAuthManager` (in `app`) is the active `GoogleAuthProvider` binding. It manages BYOK OAuth for Gmail, Calendar, Tasks, Contacts, Drive, Docs, Sheets, Presentations, and Forms scopes. Tokens are used by the Google Workspace JS plugins.

`AntigravityAuthManager` (in `app`) handles OAuth for Google Antigravity (Cloud Code Assist). Uses Gemini CLI OAuth app credentials with PKCE and loopback redirect. Provides access tokens and project IDs to `LlmClientProvider` for the Antigravity LLM provider.

## Dependency Injection

Uses Koin. Modules defined in:
- `app/.../di/AppModule.kt` -- singletons (database, DAOs, preferences, clients, registries)
- `app/.../di/ViewModelModule.kt` -- ViewModel factories with parameter injection
- `lib-scheduler/.../di/SchedulerModule.kt` -- scheduler components

All Koin modules registered in `OneClawApp.onCreate()`.

## Configuration Storage

- `ModelPreferences` (SharedPreferences) -- selected model, per-provider models, max iterations, active agent
- `ConversationPreferences` (SharedPreferences) -- active conversation ID
- `CredentialVault` (EncryptedSharedPreferences) -- API keys and base URLs per provider

## Database

Room database (`AppDatabase`, version 1). Key entities:
- `ConversationEntity` -- id, title, timestamps, messageCount, lastMessagePreview
- `MessageEntity` -- id, conversationId, role, content, timestamp, tool fields (toolCallId, toolName, toolCalls JSON), media attachment paths (imagePaths, audioPaths, videoPaths, documentPaths as JSON arrays)

Separate `CronjobDatabase` in lib-scheduler: `CronjobEntity` (id, title, instruction, scheduleType, cronExpression, executeAt, intervalMinutes, constraints, enabled, conversationId) + `ExecutionLog`.

## Slash Commands

Handled in `ChatViewModel.sendMessage()` before normal message flow. Built-in: `/summarize`. Skill-based commands routed via `SlashCommandRouter`. The command infrastructure lives in `app/.../command/`.

## Backup & Restore

`BackupManager` (`app/.../backup/`) exports conversations, messages, cronjobs, execution logs, preferences, and optionally media as a ZIP archive. Corresponding `BackupScreen` in settings UI.

## Fetching Source Code from GitHub

Use a two-step approach to find and read source files:

**Step 1 -- Discover file paths.** Fetch the full repo tree in one call:
```
http_get({ "url": "https://api.github.com/repos/GNHua/oneclaw/git/trees/main?recursive=1" })
```
This returns every file path in the repo as a JSON array of `{ "path": "...", "type": "blob|tree" }` entries. Search the result for the file you need.

**Step 2 -- Fetch the file content:**
```
http_get({ "url": "https://raw.githubusercontent.com/GNHua/oneclaw/main/<path>" })
```

**List a specific directory** (alternative to the full tree when you know the area):
```
http_get({ "url": "https://api.github.com/repos/GNHua/oneclaw/contents/<directory-path>" })
```
Returns a JSON array with `name`, `path`, `type`, and `download_url` fields.

### Entry-point files by topic

These are good starting points -- but always verify paths via the tree API first.

| Topic | File to fetch |
|-------|---------------|
| Agent loop | `core-agent/src/main/java/com/tomandy/oneclaw/agent/AgentCoordinator.kt` |
| ReAct step | `core-agent/src/main/java/com/tomandy/oneclaw/agent/ReActLoop.kt` |
| Tool registry | `core-agent/src/main/java/com/tomandy/oneclaw/agent/tool/ToolRegistry.kt` |
| LLM client interface | `core-agent/src/main/java/com/tomandy/oneclaw/agent/llm/LlmClient.kt` |
| OpenAI client | `core-agent/src/main/java/com/tomandy/oneclaw/agent/llm/OpenAiClient.kt` |
| Anthropic client | `core-agent/src/main/java/com/tomandy/oneclaw/agent/llm/AnthropicClient.kt` |
| Gemini client | `core-agent/src/main/java/com/tomandy/oneclaw/agent/llm/GeminiClient.kt` |
| Chat execution | `app/src/main/java/com/tomandy/oneclaw/service/ChatExecutionService.kt` |
| System prompt builder | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/SystemPromptBuilder.kt` |
| Workspace tools | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/WorkspacePlugin.kt` |
| Memory tools | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/MemoryPlugin.kt` |
| Memory bootstrap | `app/src/main/java/com/tomandy/oneclaw/service/MemoryBootstrap.kt` |
| Scheduler | `lib-scheduler/src/main/java/com/tomandy/oneclaw/scheduler/SchedulerPlugin.kt` |
| Scheduled execution | `lib-scheduler/src/main/java/com/tomandy/oneclaw/scheduler/service/AgentExecutionService.kt` |
| Skill loader | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/SkillLoader.kt` |
| Slash command router | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/SlashCommandRouter.kt` |
| JS plugin host | `plugin-runtime/src/main/java/com/tomandy/oneclaw/plugin/JsPlugin.kt` |
| Plugin coordinator | `plugin-manager/src/main/java/com/tomandy/oneclaw/pluginmanager/PluginCoordinator.kt` |
| Device control | `lib-device-control/src/main/java/com/tomandy/oneclaw/devicecontrol/DeviceControlPlugin.kt` |
| Messaging bridge service | `lib-messaging-bridge/src/main/java/com/tomandy/oneclaw/bridge/service/MessagingBridgeService.kt` |
| Bridge broadcaster | `lib-messaging-bridge/src/main/java/com/tomandy/oneclaw/bridge/BridgeBroadcaster.kt` |
| Koin DI setup | `app/src/main/java/com/tomandy/oneclaw/di/AppModule.kt` |
| Database schema | `app/src/main/java/com/tomandy/oneclaw/data/AppDatabase.kt` |
| Agent profiles | `app/src/main/java/com/tomandy/oneclaw/agent/profile/AgentProfileRepository.kt` |
| Google auth | `app/src/main/java/com/tomandy/oneclaw/google/OAuthGoogleAuthManager.kt` |
| Backup | `app/src/main/java/com/tomandy/oneclaw/backup/BackupManager.kt` |
