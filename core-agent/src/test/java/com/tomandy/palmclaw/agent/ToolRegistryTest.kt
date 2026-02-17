package com.tomandy.palmclaw.agent

import com.tomandy.palmclaw.engine.LoadedPlugin
import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

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
            id = id,
            name = id,
            version = "1.0.0",
            description = description,
            author = "test",
            entryPoint = "Test",
            tools = tools,
            category = category
        ),
        instance = stubPlugin
    )

    @Before
    fun setup() {
        registry = ToolRegistry()
    }

    @Test
    fun `registerPlugin registers all tools from plugin metadata`() {
        val plugin = loadedPlugin("p1", listOf(toolDef("tool_a"), toolDef("tool_b")))
        registry.registerPlugin(plugin)

        assertEquals(2, registry.size())
        assertTrue(registry.hasTool("tool_a"))
        assertTrue(registry.hasTool("tool_b"))
    }

    @Test
    fun `registerPlugin overwrites tool with same name`() {
        val plugin1 = loadedPlugin("p1", listOf(toolDef("tool_a", "old")))
        val plugin2 = loadedPlugin("p2", listOf(toolDef("tool_a", "new")))

        registry.registerPlugin(plugin1)
        registry.registerPlugin(plugin2)

        assertEquals(1, registry.size())
        assertEquals("new", registry.getTool("tool_a")!!.definition.description)
        assertEquals("p2", registry.getTool("tool_a")!!.pluginId)
    }

    @Test
    fun `unregisterPlugin removes all tools from a plugin`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a"), toolDef("b"))))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("c"))))

        registry.unregisterPlugin("p1")

        assertEquals(1, registry.size())
        assertFalse(registry.hasTool("a"))
        assertFalse(registry.hasTool("b"))
        assertTrue(registry.hasTool("c"))
    }

    @Test
    fun `unregisterPlugin with unknown pluginId is a no-op`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a"))))
        registry.unregisterPlugin("unknown")
        assertEquals(1, registry.size())
    }

    @Test
    fun `getTool returns registered tool by name`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("my_tool"))))
        val tool = registry.getTool("my_tool")

        assertNotNull(tool)
        assertEquals("my_tool", tool!!.definition.name)
        assertEquals("p1", tool.pluginId)
    }

    @Test
    fun `getTool returns null for unknown tool name`() {
        assertNull(registry.getTool("nonexistent"))
    }

    @Test
    fun `getToolDefinitions returns all definitions`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a"), toolDef("b"))))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("c")), category = "gmail"))

        val defs = registry.getToolDefinitions()
        assertEquals(3, defs.size)
        assertEquals(setOf("a", "b", "c"), defs.map { it.name }.toSet())
    }

    @Test
    fun `getToolDefinitions with activeCategories filters to core plus active`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("core_tool")), category = "core"))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("gmail_tool")), category = "gmail"))
        registry.registerPlugin(loadedPlugin("p3", listOf(toolDef("cal_tool")), category = "calendar"))

        val defs = registry.getToolDefinitions(setOf("gmail"))
        assertEquals(2, defs.size)
        assertEquals(setOf("core_tool", "gmail_tool"), defs.map { it.name }.toSet())
    }

    @Test
    fun `getToolDefinitions with empty active set returns only core tools`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("core_tool")), category = "core"))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("gmail_tool")), category = "gmail"))

        val defs = registry.getToolDefinitions(emptySet())
        assertEquals(1, defs.size)
        assertEquals("core_tool", defs[0].name)
    }

    @Test
    fun `getOnDemandCategories returns non-core categories`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("b")), category = "gmail"))
        registry.registerPlugin(loadedPlugin("p3", listOf(toolDef("c")), category = "calendar"))

        val categories = registry.getOnDemandCategories()
        assertEquals(setOf("gmail", "calendar"), categories)
    }

    @Test
    fun `getOnDemandCategories excludes core`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))
        val categories = registry.getOnDemandCategories()
        assertTrue(categories.isEmpty())
    }

    @Test
    fun `getCategoryDescription returns aggregated descriptions`() {
        registry.registerPlugin(
            loadedPlugin("p1", listOf(toolDef("a")), category = "gmail", description = "Search emails")
        )
        registry.registerPlugin(
            loadedPlugin("p2", listOf(toolDef("b")), category = "gmail", description = "Send emails")
        )

        val desc = registry.getCategoryDescription("gmail")
        assertEquals("Search emails; Send emails", desc)
    }

    @Test
    fun `getCategoryDescription returns category name as fallback for unknown category`() {
        assertEquals("unknown_cat", registry.getCategoryDescription("unknown_cat"))
    }

    @Test
    fun `getCategoryDescription does not track core category`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))
        assertEquals("core", registry.getCategoryDescription("core"))
    }

    @Test
    fun `getAllTools returns all registered tools`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a"), toolDef("b"))))
        val all = registry.getAllTools()
        assertEquals(2, all.size)
    }

    @Test
    fun `clear removes all tools and category descriptions`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "gmail", description = "Test"))
        registry.clear()

        assertEquals(0, registry.size())
        assertFalse(registry.hasTool("a"))
        assertEquals("gmail", registry.getCategoryDescription("gmail"))
    }

    @Test
    fun `size returns correct count after register and unregister`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a"), toolDef("b"))))
        assertEquals(2, registry.size())

        registry.unregisterPlugin("p1")
        assertEquals(0, registry.size())
    }

    @Test
    fun `hasTool returns true for registered tool`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("my_tool"))))
        assertTrue(registry.hasTool("my_tool"))
    }

    @Test
    fun `hasTool returns false for unregistered tool`() {
        assertFalse(registry.hasTool("nonexistent"))
    }

    @Test
    fun `copyFiltered creates independent registry with matching tools`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))
        registry.registerPlugin(loadedPlugin("p2", listOf(toolDef("b")), category = "gmail"))

        val filtered = registry.copyFiltered { it.category == "core" }

        assertEquals(1, filtered.size())
        assertTrue(filtered.hasTool("a"))
        assertFalse(filtered.hasTool("b"))
    }

    @Test
    fun `copyFiltered preserves category descriptions`() {
        registry.registerPlugin(
            loadedPlugin("p1", listOf(toolDef("a")), category = "gmail", description = "Gmail tools")
        )

        val filtered = registry.copyFiltered { true }
        assertEquals("Gmail tools", filtered.getCategoryDescription("gmail"))
    }

    @Test
    fun `copyFiltered creates independent copy that does not affect original`() {
        registry.registerPlugin(loadedPlugin("p1", listOf(toolDef("a")), category = "core"))
        val filtered = registry.copyFiltered { true }

        filtered.clear()

        assertEquals(1, registry.size())
        assertTrue(registry.hasTool("a"))
    }
}
