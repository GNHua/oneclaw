# RFC-053: Scheduled Task Execution Log

## Document Information
- **RFC ID**: RFC-053
- **Related PRD**: [FEAT-053 (Scheduled Task Execution Log)](../../prd/features/FEAT-053-execution-log.md)
- **Depends On**: [RFC-028 (Scheduled Task Detail)](RFC-028-scheduled-task-detail.md)
- **Depended On By**: None
- **Created**: 2026-03-06
- **Last Updated**: 2026-03-06
- **Status**: Implemented

## Overview

This RFC adds a dedicated Execution Log screen for scheduled task executions. It also makes all execution history rows tappable and fixes a pre-existing bug where navigating to a chat session via route parameter did not load the session content.

## Changes

### New Files

```
feature/schedule/
├── ExecutionLogScreen.kt      # full-screen execution detail UI
└── ExecutionLogViewModel.kt   # loads single record + task name
```

### Modified Files

| File | Change |
|---|---|
| `TaskExecutionRecordDao.kt` | Add `getRecordById(id)` query |
| `TaskExecutionRecordRepository.kt` | Add `getRecordById(id)` to interface |
| `TaskExecutionRecordRepositoryImpl.kt` | Implement `getRecordById(id)` |
| `ScheduledTaskDetailScreen.kt` | All rows tappable; navigate by `record.id` to `ExecutionLog`; add `onNavigateToSession` param for "View Last Session" button |
| `Routes.kt` | Add `Route.ExecutionLog` |
| `NavGraph.kt` | Register `ExecutionLog` composable; pass `initialSessionId` to `ChatScreen` in `ChatSession` route |
| `ChatScreen.kt` | Add `initialSessionId` param; call `viewModel.initialize()` via `LaunchedEffect` |
| `FeatureModule.kt` | Register `ExecutionLogViewModel` with Koin |

## Data Layer

### DAO — `TaskExecutionRecordDao`

```kotlin
@Query("SELECT * FROM task_execution_records WHERE id = :id LIMIT 1")
suspend fun getRecordById(id: String): TaskExecutionRecordEntity?
```

### Repository Interface — `TaskExecutionRecordRepository`

```kotlin
suspend fun getRecordById(id: String): TaskExecutionRecord?
```

### Repository Impl — `TaskExecutionRecordRepositoryImpl`

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

### Layout (top to bottom)

1. **Status header card** — colored dot + status text + task name subtitle
2. **Timing card** — started-at, completed-at (if present), duration
3. **Error card** (FAILED only) — `MaterialTheme.colorScheme.errorContainer` background; shows `record.errorMessage` or a fallback string if null
4. **"View Conversation" button** (only when `record.sessionId != null`) — calls `onNavigateToSession(sessionId)`

### Error fallback text

```
"An unknown error occurred. No additional details were recorded."
```

### Duration formatting

| Range | Format |
|---|---|
| < 60 s | `${s}s` |
| 60 s – 3600 s | `${m}m ${s}s` |
| ≥ 3600 s | `${h}h ${m}m` |

## Navigation

### Route

```kotlin
data class ExecutionLog(val recordId: String) : Route("schedules/execution/{recordId}") {
    companion object {
        const val PATH = "schedules/execution/{recordId}"
        fun create(recordId: String) = "schedules/execution/$recordId"
    }
}
```

### Nav Graph

```kotlin
// ExecutionLog destination
composable(Route.ExecutionLog.PATH) {
    ExecutionLogScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onNavigateToSession = { sessionId ->
            navController.safeNavigate(Route.ChatSession.create(sessionId))
        }
    )
}

// ScheduleDetail — navigate to execution log on row tap
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

## Bug Fix: ChatSession Route Not Loading Session

### Root Cause

`NavGraph.kt` extracted `sessionId` from `backStackEntry.arguments` but never passed it to `ChatScreen`. `ChatViewModel` started in its default empty state regardless of the route.

### Fix

```kotlin
// ChatScreen.kt — new parameter
fun ChatScreen(
    onNavigateToSettings: () -> Unit,
    initialSessionId: String? = null,   // NEW
    viewModel: ChatViewModel = koinViewModel(),
    ...
) {
    LaunchedEffect(initialSessionId) {   // NEW
        if (initialSessionId != null) viewModel.initialize(initialSessionId)
    }
    ...
}

// NavGraph.kt — ChatSession route
composable(Route.ChatSession.PATH) { backStackEntry ->
    val sessionId = backStackEntry.arguments?.getString("sessionId") ?: ""
    ChatScreen(
        onNavigateToSettings = { navController.safeNavigate(Route.Settings.path) },
        initialSessionId = sessionId   // NEW
    )
}
```

This fix benefits all callers of `Route.ChatSession`, including notification tap-to-open and the "View Last Session" button on the scheduled task detail screen.

## Koin DI

```kotlin
// FeatureModule.kt
viewModelOf(::ExecutionLogViewModel)
```

## Open Questions

None.
