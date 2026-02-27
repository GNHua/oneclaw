# Project Instructions

## Documentation

- When creating or updating documentation files, always produce both an English version and a Chinese version.
- The Chinese version uses a `-zh` suffix before the file extension. For example:
  - English: `00-overview.md`
  - Chinese: `00-overview-zh.md`
- Both versions must be kept in sync. When one is updated, the other must be updated as well.

## Communication

- Communicate with the user in Chinese (中文).

## Current Implementation Status

### Git Log
- `6d505ac` — init commit (all documentation, bilingual EN+ZH)
- `02e44d7` — Phase 1: Project foundation (Android project, domain models, Room DB, repositories, DI, navigation, tests)

### Phase 1: COMPLETE
All Phase 1 tasks are done and committed. The app builds cleanly (`./gradlew assembleDebug` passes, zero warnings).

**What was built:**
- Gradle project: Kotlin 2.0.21, Compose BOM 2024.12, Room 2.6.1, Koin 3.5.6, OkHttp 4.12
- Domain models: `Agent`, `AiModel`, `Message`, `Provider`, `Session`, `ToolDefinition`, `ToolResult`, `ResolvedModel`, `AppResult`, `ConnectionTestResult`
- Room: 6 entities, 6 DAOs, `AppDatabase` with seed callback (3 providers + General Assistant agent), `Converters`
- Repository interfaces (5) + implementations (5)
- API adapter stubs: `OpenAiAdapter`, `AnthropicAdapter`, `GeminiAdapter` (all methods currently `TODO` / return "Not implemented")
- `ApiKeyStorage` (EncryptedSharedPreferences with `MasterKey.Builder`)
- Koin DI: 6 modules (App, Database, Network, Repository, Tool, Feature)
- Navigation: `NavGraph` with placeholder screens, `Routes` sealed class
- Theme: Material 3, gold/amber accent (#6D5E0F)
- JVM unit tests: 57 tests, all pass (`./gradlew testDebugUnitTest`)
- Instrumented DAO tests: 47 tests written + compiled, **pending emulator run** (`./gradlew connectedAndroidTest`)

### Phase 2: NOT STARTED — next task
**RFC:** RFC-003 Provider Management (FEAT-003)

**What needs to be built (in order):**

#### 2.1 — Add `ConnectionErrorType` enum to `core/model/ConnectionTestResult.kt`
Currently `ConnectionTestResult` has no `errorType` field. RFC-003 defines:
```kotlin
enum class ConnectionErrorType { AUTH_FAILURE, NETWORK_FAILURE, TIMEOUT, UNKNOWN }
// Add field: val errorType: ConnectionErrorType? to ConnectionTestResult
```

#### 2.2 — Create API response DTOs
New package: `data/remote/dto/`
- `data/remote/dto/openai/OpenAiModelListResponse.kt` — `OpenAiModelListResponse`, `OpenAiModelDto`
- `data/remote/dto/anthropic/AnthropicModelListResponse.kt` — `AnthropicModelListResponse`, `AnthropicModelDto`
- `data/remote/dto/gemini/GeminiModelListResponse.kt` — `GeminiModelListResponse`, `GeminiModelDto`

All use `@Serializable` (kotlinx.serialization).

#### 2.3 — Implement adapter `listModels()` + `testConnection()`
Replace stubs in `data/remote/adapter/`:
- `OpenAiAdapter`: GET `{base}/models`, header `Authorization: Bearer {key}`, filter with `isRelevantOpenAiModel()` (keep `gpt-`, `o1`, `o3`, `o4`, `chatgpt-` prefixes), format name with `formatOpenAiModelName()`
- `AnthropicAdapter`: GET `{base}/models`, headers `x-api-key: {key}` + `anthropic-version: 2023-06-01`, filter `type == "model"`
- `GeminiAdapter`: GET `{base}/models?key={key}` (key in query param, not header), filter `"generateContent" in supportedGenerationMethods`, strip `"models/"` prefix from id
- Error handling: 401/403 → `AUTH_ERROR`, `UnknownHostException` → `NETWORK_ERROR`, `SocketTimeoutException` → `TIMEOUT_ERROR`
- `testConnection()` delegates to `listModels()`, wraps result in `ConnectionTestResult`
- `sendMessageStream()` should throw `NotImplementedError("implemented in RFC-001")` (NOT return stub flow)

**IMPORTANT base URL corrections vs Phase 1 seed data:**
- Anthropic: `https://api.anthropic.com` (no `/v1` suffix — the adapter appends `/models` directly)
- Gemini: `https://generativelanguage.googleapis.com/v1beta` (RFC-003 uses `/v1beta`)

#### 2.4 — Update `AppDatabase` seed callback
Current seed has wrong Anthropic URL and missing preset models. Fix:
- Anthropic base URL: `https://api.anthropic.com` (current code has this correct already)
- Add preset models INSERT statements for all 8 models:
  - OpenAI: `gpt-4o`, `gpt-4o-mini`, `o1`, `o3-mini`
  - Anthropic: `claude-sonnet-4-20250514`, `claude-haiku-4-20250414`
  - Gemini: `gemini-2.0-flash`, `gemini-2.5-pro`
- Also seed the General Assistant agent (already done in Phase 1)

#### 2.5 — Update `ProviderRepositoryImpl`
Current impl is basic. RFC-003 has more complete version with:
- `deleteProvider()` checks if deleted provider has the global default model (block if so)
- `deleteProvider()` uses `providerDao.deleteCustomProvider(id)` which only deletes non-pre-configured providers (returns affected rows count)
- `addManualModel()` checks for duplicate before inserting
- `deleteManualModel()` checks model exists, is MANUAL source, is not default
- `fetchModelsFromApi()` sets `providerId` on returned models: `result.data.map { it.copy(providerId = providerId) }`

#### 2.6 — Create Use Cases
New package: `feature/provider/usecase/`
- `TestConnectionUseCase(providerRepository)` — delegates to `providerRepository.testConnection()`
- `FetchModelsUseCase(providerRepository)` — fetches, falls back to existing if fetch fails + models exist
- `SetDefaultModelUseCase(providerRepository)` — validates model + provider exist and provider is active

#### 2.7 — Create UI State classes
New file: `feature/provider/ProviderUiState.kt`
- `ProviderListUiState(providers: List<ProviderListItem>, isLoading: Boolean)`
- `ProviderListItem(id, name, type, modelCount, isActive, isPreConfigured, hasApiKey, connectionStatus)`
- `ConnectionStatus` enum: `CONNECTED`, `NOT_CONFIGURED`, `DISCONNECTED`
- `ProviderDetailUiState(provider, models, globalDefaultModelId, globalDefaultProviderId, apiKeyMasked, apiKeyVisible, apiKeyFull, isEditingApiKey, apiKeyInput, isTestingConnection, connectionTestResult, isRefreshingModels, isActive, isPreConfigured, showAddModelDialog, manualModelIdInput, isLoading, errorMessage, successMessage)`
- `SetupUiState(step, selectedProviderType, selectedProviderId, apiKeyInput, isTestingConnection, connectionTestResult, models, selectedDefaultModelId, errorMessage)`
- `SetupStep` enum: `CHOOSE_PROVIDER`, `ENTER_API_KEY`, `SELECT_MODEL`

#### 2.8 — Create ViewModels
New package: `feature/provider/`
- `ProviderListViewModel(providerRepository, apiKeyStorage)` — collects `getAllProviders()` flow, builds `ProviderListItem` list
- `ProviderDetailViewModel(providerRepository, apiKeyStorage, testConnectionUseCase, fetchModelsUseCase, setDefaultModelUseCase, savedStateHandle)` — reads `providerId` from `savedStateHandle["providerId"]`; full actions: `saveApiKey`, `toggleApiKeyVisibility`, `testConnection`, `refreshModels`, `setDefaultModel`, `toggleProviderActive`, `addManualModel`, `deleteManualModel`, `deleteProvider`, `clearError`, `clearSuccess`, `maskApiKey`
- `SetupViewModel(providerRepository, apiKeyStorage, testConnectionUseCase, fetchModelsUseCase, setDefaultModelUseCase, settingsRepository)` — manages 3-step flow, marks `has_completed_setup = true` on finish or skip

#### 2.9 — Create UI Screens
New package: `feature/provider/`
- `ProviderListScreen.kt` — `Scaffold` + `LazyColumn`, pre-configured section + custom section, each item shows name, status chip (Not configured / Connected / Disconnected), model count
- `ProviderDetailScreen.kt` — sections: API Key (input + eye toggle + save + format warning), Test Connection (button + result card), Available Models (list with source label + star for default + delete for MANUAL), Refresh Models button, Add Manual Model button, Active toggle, Delete Provider button (custom only)
- `SetupScreen.kt` — 3-step pager/flow: Step 1 choose provider type, Step 2 enter key + test, Step 3 select default model + "Get Started"; "Skip for now" always visible
- `SettingsScreen.kt` — basic: list item "Manage Providers [>]" → navigates to ProviderList, list item "Default Model [>]" → shows current default, tapping opens model picker

#### 2.10 — Update NavGraph + first-launch detection
- `NavGraph.kt`: replace `PlaceholderScreen` for `Route.ProviderList`, `Route.ProviderDetail`, `Route.Setup`, `Route.Settings` with real screens
- First-launch: on `NavGraph` init, read `settingsRepository.getBoolean("has_completed_setup", false)`. If false → `startDestination = Route.Setup.path`. If true → `startDestination = Route.Chat.path`
- `Route.Settings` needs to be added if not already present

#### 2.11 — Update Koin DI
- `FeatureModule.kt`: add `factory { TestConnectionUseCase(get()) }`, `factory { FetchModelsUseCase(get()) }`, `factory { SetDefaultModelUseCase(get()) }`, `viewModel { ProviderListViewModel(get(), get()) }`, `viewModel { parameters -> ProviderDetailViewModel(get(), get(), get(), get(), get(), get(parameters)) }`, `viewModel { SetupViewModel(get(), get(), get(), get(), get(), get()) }`

#### 2.12 — Write unit tests for Phase 2
Per testing strategy `docs/testing/strategy.md`:
- `TestConnectionUseCaseTest` — success, no API key, network failure
- `FetchModelsUseCaseTest` — fetch + save, failure with existing models (fallback), failure with no models
- `SetDefaultModelUseCaseTest` — success, model not found, inactive provider
- `ProviderDetailViewModelTest` — state updates for all actions
- `ProviderListViewModelTest` — loads sessions from repository

### Key Technical Decisions (do not change without discussion)
- `ProviderType`: only `OPENAI`, `ANTHROPIC`, `GEMINI` — no `CUSTOM`
- API keys: `EncryptedSharedPreferences` only, never in Room
- `ModelApiAdapter.sendMessageStream()` deferred to RFC-001 (Phase 6) — throw `NotImplementedError`
- Koin (not Hilt), Kotlinx Serialization (not Gson)
- `AppResult<T>` for all fallible operations
- UI style: Material 3, gold/amber accent `#6D5E0F`, Google Gemini app style

### Project Location
`/Users/huagong/Developer/oneclaw/oneclaw-shadow-1`

### Test Commands
```bash
# Build
./gradlew assembleDebug

# JVM unit tests (57 tests, all pass)
./gradlew testDebugUnitTest

# Instrumented tests (need emulator)
./gradlew connectedAndroidTest

# Compile check for androidTest
./gradlew compileDebugAndroidTestKotlin
```
