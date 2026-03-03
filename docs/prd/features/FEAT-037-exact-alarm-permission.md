# Exact Alarm Permission Dialog

## Feature Information
- **Feature ID**: FEAT-037
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P0 (Blocker -- Play Store submission)
- **Owner**: TBD
- **Related RFC**: [RFC-037 (Exact Alarm Permission)](../../rfc/features/RFC-037-exact-alarm-permission.md)
- **Depends On**: [FEAT-019 (Scheduled Tasks)](FEAT-019-scheduled-tasks.md)

## User Story

**As** a user of OneClaw,
**I want** the app to guide me through granting the exact alarm permission,
**so that** scheduled tasks fire at the exact times I configured, and the app complies with Google Play policies.

### Typical Scenarios

1. User creates their first scheduled task on Android 13+. The app detects that exact alarm permission is not granted, shows a dialog explaining why it is needed, and offers a button to open system settings.
2. User taps "Go to Settings" in the dialog. The system exact alarm permission settings page opens. The user grants the permission and returns to the app. The scheduled task is created with the alarm registered.
3. User taps "Cancel" in the dialog. The task is still saved but the alarm is not registered. The app shows a warning that the task will not fire at the exact time.
4. User revokes the exact alarm permission from system settings while the app is running. The system broadcasts a permission state change. The app detects the revocation and disables exact alarm scheduling until the user re-grants the permission.
5. User has previously denied the permission. When editing or creating another scheduled task, the dialog appears again.
6. The device reboots. The `BootCompletedReceiver` checks `canScheduleExactAlarms()` before rescheduling alarms. If the permission is not granted, alarms are not registered.
7. The AI agent calls `schedule_task` from conversation. The tool checks the permission and returns an error message telling the user to grant the permission via app settings if it is not available.

## Feature Description

### Overview

Starting with Android 12 (API 31), exact alarm scheduling requires explicit user permission. Android 13+ (API 33) further tightened this by not granting `SCHEDULE_EXACT_ALARM` by default. Google Play policy restricts the `USE_EXACT_ALARM` permission to alarm clock, timer, and calendar apps. Since OneClaw is an AI Agent runtime, it must use `SCHEDULE_EXACT_ALARM` and guide users to grant it through system settings.

### Current Problem

The app currently declares both `SCHEDULE_EXACT_ALARM` (maxSdkVersion=32) and `USE_EXACT_ALARM` in the manifest. On Android 13+, it relies on `USE_EXACT_ALARM` which may cause Play Store rejection since the app is not an alarm clock app. Additionally, the code calls `setExactAndAllowWhileIdle()` without checking whether the permission is granted, which crashes on Android 12+ when the permission is denied.

### Solution

1. **Manifest**: Replace `USE_EXACT_ALARM` with `SCHEDULE_EXACT_ALARM` (no maxSdkVersion restriction).
2. **Runtime check**: Before any call to `setExactAndAllowWhileIdle()`, check `AlarmManager.canScheduleExactAlarms()`.
3. **UI dialog**: When the check fails in a UI context (task edit screen), show a Material 3 AlertDialog explaining why the permission is needed and offering to open system settings.
4. **Tool context**: When the check fails in a tool context (AI agent creating a task), return an error message instructing the user to grant the permission.
5. **Permission state receiver**: Listen for `ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED` to reschedule alarms when permission is granted.

### Permission Flow

```
User saves task / tool creates task
         |
         v
  canScheduleExactAlarms()?
     /           \
   YES            NO
    |              |
    v              v
 Register     UI context?
  alarm       /        \
            YES         NO (tool)
             |           |
             v           v
       Show dialog    Return error
             |        message
             v
    User taps "Settings"
             |
             v
    System settings page
             |
             v
    User grants permission
             |
             v
    ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED
             |
             v
    Reschedule all enabled tasks
```

## Acceptance Criteria

### TEST-037-01: Permission Check Before Scheduling
- **Given** the app runs on Android 12+ and the exact alarm permission is NOT granted
- **When** the user saves a new scheduled task from the edit screen
- **Then** the exact alarm permission dialog is shown instead of silently failing or crashing

### TEST-037-02: Dialog Content
- **Given** the exact alarm permission dialog is shown
- **When** the user reads the dialog
- **Then** it explains that the permission is needed for scheduled tasks to fire at exact times, and shows "Go to Settings" and "Cancel" buttons

### TEST-037-03: Go to Settings
- **Given** the exact alarm permission dialog is shown
- **When** the user taps "Go to Settings"
- **Then** the system settings page for exact alarm permission opens (`ACTION_REQUEST_SCHEDULE_EXACT_ALARM`)

### TEST-037-04: Permission Granted After Settings
- **Given** the user grants the permission in system settings and returns to the app
- **When** the permission state change broadcast is received
- **Then** all enabled scheduled tasks have their alarms re-registered

### TEST-037-05: Cancel Dialog
- **Given** the exact alarm permission dialog is shown
- **When** the user taps "Cancel"
- **Then** the task is saved but a warning is shown that the task will not fire at the exact scheduled time

### TEST-037-06: Tool Context Error
- **Given** the exact alarm permission is NOT granted
- **When** the AI agent calls `schedule_task`
- **Then** the tool returns an error message: "Exact alarm permission is not granted. Please go to Settings > Apps > OneClaw > Alarms & reminders to enable it."

### TEST-037-07: Boot Receiver Check
- **Given** the device reboots and the exact alarm permission is NOT granted
- **When** `BootCompletedReceiver` fires
- **Then** alarms are NOT registered and no crash occurs

### TEST-037-08: Pre-Android 12 No-Op
- **Given** the app runs on Android 11 or lower
- **When** a scheduled task is created
- **Then** no permission check or dialog is shown; the alarm is registered directly

### TEST-037-09: Manifest Compliance
- **Given** the app is submitted to Google Play
- **When** the manifest is reviewed
- **Then** only `SCHEDULE_EXACT_ALARM` is declared (no `USE_EXACT_ALARM`), satisfying Play Store policy for non-alarm-clock apps

## Non-Functional Requirements

- The permission check must have zero overhead on Android < 12 (no reflection, no version-guarded code paths beyond a simple `Build.VERSION.SDK_INT` check).
- The dialog must follow Material 3 design guidelines and match the app's existing theme.
- The permission state receiver must be lightweight -- only reschedule alarms, no heavy initialization.

## Out of Scope

- Showing the permission dialog on app startup proactively (only shown when the user attempts to create/edit a scheduled task).
- Falling back to inexact alarms when permission is denied (tasks simply won't have alarms registered).
- In-app settings toggle for exact alarms (managed entirely through system settings).
