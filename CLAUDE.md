# Project Instructions

## Documentation

- When creating or updating documentation files, always produce both an English version and a Chinese version.
- The Chinese version uses a `-zh` suffix before the file extension. For example:
  - English: `00-overview.md`
  - Chinese: `00-overview-zh.md`
- Both versions must be kept in sync. When one is updated, the other must be updated as well.

## Communication

- Communicate with the user in Chinese (中文).

## Testing Rules (After Every RFC Implementation)

After completing all implementation tasks for an RFC, the following testing steps must be executed in order. Do not skip any step that is feasible given the current state of the app.

### Step 1: Layer 1A — JVM Unit Tests
```bash
./gradlew test
```
All tests must pass before proceeding. Fix any failures first.

### Step 2: Layer 1B — Instrumented DAO + UI Tests
```bash
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest
```
Requires emulator running. If emulator is not available, note it in the test report and skip.

### Step 3: Layer 1C — Roborazzi Screenshot Tests
- If new Composable screens were added or modified in this RFC, add or update screenshot tests in `app/src/test/kotlin/com/oneclaw/shadow/screenshot/`.
- Run: `./gradlew recordRoborazziDebug` to record baselines (first time for new screens).
- Read each generated PNG file and verify the UI looks correct visually.
- Run: `./gradlew verifyRoborazziDebug` to confirm verification mode passes.
- If no UI was added or changed in this RFC, this step can be skipped (note it in the report).

### Step 4: Layer 2 — adb Visual Verification
- Execute the flows from `docs/testing/strategy.md` that are applicable given the current implementation state.
- Requires: emulator running + API keys set as env vars (`ONECLAW_ANTHROPIC_API_KEY`, etc.).
- If API keys are not set or the relevant features (e.g., chat) are not yet implemented, note it in the report and skip.
- Save screenshots to `screenshots/layer2/` with descriptive names.

### Step 5: Write Test Report
After completing all feasible testing steps, write a test report to:
```
docs/testing/reports/RFC-XXX-<rfc-name>-report.md
docs/testing/reports/RFC-XXX-<rfc-name>-report-zh.md
```
Use the template at `docs/testing/reports/_template.md`. The report must:
- List each test step with PASS / SKIP (with reason) / FAIL (with details)
- Include links to any Roborazzi screenshots (relative paths)
- Include inline screenshots for Layer 2 adb results if taken
- Record test counts (e.g., "181 unit tests, 47 DAO tests")

### Step 6: Update Manual Test Guide
After writing the report, update `docs/testing/manual-test-guide.md` (and `-zh.md`) to reflect the current cumulative state of the app:
- Add new user flows introduced by this RFC
- Update or remove flows that changed due to this RFC
- Keep the guide accurate as a complete description of how to manually test the current app from a fresh install

## Current Implementation Status

### Git Log
- `6d505ac` — init commit (all documentation, bilingual EN+ZH)
- `02e44d7` — Phase 1: Project foundation (Android project, domain models, Room DB, repositories, DI, navigation, tests)
- `70a350f` + `10ebbbd` — Phase 2: RFC-003 Provider Management (all implementation + tests)
- `ca5e281` — Phase 3: RFC-004 Tool System (all implementation + 181 unit tests)
- `4dbe9c3` — Phase 3 Layer 1 testing: Roborazzi screenshot tests, ProviderDaoTest fix, junit-vintage-engine

### Phase 1: COMPLETE (commit 02e44d7)
- Gradle project, domain models, Room DB (6 entities/DAOs), repositories (5), API adapter stubs
- ApiKeyStorage, Koin DI (6 modules), Navigation, Material 3 theme
- 57 JVM unit tests passing

### Phase 2: COMPLETE (commits 70a350f, 10ebbbd) — RFC-003 Provider Management
- ConnectionErrorType enum, API response DTOs (OpenAI/Anthropic/Gemini)
- Adapter listModels() + testConnection() implemented for all 3 providers
- AppDatabase seed: 8 preset models added
- ProviderRepositoryImpl updated with delete guards, duplicate checks
- Use cases: TestConnectionUseCase, FetchModelsUseCase, SetDefaultModelUseCase
- UI: ProviderListScreen, ProviderDetailScreen, SetupScreen, SettingsScreen
- ViewModels: ProviderListViewModel, ProviderDetailViewModel, SetupViewModel
- NavGraph wired; first-launch detection
- Unit tests: all passing

### Phase 3: COMPLETE (commits ca5e281, 4dbe9c3) — RFC-004 Tool System + Layer 1 testing
- Tool interface, ToolRegistry, ToolSchemaSerializer, ToolCallModels
- PermissionChecker with Activity lifecycle
- ToolExecutionEngine (lookup, availability, validation, permissions, timeout, parallel)
- Built-in tools: GetCurrentTimeTool, ReadFileTool, WriteFileTool, HttpRequestTool
- formatToolDefinitions() in all 3 adapters
- ToolModule Koin DI, MainActivity wired
- 181 JVM unit tests passing
- Layer 1B: 47 DAO instrumented tests passing (emulator-5554)
- Layer 1C: Roborazzi screenshot tests — 5 baselines recorded and verified
  - SettingsScreen, ProviderListScreen (loading/populated/empty/dark)
- Test report: `docs/testing/reports/phase1-3-report.md`

### Next Phase: Discuss with user before starting.

### Key Technical Decisions (do not change without discussion)
- `ProviderType`: only `OPENAI`, `ANTHROPIC`, `GEMINI` — no `CUSTOM`
- API keys: `EncryptedSharedPreferences` only, never in Room
- `ModelApiAdapter.sendMessageStream()` deferred to RFC-001 (Phase 6) — throw `NotImplementedError`
- Koin (not Hilt), Kotlinx Serialization (not Gson)
- `AppResult<T>` for all fallible operations
- UI style: Material 3, gold/amber accent `#6D5E0F`, Google Gemini app style

### Test Commands
```bash
# Build
./gradlew assembleDebug

# Layer 1A: JVM unit tests
./gradlew test

# Layer 1B: Instrumented tests (requires emulator-5554)
ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest

# Layer 1C: Roborazzi screenshot tests
./gradlew recordRoborazziDebug     # record baselines
./gradlew verifyRoborazziDebug     # verify against baselines

# Compile checks
./gradlew compileDebugUnitTestKotlin
./gradlew compileDebugAndroidTestKotlin
```
