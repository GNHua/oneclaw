package com.oneclaw.shadow.tool.engine

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ToolRegistryTest {

    private lateinit var registry: ToolRegistry

    private fun makeTool(name: String): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Test tool: $name",
            parametersSchema = ToolParametersSchema(
                properties = mapOf("input" to ToolParameter(type = "string", description = "test")),
                required = listOf("input")
            ),
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
    fun `register and getTool returns the tool`() {
        val tool = makeTool("tool_a")
        registry.register(tool)

        assertNotNull(registry.getTool("tool_a"))
    }

    @Test
    fun `getTool returns null for unknown name`() {
        assertNull(registry.getTool("nonexistent"))
    }

    @Test
    fun `hasTool returns false when not registered`() {
        assertFalse(registry.hasTool("tool_a"))
    }

    @Test
    fun `hasTool returns true after registration`() {
        registry.register(makeTool("tool_a"))
        assertTrue(registry.hasTool("tool_a"))
    }

    @Test
    fun `getAllToolDefinitions returns all registered definitions`() {
        registry.register(makeTool("tool_a"))
        registry.register(makeTool("tool_b"))
        registry.register(makeTool("tool_c"))

        val all = registry.getAllToolDefinitions()
        assertEquals(3, all.size)
        assertTrue(all.map { it.name }.containsAll(listOf("tool_a", "tool_b", "tool_c")))
    }

    @Test
    fun `getToolDefinitionsByNames returns matching definitions`() {
        registry.register(makeTool("tool_a"))
        registry.register(makeTool("tool_b"))
        registry.register(makeTool("tool_c"))

        val selected = registry.getToolDefinitionsByNames(listOf("tool_a", "tool_c"))
        assertEquals(2, selected.size)
        assertTrue(selected.map { it.name }.containsAll(listOf("tool_a", "tool_c")))
    }

    @Test
    fun `getToolDefinitionsByNames silently ignores unknown names`() {
        registry.register(makeTool("tool_a"))

        val selected = registry.getToolDefinitionsByNames(listOf("tool_a", "unknown"))
        assertEquals(1, selected.size)
        assertEquals("tool_a", selected[0].name)
    }

    @Test
    fun `getAllToolNames returns all registered names`() {
        registry.register(makeTool("tool_a"))
        registry.register(makeTool("tool_b"))

        val names = registry.getAllToolNames()
        assertTrue(names.containsAll(listOf("tool_a", "tool_b")))
    }

    @Test
    fun `register duplicate name throws IllegalArgumentException`() {
        registry.register(makeTool("tool_a"))
        assertThrows(IllegalArgumentException::class.java) {
            registry.register(makeTool("tool_a"))
        }
    }
}
