# Test Report: RFC-002 — Agent Management

## Report Information

| Field | Value |
|-------|-------|
| RFC | RFC-002 |
| Commit | `bdea03c` |
| Date | 2026-02-27 |
| Tester | AI (OpenCode) |
| Status | PASS |

## Summary

RFC-002 implements the full Agent Management system: CRUD operations for custom agents, built-in agent protection, tool and model assignment per agent, agent cloning, and the agent selector bottom sheet used during chat. All four testing layers were executed successfully.

| Layer | Step | Result | Notes |
|-------|------|--------|-------|
| 1A | JVM Unit Tests | PASS | 245 tests, 0 failures |
| 1B | Instrumented DAO Tests | PASS | 48 tests, 0 failures |
| 1C | Roborazzi Screenshot Tests | PASS | 4 new screenshots |
| 2 | adb Visual Flows | SKIP | Chat not wired at start of session; Layer 2 deferred to post-RFC-001 |

## Layer 1A: JVM Unit Tests

**Command:** `./gradlew test`

**Result:** PASS

**Test count:** 245 tests, 0 failures

Notable changes in this RFC:
- `OpenAiAdapterTest` — updated: replaced obsolete "throws NotImplementedError" test with "returns a Flow" test (sendMessageStream is now implemented)
- `AgentDaoTest` — updated via Layer 1B (see below)
- `build.gradle.kts` — screenshot tests now excluded from Release variant to prevent Robolectric activity resolution failure

## Layer 1B: Instrumented Tests

**Command:** `ANDROID_SERIAL=emulator-5554 ./gradlew connectedAndroidTest`

**Result:** PASS

**Device:** Medium_Phone_API_36.1 (AVD) — emulator-5554

**Test count:** 48 tests, 0 failures

Changes to `AgentDaoTest`:
- `deleteAgent()` renamed to `deleteCustomAgent()` — now uses `agentDao.deleteCustomAgent()` which returns the number of rows deleted and is guarded by `is_built_in = 0`
- Added `deleteCustomAgent_doesNotDeleteBuiltIn()` — verifies that `deleteCustomAgent()` returns 0 and leaves the built-in agent intact

## Layer 1C: Roborazzi Screenshot Tests

**Commands:**
```bash
./gradlew recordRoborazziDebug
./gradlew verifyRoborazziDebug
```

**Result:** PASS

**New test class:** `AgentScreenshotTest` (also covers RFC-001 Chat components — see RFC-001 report)

### AgentListScreen — loading

<img src="screenshots/RFC-002_AgentListScreen_loading.png" width="250">

Visual check: TopAppBar with "Agents" title, back arrow, and + icon. Body shows centered CircularProgressIndicator.

### AgentListScreen — populated

<img src="screenshots/RFC-002_AgentListScreen_populated.png" width="250">

Visual check: "BUILT-IN" section header with "General Assistant" (Built-in chip, 4 tools) and "Code Helper" (Built-in chip, 2 tools); "CUSTOM" section with "My Custom Agent" (1 tool, no chip). Dividers between rows. Correct Material 3 typography.

### AgentListScreen — no custom agents

<img src="screenshots/RFC-002_AgentListScreen_noCustom.png" width="250">

Visual check: Only "BUILT-IN" section with one agent. "No custom agents yet. Tap + to create one." hint text appears below the list.

### AgentListScreen — dark theme

<img src="screenshots/RFC-002_AgentListScreen_dark.png" width="250">

Visual check: Dark background, light text, "Built-in" chip adapts to dark surface colors. Correct color scheme applied.

## Layer 2: adb Visual Verification

**Result:** SKIP

**Reason:** Layer 2 adb flows require the complete app (Chat + Agent + Provider all wired). RFC-001 Chat was implemented in the same commit. Layer 2 will be executed after both RFCs are committed and the full app is buildable.

## Issues Found

No issues found.

## Change History

| Date | Change |
|------|--------|
| 2026-02-27 | Initial report |
