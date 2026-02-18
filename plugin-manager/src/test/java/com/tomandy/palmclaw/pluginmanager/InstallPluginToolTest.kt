package com.tomandy.palmclaw.pluginmanager

import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.engine.ToolResult
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class InstallPluginToolTest {

    private lateinit var mockManager: UserPluginManager
    private lateinit var tool: InstallPluginTool
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun args(vararg pairs: Pair<String, String>): JsonObject {
        return JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockManager = mockk(relaxed = true)
        tool = InstallPluginTool(mockManager)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `unknown tool name returns Failure`() = runTest {
        val result = tool.execute("nonexistent", args())
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `missing metadata argument returns Failure`() = runTest {
        val result = tool.execute("install_plugin", args("source" to "function execute() {}"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("metadata"))
    }

    @Test
    fun `missing source argument returns Failure`() = runTest {
        val result = tool.execute("install_plugin", args("metadata" to "{}"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("source"))
    }

    @Test
    fun `successful install returns Success with plugin info`() = runTest {
        val loadedPlugin = LoadedPlugin(
            metadata = PluginMetadata(
                id = "my_plugin",
                name = "My Plugin",
                version = "1.0.0",
                description = "Test",
                author = "AI",
                entryPoint = "MyPlugin",
                tools = listOf(
                    ToolDefinition("my_tool", "Does X", buildJsonObject { put("type", "object") })
                )
            ),
            instance = mockk()
        )
        coEvery { mockManager.installFromSource(any(), any()) } returns Result.success(loadedPlugin)

        val result = tool.execute("install_plugin", args(
            "metadata" to "{}",
            "source" to "function execute() {}"
        ))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("My Plugin"))
        assertTrue(output.contains("my_plugin"))
        assertTrue(output.contains("1 tool(s)"))
        assertTrue(output.contains("my_tool"))
    }

    @Test
    fun `failed install returns Failure with error message`() = runTest {
        coEvery { mockManager.installFromSource(any(), any()) } returns
            Result.failure(RuntimeException("Invalid metadata"))

        val result = tool.execute("install_plugin", args(
            "metadata" to "{}",
            "source" to "function execute() {}"
        ))

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid metadata"))
    }
}
