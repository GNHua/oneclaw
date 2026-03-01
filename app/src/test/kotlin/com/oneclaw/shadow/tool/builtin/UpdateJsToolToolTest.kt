package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.js.UserToolManager
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateJsToolToolTest {

    private lateinit var userToolManager: UserToolManager
    private lateinit var tool: UpdateJsToolTool

    @BeforeEach
    fun setup() {
        userToolManager = mockk()
        tool = UpdateJsToolTool(userToolManager)
    }

    @Test
    fun `definition has correct name`() {
        assertEquals("update_js_tool", tool.definition.name)
    }

    @Test
    fun `definition requires only name`() {
        val required = tool.definition.parametersSchema.required
        assertEquals(listOf("name"), required)
    }

    @Test
    fun `definition has no required permissions`() {
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }

    @Test
    fun `execute missingName returns validation error`() = runTest {
        val result = tool.execute(mapOf("name" to null))
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("name"))
    }

    @Test
    fun `execute emptyParams returns validation error for missing name`() = runTest {
        val result = tool.execute(emptyMap())
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute success delegates to UserToolManager`() = runTest {
        every {
            userToolManager.update(any(), any(), any(), any(), any(), any())
        } returns AppResult.Success("Tool 'my_tool' updated successfully.")

        val result = tool.execute(mapOf("name" to "my_tool", "description" to "New description"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("my_tool"))
    }

    @Test
    fun `execute invalidSchema returns validation error`() = runTest {
        val params = mapOf(
            "name" to "my_tool",
            "parameters_schema" to "{ invalid json"
        )
        val result = tool.execute(params)
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("parameters_schema"))
    }

    @Test
    fun `execute schemaMissingProperties returns validation error`() = runTest {
        val params = mapOf(
            "name" to "my_tool",
            "parameters_schema" to """{"required": ["input"]}"""
        )
        val result = tool.execute(params)
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute managerError propagates as update_failed error`() = runTest {
        every {
            userToolManager.update(any(), any(), any(), any(), any(), any())
        } returns AppResult.Error(message = "Tool 'unknown' not found.")

        val result = tool.execute(mapOf("name" to "unknown"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("update_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("not found"))
    }

    @Test
    fun `execute withNoOptionalParams passes nulls to manager`() = runTest {
        every {
            userToolManager.update(
                name = "my_tool",
                description = null,
                parametersSchema = null,
                jsCode = null,
                requiredPermissions = null,
                timeoutSeconds = null
            )
        } returns AppResult.Success("Tool updated.")

        val result = tool.execute(mapOf("name" to "my_tool"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        verify {
            userToolManager.update(
                name = "my_tool",
                description = null,
                parametersSchema = null,
                jsCode = null,
                requiredPermissions = null,
                timeoutSeconds = null
            )
        }
    }

    @Test
    fun `execute withPermissions parses comma-separated permissions`() = runTest {
        every {
            userToolManager.update(
                name = "my_tool",
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = listOf("android.permission.CAMERA", "android.permission.INTERNET"),
                timeoutSeconds = any()
            )
        } returns AppResult.Success("Tool updated.")

        val result = tool.execute(mapOf(
            "name" to "my_tool",
            "required_permissions" to "android.permission.CAMERA, android.permission.INTERNET"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute withTimeout passes custom timeout to manager`() = runTest {
        every {
            userToolManager.update(
                name = "my_tool",
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = any(),
                timeoutSeconds = 120
            )
        } returns AppResult.Success("Tool updated.")

        val result = tool.execute(mapOf("name" to "my_tool", "timeout_seconds" to 120))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute withValidSchema passes parsed schema to manager`() = runTest {
        every {
            userToolManager.update(
                name = "my_tool",
                description = any(),
                parametersSchema = match { schema: ToolParametersSchema? ->
                    schema != null &&
                    schema.properties.containsKey("q") &&
                    schema.required.contains("q")
                },
                jsCode = any(),
                requiredPermissions = any(),
                timeoutSeconds = any()
            )
        } returns AppResult.Success("Tool updated.")

        val result = tool.execute(mapOf(
            "name" to "my_tool",
            "parameters_schema" to """{"properties": {"q": {"type": "string", "description": "Query"}}, "required": ["q"]}"""
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }
}
