package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.engine.LoadedPlugin
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ActivateToolsPluginTest {

    private lateinit var registry: ToolRegistry
    private lateinit var plugin: ActivateToolsPlugin

    private val stubPlugin = object : Plugin {
        override suspend fun onLoad(context: PluginContext) {}
        override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult =
            ToolResult.Success("stub")
        override suspend fun onUnload() {}
    }

    private fun toolDef(name: String, description: String = "desc"): ToolDefinition =
        ToolDefinition(name = name, description = description, parameters = buildJsonObject {})

    private fun loadedPlugin(
        id: String,
        tools: List<ToolDefinition>,
        category: String = "core",
        description: String = "Plugin $id"
    ): LoadedPlugin = LoadedPlugin(
        metadata = PluginMetadata(
            id = id, name = id, version = "1.0.0", description = description,
            author = "test", entryPoint = "Test", tools = tools, category = category
        ),
        instance = stubPlugin
    )

    private fun categoriesArgs(vararg categories: String): JsonObject = buildJsonObject {
        putJsonArray("categories") {
            categories.forEach { add(JsonPrimitive(it)) }
        }
    }

    @Before
    fun setup() {
        registry = ToolRegistry()
        registry.registerPlugin(loadedPlugin("core_plugin", listOf(toolDef("core_tool")), category = "core"))
        registry.registerPlugin(loadedPlugin("gmail_plugin", listOf(toolDef("search_gmail", "Search emails")), category = "gmail", description = "Gmail tools"))
        registry.registerPlugin(loadedPlugin("cal_plugin", listOf(toolDef("list_events", "List calendar events")), category = "calendar", description = "Calendar tools"))

        plugin = ActivateToolsPlugin(registry)
    }

    @Test
    fun `execute with wrong tool name returns Failure`() = runTest {
        plugin.activeCategories = mutableSetOf()
        val result = plugin.execute("wrong_tool", buildJsonObject {})

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Unknown tool"))
    }

    @Test
    fun `execute when activeCategories is null returns Failure`() = runTest {
        plugin.activeCategories = null
        val result = plugin.execute("activate_tools", categoriesArgs("gmail"))

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("not available"))
    }

    @Test
    fun `execute with missing categories field returns Failure`() = runTest {
        plugin.activeCategories = mutableSetOf()
        val result = plugin.execute("activate_tools", buildJsonObject {})

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Missing required field"))
    }

    @Test
    fun `execute with valid categories adds them to activeCategories`() = runTest {
        val activeSet = mutableSetOf<String>()
        plugin.activeCategories = activeSet

        val result = plugin.execute("activate_tools", categoriesArgs("gmail"))

        assertTrue(result is ToolResult.Success)
        assertTrue(activeSet.contains("gmail"))
    }

    @Test
    fun `execute with valid categories returns Success with activation summary`() = runTest {
        plugin.activeCategories = mutableSetOf()

        val result = plugin.execute("activate_tools", categoriesArgs("gmail"))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("Activated 1 category"))
        assertTrue(output.contains("gmail"))
        assertTrue(output.contains("search_gmail"))
    }

    @Test
    fun `execute with mix of valid and invalid activates valid and warns about invalid`() = runTest {
        plugin.activeCategories = mutableSetOf()

        val result = plugin.execute("activate_tools", categoriesArgs("gmail", "nonexistent"))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("gmail"))
        assertTrue(output.contains("Unknown categories"))
        assertTrue(output.contains("nonexistent"))
    }

    @Test
    fun `execute with all invalid categories returns Failure`() = runTest {
        plugin.activeCategories = mutableSetOf()

        val result = plugin.execute("activate_tools", categoriesArgs("nonexistent", "fake"))

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("No valid categories"))
    }

    @Test
    fun `execute lists newly activated tools in output`() = runTest {
        plugin.activeCategories = mutableSetOf()

        val result = plugin.execute("activate_tools", categoriesArgs("gmail", "calendar"))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("search_gmail"))
        assertTrue(output.contains("list_events"))
        assertTrue(output.contains("Activated 2 category"))
    }

    @Test
    fun `execute does not duplicate already-active categories`() = runTest {
        val activeSet = mutableSetOf("gmail")
        plugin.activeCategories = activeSet

        val result = plugin.execute("activate_tools", categoriesArgs("gmail"))

        assertTrue(result is ToolResult.Success)
        assertEquals(1, activeSet.count { it == "gmail" })
    }

    @Test
    fun `metadata generates tool definition listing available categories`() {
        val metadata = ActivateToolsPlugin.metadata(registry)

        assertEquals("activate_tools", metadata.id)
        assertEquals(1, metadata.tools.size)
        assertEquals("activate_tools", metadata.tools[0].name)

        val description = metadata.tools[0].description
        assertTrue(description.contains("gmail"))
        assertTrue(description.contains("calendar"))
        assertFalse(description.contains("(none currently registered)"))
    }

    @Test
    fun `metadata shows none registered when no on-demand categories exist`() {
        val emptyRegistry = ToolRegistry()
        emptyRegistry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))

        val metadata = ActivateToolsPlugin.metadata(emptyRegistry)
        assertTrue(metadata.tools[0].description.contains("(none currently registered)"))
    }
}
