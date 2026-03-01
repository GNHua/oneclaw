# RFC-023: Memory System Enhancement

## Document Information
- **RFC ID**: RFC-023
- **Related PRD**: [FEAT-023 (Memory System Enhancement)](../../prd/features/FEAT-023-memory-enhancement.md)
- **Extends**: [RFC-013 (Memory System)](RFC-013-memory.md)
- **Depends On**: [RFC-013 (Memory System)](RFC-013-memory.md), [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md), [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
The Memory System (RFC-013) implemented five trigger methods in `MemoryTriggerManager`: `onAppBackground()`, `onSessionSwitch()`, `onDayChange()`, `onSessionEnd()`, and `onPreCompaction()`. However, only `onAppBackground()` is actually wired -- the other four are dead code. Additionally, the AI can read memory (injected into the system prompt via `MemoryInjector`) but has no tool to write to long-term memory. Users must wait for the automatic daily log summarization process.

### Goals
1. Wire `onSessionSwitch` trigger in `ChatViewModel.initialize()` so daily logs are flushed when the user switches sessions
2. Wire `onDayChange` trigger via `ProcessLifecycleOwner.onStart` so daily logs are flushed when the app is reopened on a new day
3. Implement a `save_memory` built-in tool so the AI can proactively write to MEMORY.md during conversations

### Non-Goals
- Wiring `onSessionEnd` (no explicit close action exists in the app)
- Wiring `onPreCompaction` (deferred to FEAT-011 Auto Compact integration)
- Memory editing or deletion tools
- Automatic AI-initiated saves without user conversation context
- Memory deduplication with daily log entries

## Technical Design

### Architecture Overview

Changes span three packages: `feature/chat/` (ChatViewModel trigger wiring), `feature/memory/` (new methods in MemoryTriggerManager and MemoryManager), and `tool/builtin/` (new SaveMemoryTool). The `OneclawApplication` is also modified for day-change detection.

```
┌─────────────────────────────────────────────────┐
│               Application Layer                  │
│                                                  │
│  OneclawApplication.kt                          │
│  └── onStart handler (day-change detection)     │
│                                                  │
├─────────────────────────────────────────────────┤
│               ViewModel Layer                    │
│                                                  │
│  ChatViewModel.kt                               │
│  └── initialize() calls onSessionSwitch()       │
│                                                  │
├─────────────────────────────────────────────────┤
│               Memory Layer                       │
│                                                  │
│  MemoryTriggerManager.kt                        │
│  └── onDayChangeForActiveSession()  (new)       │
│                                                  │
│  MemoryManager.kt                               │
│  └── saveToLongTermMemory()         (new)       │
│                                                  │
├─────────────────────────────────────────────────┤
│               Tool Layer                         │
│                                                  │
│  SaveMemoryTool.kt                  (new)       │
│                                                  │
├─────────────────────────────────────────────────┤
│               Data Layer                         │
│                                                  │
│  LongTermMemoryManager.appendMemory() (existing)│
│  MemoryFileStorage                    (existing) │
│  MemoryIndexDao                       (existing) │
└─────────────────────────────────────────────────┘
```

### Core Components

#### 1. Wire `onSessionSwitch` in ChatViewModel

**File**: `feature/chat/ChatViewModel.kt`

Add `MemoryTriggerManager?` as an optional constructor parameter and call `onSessionSwitch` in `initialize()`:

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
    private val memoryTriggerManager: MemoryTriggerManager? = null  // new
) : ViewModel() {
    // ... existing fields ...

    fun initialize(sessionId: String?) {
        // RFC-023: Trigger session switch before loading new session
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

Key points:
- `memoryTriggerManager` is nullable for backward compatibility (existing tests without memory DI)
- Session switch trigger fires BEFORE canceling the load job and switching state
- Only fires when both `previousSessionId` and `sessionId` are non-null and differ
- `onSessionSwitch` is fire-and-forget (runs on its own CoroutineScope internally)

#### 2. Wire `onDayChange` in OneclawApplication

**File**: `OneclawApplication.kt`

Extend the existing `ProcessLifecycleOwner` observer to also handle `onStart`:

```kotlin
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... existing Koin, lifecycle, notification setup ...

        // RFC-013 + RFC-023: Register memory triggers for app lifecycle events
        val memoryTriggerManager = get<MemoryTriggerManager>(MemoryTriggerManager::class.java)
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    // RFC-023: Day-change detection
                    checkDayChange(memoryTriggerManager)
                }

                override fun onStop(owner: LifecycleOwner) {
                    // RFC-013: Flush on background
                    memoryTriggerManager.onAppBackground()
                }
            }
        )

        // ... rest of onCreate ...
    }

    private fun checkDayChange(memoryTriggerManager: MemoryTriggerManager) {
        val prefs = getSharedPreferences("memory_trigger_prefs", MODE_PRIVATE)
        val today = java.time.LocalDate.now().toString()  // "YYYY-MM-DD"
        val lastDate = prefs.getString("last_active_date", null)

        if (lastDate != null && lastDate != today) {
            // Day has changed since last foreground -- flush active session
            memoryTriggerManager.onDayChangeForActiveSession()
        }

        // Always update the stored date
        prefs.edit().putString("last_active_date", today).apply()
    }
}
```

Key points:
- On first-ever launch, `lastDate` is null so no flush fires -- only the date is stored
- Date comparison uses `LocalDate.now().toString()` for device-local `YYYY-MM-DD` format
- The stored date is updated AFTER triggering the flush
- `onDayChangeForActiveSession()` is a new method on `MemoryTriggerManager` (see below)

#### 3. New Method in MemoryTriggerManager

**File**: `feature/memory/trigger/MemoryTriggerManager.kt`

Add `onDayChangeForActiveSession()` which resolves the active session internally:

```kotlin
class MemoryTriggerManager(
    private val memoryManager: MemoryManager,
    private val sessionRepository: SessionRepository
) {
    // ... existing fields and methods ...

    /**
     * Called when the date changes while the app is active.
     * Resolves the active session internally, same pattern as flushActiveSession().
     */
    fun onDayChangeForActiveSession() {
        scope.launch {
            flushActiveSession()
        }
    }
}
```

This method follows the same pattern as `onAppBackground()` -- it calls `flushActiveSession()` which resolves the active session via `sessionRepository.getActiveSession()` and flushes its daily log. The only difference is the call site (day-change vs. background).

#### 4. New Method in MemoryManager

**File**: `feature/memory/MemoryManager.kt`

Add `saveToLongTermMemory(content)` which appends to MEMORY.md and indexes the content:

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
    // ... existing methods ...

    /**
     * Save content directly to long-term memory (MEMORY.md).
     * Called by SaveMemoryTool when the AI proactively saves information.
     * Content is appended and indexed for search.
     */
    suspend fun saveToLongTermMemory(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Append to MEMORY.md
            longTermMemoryManager.appendMemory(content)

            // 2. Index the new content for search
            try {
                indexContent(content, "long_term", null)
            } catch (e: Exception) {
                // Indexing failure is non-fatal -- content is already saved
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

Key points:
- Delegates to `longTermMemoryManager.appendMemory()` which handles file creation and appending
- Indexes the content for hybrid search availability
- Indexing failure is non-fatal -- the content is still saved to MEMORY.md
- Returns `Result<Unit>` consistent with `flushDailyLog()`

#### 5. SaveMemoryTool (new built-in tool)

**File**: `tool/builtin/SaveMemoryTool.kt`

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
        // 1. Extract and validate content parameter
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

        // 2. Save to long-term memory
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

Key points:
- Follows the same pattern as `CreateAgentTool` and `CreateScheduledTaskTool`
- Single required parameter: `content` (string, max 5,000 chars)
- Delegates to `MemoryManager.saveToLongTermMemory()` which handles file I/O and indexing
- No `requiredPermissions` -- memory save is considered a non-sensitive operation
- Timeout of 10 seconds matches other built-in tools

### Data Model

No changes to Room entities, DAOs, or data models. The `MemoryManager` and `LongTermMemoryManager` use file-based storage (MEMORY.md) which is already part of RFC-013.

### API Design

#### New Public Methods

```kotlin
// MemoryTriggerManager
fun onDayChangeForActiveSession()

// MemoryManager
suspend fun saveToLongTermMemory(content: String): Result<Unit>
```

#### Modified Constructor

```kotlin
// ChatViewModel -- new optional parameter
class ChatViewModel(
    // ... existing 10 parameters ...
    private val memoryTriggerManager: MemoryTriggerManager? = null  // new
) : ViewModel()
```

### Dependency Injection

**File**: `di/FeatureModule.kt`

Update `ChatViewModel` registration to inject `MemoryTriggerManager`:

```kotlin
// RFC-001 + RFC-014 + RFC-023: Chat feature view model
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

**File**: `di/ToolModule.kt`

Register `SaveMemoryTool`:

```kotlin
// RFC-023: save_memory built-in tool
single { SaveMemoryTool(get()) }

single {
    ToolRegistry().apply {
        // ... existing registrations ...

        try {
            register(get<SaveMemoryTool>(), ToolSourceInfo.BUILTIN)
        } catch (e: Exception) {
            Log.e("ToolModule", "Failed to register save_memory: ${e.message}")
        }

        // ... JS tool loading ...
    }
}
```

## Implementation Steps

### Phase 1: Wire `onSessionSwitch` (ChatViewModel + FeatureModule)
1. [ ] Add `memoryTriggerManager: MemoryTriggerManager? = null` parameter to `ChatViewModel` constructor
2. [ ] Add session switch detection logic to `ChatViewModel.initialize()`
3. [ ] Update `ChatViewModel` DI registration in `FeatureModule.kt` to inject `MemoryTriggerManager`
4. [ ] Add unit test: verify `onSessionSwitch` is called when switching sessions
5. [ ] Add unit test: verify `onSessionSwitch` is NOT called for `initialize(null)`
6. [ ] Add unit test: verify `onSessionSwitch` is NOT called when same session ID

### Phase 2: Wire `onDayChange` (OneclawApplication + MemoryTriggerManager)
1. [ ] Add `onDayChangeForActiveSession()` method to `MemoryTriggerManager`
2. [ ] Add `checkDayChange()` private method to `OneclawApplication`
3. [ ] Extend the existing `ProcessLifecycleOwner` observer to handle `onStart` alongside `onStop`
4. [ ] Add unit test: verify `onDayChangeForActiveSession` calls `flushActiveSession` pattern
5. [ ] Manual test: verify SharedPreferences date storage and comparison

### Phase 3: `SaveMemoryTool` (new tool + MemoryManager + ToolModule)
1. [ ] Add `saveToLongTermMemory(content)` method to `MemoryManager`
2. [ ] Create `SaveMemoryTool` in `tool/builtin/SaveMemoryTool.kt`
3. [ ] Register `SaveMemoryTool` in `ToolModule.kt` with `ToolSourceInfo.BUILTIN`
4. [ ] Add unit test: `SaveMemoryTool` execute with valid content
5. [ ] Add unit test: `SaveMemoryTool` execute with empty content (validation error)
6. [ ] Add unit test: `SaveMemoryTool` execute with content exceeding 5,000 chars (validation error)
7. [ ] Add unit test: `SaveMemoryTool` execute when `saveToLongTermMemory` fails
8. [ ] Add unit test: `MemoryManager.saveToLongTermMemory` appends and indexes content
9. [ ] Integration test: verify `save_memory` tool appears in `ToolRegistry.getAllToolDefinitions()`

## Testing Strategy

### Unit Tests

**ChatViewModel Session Switch Tests:**
- Verify `onSessionSwitch(previousId)` is called when `initialize("session-B")` is called while `uiState.sessionId == "session-A"`
- Verify `onSessionSwitch` is NOT called when `initialize(null)` is called
- Verify `onSessionSwitch` is NOT called when `initialize("session-A")` is called while `uiState.sessionId == "session-A"` (same session)
- Verify `onSessionSwitch` is NOT called when `uiState.sessionId == null` (no previous session)
- Verify that `memoryTriggerManager` being null does not cause a crash (backward compatibility)

**MemoryTriggerManager Day-Change Test:**
- Verify `onDayChangeForActiveSession()` delegates to `flushActiveSession()` internally

**MemoryManager SaveToLongTermMemory Tests:**
- Verify `appendMemory()` is called with the provided content
- Verify `indexContent()` is called after successful append
- Verify indexing failure does not cause the overall operation to fail
- Verify file I/O failure returns `Result.failure`

**SaveMemoryTool Tests:**
- Execute with valid content: returns `ToolResult.success`
- Execute with empty content: returns `ToolResult.error("validation_error", ...)`
- Execute with null content: returns `ToolResult.error("validation_error", ...)`
- Execute with content at exactly 5,000 chars: returns `ToolResult.success`
- Execute with content at 5,001 chars: returns `ToolResult.error("validation_error", ...)`
- Execute when `saveToLongTermMemory` returns failure: returns `ToolResult.error("save_failed", ...)`

### Integration Tests
- Verify `save_memory` tool is registered in `ToolRegistry` and accessible via `ToolExecutionEngine`
- End-to-end: call `SaveMemoryTool.execute()`, verify content appears in `LongTermMemoryManager.readMemory()`

### Manual Tests
- Switch between two sessions in the app and verify daily log is flushed for the previous session
- Leave app overnight, reopen the next day, and verify day-change trigger fires
- Chat with AI: "Remember that I prefer dark mode in all my apps" -- verify content saved to MEMORY.md
- Chat with AI: "Save a summary of today's discussion to memory" -- verify summary saved
- Open a new conversation and verify previously saved memory appears in the system prompt context
- Try saving very long content (over 5,000 chars) and verify error message

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
