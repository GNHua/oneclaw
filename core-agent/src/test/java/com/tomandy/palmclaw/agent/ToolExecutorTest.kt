package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.engine.ToolResult
import com.tomandy.palmclaw.llm.FunctionCall
import com.tomandy.palmclaw.llm.ToolCall
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.buildJsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ToolExecutorTest {

    private lateinit var mockRegistry: ToolRegistry
    private lateinit var mockMessageStore: MessageStore
    private lateinit var mockPlugin: Plugin
    private lateinit var executor: ToolExecutor

    private val testDispatcher = UnconfinedTestDispatcher()

    private fun toolCall(name: String, args: String = """{"key":"value"}"""): ToolCall =
        ToolCall(id = "call_1", type = "function", function = FunctionCall(name = name, arguments = args))

    private fun registeredTool(
        pluginId: String = "test_plugin",
        name: String = "my_tool",
        timeoutMs: Long = 0
    ): RegisteredTool = RegisteredTool(
        pluginId = pluginId,
        definition = ToolDefinition(
            name = name,
            description = "test tool",
            parameters = buildJsonObject {},
            timeoutMs = timeoutMs
        ),
        plugin = mockPlugin,
        category = "core"
    )

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockRegistry = mockk()
        mockMessageStore = mockk(relaxed = true)
        mockPlugin = mockk()
        executor = ToolExecutor(mockRegistry, mockMessageStore)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `execute returns Success when tool executes successfully`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } returns ToolResult.Success("result output")

        val result = executor.execute("conv1", toolCall("my_tool"))

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals("result output", (result as ToolExecutionResult.Success).output)
    }

    @Test
    fun `execute returns Failure for unknown tool`() = runTest {
        coEvery { mockRegistry.getTool("unknown") } returns null

        val result = executor.execute("conv1", toolCall("unknown"))

        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).error.contains("not found"))
    }

    @Test
    fun `execute returns Failure for invalid JSON arguments`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()

        val result = executor.execute("conv1", toolCall("my_tool", args = "not json"))

        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).error.contains("Invalid JSON"))
    }

    @Test
    fun `execute enriches arguments with conversation_id`() = runTest {
        val argsSlot = slot<kotlinx.serialization.json.JsonObject>()
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), capture(argsSlot)) } returns ToolResult.Success("ok")

        executor.execute("conv_123", toolCall("my_tool"))

        val enriched = argsSlot.captured
        assertEquals("conv_123", enriched["_conversation_id"]?.toString()?.trim('"'))
    }

    @Test
    fun `execute persists tool result to MessageStore`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } returns ToolResult.Success("output")

        executor.execute("conv1", toolCall("my_tool"))

        coVerify(exactly = 1) { mockMessageStore.insert(match { it.role == "tool" && it.content == "output" }) }
    }

    @Test
    fun `execute truncates stored result at 16384 chars`() = runTest {
        val longOutput = "x".repeat(20_000)
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } returns ToolResult.Success(longOutput)

        val recordSlot = slot<MessageRecord>()
        coEvery { mockMessageStore.insert(capture(recordSlot)) } returns Unit

        val result = executor.execute("conv1", toolCall("my_tool"))

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(longOutput, (result as ToolExecutionResult.Success).output)

        val stored = recordSlot.captured.content
        assertTrue(stored.length < longOutput.length)
        assertTrue(stored.contains("[Truncated: 20000 chars total]"))
    }

    @Test
    fun `execute returns Failure when plugin throws exception`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } throws RuntimeException("plugin crashed")

        val result = executor.execute("conv1", toolCall("my_tool"))

        assertTrue(result is ToolExecutionResult.Failure)
        assertTrue((result as ToolExecutionResult.Failure).error.contains("plugin crashed"))
    }

    @Test
    fun `execute maps ToolResult Failure to ToolExecutionResult Failure and persists error`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } returns ToolResult.Failure("tool error")

        val result = executor.execute("conv1", toolCall("my_tool"))

        assertTrue(result is ToolExecutionResult.Failure)
        assertEquals("tool error", (result as ToolExecutionResult.Failure).error)

        coVerify { mockMessageStore.insert(match { it.content.contains("Error: tool error") }) }
    }

    @Test
    fun `execute preserves metadata from ToolResult Success`() = runTest {
        coEvery { mockRegistry.getTool("my_tool") } returns registeredTool()
        coEvery { mockPlugin.execute(any(), any()) } returns ToolResult.Success(
            output = "ok",
            metadata = mapOf("key" to "value")
        )

        val result = executor.execute("conv1", toolCall("my_tool"))

        assertTrue(result is ToolExecutionResult.Success)
        assertEquals(mapOf("key" to "value"), (result as ToolExecutionResult.Success).metadata)
    }

    @Test
    fun `executeBatch returns results for all tool calls in order`() = runTest {
        coEvery { mockRegistry.getTool("tool_a") } returns registeredTool(name = "tool_a")
        coEvery { mockRegistry.getTool("tool_b") } returns registeredTool(name = "tool_b")
        coEvery { mockPlugin.execute(eq("tool_a"), any()) } returns ToolResult.Success("result_a")
        coEvery { mockPlugin.execute(eq("tool_b"), any()) } returns ToolResult.Success("result_b")

        val results = executor.executeBatch(
            "conv1",
            listOf(
                toolCall("tool_a"),
                ToolCall(id = "call_2", type = "function", function = FunctionCall("tool_b", """{"x":1}"""))
            )
        )

        assertEquals(2, results.size)
        assertEquals("result_a", (results[0] as ToolExecutionResult.Success).output)
        assertEquals("result_b", (results[1] as ToolExecutionResult.Success).output)
    }

    @Test
    fun `executeBatch with empty list returns empty list`() = runTest {
        val results = executor.executeBatch("conv1", emptyList())
        assertTrue(results.isEmpty())
    }

    @Test
    fun `executeBatch handles mix of successful and failed tools`() = runTest {
        coEvery { mockRegistry.getTool("good") } returns registeredTool(name = "good")
        coEvery { mockRegistry.getTool("bad") } returns null
        coEvery { mockPlugin.execute(eq("good"), any()) } returns ToolResult.Success("ok")

        val results = executor.executeBatch(
            "conv1",
            listOf(
                toolCall("good"),
                ToolCall(id = "call_2", type = "function", function = FunctionCall("bad", "{}"))
            )
        )

        assertEquals(2, results.size)
        assertTrue(results[0] is ToolExecutionResult.Success)
        assertTrue(results[1] is ToolExecutionResult.Failure)
    }
}
