---
name: about-oneclaw
display_name: About OneClaw
description: Comprehensive knowledge base about OneClaw -- architecture, features, tools, and configuration
version: "1.0"
---

# About OneClaw

OneClaw is a local-first, BYOK (Bring Your Own Key) AI agent platform for Android. It runs entirely on the device with no backend server. API keys are encrypted with Android KeyStore. No root access required.

**GitHub:** https://github.com/GNHua/oneclaw
**Min SDK:** Android 8.0 (API 26)
**Package:** `com.oneclaw.shadow`

---

## Module Structure

Two Gradle modules:

- **`:app`** (`com.oneclaw.shadow`) -- Main Android application with UI, agent logic, tools, and all features
- **`:bridge`** (`com.oneclaw.shadow.bridge`) -- Standalone Android library for multi-channel messaging (Telegram, Discord, LINE, Slack, Matrix, WebChat)

---

## Architecture (`:app` module)

Clean Architecture with four layers:

### Core Layer (`core/`)
- `model/` -- Domain models: Agent, AiModel, Message, Session, Provider, ScheduledTask, ToolDefinition, SkillDefinition, FileInfo, Attachment, Citation
- `repository/` -- Repository interfaces
- `util/` -- `AppResult<T>` sealed class, `ErrorCode` enum

### Data Layer (`data/`)
- Room database (entities, DAOs, migrations)
- API adapters: `OpenAiAdapter`, `AnthropicAdapter`, `GeminiAdapter` (implement `ModelApiAdapter`)
- SSE parsing for streaming responses
- `EncryptedKeyStorage` via `EncryptedSharedPreferences`
- Google Drive sync (`data/sync/`)

### Feature Layer (`feature/`)
Each feature has screens, ViewModels, UI state, and use cases:
`agent`, `bridge`, `chat`, `file`, `memory`, `provider`, `schedule`, `search`, `session`, `settings`, `skill`, `tool`, `usage`

### Tool Layer (`tool/`)
- `ToolRegistry`, `ToolExecutionEngine`, `PermissionChecker`
- 39 built-in Kotlin tools in `tool/builtin/`
- QuickJS JavaScript engine in `tool/js/`
- Skill management in `tool/skill/`

### DI
Koin with 8 modules: `appModule`, `bridgeModule`, `databaseModule`, `featureModule`, `memoryModule`, `networkModule`, `repositoryModule`, `toolModule`

---

## AI Providers

Three providers, selected via `ProviderType` enum:

| Provider | Type | API Base |
|----------|------|----------|
| OpenAI | `OPENAI` | https://api.openai.com/v1 |
| Anthropic | `ANTHROPIC` | https://api.anthropic.com |
| Google Gemini | `GEMINI` | https://generativelanguage.googleapis.com |

API keys are stored encrypted in `EncryptedSharedPreferences`, never in Room. Providers and models are managed via Settings or the `create_provider`, `add_model` tools.

---

## Built-in Tools (39+)

### Web and Content
- `webfetch` -- Fetch a URL and return as Markdown
- `browser` -- Render a page in WebView, screenshot or extract content

### PDF
- `pdf_extract_text` -- Extract text from PDF (with page range support)
- `pdf_info` -- Get PDF metadata (page count, size, title)
- `pdf_render_page` -- Render a PDF page to PNG image

### Code Execution
- `exec` -- Execute shell commands on the device
- `js_eval` -- Run JavaScript in sandboxed QuickJS

### File System
- `read_file` -- Read a file from app storage
- `write_file` -- Write content to a file
- `list_files` -- List directory contents
- `delete_file` -- Delete a file or directory
- `move_file` -- Move or rename a file
- `file_info` -- Get file metadata (size, type, dates)

### Memory
- `save_memory` -- Add to persistent MEMORY.md (categories: profile, preferences, interests, workflow, projects, notes)
- `update_memory` -- Edit or delete a memory entry
- `search_history` -- Hybrid search across memory, daily logs, and past sessions

### Agent Management
- `create_agent`, `update_agent`, `delete_agent`, `list_agents`

### Scheduled Tasks
- `schedule_task` -- Create one-time, daily, or weekly tasks
- `update_scheduled_task`, `delete_scheduled_task`, `list_scheduled_tasks`, `run_scheduled_task`

### Provider and Model Configuration
- `create_provider`, `update_provider`, `delete_provider`, `list_providers`
- `add_model`, `delete_model`, `list_models`, `fetch_models`, `set_default_model`

### App Configuration
- `get_config`, `set_config` -- Read/write app settings
- `manage_env_var` -- Manage JS tool environment variables

### Tool State Management
- `list_tool_states` -- List all tools with enabled/disabled status
- `set_tool_enabled` -- Enable or disable a tool or tool group

### JavaScript Tool CRUD
- `create_js_tool`, `update_js_tool`, `delete_js_tool`, `list_user_tools`

### Skills
- `load_skill` -- Load a skill's full prompt instructions
- `load_tool_group` -- Activate all tools in a group

### Git Versioning
- `git_log` -- List commit history (optionally filtered by file path)
- `git_show` -- Show diff for a specific commit
- `git_diff` -- Compare two commits
- `git_restore` -- Restore a file to a previous version
- `git_bundle` -- Export repository as bundle for backup

---

## JavaScript Tool Groups

JS tools are loaded via `load_tool_group`. Built-in groups include:
- `google_gmail` -- Gmail read/send/search
- `google_calendar` -- Calendar events
- `google_contacts` -- Contacts lookup
- `google_tasks` -- Google Tasks
- `google_drive` -- Drive file access
- `google_docs`, `google_sheets`, `google_slides`, `google_forms`
- `web_search` -- Provider-native web search (grounding)
- `http` -- Generic HTTP requests
- `time` -- Date/time utilities

---

## Skills System

Skills are Markdown prompt templates stored as `SKILL.md` files.

**Locations:**
- Built-in: `app/src/main/assets/skills/<name>/SKILL.md`
- User-created: `filesDir/skills/<name>/SKILL.md`

**Built-in skills:** `create-skill`, `create-tool`, `about-oneclaw`

**Invocation:** `/skill-name` in chat, UI skill selector, or `load_skill` tool

**SKILL.md format:**
```
---
name: skill-name
display_name: Display Name
description: One-line description
version: "1.0"
tools_required:
  - tool_name
parameters:
  - name: param
    type: string
    required: false
    description: Description
---

# Prompt content
Use {{param}} for substitution.
```

---

## Memory System

**Long-term memory:** `filesDir/memory/MEMORY.md` -- injected into every system prompt
- Categories: profile, preferences, interests, workflow, projects, notes
- Managed via `save_memory` and `update_memory` tools

**Daily logs:** `filesDir/memory/daily/YYYY-MM-DD.md` -- auto-written at session end, app background, session switch, day change, pre-compaction

**Hybrid search:** BM25 (30%) + vector embeddings (70%) + time decay
- Embedding model: MiniLM-L6-v2 via ONNX Runtime, 384-dimensional vectors

**Git versioning (FEAT-050):** All text files auto-committed via JGit
- Auto-commit triggers: MEMORY.md update, daily log append, file write/delete
- Browse history in Memory screen via the history icon

---

## Messaging Bridge (`:bridge` module)

Channels: Telegram, Discord, LINE, Matrix (placeholder), Slack (placeholder), WebChat (placeholder)

Key components:
- `MessagingBridgeService` -- Foreground service managing all channels
- `BridgeConversationManager` -- Session routing and lifecycle
- `BridgeStateTracker` -- SharedFlow event bus for bridge↔app sync

Configure in Settings > Bridge. Each channel has its own token/credentials.

---

## Database Schema (Room)

| Entity | Description |
|--------|-------------|
| `SessionEntity` | Conversation sessions |
| `MessageEntity` | Chat messages with token usage |
| `AgentEntity` | AI agent configurations |
| `ProviderEntity` | API provider configs |
| `AiModelEntity` | Available models per provider |
| `ScheduledTaskEntity` | Scheduled task definitions |
| `TaskExecutionRecordEntity` | Execution history |
| `AttachmentEntity` | File attachments linked to messages |
| `MemoryIndexEntity` | Vector embeddings for memory search |

---

## Navigation Routes

Chat, Session drawer, Agent list/detail, Provider list/detail/setup, Settings, Scheduled task list/detail/edit, File browser/preview, Tool management, Skill editor/management, Usage statistics, Memory viewer, Bridge settings

---

## Key File Paths (on device)

```
filesDir/
├── .git/               # Git repository (auto-managed by JGit)
├── .gitignore
├── memory/
│   ├── MEMORY.md       # Long-term memory (system prompt injection)
│   └── daily/
│       └── YYYY-MM-DD.md
├── skills/             # User-created skills
│   └── <name>/SKILL.md
├── tools/              # User-created JS tools
└── attachments/        # Chat attachments
```
