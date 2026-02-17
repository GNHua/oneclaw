package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.llm.Choice
import com.tomandy.palmclaw.llm.FunctionCall
import com.tomandy.palmclaw.llm.LlmClient
import com.tomandy.palmclaw.llm.LlmResponse
import com.tomandy.palmclaw.llm.MessageResponse
import com.tomandy.palmclaw.llm.ToolCall
import com.tomandy.palmclaw.llm.Usage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ReActLoopTest {

    private lateinit var mockLlmClient: LlmClient
    private lateinit var mockToolExecutor: ToolExecutor
    private lateinit var mockMessageStore: MessageStore
    private lateinit var loop: ReActLoop

    private val emptyToolsProvider: () -> List<ToolDefinition> = { emptyList() }
    private val testMessages = listOf(
        com.tomandy.palmclaw.llm.Message(role = "user", content = "Hello")
    )

    @Before
    fun setup() {
        mockLlmClient = mockk()
        mockToolExecutor = mockk()
        mockMessageStore = mockk(relaxed = true)
        loop = ReActLoop(mockLlmClient, mockToolExecutor, mockMessageStore)
    }

    private fun stopResponse(content: String?, usage: Usage? = null): LlmResponse =
        LlmResponse(
            id = "test-id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(role = "assistant", content = content, tool_calls = null),
                    finish_reason = "stop"
                )
            ),
            usage = usage
        )

    private fun toolCallResponse(
        toolCalls: List<ToolCall>,
        content: String? = null,
        usage: Usage? = null
    ): LlmResponse =
        LlmResponse(
            id = "test-id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(role = "assistant", content = content, tool_calls = toolCalls),
                    finish_reason = "tool_calls"
                )
            ),
            usage = usage
        )

    private fun makeToolCall(
        id: String = "call_1",
        name: String = "my_tool",
        args: String = """{"key":"value"}"""
    ): ToolCall = ToolCall(id = id, type = "function", function = FunctionCall(name = name, arguments = args))

    // --- Final answer path ---

    @Test
    fun `step returns success when LLM responds with stop`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(stopResponse("Hello there!"))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        assertEquals("Hello there!", result.getOrNull())
    }

    @Test
    fun `step returns failure when LLM response has no choices`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(LlmResponse(id = "id", choices = emptyList(), usage = null))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertEquals("No choices in LLM response", result.exceptionOrNull()?.message)
    }

    @Test
    fun `step returns failure when content is null on stop`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(stopResponse(null))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertEquals("Empty final response from LLM", result.exceptionOrNull()?.message)
    }

    @Test
    fun `step returns failure when content is blank on stop`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(stopResponse("   "))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertEquals("Empty final response from LLM", result.exceptionOrNull()?.message)
    }

    @Test
    fun `step returns failure when LLM client returns failure Result`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.failure(RuntimeException("API error"))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertEquals("API error", result.exceptionOrNull()?.message)
    }

    // --- Tool execution path ---

    @Test
    fun `step executes tools when finish_reason is tool_calls`() = runTest {
        val tc = makeToolCall()
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc))) andThen
            Result.success(stopResponse("Done"))

        coEvery { mockToolExecutor.executeBatch(any(), any()) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc, output = "tool output")
        )

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        assertEquals("Done", result.getOrNull())
        coVerify(exactly = 1) { mockToolExecutor.executeBatch("conv1", listOf(tc)) }
    }

    @Test
    fun `step handles failed tool execution and sends error to LLM`() = runTest {
        val tc = makeToolCall()
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc))) andThen
            Result.success(stopResponse("I see the error"))

        coEvery { mockToolExecutor.executeBatch(any(), any()) } returns listOf(
            ToolExecutionResult.Failure(toolCall = tc, error = "tool failed")
        )

        val messagesSlot = mutableListOf<List<com.tomandy.palmclaw.llm.Message>>()
        coEvery { mockLlmClient.complete(capture(messagesSlot), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc))) andThen
            Result.success(stopResponse("I see the error"))

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        // The second LLM call should include a tool message with the error
        val lastMessages = messagesSlot.last()
        val toolMessage = lastMessages.find { it.role == "tool" }
        assertNotNull(toolMessage)
        assertTrue(toolMessage!!.content!!.contains("Error: tool failed"))
    }

    @Test
    fun `step persists assistant message with tool calls to MessageStore`() = runTest {
        val tc = makeToolCall()
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc))) andThen
            Result.success(stopResponse("Done"))

        coEvery { mockToolExecutor.executeBatch(any(), any()) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc, output = "ok")
        )

        loop.step(testMessages, emptyToolsProvider, "conv1")

        coVerify {
            mockMessageStore.insert(match {
                it.role == "assistant" && it.toolCalls != null && it.toolCalls!!.contains("call_1")
            })
        }
    }

    @Test
    fun `step returns failure when tool_calls finish_reason but no tool_calls present`() = runTest {
        val response = LlmResponse(
            id = "id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(role = "assistant", content = null, tool_calls = null),
                    finish_reason = "tool_calls"
                )
            ),
            usage = null
        )
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns Result.success(response)

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("no tool_calls") == true)
    }

    // --- Multi-iteration ---

    @Test
    fun `step supports multiple tool call iterations before final answer`() = runTest {
        val tc1 = makeToolCall(id = "call_1", name = "tool_a")
        val tc2 = makeToolCall(id = "call_2", name = "tool_b")

        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc1))) andThen
            Result.success(toolCallResponse(listOf(tc2))) andThen
            Result.success(stopResponse("Final answer"))

        coEvery { mockToolExecutor.executeBatch(any(), match { it[0].id == "call_1" }) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc1, output = "result_a")
        )
        coEvery { mockToolExecutor.executeBatch(any(), match { it[0].id == "call_2" }) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc2, output = "result_b")
        )

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        assertEquals("Final answer", result.getOrNull())
        coVerify(exactly = 2) { mockToolExecutor.executeBatch(any(), any()) }
    }

    @Test
    fun `step enforces maxIterations limit`() = runTest {
        val tc = makeToolCall()
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc)))

        coEvery { mockToolExecutor.executeBatch(any(), any()) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc, output = "ok")
        )

        val result = loop.step(testMessages, emptyToolsProvider, "conv1", maxIterations = 3)

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Max iterations (3)") == true)
    }

    @Test
    fun `step refreshes tools each iteration via toolsProvider`() = runTest {
        var callCount = 0
        val toolsProvider = {
            callCount++
            emptyList<ToolDefinition>()
        }

        val tc = makeToolCall()
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(toolCallResponse(listOf(tc))) andThen
            Result.success(stopResponse("Done"))

        coEvery { mockToolExecutor.executeBatch(any(), any()) } returns listOf(
            ToolExecutionResult.Success(toolCall = tc, output = "ok")
        )

        loop.step(testMessages, toolsProvider, "conv1")

        assertEquals(2, callCount)
    }

    // --- Unknown finish reason ---

    @Test
    fun `step returns content for unknown finish_reason if content present`() = runTest {
        val response = LlmResponse(
            id = "id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(role = "assistant", content = "some content", tool_calls = null),
                    finish_reason = "length"
                )
            ),
            usage = null
        )
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns Result.success(response)

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        assertEquals("some content", result.getOrNull())
    }

    @Test
    fun `step returns failure for unknown finish_reason with no content`() = runTest {
        val response = LlmResponse(
            id = "id",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(role = "assistant", content = null, tool_calls = null),
                    finish_reason = "length"
                )
            ),
            usage = null
        )
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns Result.success(response)

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull()?.message?.contains("Unknown finish_reason") == true)
    }

    // --- Message injection ---

    @Test
    fun `injectMessage adds message that gets drained into working messages`() = runTest {
        loop.injectMessage("injected message")

        val messagesSlot = slot<List<com.tomandy.palmclaw.llm.Message>>()
        coEvery { mockLlmClient.complete(capture(messagesSlot), any(), any(), any(), any()) } returns
            Result.success(stopResponse("response"))

        loop.step(testMessages, emptyToolsProvider, "conv1")

        val sentMessages = messagesSlot.captured
        assertTrue(sentMessages.any { it.role == "user" && it.content == "injected message" })
    }

    @Test
    fun `step continues loop when stop received but pending user messages exist`() = runTest {
        // Simulate a user message arriving while the LLM is processing (mid-call injection).
        // The first LLM call returns "stop", but during that call a message is injected,
        // so pendingUserMessages is non-empty when the stop check runs.
        var callCount = 0
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } coAnswers {
            callCount++
            if (callCount == 1) {
                // Inject during the first LLM call so it's pending when "stop" is checked
                loop.injectMessage("follow-up")
                Result.success(stopResponse("intermediate"))
            } else {
                Result.success(stopResponse("final answer"))
            }
        }

        val result = loop.step(testMessages, emptyToolsProvider, "conv1")

        assertTrue(result.isSuccess)
        assertEquals("final answer", result.getOrNull())
        assertEquals(2, callCount)
    }

    @Test
    fun `step persists intermediate answer to MessageStore on continue`() = runTest {
        var callCount = 0
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } coAnswers {
            callCount++
            if (callCount == 1) {
                loop.injectMessage("follow-up")
                Result.success(stopResponse("intermediate"))
            } else {
                Result.success(stopResponse("final"))
            }
        }

        loop.step(testMessages, emptyToolsProvider, "conv1")

        coVerify {
            mockMessageStore.insert(match {
                it.role == "assistant" && it.content == "intermediate"
            })
        }
    }

    // --- Usage tracking ---

    @Test
    fun `step updates lastUsage from LLM response`() = runTest {
        val usage = Usage(prompt_tokens = 10, completion_tokens = 20, total_tokens = 30)
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), any()) } returns
            Result.success(stopResponse("ok", usage = usage))

        assertNull(loop.lastUsage)

        loop.step(testMessages, emptyToolsProvider, "conv1")

        assertNotNull(loop.lastUsage)
        assertEquals(30, loop.lastUsage!!.total_tokens)
    }

    // --- Tools passed to LLM ---

    @Test
    fun `step passes null tools when toolsProvider returns empty list`() = runTest {
        coEvery { mockLlmClient.complete(any(), any(), any(), any(), tools = null) } returns
            Result.success(stopResponse("ok"))

        loop.step(testMessages, emptyToolsProvider, "conv1")

        coVerify { mockLlmClient.complete(any(), any(), any(), any(), tools = null) }
    }

    @Test
    fun `step passes converted tools when toolsProvider returns definitions`() = runTest {
        val toolDefs = listOf(
            ToolDefinition(name = "my_tool", description = "does stuff", parameters = buildJsonObject {})
        )

        coEvery { mockLlmClient.complete(any(), any(), any(), any(), tools = any()) } returns
            Result.success(stopResponse("ok"))

        loop.step(testMessages, { toolDefs }, "conv1")

        coVerify {
            mockLlmClient.complete(
                any(), any(), any(), any(),
                tools = match { it != null && it.size == 1 && it[0].function.name == "my_tool" }
            )
        }
    }
}
