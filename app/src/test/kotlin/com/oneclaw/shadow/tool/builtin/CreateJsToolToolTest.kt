package com.oneclaw.shadow.tool.builtin

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

class CreateJsToolToolTest {

    private lateinit var userToolManager: UserToolManager
    private lateinit var tool: CreateJsToolTool

    private val validParams: Map<String, Any?> = mapOf(
        "name" to "test_tool",
        "description" to "A test tool",
        "parameters_schema" to """{"properties": {"input": {"type": "string", "description": "Input"}}, "required": ["input"]}""",
        "js_code" to "function execute(params) { return params.input; }"
    )

    @BeforeEach
    fun setup() {
        userToolManager = mockk()
        tool = CreateJsToolTool(userToolManager)
    }

    @Test
    fun `definition has correct name`() {
        assertEquals("create_js_tool", tool.definition.name)
    }

    @Test
    fun `definition requires name description parameters_schema js_code`() {
        val required = tool.definition.parametersSchema.required
        assertTrue(required.contains("name"))
        assertTrue(required.contains("description"))
        assertTrue(required.contains("parameters_schema"))
        assertTrue(required.contains("js_code"))
    }

    @Test
    fun `definition has no required permissions`() {
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }

    @Test
    fun `execute missingName returns validation error`() = runTest {
        val result = tool.execute(validParams + mapOf("name" to null))
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("name"))
    }

    @Test
    fun `execute missingDescription returns validation error`() = runTest {
        val result = tool.execute(validParams + mapOf("description" to null))
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("description"))
    }

    @Test
    fun `execute missingSchema returns validation error`() = runTest {
        val result = tool.execute(validParams + mapOf("parameters_schema" to null))
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("parameters_schema"))
    }

    @Test
    fun `execute missingJsCode returns validation error`() = runTest {
        val result = tool.execute(validParams + mapOf("js_code" to null))
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("js_code"))
    }

    @Test
    fun `execute invalidSchemaJson returns validation error`() = runTest {
        val params = validParams + mapOf("parameters_schema" to "not valid json{{")
        val result = tool.execute(params)
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("parameters_schema"))
    }

    @Test
    fun `execute schemaMissingProperties returns validation error`() = runTest {
        val params = validParams + mapOf("parameters_schema" to """{"required": ["input"]}""")
        val result = tool.execute(params)
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute success delegates to UserToolManager and returns success`() = runTest {
        every {
            userToolManager.create(any(), any(), any(), any(), any(), any())
        } returns AppResult.Success("Tool 'test_tool' created and registered successfully.")

        val result = tool.execute(validParams)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("test_tool"))
    }

    @Test
    fun `execute withPermissions parses comma-separated permissions`() = runTest {
        every {
            userToolManager.create(
                name = "test_tool",
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = listOf("android.permission.CAMERA", "android.permission.RECORD_AUDIO"),
                timeoutSeconds = any()
            )
        } returns AppResult.Success("Tool 'test_tool' created and registered successfully.")

        val params = validParams + mapOf(
            "required_permissions" to "android.permission.CAMERA, android.permission.RECORD_AUDIO"
        )
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute withTimeout passes custom timeout value`() = runTest {
        every {
            userToolManager.create(
                name = any(),
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = any(),
                timeoutSeconds = 60
            )
        } returns AppResult.Success("Tool 'test_tool' created and registered successfully.")

        val params = validParams + mapOf("timeout_seconds" to 60)
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute defaultTimeout uses 30 when not specified`() = runTest {
        every {
            userToolManager.create(
                name = any(),
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = any(),
                timeoutSeconds = 30
            )
        } returns AppResult.Success("Tool 'test_tool' created and registered successfully.")

        val result = tool.execute(validParams)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        verify {
            userToolManager.create(
                name = any(),
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = any(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `execute managerError propagates as create_failed error`() = runTest {
        every {
            userToolManager.create(any(), any(), any(), any(), any(), any())
        } returns AppResult.Error(message = "Tool 'test_tool' already exists.")

        val result = tool.execute(validParams)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("create_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("already exists"))
    }

    @Test
    fun `execute emptyPermissionsString results in empty permissions list`() = runTest {
        every {
            userToolManager.create(
                name = any(),
                description = any(),
                parametersSchema = any(),
                jsCode = any(),
                requiredPermissions = emptyList(),
                timeoutSeconds = any()
            )
        } returns AppResult.Success("Tool 'test_tool' created and registered successfully.")

        val params = validParams + mapOf("required_permissions" to "  ")
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }
}
