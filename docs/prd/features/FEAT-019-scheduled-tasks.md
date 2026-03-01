# Scheduled Tasks (Cron Job)

## Feature Information
- **Feature ID**: FEAT-019
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: [RFC-019 (Scheduled Tasks)](../../rfc/features/RFC-019-scheduled-tasks.md)

## User Story

**As** a user of OneClawShadow,
**I want to** schedule AI agent tasks to run automatically at specified times,
**so that** I can automate recurring tasks like morning briefings, daily summaries, or periodic data checks without manual intervention.

### Typical Scenarios

1. User creates a daily morning briefing task that runs at 7:00 AM. Every morning, the agent automatically creates a new session, fetches news/weather via tools, and sends the user a notification with a preview of the response.
2. User sets a one-time reminder task for next Tuesday at 3:00 PM to ask the agent to prepare meeting notes. The task fires once, creates a session with the results, and disables itself.
3. User creates a weekly task that runs every Monday at 9:00 AM to generate a weekly summary. The agent runs the full Agent Loop including tool calls, saves the result to a session, and notifies the user.
4. User disables a scheduled task temporarily via a toggle switch. The alarm is cancelled. When re-enabled, the alarm is re-registered for the next trigger time.
5. User reboots their device. All enabled scheduled tasks automatically re-register their alarms and continue to fire at the correct times.

## Feature Description

### Overview

Scheduled Tasks allows users to create, manage, and automate AI agent tasks on a time-based schedule. Each task is associated with an agent and a prompt message. When the scheduled time arrives, the system automatically creates a new session, runs the full Agent Loop (including streaming and tool execution), saves the result, and sends a notification.

### Schedule Types

1. **One-Time**: Fires once at a specific date and time. Automatically disables after execution.
2. **Daily**: Fires every day at the specified hour and minute.
3. **Weekly**: Fires every week on the specified day of week at the specified hour and minute.

### Task Lifecycle

1. **Creation**: User fills in task name, selects an agent, writes a prompt, chooses schedule type and time.
2. **Scheduling**: System calculates the next trigger time and registers an exact alarm via AlarmManager.
3. **Triggering**: AlarmManager fires a BroadcastReceiver, which enqueues a WorkManager one-time work request.
4. **Execution**: Worker creates a session, runs SendMessageUseCase, collects the full response, and updates execution status.
5. **Notification**: Worker sends a notification with the task name and response preview. Tapping the notification opens the corresponding session.
6. **Rescheduling**: For recurring tasks (DAILY, WEEKLY), the worker calculates and registers the next alarm. For ONE_TIME tasks, the task is automatically disabled.

### Management

- **List View**: Shows all scheduled tasks with name, schedule description, enabled/disabled toggle, and last execution status.
- **Create/Edit**: Form with task name, agent selector, prompt text, schedule type selector, time picker, and (for ONE_TIME) date picker or (for WEEKLY) day-of-week selector.
- **Delete**: Cancels the alarm and removes the task from the database.
- **Toggle**: Enable/disable switch that registers or cancels the alarm.

### Prompt-Based Creation

Users can also create scheduled tasks by asking the AI agent in conversation. For example:
- "Schedule a daily task at 7am to give me a morning news briefing"
- "Create a weekly task every Monday at 9am to summarize my week"

The agent uses the `schedule_task` tool to create the task, which is then managed through the Scheduled Tasks UI.

## Acceptance Criteria

### TEST-019-01: Create a One-Time Task
- **Given** the user is on the scheduled task creation screen
- **When** they fill in the name, select an agent, enter a prompt, choose "One-Time", pick a future date and time, and tap Save
- **Then** the task appears in the list with the correct schedule description and an enabled toggle

### TEST-019-02: Task Triggers and Creates Session
- **Given** a scheduled task is enabled with a trigger time that has arrived
- **When** the AlarmManager fires the alarm
- **Then** a new session is created, the agent loop runs with the configured prompt, and the response is saved in the session

### TEST-019-03: Notification on Completion
- **Given** a scheduled task has completed execution
- **When** the worker finishes
- **Then** a notification is sent with the task name and a preview of the response. Tapping the notification opens the session.

### TEST-019-04: One-Time Auto-Disable
- **Given** a one-time scheduled task has executed
- **When** execution completes
- **Then** the task is automatically disabled and no further alarms are scheduled

### TEST-019-05: Daily Rescheduling
- **Given** a daily scheduled task has executed at 7:00 AM
- **When** execution completes
- **Then** the next alarm is registered for 7:00 AM the following day

### TEST-019-06: Toggle Enable/Disable
- **Given** a scheduled task is enabled
- **When** the user toggles it off
- **Then** the alarm is cancelled. When toggled back on, the alarm is re-registered for the next trigger time.

### TEST-019-07: Boot Persistence
- **Given** there are enabled scheduled tasks
- **When** the device reboots
- **Then** all enabled alarms are re-registered

### TEST-019-08: Delete Task
- **Given** a scheduled task exists
- **When** the user deletes it
- **Then** the alarm is cancelled and the task is removed from the database

## Non-Functional Requirements

- Task execution must use a foreground service notification to prevent the system from killing the worker during long-running agent loops.
- Alarms use `setExactAndAllowWhileIdle` for reliable triggering even in Doze mode.
- Network connectivity is required for task execution (WorkManager constraint).
- Task execution timeout: 10 minutes maximum.

## Out of Scope

- Custom cron expressions (only preset schedule types are supported)
- Task chaining or dependencies between tasks
- Editing a running task's parameters mid-execution
- Cloud-based scheduling (all scheduling is local via AlarmManager)
