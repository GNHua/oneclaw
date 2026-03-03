# RFC-019：定时任务

## 元数据
- **RFC ID**: RFC-019
- **功能**: FEAT-019（定时任务）
- **创建日期**: 2026-02-28
- **状态**: 草稿

## 概述

本 RFC 描述了 OneClaw 中定时任务功能的技术设计。用户可以使用三种计划类型（一次性、每日、每周）创建在指定时间自动运行 AI 代理的任务。

## 架构

### 调度机制：AlarmManager + WorkManager

- **AlarmManager**（`setExactAndAllowWhileIdle`）：为任务触发提供精确计时。需要 `SCHEDULE_EXACT_ALARM`（API < 33）或 `USE_EXACT_ALARM` 权限。
- **WorkManager**（`OneTimeWorkRequest`）：处理实际的任务执行。BroadcastReceiver 有 10 秒限制，但代理循环执行可能需要数分钟。WorkManager 负责处理重试和约束条件。
- **BOOT_COMPLETED Receiver**：在设备重启或时区变更后重新注册所有已启用的闹钟。
- **前台服务**：Worker 使用 `setForeground()` 配合持续通知，以防止系统终止长时间运行的任务。

### 执行流程

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

## 数据模型

### ScheduledTask（领域模型）

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

### Room 实体

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

### 数据库迁移（v4 -> v5）

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

## 组件

### NextTriggerCalculator

使用 `java.time.*` API（minSdk 26）计算下一次触发时间：

- **ONE_TIME**：将 `dateMillis` 日期与 `hour:minute` 时间组合。如果已过去，返回 null。
- **DAILY**：如果今天的 `hour:minute` 尚未到来，则为今天该时间；否则为明天的 `hour:minute`。
- **WEEKLY**：`dayOfWeek` 在 `hour:minute` 的下一次出现时间。

所有计算均遵循设备的默认时区。

### AlarmScheduler

封装 AlarmManager 操作：

- `scheduleTask(task)`：使用 `FLAG_IMMUTABLE` PendingIntent 设置 `setExactAndAllowWhileIdle()` 闹钟。使用 `task.id.hashCode()` 作为请求码。
- `cancelTask(taskId)`：取消给定任务 ID 的 PendingIntent。
- `rescheduleAllEnabled(tasks)`：批量重新注册所有已启用任务的闹钟。在启动和时区变更时使用。

### ScheduledTaskReceiver（BroadcastReceiver）

接收闹钟 Intent 并将 WorkManager `OneTimeWorkRequest` 加入队列：

- 从 Intent extras 中提取 `taskId`
- 设置 `NetworkType.CONNECTED` 约束条件
- 使用工作名称 `"scheduled_task_{taskId}"` 和 `ExistingWorkPolicy.REPLACE` 策略

### BootCompletedReceiver（BroadcastReceiver）

监听 `ACTION_BOOT_COMPLETED` 和 `ACTION_TIMEZONE_CHANGED`：

- 使用 `goAsync()` 和协程作用域获取所有已启用的任务
- 调用 `AlarmScheduler.rescheduleAllEnabled()`

### ScheduledTaskWorker（CoroutineWorker）

执行定时任务：

1. 在 `SCHEDULED_TASK_EXECUTION_CHANNEL_ID` 上调用 `setForeground()` 并附带持续通知
2. 从仓库读取任务配置
3. 将任务状态更新为 `RUNNING`
4. 通过 `CreateSessionUseCase` 创建新会话
5. 执行 `SendMessageUseCase.execute()` 并收集 `Flow<ChatEvent>`：
   - `ChatEvent.StreamingText` -> 累积文本
   - `ChatEvent.ResponseComplete` -> 标记为成功
   - `ChatEvent.Error` -> 标记为失败
6. 使用执行结果（状态、会话 ID、时间戳）更新任务
7. 通过 `NotificationHelper` 发送通知
8. 对于重复任务：计算下一次触发时间并重新注册闹钟
9. 对于 ONE_TIME 任务：禁用该任务

## 内置工具：schedule_task

一个 Kotlin 内置工具（遵循 `LoadSkillTool` 模式），使 AI 代理能够通过对话创建定时任务。

### 参数
| 参数 | 类型 | 必填 | 描述 |
|------|------|------|------|
| name | string | 是 | 任务名称 |
| prompt | string | 是 | 任务触发时发送的提示 |
| schedule_type | string | 是 | "one_time"、"daily" 或 "weekly" |
| hour | integer | 是 | 小时（0-23） |
| minute | integer | 是 | 分钟（0-59） |
| day_of_week | string | 否 | 每周任务的星期名称（例如 "monday"） |
| date | string | 否 | 一次性任务的日期，格式为 YYYY-MM-DD |

### 注册
- 在 `ToolModule` 中以 `ToolSourceInfo.BUILTIN` 注册
- 通过 `MIGRATION_5_6` 添加到通用助手的默认工具列表中

## 通知

### 通知渠道

1. **`scheduled_task_results`**：结果通知（已完成/已失败）。默认重要性。
2. **`scheduled_task_execution`**：执行期间的前台服务通知。低重要性。

### 通知内容

- **已完成**：标题 = 任务名称，正文 = 响应预览（截断至 100 个字符）。点击打开会话。
- **已失败**：标题 = "Task failed: {name}"，正文 = 错误消息。如果会话可用，点击打开会话。

## Android 清单

### 权限

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

## 用户界面

### ScheduledTaskListScreen

- TopAppBar，标题为"Scheduled Tasks"，包含返回导航
- LazyColumn 列出所有任务，每项包含：
  - 任务名称
  - 计划描述（例如："Daily at 07:00"、"Every Monday at 09:00"）
  - 启用/禁用 Switch
  - 最后执行状态指示器
- FAB 用于创建新任务

### ScheduledTaskEditScreen

- 任务名称文本字段
- 代理选择器下拉菜单（从 AgentRepository 加载）
- 提示文本字段（多行）
- 计划类型分段按钮（One-Time / Daily / Weekly）
- 时间选择器（小时和分钟）
- 日期选择器（仅对 ONE_TIME 可见）
- 星期几选择器（仅对 WEEKLY 可见）
- TopAppBar 中的保存按钮

## 导航

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

## 依赖注入注册

- `DatabaseModule`：`scheduledTaskDao()`
- `RepositoryModule`：`ScheduledTaskRepository` -> `ScheduledTaskRepositoryImpl`
- `FeatureModule`：AlarmScheduler、所有 UseCases、ViewModels

## 测试策略

### 单元测试
- `NextTriggerCalculatorTest`：验证所有计划类型的正确下一次触发时间计算、时区处理及边界情况
- `ScheduledTaskRepositoryImplTest`：使用模拟 DAO 进行 CRUD 操作
- `CreateScheduledTaskUseCaseTest`：验证输入、计算触发时间、保存并调度闹钟
- `ScheduledTaskListViewModelTest`：状态管理和用户操作
