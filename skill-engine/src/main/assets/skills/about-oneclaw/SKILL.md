---
name: about-oneclaw
description: Comprehensive knowledge about OneClaw's architecture, features, tools, and source code
---

You now have detailed knowledge about OneClaw's architecture. Use this to answer questions about how OneClaw works, its features, and its source code. When needed, fetch actual source files from GitHub using `http_get`.

## Identity

OneClaw is an open-source Android AI assistant (Apache 2.0) at `https://github.com/GNHua/oneclaw`. It runs entirely on-device with no cloud backend and no root access required. The user provides their own LLM API key (OpenAI-compatible, Anthropic, Google Gemini, or Google Antigravity via OAuth).

## Module Structure

| Module | Purpose | Key Entry Point |
|--------|---------|-----------------|
| `app` | Android app -- UI, DI, services, navigation | `app/src/main/java/com/tomandy/oneclaw/` |
| `core-agent` | ReAct agent loop, LLM clients, tool execution | `core-agent/src/main/java/com/tomandy/oneclaw/agent/` |
| `lib-workspace` | Sandboxed workspace tools (file ops, exec) | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/` |
| `skill-engine` | Skill loading, slash commands, prompt augmentation | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/` |
| `lib-scheduler` | WorkManager/AlarmManager scheduled tasks | `lib-scheduler/src/main/java/com/tomandy/oneclaw/scheduler/` |
| `plugin-runtime` | QuickJS-based JavaScript plugin engine | `plugin-runtime/src/main/java/com/tomandy/oneclaw/plugin/` |
| `plugin-manager` | Built-in and user plugin management | `plugin-manager/src/main/java/com/tomandy/oneclaw/pluginmanager/` |
| `lib-device-control` | Accessibility Service screen automation | `lib-device-control/src/main/java/com/tomandy/oneclaw/devicecontrol/` |
| `lib-web` | Web search/fetch via Tavily or Brave | `lib-web/src/main/java/com/tomandy/oneclaw/web/` |
| `lib-qrcode` | QR code scan and generate | `lib-qrcode/src/main/java/com/tomandy/oneclaw/qrcode/` |
| `lib-location` | GPS, nearby places, directions | `lib-location/src/main/java/com/tomandy/oneclaw/location/` |
| `lib-notification-media` | Notification + media playback control | `lib-notification-media/src/main/java/com/tomandy/oneclaw/notification/` |
| `lib-pdf` | PDF info, text extraction, page rendering | `lib-pdf/src/main/java/com/tomandy/oneclaw/pdf/` |
| `lib-camera` | Headless photo capture via CameraX | `lib-camera/src/main/java/com/tomandy/oneclaw/camera/` |
| `lib-sms-phone` | SMS send/list/search, phone dial, call log | `lib-sms-phone/src/main/java/com/tomandy/oneclaw/sms/` |
| `lib-voice-memo` | Audio recording and transcription via OpenAI Whisper | `lib-voice-memo/src/main/java/com/tomandy/oneclaw/voicememo/` |

## Chat Execution Data Flow

1. `ChatInput` composable -> `ChatViewModel.sendMessage()` -> inserts user `MessageEntity` to Room DB
2. `ChatViewModel` calls `ChatExecutionService.startExecution()` (foreground service)
3. `ChatExecutionService` resolves the active agent profile, builds the system prompt (base text + skills XML + conversation summary)
4. Creates `AgentCoordinator` with a `toolFilter` from the profile's `allowedTools`
5. `AgentCoordinator.execute()` -> `ReActLoop.step()` -> `LlmClient.complete()` (iterates with tool calls)
6. Final response persisted to DB -> UI updates reactively via Room `Flow`

Key files:
- `app/.../service/ChatExecutionService.kt` -- foreground service orchestration
- `app/.../viewmodel/ChatViewModel.kt` -- UI-layer message handling
- `core-agent/.../agent/AgentCoordinator.kt` -- tool filtering, category activation, loop control
- `core-agent/.../agent/ReActLoop.kt` -- single-step LLM call + tool execution
- `core-agent/.../agent/llm/LlmClient.kt` -- LLM abstraction interface

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

## Two-Tier Tool Activation

Tools are either always-on or gated behind categories. The LLM calls `activate_tools({ "category": "web" })` to enable a category mid-conversation. Once activated, a category stays active for the rest of that conversation. Agent profiles can restrict available tools via `allowedTools` in their YAML frontmatter.

## Agent Profiles

Markdown files with YAML frontmatter in `assets/agents/` (bundled) or `workspace/agents/` (user-created). Fields: `name`, `description`, `model`, `allowedTools`, `enabledSkills`. The body is the system prompt. User profiles override bundled ones with the same name.

## Skill System

Skills are markdown files in `assets/skills/{name}/SKILL.md` (bundled) or `workspace/skills/{name}/SKILL.md` (user). Only metadata appears in the system prompt. The full skill body is injected when the user invokes `/skill-name`. Skills can be enabled/disabled in Settings.

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
| Antigravity client | `core-agent/src/main/java/com/tomandy/oneclaw/llm/AntigravityClient.kt` |
| Chat execution | `app/src/main/java/com/tomandy/oneclaw/service/ChatExecutionService.kt` |
| System prompt builder | `app/src/main/java/com/tomandy/oneclaw/service/SystemPromptBuilder.kt` |
| Workspace tools | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/WorkspacePlugin.kt` |
| Memory tools | `lib-workspace/src/main/java/com/tomandy/oneclaw/workspace/MemoryPlugin.kt` |
| Scheduler | `lib-scheduler/src/main/java/com/tomandy/oneclaw/scheduler/SchedulerPlugin.kt` |
| Skill loader | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/SkillLoader.kt` |
| Slash command router | `skill-engine/src/main/java/com/tomandy/oneclaw/skill/SlashCommandRouter.kt` |
| JS plugin host | `plugin-runtime/src/main/java/com/tomandy/oneclaw/plugin/JsPlugin.kt` |
| Plugin coordinator | `plugin-manager/src/main/java/com/tomandy/oneclaw/pluginmanager/PluginCoordinator.kt` |
| Device control | `lib-device-control/src/main/java/com/tomandy/oneclaw/devicecontrol/DeviceControlPlugin.kt` |
| Koin DI setup | `app/src/main/java/com/tomandy/oneclaw/di/AppModule.kt` |
| Database schema | `app/src/main/java/com/tomandy/oneclaw/data/AppDatabase.kt` |
| Agent profiles | `app/src/main/java/com/tomandy/oneclaw/agent/AgentProfileRepository.kt` |
| Google auth | `app/src/main/java/com/tomandy/oneclaw/auth/CompositeGoogleAuthProvider.kt` |
| Backup | `app/src/main/java/com/tomandy/oneclaw/backup/BackupManager.kt` |

## Conversation Summarization

Auto-triggers when prompt tokens exceed 80% of the context window. Old messages are summarized into a meta message prepended to the system prompt. A pre-summarization callback flushes important context to workspace memory files first.

## Database

Room database (version 11). Key tables: `ConversationEntity` (id, title, timestamps) and `MessageEntity` (id, conversationId, role, content, tool fields, media attachment JSON arrays).

## Configuration

- `ModelPreferences` (SharedPreferences) -- selected model, active agent
- `ConversationPreferences` -- active conversation ID
- `CredentialVault` (EncryptedSharedPreferences) -- API keys per provider
