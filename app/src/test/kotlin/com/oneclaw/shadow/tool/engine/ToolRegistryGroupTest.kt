package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolGroupDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistryGroupTest {

    private lateinit var registry: ToolRegistry

    private fun makeTool(name: String): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Test tool: $name",
            parametersSchema = ToolParametersSchema(emptyMap(), emptyList()),
            requiredPermissions = emptyList(),
            timeoutSeconds = 5
        )
        override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
    }

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()
    }

    @Test
    fun `getCoreToolDefinitions excludes grouped tools`() {
        registry.register(makeTool("core_tool"), ToolSourceInfo.BUILTIN)
        registry.register(
            makeTool("grouped_tool"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
        )

        val core = registry.getCoreToolDefinitions()
        assertEquals(1, core.size)
        assertEquals("core_tool", core[0].name)
    }

    @Test
    fun `getCoreToolDefinitions returns all when no groups registered`() {
        registry.register(makeTool("tool_a"), ToolSourceInfo.BUILTIN)
        registry.register(makeTool("tool_b"), ToolSourceInfo.BUILTIN)

        val core = registry.getCoreToolDefinitions()
        assertEquals(2, core.size)
    }

    @Test
    fun `getGroupToolDefinitions returns correct tools`() {
        registry.register(
            makeTool("config_tool1"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
        )
        registry.register(
            makeTool("config_tool2"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
        )
        registry.register(
            makeTool("pdf_tool"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "pdf")
        )

        val configTools = registry.getGroupToolDefinitions("config")
        assertEquals(2, configTools.size)
        assertTrue(configTools.any { it.name == "config_tool1" })
        assertTrue(configTools.any { it.name == "config_tool2" })

        val pdfTools = registry.getGroupToolDefinitions("pdf")
        assertEquals(1, pdfTools.size)
        assertEquals("pdf_tool", pdfTools[0].name)
    }

    @Test
    fun `getGroupToolDefinitions returns empty for unknown group`() {
        val tools = registry.getGroupToolDefinitions("nonexistent")
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `registerGroup and getGroupDefinition`() {
        val group = ToolGroupDefinition("config", "Configuration", "Config tools")
        registry.registerGroup(group)

        val found = registry.getGroupDefinition("config")
        assertNotNull(found)
        assertEquals("config", found!!.name)
        assertEquals("Configuration", found.displayName)
    }

    @Test
    fun `getGroupDefinition returns null for unknown group`() {
        assertNull(registry.getGroupDefinition("nonexistent"))
    }

    @Test
    fun `getAllGroupDefinitions returns all registered groups`() {
        registry.registerGroup(ToolGroupDefinition("config", "Configuration", "Config tools"))
        registry.registerGroup(ToolGroupDefinition("pdf", "PDF Tools", "PDF tools"))

        val all = registry.getAllGroupDefinitions()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "config" })
        assertTrue(all.any { it.name == "pdf" })
    }

    @Test
    fun `getToolGroups includes BUILTIN tools with groupName`() {
        registry.register(
            makeTool("config_tool"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
        )
        registry.register(
            makeTool("js_tool"),
            ToolSourceInfo(type = ToolSourceType.TOOL_GROUP, groupName = "google_gmail")
        )

        val groups = registry.getToolGroups()
        assertEquals(2, groups.size)
        assertTrue(groups.containsKey("config"))
        assertTrue(groups.containsKey("google_gmail"))
    }

    @Test
    fun `getToolGroups excludes tools without groupName`() {
        registry.register(makeTool("core_tool"), ToolSourceInfo.BUILTIN)
        registry.register(
            makeTool("grouped_tool"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config")
        )

        val groups = registry.getToolGroups()
        assertEquals(1, groups.size)
        assertTrue(groups.containsKey("config"))
    }
}
