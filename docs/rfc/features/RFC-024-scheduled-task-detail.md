# RFC-024: Scheduled Task Detail Page

## Document Information
- **RFC ID**: RFC-024
- **Related PRD**: [FEAT-024 (Scheduled Task Detail)](../../prd/features/FEAT-024-scheduled-task-detail.md)
- **Extends**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md)
- **Depends On**: [RFC-019 (Scheduled Tasks)](RFC-019-scheduled-tasks.md), [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

RFC-019 implemented the scheduled task system with two screens: a list screen and an edit screen. The list screen shows minimal information (name, schedule, toggle, last status), and tapping a task navigates directly to the edit form. There is no way to view detailed task information, execution history, or quickly access past execution sessions without entering edit mode.

Additionally, the current data model only stores the last execution result on the `ScheduledTask` entity itself (`lastExecutionAt`, `lastExecutionStatus`, `lastExecutionSessionId`). There is no persistent record of execution history.

### Goals

1. Add a read-only detail page that displays full task configuration, runtime status, and execution history
2. Introduce a `task_execution_records` table to persist execution history
3. Change the list screen navigation so tapping a task opens the detail page instead of the edit screen
4. Add a "Run Now" action to manually trigger task execution from the detail page

### Non-Goals

- Modifying the edit screen (it remains unchanged)
- Adding execution statistics, charts, or analytics
- Supporting execution retry from history entries
- Modifying the `ScheduledTask` domain model's existing fields

## Technical Design

### Architecture Overview

```
┌─────────────────────────────────────────────────────────────┐
│                     Navigation Layer                         │
│                                                              │
│  Routes.kt                                                   │
│  └── ScheduleDetail(taskId)  (new route)                    │
│                                                              │
│  NavGraph.kt                                                 │
│  └── List -> tap item -> Detail (not Edit)                  │
│  └── Detail -> Edit button -> Edit                          │
├─────────────────────────────────────────────────────────────┤
│                     UI Layer                                 │
│                                                              │
│  ScheduledTaskDetailScreen.kt              (new)            │
│  ScheduledTaskDetailViewModel.kt           (new)            │
│  ScheduledTaskDetailUiState                (new)            │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Use Case Layer                           │
│                                                              │
│  RunScheduledTaskNowUseCase.kt             (new)            │
│  CleanupExecutionHistoryUseCase.kt         (new)            │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Data Layer                               │
│                                                              │
│  TaskExecutionRecord           (new domain model)           │
│  TaskExecutionRecordEntity     (new Room entity)            │
│  TaskExecutionRecordDao        (new DAO)                    │
│  TaskExecutionRecordMapper     (new mapper)                 │
│  TaskExecutionRecordRepository (new repository)             │
│                                                              │
│  ScheduledTaskWorker.kt       (modified: save history)      │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Database                                 │
│                                                              │
│  Migration v(N) -> v(N+1): CREATE TABLE                     │
│  task_execution_records                                      │
└─────────────────────────────────────────────────────────────┘
```

### Data Model

#### TaskExecutionRecord (Domain Model)

```kotlin
data class TaskExecutionRecord(
    val id: String,             // UUID
    val taskId: String,         // FK to scheduled_tasks.id
    val status: ExecutionStatus,
    val sessionId: String?,     // chat session created for this execution
    val startedAt: Long,        // epoch millis
    val completedAt: Long?,     // epoch millis, null if still running
    val errorMessage: String?   // error description if FAILED
)
```

#### Room Entity

```kotlin
@Entity(
    tableName = "task_execution_records",
    foreignKeys = [
        ForeignKey(
            entity = ScheduledTaskEntity::class,
            parentColumns = ["id"],
            childColumns = ["task_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["task_id"]),
        Index(value = ["started_at"])
    ]
)
data class TaskExecutionRecordEntity(
    @PrimaryKey val id: String,
    @ColumnInfo(name = "task_id") val taskId: String,
    val status: String,              // "RUNNING", "SUCCESS", "FAILED"
    @ColumnInfo(name = "session_id") val sessionId: String?,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "completed_at") val completedAt: Long?,
    @ColumnInfo(name = "error_message") val errorMessage: String?
)
```

Key decisions:
- `CASCADE` delete: when a scheduled task is deleted, all its execution records are automatically removed
- Index on `task_id` for efficient per-task queries
- Index on `started_at` for ordering and cleanup queries
- `errorMessage` stores failure details, null on success

#### Database Migration

```sql
CREATE TABLE IF NOT EXISTS task_execution_records (
    id TEXT NOT NULL PRIMARY KEY,
    task_id TEXT NOT NULL,
    status TEXT NOT NULL,
    session_id TEXT,
    started_at INTEGER NOT NULL,
    completed_at INTEGER,
    error_message TEXT,
    FOREIGN KEY (task_id) REFERENCES scheduled_tasks(id) ON DELETE CASCADE
);
CREATE INDEX IF NOT EXISTS index_task_execution_records_task_id ON task_execution_records(task_id);
CREATE INDEX IF NOT EXISTS index_task_execution_records_started_at ON task_execution_records(started_at);
```

#### DAO

```kotlin
@Dao
interface TaskExecutionRecordDao {

    @Query("""
        SELECT * FROM task_execution_records
        WHERE task_id = :taskId
        ORDER BY started_at DESC
        LIMIT :limit
    """)
    fun getRecordsByTaskId(taskId: String, limit: Int = 50): Flow<List<TaskExecutionRecordEntity>>

    @Insert
    suspend fun insert(record: TaskExecutionRecordEntity)

    @Query("""
        UPDATE task_execution_records
        SET status = :status, completed_at = :completedAt,
            session_id = :sessionId, error_message = :errorMessage
        WHERE id = :id
    """)
    suspend fun updateResult(
        id: String,
        status: String,
        completedAt: Long,
        sessionId: String?,
        errorMessage: String?
    )

    @Query("DELETE FROM task_execution_records WHERE task_id = :taskId")
    suspend fun deleteByTaskId(taskId: String)

    @Query("DELETE FROM task_execution_records WHERE started_at < :cutoffMillis")
    suspend fun deleteOlderThan(cutoffMillis: Long)

    @Query("SELECT COUNT(*) FROM task_execution_records WHERE task_id = :taskId")
    suspend fun countByTaskId(taskId: String): Int
}
```

#### Repository

```kotlin
interface TaskExecutionRecordRepository {
    fun getRecordsByTaskId(taskId: String, limit: Int = 50): Flow<List<TaskExecutionRecord>>
    suspend fun createRecord(record: TaskExecutionRecord)
    suspend fun updateResult(
        id: String,
        status: ExecutionStatus,
        completedAt: Long,
        sessionId: String?,
        errorMessage: String?
    )
    suspend fun deleteByTaskId(taskId: String)
    suspend fun cleanupOlderThan(days: Int)
}
```

### Components

#### ScheduledTaskDetailScreen

Read-only Composable screen with the following structure:

```
┌─────────────────────────────────┐
│ TopAppBar                       │
│ ← [Task Name]         [Edit]   │
├─────────────────────────────────┤
│                                 │
│ ┌─ Configuration Card ────────┐ │
│ │ Agent:    General Assistant  │ │
│ │ Schedule: Daily at 07:00    │ │
│ │ Enabled:  [====ON====]      │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─ Prompt Card ───────────────┐ │
│ │ Give me a morning briefing  │ │
│ │ including today's weather   │ │
│ │ and top news headlines.     │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─ Status Card ───────────────┐ │
│ │ Next trigger: Mar 2 07:00   │ │
│ │ Last run:     Mar 1 07:00   │ │
│ │ Last status:  ● Success     │ │
│ │ Created:      Feb 28 10:30  │ │
│ └─────────────────────────────┘ │
│                                 │
│ [Run Now]    [View Last Session]│
│                                 │
│ ── Execution History ────────── │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ ● Mar 1 07:00    SUCCESS  →│ │
│ │ ● Feb 28 07:01   FAILED   →│ │
│ │ ● Feb 27 07:00   SUCCESS  →│ │
│ └─────────────────────────────┘ │
│                                 │
│           [Delete Task]         │
│                                 │
└─────────────────────────────────┘
```

Parameters:
```kotlin
@Composable
fun ScheduledTaskDetailScreen(
    onNavigateBack: () -> Unit,
    onEditTask: (String) -> Unit,
    onNavigateToSession: (String) -> Unit,
    viewModel: ScheduledTaskDetailViewModel = koinViewModel()
)
```

Key UI details:
- TopAppBar: task name as title, back button, edit icon button
- Configuration card: agent name (resolved via `AgentRepository`), schedule description (reuses `formatScheduleDescription`), enabled toggle switch
- Prompt card: full prompt text displayed in a card, scrollable if very long
- Status card: next trigger time, last execution time, last status with colored indicator, created/updated timestamps
- Action buttons row: "Run Now" button (shows progress indicator while running), "View Last Session" button (disabled if no session exists)
- Execution history: `LazyColumn` section listing `TaskExecutionRecord` entries. Each entry shows timestamp, status indicator (colored dot), and a chevron to navigate to the session. Tapping an entry navigates to its session.
- Delete button at the bottom with confirmation dialog
- Empty state for execution history: "No executions yet"

#### ScheduledTaskDetailViewModel

```kotlin
class ScheduledTaskDetailViewModel(
    savedStateHandle: SavedStateHandle,
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val agentRepository: AgentRepository,
    private val toggleUseCase: ToggleScheduledTaskUseCase,
    private val deleteUseCase: DeleteScheduledTaskUseCase,
    private val runNowUseCase: RunScheduledTaskNowUseCase
) : ViewModel() {

    private val taskId: String = savedStateHandle["taskId"] ?: ""

    private val _uiState = MutableStateFlow(ScheduledTaskDetailUiState())
    val uiState: StateFlow<ScheduledTaskDetailUiState> = _uiState.asStateFlow()

    init {
        loadTask()
        loadExecutionHistory()
    }

    fun toggleEnabled(enabled: Boolean) { /* ... */ }
    fun deleteTask() { /* ... */ }
    fun runNow() { /* ... */ }
}
```

#### ScheduledTaskDetailUiState

```kotlin
data class ScheduledTaskDetailUiState(
    val task: ScheduledTask? = null,
    val agentName: String = "",
    val executionHistory: List<TaskExecutionRecord> = emptyList(),
    val isLoading: Boolean = true,
    val isRunningNow: Boolean = false,
    val isDeleted: Boolean = false,
    val errorMessage: String? = null
)
```

#### RunScheduledTaskNowUseCase

Manually triggers an immediate execution of a scheduled task:

```kotlin
class RunScheduledTaskNowUseCase(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val notificationHelper: NotificationHelper
) {
    suspend operator fun invoke(taskId: String): AppResult<String> {
        // 1. Read task from DB
        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return AppResult.Error(ErrorCode.NOT_FOUND, "Task not found")

        // 2. Create execution record (status = RUNNING)
        val recordId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        executionRecordRepository.createRecord(
            TaskExecutionRecord(
                id = recordId,
                taskId = taskId,
                status = ExecutionStatus.RUNNING,
                sessionId = null,
                startedAt = startedAt,
                completedAt = null,
                errorMessage = null
            )
        )

        // 3. Create session and run agent loop
        var sessionId: String? = null
        var responseText = ""
        var isSuccess = false

        try {
            val session = createSessionUseCase(agentId = task.agentId)
            sessionId = session.id

            sendMessageUseCase.execute(
                sessionId = session.id,
                userText = task.prompt,
                agentId = task.agentId
            ).collect { event ->
                when (event) {
                    is ChatEvent.StreamingText -> responseText += event.text
                    is ChatEvent.ResponseComplete -> isSuccess = true
                    is ChatEvent.Error -> {
                        responseText = event.message
                        isSuccess = false
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            responseText = e.message ?: "Unknown error"
            isSuccess = false
        }

        // 4. Update execution record
        val completedAt = System.currentTimeMillis()
        executionRecordRepository.updateResult(
            id = recordId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            completedAt = completedAt,
            sessionId = sessionId,
            errorMessage = if (!isSuccess) responseText else null
        )

        // 5. Update task's last execution fields
        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            sessionId = sessionId,
            nextTriggerAt = task.nextTriggerAt,  // unchanged
            isEnabled = task.isEnabled           // unchanged
        )

        // 6. Send notification
        if (isSuccess) {
            notificationHelper.sendScheduledTaskCompletedNotification(
                taskName = task.name,
                sessionId = sessionId,
                responsePreview = responseText
            )
        } else {
            notificationHelper.sendScheduledTaskFailedNotification(
                taskName = task.name,
                sessionId = sessionId,
                errorMessage = responseText
            )
        }

        return if (isSuccess) {
            AppResult.Success(sessionId ?: recordId)
        } else {
            AppResult.Error(ErrorCode.EXECUTION_FAILED, responseText)
        }
    }
}
```

Key difference from `ScheduledTaskWorker`: "Run Now" does NOT reschedule the alarm or disable one-time tasks. It is an ad-hoc execution that does not affect the task's scheduling lifecycle.

#### CleanupExecutionHistoryUseCase

Removes execution records older than the configured retention period:

```kotlin
class CleanupExecutionHistoryUseCase(
    private val executionRecordRepository: TaskExecutionRecordRepository
) {
    suspend operator fun invoke(retentionDays: Int = 90) {
        val cutoffMillis = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        executionRecordRepository.cleanupOlderThan(retentionDays)
    }
}
```

Called from `OneclawApplication.onCreate()` on app startup.

### Modifications to Existing Components

#### ScheduledTaskWorker (modified)

The worker is modified to create a `TaskExecutionRecord` at the start and update it upon completion, in addition to the existing `updateExecutionResult` call on `ScheduledTask`:

```kotlin
// In doWork(), after marking as RUNNING:
val recordId = UUID.randomUUID().toString()
executionRecordRepository.createRecord(
    TaskExecutionRecord(
        id = recordId,
        taskId = taskId,
        status = ExecutionStatus.RUNNING,
        sessionId = null,
        startedAt = System.currentTimeMillis(),
        completedAt = null,
        errorMessage = null
    )
)

// ... existing execution logic ...

// After execution completes (alongside existing updateExecutionResult):
executionRecordRepository.updateResult(
    id = recordId,
    status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
    completedAt = System.currentTimeMillis(),
    sessionId = sessionId,
    errorMessage = if (!isSuccess) responseText else null
)
```

#### ScheduledTaskListScreen (modified)

Change `onEditTask` callback to `onTaskClick` to navigate to the detail page instead of the edit page:

```kotlin
// Before:
onEditTask = { onEditTask(task.id) }

// After:
onClick = { onTaskClick(task.id) }
```

The `ScheduledTaskItem` composable is updated to make the entire row clickable (instead of relying on the edit behavior).

#### NavGraph (modified)

Add the detail route and update list screen navigation:

```kotlin
// New route
composable(Route.ScheduleDetail.PATH) { backStackEntry ->
    val taskId = backStackEntry.arguments?.getString("taskId") ?: return@composable
    ScheduledTaskDetailScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onEditTask = { id -> navController.safeNavigate(Route.ScheduleEdit.create(id)) },
        onNavigateToSession = { sessionId ->
            navController.safeNavigate(Route.ChatSession.create(sessionId))
        }
    )
}

// Update list screen: onEditTask -> onTaskClick -> navigate to detail
composable(Route.ScheduleList.path) {
    ScheduledTaskListScreen(
        onNavigateBack = { navController.safePopBackStack() },
        onCreateTask = { navController.safeNavigate(Route.ScheduleCreate.path) },
        onTaskClick = { taskId ->
            navController.safeNavigate(Route.ScheduleDetail.create(taskId))
        }
    )
}
```

### Navigation

New route added to `Routes.kt`:

```kotlin
data class ScheduleDetail(val taskId: String) : Route("schedules/{taskId}/detail") {
    companion object {
        const val PATH = "schedules/{taskId}/detail"
        fun create(taskId: String) = "schedules/$taskId/detail"
    }
}
```

The existing `ScheduleEdit` route path `schedules/{taskId}` remains unchanged. The detail route uses `schedules/{taskId}/detail` to avoid path conflicts.

### DI Registration

**DatabaseModule**:
```kotlin
// RFC-024: Execution record DAO
single { get<AppDatabase>().taskExecutionRecordDao() }
```

**RepositoryModule**:
```kotlin
// RFC-024: Execution record repository
single<TaskExecutionRecordRepository> { TaskExecutionRecordRepositoryImpl(get()) }
```

**FeatureModule**:
```kotlin
// RFC-024: Detail page
single { RunScheduledTaskNowUseCase(get(), get(), get(), get(), get()) }
single { CleanupExecutionHistoryUseCase(get()) }
viewModel { ScheduledTaskDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
```

## Implementation Steps

### Phase 1: Data Layer (execution history persistence)
1. [ ] Create `TaskExecutionRecord` domain model in `core/model/`
2. [ ] Create `TaskExecutionRecordEntity` Room entity in `data/local/entity/`
3. [ ] Create `TaskExecutionRecordDao` in `data/local/dao/`
4. [ ] Create `TaskExecutionRecordMapper` in `data/local/mapper/`
5. [ ] Create `TaskExecutionRecordRepository` interface in `core/repository/`
6. [ ] Create `TaskExecutionRecordRepositoryImpl` in `data/repository/`
7. [ ] Add `taskExecutionRecordDao()` to `AppDatabase`
8. [ ] Create database migration for `task_execution_records` table
9. [ ] Register DAO and repository in DI modules

### Phase 2: Worker modification (record executions)
1. [ ] Inject `TaskExecutionRecordRepository` into `ScheduledTaskWorker`
2. [ ] Create execution record at the start of `doWork()`
3. [ ] Update execution record upon completion
4. [ ] Add unit tests for worker execution recording

### Phase 3: Detail page UI
1. [ ] Create `ScheduledTaskDetailUiState` data class
2. [ ] Create `ScheduledTaskDetailViewModel` with task loading, execution history, and actions
3. [ ] Create `ScheduledTaskDetailScreen` composable with all sections
4. [ ] Add `ScheduleDetail` route to `Routes.kt`
5. [ ] Register detail page in `NavGraph.kt`
6. [ ] Update `ScheduledTaskListScreen` to navigate to detail page instead of edit page
7. [ ] Register ViewModel and use cases in DI modules

### Phase 4: Run Now feature
1. [ ] Create `RunScheduledTaskNowUseCase`
2. [ ] Wire "Run Now" button in the detail screen to the use case
3. [ ] Add loading state and error handling
4. [ ] Add unit tests for `RunScheduledTaskNowUseCase`

### Phase 5: History cleanup
1. [ ] Create `CleanupExecutionHistoryUseCase`
2. [ ] Call cleanup on app startup in `OneclawApplication.onCreate()`
3. [ ] Add unit test for cleanup logic

## Testing Strategy

### Unit Tests

**TaskExecutionRecordRepositoryImplTest:**
- CRUD operations with mocked DAO
- Verify `getRecordsByTaskId` returns records in descending order by `startedAt`
- Verify `deleteByTaskId` removes all records for a task
- Verify `cleanupOlderThan` removes only records older than the cutoff

**ScheduledTaskDetailViewModelTest:**
- Verify task and agent name are loaded on init
- Verify execution history is loaded and exposed in UI state
- Verify `toggleEnabled` calls `ToggleScheduledTaskUseCase`
- Verify `deleteTask` calls `DeleteScheduledTaskUseCase` and sets `isDeleted = true`
- Verify `runNow` sets `isRunningNow = true` during execution and `false` after

**RunScheduledTaskNowUseCaseTest:**
- Verify execution record is created with `RUNNING` status
- Verify session is created and agent loop is executed
- Verify record is updated with `SUCCESS` on success
- Verify record is updated with `FAILED` on failure
- Verify task alarm schedule is NOT modified
- Verify notification is sent

**CleanupExecutionHistoryUseCaseTest:**
- Verify records older than retention period are deleted
- Verify recent records are preserved

### Integration Tests

- Verify `ScheduledTaskWorker` creates and updates execution records
- Verify cascade delete removes execution records when task is deleted
- Verify detail page loads with task and execution history data

### Manual Tests

- Navigate to detail page from list, verify all sections display correctly
- Run Now: tap button, verify progress indicator, verify execution history updates
- Toggle enable/disable from detail page, verify alarm state
- Delete from detail page, verify navigation back to list
- Tap execution history entry, verify navigation to session
- View detail page for a task with no executions, verify empty state

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
