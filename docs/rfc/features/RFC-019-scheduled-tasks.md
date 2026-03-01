# RFC-019: Scheduled Tasks

## Metadata
- **RFC ID**: RFC-019
- **Feature**: FEAT-019 (Scheduled Tasks)
- **Created**: 2026-02-28
- **Status**: Draft

## Overview

This RFC describes the technical design for scheduled task functionality in OneClawShadow. Users can create tasks that automatically run an AI agent at specified times using three schedule types: one-time, daily, and weekly.

## Architecture

### Scheduling Mechanism: AlarmManager + WorkManager

- **AlarmManager** (`setExactAndAllowWhileIdle`): Provides precise timing for task triggers. Requires `SCHEDULE_EXACT_ALARM` (API < 33) or `USE_EXACT_ALARM` permission.
- **WorkManager** (`OneTimeWorkRequest`): Handles the actual task execution. BroadcastReceiver has a 10-second limit, but Agent Loop execution may take several minutes. WorkManager handles retries and constraints.
- **BOOT_COMPLETED Receiver**: Re-registers all enabled alarms after device reboot or timezone change.
- **Foreground Service**: Worker uses `setForeground()` with an ongoing notification to prevent the system from killing long-running tasks.

### Execution Flow

```
AlarmManager trigger -> ScheduledTaskReceiver (BroadcastReceiver)
  -> Enqueue WorkManager OneTimeWork (requires network)
  -> ScheduledTaskWorker:
     1. Read task configuration from DB
     2. CreateSessionUseCase to create a new Session
     3. SendMessageUseCase.execute() to run full Agent Loop
     4. Collect Flow<ChatEvent>, extract final response text
     5. Update task execution status (success/failure)
     6. Send result notification (tap to open session)
     7. For recurring tasks: calculate and register next alarm
```

## Data Model

### ScheduledTask (Domain Model)

```kotlin
data class ScheduledTask(
    val id: String,
    val name: String,
    val agentId: String,
    val prompt: String,
    val scheduleType: ScheduleType,
    val hour: Int,          // 0-23
    val minute: Int,        // 0-59
    val dayOfWeek: Int?,    // 1=Monday..7=Sunday (ISO), null for non-WEEKLY
    val dateMillis: Long?,  // epoch ms for ONE_TIME date, null otherwise
    val isEnabled: Boolean,
    val lastExecutionAt: Long?,
    val lastExecutionStatus: ExecutionStatus?,
    val lastExecutionSessionId: String?,
    val nextTriggerAt: Long?,
    val createdAt: Long,
    val updatedAt: Long
)

enum class ScheduleType { ONE_TIME, DAILY, WEEKLY }
enum class ExecutionStatus { RUNNING, SUCCESS, FAILED }
```

### Room Entity

```kotlin
@Entity(
    tableName = "scheduled_tasks",
    indices = [
        Index(value = ["is_enabled"]),
        Index(value = ["next_trigger_at"])
    ]
)
data class ScheduledTaskEntity(
    @PrimaryKey val id: String,
    val name: String,
    @ColumnInfo(name = "agent_id") val agentId: String,
    val prompt: String,
    @ColumnInfo(name = "schedule_type") val scheduleType: String,
    val hour: Int,
    val minute: Int,
    @ColumnInfo(name = "day_of_week") val dayOfWeek: Int?,
    @ColumnInfo(name = "date_millis") val dateMillis: Long?,
    @ColumnInfo(name = "is_enabled") val isEnabled: Boolean,
    @ColumnInfo(name = "last_execution_at") val lastExecutionAt: Long?,
    @ColumnInfo(name = "last_execution_status") val lastExecutionStatus: String?,
    @ColumnInfo(name = "last_execution_session_id") val lastExecutionSessionId: String?,
    @ColumnInfo(name = "next_trigger_at") val nextTriggerAt: Long?,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long
)
```

### Database Migration (v4 -> v5)

```sql
CREATE TABLE IF NOT EXISTS scheduled_tasks (
    id TEXT NOT NULL PRIMARY KEY,
    name TEXT NOT NULL,
    agent_id TEXT NOT NULL,
    prompt TEXT NOT NULL,
    schedule_type TEXT NOT NULL,
    hour INTEGER NOT NULL,
    minute INTEGER NOT NULL,
    day_of_week INTEGER,
    date_millis INTEGER,
    is_enabled INTEGER NOT NULL DEFAULT 1,
    last_execution_at INTEGER,
    last_execution_status TEXT,
    last_execution_session_id TEXT,
    next_trigger_at INTEGER,
    created_at INTEGER NOT NULL,
    updated_at INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS index_scheduled_tasks_is_enabled ON scheduled_tasks(is_enabled);
CREATE INDEX IF NOT EXISTS index_scheduled_tasks_next_trigger_at ON scheduled_tasks(next_trigger_at);
```

## Components

### NextTriggerCalculator

Calculates the next trigger time using `java.time.*` API (minSdk 26):

- **ONE_TIME**: Combines `dateMillis` date with `hour:minute` time. If past, returns null.
- **DAILY**: Today at `hour:minute` if in the future, otherwise tomorrow at `hour:minute`.
- **WEEKLY**: Next occurrence of `dayOfWeek` at `hour:minute`.

All calculations respect the device's default timezone.

### AlarmScheduler

Wraps AlarmManager operations:

- `scheduleTask(task)`: Sets `setExactAndAllowWhileIdle()` alarm with `FLAG_IMMUTABLE` PendingIntent. Uses `task.id.hashCode()` as the request code.
- `cancelTask(taskId)`: Cancels the PendingIntent for the given task ID.
- `rescheduleAllEnabled(tasks)`: Bulk re-registers alarms for all enabled tasks. Used on boot and timezone changes.

### ScheduledTaskReceiver (BroadcastReceiver)

Receives alarm intents and enqueues a WorkManager `OneTimeWorkRequest`:

- Extracts `taskId` from intent extras
- Sets `NetworkType.CONNECTED` constraint
- Uses `ExistingWorkPolicy.REPLACE` with work name `"scheduled_task_{taskId}"`

### BootCompletedReceiver (BroadcastReceiver)

Listens for `ACTION_BOOT_COMPLETED` and `ACTION_TIMEZONE_CHANGED`:

- Uses `goAsync()` + coroutine scope to fetch all enabled tasks
- Calls `AlarmScheduler.rescheduleAllEnabled()`

### ScheduledTaskWorker (CoroutineWorker)

Executes the scheduled task:

1. Calls `setForeground()` with an ongoing notification on `SCHEDULED_TASK_EXECUTION_CHANNEL_ID`
2. Reads task configuration from the repository
3. Updates task status to `RUNNING`
4. Creates a new session via `CreateSessionUseCase`
5. Executes `SendMessageUseCase.execute()` and collects the `Flow<ChatEvent>`:
   - `ChatEvent.StreamingText` -> accumulates text
   - `ChatEvent.ResponseComplete` -> marks success
   - `ChatEvent.Error` -> marks failure
6. Updates task with execution result (status, session ID, timestamp)
7. Sends notification via `NotificationHelper`
8. For recurring tasks: calculates next trigger time and re-registers alarm
9. For ONE_TIME tasks: disables the task

## Built-in Tool: schedule_task

A Kotlin built-in tool (following the `LoadSkillTool` pattern) that enables AI agents to create scheduled tasks from conversation.

### Parameters
| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| name | string | yes | Task name |
| prompt | string | yes | Prompt to send when task fires |
| schedule_type | string | yes | "one_time", "daily", or "weekly" |
| hour | integer | yes | Hour (0-23) |
| minute | integer | yes | Minute (0-59) |
| day_of_week | string | no | Day name for weekly (e.g., "monday") |
| date | string | no | Date for one-time in YYYY-MM-DD format |

### Registration
- Registered in `ToolModule` with `ToolSourceInfo.BUILTIN`
- Added to General Assistant's default tool list via `MIGRATION_5_6`

## Notifications

### Channels

1. **`scheduled_task_results`**: Result notifications (completed/failed). Default importance.
2. **`scheduled_task_execution`**: Foreground service notification during execution. Low importance.

### Notification Content

- **Completed**: Title = task name, body = response preview (truncated to 100 chars). Tap opens session.
- **Failed**: Title = "Task failed: {name}", body = error message. Tap opens session if available.

## Android Manifest

### Permissions

```xml
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />
<uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_DATA_SYNC" />
```

### Receivers

```xml
<receiver android:name=".feature.schedule.alarm.ScheduledTaskReceiver" android:exported="false" />
<receiver android:name=".feature.schedule.alarm.BootCompletedReceiver"
    android:exported="true">
    <intent-filter>
        <action android:name="android.intent.action.BOOT_COMPLETED" />
        <action android:name="android.intent.action.TIMEZONE_CHANGED" />
    </intent-filter>
</receiver>
```

### Service

```xml
<service android:name="androidx.work.impl.foreground.SystemForegroundService"
    android:foregroundServiceType="dataSync"
    tools:node="merge" />
```

## UI

### ScheduledTaskListScreen

- TopAppBar with title "Scheduled Tasks" and back navigation
- LazyColumn listing tasks with:
  - Task name
  - Schedule description (e.g., "Daily at 07:00", "Every Monday at 09:00")
  - Enabled/disabled Switch
  - Last execution status indicator
- FAB to create new task

### ScheduledTaskEditScreen

- Task name text field
- Agent selector dropdown (loads from AgentRepository)
- Prompt text field (multi-line)
- Schedule type segmented button (One-Time / Daily / Weekly)
- Time picker (hour and minute)
- Date picker (visible only for ONE_TIME)
- Day-of-week selector (visible only for WEEKLY)
- Save button in TopAppBar

## Navigation

```kotlin
data object ScheduleList : Route("schedules")
data object ScheduleCreate : Route("schedules/create")
data class ScheduleEdit(val taskId: String) : Route("schedules/{taskId}") {
    companion object {
        const val PATH = "schedules/{taskId}"
        fun create(taskId: String) = "schedules/$taskId"
    }
}
```

## DI Registration

- `DatabaseModule`: `scheduledTaskDao()`
- `RepositoryModule`: `ScheduledTaskRepository` -> `ScheduledTaskRepositoryImpl`
- `FeatureModule`: AlarmScheduler, all UseCases, ViewModels

## Testing Strategy

### Unit Tests
- `NextTriggerCalculatorTest`: Verify correct next trigger time calculation for all schedule types, timezone handling, edge cases
- `ScheduledTaskRepositoryImplTest`: CRUD operations with mocked DAO
- `CreateScheduledTaskUseCaseTest`: Validates input, calculates trigger time, saves, schedules alarm
- `ScheduledTaskListViewModelTest`: State management and user actions
