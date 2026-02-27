# Test Strategy: OneClawShadow

## Document Information
- **Related Architecture**: [RFC-000 (Overall Architecture)](../rfc/architecture/RFC-000-overall-architecture.md)
- **Related RFCs**: RFC-001 through RFC-005
- **Created**: 2026-02-27
- **Last Updated**: 2026-02-27
- **Status**: Draft

## Overview

### Background
OneClawShadow uses a documentation-driven development approach where AI generates code from RFCs. Automated testing is essential to verify that generated code matches the specifications. This document defines a two-layer testing strategy: Layer 1 (fully automated, no human interaction) and Layer 2 (AI-controlled emulator with visual verification).

### Goals
1. Define a comprehensive test strategy that AI can execute autonomously
2. Layer 1: Unit tests, Compose UI tests, and screenshot tests that run via Gradle commands
3. Layer 2: adb-based visual verification on a real emulator, controlled by AI
4. Provide enough detail for AI to generate test code and execute tests without human intervention
5. Use real API keys (from environment variables) for end-to-end verification -- never hardcoded, never sent to models

### Testing Principles
- **AI-executable**: Every test can be triggered by a shell command
- **Fast feedback**: Unit tests run in seconds, not minutes
- **Visual verification**: Screenshot tests and adb screenshots catch UI regressions
- **Real API validation**: Layer 2 uses real provider APIs for final verification
- **No secrets in code**: API keys read from environment variables only

## Technology Stack

### Testing Libraries

| Library | Version | Purpose | Layer |
|---------|---------|---------|-------|
| JUnit 5 | 5.10.x | Test framework | Both |
| MockK | 1.13.x | Kotlin mocking | L1 |
| Turbine | 1.0.x | Flow testing | L1 |
| Kotlin Coroutines Test | 1.8.x | Coroutine testing (runTest, TestDispatcher) | L1 |
| Roborazzi | 1.20.x+ | JVM screenshot testing (Robolectric-based) | L1 |
| Robolectric | 4.12.x | Android framework on JVM (required by Roborazzi) | L1 |
| Compose UI Test | (Jetpack) | Compose component testing | L1 |
| adb | (Android SDK) | Device control, screenshots, app install | L2 |

### Emulator Configuration

| Property | Value |
|----------|-------|
| AVD Name | Medium Phone API 36.1 |
| AVD ID | Medium_Phone_API_36.1 |
| Android Version | 16.0 (Baklava) |
| API Level | 36.1 |
| Resolution (px) | 1080 x 2400 |
| Resolution (dp) | 412 x 915 |
| Density | 420 dpi |
| ABI | arm64-v8a |

### Environment Variables

API keys are stored as environment variables on the developer's machine. They are **never hardcoded** in source code, test code, or configuration files. They are **never sent to AI models** -- they are only used by the app or test harness at runtime.

| Variable | Provider | Usage |
|----------|----------|-------|
| `ONECLAW_OPENAI_API_KEY` | OpenAI | Layer 2 end-to-end tests |
| `ONECLAW_ANTHROPIC_API_KEY` | Anthropic | **Primary** for Layer 2 end-to-end tests |
| `ONECLAW_GEMINI_API_KEY` | Google Gemini | Layer 2 end-to-end tests |

**How API keys reach the emulator in Layer 2:**
```bash
# Read from local environment, inject into emulator via adb
adb shell "setprop oneclaw.test.anthropic_key $(printenv ONECLAW_ANTHROPIC_API_KEY)"
```
Or, for instrumented tests that need keys:
```bash
# Pass as instrumentation arguments
adb shell am instrument \
  -e ANTHROPIC_API_KEY "$(printenv ONECLAW_ANTHROPIC_API_KEY)" \
  -w com.oneclaw.shadow.test/androidx.test.runner.AndroidJUnitRunner
```
Or, for Layer 2 adb UI testing, the AI types the key into the app's provider setup screen:
```bash
# AI reads key from environment, types it into the text field via adb
API_KEY=$(printenv ONECLAW_ANTHROPIC_API_KEY)
adb shell input text "$API_KEY"
```

## Test Directory Structure

```
app/src/
├── test/kotlin/com/oneclaw/shadow/        # Layer 1: Unit tests (JVM)
│   ├── core/
│   │   └── model/                          # Domain model tests (if any)
│   ├── data/
│   │   ├── repository/                     # Repository implementation tests
│   │   │   ├── AgentRepositoryImplTest.kt
│   │   │   ├── ProviderRepositoryImplTest.kt
│   │   │   ├── SessionRepositoryImplTest.kt
│   │   │   └── MessageRepositoryImplTest.kt
│   │   └── remote/
│   │       ├── adapter/                    # API adapter tests
│   │       │   ├── OpenAiAdapterTest.kt
│   │       │   ├── AnthropicAdapterTest.kt
│   │       │   └── GeminiAdapterTest.kt
│   │       └── sse/
│   │           └── SseParserTest.kt
│   ├── tool/
│   │   ├── engine/
│   │   │   ├── ToolExecutionEngineTest.kt
│   │   │   └── ToolRegistryTest.kt
│   │   └── builtin/
│   │       ├── GetCurrentTimeToolTest.kt
│   │       ├── ReadFileToolTest.kt
│   │       ├── WriteFileToolTest.kt
│   │       └── HttpRequestToolTest.kt
│   ├── feature/
│   │   ├── chat/
│   │   │   ├── ChatViewModelTest.kt
│   │   │   ├── ChatEventTest.kt
│   │   │   └── usecase/
│   │   │       ├── SendMessageUseCaseTest.kt
│   │   │       └── MessageToApiMapperTest.kt
│   │   ├── agent/
│   │   │   ├── AgentListViewModelTest.kt
│   │   │   ├── AgentDetailViewModelTest.kt
│   │   │   └── usecase/
│   │   │       ├── CreateAgentUseCaseTest.kt
│   │   │       ├── CloneAgentUseCaseTest.kt
│   │   │       └── DeleteAgentUseCaseTest.kt
│   │   ├── provider/
│   │   │   ├── ProviderListViewModelTest.kt
│   │   │   ├── ProviderDetailViewModelTest.kt
│   │   │   └── usecase/
│   │   │       ├── TestConnectionUseCaseTest.kt
│   │   │       └── FetchModelsUseCaseTest.kt
│   │   └── session/
│   │       ├── SessionListViewModelTest.kt
│   │       └── usecase/
│   │           ├── CreateSessionUseCaseTest.kt
│   │           ├── DeleteSessionUseCaseTest.kt
│   │           ├── GenerateTitleUseCaseTest.kt
│   │           └── CleanupSoftDeletedUseCaseTest.kt
│   ├── screenshot/                         # Roborazzi screenshot tests
│   │   ├── ChatScreenScreenshotTest.kt
│   │   ├── AgentScreenScreenshotTest.kt
│   │   ├── ProviderScreenScreenshotTest.kt
│   │   └── SessionDrawerScreenshotTest.kt
│   └── testutil/                           # Shared test utilities
│       ├── TestDataFactory.kt              # Domain model builders
│       ├── FakeRepositories.kt             # In-memory repository implementations
│       ├── FakeModelApiAdapter.kt          # Mock API adapter for streaming
│       ├── FakeToolExecutionEngine.kt      # Mock tool engine
│       └── MainDispatcherRule.kt           # JUnit rule for coroutine testing
│
├── androidTest/kotlin/com/oneclaw/shadow/  # Layer 1: Instrumented tests
│   ├── data/local/
│   │   ├── dao/
│   │   │   ├── AgentDaoTest.kt
│   │   │   ├── ProviderDaoTest.kt
│   │   │   ├── ModelDaoTest.kt
│   │   │   ├── SessionDaoTest.kt
│   │   │   ├── MessageDaoTest.kt
│   │   │   └── SettingsDaoTest.kt
│   │   └── db/
│   │       └── MigrationTest.kt            # Future: DB migration tests
│   ├── feature/
│   │   ├── chat/
│   │   │   └── ChatScreenTest.kt           # Compose UI tests
│   │   ├── agent/
│   │   │   └── AgentScreenTest.kt
│   │   ├── provider/
│   │   │   └── ProviderScreenTest.kt
│   │   └── session/
│   │       └── SessionDrawerTest.kt
│   └── testutil/
│       └── TestDatabaseHelper.kt
│
└── test/resources/                         # Test resources
    └── screenshots/                        # Roborazzi baseline screenshots
        └── (auto-generated .png files)
```

## Shared Test Utilities

### TestDataFactory

Centralized factory for creating domain model instances in tests. All fields have sensible defaults, overridable via named parameters.

```kotlin
/**
 * Located in: test/kotlin/com/oneclaw/shadow/testutil/TestDataFactory.kt
 */
object TestDataFactory {

    // --- Agent ---
    fun agent(
        id: String = "agent-test",
        name: String = "Test Agent",
        description: String? = "A test agent",
        systemPrompt: String = "You are a helpful assistant.",
        toolIds: List<String> = listOf("get_current_time", "read_file"),
        preferredProviderId: String? = null,
        preferredModelId: String? = null,
        isBuiltIn: Boolean = false,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Agent(id, name, description, systemPrompt, toolIds,
        preferredProviderId, preferredModelId, isBuiltIn, createdAt, updatedAt)

    fun generalAssistant() = agent(
        id = "agent-general-assistant",
        name = "General Assistant",
        systemPrompt = "You are a helpful, harmless, and honest assistant.",
        toolIds = listOf("get_current_time", "read_file", "write_file", "http_request"),
        isBuiltIn = true
    )

    // --- Provider ---
    fun provider(
        id: String = "provider-test",
        name: String = "Test Provider",
        type: ProviderType = ProviderType.ANTHROPIC,
        apiBaseUrl: String = "https://api.anthropic.com",
        isPreConfigured: Boolean = false,
        isActive: Boolean = true,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Provider(id, name, type, apiBaseUrl, isPreConfigured, isActive, createdAt, updatedAt)

    fun anthropicProvider() = provider(
        id = "provider-anthropic",
        name = "Anthropic",
        type = ProviderType.ANTHROPIC,
        apiBaseUrl = "https://api.anthropic.com",
        isPreConfigured = true
    )

    fun openAiProvider() = provider(
        id = "provider-openai",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true
    )

    fun geminiProvider() = provider(
        id = "provider-gemini",
        name = "Google Gemini",
        type = ProviderType.GEMINI,
        apiBaseUrl = "https://generativelanguage.googleapis.com",
        isPreConfigured = true
    )

    // --- Model ---
    fun model(
        id: String = "claude-sonnet-4-20250514",
        displayName: String? = "Claude Sonnet 4",
        providerId: String = "provider-anthropic",
        isDefault: Boolean = false,
        source: ModelSource = ModelSource.PRESET
    ) = AiModel(id, displayName, providerId, isDefault, source)

    // --- Session ---
    fun session(
        id: String = "session-test",
        title: String = "Test Session",
        currentAgentId: String = "agent-general-assistant",
        messageCount: Int = 0,
        lastMessagePreview: String? = null,
        isActive: Boolean = false,
        deletedAt: Long? = null,
        createdAt: Long = 1000L,
        updatedAt: Long = 1000L
    ) = Session(id, title, currentAgentId, messageCount,
        lastMessagePreview, isActive, deletedAt, createdAt, updatedAt)

    // --- Message ---
    fun userMessage(
        id: String = "msg-user-1",
        sessionId: String = "session-test",
        content: String = "Hello, how are you?",
        createdAt: Long = 1000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.USER,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun aiMessage(
        id: String = "msg-ai-1",
        sessionId: String = "session-test",
        content: String = "I'm doing well, thank you!",
        thinkingContent: String? = null,
        modelId: String? = "claude-sonnet-4-20250514",
        providerId: String? = "provider-anthropic",
        tokenCountInput: Int? = 10,
        tokenCountOutput: Int? = 20,
        createdAt: Long = 2000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.AI_RESPONSE,
        content = content, thinkingContent = thinkingContent,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = tokenCountInput, tokenCountOutput = tokenCountOutput,
        modelId = modelId, providerId = providerId, createdAt = createdAt
    )

    fun toolCallMessage(
        id: String = "msg-toolcall-1",
        sessionId: String = "session-test",
        toolCallId: String = "call_123",
        toolName: String = "get_current_time",
        toolInput: String = """{"timezone":"UTC"}""",
        createdAt: Long = 3000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.TOOL_CALL,
        content = "", thinkingContent = null,
        toolCallId = toolCallId, toolName = toolName, toolInput = toolInput,
        toolOutput = null, toolStatus = ToolCallStatus.PENDING, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun toolResultMessage(
        id: String = "msg-toolresult-1",
        sessionId: String = "session-test",
        toolCallId: String = "call_123",
        toolName: String = "get_current_time",
        toolOutput: String = "2026-02-27T15:30:00Z",
        toolStatus: ToolCallStatus = ToolCallStatus.SUCCESS,
        durationMs: Long = 50L,
        createdAt: Long = 4000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.TOOL_RESULT,
        content = "", thinkingContent = null,
        toolCallId = toolCallId, toolName = toolName, toolInput = null,
        toolOutput = toolOutput, toolStatus = toolStatus, toolDurationMs = durationMs,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun errorMessage(
        id: String = "msg-error-1",
        sessionId: String = "session-test",
        content: String = "API request failed.",
        createdAt: Long = 5000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.ERROR,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    fun systemMessage(
        id: String = "msg-system-1",
        sessionId: String = "session-test",
        content: String = "Switched to Code Helper",
        createdAt: Long = 6000L
    ) = Message(
        id = id, sessionId = sessionId, type = MessageType.SYSTEM,
        content = content, thinkingContent = null,
        toolCallId = null, toolName = null, toolInput = null,
        toolOutput = null, toolStatus = null, toolDurationMs = null,
        tokenCountInput = null, tokenCountOutput = null,
        modelId = null, providerId = null, createdAt = createdAt
    )

    // --- ToolResult ---
    fun toolResultSuccess(
        result: String = "Operation completed."
    ) = ToolResult(
        status = ToolResultStatus.SUCCESS,
        result = result,
        errorType = null,
        errorMessage = null
    )

    fun toolResultError(
        errorType: String = "execution_error",
        errorMessage: String = "Something went wrong."
    ) = ToolResult(
        status = ToolResultStatus.ERROR,
        result = null,
        errorType = errorType,
        errorMessage = errorMessage
    )
}
```

### MainDispatcherRule

```kotlin
/**
 * JUnit 5 extension that replaces Dispatchers.Main with a TestDispatcher.
 * Located in: test/kotlin/com/oneclaw/shadow/testutil/MainDispatcherRule.kt
 */
class MainDispatcherRule(
    private val dispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : BeforeEachCallback, AfterEachCallback {
    override fun beforeEach(context: ExtensionContext?) {
        Dispatchers.setMain(dispatcher)
    }
    override fun afterEach(context: ExtensionContext?) {
        Dispatchers.resetMain()
    }
}
```

### FakeRepositories

In-memory implementations of repository interfaces for unit tests. These are simpler and faster than MockK when the test needs a working repository (e.g., testing a UseCase that reads + writes).

```kotlin
/**
 * In-memory MessageRepository for testing.
 * Located in: test/kotlin/com/oneclaw/shadow/testutil/FakeRepositories.kt
 */
class FakeMessageRepository : MessageRepository {
    private val messages = mutableListOf<Message>()
    private val _flow = MutableStateFlow<List<Message>>(emptyList())

    override fun getMessagesForSession(sessionId: String): Flow<List<Message>> =
        _flow.map { it.filter { m -> m.sessionId == sessionId } }

    override suspend fun addMessage(message: Message): Message {
        val saved = message.copy(
            id = if (message.id.isBlank()) UUID.randomUUID().toString() else message.id,
            createdAt = if (message.createdAt == 0L) System.currentTimeMillis() else message.createdAt
        )
        messages.add(saved)
        _flow.value = messages.toList()
        return saved
    }

    override suspend fun updateMessage(message: Message) {
        val index = messages.indexOfFirst { it.id == message.id }
        if (index >= 0) { messages[index] = message; _flow.value = messages.toList() }
    }

    override suspend fun deleteMessagesForSession(sessionId: String) {
        messages.removeAll { it.sessionId == sessionId }
        _flow.value = messages.toList()
    }

    override suspend fun getMessageCount(sessionId: String): Int =
        messages.count { it.sessionId == sessionId }

    override suspend fun getMessagesSnapshot(sessionId: String): List<Message> =
        messages.filter { it.sessionId == sessionId }

    override suspend fun deleteMessage(id: String) {
        messages.removeAll { it.id == id }
        _flow.value = messages.toList()
    }
}

// Similar fakes for AgentRepository, SessionRepository, ProviderRepository...
// Each stores data in a MutableList and emits via MutableStateFlow.
```

## Layer 1: Automated Tests

### 1A. Unit Tests (JVM)

Run command: `./gradlew test`

These run on JVM without an Android device. They test business logic, state management, data mapping, and parsing.

#### Feature: Chat Interaction (RFC-001)

**SendMessageUseCaseTest**

| Test | Description | Approach |
|------|-------------|----------|
| `execute normal message returns streaming events` | Send a user message, mock adapter returns text deltas, verify ChatEvent sequence | Mock adapter emits TextDelta + Done; collect flow; assert StreamingText events + ResponseComplete |
| `execute with tool calls loops correctly` | Model returns tool call, tool executes, model called again | Mock adapter: round 0 emits ToolCallStart + Done; mock ToolEngine returns success; round 1 emits TextDelta + Done; verify 2 rounds |
| `execute with parallel tool calls executes all` | Model returns 3 tool calls at once | Mock adapter emits 3 ToolCallStart; verify all 3 ToolCallCompleted events |
| `execute max rounds reached emits error` | 100+ rounds of tool calls | Mock adapter always returns tool calls; verify Error event with TOOL_ERROR |
| `execute with no provider emits error` | No model resolved | Mock resolveModel returns null; verify Error with VALIDATION_ERROR |
| `execute with missing api key emits error` | Provider exists but no key | Mock apiKeyStorage returns null; verify Error with AUTH_ERROR |
| `execute with API 401 emits auth error` | API returns 401 | Mock adapter emits StreamEvent.Error(401); verify ChatEvent.Error with AUTH_ERROR, not retryable |
| `execute with API 429 emits retryable error` | Rate limited | Mock adapter emits StreamEvent.Error(429); verify ChatEvent.Error, retryable |
| `execute with network error emits retryable error` | Network failure | Mock adapter throws IOException; verify ChatEvent.Error with NETWORK_ERROR, retryable |
| `execute saves user message to DB` | Verify persistence | After execute, check FakeMessageRepository has USER message |
| `execute saves AI response to DB` | Verify persistence | After stream completes, check FakeMessageRepository has AI_RESPONSE message |
| `execute saves tool call and result to DB` | Verify tool persistence | After tool execution, check DB has TOOL_CALL and TOOL_RESULT messages |
| `execute updates session stats after response` | Session preview updated | After ResponseComplete, verify sessionRepository.updateMessageStats was called |
| `execute resolves agent preferred model first` | Agent has preferred model | Set agent.preferredModelId; verify that model is used, not global default |
| `execute falls back to global default model` | Agent has no preference | Agent preferred = null; verify global default model is used |
| `cancellation does not emit error` | User stops generation | Cancel the flow collection; verify no Error event, CancellationException re-thrown |

**MessageToApiMapperTest**

| Test | Description |
|------|-------------|
| `maps USER message to ApiMessage.User` | |
| `maps AI_RESPONSE message to ApiMessage.Assistant` | |
| `maps TOOL_CALL message to ApiMessage.Assistant with toolCalls` | |
| `maps TOOL_RESULT message to ApiMessage.ToolResult` | |
| `excludes ERROR messages` | ERROR type not in output |
| `excludes SYSTEM messages` | SYSTEM type not in output |
| `preserves message order` | |
| `handles empty message list` | Returns empty list |
| `handles messages with null optional fields` | toolCallId, toolName etc. null |

**ChatViewModelTest**

| Test | Description |
|------|-------------|
| `initialize with null sessionId starts new conversation` | uiState has null sessionId, default agent |
| `initialize with sessionId loads existing session` | uiState populated from session + messages |
| `sendMessage clears input text` | inputText becomes "" |
| `sendMessage creates session on first message` | Lazy creation: sessionId goes from null to non-null |
| `sendMessage sets isStreaming true` | During streaming, isStreaming = true |
| `sendMessage accumulates streaming text` | streamingText grows as StreamingText events arrive |
| `sendMessage accumulates thinking text` | streamingThinkingText grows |
| `sendMessage finishes streaming on ResponseComplete` | isStreaming = false, messages reloaded |
| `sendMessage triggers title generation on first message` | generateTitleUseCase called after first ResponseComplete |
| `sendMessage does not trigger title on subsequent messages` | Only first message triggers title |
| `stopGeneration cancels streaming job` | streamingJob cancelled, partial text saved |
| `regenerate removes messages after last user message` | Messages after lastUserIndex deleted from DB and UI |
| `regenerate re-sends with same user text` | SendMessageUseCase called again with same text |
| `switchAgent updates session and inserts system message` | SessionRepository.updateCurrentAgent called; SYSTEM message added |
| `switchAgent blocked during streaming` | If isStreaming, switchAgent does nothing |
| `retryLastMessage removes error and regenerates` | ERROR message deleted, then regenerate called |
| `setAutoScroll updates shouldAutoScroll` | |
| `checkProviderStatus updates hasConfiguredProvider` | |

**SseParserTest**

| Test | Description |
|------|-------------|
| `parses single data line` | `data: {"text":"hi"}` -> SseEvent(null, `{"text":"hi"}`) |
| `parses event type + data` | `event: message_start\ndata: {...}` -> SseEvent("message_start", ...) |
| `handles multiple events separated by blank lines` | Multiple SseEvents emitted |
| `handles data: [DONE]` | Emitted as SseEvent with data "[DONE]" |
| `ignores comment lines (starting with :)` | Lines starting with `:` are skipped |
| `handles empty data field` | `data: ` -> SseEvent(null, "") |
| `handles malformed lines gracefully` | No crash on unexpected format |

**Adapter SSE Mapping Tests** (one test class per adapter)

| Test (per adapter) | Description |
|---------------------|-------------|
| `maps text content to TextDelta` | |
| `maps thinking content to ThinkingDelta` | (Anthropic only) |
| `maps tool call start to ToolCallStart` | |
| `maps tool call arguments to ToolCallDelta` | |
| `maps tool call end to ToolCallEnd` | |
| `maps usage to Usage event` | |
| `maps error to Error event` | |
| `maps stream end to Done` | |
| `handles unexpected JSON fields gracefully` | No crash on extra fields |

#### Feature: Agent Management (RFC-002)

**CreateAgentUseCaseTest**

| Test | Description |
|------|-------------|
| `creates agent with valid data` | Returns created agent |
| `generates UUID for new agent` | Agent ID is non-empty |
| `validates empty name returns error` | AppResult.Error on blank name |
| `validates empty system prompt returns error` | AppResult.Error on blank prompt |

**CloneAgentUseCaseTest**

| Test | Description |
|------|-------------|
| `clones agent with new ID` | New agent has different ID, same content |
| `cloned agent name has "Copy of" prefix` | |
| `cloned agent is not built-in` | isBuiltIn = false |

**DeleteAgentUseCaseTest**

| Test | Description |
|------|-------------|
| `deletes non-built-in agent` | Agent removed from repository |
| `rejects deleting built-in agent` | Returns error |
| `updates sessions referencing deleted agent to General Assistant` | sessionRepository.updateAgentForSessions called |

#### Feature: Provider Management (RFC-003)

**TestConnectionUseCaseTest**

| Test | Description |
|------|-------------|
| `returns success on valid connection` | Mock adapter returns success |
| `returns error when no API key` | apiKeyStorage returns null |
| `returns error on network failure` | Mock adapter throws |

**FetchModelsUseCaseTest**

| Test | Description |
|------|-------------|
| `fetches and saves models` | Mock adapter returns model list; verify saved to repository |
| `handles fetch failure gracefully` | Returns error, does not crash |

#### Feature: Tool System (RFC-004)

**ToolExecutionEngineTest**

| Test | Description |
|------|-------------|
| `executes registered tool successfully` | Returns ToolResult(SUCCESS) |
| `returns error for unregistered tool` | tool_not_found error |
| `returns error when tool not in available list` | tool_not_available error |
| `returns timeout error when tool exceeds timeout` | Tool takes too long |
| `returns permission_denied when permission not granted` | Mock permission denied |
| `catches tool execution exceptions` | Tool throws; returns execution_error |

**ToolRegistryTest**

| Test | Description |
|------|-------------|
| `registers and retrieves tool by name` | |
| `getAllTools returns all registered tool definitions` | |
| `getToolsByIds returns matching tools` | |
| `getTool returns null for unknown name` | |

**Built-in Tool Tests** (one class per tool)

| Test | Description |
|------|-------------|
| GetCurrentTimeTool: `returns current time in default timezone` | |
| GetCurrentTimeTool: `returns time in specified timezone` | |
| GetCurrentTimeTool: `returns error for invalid timezone` | |
| ReadFileTool: `reads existing file content` | (mock file system) |
| ReadFileTool: `returns error for non-existent file` | |
| WriteFileTool: `writes content to file` | |
| WriteFileTool: `returns error on write failure` | |
| HttpRequestTool: `performs GET request and returns response` | (MockWebServer) |
| HttpRequestTool: `truncates response over 100KB` | |
| HttpRequestTool: `returns error on network failure` | |
| HttpRequestTool: `respects timeout` | |

#### Feature: Session Management (RFC-005)

**CreateSessionUseCaseTest**

| Test | Description |
|------|-------------|
| `creates session with agent ID` | Session created in repository |
| `generates UUID for session ID` | |

**DeleteSessionUseCaseTest**

| Test | Description |
|------|-------------|
| `soft deletes session` | deletedAt set |
| `restore undoes soft delete` | deletedAt cleared |

**GenerateTitleUseCaseTest**

| Test | Description |
|------|-------------|
| `generateTruncatedTitle truncates at 50 chars` | |
| `generateAiTitle calls adapter and updates session` | Mock adapter returns title |
| `generateAiTitle uses lightweight model` | Verify claude-haiku-4-20250414 for Anthropic |
| `generateAiTitle falls back to current model if lightweight not found` | |

**CleanupSoftDeletedUseCaseTest**

| Test | Description |
|------|-------------|
| `hard deletes all soft-deleted sessions` | |

**SessionListViewModelTest**

| Test | Description |
|------|-------------|
| `loads sessions from repository` | uiState has session list |
| `deleteSession triggers soft delete + undo state` | |
| `undoDelete restores session` | |
| `batchDelete deletes multiple sessions` | |
| `renameSession updates title` | |

### 1B. Instrumented Tests (Android Device)

Run command: `./gradlew connectedAndroidTest`

Requires a running emulator or connected device.

#### Room DAO Tests

Each DAO test uses an in-memory database and verifies CRUD operations.

**AgentDaoTest**

| Test | Description |
|------|-------------|
| `insert and query agent` | |
| `update agent` | |
| `delete agent` | |
| `query built-in agents` | |
| `getAllAgents returns Flow that updates on insert` | |

**ProviderDaoTest**

| Test | Description |
|------|-------------|
| `insert and query provider` | |
| `query active providers` | |
| `cascade delete removes models` | |

**SessionDaoTest**

| Test | Description |
|------|-------------|
| `insert and query session` | |
| `soft delete sets deletedAt` | |
| `query excludes soft-deleted sessions` | |
| `hard delete removes session` | |
| `cascade delete removes messages` | |
| `query ordered by updatedAt descending` | |

**MessageDaoTest**

| Test | Description |
|------|-------------|
| `insert and query messages for session` | |
| `delete single message` | |
| `delete all messages for session` | |
| `getMessageCount returns correct count` | |
| `getMessagesSnapshot returns non-reactive list` | |

**ModelDaoTest**

| Test | Description |
|------|-------------|
| `insert and query models for provider` | |
| `query default model` | |
| `set default model` | |

#### Compose UI Tests

Test composable behavior (not visual appearance -- that's for screenshot tests).

**ChatScreenTest**

| Test | Description |
|------|-------------|
| `empty state shows greeting text` | "How can I help you today?" displayed |
| `send button disabled when input empty` | |
| `send button disabled when no provider configured` | |
| `send button enabled when text entered and provider exists` | |
| `stop button appears during streaming` | |
| `user message bubble appears after send` | |
| `thinking block is collapsed by default` | |
| `thinking block expands on click` | |
| `error message shows retry button when retryable` | |
| `system message displayed centered` | |
| `agent name displayed in top bar` | |

### 1C. Screenshot Tests (Roborazzi)

Run command: `./gradlew recordRoborazziDebug` (record baselines) / `./gradlew verifyRoborazziDebug` (compare)

Roborazzi captures screenshots of Compose components on JVM (via Robolectric). AI can view the generated PNG files to verify visual correctness.

**Screenshots to capture:**

| Component | Variants |
|-----------|----------|
| EmptyChatState | Default |
| UserMessageBubble | Short text, long text |
| AiMessageBubble | Plain text, with thinking block, with model badge |
| ThinkingBlock | Collapsed, expanded |
| ToolCallCard | Pending, executing, success, error |
| ToolResultCard | Success collapsed, success expanded, error |
| ErrorMessageCard | Retryable, not retryable |
| SystemMessageCard | Default |
| ChatInput | Empty, with text, streaming (stop button) |
| ChatTopBar | Default |
| StreamingCursor | Default |
| AgentSelectorSheet | With 3 agents |
| SessionDrawerContent | With 5 sessions, empty |
| ProviderListScreen | With 3 providers |

**Screenshot test structure:**
```kotlin
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [33])
class ChatComponentScreenshotTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun emptyState() {
        composeTestRule.setContent {
            OneClawShadowTheme {
                EmptyChatState()
            }
        }
        composeTestRule.onRoot().captureRoboImage("EmptyChatState.png")
    }

    @Test
    fun userMessageBubble_shortText() {
        composeTestRule.setContent {
            OneClawShadowTheme {
                UserMessageBubble(
                    content = "Hello!",
                    onCopy = {}
                )
            }
        }
        composeTestRule.onRoot().captureRoboImage("UserMessageBubble_short.png")
    }

    // ... more screenshot tests
}
```

**Baseline workflow:**
1. First run: `./gradlew recordRoborazziDebug` generates baseline PNGs in `test/resources/screenshots/`
2. AI reviews each PNG to confirm correctness
3. Subsequent runs: `./gradlew verifyRoborazziDebug` compares against baselines
4. If a test fails, AI can view both the baseline and actual PNGs to determine if the change is intentional

## Layer 2: adb Visual Verification

### Prerequisites

1. Emulator `Medium_Phone_API_36.1` is running (started by user or via `emulator -avd Medium_Phone_API_36.1`)
2. `adb devices` shows the emulator
3. Environment variables set: `ONECLAW_ANTHROPIC_API_KEY`, `ONECLAW_OPENAI_API_KEY`, `ONECLAW_GEMINI_API_KEY`
4. App APK built: `./gradlew assembleDebug`

### adb Commands Reference

```bash
# Check emulator is connected
adb devices

# Install the app
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Take a screenshot
adb exec-out screencap -p > /tmp/screen.png

# Tap at coordinates (x, y) in pixels
adb shell input tap 540 1200

# Swipe (x1 y1 x2 y2 duration_ms)
adb shell input swipe 540 1800 540 600 300

# Type text
adb shell input text "Hello"

# Press back
adb shell input keyevent KEYCODE_BACK

# Press home
adb shell input keyevent KEYCODE_HOME

# Launch app
adb shell am start -n com.oneclaw.shadow/.MainActivity

# Clear app data (fresh start)
adb shell pm clear com.oneclaw.shadow

# Long press (tap with duration)
adb shell input swipe 540 1200 540 1200 1000

# Get current activity (verify which screen is showing)
adb shell dumpsys activity activities | head -20
```

### Coordinate Map (1080x2400 px, 420 dpi)

Key UI element positions (approximate, may need adjustment):

| Element | Approx Position (px) | Notes |
|---------|----------------------|-------|
| Hamburger menu (top-left) | (80, 120) | Opens drawer |
| Agent name (top-center) | (540, 120) | Opens agent selector |
| Settings gear (top-right) | (1000, 120) | Opens settings |
| Input text field | (440, 2250) | Tap to focus |
| Send button | (980, 2250) | Right of input |
| Stop button (during streaming) | (980, 2250) | Same position as send |
| Scroll-to-bottom FAB | (980, 2100) | Bottom-right |
| First session in drawer | (400, 300) | After drawer opens |
| "New Conversation" in drawer | (400, 200) | Top of drawer |

**Note**: These are estimates. AI should take a screenshot first, analyze it, then determine exact tap coordinates.

### Verification Flows

Each flow follows the pattern: perform actions via adb -> take screenshot -> AI analyzes screenshot to verify.

#### Flow 1: First Launch + Provider Setup

```
Goal: Verify welcome screen, skip to chat, then set up Anthropic provider.

Steps:
1. adb shell pm clear com.oneclaw.shadow      # Fresh install state
2. adb shell am start -n com.oneclaw.shadow/.MainActivity
3. Screenshot -> Verify: Welcome/Setup screen shown
4. Tap "Skip" button
5. Screenshot -> Verify: Empty chat screen with "How can I help you today?"
6. Tap Settings gear (top-right)
7. Screenshot -> Verify: Settings screen
8. Tap "Manage Providers"
9. Screenshot -> Verify: Provider list with pre-configured providers
10. Tap "Anthropic"
11. Screenshot -> Verify: Anthropic provider detail screen
12. Tap API key field, type key from $ONECLAW_ANTHROPIC_API_KEY
13. Tap "Test Connection"
14. Screenshot -> Verify: Connection success indicator
15. Navigate back to chat
16. Screenshot -> Verify: Chat screen, send button should be enabled now
```

#### Flow 2: Send Message + Streaming Response

```
Goal: Send a simple message and verify streaming response appears.

Prerequisites: Provider configured (Flow 1 completed).

Steps:
1. Tap input text field
2. Type "What is 2 + 2? Answer in one word."
3. Tap send button
4. Wait 1 second
5. Screenshot -> Verify: User message bubble visible, streaming in progress (stop button shown)
6. Wait 5 seconds (let response complete)
7. Screenshot -> Verify: AI response bubble with "Four" or similar, send button back
8. Verify: Session title should have updated (visible in top bar or drawer)
```

#### Flow 3: Tool Call Interaction

```
Goal: Trigger a tool call and verify tool call/result cards appear.

Steps:
1. Tap input field
2. Type "What time is it right now?"
3. Tap send
4. Wait 3 seconds
5. Screenshot -> Verify: ToolCallCard visible for "get_current_time"
6. Wait 5 seconds (let full response complete)
7. Screenshot -> Verify: ToolResultCard with time, AI response summarizing the time
```

#### Flow 4: Session Management

```
Goal: Verify drawer shows sessions, create new conversation, switch between them.

Steps:
1. Tap hamburger menu (top-left)
2. Screenshot -> Verify: Drawer open, at least 1 session listed
3. Tap "New Conversation"
4. Screenshot -> Verify: Empty chat screen
5. Type and send a message ("Hello from session 2")
6. Wait for response
7. Tap hamburger menu
8. Screenshot -> Verify: Drawer shows 2 sessions
9. Tap first session
10. Screenshot -> Verify: Previous conversation loaded with its messages
```

#### Flow 5: Agent Switching

```
Goal: Create a custom agent and switch to it mid-conversation.

Prerequisites: At least one message in current session.

Steps:
1. Navigate to Settings -> Agent Management
2. Tap "Create Agent"
3. Fill in: name "Code Helper", system prompt "You are a coding assistant."
4. Save
5. Navigate back to chat
6. Tap agent name in top bar
7. Screenshot -> Verify: Agent selector bottom sheet with "General Assistant" and "Code Helper"
8. Tap "Code Helper"
9. Screenshot -> Verify: System message "Switched to Code Helper" in chat, agent name updated in top bar
```

#### Flow 6: Error Handling

```
Goal: Verify error display when API key is invalid.

Steps:
1. Navigate to Settings -> Providers -> Anthropic
2. Change API key to "invalid-key-12345"
3. Save and go back to chat
4. Send a message
5. Wait 5 seconds
6. Screenshot -> Verify: Error message card in chat with "API key is invalid" or similar, Retry button visible
7. Navigate back, fix the API key
8. Go back to chat, tap Retry
9. Screenshot -> Verify: New response streaming after retry
```

#### Flow 7: Stop Generation

```
Goal: Verify stop generation saves partial text.

Steps:
1. Send a message that will produce a long response: "Write a 500 word essay about the ocean."
2. Wait 2 seconds (partial response should be streaming)
3. Tap stop button
4. Screenshot -> Verify: Partial AI response visible, send button back, streaming stopped
5. Send another message to verify chat still works
```

### Layer 2 AI Verification Method

For each screenshot taken:
1. AI reads the PNG file using the Read tool
2. AI analyzes the image content to verify expected UI elements
3. AI reports PASS/FAIL with explanation
4. If FAIL, AI suggests corrective action

Screenshots are saved to the `screenshots/layer2/` directory in the project root. These PNG files are gitignored (only the directory structure is tracked via `.gitkeep`).
```bash
adb exec-out screencap -p > screenshots/layer2/flow1-step3-welcome.png
adb exec-out screencap -p > screenshots/layer2/flow1-step5-empty-chat.png
# etc.
```

## Execution Commands Summary

```bash
# --- Layer 1 ---

# Run all unit tests (JVM, no device needed)
./gradlew test

# Run a specific test class
./gradlew test --tests "com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCaseTest"

# Run all instrumented tests (requires emulator)
./gradlew connectedAndroidTest

# Record Roborazzi screenshot baselines
./gradlew recordRoborazziDebug

# Verify screenshots against baselines
./gradlew verifyRoborazziDebug

# Generate coverage report
./gradlew jacocoTestReport
# Report at: app/build/reports/jacoco/jacocoTestReport/html/index.html

# --- Layer 2 ---

# Build debug APK
./gradlew assembleDebug

# Install on emulator
adb install -r app/build/outputs/apk/debug/app-debug.apk

# Take screenshot
adb exec-out screencap -p > screenshots/layer2/screenshot.png

# Full Layer 2 flow is executed by AI using adb commands interactively
```

## Coverage Targets

| Category | Target | Rationale |
|----------|--------|-----------|
| Domain layer (UseCases, Mappers) | > 90% | Core business logic, must be reliable |
| ViewModel layer | > 85% | State management, drives UI |
| Data layer (Repositories) | > 80% | Data access, persistence |
| Adapters (SSE parsing) | > 80% | Critical for streaming functionality |
| Tool implementations | > 80% | Tool execution must be reliable |
| UI layer (Composables) | > 60% | Covered more by screenshot + adb tests |
| **Overall** | **> 80%** | |

## Test Execution Order

When verifying a build, tests should be executed in this order:

1. `./gradlew test` -- Fast, catches most issues
2. `./gradlew connectedAndroidTest` -- Verifies DB and UI logic on device
3. `./gradlew verifyRoborazziDebug` -- Catches visual regressions
4. Layer 2 adb flows -- Final visual and integration verification

If step 1 fails, fix before proceeding. Each step builds confidence incrementally.

## References

- [RFC-000 Overall Architecture](../rfc/architecture/RFC-000-overall-architecture.md) -- Project structure, tech stack
- [RFC-001 Chat Interaction](../rfc/features/RFC-001-chat-interaction.md) -- Chat flow, test scenarios
- [RFC-002 Agent Management](../rfc/features/RFC-002-agent-management.md) -- Agent CRUD tests
- [RFC-003 Provider Management](../rfc/features/RFC-003-provider-management.md) -- Provider tests
- [RFC-004 Tool System](../rfc/features/RFC-004-tool-system.md) -- Tool execution tests
- [RFC-005 Session Management](../rfc/features/RFC-005-session-management.md) -- Session lifecycle tests

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-27 | 0.1 | Initial version: Two-layer test strategy, complete test catalog for all 5 features | - |
