package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.tool.js.UserToolInfo
import com.oneclaw.shadow.tool.js.UserToolManager
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ListUserToolsToolTest {

    private lateinit var userToolManager: UserToolManager
    private lateinit var tool: ListUserToolsTool

    @BeforeEach
    fun setup() {
        userToolManager = mockk()
        tool = ListUserToolsTool(userToolManager)
    }

    @Test
    fun `definition has correct name`() {
        assertEquals("list_user_tools", tool.definition.name)
    }

    @Test
    fun `definition has no required parameters`() {
        assertTrue(tool.definition.parametersSchema.required.isEmpty())
        assertTrue(tool.definition.parametersSchema.properties.isEmpty())
    }

    @Test
    fun `definition has no required permissions`() {
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }

    @Test
    fun `execute noTools returns no tools found message`() = runTest {
        every { userToolManager.listUserTools() } returns emptyList()

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("No user-created tools found"))
    }

    @Test
    fun `execute withTools returns formatted tool list`() = runTest {
        val tools = listOf(
            UserToolInfo(
                name = "my_tool",
                description = "Does something useful",
                filePath = "/data/user/0/com.oneclaw.shadow/files/tools/my_tool.js"
            ),
            UserToolInfo(
                name = "another_tool",
                description = "Does something else",
                filePath = "/data/user/0/com.oneclaw.shadow/files/tools/another_tool.js"
            )
        )
        every { userToolManager.listUserTools() } returns tools

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("User-created tools (2)"))
        assertTrue(result.result!!.contains("my_tool"))
        assertTrue(result.result!!.contains("Does something useful"))
        assertTrue(result.result!!.contains("another_tool"))
        assertTrue(result.result!!.contains("Does something else"))
    }

    @Test
    fun `execute withSingleTool shows count of 1`() = runTest {
        val tools = listOf(
            UserToolInfo(
                name = "solo_tool",
                description = "Only tool",
                filePath = "/data/user/0/com.oneclaw.shadow/files/tools/solo_tool.js"
            )
        )
        every { userToolManager.listUserTools() } returns tools

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("User-created tools (1)"))
        assertTrue(result.result!!.contains("solo_tool"))
    }

    @Test
    fun `execute includes file path in output`() = runTest {
        val filePath = "/data/user/0/com.oneclaw.shadow/files/tools/my_tool.js"
        val tools = listOf(
            UserToolInfo(
                name = "my_tool",
                description = "A tool",
                filePath = filePath
            )
        )
        every { userToolManager.listUserTools() } returns tools

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains(filePath))
    }
}
