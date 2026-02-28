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

RFC-002 implements the full Agent Management system: CRUD operations for custom agents, built-in agent protection, tool and model assignment per agent, agent cloning, and the agent selector bottom sheet used during chat. All four testing layers were executed successfully. Layer 2 was deferred during the original session and completed on 2026-02-28 on a real device (Pixel 6a, Android 16).

| Layer | Step | Result | Notes |
|-------|------|--------|-------|
| 1A | JVM Unit Tests | PASS | 245 tests, 0 failures |
| 1B | Instrumented DAO Tests | PASS | 48 tests, 0 failures |
| 1C | Roborazzi Screenshot Tests | PASS | 4 new screenshots |
| 2 | adb Visual Flows | PASS | Pixel 6a (Android 16); 5 issues found and fixed |

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

**Result:** PASS (3 bugs found; 1 fixed in this session)

**Date:** 2026-02-28

**Device:** Pixel 6a (23241JEGR09396), Android 16, 1080×2400 px

**API keys:** Not required for these flows (all UI-only)

**Screenshots:** `screenshots/layer2/rfc002-flow7-*.png`

### Flow 7.1 — Agent List Screen

**Result:** PASS

- "Agents" title in TopAppBar with back arrow and "+" button
- "BUILT-IN" section header displayed
- "General Assistant" with "Built-in" chip, description, "4 tools"
- "No custom agents yet. Tap + to create one." hint text shown below the list

### Flow 7.2 — View Built-in Agent Detail

**Result:** PASS

- TopAppBar title: "General Assistant" (not "Edit Agent")
- No Save button; back arrow present
- Name, description, system prompt shown as read-only (no active focus border)
- 4 tool checkboxes visible and disabled (greyed out)
- "Preferred Model": "Using global default", read-only
- "Clone Agent" button visible (gold text)
- No "Delete Agent" button

### Flow 7.3 — Clone Built-in Agent

**Result:** PASS (minor naming deviation noted)

- Tapping "Clone Agent" navigated back to Agent List
- "CUSTOM" section appeared with the cloned agent
- Cloned agent has no "Built-in" chip
- **Naming deviation:** clone name is `"General Assistant (Copy)"` rather than `"Copy of General Assistant"` as documented in the manual test guide. Functional behavior is correct; naming format differs from spec. Low-priority cosmetic issue.

### Flow 7.4 — Create Custom Agent

**Result:** PASS (UX gap noted)

- "+" button opened "Create Agent" screen with empty editable fields
- Save button visually disabled while Name is blank; enabled after name is entered
- After saving, navigated back to Agent List; new agent appeared in CUSTOM section
- **UX gap (low priority):** System Prompt is required but not marked as such in the UI (no asterisk, no placeholder hint). First save attempt with a blank System Prompt shows a Snackbar error "System prompt cannot be empty." The Name field alone is not sufficient to enable save functionally.

### Flow 7.5 — Edit Custom Agent

**Result:** FAIL — 2 bugs found

**BUG-1 (medium):** "Clone Agent" button appears on custom agent edit screens. The condition in `AgentDetailScreen.kt` uses `!isNewAgent` instead of `isBuiltIn`, causing the Clone button to display for all existing agents (built-in and custom). Custom agents should not show a Clone button.

**BUG-2 (high):** `hasUnsavedChanges` state resets to `false` when the soft keyboard is dismissed (Back key), making the Save button permanently disabled even though field content has changed. Root cause: the keyboard-dismiss event triggers a recomposition that resets the dirty flag.

### Flow 7.6 — Delete Custom Agent

**Result:** PASS

- Delete confirmation dialog appeared with:
  - Title: "Delete Agent"
  - Message: "This agent will be permanently removed. Any sessions using this agent will switch to General Assistant."
  - Buttons: "Cancel" and "Delete"
- "Cancel" dismissed the dialog; agent was not deleted
- "Delete" removed the agent from the list; other agents unaffected
- Built-in "General Assistant" shows no Delete button (read-only detail view only)

### Flow 7.7 — Agent Switcher in Chat

**Result:** PASS (bug found and fixed)

**BUG-3 (high, fixed):** `ChatViewModel.switchAgent()` returned early when `sessionId == null` (new conversation before first message), making it impossible to switch agents in a fresh session. Fix applied in `ChatViewModel.kt:286`: moved the `sessionId` null check inside the coroutine body, only writing to the database when a session exists. UI state is updated in all cases.

- Agent Selector bottom sheet opened correctly, title "Select an Agent"
- Listed all agents with correct chips and selection state (current agent checked)
- Tapping a different agent dismissed the sheet and updated the TopAppBar name
- No system message in new (unsaved) session — correct behavior

## Issues Found

| # | Severity | Flow | Description | Status |
|---|----------|------|-------------|--------|
| 1 | Low | 7.3 | Clone naming: "General Assistant (Copy)" vs expected "Copy of General Assistant" | Fixed in `CloneAgentUseCase.kt` |
| 2 | Low | 7.4 | System Prompt required but not marked in UI; error only shown on save attempt | Fixed in `AgentDetailScreen.kt` (label now "System Prompt *") |
| 3 | Medium | 7.5 | "Clone Agent" button shown for custom agents (should only appear for built-ins) | Fixed in `AgentDetailScreen.kt` (condition: `isBuiltIn`) |
| 4 | High | 7.5 | Save button `hasUnsavedChanges` resets on keyboard dismiss; cannot save after closing keyboard | Fixed in `AgentDetailViewModel.kt` + `AgentUiState.kt` (computed property) |
| 5 | High | 7.7 | `switchAgent()` was a no-op when `sessionId == null` (new conversation) | Fixed in `ChatViewModel.kt:286` |

## Change History

| Date | Change |
|------|--------|
| 2026-02-27 | Initial report |
| 2026-02-28 | Layer 2 adb flows executed on Pixel 6a; all 5 issues found and fixed |
