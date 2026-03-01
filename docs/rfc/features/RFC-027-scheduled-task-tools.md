# RFC-027: Scheduled Task Management Tools

## Metadata
- **RFC ID**: RFC-027
- **Feature**: FEAT-027 (Scheduled Task Management Tools)
- **Extends**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md)
- **Depends On**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft

## Overview

RFC-019 introduced the `schedule_task` built-in tool for creating scheduled tasks from conversation. This RFC adds four additional built-in tools that enable full lifecycle management of scheduled tasks through the AI agent:

| Tool | Action |
|------|--------|
| `list_scheduled_tasks` | List all tasks with details |
| `run_scheduled_task` | Trigger immediate async execution |
| `update_scheduled_task` | Partial update of task fields |
| `delete_scheduled_task` | Cancel alarm and remove task |

All tools follow the same patterns established by `CreateScheduledTaskTool` in RFC-019: implement the `Tool` interface, register in `ToolModule` with `ToolSourceInfo.BUILTIN`, and delegate to use cases for business logic.

## Tool Definitions

### list_scheduled_tasks

Lists all scheduled tasks with their current state.

**Parameters**: None

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| _(none)_ | | | |

**Implementation**: `ListScheduledTasksTool`

```kotlin
class ListScheduledTasksTool(
    private val scheduledTaskRepository: ScheduledTaskRepository
) : Tool
```

**Behavior**:
1. Calls `scheduledTaskRepository.getAllTasks().first()` to get a snapshot of all tasks
2. If the list is empty, returns a message: "No scheduled tasks configured."
3. Otherwise, formats each task as a text block containing:
   - `id`: task ID (for use with other management tools)
   - `name`: task name
   - `schedule`: human-readable schedule description (e.g., "Daily at 07:00", "Every Monday at 09:00")
   - `enabled`: true/false
   - `last_execution`: timestamp and status of last execution, or "Never"
   - `next_trigger`: next scheduled trigger time, or "None"

**Return Format** (example):

```
Found 2 scheduled tasks:

1. [id: abc123] Morning Briefing
   Schedule: Daily at 07:00
   Enabled: true
   Last execution: 2026-03-01 07:00 - SUCCESS
   Next trigger: 2026-03-02 07:00

2. [id: def456] Weekly Summary
   Schedule: Every Monday at 09:00
   Enabled: false
   Last execution: 2026-02-24 09:01 - FAILED
   Next trigger: None
```

**Timeout**: 10 seconds

---

### run_scheduled_task

Enqueues a scheduled task for immediate asynchronous execution via WorkManager.

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| task_id | string | yes | ID of the task to run |

**Implementation**: `RunScheduledTaskTool` + `RunScheduledTaskUseCase`

```kotlin
class RunScheduledTaskTool(
    private val runScheduledTaskUseCase: RunScheduledTaskUseCase
) : Tool
```

**Behavior**:
1. Validates `task_id` parameter is present
2. Delegates to `RunScheduledTaskUseCase(taskId)`
3. Returns success message on enqueue, or error if task not found

**Return Format**:
- Success: `"Task 'Morning Briefing' has been queued for execution. You will receive a notification when it completes."`
- Error: `"Task not found with ID 'xyz'."`

**Timeout**: 10 seconds

---

### update_scheduled_task

Updates one or more fields of an existing scheduled task using partial update semantics.

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| task_id | string | yes | ID of the task to update |
| name | string | no | New task name |
| prompt | string | no | New prompt message |
| schedule_type | string | no | "one_time", "daily", or "weekly" |
| hour | integer | no | New hour (0-23) |
| minute | integer | no | New minute (0-59) |
| day_of_week | string | no | Day name for weekly (e.g., "monday") |
| date | string | no | Date for one-time in YYYY-MM-DD format |
| enabled | boolean | no | Enable or disable the task |

**Implementation**: `UpdateScheduledTaskTool`

```kotlin
class UpdateScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val updateScheduledTaskUseCase: UpdateScheduledTaskUseCase
) : Tool
```

**Behavior**:
1. Validates `task_id` is present
2. Fetches the existing task from `scheduledTaskRepository.getTaskById(taskId)`
3. Returns error if task not found
4. Applies partial updates: for each optional parameter that is provided and non-null, overrides the corresponding field on the existing `ScheduledTask`. Omitted parameters retain their current values.
5. Delegates the updated `ScheduledTask` to `UpdateScheduledTaskUseCase`, which handles alarm cancellation, trigger recalculation, and alarm re-registration
6. Returns success message listing the changed fields

**Partial Update Logic**:

```kotlin
val updated = existingTask.copy(
    name = (parameters["name"] as? String) ?: existingTask.name,
    prompt = (parameters["prompt"] as? String) ?: existingTask.prompt,
    scheduleType = parsedScheduleType ?: existingTask.scheduleType,
    hour = parsedHour ?: existingTask.hour,
    minute = parsedMinute ?: existingTask.minute,
    dayOfWeek = parsedDayOfWeek ?: existingTask.dayOfWeek,
    dateMillis = parsedDateMillis ?: existingTask.dateMillis,
    isEnabled = parsedEnabled ?: existingTask.isEnabled
)
```

**Return Format**:
- Success: `"Task 'Morning Briefing' updated successfully. Changed fields: hour (7 -> 8). Next trigger: 2026-03-02 08:00."`
- Error (not found): `"Task not found with ID 'xyz'."`
- Error (validation): `"Task name is required."` (from `UpdateScheduledTaskUseCase`)

**Timeout**: 10 seconds

---

### delete_scheduled_task

Permanently removes a scheduled task, cancelling its alarm.

**Parameters**:

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| task_id | string | yes | ID of the task to delete |

**Implementation**: `DeleteScheduledTaskTool`

```kotlin
class DeleteScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val deleteScheduledTaskUseCase: DeleteScheduledTaskUseCase
) : Tool
```

**Behavior**:
1. Validates `task_id` is present
2. Fetches the task to verify it exists and get its name for the confirmation message
3. Returns error if task not found
4. Delegates to `DeleteScheduledTaskUseCase(taskId)`, which cancels the alarm and deletes the task from the database
5. Returns success message

**Return Format**:
- Success: `"Task 'Morning Briefing' has been deleted. Its alarm has been cancelled."`
- Error: `"Task not found with ID 'xyz'."`

**Timeout**: 10 seconds

## New Components

### RunScheduledTaskUseCase

A new use case that enqueues a WorkManager work request for immediate task execution. Unlike the alarm-triggered flow in `ScheduledTaskReceiver`, this use case:

- Validates the task exists (returns error if not found)
- Passes a `KEY_MANUAL_RUN = true` flag to the worker
- Does NOT require the task to be enabled

```kotlin
package com.oneclaw.shadow.feature.schedule.usecase

class RunScheduledTaskUseCase(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val workManager: WorkManager
) {
    suspend operator fun invoke(taskId: String): AppResult<String> {
        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return AppResult.Error(
                message = "Task not found with ID '$taskId'.",
                code = ErrorCode.NOT_FOUND
            )

        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val workRequest = OneTimeWorkRequestBuilder<ScheduledTaskWorker>()
            .setConstraints(constraints)
            .setInputData(
                workDataOf(
                    ScheduledTaskWorker.KEY_TASK_ID to taskId,
                    ScheduledTaskWorker.KEY_MANUAL_RUN to true
                )
            )
            .build()

        workManager.enqueueUniqueWork(
            "scheduled_task_manual_$taskId",
            ExistingWorkPolicy.REPLACE,
            workRequest
        )

        return AppResult.Success(task.name)
    }
}
```

Key design decisions:
- Uses a separate work name prefix (`scheduled_task_manual_`) to avoid conflicting with alarm-triggered work (`scheduled_task_`)
- Returns the task name so the tool can include it in the confirmation message
- WorkManager handles network constraints and foreground service, same as alarm-triggered execution

### ScheduledTaskWorker Modifications

The existing `ScheduledTaskWorker` requires two changes to support manual runs:

1. **New constant**: `KEY_MANUAL_RUN`
2. **Skip enabled check**: When `manualRun = true`, skip the `if (!task.isEnabled) return Result.success()` guard
3. **Skip rescheduling**: When `manualRun = true`, do not modify `isEnabled` or `nextTriggerAt`, and do not re-register the alarm

```kotlin
companion object {
    const val KEY_TASK_ID = "task_id"
    const val KEY_MANUAL_RUN = "manual_run"
    private const val FOREGROUND_NOTIFICATION_ID = 9001
}

override suspend fun doWork(): Result {
    val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
    val manualRun = inputData.getBoolean(KEY_MANUAL_RUN, false)

    val task = scheduledTaskRepository.getTaskById(taskId) ?: return Result.failure()

    // Skip enabled check for manual runs
    if (!manualRun && !task.isEnabled) return Result.success()

    // ... existing execution logic (create session, run agent loop) ...

    if (manualRun) {
        // Manual run: update execution status only, do not change enabled/nextTriggerAt
        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            sessionId = sessionId,
            nextTriggerAt = task.nextTriggerAt,  // unchanged
            isEnabled = task.isEnabled            // unchanged
        )
        // No alarm rescheduling
    } else {
        // Alarm-triggered: existing rescheduling logic
        val isOneTime = task.scheduleType == ScheduleType.ONE_TIME
        val nextEnabled = if (isOneTime) false else task.isEnabled
        val nextTriggerAt = if (isOneTime) null else NextTriggerCalculator.calculate(task)

        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            sessionId = sessionId,
            nextTriggerAt = nextTriggerAt,
            isEnabled = nextEnabled
        )

        if (!isOneTime && nextTriggerAt != null) {
            val updatedTask = task.copy(nextTriggerAt = nextTriggerAt)
            alarmScheduler.scheduleTask(updatedTask)
        }
    }

    // ... existing notification logic ...

    return Result.success()
}
```

### Tool Classes

All four tool classes live in `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/`:

| File | Class |
|------|-------|
| `ListScheduledTasksTool.kt` | `ListScheduledTasksTool` |
| `RunScheduledTaskTool.kt` | `RunScheduledTaskTool` |
| `UpdateScheduledTaskTool.kt` | `UpdateScheduledTaskTool` |
| `DeleteScheduledTaskTool.kt` | `DeleteScheduledTaskTool` |

Each tool follows the same structure as `CreateScheduledTaskTool`:
- Implements `Tool` interface
- Defines `ToolDefinition` with name, description, and `ToolParametersSchema`
- `execute()` validates parameters, delegates to use case / repository, and returns `ToolResult`
- `requiredPermissions = emptyList()` (no special permissions needed)
- `timeoutSeconds = 10`

### Helper: Schedule Description Formatter

The `buildScheduleDescription()` function in `CreateScheduledTaskTool` is duplicated for `ListScheduledTasksTool` and `UpdateScheduledTaskTool`. Extract to a shared utility:

```kotlin
package com.oneclaw.shadow.feature.schedule.util

object ScheduleDescriptionFormatter {
    fun format(task: ScheduledTask): String {
        val time = String.format("%02d:%02d", task.hour, task.minute)
        return when (task.scheduleType) {
            ScheduleType.DAILY -> "Daily at $time"
            ScheduleType.WEEKLY -> {
                val dayName = task.dayOfWeek?.let {
                    DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() }
                } ?: "unknown"
                "Every $dayName at $time"
            }
            ScheduleType.ONE_TIME -> {
                val dateStr = task.dateMillis?.let {
                    Instant.ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                } ?: "unknown date"
                "One-time on $dateStr at $time"
            }
        }
    }
}
```

## DI Registration

### ToolModule

```kotlin
// RFC-027: Scheduled task management tools
single { ListScheduledTasksTool(get()) }
single { RunScheduledTaskTool(get()) }
single { UpdateScheduledTaskTool(get(), get()) }
single { DeleteScheduledTaskTool(get(), get()) }

// In ToolRegistry setup:
try { register(get<ListScheduledTasksTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register list_scheduled_tasks: ${e.message}") }

try { register(get<RunScheduledTaskTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register run_scheduled_task: ${e.message}") }

try { register(get<UpdateScheduledTaskTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register update_scheduled_task: ${e.message}") }

try { register(get<DeleteScheduledTaskTool>(), ToolSourceInfo.BUILTIN) }
catch (e: Exception) { Log.e("ToolModule", "Failed to register delete_scheduled_task: ${e.message}") }
```

### FeatureModule

```kotlin
// RFC-027: RunScheduledTaskUseCase
factory { RunScheduledTaskUseCase(get(), WorkManager.getInstance(androidContext())) }
```

Note: `UpdateScheduledTaskUseCase` and `DeleteScheduledTaskUseCase` are already registered by RFC-019.

## Relationship to RFC-024

RFC-024 defines `RunScheduledTaskNowUseCase`, which executes a task synchronously in the calling coroutine context (used by the detail page's "Run Now" button). RFC-027's `RunScheduledTaskUseCase` is distinct: it enqueues WorkManager and returns immediately, which is appropriate for tool execution within a chat context where blocking for minutes is unacceptable.

If RFC-024 is implemented before RFC-027, both use cases coexist:
- `RunScheduledTaskNowUseCase` -- synchronous execution for UI context (detail page)
- `RunScheduledTaskUseCase` -- async WorkManager enqueue for tool context (chat)

## Database Changes

No database schema changes required. All operations use existing `ScheduledTaskRepository` methods.

## Testing Strategy

### Unit Tests

**ListScheduledTasksToolTest:**
- Verify empty list returns "No scheduled tasks configured."
- Verify non-empty list formats all tasks with correct fields
- Verify schedule description formatting for DAILY, WEEKLY, ONE_TIME

**RunScheduledTaskToolTest:**
- Verify missing `task_id` returns validation error
- Verify nonexistent task ID returns not-found error
- Verify valid task ID returns success message with task name

**RunScheduledTaskUseCaseTest:**
- Verify nonexistent task returns `AppResult.Error` with `NOT_FOUND`
- Verify valid task enqueues WorkManager with correct input data (`KEY_TASK_ID`, `KEY_MANUAL_RUN = true`)
- Verify work name uses `scheduled_task_manual_` prefix
- Verify returns `AppResult.Success` with task name

**UpdateScheduledTaskToolTest:**
- Verify missing `task_id` returns validation error
- Verify nonexistent task ID returns not-found error
- Verify partial update: only provided fields change, others remain
- Verify `schedule_type` change with missing required fields (e.g., weekly without `day_of_week`) returns validation error
- Verify `enabled=false` results in alarm cancellation (via `UpdateScheduledTaskUseCase`)

**DeleteScheduledTaskToolTest:**
- Verify missing `task_id` returns validation error
- Verify nonexistent task ID returns not-found error
- Verify valid task ID calls `DeleteScheduledTaskUseCase` and returns success

**ScheduledTaskWorkerTest (manual run):**
- Verify `KEY_MANUAL_RUN = true` skips the enabled check
- Verify `KEY_MANUAL_RUN = true` does not modify `isEnabled` or `nextTriggerAt`
- Verify `KEY_MANUAL_RUN = true` does not re-register the alarm
- Verify `KEY_MANUAL_RUN = false` (default) retains existing behavior

**ScheduleDescriptionFormatterTest:**
- Verify formatting for DAILY, WEEKLY, ONE_TIME schedule types
- Verify edge cases: null `dayOfWeek`, null `dateMillis`

## Implementation Steps

### Phase 1: Shared Utilities
1. [ ] Extract `ScheduleDescriptionFormatter` from `CreateScheduledTaskTool`
2. [ ] Update `CreateScheduledTaskTool` to use the shared formatter

### Phase 2: Worker Modification
1. [ ] Add `KEY_MANUAL_RUN` constant to `ScheduledTaskWorker`
2. [ ] Read `manualRun` flag from `inputData` in `doWork()`
3. [ ] Skip enabled check when `manualRun = true`
4. [ ] Skip rescheduling when `manualRun = true`
5. [ ] Add unit tests for manual run behavior

### Phase 3: Use Case
1. [ ] Create `RunScheduledTaskUseCase` in `feature/schedule/usecase/`
2. [ ] Register in `FeatureModule`
3. [ ] Add unit tests

### Phase 4: Tool Classes
1. [ ] Create `ListScheduledTasksTool` in `tool/builtin/`
2. [ ] Create `RunScheduledTaskTool` in `tool/builtin/`
3. [ ] Create `UpdateScheduledTaskTool` in `tool/builtin/`
4. [ ] Create `DeleteScheduledTaskTool` in `tool/builtin/`
5. [ ] Register all four tools in `ToolModule`
6. [ ] Add unit tests for all four tools

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
