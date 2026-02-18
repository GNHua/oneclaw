package com.tomandy.oneclaw.pluginmanager

import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.PluginEngine
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.engine.ToolResult
import io.mockk.coEvery
import io.mockk.every
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
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class PluginManagementToolTest {

    private lateinit var mockManager: UserPluginManager
    private lateinit var mockEngine: PluginEngine
    private lateinit var tool: PluginManagementTool
    private val testDispatcher = UnconfinedTestDispatcher()

    private fun args(vararg pairs: Pair<String, String>): JsonObject {
        return JsonObject(pairs.associate { (k, v) -> k to JsonPrimitive(v) })
    }

    @Before
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        mockManager = mockk(relaxed = true)
        mockEngine = mockk(relaxed = true)
        tool = PluginManagementTool(mockManager, mockEngine)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    // --- install_plugin tests ---

    @Test
    fun `unknown tool name returns Failure`() = runTest {
        val result = tool.execute("nonexistent", args())
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `install - missing metadata argument returns Failure`() = runTest {
        val result = tool.execute("install_plugin", args("source" to "function execute() {}"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("metadata"))
    }

    @Test
    fun `install - missing source argument returns Failure`() = runTest {
        val result = tool.execute("install_plugin", args("metadata" to "{}"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("source"))
    }

    @Test
    fun `install - successful install returns Success with plugin info`() = runTest {
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

        val result = tool.execute(
            "install_plugin",
            args(
                "metadata" to "{}",
                "source" to "function execute() {}"
            )
        )

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("My Plugin"))
        assertTrue(output.contains("my_plugin"))
        assertTrue(output.contains("1 tool(s)"))
        assertTrue(output.contains("my_tool"))
    }

    @Test
    fun `install - failed install returns Failure with error message`() = runTest {
        coEvery { mockManager.installFromSource(any(), any()) } returns
            Result.failure(RuntimeException("Invalid metadata"))

        val result = tool.execute(
            "install_plugin",
            args(
                "metadata" to "{}",
                "source" to "function execute() {}"
            )
        )

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid metadata"))
    }

    // --- remove_plugin tests ---

    @Test
    fun `remove - missing plugin_id returns Failure`() = runTest {
        val result = tool.execute("remove_plugin", args())
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("plugin_id"))
    }

    @Test
    fun `remove - non-user plugin returns Failure`() = runTest {
        every { mockManager.isUserPlugin("built_in_plugin") } returns false

        val result = tool.execute("remove_plugin", args("plugin_id" to "built_in_plugin"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("not a user-installed plugin"))
    }

    @Test
    fun `remove - successful removal returns Success`() = runTest {
        every { mockManager.isUserPlugin("my_plugin") } returns true
        coEvery { mockManager.deletePlugin("my_plugin") } returns true

        val result = tool.execute("remove_plugin", args("plugin_id" to "my_plugin"))
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("removed successfully"))
    }

    @Test
    fun `remove - deletePlugin failure returns Failure`() = runTest {
        every { mockManager.isUserPlugin("my_plugin") } returns true
        coEvery { mockManager.deletePlugin("my_plugin") } returns false

        val result = tool.execute("remove_plugin", args("plugin_id" to "my_plugin"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Failed to remove"))
    }

    // --- list_user_plugins tests ---

    @Test
    fun `list - empty returns no plugins message`() = runTest {
        every { mockManager.getUserPluginIds() } returns emptySet()

        val result = tool.execute("list_user_plugins", args())
        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("No user-installed plugins"))
    }

    @Test
    fun `list - with loaded plugins returns rich metadata`() = runTest {
        every { mockManager.getUserPluginIds() } returns setOf("plugin_a", "plugin_b")
        every { mockEngine.getLoadedPlugin("plugin_a") } returns LoadedPlugin(
            metadata = PluginMetadata(
                id = "plugin_a",
                name = "Plugin A",
                version = "1.0.0",
                description = "First plugin",
                author = "AI",
                entryPoint = "PluginA",
                tools = listOf(
                    ToolDefinition("tool_a", "Does A", buildJsonObject { put("type", "object") })
                )
            ),
            instance = mockk()
        )
        every { mockEngine.getLoadedPlugin("plugin_b") } returns LoadedPlugin(
            metadata = PluginMetadata(
                id = "plugin_b",
                name = "Plugin B",
                version = "2.0.0",
                description = "Second plugin",
                author = "AI",
                entryPoint = "PluginB",
                tools = listOf(
                    ToolDefinition("tool_b1", "Does B1", buildJsonObject { put("type", "object") }),
                    ToolDefinition("tool_b2", "Does B2", buildJsonObject { put("type", "object") })
                )
            ),
            instance = mockk()
        )

        val result = tool.execute("list_user_plugins", args())
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Plugin A"))
        assertTrue(output.contains("plugin_a"))
        assertTrue(output.contains("v1.0.0"))
        assertTrue(output.contains("1 tool(s)"))
        assertTrue(output.contains("Plugin B"))
        assertTrue(output.contains("2 tool(s)"))
    }

    @Test
    fun `list - unloaded plugin shows not loaded`() = runTest {
        every { mockManager.getUserPluginIds() } returns setOf("broken_plugin")
        every { mockEngine.getLoadedPlugin("broken_plugin") } returns null

        val result = tool.execute("list_user_plugins", args())
        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("broken_plugin"))
        assertTrue(output.contains("not loaded"))
    }
}
