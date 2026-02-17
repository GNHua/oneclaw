package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.llm.Choice
import com.tomandy.palmclaw.llm.FunctionCall
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.LlmResponse
import com.tomandy.palmclaw.llm.MessageResponse
import com.tomandy.palmclaw.llm.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for AgentCoordinator.
 *
 * Tests cover:
 * - Basic execution and response handling
 * - State transitions
 * - Conversation history management
 * - Error handling
 * - Cancellation
 * - Tool call detection (Phase 1)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AgentCoordinatorTest {

    private lateinit var mockLlmClient: LlmClient
    private lateinit var coordinator: AgentCoordinator
    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)

    private lateinit var mockToolRegistry: ToolRegistry
    private lateinit var mockToolExecutor: ToolExecutor
    private lateinit var mockMessageStore: MessageStore

    private val testSystemPrompt = "You are a helpful AI assistant. Be concise and accurate."

    @Before
    fun setup() {
        // Create mocks using MockK
        mockLlmClient = mockk<LlmClient>()
        mockToolRegistry = mockk<ToolRegistry>()
        mockToolExecutor = mockk<ToolExecutor>()
        mockMessageStore = mockk<MessageStore>(relaxed = true)

        // Default: no tools available
        coEvery { mockToolRegistry.getToolDefinitions() } returns emptyList()
        coEvery { mockToolRegistry.getToolDefinitions(any<Set<String>>()) } returns emptyList()
        coEvery { mockToolRegistry.registerPlugin(any()) } returns Unit
        coEvery { mockToolRegistry.getTool(any()) } returns null
        coEvery { mockLlmClient.cancel() } returns Unit

        coordinator = AgentCoordinator(
            clientProvider = { mockLlmClient },
            toolRegistry = mockToolRegistry,
            toolExecutor = mockToolExecutor,
            messageStore = mockMessageStore,
            conversationId = "test-conversation",
            scope = testScope
        )
    }

    @Test
    fun `execute returns success with valid response`() = testScope.runTest {
        // Given: Mock successful LLM response
        val expectedResponse = "Hello! How can I help you today?"
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = expectedResponse)
        )

        // When: Execute agent request
        val result = coordinator.execute("Hello", systemPrompt = testSystemPrompt)

        // Then: Result is successful with expected content
        assertTrue(result.isSuccess)
        assertEquals(expectedResponse, result.getOrNull())
    }

    @Test
    fun `execute adds messages to conversation history`() = testScope.runTest {
        // Given: Mock successful LLM response
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response 1")
        )

        // When: Execute first request
        coordinator.execute("Message 1", systemPrompt = testSystemPrompt)

        // Then: History contains user message and assistant response
        assertEquals(2, coordinator.getConversationSize()) // user + assistant

        // Given: Mock second response
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response 2")
        )

        // When: Execute second request
        coordinator.execute("Message 2", systemPrompt = testSystemPrompt)

        // Then: History contains all messages
        assertEquals(4, coordinator.getConversationSize()) // 2 users + 2 assistants
    }

    @Test
    fun `state transitions from Idle to Thinking to Completed`() = testScope.runTest {
        // Given: Mock successful LLM response with delay simulation
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Test response")
        )

        // Initial state should be Idle
        assertTrue(coordinator.state.value is AgentState.Idle)

        // When: Execute request
        coordinator.execute("Test", systemPrompt = testSystemPrompt)

        // Then: Final state should be Completed
        val finalState = coordinator.state.value
        assertTrue(finalState is AgentState.Completed)
        assertEquals("Test response", (finalState as AgentState.Completed).response)
    }

    @Test
    fun `execute handles LLM errors gracefully`() = testScope.runTest {
        // Given: Mock error response
        val errorMessage = "Network error"
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.failure(Exception(errorMessage))

        // When: Execute request
        val result = coordinator.execute("Hello", systemPrompt = testSystemPrompt)

        // Then: Result is failure
        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())

        // And: State is Error
        val state = coordinator.state.value
        assertTrue(state is AgentState.Error)
        assertEquals(errorMessage, (state as AgentState.Error).message)
    }

    @Test
    fun `execute handles empty response choices`() = testScope.runTest {
        // Given: Mock response with empty choices
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            LlmResponse(
                id = "test-id",
                choices = emptyList(),
                usage = null
            )
        )

        // When: Execute request
        val result = coordinator.execute("Hello", systemPrompt = testSystemPrompt)

        // Then: Result is failure
        assertTrue(result.isFailure)
        assertEquals("No choices in LLM response", result.exceptionOrNull()?.message)

        // And: State is Error
        assertTrue(coordinator.state.value is AgentState.Error)
    }

    @Test
    fun `execute handles null content in response`() = testScope.runTest {
        // Given: Mock response with null content
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            LlmResponse(
                id = "test-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = MessageResponse(
                            role = "assistant",
                            content = null,
                            tool_calls = null
                        ),
                        finish_reason = "stop"
                    )
                ),
                usage = null
            )
        )

        // When: Execute request
        val result = coordinator.execute("Hello", systemPrompt = testSystemPrompt)

        // Then: Result is failure
        assertTrue(result.isFailure)
        assertEquals("Empty final response from LLM", result.exceptionOrNull()?.message)
    }

    @Test
    fun `execute handles tool calls via ReAct loop`() = testScope.runTest {
        // Given: Mock response with tool calls, then a final answer
        val toolCalls = listOf(
            ToolCall(
                id = "call_123",
                type = "function",
                function = FunctionCall(
                    name = "get_weather",
                    arguments = """{"location": "San Francisco"}"""
                )
            )
        )

        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            LlmResponse(
                id = "test-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = MessageResponse(
                            role = "assistant",
                            content = null,
                            tool_calls = toolCalls
                        ),
                        finish_reason = "tool_calls"
                    )
                ),
                usage = null
            )
        ) andThen Result.success(
            createLlmResponse(content = "The weather in San Francisco is sunny.")
        )

        // Mock tool execution
        coEvery {
            mockToolExecutor.executeBatch(any(), any())
        } returns listOf(
            ToolExecutionResult.Success(
                toolCall = toolCalls[0],
                output = """{"temp": 72, "condition": "sunny"}"""
            )
        )

        // When: Execute request
        val result = coordinator.execute("What's the weather?", systemPrompt = testSystemPrompt)

        // Then: Result is the final answer after tool execution
        assertTrue(result.isSuccess)
        assertEquals("The weather in San Francisco is sunny.", result.getOrNull())
    }

    @Test
    fun `cancel stops execution and resets state`() = testScope.runTest {
        // Given: Coordinator in some state
        coordinator.execute("Test", systemPrompt = testSystemPrompt) // This completes immediately in test

        // When: Cancel is called
        coordinator.cancel()

        // Then: State is reset to Idle
        assertTrue(coordinator.state.value is AgentState.Idle)
    }

    @Test
    fun `reset clears conversation history`() = testScope.runTest {
        // Given: Mock successful responses
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response")
        )

        // When: Execute multiple requests
        coordinator.execute("Message 1", systemPrompt = testSystemPrompt)
        coordinator.execute("Message 2", systemPrompt = testSystemPrompt)

        // Then: History has messages
        assertTrue(coordinator.getConversationSize() > 0)

        // When: Reset is called
        coordinator.reset()

        // Then: History is cleared and state is Idle
        assertEquals(0, coordinator.getConversationSize())
        assertTrue(coordinator.state.value is AgentState.Idle)
    }

    @Test
    fun `conversation history maintains proper order`() = testScope.runTest {
        // Given: Mock successful responses
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response 1")
        ) andThen Result.success(
            createLlmResponse(content = "Response 2")
        )

        // When: Execute multiple requests
        coordinator.execute("User message 1", systemPrompt = testSystemPrompt)
        coordinator.execute("User message 2", systemPrompt = testSystemPrompt)

        // Then: History maintains order
        val history = coordinator.getConversationHistory()
        assertEquals("User message 1", history[0].content)
        assertEquals("Response 1", history[1].content)
        assertEquals("User message 2", history[2].content)
        assertEquals("Response 2", history[3].content)
    }

    @Test
    fun `execute includes system prompt on first request`() = testScope.runTest {
        // Given: Mock LLM client
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response")
        )

        // When: Execute first request with custom system prompt
        val customPrompt = "You are a test assistant"
        coordinator.execute("Hello", systemPrompt = customPrompt)

        // Then: LLM was called with system prompt
        coVerify {
            mockLlmClient.complete(
                messages = match { messages ->
                    messages.first().role == "system" &&
                    messages.first().content == customPrompt
                },
                any(), any(), any(), any()
            )
        }
    }

    @Test
    fun `execute does not add system prompt on subsequent requests`() = testScope.runTest {
        // Given: Mock LLM client
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Response")
        )

        // When: Execute first request
        coordinator.execute("First message", systemPrompt = testSystemPrompt)

        // Then: Execute second request
        coordinator.execute("Second message", systemPrompt = testSystemPrompt)

        // The second call should have 3 messages: system + user1 + assistant1 + user2
        // But not duplicate system prompts
        val history = coordinator.getConversationHistory()
        val systemMessages = history.filter { it.role == "system" }
        assertEquals(0, systemMessages.size) // System prompt not stored in history
    }

    @Test
    fun `executeAsync launches in background`() = testScope.runTest {
        // Given: Mock successful response
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            createLlmResponse(content = "Async response")
        )

        var callbackInvoked = false
        var callbackResult: Result<String>? = null

        // When: Execute async
        coordinator.executeAsync("Test", systemPrompt = testSystemPrompt) { result ->
            callbackInvoked = true
            callbackResult = result
        }

        // Advance time to let coroutine complete
        testScheduler.advanceUntilIdle()

        // Then: Callback was invoked with result
        assertTrue(callbackInvoked)
        assertTrue(callbackResult!!.isSuccess)
        assertEquals("Async response", callbackResult!!.getOrNull())
    }

    @Test
    fun `multiple tool calls are executed via ReAct loop`() = testScope.runTest {
        // Given: Mock response with multiple tool calls, then a final answer
        val toolCalls = listOf(
            ToolCall(
                id = "call_1",
                type = "function",
                function = FunctionCall(
                    name = "get_weather",
                    arguments = """{"location": "SF"}"""
                )
            ),
            ToolCall(
                id = "call_2",
                type = "function",
                function = FunctionCall(
                    name = "get_time",
                    arguments = """{"timezone": "PST"}"""
                )
            )
        )

        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } returns Result.success(
            LlmResponse(
                id = "test-id",
                choices = listOf(
                    Choice(
                        index = 0,
                        message = MessageResponse(
                            role = "assistant",
                            content = null,
                            tool_calls = toolCalls
                        ),
                        finish_reason = "tool_calls"
                    )
                ),
                usage = null
            )
        ) andThen Result.success(
            createLlmResponse(content = "It's sunny in SF and 3pm PST.")
        )

        // Mock tool execution
        coEvery {
            mockToolExecutor.executeBatch(any(), any())
        } returns listOf(
            ToolExecutionResult.Success(
                toolCall = toolCalls[0],
                output = """{"temp": 72, "condition": "sunny"}"""
            ),
            ToolExecutionResult.Success(
                toolCall = toolCalls[1],
                output = """{"time": "3:00 PM PST"}"""
            )
        )

        // When: Execute request
        val result = coordinator.execute("Check weather and time", systemPrompt = testSystemPrompt)

        // Then: Result is the final answer after both tools were executed
        assertTrue(result.isSuccess)
        assertEquals("It's sunny in SF and 3pm PST.", result.getOrNull())
    }

    @Test
    fun `state is Error after exception during execution`() = testScope.runTest {
        // Given: Mock client throws exception
        coEvery {
            mockLlmClient.complete(any(), any(), any(), any(), any())
        } throws RuntimeException("Unexpected error")

        // When: Execute request
        val result = coordinator.execute("Test", systemPrompt = testSystemPrompt)

        // Then: Result is failure and state is Error
        assertFalse(result.isSuccess)
        assertTrue(coordinator.state.value is AgentState.Error)
        val errorState = coordinator.state.value as AgentState.Error
        assertEquals("Unexpected error", errorState.message)
        assertNotNull(errorState.exception)
    }

    // Helper function to create mock LLM responses
    private fun createLlmResponse(
        content: String,
        toolCalls: List<ToolCall>? = null
    ): LlmResponse {
        return LlmResponse(
            id = "test-id-${System.currentTimeMillis()}",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(
                        role = "assistant",
                        content = content,
                        tool_calls = toolCalls
                    ),
                    finish_reason = if (toolCalls != null) "tool_calls" else "stop"
                )
            ),
            usage = null
        )
    }
}
