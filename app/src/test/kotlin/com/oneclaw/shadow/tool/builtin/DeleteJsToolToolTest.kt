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

class DeleteJsToolToolTest {

    private lateinit var userToolManager: UserToolManager
    private lateinit var tool: DeleteJsToolTool

    @BeforeEach
    fun setup() {
        userToolManager = mockk()
        tool = DeleteJsToolTool(userToolManager)
    }

    @Test
    fun `definition has correct name`() {
        assertEquals("delete_js_tool", tool.definition.name)
    }

    @Test
    fun `definition requires name parameter`() {
        val required = tool.definition.parametersSchema.required
        assertEquals(listOf("name"), required)
        assertTrue(tool.definition.parametersSchema.properties.containsKey("name"))
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
    fun `execute emptyParams returns validation error`() = runTest {
        val result = tool.execute(emptyMap())
        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute success delegates to UserToolManager`() = runTest {
        every { userToolManager.delete("my_tool") } returns AppResult.Success("Tool 'my_tool' deleted successfully.")

        val result = tool.execute(mapOf("name" to "my_tool"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("my_tool"))
        verify { userToolManager.delete("my_tool") }
    }

    @Test
    fun `execute managerError propagates as delete_failed error`() = runTest {
        every { userToolManager.delete("nonexistent") } returns AppResult.Error(
            message = "Tool 'nonexistent' not found."
        )

        val result = tool.execute(mapOf("name" to "nonexistent"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("delete_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("not found"))
    }

    @Test
    fun `execute builtinToolError propagates as delete_failed`() = runTest {
        every { userToolManager.delete("load_skill") } returns AppResult.Error(
            message = "Cannot delete tool 'load_skill': not a user-created tool."
        )

        val result = tool.execute(mapOf("name" to "load_skill"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("delete_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("not a user-created tool"))
    }

    @Test
    fun `execute passes exact tool name to manager`() = runTest {
        every { userToolManager.delete("my_specific_tool") } returns AppResult.Success(
            "Tool 'my_specific_tool' deleted successfully."
        )

        tool.execute(mapOf("name" to "my_specific_tool"))

        verify(exactly = 1) { userToolManager.delete("my_specific_tool") }
    }
}
