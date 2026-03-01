# RFC-024: 定时任务详情页

## 文档信息
- **RFC ID**: RFC-024
- **Related PRD**: [FEAT-024 (定时任务详情)](../../prd/features/FEAT-024-scheduled-task-detail.md)
- **Extends**: [RFC-019 (定时任务)](RFC-019-scheduled-tasks.md)
- **Depends On**: [RFC-019 (定时任务)](RFC-019-scheduled-tasks.md), [RFC-001 (对话交互)](RFC-001-chat-interaction.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## 概述

### 背景

RFC-019 实现了定时任务系统，包含两个页面：列表页和编辑页。列表页仅展示最少量信息（名称、计划、开关、最近状态），点击任务会直接跳转到编辑表单。目前没有办法查看任务详细信息、执行历史，或在不进入编辑模式的情况下访问历史执行会话。

此外，当前数据模型仅在 `ScheduledTask` 实体本身上存储最近一次执行结果（`lastExecutionAt`、`lastExecutionStatus`、`lastExecutionSessionId`），没有持久化的执行历史记录。

### 目标

1. 新增一个只读详情页，展示完整的任务配置、运行状态和执行历史
2. 引入 `task_execution_records` 表以持久化执行历史
3. 修改列表页的导航逻辑，使点击任务时跳转到详情页而非编辑页
4. 在详情页添加"立即运行"操作，支持手动触发任务执行

### 非目标

- 修改编辑页（保持不变）
- 添加执行统计、图表或分析功能
- 支持从历史记录中重试执行
- 修改 `ScheduledTask` 领域模型的现有字段

## 技术设计

### 架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                     Navigation Layer                         │
│                                                              │
│  Routes.kt                                                   │
│  └── ScheduleDetail(taskId)  (新路由)                        │
│                                                              │
│  NavGraph.kt                                                 │
│  └── 列表 -> 点击条目 -> 详情（不再跳转编辑）                │
│  └── 详情 -> 编辑按钮 -> 编辑                               │
├─────────────────────────────────────────────────────────────┤
│                     UI Layer                                 │
│                                                              │
│  ScheduledTaskDetailScreen.kt              (新建)            │
│  ScheduledTaskDetailViewModel.kt           (新建)            │
│  ScheduledTaskDetailUiState                (新建)            │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Use Case Layer                           │
│                                                              │
│  RunScheduledTaskNowUseCase.kt             (新建)            │
│  CleanupExecutionHistoryUseCase.kt         (新建)            │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Data Layer                               │
│                                                              │
│  TaskExecutionRecord           (新建领域模型)                │
│  TaskExecutionRecordEntity     (新建 Room 实体)              │
│  TaskExecutionRecordDao        (新建 DAO)                    │
│  TaskExecutionRecordMapper     (新建 Mapper)                 │
│  TaskExecutionRecordRepository (新建 Repository)             │
│                                                              │
│  ScheduledTaskWorker.kt       (修改：保存执行历史)           │
│                                                              │
├─────────────────────────────────────────────────────────────┤
│                     Database                                 │
│                                                              │
│  Migration v(N) -> v(N+1): CREATE TABLE                     │
│  task_execution_records                                      │
└─────────────────────────────────────────────────────────────┘
```

### 数据模型

#### TaskExecutionRecord（领域模型）

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

#### Room 实体

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

关键决策：
- `CASCADE` 删除：删除定时任务时，其所有执行记录自动级联删除
- 在 `task_id` 上建立索引，以便高效查询单个任务的记录
- 在 `started_at` 上建立索引，用于排序和清理查询
- `errorMessage` 存储失败详情，成功时为 null

#### 数据库迁移

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

### 组件说明

#### ScheduledTaskDetailScreen

只读 Composable 页面，结构如下：

```
┌─────────────────────────────────┐
│ TopAppBar                       │
│ ← [任务名称]          [编辑]   │
├─────────────────────────────────┤
│                                 │
│ ┌─ 配置卡片 ──────────────────┐ │
│ │ 智能体：通用助手             │ │
│ │ 计划：  每天 07:00           │ │
│ │ 已启用：[====ON====]         │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─ 提示词卡片 ────────────────┐ │
│ │ 给我一份早间简报，           │ │
│ │ 包括今天的天气               │ │
│ │ 和热点新闻标题。             │ │
│ └─────────────────────────────┘ │
│                                 │
│ ┌─ 状态卡片 ──────────────────┐ │
│ │ 下次触发：3月2日 07:00       │ │
│ │ 上次运行：3月1日 07:00       │ │
│ │ 上次状态：● 成功             │ │
│ │ 创建时间：2月28日 10:30      │ │
│ └─────────────────────────────┘ │
│                                 │
│ [立即运行]    [查看上次会话]    │
│                                 │
│ ── 执行历史 ─────────────────── │
│                                 │
│ ┌─────────────────────────────┐ │
│ │ ● 3月1日 07:00   SUCCESS  →│ │
│ │ ● 2月28日 07:01  FAILED   →│ │
│ │ ● 2月27日 07:00  SUCCESS  →│ │
│ └─────────────────────────────┘ │
│                                 │
│           [删除任务]            │
│                                 │
└─────────────────────────────────┘
```

参数：
```kotlin
@Composable
fun ScheduledTaskDetailScreen(
    onNavigateBack: () -> Unit,
    onEditTask: (String) -> Unit,
    onNavigateToSession: (String) -> Unit,
    viewModel: ScheduledTaskDetailViewModel = koinViewModel()
)
```

关键 UI 细节：
- TopAppBar：任务名称作为标题，返回按钮，编辑图标按钮
- 配置卡片：智能体名称（通过 `AgentRepository` 解析）、计划描述（复用 `formatScheduleDescription`）、启用开关
- 提示词卡片：在卡片中展示完整提示词文本，内容过长时支持滚动
- 状态卡片：下次触发时间、上次执行时间、带颜色指示器的上次状态、创建/更新时间戳
- 操作按钮行："立即运行"按钮（运行时显示进度指示器）、"查看上次会话"按钮（无会话时禁用）
- 执行历史：`LazyColumn` 列表展示 `TaskExecutionRecord` 条目，每条显示时间戳、带颜色圆点的状态指示器及跳转会话的箭头，点击条目跳转到对应会话
- 底部删除按钮，带确认对话框
- 执行历史空状态文案："暂无执行记录"

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

手动触发定时任务的立即执行：

```kotlin
class RunScheduledTaskNowUseCase(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val notificationHelper: NotificationHelper
) {
    suspend operator fun invoke(taskId: String): AppResult<String> {
        // 1. 从数据库读取任务
        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return AppResult.Error(ErrorCode.NOT_FOUND, "Task not found")

        // 2. 创建执行记录（status = RUNNING）
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

        // 3. 创建会话并运行智能体循环
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

        // 4. 更新执行记录
        val completedAt = System.currentTimeMillis()
        executionRecordRepository.updateResult(
            id = recordId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            completedAt = completedAt,
            sessionId = sessionId,
            errorMessage = if (!isSuccess) responseText else null
        )

        // 5. 更新任务的最近执行字段
        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            sessionId = sessionId,
            nextTriggerAt = task.nextTriggerAt,  // unchanged
            isEnabled = task.isEnabled           // unchanged
        )

        // 6. 发送通知
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

与 `ScheduledTaskWorker` 的关键区别："立即运行"不会重新调度闹钟，也不会禁用一次性任务。它是一次临时执行，不影响任务的调度生命周期。

#### CleanupExecutionHistoryUseCase

删除超过配置保留期限的执行记录：

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

在 `OneclawApplication.onCreate()` 应用启动时调用。

### 现有组件修改

#### ScheduledTaskWorker（修改）

在 Worker 中增加执行记录的创建与更新逻辑，在原有 `ScheduledTask` 的 `updateExecutionResult` 调用基础上补充：

```kotlin
// 在 doWork() 中，标记为 RUNNING 之后：
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

// ... 现有执行逻辑 ...

// 执行完成后（与现有 updateExecutionResult 同步执行）：
executionRecordRepository.updateResult(
    id = recordId,
    status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
    completedAt = System.currentTimeMillis(),
    sessionId = sessionId,
    errorMessage = if (!isSuccess) responseText else null
)
```

#### ScheduledTaskListScreen（修改）

将 `onEditTask` 回调改为 `onTaskClick`，使其跳转到详情页而非编辑页：

```kotlin
// 修改前：
onEditTask = { onEditTask(task.id) }

// 修改后：
onClick = { onTaskClick(task.id) }
```

`ScheduledTaskItem` Composable 更新为整行可点击（不再依赖编辑行为）。

#### NavGraph（修改）

添加详情路由并更新列表页导航：

```kotlin
// 新路由
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

// 更新列表页：onEditTask -> onTaskClick -> 跳转到详情
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

### 导航

在 `Routes.kt` 中新增路由：

```kotlin
data class ScheduleDetail(val taskId: String) : Route("schedules/{taskId}/detail") {
    companion object {
        const val PATH = "schedules/{taskId}/detail"
        fun create(taskId: String) = "schedules/$taskId/detail"
    }
}
```

现有 `ScheduleEdit` 路由路径 `schedules/{taskId}` 保持不变。详情路由使用 `schedules/{taskId}/detail`，以避免路径冲突。

### 依赖注入注册

**DatabaseModule**：
```kotlin
// RFC-024: Execution record DAO
single { get<AppDatabase>().taskExecutionRecordDao() }
```

**RepositoryModule**：
```kotlin
// RFC-024: Execution record repository
single<TaskExecutionRecordRepository> { TaskExecutionRecordRepositoryImpl(get()) }
```

**FeatureModule**：
```kotlin
// RFC-024: Detail page
single { RunScheduledTaskNowUseCase(get(), get(), get(), get(), get()) }
single { CleanupExecutionHistoryUseCase(get()) }
viewModel { ScheduledTaskDetailViewModel(get(), get(), get(), get(), get(), get(), get()) }
```

## 实现步骤

### 阶段一：数据层（执行历史持久化）
1. [ ] 在 `core/model/` 中创建 `TaskExecutionRecord` 领域模型
2. [ ] 在 `data/local/entity/` 中创建 `TaskExecutionRecordEntity` Room 实体
3. [ ] 在 `data/local/dao/` 中创建 `TaskExecutionRecordDao`
4. [ ] 在 `data/local/mapper/` 中创建 `TaskExecutionRecordMapper`
5. [ ] 在 `core/repository/` 中创建 `TaskExecutionRecordRepository` 接口
6. [ ] 在 `data/repository/` 中创建 `TaskExecutionRecordRepositoryImpl`
7. [ ] 在 `AppDatabase` 中添加 `taskExecutionRecordDao()`
8. [ ] 创建 `task_execution_records` 表的数据库迁移
9. [ ] 在 DI 模块中注册 DAO 和 Repository

### 阶段二：Worker 修改（记录执行历史）
1. [ ] 将 `TaskExecutionRecordRepository` 注入到 `ScheduledTaskWorker`
2. [ ] 在 `doWork()` 开始时创建执行记录
3. [ ] 执行完成后更新执行记录
4. [ ] 为 Worker 执行记录逻辑添加单元测试

### 阶段三：详情页 UI
1. [ ] 创建 `ScheduledTaskDetailUiState` 数据类
2. [ ] 创建 `ScheduledTaskDetailViewModel`，包含任务加载、执行历史和操作逻辑
3. [ ] 创建包含所有区块的 `ScheduledTaskDetailScreen` Composable
4. [ ] 在 `Routes.kt` 中添加 `ScheduleDetail` 路由
5. [ ] 在 `NavGraph.kt` 中注册详情页
6. [ ] 更新 `ScheduledTaskListScreen`，使其跳转到详情页而非编辑页
7. [ ] 在 DI 模块中注册 ViewModel 和 Use Case

### 阶段四：立即运行功能
1. [ ] 创建 `RunScheduledTaskNowUseCase`
2. [ ] 将详情页中的"立即运行"按钮与 Use Case 连接
3. [ ] 添加加载状态和错误处理
4. [ ] 为 `RunScheduledTaskNowUseCase` 添加单元测试

### 阶段五：历史记录清理
1. [ ] 创建 `CleanupExecutionHistoryUseCase`
2. [ ] 在 `OneclawApplication.onCreate()` 应用启动时调用清理逻辑
3. [ ] 为清理逻辑添加单元测试

## 测试策略

### 单元测试

**TaskExecutionRecordRepositoryImplTest：**
- 使用 Mock DAO 验证增删改查操作
- 验证 `getRecordsByTaskId` 按 `startedAt` 降序返回记录
- 验证 `deleteByTaskId` 删除某任务的所有记录
- 验证 `cleanupOlderThan` 仅删除早于截止时间的记录

**ScheduledTaskDetailViewModelTest：**
- 验证初始化时加载任务和智能体名称
- 验证执行历史已加载并暴露在 UI 状态中
- 验证 `toggleEnabled` 调用 `ToggleScheduledTaskUseCase`
- 验证 `deleteTask` 调用 `DeleteScheduledTaskUseCase` 并将 `isDeleted` 设为 true
- 验证 `runNow` 在执行期间将 `isRunningNow` 设为 true，执行结束后恢复为 false

**RunScheduledTaskNowUseCaseTest：**
- 验证执行记录以 `RUNNING` 状态创建
- 验证会话已创建且智能体循环已执行
- 验证成功时记录更新为 `SUCCESS`
- 验证失败时记录更新为 `FAILED`
- 验证任务的闹钟调度未被修改
- 验证通知已发送

**CleanupExecutionHistoryUseCaseTest：**
- 验证超过保留期的记录被删除
- 验证近期记录被保留

### 集成测试

- 验证 `ScheduledTaskWorker` 创建并更新执行记录
- 验证删除任务时级联删除执行记录
- 验证详情页加载任务及执行历史数据

### 手动测试

- 从列表页导航到详情页，验证所有区块正确显示
- 立即运行：点击按钮，验证进度指示器，验证执行历史更新
- 从详情页切换启用/禁用状态，验证闹钟状态
- 从详情页删除任务，验证返回列表页的导航
- 点击执行历史条目，验证跳转到对应会话
- 查看无执行记录的任务详情页，验证空状态显示

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
