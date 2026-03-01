# RFC-023：记忆系统增强

## 文档信息
- **RFC ID**: RFC-023
- **相关 PRD**: [FEAT-023（记忆系统增强）](../../prd/features/FEAT-023-memory-enhancement.md)
- **扩展自**: [RFC-013（记忆系统）](RFC-013-memory.md)
- **依赖**: [RFC-013（记忆系统）](RFC-013-memory.md)、[RFC-001（聊天交互）](RFC-001-chat-interaction.md)、[RFC-004（工具系统）](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## 概述

### 背景
记忆系统（RFC-013）在 `MemoryTriggerManager` 中实现了五种触发方式：`onAppBackground()`、`onSessionSwitch()`、`onDayChange()`、`onSessionEnd()` 和 `onPreCompaction()`。然而，目前只有 `onAppBackground()` 已接入——其余四个均为死代码。此外，AI 能读取记忆（通过 `MemoryInjector` 注入系统提示），却没有工具将信息写入长期记忆。用户必须等待自动的每日日志归纳流程。

### 目标
1. 在 `ChatViewModel.initialize()` 中接入 `onSessionSwitch` 触发器，使用户切换会话时每日日志得以刷新
2. 通过 `ProcessLifecycleOwner.onStart` 接入 `onDayChange` 触发器，使 App 在新的一天重新打开时每日日志得以刷新
3. 实现 `save_memory` 内置工具，使 AI 可以在对话过程中主动向 MEMORY.md 写入内容

### 非目标
- 接入 `onSessionEnd`（App 中无明确的关闭操作）
- 接入 `onPreCompaction`（推迟至 FEAT-011 自动压缩集成）
- 记忆编辑或删除工具
- 无用户对话上下文的 AI 自动保存
- 与每日日志条目的记忆去重

## 技术设计

### 架构概览

改动涉及三个包：`feature/chat/`（ChatViewModel 触发器接入）、`feature/memory/`（MemoryTriggerManager 和 MemoryManager 的新方法）以及 `tool/builtin/`（新增 SaveMemoryTool）。同时也会修改 `OneclawApplication` 以实现日期变更检测。

```
┌─────────────────────────────────────────────────┐
│               Application Layer                  │
│                                                  │
│  OneclawApplication.kt                          │
│  └── onStart handler（日期变更检测）             │
│                                                  │
├─────────────────────────────────────────────────┤
│               ViewModel Layer                    │
│                                                  │
│  ChatViewModel.kt                               │
│  └── initialize() 调用 onSessionSwitch()         │
│                                                  │
├─────────────────────────────────────────────────┤
│               Memory Layer                       │
│                                                  │
│  MemoryTriggerManager.kt                        │
│  └── onDayChangeForActiveSession()（新增）       │
│                                                  │
│  MemoryManager.kt                               │
│  └── saveToLongTermMemory()（新增）              │
│                                                  │
├─────────────────────────────────────────────────┤
│               Tool Layer                         │
│                                                  │
│  SaveMemoryTool.kt（新增）                       │
│                                                  │
├─────────────────────────────────────────────────┤
│               Data Layer                         │
│                                                  │
│  LongTermMemoryManager.appendMemory()（已有）    │
│  MemoryFileStorage（已有）                       │
│  MemoryIndexDao（已有）                          │
└─────────────────────────────────────────────────┘
```

### 核心组件

#### 1. 在 ChatViewModel 中接入 `onSessionSwitch`

**文件**：`feature/chat/ChatViewModel.kt`

将 `MemoryTriggerManager?` 作为可选构造参数添加，并在 `initialize()` 中调用 `onSessionSwitch`：

```kotlin
class ChatViewModel(
    private val sendMessageUseCase: SendMessageUseCase,
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val generateTitleUseCase: GenerateTitleUseCase,
    private val appLifecycleObserver: AppLifecycleObserver,
    private val notificationHelper: NotificationHelper,
    private val skillRegistry: SkillRegistry? = null,
    private val memoryTriggerManager: MemoryTriggerManager? = null  // 新增
) : ViewModel() {
    // ... 已有字段 ...

    fun initialize(sessionId: String?) {
        // RFC-023：在加载新会话之前触发会话切换
        val previousSessionId = _uiState.value.sessionId
        if (previousSessionId != null && sessionId != null && previousSessionId != sessionId) {
            memoryTriggerManager?.onSessionSwitch(previousSessionId)
        }

        loadSessionJob?.cancel()
        loadSessionJob = null
        if (sessionId != null) {
            loadSession(sessionId)
        } else {
            isFirstMessage = true
            firstUserMessageText = null
            _uiState.update {
                it.copy(
                    sessionId = null,
                    sessionTitle = "New Conversation",
                    currentAgentId = AgentConstants.GENERAL_ASSISTANT_ID,
                    currentAgentName = "General Assistant",
                    messages = emptyList(),
                    isStreaming = false,
                    streamingText = "",
                    streamingThinkingText = "",
                    activeToolCalls = emptyList(),
                    inputText = ""
                )
            }
        }
    }
}
```

关键说明：
- `memoryTriggerManager` 为可空类型，保证向后兼容（现有无记忆 DI 的测试不受影响）
- 会话切换触发器在取消加载任务和切换状态之前触发
- 仅当 `previousSessionId` 和 `sessionId` 均非空且不同时才触发
- `onSessionSwitch` 为即发即忘（内部在自身的 CoroutineScope 上运行）

#### 2. 在 OneclawApplication 中接入 `onDayChange`

**文件**：`OneclawApplication.kt`

扩展现有 `ProcessLifecycleOwner` 观察者，同时处理 `onStart`：

```kotlin
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... 已有 Koin、生命周期、通知初始化 ...

        // RFC-013 + RFC-023：为 App 生命周期事件注册记忆触发器
        val memoryTriggerManager = get<MemoryTriggerManager>(MemoryTriggerManager::class.java)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // RFC-023：日期变更检测
                    checkDayChange(memoryTriggerManager)
                }

                override fun onStop(owner: LifecycleOwner) {
                    // RFC-013：进入后台时刷新
                    memoryTriggerManager.onAppBackground()
                }
            }
        )

        // ... onCreate 其余逻辑 ...
    }

    private fun checkDayChange(memoryTriggerManager: MemoryTriggerManager) {
        val prefs = getSharedPreferences("memory_trigger_prefs", MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()  // "YYYY-MM-DD"
        val lastDate = prefs.getString("last_active_date", null)

        if (lastDate != null && lastDate != today) {
            // 自上次前台以来日期已变更——刷新活跃会话
            memoryTriggerManager.onDayChangeForActiveSession()
        }

        // 始终更新存储的日期
        prefs.edit().putString("last_active_date", today).apply()
    }
}
```

关键说明：
- 首次启动时 `lastDate` 为 null，不会触发刷新——仅记录日期
- 日期比较使用 `LocalDate.now().toString()` 获取设备本地 `YYYY-MM-DD` 格式
- 存储的日期在触发刷新之后更新
- `onDayChangeForActiveSession()` 是 `MemoryTriggerManager` 的新方法（见下文）

#### 3. MemoryTriggerManager 新增方法

**文件**：`feature/memory/trigger/MemoryTriggerManager.kt`

新增 `onDayChangeForActiveSession()`，在内部解析活跃会话：

```kotlin
class MemoryTriggerManager(
    private val memoryManager: MemoryManager,
    private val sessionRepository: SessionRepository
) {
    // ... 已有字段和方法 ...

    /**
     * 当日期在 App 活跃时发生变更时调用。
     * 内部解析活跃会话，与 flushActiveSession() 模式相同。
     */
    fun onDayChangeForActiveSession() {
        scope.launch {
            flushActiveSession()
        }
    }
}
```

此方法遵循与 `onAppBackground()` 相同的模式——调用 `flushActiveSession()`，后者通过 `sessionRepository.getActiveSession()` 解析活跃会话并刷新其每日日志。唯一区别在于调用时机（日期变更 vs 进入后台）。

#### 4. MemoryManager 新增方法

**文件**：`feature/memory/MemoryManager.kt`

新增 `saveToLongTermMemory(content)`，将内容追加至 MEMORY.md 并对其建立索引：

```kotlin
class MemoryManager(
    private val dailyLogWriter: DailyLogWriter,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val hybridSearchEngine: HybridSearchEngine,
    private val memoryInjector: MemoryInjector,
    private val memoryIndexDao: MemoryIndexDao,
    private val memoryFileStorage: MemoryFileStorage,
    private val embeddingEngine: EmbeddingEngine
) {
    // ... 已有方法 ...

    /**
     * 将内容直接保存到长期记忆（MEMORY.md）。
     * 当 AI 主动保存信息时由 SaveMemoryTool 调用。
     * 内容将被追加并建立索引以供搜索。
     */
    suspend fun saveToLongTermMemory(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. 追加到 MEMORY.md
            longTermMemoryManager.appendMemory(content)

            // 2. 对新内容建立索引以供搜索
            try {
                indexContent(content, "long_term", null)
            } catch (e: Exception) {
                // 建立索引失败为非致命错误——内容已保存
                Log.w(TAG, "Failed to index saved memory content: ${e.message}")
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    companion object {
        private const val TAG = "MemoryManager"
    }
}
```

关键说明：
- 委托给 `longTermMemoryManager.appendMemory()`，后者负责文件的创建与追加
- 对内容建立索引以供混合搜索使用
- 建立索引失败为非致命错误——内容仍会保存至 MEMORY.md
- 返回 `Result<Unit>`，与 `flushDailyLog()` 保持一致

#### 5. SaveMemoryTool（新增内置工具）

**文件**：`tool/builtin/SaveMemoryTool.kt`

```kotlin
package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.tool.engine.Tool

class SaveMemoryTool(
    private val memoryManager: MemoryManager
) : Tool {

    override val definition = ToolDefinition(
        name = "save_memory",
        description = "Save important information to long-term memory (MEMORY.md). " +
            "Use this when the user asks you to remember something, or when you identify " +
            "critical information that should persist across conversations. " +
            "The content will be appended to MEMORY.md and available in future conversations.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "content" to ToolParameter(
                    type = "string",
                    description = "The text to save to long-term memory. Should be well-formatted " +
                        "and self-contained. Max 5,000 characters."
                )
            ),
            required = listOf("content")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. 提取并验证 content 参数
        val content = (parameters["content"] as? String)?.trim()
        if (content.isNullOrEmpty()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' is required and must be non-empty."
            )
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' must be $MAX_CONTENT_LENGTH characters or less. " +
                    "Current length: ${content.length}."
            )
        }

        // 2. 保存到长期记忆
        val result = memoryManager.saveToLongTermMemory(content)
        return result.fold(
            onSuccess = {
                ToolResult.success(
                    "Memory saved successfully. The content has been appended to MEMORY.md " +
                        "and will be available in future conversations."
                )
            },
            onFailure = { e ->
                ToolResult.error(
                    "save_failed",
                    "Failed to save memory: ${e.message}"
                )
            }
        )
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 5_000
    }
}
```

关键说明：
- 遵循与 `CreateAgentTool` 和 `CreateScheduledTaskTool` 相同的模式
- 单个必填参数：`content`（字符串，最多 5,000 字符）
- 委托给 `MemoryManager.saveToLongTermMemory()`，后者处理文件 I/O 和索引
- 无 `requiredPermissions`——记忆保存被视为非敏感操作
- 超时 10 秒，与其他内置工具保持一致

### 数据模型

不涉及 Room 实体、DAO 或数据模型的变更。`MemoryManager` 和 `LongTermMemoryManager` 使用基于文件的存储（MEMORY.md），已在 RFC-013 中实现。

### API 设计

#### 新增公共方法

```kotlin
// MemoryTriggerManager
fun onDayChangeForActiveSession()

// MemoryManager
suspend fun saveToLongTermMemory(content: String): Result<Unit>
```

#### 修改的构造函数

```kotlin
// ChatViewModel——新增可选参数
class ChatViewModel(
    // ... 已有 10 个参数 ...
    private val memoryTriggerManager: MemoryTriggerManager? = null  // 新增
) : ViewModel()
```

### 依赖注入

**文件**：`di/FeatureModule.kt`

更新 `ChatViewModel` 注册，注入 `MemoryTriggerManager`：

```kotlin
// RFC-001 + RFC-014 + RFC-023：聊天功能 ViewModel
viewModel {
    ChatViewModel(
        sendMessageUseCase = get(),
        sessionRepository = get(),
        messageRepository = get(),
        agentRepository = get(),
        providerRepository = get(),
        createSessionUseCase = get(),
        generateTitleUseCase = get(),
        appLifecycleObserver = get(),
        notificationHelper = get(),
        skillRegistry = get(),
        memoryTriggerManager = get()  // RFC-023
    )
}
```

**文件**：`di/ToolModule.kt`

注册 `SaveMemoryTool`：

```kotlin
// RFC-023：save_memory 内置工具
single { SaveMemoryTool(get()) }

single {
    ToolRegistry().apply {
        // ... 已有注册 ...

        try {
            register(get<SaveMemoryTool>(), ToolSourceInfo.BUILTIN)
        } catch (e: Exception) {
            Log.e("ToolModule", "Failed to register save_memory: ${e.message}")
        }

        // ... JS 工具加载 ...
    }
}
```

## 实现步骤

### 阶段一：接入 `onSessionSwitch`（ChatViewModel + FeatureModule）
1. [ ] 在 `ChatViewModel` 构造函数中添加 `memoryTriggerManager: MemoryTriggerManager? = null` 参数
2. [ ] 在 `ChatViewModel.initialize()` 中添加会话切换检测逻辑
3. [ ] 在 `FeatureModule.kt` 中更新 `ChatViewModel` DI 注册，注入 `MemoryTriggerManager`
4. [ ] 添加单元测试：验证切换会话时 `onSessionSwitch` 被调用
5. [ ] 添加单元测试：验证调用 `initialize(null)` 时 `onSessionSwitch` 不被调用
6. [ ] 添加单元测试：验证会话 ID 相同时 `onSessionSwitch` 不被调用

### 阶段二：接入 `onDayChange`（OneclawApplication + MemoryTriggerManager）
1. [ ] 在 `MemoryTriggerManager` 中添加 `onDayChangeForActiveSession()` 方法
2. [ ] 在 `OneclawApplication` 中添加 `checkDayChange()` 私有方法
3. [ ] 扩展现有 `ProcessLifecycleOwner` 观察者，在 `onStop` 之外同时处理 `onStart`
4. [ ] 添加单元测试：验证 `onDayChangeForActiveSession` 在内部委托给 `flushActiveSession` 模式
5. [ ] 手动测试：验证 SharedPreferences 日期存储与比较逻辑

### 阶段三：`SaveMemoryTool`（新工具 + MemoryManager + ToolModule）
1. [ ] 在 `MemoryManager` 中添加 `saveToLongTermMemory(content)` 方法
2. [ ] 在 `tool/builtin/SaveMemoryTool.kt` 中创建 `SaveMemoryTool`
3. [ ] 在 `ToolModule.kt` 中以 `ToolSourceInfo.BUILTIN` 注册 `SaveMemoryTool`
4. [ ] 添加单元测试：`SaveMemoryTool` 以有效内容执行，返回成功
5. [ ] 添加单元测试：`SaveMemoryTool` 以空内容执行，返回验证错误
6. [ ] 添加单元测试：`SaveMemoryTool` 以超过 5,000 字符的内容执行，返回验证错误
7. [ ] 添加单元测试：`SaveMemoryTool` 在 `saveToLongTermMemory` 失败时执行，返回保存错误
8. [ ] 添加单元测试：`MemoryManager.saveToLongTermMemory` 追加并索引内容
9. [ ] 集成测试：验证 `save_memory` 工具出现在 `ToolRegistry.getAllToolDefinitions()` 中

## 测试策略

### 单元测试

**ChatViewModel 会话切换测试：**
- 验证当 `uiState.sessionId == "session-A"` 时调用 `initialize("session-B")`，`onSessionSwitch(previousId)` 被调用
- 验证调用 `initialize(null)` 时 `onSessionSwitch` 不被调用
- 验证当 `uiState.sessionId == "session-A"` 时调用 `initialize("session-A")`（相同会话），`onSessionSwitch` 不被调用
- 验证当 `uiState.sessionId == null`（无上一个会话）时 `onSessionSwitch` 不被调用
- 验证 `memoryTriggerManager` 为 null 时不会导致崩溃（向后兼容）

**MemoryTriggerManager 日期变更测试：**
- 验证 `onDayChangeForActiveSession()` 在内部委托给 `flushActiveSession()`

**MemoryManager saveToLongTermMemory 测试：**
- 验证 `appendMemory()` 以所提供的内容被调用
- 验证 `indexContent()` 在追加成功后被调用
- 验证建立索引失败不会导致整体操作失败
- 验证文件 I/O 失败时返回 `Result.failure`

**SaveMemoryTool 测试：**
- 以有效内容执行：返回 `ToolResult.success`
- 以空内容执行：返回 `ToolResult.error("validation_error", ...)`
- 以 null 内容执行：返回 `ToolResult.error("validation_error", ...)`
- 以恰好 5,000 字符的内容执行：返回 `ToolResult.success`
- 以 5,001 字符的内容执行：返回 `ToolResult.error("validation_error", ...)`
- 当 `saveToLongTermMemory` 返回失败时执行：返回 `ToolResult.error("save_failed", ...)`

### 集成测试
- 验证 `save_memory` 工具已注册到 `ToolRegistry` 并可通过 `ToolExecutionEngine` 访问
- 端到端：调用 `SaveMemoryTool.execute()`，验证内容出现在 `LongTermMemoryManager.readMemory()` 中

### 手动测试
- 在 App 中切换两个会话，验证上一个会话的每日日志被刷新
- 将 App 挂放一夜，次日重新打开，验证日期变更触发器触发
- 与 AI 对话："记住我所有 App 都偏好深色模式"——验证内容保存至 MEMORY.md
- 与 AI 对话："将今天的讨论摘要保存到记忆"——验证摘要已保存
- 打开新对话，验证之前保存的记忆出现在系统提示上下文中
- 尝试保存过长内容（超过 5,000 字符），验证错误提示

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
