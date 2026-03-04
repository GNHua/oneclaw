<p align="center">
  <img src="docs/icon.png" width="128" height="128" alt="OneClaw">
</p>

<h1 align="center">OneClaw</h1>

<p align="center">
  An AI agent platform for Android that brings Large Language Models to your phone -- with tools, skills, memory, and automation.
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-3DDC84?logo=android" alt="Android">
  <img src="https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin" alt="Kotlin">
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-4285F4" alt="Compose">
  <img src="https://img.shields.io/badge/License-All%20Rights%20Reserved-lightgrey" alt="License">
</p>

---

## What is OneClaw?

OneClaw is a **local-first, BYOK (Bring Your Own Key)** AI assistant for Android. No root access required. It goes beyond simple chat -- the agent can call tools, run skills, schedule tasks, manage persistent memory, and automate your device through an extensible JavaScript plugin system.

All data stays on your device. You provide your own API keys. Nothing is collected.

## Screenshots

<p align="center">
  <img src="docs/screenshots/chat-screen.png" width="220" alt="Chat conversation">
  &nbsp;&nbsp;
  <img src="docs/screenshots/history.png" width="220" alt="Conversation history">
  &nbsp;&nbsp;
  <img src="docs/screenshots/model-providers-screen.png" width="220" alt="Model providers">
  &nbsp;&nbsp;
  <img src="docs/screenshots/settings-screen.png" width="220" alt="Settings">
</p>

## Features

**Multi-provider LLM support** -- OpenAI, Anthropic Claude, and Google Gemini. Switch providers and models per conversation.

**Tool execution** -- The agent calls 39+ built-in tools mid-conversation: read/write files, run shell commands, fetch web pages, process PDFs, search memory, manage scheduled tasks, and more. Tools activate on demand.

**JavaScript tool engine** -- Extend OneClaw with custom JavaScript tools running in a sandboxed QuickJS engine. Built-in tool groups include Google Workspace (Gmail, Calendar, Contacts, Tasks, Drive, Docs, Sheets, Slides, Forms), web search, HTTP, and time utilities.

**Skills** -- Markdown-based reusable prompt templates invoked via slash commands (`/create-skill`, `/create-tool`, `/about-oneclaw`, or any custom skill). Create your own or use the built-ins.

**Scheduled tasks** -- Set one-time or recurring reminders. The agent creates and manages them through natural conversation, backed by WorkManager/AlarmManager.

**Persistent memory** -- The agent writes and searches memory files across conversations, building long-term context about your preferences and past interactions. Hybrid search with BM25 + vector embeddings (MiniLM-L6-v2).

**Git-based file versioning** -- All text files (memory, daily logs, AI-generated files) are automatically versioned in a local git repository. Browse and restore history from within the app.

**Agent profiles** -- Define custom personas with different models, tools, and system prompts.

**File attachments** -- Attach images and PDFs to messages for vision and document analysis.

**Messaging bridge** -- Interact with OneClaw through Telegram, Discord, or other messaging platforms. Scheduled task results are automatically forwarded to all active channels.

**Conversation summarization** -- Auto-summarizes long conversations to stay within context windows while preserving important information.

**Security** -- API keys encrypted with hardware-backed Android KeyStore. All data stored locally. No telemetry.

## Architecture

OneClaw is a two-module Kotlin project:

| Module | Purpose |
|--------|---------|
| `app` | Android UI, agent logic, 39+ built-in tools, JS engine, skills, DI, navigation |
| `bridge` | Standalone messaging library (Telegram, Discord, LINE, Slack, Matrix, WebChat) |

### Clean Architecture layers (`:app`)

| Layer | Contents |
|-------|----------|
| `core/` | Domain models, repository interfaces, `AppResult<T>` error handling |
| `data/` | Room DB, API adapters (OpenAI/Anthropic/Gemini), SSE streaming, encrypted key storage, Google Drive sync |
| `feature/` | 13 feature verticals: `agent`, `bridge`, `chat`, `file`, `memory`, `provider`, `schedule`, `search`, `session`, `settings`, `skill`, `tool`, `usage` |
| `tool/` | `ToolRegistry`, `ToolExecutionEngine`, 39 built-in Kotlin tools, QuickJS JS engine, skill management |

### Key data flow

1. User sends a message via Compose UI
2. `ChatViewModel` persists the message and invokes `SendMessageUseCase`
3. `ModelApiAdapterFactory` selects the right adapter (`OpenAiAdapter`, `AnthropicAdapter`, or `GeminiAdapter`)
4. The adapter streams SSE events: text deltas, tool calls, usage stats
5. `ToolExecutionEngine` executes tool calls via `ToolRegistry`
6. All messages persisted to Room; UI updates reactively via StateFlow

## Install

1. Download the latest APK from the [Releases](https://github.com/GNHua/oneclaw/releases/latest) page
2. Transfer the APK to your Android device (or download directly on the device)
3. Open the APK to install -- you may need to enable "Install from unknown sources" in your device settings
4. Open OneClaw, go to Settings, select your LLM provider, and enter your API key

Requires Android 8.0 (API 26) or later.

## Build from Source

### Prerequisites

- Android Studio Hedgehog (2023.1.1) or later
- Android SDK 26+ (Android 8.0)
- JDK 17+

### Build

```bash
git clone https://github.com/GNHua/oneclaw.git
cd oneclaw

# Build debug APK
./gradlew assembleDebug

# Run unit tests
./gradlew test

# Run instrumented tests (requires emulator)
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest
```

### Supported Providers

| Provider | API Key |
|----------|---------|
| OpenAI | https://platform.openai.com/api-keys |
| Anthropic | https://console.anthropic.com/settings/keys |
| Google Gemini | https://aistudio.google.com/apikey |

## Tech Stack

| Category | Technology |
|----------|-----------|
| Language | Kotlin 2.0 |
| UI | Jetpack Compose + Material 3 |
| Architecture | MVVM + Clean Architecture + Coroutines + Flow |
| Database | Room |
| Networking | OkHttp + Retrofit |
| Serialization | kotlinx.serialization |
| Security | EncryptedSharedPreferences + Android KeyStore |
| DI | Koin |
| JS Engine | QuickJS |
| Git Engine | JGit |
| Embeddings | ONNX Runtime (MiniLM-L6-v2) |
| Testing | JUnit 5, MockK, Roborazzi |

## Documentation

- [Feature Overview](docs/wiki/Features.md) -- All features with PRD/RFC links
- [Architecture Overview](docs/wiki/Architecture.md) -- Clean Architecture layers and data flow
- [Built-in Tool Reference](docs/wiki/Tool-Reference.md) -- All 39+ tools with parameters
- [Custom Skills](docs/wiki/Skill-System.md) -- Create and manage prompt templates
- [Memory System](docs/wiki/Memory-System.md) -- Persistent memory and hybrid search
- [Messaging Bridge](docs/wiki/Bridge-Module.md) -- Multi-channel messaging setup
- [AI Provider Integration](docs/wiki/API-Adapters.md) -- OpenAI, Anthropic, and Gemini
- [Getting Started](docs/wiki/Getting-Started.md) -- Developer setup guide
- [Project Structure](docs/wiki/Project-Structure.md) -- Annotated directory layout

## License

All rights reserved.
