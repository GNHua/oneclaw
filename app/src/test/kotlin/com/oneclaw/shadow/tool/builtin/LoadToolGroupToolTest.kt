package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolGroupDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoadToolGroupToolTest {

    private lateinit var registry: ToolRegistry
    private lateinit var tool: LoadToolGroupTool

    private fun makeTool(name: String): Tool = object : Tool {
        override val definition = ToolDefinition(
            name = name,
            description = "Description of $name",
            parametersSchema = ToolParametersSchema(emptyMap(), emptyList()),
            requiredPermissions = emptyList(),
            timeoutSeconds = 5
        )
        override suspend fun execute(parameters: Map<String, Any?>) = ToolResult.success("ok")
    }

    @BeforeEach
    fun setup() {
        registry = ToolRegistry()
        tool = LoadToolGroupTool(registry)
    }

    private fun setupPdfGroup() {
        registry.registerGroup(
            ToolGroupDefinition("pdf", "PDF Tools", "PDF tool group")
        )
        registry.register(
            makeTool("pdf_info"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "pdf")
        )
        registry.register(
            makeTool("pdf_extract_text"),
            ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "pdf")
        )
    }

    @Test
    fun `execute returns tool list for valid group`() = runTest {
        setupPdfGroup()

        val result = tool.execute(mapOf("group_name" to "pdf"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("pdf_info"))
        assertTrue(result.result!!.contains("pdf_extract_text"))
        assertTrue(result.result!!.contains("Loaded 2 tools"))
        assertTrue(result.result!!.contains("PDF Tools"))
    }

    @Test
    fun `execute returns error for unknown group`() = runTest {
        setupPdfGroup()

        val result = tool.execute(mapOf("group_name" to "nonexistent"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("pdf"))
    }

    @Test
    fun `execute returns error for missing parameter`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("missing_parameter", result.errorType)
    }

    @Test
    fun `execute returns error for group with no tools`() = runTest {
        registry.registerGroup(
            ToolGroupDefinition("empty_group", "Empty Group", "No tools here")
        )

        val result = tool.execute(mapOf("group_name" to "empty_group"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("empty_group", result.errorType)
    }

    @Test
    fun `execute tool definition contains correct metadata`() {
        assertEquals("load_tool_group", tool.definition.name)
        assertTrue(tool.definition.description.contains("tool group"))
        assertEquals(listOf("group_name"), tool.definition.parametersSchema.required)
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }
}
