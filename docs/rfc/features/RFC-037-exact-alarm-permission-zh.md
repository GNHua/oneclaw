# RFC-037: 精确闹钟权限对话框

## 元数据
- **RFC ID**: RFC-037
- **功能**: FEAT-037（精确闹钟权限对话框）
- **扩展自**: [RFC-019（定时任务）](RFC-019-scheduled-tasks.md)
- **依赖**: [RFC-019（定时任务）](RFC-019-scheduled-tasks.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft

## 概述

### 背景

Android 12（API 31）将 `SCHEDULE_EXACT_ALARM` 引入为精确闹钟调度的特殊权限。在 Android 13+（API 33）上，该权限默认**不**授予。替代方案 `USE_EXACT_ALARM` 虽可自动授权，但 Google Play 将其限制为仅适用于闹钟、计时器和日历类应用。由于 OneClawShadow 是使用精确闹钟执行定时任务的 AI Agent 运行时，它必须：

1. 使用 `SCHEDULE_EXACT_ALARM`（而非 `USE_EXACT_ALARM`）以符合 Play Store 政策
2. 在调度前于运行时检查 `AlarmManager.canScheduleExactAlarms()`
3. 在需要时引导用户前往系统设置授予权限

### 目标
1. 从 manifest 中移除 `USE_EXACT_ALARM`，对所有 API 级别统一使用 `SCHEDULE_EXACT_ALARM`
2. 在 `AlarmScheduler` 中调用 `setExactAndAllowWhileIdle()` 前加入运行时权限检查
3. 在 UI 上下文中权限缺失时显示 Material 3 对话框
4. 在工具上下文中权限缺失时返回清晰的错误信息
5. 监听权限状态变更，在权限被授予时重新调度闹钟

### 非目标
- 应用启动时主动请求权限
- 降级使用非精确闹钟（`setAndAllowWhileIdle()`）
- 应用内精确闹钟权限设置开关

## 技术设计

### 架构概览

```
┌─────────────────────────────────────────────────┐
│                   Manifest                       │
│  SCHEDULE_EXACT_ALARM（所有 API 级别）            │
│  （已移除 USE_EXACT_ALARM）                       │
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
│  （调度前检查）   │  │ StateReceiver             │
│                  │  │ （授权后重新调度）          │
└──────────────────┘  └──────────────────────────┘
        │
        v
┌──────────────────────────────────────────────────┐
│           集成点                                  │
│  - ScheduledTaskEditViewModel（UI 对话框）         │
│  - CreateScheduledTaskUseCase（返回状态）          │
│  - CreateScheduledTaskTool（错误信息）             │
│  - BootCompletedReceiver（调度前守卫检查）          │
└──────────────────────────────────────────────────┘
```

### 核心组件

**新增：**
- `ExactAlarmHelper` -- 检查权限并构建设置跳转 Intent 的工具类
- `ExactAlarmPermissionStateReceiver` -- 监听权限状态变更的 BroadcastReceiver
- `ExactAlarmPermissionDialog` -- Composable 对话框

**修改：**
- `AndroidManifest.xml` -- 权限声明及接收器注册
- `AlarmScheduler` -- 添加 `canScheduleExactAlarms()` 检查，返回调度结果
- `ScheduledTaskEditViewModel` -- 暴露权限状态，触发对话框
- `ScheduledTaskEditScreen` -- 显示对话框
- `CreateScheduledTaskUseCase` -- 闹钟未注册时返回警告
- `CreateScheduledTaskTool` -- 在工具结果中包含权限错误信息
- `BootCompletedReceiver` -- 添加权限检查守卫

**不变：**
- `ScheduledTaskReceiver`
- `ScheduledTaskWorker`
- `NextTriggerCalculator`
- Room entities、DAOs、repositories

## 详细设计

### 目录结构（新增及修改文件）

```
app/src/main/
├── AndroidManifest.xml                                      # 修改
├── kotlin/com/oneclaw/shadow/
│   ├── feature/schedule/
│   │   ├── ScheduledTaskEditScreen.kt                       # 修改
│   │   ├── ScheduledTaskEditViewModel.kt                    # 修改
│   │   ├── ScheduledTaskUiState.kt                          # 修改
│   │   ├── alarm/
│   │   │   ├── AlarmScheduler.kt                            # 修改
│   │   │   ├── BootCompletedReceiver.kt                     # 修改
│   │   │   ├── ExactAlarmHelper.kt                          # 新增
│   │   │   └── ExactAlarmPermissionStateReceiver.kt         # 新增
│   │   └── usecase/
│   │       └── CreateScheduledTaskUseCase.kt                # 修改
│   └── tool/builtin/
│       └── CreateScheduledTaskTool.kt                       # 修改
```

### 组件实现

#### 1. ExactAlarmHelper

用于精确闹钟权限检查的集中工具类。

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

#### 2. AlarmScheduler 修改

返回标识闹钟是否已注册的结果。

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

接收用户在系统设置中授予或撤销精确闹钟权限时系统发出的广播。权限被授予时重新调度所有已启用任务。

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

在需要精确闹钟权限时显示的 Material 3 AlertDialog。

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

#### 5. ScheduledTaskEditUiState 修改

添加控制对话框可见性的标志位。

```kotlin
// Add to ScheduledTaskEditUiState:
val showExactAlarmDialog: Boolean = false
```

#### 6. ScheduledTaskEditViewModel 修改

保存前检查权限，并暴露对话框状态。

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

#### 7. ScheduledTaskEditScreen 修改

显示对话框并处理设置跳转 Intent。

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

#### 8. CreateScheduledTaskUseCase 修改

处理闹钟无法注册的情况。任务仍会保存，但调用方会收到相应通知。

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

#### 9. CreateScheduledTaskTool 修改

当闹钟因权限缺失未能注册时，在工具结果中包含警告信息。

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

#### 10. BootCompletedReceiver 修改

在重新调度前添加权限检查守卫。

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

### AndroidManifest.xml 变更

```xml
<!-- 修改前 -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM"
    android:maxSdkVersion="32" />
<uses-permission android:name="android.permission.USE_EXACT_ALARM" />

<!-- 修改后 -->
<uses-permission android:name="android.permission.SCHEDULE_EXACT_ALARM" />

<!-- 新增接收器 -->
<receiver
    android:name=".feature.schedule.alarm.ExactAlarmPermissionStateReceiver"
    android:exported="false">
    <intent-filter>
        <action android:name="android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED" />
    </intent-filter>
</receiver>
```

## DI 注册

### AppModule / FeatureModule

```kotlin
// ExactAlarmHelper
single { ExactAlarmHelper(androidContext()) }

// AlarmScheduler now depends on ExactAlarmHelper
single { AlarmScheduler(androidContext(), get()) }
```

`ToolModule` 无需修改。`ExactAlarmHelper` 依赖通过 `AlarmScheduler` 传递，而 `AlarmScheduler` 已注入至 use cases 和 tools 中。

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

## 错误处理

| 错误 | 原因 | 处理方式 |
|------|------|----------|
| 权限未授权（UI 上下文） | 用户未授予 `SCHEDULE_EXACT_ALARM` | 显示 `ExactAlarmPermissionDialog`。用户关闭对话框时任务仍保存，但闹钟不注册。 |
| 权限未授权（工具上下文） | 同上，但来自 AI Agent 上下文 | 在 `ToolResult` 中返回警告信息，附带打开设置的指引。 |
| 应用运行时权限被撤销 | 用户在系统设置中撤销权限 | `ExactAlarmPermissionStateReceiver` 触发。`rescheduleAllEnabled()` 因 `canScheduleExactAlarms()` 返回 false 而变为空操作。 |
| 设置 Intent 不可用 | 高度定制的旧版 ROM | 在 `ExactAlarmHelper.buildSettingsIntent()` 中降级使用 `ACTION_APPLICATION_DETAILS_SETTINGS`。 |

## 测试策略

### 单元测试

**ExactAlarmHelperTest:**
- 验证 API < 31 时 `canScheduleExactAlarms()` 返回 true
- 验证 API 31+ 时 `canScheduleExactAlarms()` 委托给 `AlarmManager.canScheduleExactAlarms()`
- 验证 API 31+ 时 `buildSettingsIntent()` 返回 `ACTION_REQUEST_SCHEDULE_EXACT_ALARM` Intent

**AlarmSchedulerTest:**
- 验证 `canScheduleExactAlarms()` 返回 false 时 `scheduleTask()` 返回 false
- 验证权限已授予时 `scheduleTask()` 返回 true 并调用 `setExactAndAllowWhileIdle()`
- 验证权限未授予时 `rescheduleAllEnabled()` 不执行任何操作

**CreateScheduledTaskUseCaseTest:**
- 验证权限已授予时 `CreateResult.alarmRegistered` 为 true
- 验证权限被拒绝时 `CreateResult.alarmRegistered` 为 false
- 验证闹钟注册失败时任务仍保存至数据库

**ScheduledTaskEditViewModelTest:**
- 验证权限未授予时 `save()` 将 `showExactAlarmDialog` 设置为 true
- 验证 `saveWithoutAlarm()` 继续执行保存，且闹钟不注册
- 验证权限已授予时 `save()` 直接调用 `performSave()`

### 手动测试（第 2 层）

1. 在 Android 13+ 设备上安装应用。在系统设置中撤销精确闹钟权限。创建定时任务。验证对话框出现。
2. 在对话框中点击"前往设置"并授权。返回应用。再次创建任务。验证无对话框出现。
3. 创建定时任务后在系统设置中撤销权限。验证任务仍存在但闹钟不触发。
4. 重新授权。验证 `ExactAlarmPermissionStateReceiver` 重新调度闹钟。

## 实现步骤

### 阶段一：Manifest 与 Helper
1. [ ] 从 manifest 中移除 `USE_EXACT_ALARM`
2. [ ] 移除 `SCHEDULE_EXACT_ALARM` 的 `maxSdkVersion="32"` 限制
3. [ ] 在 `feature/schedule/alarm/` 中创建 `ExactAlarmHelper`
4. [ ] 在 DI 中注册 `ExactAlarmHelper`

### 阶段二：AlarmScheduler
1. [ ] 向 `AlarmScheduler` 构造函数添加 `ExactAlarmHelper` 依赖
2. [ ] 将 `scheduleTask()` 返回类型从 `Unit` 改为 `Boolean`
3. [ ] 在 `scheduleTask()` 中添加 `canScheduleExactAlarms()` 检查
4. [ ] 在 `rescheduleAllEnabled()` 中添加 `canScheduleExactAlarms()` 守卫
5. [ ] 更新 `AlarmScheduler` 的 DI 注册
6. [ ] 更新所有 `scheduleTask()` 调用处以适配返回类型变更

### 阶段三：权限状态接收器
1. [ ] 在 `feature/schedule/alarm/` 中创建 `ExactAlarmPermissionStateReceiver`
2. [ ] 在 `AndroidManifest.xml` 中注册接收器

### 阶段四：UI 集成
1. [ ] 向 `ScheduledTaskEditUiState` 添加 `showExactAlarmDialog` 字段
2. [ ] 向 `ScheduledTaskEditViewModel` 构造函数添加 `ExactAlarmHelper`
3. [ ] 在 `ScheduledTaskEditViewModel.save()` 中添加权限检查
4. [ ] 添加 `dismissExactAlarmDialog()`、`onExactAlarmDialogSettings()`、`saveWithoutAlarm()` 方法
5. [ ] 创建 `ExactAlarmPermissionDialog` Composable
6. [ ] 将对话框集成至 `ScheduledTaskEditScreen`

### 阶段五：Use Case 与 Tool
1. [ ] 向 `CreateScheduledTaskUseCase` 添加 `CreateResult` 数据类
2. [ ] 更新 `CreateScheduledTaskUseCase` 以返回 `alarmRegistered` 状态
3. [ ] 更新 `CreateScheduledTaskTool`，在闹钟未注册时包含警告信息
4. [ ] 更新 `ScheduledTaskEditViewModel` 以适配 `CreateResult` 类型变更

### 阶段六：测试
1. [ ] 为 `ExactAlarmHelper` 编写单元测试
2. [ ] 为 `AlarmScheduler` 权限检查编写单元测试
3. [ ] 为 `CreateScheduledTaskUseCase` 闹钟注册结果编写单元测试
4. [ ] 为 `ScheduledTaskEditViewModel` 对话框流程编写单元测试

## 备选方案

### 1. 使用 `USE_EXACT_ALARM` 并申请 Play Store 例外
**方案**：在 manifest 中保留 `USE_EXACT_ALARM`，并向 Play Store 申请政策例外。
**拒绝原因**：例外审批流程不确定，可能延误上架。对于非闹钟类应用，官方推荐方案是使用 `SCHEDULE_EXACT_ALARM` 配合用户引导对话框。

### 2. 降级使用非精确闹钟
**方案**：精确闹钟权限被拒绝时，改用 `setAndAllowWhileIdle()`。
**暂缓原因**：增加复杂度而收益甚微。Android 12+ 上的非精确闹钟可能延迟 10 分钟以上，对定时任务而言可靠性过低。明确告知用户权限要求是更好的选择。

### 3. 应用启动时请求权限
**方案**：应用打开时立即检查并显示对话框。
**拒绝原因**：该权限仅与定时任务相关。在启动时显示对话框会让不使用定时功能的用户感到困惑，影响首次启动体验。

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
