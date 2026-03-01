# RFC-037: Exact Alarm Permission Dialog

## Metadata
- **RFC ID**: RFC-037
- **Feature**: FEAT-037 (Exact Alarm Permission Dialog)
- **Extends**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md)
- **Depends On**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft

## Overview

### Background

Android 12 (API 31) introduced `SCHEDULE_EXACT_ALARM` as a special permission for exact alarm scheduling. On Android 13+ (API 33), this permission is NOT granted by default. The alternative `USE_EXACT_ALARM` is auto-granted but Google Play restricts it to alarm clock, timer, and calendar apps. Since OneClawShadow is an AI Agent runtime that uses exact alarms for scheduled tasks, it must:

1. Use `SCHEDULE_EXACT_ALARM` (not `USE_EXACT_ALARM`) to comply with Play Store policy
2. Check `AlarmManager.canScheduleExactAlarms()` at runtime before scheduling
3. Guide users to system settings to grant the permission when needed

### Goals
1. Remove `USE_EXACT_ALARM` from manifest and use `SCHEDULE_EXACT_ALARM` for all API levels
2. Add runtime permission check in `AlarmScheduler` before calling `setExactAndAllowWhileIdle()`
3. Show a Material 3 dialog in UI contexts when the permission is missing
4. Return a clear error in tool contexts when the permission is missing
5. Listen for permission state changes to reschedule alarms when permission is granted

### Non-Goals
- Proactive permission request on app startup
- Fallback to inexact alarms (`setAndAllowWhileIdle()`)
- In-app settings toggle for exact alarm permission

## Technical Design

### Architecture Overview

```
┌─────────────────────────────────────────────────┐
│                   Manifest                       │
│  SCHEDULE_EXACT_ALARM (all API levels)           │
│  (USE_EXACT_ALARM removed)                       │
└─────────────────────────────────────────────────┘

┌─────────────────────────────────────────────────┐
│             ExactAlarmHelper                     │
│  - canScheduleExactAlarms(): Boolean             │
│  - buildSettingsIntent(): Intent                 │
└─────────────────────────────────────────────────┘
        │                        │
        v                        v
┌──────────────────┐  ┌──────────────────────────┐
│  AlarmScheduler  │  │ ExactAlarmPermission      │
│  (checks before  │  │ StateReceiver             │
│   scheduling)    │  │ (reschedules on grant)    │
└──────────────────┘  └──────────────────────────┘
        │
        v
┌──────────────────────────────────────────────────┐
│           Integration Points                      │
│  - ScheduledTaskEditViewModel (UI dialog)         │
│  - CreateScheduledTaskUseCase (returns status)    │
│  - CreateScheduledTaskTool (error message)        │
│  - BootCompletedReceiver (guard rescheduling)     │
└──────────────────────────────────────────────────┘
```

### Core Components

**New:**
- `ExactAlarmHelper` -- utility to check permission and build settings intent
- `ExactAlarmPermissionStateReceiver` -- BroadcastReceiver for permission state changes
- `ExactAlarmPermissionDialog` -- Composable dialog

**Modified:**
- `AndroidManifest.xml` -- permission declarations and receiver registration
- `AlarmScheduler` -- add `canScheduleExactAlarms()` check, return scheduling result
- `ScheduledTaskEditViewModel` -- expose permission state, trigger dialog
- `ScheduledTaskEditScreen` -- show dialog
- `CreateScheduledTaskUseCase` -- return warning when alarm not registered
- `CreateScheduledTaskTool` -- include permission error in tool result
- `BootCompletedReceiver` -- guard with permission check

**Unchanged:**
- `ScheduledTaskReceiver`
- `ScheduledTaskWorker`
- `NextTriggerCalculator`
- Room entities, DAOs, repositories

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── AndroidManifest.xml                                      # MODIFIED
├── kotlin/com/oneclaw/shadow/
│   ├── feature/schedule/
│   │   ├── ScheduledTaskEditScreen.kt                       # MODIFIED
│   │   ├── ScheduledTaskEditViewModel.kt                    # MODIFIED
│   │   ├── ScheduledTaskUiState.kt                          # MODIFIED
│   │   ├── alarm/
│   │   │   ├── AlarmScheduler.kt                            # MODIFIED
│   │   │   ├── BootCompletedReceiver.kt                     # MODIFIED
│   │   │   ├── ExactAlarmHelper.kt                          # NEW
│   │   │   └── ExactAlarmPermissionStateReceiver.kt         # NEW
│   │   └── usecase/
│   │       └── CreateScheduledTaskUseCase.kt                # MODIFIED
│   └── tool/builtin/
│       └── CreateScheduledTaskTool.kt                       # MODIFIED
```

### Component Implementation

#### 1. ExactAlarmHelper

Centralized utility for exact alarm permission checks.

```kotlin
package com.oneclaw.shadow.feature.schedule.alarm

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

class ExactAlarmHelper(private val context: Context) {

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Returns true if the app can schedule exact alarms.
     * Always returns true on Android < 12 (API 30 and below).
     */
    fun canScheduleExactAlarms(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            alarmManager.canScheduleExactAlarms()
        } else {
            true
        }
    }

    /**
     * Creates an intent to open the system settings page for exact alarm permission.
     * Only meaningful on Android 12+.
     */
    fun buildSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        } else {
            // Fallback: open general app settings (should not be reached)
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        }
    }
}
```

#### 2. AlarmScheduler Modifications

Return a result indicating whether the alarm was registered.

```kotlin
package com.oneclaw.shadow.feature.schedule.alarm

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.oneclaw.shadow.core.model.ScheduledTask

class AlarmScheduler(
    private val context: Context,
    private val exactAlarmHelper: ExactAlarmHelper
) {
    companion object {
        const val EXTRA_TASK_ID = "scheduled_task_id"
        const val ACTION_TRIGGER = "com.oneclaw.shadow.SCHEDULED_TASK_TRIGGER"
    }

    private val alarmManager: AlarmManager
        get() = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    /**
     * Schedules an exact alarm for the given task.
     * Returns true if the alarm was registered, false if the exact alarm
     * permission is not granted.
     */
    fun scheduleTask(task: ScheduledTask): Boolean {
        val triggerAt = task.nextTriggerAt ?: return false
        if (!exactAlarmHelper.canScheduleExactAlarms()) {
            return false
        }
        val pendingIntent = createPendingIntent(task.id)
        alarmManager.setExactAndAllowWhileIdle(
            AlarmManager.RTC_WAKEUP,
            triggerAt,
            pendingIntent
        )
        return true
    }

    fun cancelTask(taskId: String) {
        val pendingIntent = createPendingIntent(taskId)
        alarmManager.cancel(pendingIntent)
    }

    fun rescheduleAllEnabled(tasks: List<ScheduledTask>) {
        if (!exactAlarmHelper.canScheduleExactAlarms()) return
        for (task in tasks) {
            if (task.isEnabled && task.nextTriggerAt != null) {
                scheduleTask(task)
            }
        }
    }

    private fun createPendingIntent(taskId: String): PendingIntent {
        val intent = Intent(context, ScheduledTaskReceiver::class.java).apply {
            action = ACTION_TRIGGER
            putExtra(EXTRA_TASK_ID, taskId)
        }
        return PendingIntent.getBroadcast(
            context,
            taskId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
```

#### 3. ExactAlarmPermissionStateReceiver

Receives system broadcast when the user grants or revokes the exact alarm permission in system settings. Reschedules all enabled tasks when granted.

```kotlin
package com.oneclaw.shadow.feature.schedule.alarm

import android.app.AlarmManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class ExactAlarmPermissionStateReceiver : BroadcastReceiver(), KoinComponent {

    private val scheduledTaskRepository: ScheduledTaskRepository by inject()
    private val alarmScheduler: AlarmScheduler by inject()

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != AlarmManager.ACTION_SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED) return

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val tasks = scheduledTaskRepository.getAllTasks().first()
                alarmScheduler.rescheduleAllEnabled(tasks)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
```

#### 4. ExactAlarmPermissionDialog

Material 3 AlertDialog shown when exact alarm permission is needed.

```kotlin
package com.oneclaw.shadow.feature.schedule

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable

@Composable
fun ExactAlarmPermissionDialog(
    onGoToSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exact Alarm Permission Required") },
        text = {
            Text(
                "To run scheduled tasks at the exact times you set, " +
                    "this app needs the \"Alarms & reminders\" permission. " +
                    "Without it, your scheduled tasks may not trigger on time.\n\n" +
                    "Tap \"Go to Settings\" to grant the permission."
            )
        },
        confirmButton = {
            TextButton(onClick = onGoToSettings) {
                Text("Go to Settings")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
```

#### 5. ScheduledTaskEditUiState Modifications

Add a flag to control dialog visibility.

```kotlin
// Add to ScheduledTaskEditUiState:
val showExactAlarmDialog: Boolean = false
```

#### 6. ScheduledTaskEditViewModel Modifications

Check permission before saving. Expose dialog state.

```kotlin
// Add to ScheduledTaskEditViewModel:

private val exactAlarmHelper: ExactAlarmHelper  // injected via constructor

fun save() {
    val state = _uiState.value
    if (state.isSaving) return

    // Check exact alarm permission before saving
    if (!exactAlarmHelper.canScheduleExactAlarms()) {
        _uiState.value = state.copy(showExactAlarmDialog = true)
        return
    }

    performSave()
}

fun dismissExactAlarmDialog() {
    _uiState.value = _uiState.value.copy(showExactAlarmDialog = false)
}

fun onExactAlarmDialogSettings() {
    _uiState.value = _uiState.value.copy(showExactAlarmDialog = false)
    // The screen will launch the settings intent
}

fun saveWithoutAlarm() {
    // Called when user dismisses the dialog -- save the task but alarm won't be registered
    _uiState.value = _uiState.value.copy(showExactAlarmDialog = false)
    performSave()
}

private fun performSave() {
    val state = _uiState.value
    _uiState.value = state.copy(isSaving = true, errorMessage = null)

    viewModelScope.launch {
        // ... existing save logic ...
    }
}
```

#### 7. ScheduledTaskEditScreen Modifications

Show the dialog and handle the settings intent.

```kotlin
// Add inside ScheduledTaskEditScreen composable:

val context = LocalContext.current
val exactAlarmHelper: ExactAlarmHelper = remember { get() }  // Koin

if (uiState.showExactAlarmDialog) {
    ExactAlarmPermissionDialog(
        onGoToSettings = {
            viewModel.onExactAlarmDialogSettings()
            context.startActivity(exactAlarmHelper.buildSettingsIntent())
        },
        onDismiss = {
            viewModel.saveWithoutAlarm()
        }
    )
}

// Re-check permission when returning from settings
val lifecycleOwner = LocalLifecycleOwner.current
DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
        if (event == Lifecycle.Event.ON_RESUME) {
            // If user just returned from settings and permission is now granted,
            // no additional action needed -- the ExactAlarmPermissionStateReceiver
            // handles rescheduling existing tasks.
        }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
}
```

#### 8. CreateScheduledTaskUseCase Modifications

Handle the case where alarm cannot be registered. The task is still saved, but the caller is informed.

```kotlin
class CreateScheduledTaskUseCase(
    private val repository: ScheduledTaskRepository,
    private val alarmScheduler: AlarmScheduler
) {
    /**
     * Result of task creation, including whether the alarm was registered.
     */
    data class CreateResult(
        val task: ScheduledTask,
        val alarmRegistered: Boolean
    )

    suspend operator fun invoke(task: ScheduledTask): AppResult<CreateResult> {
        // ... existing validation ...

        val created = repository.createTask(taskWithTrigger)
        val alarmRegistered = alarmScheduler.scheduleTask(created)

        return AppResult.Success(CreateResult(created, alarmRegistered))
    }
}
```

#### 9. CreateScheduledTaskTool Modifications

When the alarm is not registered due to missing permission, include a warning in the tool result.

```kotlin
// In CreateScheduledTaskTool.execute():

when (result) {
    is AppResult.Success -> {
        val warning = if (!result.data.alarmRegistered) {
            "\n\nWarning: Exact alarm permission is not granted. " +
                "The task has been saved but will not trigger at the scheduled time. " +
                "Please ask the user to go to Settings > Apps > OneClawShadow > " +
                "Alarms & reminders to enable the permission."
        } else ""

        ToolResult.Success("Task '${result.data.task.name}' created...$warning")
    }
    // ...
}
```

#### 10. BootCompletedReceiver Modifications

Guard rescheduling with permission check.

```kotlin
// In BootCompletedReceiver.onReceive():

class BootCompletedReceiver : BroadcastReceiver(), KoinComponent {
    private val alarmScheduler: AlarmScheduler by inject()
    // ...

    override fun onReceive(context: Context, intent: Intent) {
        // alarmScheduler.rescheduleAllEnabled() already checks
        // canScheduleExactAlarms() internally, so no additional
        // guard is needed here. The AlarmScheduler modification
        // handles this transparently.
    }
}
```

### AndroidManifest.xml Changes

```xml
<!-- BEFORE -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- AFTER -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<!-- New receiver -->
<receiver
    android:name=".feature.schedule.alarm.ExactAlarmPermissionStateReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
    </intent-filter>
</receiver>
```

## DI Registration

### AppModule / FeatureModule

```kotlin
// ExactAlarmHelper
single { ExactAlarmHelper(androidContext()) }

// AlarmScheduler now depends on ExactAlarmHelper
single { AlarmScheduler(androidContext(), get()) }
```

No changes to `ToolModule`. The `ExactAlarmHelper` dependency flows through `AlarmScheduler`, which is already injected into use cases and tools.

### ScheduledTaskEditViewModel

```kotlin
// Update ViewModel factory registration to include ExactAlarmHelper
viewModel { params ->
    ScheduledTaskEditViewModel(
        savedStateHandle = params.get(),
        agentRepository = get(),
        scheduledTaskRepository = get(),
        createUseCase = get(),
        updateUseCase = get(),
        exactAlarmHelper = get()  // NEW
    )
}
```

## Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| Permission not granted (UI) | User has not granted `SCHEDULE_EXACT_ALARM` | Show `ExactAlarmPermissionDialog`. Task is saved if user dismisses; alarm is not registered. |
| Permission not granted (tool) | Same, but from AI agent context | Return warning in `ToolResult` with instructions to open settings. |
| Permission revoked while app running | User revoked permission in system settings | `ExactAlarmPermissionStateReceiver` fires. `rescheduleAllEnabled()` becomes a no-op since `canScheduleExactAlarms()` returns false. |
| Settings intent not available | Very old custom ROM | Fallback to `ACTION_APPLICATION_DETAILS_SETTINGS` in `ExactAlarmHelper.buildSettingsIntent()`. |

## Testing Strategy

### Unit Tests

**ExactAlarmHelperTest:**
- Verify `canScheduleExactAlarms()` returns true on API < 31
- Verify `canScheduleExactAlarms()` delegates to `AlarmManager.canScheduleExactAlarms()` on API 31+
- Verify `buildSettingsIntent()` returns `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` intent on API 31+

**AlarmSchedulerTest:**
- Verify `scheduleTask()` returns false when `canScheduleExactAlarms()` returns false
- Verify `scheduleTask()` returns true and calls `setExactAndAllowWhileIdle()` when permission is granted
- Verify `rescheduleAllEnabled()` does nothing when permission is not granted

**CreateScheduledTaskUseCaseTest:**
- Verify `CreateResult.alarmRegistered` is true when permission is granted
- Verify `CreateResult.alarmRegistered` is false when permission is denied
- Verify the task is still saved in the database when alarm registration fails

**ScheduledTaskEditViewModelTest:**
- Verify `save()` sets `showExactAlarmDialog = true` when permission is not granted
- Verify `saveWithoutAlarm()` proceeds with save and alarm is not registered
- Verify `save()` calls `performSave()` directly when permission is granted

### Manual Testing (Layer 2)

1. Install app on Android 13+ device. Revoke exact alarm permission in system settings. Create a scheduled task. Verify dialog appears.
2. Tap "Go to Settings" in dialog. Grant permission. Return to app. Create another task. Verify no dialog.
3. Create a scheduled task, then revoke permission in settings. Verify the task still exists but the alarm does not fire.
4. Grant permission again. Verify `ExactAlarmPermissionStateReceiver` reschedules alarms.

## Implementation Steps

### Phase 1: Manifest and Helper
1. [ ] Remove `USE_EXACT_ALARM` from manifest
2. [ ] Remove `maxSdkVersion="32"` from `SCHEDULE_EXACT_ALARM`
3. [ ] Create `ExactAlarmHelper` in `feature/schedule/alarm/`
4. [ ] Register `ExactAlarmHelper` in DI

### Phase 2: AlarmScheduler
1. [ ] Add `ExactAlarmHelper` dependency to `AlarmScheduler` constructor
2. [ ] Change `scheduleTask()` return type from `Unit` to `Boolean`
3. [ ] Add `canScheduleExactAlarms()` check in `scheduleTask()`
4. [ ] Add `canScheduleExactAlarms()` guard in `rescheduleAllEnabled()`
5. [ ] Update DI registration for `AlarmScheduler`
6. [ ] Update all call sites for `scheduleTask()` return type change

### Phase 3: Permission State Receiver
1. [ ] Create `ExactAlarmPermissionStateReceiver` in `feature/schedule/alarm/`
2. [ ] Register receiver in `AndroidManifest.xml`

### Phase 4: UI Integration
1. [ ] Add `showExactAlarmDialog` to `ScheduledTaskEditUiState`
2. [ ] Add `ExactAlarmHelper` to `ScheduledTaskEditViewModel` constructor
3. [ ] Add permission check in `ScheduledTaskEditViewModel.save()`
4. [ ] Add `dismissExactAlarmDialog()`, `onExactAlarmDialogSettings()`, `saveWithoutAlarm()` methods
5. [ ] Create `ExactAlarmPermissionDialog` composable
6. [ ] Integrate dialog into `ScheduledTaskEditScreen`

### Phase 5: Use Case and Tool
1. [ ] Add `CreateResult` data class to `CreateScheduledTaskUseCase`
2. [ ] Update `CreateScheduledTaskUseCase` to return `alarmRegistered` status
3. [ ] Update `CreateScheduledTaskTool` to include warning when alarm not registered
4. [ ] Update `ScheduledTaskEditViewModel` for `CreateResult` type change

### Phase 6: Tests
1. [ ] Write unit tests for `ExactAlarmHelper`
2. [ ] Write unit tests for `AlarmScheduler` permission check
3. [ ] Write unit tests for `CreateScheduledTaskUseCase` alarm registration result
4. [ ] Write unit tests for `ScheduledTaskEditViewModel` dialog flow

## Alternatives Considered

### 1. Use `USE_EXACT_ALARM` and Apply for Play Store Exception
**Approach**: Keep `USE_EXACT_ALARM` in manifest and apply for a Play Store policy exception.
**Rejected**: Exception process is uncertain and could delay submission. `SCHEDULE_EXACT_ALARM` with a user-facing dialog is the recommended approach for non-alarm-clock apps.

### 2. Fall Back to Inexact Alarms
**Approach**: When exact alarm permission is denied, use `setAndAllowWhileIdle()` instead.
**Deferred**: Adds complexity with minimal benefit. Inexact alarms on Android 12+ can be delayed by 10+ minutes, making them unreliable for scheduled tasks. Better to clearly communicate the permission requirement.

### 3. Request Permission on App Startup
**Approach**: Check and show the dialog immediately when the app opens.
**Rejected**: The permission is only relevant for scheduled tasks. Showing the dialog on startup would confuse users who don't use scheduling and creates a poor first-run experience.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
