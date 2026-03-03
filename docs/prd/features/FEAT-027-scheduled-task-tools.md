# Scheduled Task Management Tools

## Feature Information
- **Feature ID**: FEAT-027
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P2 (Nice to Have)
- **Owner**: TBD
- **Related RFC**: [RFC-027 (Scheduled Task Management Tools)](../../rfc/features/RFC-027-scheduled-task-tools.md)
- **Depends On**: [FEAT-019 (Scheduled Tasks)](FEAT-019-scheduled-tasks.md)

## User Story

**As** a user of OneClaw,
**I want to** manage my scheduled tasks through conversation with the AI agent,
**so that** I can list, run, update, and delete scheduled tasks without navigating to the Scheduled Tasks UI.

### Typical Scenarios

1. User asks "What scheduled tasks do I have?" The agent calls `list_scheduled_tasks` and returns a formatted list showing each task's ID, name, schedule, enabled status, and last execution result.
2. User says "Run my morning briefing task right now." The agent calls `run_scheduled_task` with the task ID. The tool enqueues the task for immediate execution via WorkManager and returns a confirmation. The user receives a notification when execution completes.
3. User says "Change my daily briefing to run at 8am instead of 7am." The agent calls `update_scheduled_task` with the task ID and `hour=8`. Only the specified field is changed; all other fields remain unchanged.
4. User says "Delete the weekly summary task." The agent calls `delete_scheduled_task` with the task ID. The alarm is cancelled and the task is permanently removed.
5. User says "Disable the daily news task." The agent calls `update_scheduled_task` with the task ID and `enabled=false`. The alarm is cancelled but the task remains in the database for later re-enabling.
6. User says "Run the daily briefing task to test it" for a disabled task. The agent calls `run_scheduled_task`. The tool executes the task even though it is disabled, without changing the enabled status or alarm schedule.

## Feature Description

### Overview

FEAT-019 introduced the `schedule_task` tool for creating scheduled tasks from conversation. However, once created, users must switch to the Scheduled Tasks UI to manage them. This feature adds four new built-in tools that enable full lifecycle management of scheduled tasks through conversation:

| Tool | Purpose |
|------|---------|
| `list_scheduled_tasks` | List all scheduled tasks with their details |
| `run_scheduled_task` | Trigger immediate execution of a task |
| `update_scheduled_task` | Modify task configuration |
| `delete_scheduled_task` | Remove a task permanently |

### Tool Descriptions

#### list_scheduled_tasks

Returns all scheduled tasks with their current state. No parameters required. The response includes each task's ID, name, schedule description, enabled status, last execution time, and last execution status. The agent uses this information to help users identify tasks by ID for subsequent operations.

#### run_scheduled_task

Enqueues a scheduled task for immediate asynchronous execution via WorkManager. The tool returns immediately with a confirmation message. The actual execution happens in the background using the existing `ScheduledTaskWorker`. A notification is sent when execution completes. Key behaviors:

- Works on both enabled and disabled tasks (useful for testing without enabling the schedule)
- Does not reschedule the alarm or disable one-time tasks (ad-hoc execution only)
- Requires network connectivity (enforced by WorkManager constraint)

#### update_scheduled_task

Updates one or more fields of an existing scheduled task. Uses partial update semantics: only provided fields are changed, and omitted fields retain their current values. Updatable fields include name, prompt, schedule type, hour, minute, day of week, date, and enabled status. When schedule-related fields change on an enabled task, the alarm is automatically recalculated and re-registered.

#### delete_scheduled_task

Permanently removes a scheduled task. Cancels any registered alarm and deletes the task from the database. This action is irreversible.

### Interaction Model

Users interact with these tools through natural language. The agent determines which tool to call based on user intent. A typical workflow:

1. User asks to see their tasks -> agent calls `list_scheduled_tasks`
2. Agent presents the list with task IDs
3. User references a specific task by name or ID -> agent calls the appropriate management tool

Task identification is by ID. The `list_scheduled_tasks` tool provides IDs that the agent uses for subsequent operations. The agent may also resolve task names to IDs when the user refers to tasks by name.

## Acceptance Criteria

### TEST-027-01: List All Tasks
- **Given** the user has created one or more scheduled tasks
- **When** the agent calls `list_scheduled_tasks`
- **Then** all tasks are returned with id, name, schedule description, enabled status, last execution time, and last execution status

### TEST-027-02: List Empty
- **Given** no scheduled tasks exist
- **When** the agent calls `list_scheduled_tasks`
- **Then** the tool returns a message indicating no tasks are configured

### TEST-027-03: Run Task Immediately
- **Given** a scheduled task exists with a valid ID
- **When** the agent calls `run_scheduled_task` with the task ID
- **Then** a WorkManager work request is enqueued for the task, and the tool returns a success message confirming the task has been queued

### TEST-027-04: Run Task Notification
- **Given** a task has been triggered via `run_scheduled_task`
- **When** the WorkManager execution completes
- **Then** the user receives a notification with the task name and response preview

### TEST-027-05: Run Disabled Task
- **Given** a scheduled task exists but is disabled
- **When** the agent calls `run_scheduled_task` with the task ID
- **Then** the task executes successfully without changing its enabled status or alarm schedule

### TEST-027-06: Run Nonexistent Task
- **Given** no task exists with the provided ID
- **When** the agent calls `run_scheduled_task`
- **Then** the tool returns an error message indicating the task was not found

### TEST-027-07: Update Task Name
- **Given** a scheduled task exists
- **When** the agent calls `update_scheduled_task` with a new name
- **Then** the task name is updated and all other fields remain unchanged

### TEST-027-08: Update Task Schedule
- **Given** an enabled daily task exists at 07:00
- **When** the agent calls `update_scheduled_task` with `hour=8`
- **Then** the task's hour is updated to 8, the alarm is recalculated to the next 08:00 trigger, and all other fields remain unchanged

### TEST-027-09: Update Task Enabled Status
- **Given** an enabled scheduled task exists
- **When** the agent calls `update_scheduled_task` with `enabled=false`
- **Then** the task is disabled and its alarm is cancelled

### TEST-027-10: Update Nonexistent Task
- **Given** no task exists with the provided ID
- **When** the agent calls `update_scheduled_task`
- **Then** the tool returns an error message indicating the task was not found

### TEST-027-11: Delete Task
- **Given** a scheduled task exists
- **When** the agent calls `delete_scheduled_task` with the task ID
- **Then** the alarm is cancelled and the task is removed from the database

### TEST-027-12: Delete Nonexistent Task
- **Given** no task exists with the provided ID
- **When** the agent calls `delete_scheduled_task`
- **Then** the tool returns an error message indicating the task was not found

## Non-Functional Requirements

- All four tools must have a timeout of 10 seconds (matching `schedule_task`).
- `run_scheduled_task` returns immediately after enqueuing; the actual execution timeout is governed by `ScheduledTaskWorker` (10 minutes).
- Tools are registered as `ToolSourceInfo.BUILTIN` and available to all agents.

## Out of Scope

- Bulk operations (e.g., delete all tasks, disable all tasks)
- Task filtering or search in `list_scheduled_tasks` (all tasks are returned)
- Viewing execution history through tools (use the Scheduled Tasks UI)
- Creating new tasks (already covered by `schedule_task` in FEAT-019)
