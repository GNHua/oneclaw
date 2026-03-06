# RFC-053: 计划任务执行日志

## 文档信息
- **RFC ID**: RFC-053
- **关联 PRD**: [FEAT-053（计划任务执行日志）](../../prd/features/FEAT-053-execution-log-zh.md)
- **依赖**: [RFC-028（计划任务详情）](RFC-028-scheduled-task-detail.md)
- **被依赖**: 无
- **创建日期**: 2026-03-06
- **最后更新**: 2026-03-06
- **状态**: 已实现

## 概述

本 RFC 为计划任务执行记录新增独立的执行日志屏幕，同时让所有执行历史行均可点击，并修复已有 bug——通过路由参数导航至聊天 session 时，界面不加载对话内容。

## 变更清单

### 新增文件

```
feature/schedule/
├── ExecutionLogScreen.kt      # 执行详情全屏 UI
└── ExecutionLogViewModel.kt   # 加载单条记录和任务名称
```

### 修改文件

| 文件 | 变更内容 |
|---|---|
| `TaskExecutionRecordDao.kt` | 新增 `getRecordById(id)` 查询 |
| `TaskExecutionRecordRepository.kt` | 接口新增 `getRecordById(id)` |
| `TaskExecutionRecordRepositoryImpl.kt` | 实现 `getRecordById(id)` |
| `ScheduledTaskDetailScreen.kt` | 所有行均可点击；按 `record.id` 导航至 `ExecutionLog`；新增 `onNavigateToSession` 参数用于「查看最近 Session」按钮 |
| `Routes.kt` | 新增 `Route.ExecutionLog` |
| `NavGraph.kt` | 注册 `ExecutionLog` composable；在 `ChatSession` 路由中将 `initialSessionId` 传入 `ChatScreen` |
| `ChatScreen.kt` | 新增 `initialSessionId` 参数；通过 `LaunchedEffect` 调用 `viewModel.initialize()` |
| `FeatureModule.kt` | 向 Koin 注册 `ExecutionLogViewModel` |

## 数据层

### DAO — `TaskExecutionRecordDao`

```kotlin
@Query("SELECT * FROM task_execution_records WHERE id = :id LIMIT 1")
suspend fun getRecordById(id: String): TaskExecutionRecordEntity?
```

### Repository 接口 — `TaskExecutionRecordRepository`

```kotlin
suspend fun getRecordById(id: String): TaskExecutionRecord?
```

### Repository 实现 — `TaskExecutionRecordRepositoryImpl`

```kotlin
override suspend fun getRecordById(id: String): TaskExecutionRecord? =
    dao.getRecordById(id)?.toDomain()
```

## ViewModel — `ExecutionLogViewModel`

```kotlin
data class ExecutionLogUiState(
    val record: TaskExecutionRecord? = null,
    val taskName: String = "",
    val isLoading: Boolean = true
)

class ExecutionLogViewModel(
    savedStateHandle: SavedStateHandle,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val scheduledTaskRepository: ScheduledTaskRepository
) : ViewModel() {

    private val recordId: String = savedStateHandle["recordId"] ?: ""

    init { loadRecord() }

    private fun loadRecord() {
        viewModelScope.launch {
            val record = executionRecordRepository.getRecordById(recordId)
            val taskName = record?.let {
                scheduledTaskRepository.getTaskById(it.taskId)?.name ?: ""
            } ?: ""
            _uiState.value = ExecutionLogUiState(
                record = record,
                taskName = taskName,
                isLoading = false
            )
        }
    }
}
```

## UI — `ExecutionLogScreen`

```kotlin
fun ExecutionLogScreen(
    onNavigateBack: () -> Unit,
    onNavigateToSession: (String) -> Unit,
    viewModel: ExecutionLogViewModel = koinViewModel()
)
```

### 布局（从上到下）

1. **状态头部卡片** — 彩色圆点 + 状态文字 + 任务名称副标题
2. **时间卡片** — 开始时间、结束时间（如有）、耗时
3. **错误卡片**（仅失败时显示） — 使用 `errorContainer` 背景色；显示 `record.errorMessage`，为空时显示兜底文案
4. **「查看对话」按钮**（仅 `record.sessionId != null` 时显示） — 调用 `onNavigateToSession(sessionId)`

### 错误兜底文案

```
"An unknown error occurred. No additional details were recorded."
```

### 耗时格式化规则

| 范围 | 格式 |
|---|---|
| < 60 秒 | `${s}s` |
| 60 秒 – 3600 秒 | `${m}m ${s}s` |
| ≥ 3600 秒 | `${h}h ${m}m` |

## 导航

### 路由定义

```kotlin
data class ExecutionLog(val recordId: String) : Route("schedules/execution/{recordId}") {
    companion object {
        const val PATH = "schedules/execution/{recordId}"
        fun create(recordId: String) = "schedules/execution/$recordId"
    }
}
```

### NavGraph 注册

```kotlin
// ExecutionLog 目标页
composable(Route.ExecutionLog.PATH) {
    ExecutionLogScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onNavigateToSession = { sessionId ->
            navController.safeNavigate(Route.ChatSession.create(sessionId))
        }
    )
}

// ScheduleDetail — 点击历史行导航至执行日志
composable(Route.ScheduleDetail.PATH) { backStackEntry ->
    ScheduledTaskDetailScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onEditTask = { id -> navController.safeNavigate(Route.ScheduleEdit.create(id)) },
        onNavigateToExecutionLog = { recordId ->
            navController.safeNavigate(Route.ExecutionLog.create(recordId))
        },
        onNavigateToSession = { sessionId ->
            navController.safeNavigate(Route.ChatSession.create(sessionId))
        }
    )
}
```

## Bug 修复：ChatSession 路由不加载 Session 内容

### 根本原因

`NavGraph.kt` 从 `backStackEntry.arguments` 中取出了 `sessionId`，但从未将其传给 `ChatScreen`。`ChatViewModel` 始终以默认空状态启动，与路由参数无关。

### 修复方式

```kotlin
// ChatScreen.kt — 新增参数
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    initialSessionId: String? = null,   // 新增
    viewModel: ChatViewModel = koinViewModel(),
    ...
) {
    LaunchedEffect(initialSessionId) {   // 新增
        if (initialSessionId != null) viewModel.initialize(initialSessionId)
    }
    ...
}

// NavGraph.kt — ChatSession 路由
composable(Route.ChatSession.PATH) { backStackEntry ->
    val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
    ChatScreen(
        onNavigateToSettings = { navController.safeNavigate(Route.Settings.path) },
        initialSessionId = sessionId   // 新增
    )
}
```

此修复惠及所有调用 `Route.ChatSession` 的场景，包括通知点击跳转和计划任务详情页的「查看最近 Session」按钮。

## Koin DI

```kotlin
// FeatureModule.kt
viewModelOf(::ExecutionLogViewModel)
```

## 待解决问题

无。
