# Scheduled Task Detail Page

## Feature Information
- **Feature ID**: FEAT-024
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P2 (Could Have)
- **Owner**: TBD
- **Related RFC**: [RFC-024 (Scheduled Task Detail)](../../rfc/features/RFC-024-scheduled-task-detail.md)
- **Extends**: [FEAT-019 (Scheduled Tasks)](FEAT-019-scheduled-tasks.md)

## User Story

**As** a user of OneClawShadow,
**I want to** view detailed information about a scheduled task including its configuration, execution history, and next trigger time,
**so that** I can monitor task performance, diagnose failures, review past results, and quickly navigate to execution sessions without needing to enter the edit screen.

### Typical Scenarios

1. User taps a scheduled task in the list to see its full configuration (agent name, prompt, schedule details) and current status at a glance, without entering edit mode.
2. User wants to check why a daily task failed last night. They open the detail page, see the execution history with timestamps, status indicators, and error info, then tap the failed entry to open the corresponding session and review the conversation.
3. User has a weekly report task and wants to verify the next trigger time is correct after a timezone change. The detail page shows the exact next trigger datetime.
4. User wants to manually run a scheduled task right now to test it, without waiting for the scheduled time. They tap "Run Now" on the detail page.
5. User reviews execution history and notices a task has been failing consistently. From the detail page, they tap "Edit" to adjust the prompt or switch to a different agent.

## Feature Description

### Overview

The Scheduled Task Detail Page is a read-only view that displays comprehensive information about a single scheduled task. It replaces the current behavior where tapping a task in the list navigates directly to the edit form. Instead, tapping a task now opens this detail page, which serves as the primary hub for inspecting task status, browsing execution history, and performing quick actions.

### Page Sections

#### 1. Task Configuration Section

Displays the static configuration of the task in a readable, non-editable format:
- **Task name** (page title)
- **Agent**: Resolved agent name (not raw ID)
- **Prompt**: Full prompt text, expandable if long
- **Schedule**: Human-readable description (e.g., "Daily at 07:00", "Every Monday at 09:00", "One-time at 2026-03-15 14:30")
- **Enabled/Disabled**: Current status with a toggle switch for quick enable/disable

#### 2. Status Section

Displays runtime state information:
- **Next trigger time**: Formatted datetime of the next scheduled execution (or "Disabled" / "Completed" for disabled/finished one-time tasks)
- **Last execution**: Time and status of the most recent execution
- **Created at / Updated at**: Task creation and last modification timestamps

#### 3. Execution History Section

A chronological list of all past executions, newest first:
- **Timestamp**: When the execution started
- **Status**: SUCCESS or FAILED indicator
- **Session link**: Tappable to navigate to the corresponding chat session
- **Duration**: How long the execution took (optional, if tracked)

This requires a new `task_execution_records` data model, since the current `ScheduledTask` only stores the last execution. Historical records are persisted in a new Room table.

#### 4. Actions

Available actions on the detail page:
- **Edit**: Navigate to the edit screen with current task data
- **Delete**: Delete the task (with confirmation dialog)
- **Toggle**: Enable/disable the task (inline switch)
- **Run Now**: Manually trigger an immediate execution of the task, creating a new session and running the full agent loop. This is useful for testing without waiting for the scheduled time.
- **View Last Session**: Quick link to open the most recent execution's session (if available)

### Navigation Flow Change

**Before (RFC-019)**:
```
List -> tap item -> Edit Screen
```

**After (FEAT-024)**:
```
List -> tap item -> Detail Page -> Edit button -> Edit Screen
```

The FAB on the list screen still navigates directly to the Create screen.

## Acceptance Criteria

### TEST-024-01: Navigate to Detail Page
- **Given** the user is on the scheduled task list screen
- **When** they tap on a task item
- **Then** the detail page opens showing the task's full configuration, status, and execution history

### TEST-024-02: Display Task Configuration
- **Given** the user is on the detail page for a task
- **Then** the page shows: task name, agent name (not ID), full prompt text, schedule description, and enabled status

### TEST-024-03: Display Next Trigger Time
- **Given** the user views a detail page for an enabled daily task scheduled at 08:00
- **Then** the next trigger time shows the correct upcoming datetime
- **And** for a disabled task, the next trigger shows "Disabled"

### TEST-024-04: Display Execution History
- **Given** a scheduled task has been executed 3 times (2 success, 1 failed)
- **When** the user opens the detail page
- **Then** all 3 executions are listed with correct timestamps, statuses, and session links

### TEST-024-05: Navigate to Execution Session
- **Given** the user is on the detail page and sees an execution history entry with a session
- **When** they tap on the entry
- **Then** the app navigates to the chat session showing the execution's conversation

### TEST-024-06: Edit from Detail Page
- **Given** the user is on the detail page
- **When** they tap the Edit action
- **Then** the edit screen opens with the task's current configuration pre-filled

### TEST-024-07: Delete from Detail Page
- **Given** the user is on the detail page
- **When** they tap Delete and confirm
- **Then** the task is deleted, the alarm is cancelled, and the user is navigated back to the list

### TEST-024-08: Toggle from Detail Page
- **Given** the user is on the detail page for an enabled task
- **When** they toggle the switch to disabled
- **Then** the alarm is cancelled, the status updates to disabled, and the next trigger time updates accordingly

### TEST-024-09: Run Now
- **Given** the user is on the detail page for a task
- **When** they tap "Run Now"
- **Then** the task executes immediately (creates a session, runs agent loop), the execution history updates with the new entry, and a result notification is sent

### TEST-024-10: Empty Execution History
- **Given** a newly created task that has never been executed
- **When** the user opens the detail page
- **Then** the execution history section shows an empty state message like "No executions yet"

## Non-Functional Requirements

- Execution history should be limited to the most recent 50 entries per task to prevent unbounded growth.
- Execution history older than 90 days should be automatically cleaned up.
- The detail page should load within 200ms for tasks with up to 50 execution history entries.
- "Run Now" should show a progress indicator and prevent double-tapping.

## Out of Scope

- Execution log details (full conversation transcript on the detail page -- users can view the session instead)
- Execution history export or sharing
- Execution retry from history (re-running a specific past execution)
- Push notification configuration per task (global notification settings are handled by FEAT-008)
- Execution statistics or charts (e.g., success rate, average duration)

## Dependencies

### Depends On
- **FEAT-019 (Scheduled Tasks)**: This feature extends the existing scheduled task system
- **FEAT-001 (Chat)**: Session navigation for viewing execution results

### Depended On By
- None currently

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
