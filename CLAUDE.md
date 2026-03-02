# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

OneClawShadow is an Android app serving as a mobile AI Agent runtime, built with a documentation-driven development approach. Documentation (PRD, RFC) is the single source of truth -- code is generated from RFCs and can be fully regenerated at any time.

## Build and Test Commands

```bash
# Build
./gradlew assembleDebug

# Layer 1A: JVM unit tests (JUnit 5 with vintage engine for JUnit 4 compat)
./gradlew test

# Layer 1B: Instrumented tests (requires emulator-5554)
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest

# Layer 1C: Roborazzi screenshot tests
./gradlew recordRoborazziDebug     # record baselines
./gradlew verifyRoborazziDebug     # verify against baselines

# Compile checks only
./gradlew compileDebugUnitTestKotlin
./gradlew compileDebugAndroidTestKotlin

# Run a single test class
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest"

# Run a single test method
./gradlew test --tests "com.oneclaw.shadow.data.repository.ProviderRepositoryImplTest.someTestMethod"
```

## Architecture

Two Gradle modules: `:app` (package `com.oneclaw.shadow`) and `:bridge` (Android library). Clean Architecture layers inside `:app`:

- **`core/`** -- Domain models (`model/`), repository interfaces (`repository/`), `AppResult<T>` sealed class (`util/`)
- **`data/`** -- Room DB (`local/entity/`, `local/dao/`, `local/db/`, `local/mapper/`), API adapters (`remote/adapter/`), provider-specific DTOs (`remote/dto/{openai,anthropic,gemini}/`), SSE handling (`remote/sse/`), repository implementations (`repository/`), encrypted key storage (`security/`), file storage (`storage/`), data sync (`sync/`)
- **`feature/`** -- Features organized by domain, each with screens, ViewModels, UI state, and `usecase/` subdirectory:
  `agent/`, `bridge/`, `chat/`, `file/`, `memory/`, `provider/`, `schedule/`, `search/`, `session/`, `settings/`, `skill/`, `tool/`, `usage/`
- **`tool/`** -- AI tool system: `Tool` interface, `ToolRegistry`, `ToolExecutionEngine`, `PermissionChecker`; 39 builtin tools in `tool/builtin/` (including `config/` subdirectory); JS engine integration (`tool/js/`) via QuickJS; skills loader (`tool/skill/`)
- **`di/`** -- Eight Koin modules: `appModule`, `bridgeModule`, `databaseModule`, `featureModule`, `memoryModule`, `networkModule`, `repositoryModule`, `toolModule`
- **`navigation/`** -- Compose Navigation with sealed `Route` class
- **`ui/theme/`** -- Material 3 theme with gold/amber accent `#6D5E0F`

### `:bridge` Module

Standalone Android library for multi-channel messaging. Channels: Telegram, Discord, Line, Matrix (placeholder), Slack (placeholder), WebChat (placeholder). Key components:
- `BridgeConversationManager` -- conversation lifecycle and session routing
- `BridgeStateTracker` -- singleton SharedFlow in-process event bus for bridge→app session sync events (RFC-045)
- `MessagingBridgeService` -- foreground service
- `BridgeImageStorage` -- image caching and delivery
- `TelegramHtmlRenderer` -- AST visitor for Telegram HTML rendering

App integrates bridge via `feature/bridge/BridgeConversationManagerImpl`.

### Key Technical Decisions (do not change without discussion)

- **DI**: Koin (not Hilt)
- **Serialization**: Kotlinx Serialization (not Gson)
- **Error handling**: `AppResult<T>` sealed class for all fallible operations
- **ProviderType**: only `OPENAI`, `ANTHROPIC`, `GEMINI` -- no `CUSTOM`
- **API keys**: `EncryptedSharedPreferences` only, never stored in Room
- **UI style**: Material 3, Google Gemini app style
- **JS engine**: QuickJS for JS tool execution; skills are JS bundles in `assets/skills/`

### Data Flow

Repository interfaces live in `core/repository/`. Implementations in `data/repository/` use Room DAOs + API adapters. Use cases in `feature/*/usecase/` orchestrate business logic. ViewModels expose UI state as StateFlow. Screens are Composable functions.

### API Adapter Pattern

`ModelApiAdapter` interface in `data/remote/adapter/` with three implementations: `OpenAiAdapter`, `AnthropicAdapter`, `GeminiAdapter`. `ModelApiAdapterFactory` creates the right adapter by `ProviderType`.

## Documentation Rules

- All docs must be bilingual: English (`filename.md`) and Chinese (`filename-zh.md`), kept in sync
- Every feature follows: PRD first, then RFC, then code
- ID system: `FEAT-XXX` (PRD), `RFC-XXX` (design), `ADR-XXX` (decisions), `TEST-XXX` (scenarios)
- Writing workflow: write English doc first, then spawn a subagent (model: `claude-sonnet-4-6`) to translate it into Chinese

## Testing Protocol (After Every RFC Implementation)

Must run in order after completing an RFC:
1. **Layer 1A** -- `./gradlew test` (all must pass)
2. **Layer 1B** -- `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest` (skip if no emulator)
3. **Layer 1C** -- Roborazzi screenshot tests for new/modified Composables (skip if no UI changes)
4. **Layer 2** -- adb visual verification flows from `docs/testing/strategy.md` (skip if no device/keys)
5. **Write test report** -- `docs/testing/reports/RFC-XXX-<name>-report.md` (EN + ZH)
6. **Update manual test guide** -- `docs/testing/manual-test-guide.md` (EN + ZH)

## Communication

Communicate with the user in Chinese.
