package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.feature.memory.MemoryManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SaveMemoryToolTest {

    private lateinit var memoryManager: MemoryManager
    private lateinit var tool: SaveMemoryTool

    @BeforeEach
    fun setup() {
        memoryManager = mockk()
        tool = SaveMemoryTool(memoryManager)
        // Phase 2: readLongTermMemory is called before saving; default to empty
        coEvery { memoryManager.readLongTermMemory() } returns ""
    }

    @Test
    fun `execute with valid content returns success`() = runTest {
        coEvery { memoryManager.saveToLongTermMemory(any()) } returns Result.success(Unit)

        val result = tool.execute(mapOf("content" to "User prefers dark mode"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Memory saved successfully"))
    }

    @Test
    fun `execute with empty content returns validation error`() = runTest {
        val result = tool.execute(mapOf("content" to ""))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("content"))
    }

    @Test
    fun `execute with whitespace-only content returns validation error`() = runTest {
        val result = tool.execute(mapOf("content" to "   "))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("content"))
    }

    @Test
    fun `execute with null content returns validation error`() = runTest {
        val result = tool.execute(mapOf("content" to null))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("content"))
    }

    @Test
    fun `execute with missing content key returns validation error`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute with content at exactly 5000 chars returns success`() = runTest {
        val content = "A".repeat(SaveMemoryTool.MAX_CONTENT_LENGTH)
        coEvery { memoryManager.saveToLongTermMemory(content) } returns Result.success(Unit)

        val result = tool.execute(mapOf("content" to content))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute with content exceeding 5000 chars returns validation error`() = runTest {
        val content = "A".repeat(SaveMemoryTool.MAX_CONTENT_LENGTH + 1)

        val result = tool.execute(mapOf("content" to content))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("5000"))
        assertTrue(result.errorMessage!!.contains("${SaveMemoryTool.MAX_CONTENT_LENGTH + 1}"))
    }

    @Test
    fun `execute when saveToLongTermMemory fails returns save_failed error`() = runTest {
        coEvery { memoryManager.saveToLongTermMemory(any()) } returns Result.failure(
            RuntimeException("Disk write failed")
        )

        val result = tool.execute(mapOf("content" to "Some content"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("save_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("Disk write failed"))
    }

    @Test
    fun `execute calls saveToLongTermMemory with trimmed content`() = runTest {
        coEvery { memoryManager.saveToLongTermMemory(any()) } returns Result.success(Unit)

        tool.execute(mapOf("content" to "  trimmed content  "))

        coVerify { memoryManager.saveToLongTermMemory("trimmed content") }
    }

    @Test
    fun `tool name is save_memory`() {
        assertEquals("save_memory", tool.definition.name)
    }

    @Test
    fun `tool has content as required parameter`() {
        val schema = tool.definition.parametersSchema
        assertTrue(schema.required.contains("content"))
        assertTrue(schema.properties.containsKey("content"))
    }

    @Test
    fun `tool has no required permissions`() {
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }

    @Test
    fun `tool timeout is 10 seconds`() {
        assertEquals(10, tool.definition.timeoutSeconds)
    }

    // Phase 2 tests

    @Test
    fun `execute with content already in memory returns duplicate_detected error`() = runTest {
        coEvery { memoryManager.readLongTermMemory() } returns "User prefers dark mode in all apps"

        val result = tool.execute(mapOf("content" to "User prefers dark mode in all apps"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("duplicate_detected", result.errorType)
    }

    @Test
    fun `execute with valid new content includes memory preview in success response`() = runTest {
        coEvery { memoryManager.readLongTermMemory() } returns "# Long-term Memory\n\n- Previous entry"
        coEvery { memoryManager.saveToLongTermMemory(any()) } returns Result.success(Unit)

        val result = tool.execute(mapOf("content" to "User likes Kotlin"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Memory saved successfully"))
        assertTrue(result.result!!.contains("Current MEMORY.md content"))
    }

    @Test
    fun `execute with short content skips deduplication check`() = runTest {
        // Content shorter than MIN_DEDUP_LENGTH should not trigger dedup check
        val shortContent = "short"
        coEvery { memoryManager.readLongTermMemory() } returns "short"
        coEvery { memoryManager.saveToLongTermMemory(shortContent) } returns Result.success(Unit)

        val result = tool.execute(mapOf("content" to shortContent))

        // Should succeed even though "short" appears in existing memory
        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    // Phase 3 tests

    @Test
    fun `execute with category preferences calls saveToLongTermMemoryInSection with Preferences`() = runTest {
        coEvery { memoryManager.saveToLongTermMemoryInSection(any(), any()) } returns Result.success(Unit)

        tool.execute(mapOf("content" to "Prefers dark mode", "category" to "preferences"))

        coVerify { memoryManager.saveToLongTermMemoryInSection("Prefers dark mode", "Preferences") }
    }

    @Test
    fun `execute with category profile calls saveToLongTermMemoryInSection with User Profile`() = runTest {
        coEvery { memoryManager.saveToLongTermMemoryInSection(any(), any()) } returns Result.success(Unit)

        tool.execute(mapOf("content" to "Software engineer", "category" to "profile"))

        coVerify { memoryManager.saveToLongTermMemoryInSection("Software engineer", "User Profile") }
    }

    @Test
    fun `execute with unknown category defaults to Notes section`() = runTest {
        coEvery { memoryManager.saveToLongTermMemoryInSection(any(), any()) } returns Result.success(Unit)

        tool.execute(mapOf("content" to "Some note", "category" to "unknown_category"))

        coVerify { memoryManager.saveToLongTermMemoryInSection("Some note", "Notes") }
    }

    @Test
    fun `execute without category calls saveToLongTermMemory (no section)`() = runTest {
        coEvery { memoryManager.saveToLongTermMemory(any()) } returns Result.success(Unit)

        tool.execute(mapOf("content" to "Some fact"))

        coVerify { memoryManager.saveToLongTermMemory("Some fact") }
        coVerify(exactly = 0) { memoryManager.saveToLongTermMemoryInSection(any(), any()) }
    }
}
