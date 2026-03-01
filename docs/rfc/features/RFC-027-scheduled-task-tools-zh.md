# RFC-027：定时任务管理工具

## 元数据
- **RFC ID**: RFC-027
- **功能**: FEAT-027（定时任务管理工具）
- **扩展自**: [RFC-019（定时任务）](RFC-019-scheduled-tasks.md)
- **依赖**: [RFC-019（定时任务）](RFC-019-scheduled-tasks.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿

## 概述

RFC-019 引入了 `schedule_task` 内置工具，用于从对话中创建定时任务。本 RFC 新增四个内置工具，使 AI 代理能够对定时任务进行完整的生命周期管理：

| 工具 | 操作 |
|------|--------|
| `list_scheduled_tasks` | 列出所有任务及其详情 |
| `run_scheduled_task` | 触发立即异步执行 |
| `update_scheduled_task` | 部分更新任务字段 |
| `delete_scheduled_task` | 取消闹钟并删除任务 |

所有工具遵循 RFC-019 中 `CreateScheduledTaskTool` 所建立的相同模式：实现 `Tool` 接口，以 `ToolSourceInfo.BUILTIN` 注册到 `ToolModule`，并将业务逻辑委托给 use case。

## 工具定义

### list_scheduled_tasks

列出所有定时任务及其当前状态。

**参数**：无

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| _(none)_ | | | |

**实现**：`ListScheduledTasksTool`

```kotlin
class ListScheduledTasksTool(
    private val scheduledTaskRepository: ScheduledTaskRepository
) : Tool
```

**行为**：
1. 调用 `scheduledTaskRepository.getAllTasks().first()` 获取所有任务的快照
2. 如果列表为空，返回消息："No scheduled tasks configured."
3. 否则，将每个任务格式化为包含以下内容的文本块：
   - `id`：任务 ID（用于其他管理工具）
   - `name`：任务名称
   - `schedule`：人类可读的计划描述（例如："Daily at 07:00"、"Every Monday at 09:00"）
   - `enabled`：true/false
   - `last_execution`：上次执行的时间戳和状态，或"Never"
   - `next_trigger`：下次计划触发时间，或"None"

**返回格式**（示例）：

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

**超时**：10 秒

---

### run_scheduled_task

通过 WorkManager 将定时任务加入队列以立即异步执行。

**参数**：

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| task_id | string | 是 | 要运行的任务 ID |

**实现**：`RunScheduledTaskTool` + `RunScheduledTaskUseCase`

```kotlin
class RunScheduledTaskTool(
    private val runScheduledTaskUseCase: RunScheduledTaskUseCase
) : Tool
```

**行为**：
1. 验证 `task_id` 参数是否存在
2. 委托给 `RunScheduledTaskUseCase(taskId)`
3. 入队成功时返回成功消息，任务未找到时返回错误

**返回格式**：
- 成功：`"Task 'Morning Briefing' has been queued for execution. You will receive a notification when it completes."`
- 错误：`"Task not found with ID 'xyz'."`

**超时**：10 秒

---

### update_scheduled_task

使用部分更新语义更新现有定时任务的一个或多个字段。

**参数**：

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| task_id | string | 是 | 要更新的任务 ID |
| name | string | 否 | 新任务名称 |
| prompt | string | 否 | 新提示消息 |
| schedule_type | string | 否 | "one_time"、"daily" 或 "weekly" |
| hour | integer | 否 | 新小时（0-23） |
| minute | integer | 否 | 新分钟（0-59） |
| day_of_week | string | 否 | 每周任务的星期名称（例如 "monday"） |
| date | string | 否 | 一次性任务的日期，格式为 YYYY-MM-DD |
| enabled | boolean | 否 | 启用或禁用任务 |

**实现**：`UpdateScheduledTaskTool`

```kotlin
class UpdateScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val updateScheduledTaskUseCase: UpdateScheduledTaskUseCase
) : Tool
```

**行为**：
1. 验证 `task_id` 是否存在
2. 从 `scheduledTaskRepository.getTaskById(taskId)` 获取现有任务
3. 任务未找到时返回错误
4. 应用部分更新：对于每个已提供且非 null 的可选参数，覆盖现有 `ScheduledTask` 上对应的字段。省略的参数保留其当前值。
5. 将更新后的 `ScheduledTask` 委托给 `UpdateScheduledTaskUseCase`，由其处理闹钟取消、触发时间重新计算和闹钟重新注册
6. 返回列出已变更字段的成功消息

**部分更新逻辑**：

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

**返回格式**：
- 成功：`"Task 'Morning Briefing' updated successfully. Changed fields: hour (7 -> 8). Next trigger: 2026-03-02 08:00."`
- 错误（未找到）：`"Task not found with ID 'xyz'."`
- 错误（验证）：`"Task name is required."` （来自 `UpdateScheduledTaskUseCase`）

**超时**：10 秒

---

### delete_scheduled_task

永久删除定时任务并取消其闹钟。

**参数**：

| 参数 | 类型 | 必填 | 描述 |
|-----------|------|----------|-------------|
| task_id | string | 是 | 要删除的任务 ID |

**实现**：`DeleteScheduledTaskTool`

```kotlin
class DeleteScheduledTaskTool(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val deleteScheduledTaskUseCase: DeleteScheduledTaskUseCase
) : Tool
```

**行为**：
1. 验证 `task_id` 是否存在
2. 获取任务以验证其存在，并获取其名称用于确认消息
3. 任务未找到时返回错误
4. 委托给 `DeleteScheduledTaskUseCase(taskId)`，由其取消闹钟并从数据库中删除任务
5. 返回成功消息

**返回格式**：
- 成功：`"Task 'Morning Briefing' has been deleted. Its alarm has been cancelled."`
- 错误：`"Task not found with ID 'xyz'."`

**超时**：10 秒

## 新组件

### RunScheduledTaskUseCase

一个新的 use case，用于将 WorkManager 工作请求加入队列以立即执行任务。与 `ScheduledTaskReceiver` 中闹钟触发的流程不同，该 use case：

- 验证任务是否存在（未找到时返回错误）
- 向 Worker 传递 `KEY_MANUAL_RUN = true` 标志
- 不要求任务处于启用状态

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

关键设计决策：
- 使用独立的工作名称前缀（`scheduled_task_manual_`）以避免与闹钟触发的工作（`scheduled_task_`）冲突
- 返回任务名称，以便工具在确认消息中包含该名称
- WorkManager 处理网络约束和前台服务，与闹钟触发的执行方式相同

### ScheduledTaskWorker 修改

现有的 `ScheduledTaskWorker` 需要两处修改以支持手动运行：

1. **新常量**：`KEY_MANUAL_RUN`
2. **跳过启用检查**：当 `manualRun = true` 时，跳过 `if (!task.isEnabled) return Result.success()` 守卫
3. **跳过重新调度**：当 `manualRun = true` 时，不修改 `isEnabled` 或 `nextTriggerAt`，也不重新注册闹钟

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

### 工具类

所有四个工具类均位于 `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/`：

| 文件 | 类 |
|------|-------|
| `ListScheduledTasksTool.kt` | `ListScheduledTasksTool` |
| `RunScheduledTaskTool.kt` | `RunScheduledTaskTool` |
| `UpdateScheduledTaskTool.kt` | `UpdateScheduledTaskTool` |
| `DeleteScheduledTaskTool.kt` | `DeleteScheduledTaskTool` |

每个工具遵循与 `CreateScheduledTaskTool` 相同的结构：
- 实现 `Tool` 接口
- 定义带有名称、描述和 `ToolParametersSchema` 的 `ToolDefinition`
- `execute()` 验证参数，委托给 use case / repository，并返回 `ToolResult`
- `requiredPermissions = emptyList()`（无需特殊权限）
- `timeoutSeconds = 10`

### 辅助工具：计划描述格式化器

`CreateScheduledTaskTool` 中的 `buildScheduleDescription()` 函数在 `ListScheduledTasksTool` 和 `UpdateScheduledTaskTool` 中存在重复。将其提取为共享工具：

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

## 依赖注入注册

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

注：`UpdateScheduledTaskUseCase` 和 `DeleteScheduledTaskUseCase` 已由 RFC-019 注册。

## 与 RFC-024 的关系

RFC-024 定义了 `RunScheduledTaskNowUseCase`，它在调用协程上下文中同步执行任务（用于详情页的"立即运行"按钮）。RFC-027 的 `RunScheduledTaskUseCase` 有所不同：它将任务加入 WorkManager 队列后立即返回，这适用于聊天上下文中的工具执行场景，因为在该场景下阻塞等待数分钟是不可接受的。

如果 RFC-024 先于 RFC-027 实现，两个 use case 将共存：
- `RunScheduledTaskNowUseCase` -- 用于 UI 上下文的同步执行（详情页）
- `RunScheduledTaskUseCase` -- 用于工具上下文的异步 WorkManager 入队（聊天）

## 数据库变更

无需数据库 schema 变更。所有操作均使用现有的 `ScheduledTaskRepository` 方法。

## 测试策略

### 单元测试

**ListScheduledTasksToolTest：**
- 验证空列表返回 "No scheduled tasks configured."
- 验证非空列表将所有任务格式化并包含正确字段
- 验证 DAILY、WEEKLY、ONE_TIME 计划类型的描述格式化

**RunScheduledTaskToolTest：**
- 验证缺少 `task_id` 时返回验证错误
- 验证不存在的任务 ID 返回未找到错误
- 验证有效的任务 ID 返回包含任务名称的成功消息

**RunScheduledTaskUseCaseTest：**
- 验证不存在的任务返回带有 `NOT_FOUND` 的 `AppResult.Error`
- 验证有效的任务将 WorkManager 加入队列并携带正确的输入数据（`KEY_TASK_ID`、`KEY_MANUAL_RUN = true`）
- 验证工作名称使用 `scheduled_task_manual_` 前缀
- 验证返回带有任务名称的 `AppResult.Success`

**UpdateScheduledTaskToolTest：**
- 验证缺少 `task_id` 时返回验证错误
- 验证不存在的任务 ID 返回未找到错误
- 验证部分更新：只有提供的字段发生变更，其他字段保持不变
- 验证 `schedule_type` 变更时缺少必填字段（例如每周任务缺少 `day_of_week`）返回验证错误
- 验证 `enabled=false` 导致闹钟取消（通过 `UpdateScheduledTaskUseCase`）

**DeleteScheduledTaskToolTest：**
- 验证缺少 `task_id` 时返回验证错误
- 验证不存在的任务 ID 返回未找到错误
- 验证有效的任务 ID 调用 `DeleteScheduledTaskUseCase` 并返回成功

**ScheduledTaskWorkerTest（手动运行）：**
- 验证 `KEY_MANUAL_RUN = true` 跳过启用检查
- 验证 `KEY_MANUAL_RUN = true` 不修改 `isEnabled` 或 `nextTriggerAt`
- 验证 `KEY_MANUAL_RUN = true` 不重新注册闹钟
- 验证 `KEY_MANUAL_RUN = false`（默认）保留现有行为

**ScheduleDescriptionFormatterTest：**
- 验证 DAILY、WEEKLY、ONE_TIME 计划类型的格式化
- 验证边界情况：null `dayOfWeek`、null `dateMillis`

## 实现步骤

### 阶段一：共享工具
1. [ ] 从 `CreateScheduledTaskTool` 中提取 `ScheduleDescriptionFormatter`
2. [ ] 更新 `CreateScheduledTaskTool` 以使用共享格式化器

### 阶段二：Worker 修改
1. [ ] 向 `ScheduledTaskWorker` 添加 `KEY_MANUAL_RUN` 常量
2. [ ] 在 `doWork()` 中从 `inputData` 读取 `manualRun` 标志
3. [ ] 当 `manualRun = true` 时跳过启用检查
4. [ ] 当 `manualRun = true` 时跳过重新调度
5. [ ] 添加手动运行行为的单元测试

### 阶段三：Use Case
1. [ ] 在 `feature/schedule/usecase/` 中创建 `RunScheduledTaskUseCase`
2. [ ] 在 `FeatureModule` 中注册
3. [ ] 添加单元测试

### 阶段四：工具类
1. [ ] 在 `tool/builtin/` 中创建 `ListScheduledTasksTool`
2. [ ] 在 `tool/builtin/` 中创建 `RunScheduledTaskTool`
3. [ ] 在 `tool/builtin/` 中创建 `UpdateScheduledTaskTool`
4. [ ] 在 `tool/builtin/` 中创建 `DeleteScheduledTaskTool`
5. [ ] 在 `ToolModule` 中注册所有四个工具
6. [ ] 为所有四个工具添加单元测试

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
