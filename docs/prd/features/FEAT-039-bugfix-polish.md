# Bug Fix & UI Polish Pass

## Feature Information
- **Feature ID**: FEAT-039
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: RFC-039

## User Story

**As** a OneClaw user,
**I want** the app to be stable, polished, and free of the issues I reported during testing,
**so that** I can reliably use Google OAuth, manage agents, browse files, search history, send attachments, and manage scheduled tasks without friction.

### Typical Scenarios

1. User tries to sign in with Google OAuth but the flow fails silently or crashes.
2. User switches to a new conversation and expects daily logs to be flushed for future search.
3. User searches past conversations via `search_history` tool but sees the current message in results.
4. User opens the file browser and sees an empty directory instead of their files.
5. User wants to adjust temperature/max iterations on a built-in agent but the controls are missing or disabled.
6. User wants to attach a file to a chat message but there is no attachment button.
7. User clicks a tool call in chat and wants to see its parameters.
8. User expects the tool management screen to auto-refresh when tools are registered/unregistered.
9. User finds it hard to navigate 30+ built-in tools without grouping.
10. User cannot tell that scheduled task rows are clickable.

## Feature Description

### Overview

FEAT-039 is a comprehensive bug fix and UI polish pass addressing 15 issues reported during user testing. Changes span 7 feature areas: Google OAuth, chat, agent detail, tool management, bridge settings, file browser, search history, and scheduled tasks.

### Issue Inventory

| # | Area | Summary | Type |
|---|------|---------|------|
| 1 | Chat | Daily log not flushed on session switch to "New Conversation" | Bug |
| 2 | Bridge | No explanation for Wake Lock toggle | UX |
| 3 | Google Auth | OAuth flow fails (6 sub-issues) | Bug |
| 4 | Google Auth | Auth screen lacks instructions and status | UX |
| 5 | Agent | Built-in agents cannot toggle Web Search at runtime | Bug |
| 6 | Agent | No UI for temperature and max iterations | Missing Feature |
| 7 | Chat | No attachment button / picker integration | Missing Feature |
| 8 | Chat | Tool call cards do not show parameters | UX |
| 9 | Tool Mgmt | Tool list does not auto-refresh on registry changes | Bug |
| 10 | Tool Mgmt | Built-in tools are a flat list with no categorization | UX |
| 11 | Bridge | Channel sections not visually separated | UX |
| 12 | File Browser | File browser shows empty directory | Bug |
| 13 | Search History | `search_history` returns current message in results | Bug |
| 14 | Schedule | Task rows not clickable, no edit affordance | UX |
| 15 | Schedule | Exact alarm permission skipped when editing a task | Bug |

## Acceptance Criteria

Must pass (all required):

### Bug Fixes
- [ ] Daily log flushes when switching from an active session to "New Conversation" (null sessionId)
- [ ] Google OAuth completes successfully on a real device with valid GCP credentials
- [ ] OAuth loopback server binds to 127.0.0.1 explicitly
- [ ] OAuth token exchange retries up to 3 times with 3-second intervals
- [ ] OAuth flow survives Activity recreation (NonCancellable)
- [ ] Browser intent launches without FLAG_ACTIVITY_NEW_TASK
- [ ] Specific error messages for UnknownHostException and SocketTimeoutException
- [ ] File browser shows all internal files (memory/, daily_logs/, etc.) instead of an empty `user_files/` directory
- [ ] `search_history` tool excludes messages from the last 5 seconds when `dateTo` is null
- [ ] Built-in agents can toggle Web Search, temperature, and max iterations at runtime
- [ ] Saving a built-in agent preserves its name, description, systemPrompt, and other immutable fields
- [ ] Exact alarm permission dialog appears for both new and edited scheduled tasks
- [ ] Tool management screen auto-refreshes when tools are registered or unregistered
- [ ] All Layer 1A tests pass (`./gradlew test`)

### UI/UX Improvements
- [ ] Wake Lock toggle has explanation text below it
- [ ] Google Auth screen shows connection status card (Connected/Not connected)
- [ ] Google Auth screen shows "unverified app" warning card
- [ ] Google Auth screen shows 9-step GCP setup instructions with numbered steps
- [ ] Google Auth screen lists all required Google APIs
- [ ] Google Auth screen has clickable console.cloud.google.com link
- [ ] Google Auth screen errors display in errorContainer-colored card
- [ ] Google Auth screen supports editing existing credentials
- [ ] Agent detail screen has Temperature slider (0.0-2.0) and Max Iterations text field (1-100)
- [ ] Chat input has attachment button next to skill button
- [ ] AttachmentPickerSheet and AttachmentPreviewRow are wired into ChatScreen
- [ ] Send button is enabled when there are pending attachments (even if text is empty)
- [ ] Tool call cards are expandable to show tool input parameters in monospace
- [ ] Tool input display truncates at 20 lines with ellipsis
- [ ] Built-in tools are grouped by category (Calendar, Config, Provider/Model, Agent, Scheduling, Files & Web, PDF, JS Tools, Other)
- [ ] Category groups are collapsible with expand/collapse icon
- [ ] Bridge settings channel sections are wrapped in rounded Surface cards
- [ ] HorizontalDivider between sections replaced with Spacer
- [ ] Scheduled task rows are clickable (navigate to edit screen)
- [ ] Scheduled task rows show ChevronRight icon as edit affordance

## Feature Boundary

### Included
- Bug fixes for 8 functional issues
- UI/UX improvements for 7 areas
- Test fixes (SearchHistoryUseCaseTest, ScheduledTaskEditViewModelTest)
- Build config fix (isReturnDefaultValues for unit tests)
- Network security config for OAuth cleartext traffic

### Not Included
- New features beyond what is listed
- Database schema changes
- New external dependencies
- ActivityResultLauncher wiring for attachment pickers (placeholder callbacks)
- Layer 2 manual verification

## Files Changed

| File | Change Type |
|------|------------|
| `app/build.gradle.kts` | Modified (isReturnDefaultValues) |
| `AndroidManifest.xml` | Modified (networkSecurityConfig) |
| `res/xml/network_security_config.xml` | New |
| `GoogleAuthManager.kt` | Modified (6 sub-fixes) |
| `GoogleAuthScreen.kt` | Rewritten |
| `GoogleAuthViewModel.kt` | Modified (editingCredentials, dirty) |
| `UserFileStorage.kt` | Modified (rootDir) |
| `AgentDetailScreen.kt` | Modified (runtime settings, behavior section) |
| `AgentDetailViewModel.kt` | Modified (built-in agent save logic) |
| `AgentUiState.kt` | Modified (hasRuntimeChanges) |
| `CreateAgentUseCase.kt` | Modified (new parameters) |
| `ChatScreen.kt` | Modified (attachment, tool card expand) |
| `ChatViewModel.kt` | Modified (flush condition) |
| `BridgeSettingsScreen.kt` | Modified (cards, wake lock text) |
| `ToolRegistry.kt` | Modified (version StateFlow) |
| `ToolManagementScreen.kt` | Modified (category grouping) |
| `ToolManagementViewModel.kt` | Modified (categorize, auto-refresh) |
| `ScheduledTaskListScreen.kt` | Modified (clickable, chevron) |
| `ScheduledTaskEditViewModel.kt` | Modified (alarm check) |
| `SearchHistoryUseCase.kt` | Modified (5s buffer, debug logs) |
| `ScheduledTaskEditViewModelTest.kt` | Modified (updated assertion) |
| `SearchHistoryUseCaseTest.kt` | Modified (any() matcher) |

## Dependencies

### Depends On
- FEAT-001 (Chat): ChatViewModel, ChatScreen
- FEAT-002 (Agent): AgentDetailScreen, AgentDetailViewModel
- FEAT-004 (Tool System): ToolRegistry
- FEAT-017 (Tool Management): ToolManagementScreen
- FEAT-019 (Scheduled Tasks): ScheduledTaskEditViewModel, ScheduledTaskListScreen
- FEAT-024 (Messaging Bridge): BridgeSettingsScreen
- FEAT-025 (File Browsing): UserFileStorage
- FEAT-026 (File Attachments): AttachmentPickerSheet, AttachmentPreviewRow
- FEAT-030 (Google Workspace): GoogleAuthManager, GoogleAuthScreen
- FEAT-032 (Search History): SearchHistoryUseCase

### Depended On By
- None

## Error Handling

### Google OAuth Errors
1. **UnknownHostException**: "Network unavailable. Check your internet connection and try again."
2. **SocketTimeoutException**: "Connection timed out. Check your internet connection and try again."
3. **No auth code received**: "No authorization code received (timed out or cancelled)"
4. **Token exchange failure after 3 retries**: Shows the last error message

### Search History
1. **Current message in results**: 5-second buffer automatically excludes very recent messages

## Test Points

### Unit Tests Modified
- `SearchHistoryUseCaseTest`: Updated `coVerify` assertions from `Long.MAX_VALUE` to `any()` to accommodate the 5-second buffer
- `ScheduledTaskEditViewModelTest`: Updated to expect alarm dialog for editing tasks (was previously asserting no dialog)

### Build Config
- `app/build.gradle.kts`: Added `isReturnDefaultValues = true` in `testOptions.unitTests` to prevent `android.util.Log` from throwing in JVM tests

## Round 2: Additional Issues (16-25)

### Issue Inventory (Round 2)

| # | Area | Summary | Type |
|---|------|---------|------|
| 16 | Bridge | Channel sections should be collapsible single-line headers | UX |
| 17 | Bridge | Add setup guide instructions per channel | UX |
| 18 | Bridge | Wake lock warning text needs visual emphasis | UX |
| 19 | Google Auth | SetupInstructions crash due to LinkAnnotation API instability | Bug |
| 20 | Google Auth | No way to delete stored OAuth credentials | Missing Feature |
| 21 | Agent | Max iterations should be a slider, not a text field | UX |
| 22 | Chat | Attachment picker callbacks are no-ops (not wired) | Bug |
| 23 | Chat | Attachment picker dark mode causes status bar icon color issue | Bug |
| 24 | Tool Mgmt | Category names should use full Google product names | UX |
| 25 | Tool Mgmt | Badge layout breaks with long tool names | Bug |

### Acceptance Criteria (Round 2)

- [ ] Bridge channels show as collapsible single-line headers (channel name + switch)
- [ ] Each channel has a collapsible setup guide with numbered steps
- [ ] Wake lock text has warning icon and error color
- [ ] Google OAuth screen does not crash on SetupInstructions
- [ ] Delete Credentials button available in signed-in and has-credentials states
- [ ] Agent max iterations uses a slider (1-200, 200 = Unlimited)
- [ ] Attachment pickers (photo, video, camera, file) launch real system pickers
- [ ] Attachment picker bottom sheet scrim does not affect dark mode status bar
- [ ] Tool categories use Google product names (Gmail, Google Calendar, etc.)
- [ ] Tool badges do not get squeezed by long tool names

### Files Changed (Round 2)

| File | Change Type |
|------|------------|
| `BridgeSettingsScreen.kt` | Modified (collapsible channels, setup guides, warning icon) |
| `GoogleAuthScreen.kt` | Modified (LinkAnnotation crash fix, delete button) |
| `GoogleAuthViewModel.kt` | Modified (deleteCredentials) |
| `GoogleAuthManager.kt` | Modified (clearAllCredentials) |
| `AgentDetailScreen.kt` | Modified (slider replaces text field) |
| `AgentDetailViewModel.kt` | Modified (remove validation) |
| `AgentUiState.kt` | Modified (remove maxIterationsError) |
| `SendMessageUseCase.kt` | Modified (null = Int.MAX_VALUE) |
| `ChatScreen.kt` | Modified (wire ActivityResultLaunchers) |
| `AttachmentPickerSheet.kt` | Modified (scrim color) |
| `ToolManagementViewModel.kt` | Modified (Google product names) |
| `ToolManagementScreen.kt` | Modified (badge layout fix) |
| `file_paths.xml` | Modified (camera_photos cache path) |

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial version (15 issues fixed) | - |
| 2026-03-01 | 2.0 | Round 2 (10 additional issues: 16-25) | - |
