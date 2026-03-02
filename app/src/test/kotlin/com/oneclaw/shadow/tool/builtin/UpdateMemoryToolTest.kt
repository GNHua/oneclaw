package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.feature.memory.MemoryManager
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateMemoryToolTest {

    private lateinit var memoryManager: MemoryManager
    private lateinit var tool: UpdateMemoryTool

    @BeforeEach
    fun setup() {
        memoryManager = mockk()
        tool = UpdateMemoryTool(memoryManager)
    }

    @Test
    fun `execute with valid old_text and new_text returns success`() = runTest {
        coEvery { memoryManager.updateLongTermMemory(any(), any()) } returns Result.success(1)

        val result = tool.execute(mapOf("old_text" to "old preference", "new_text" to "new preference"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("updated successfully"))
    }

    @Test
    fun `execute with old_text not found returns not_found error`() = runTest {
        coEvery { memoryManager.updateLongTermMemory(any(), any()) } returns Result.success(0)

        val result = tool.execute(mapOf("old_text" to "missing text", "new_text" to "replacement"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("not_found", result.errorType)
    }

    @Test
    fun `execute with old_text matching multiple locations returns ambiguous_match error`() = runTest {
        coEvery { memoryManager.updateLongTermMemory(any(), any()) } returns Result.success(3)

        val result = tool.execute(mapOf("old_text" to "common text", "new_text" to "replacement"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("ambiguous_match", result.errorType)
        assertTrue(result.errorMessage!!.contains("3"))
    }

    @Test
    fun `execute with empty old_text returns validation_error`() = runTest {
        val result = tool.execute(mapOf("old_text" to "", "new_text" to "replacement"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("old_text"))
    }

    @Test
    fun `execute with identical old_text and new_text returns validation_error`() = runTest {
        val result = tool.execute(mapOf("old_text" to "same text", "new_text" to "same text"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute with empty new_text performs deletion and returns success`() = runTest {
        coEvery { memoryManager.updateLongTermMemory(any(), any()) } returns Result.success(1)

        val result = tool.execute(mapOf("old_text" to "entry to delete", "new_text" to ""))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("deleted successfully"))
    }

    @Test
    fun `execute when updateLongTermMemory fails returns update_failed error`() = runTest {
        coEvery { memoryManager.updateLongTermMemory(any(), any()) } returns Result.failure(
            RuntimeException("IO error")
        )

        val result = tool.execute(mapOf("old_text" to "some text", "new_text" to "replacement"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("update_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("IO error"))
    }
}
