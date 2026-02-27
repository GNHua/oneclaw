package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class WriteFileToolTest {

    private val tool = WriteFileTool()
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        tempDir = createTempDir("oneclaw_write_test")
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `execute writes content to new file`() = runTest {
        val file = File(tempDir, "output.txt")
        val result = tool.execute(mapOf(
            "path" to file.absolutePath,
            "content" to "Hello, file!"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(file.exists())
        assertEquals("Hello, file!", file.readText())
    }

    @Test
    fun `execute overwrites existing file by default`() = runTest {
        val file = File(tempDir, "output.txt").also { it.writeText("old content") }

        tool.execute(mapOf(
            "path" to file.absolutePath,
            "content" to "new content"
        ))

        assertEquals("new content", file.readText())
    }

    @Test
    fun `execute appends to existing file in append mode`() = runTest {
        val file = File(tempDir, "output.txt").also { it.writeText("line1\n") }

        val result = tool.execute(mapOf(
            "path" to file.absolutePath,
            "content" to "line2\n",
            "mode" to "append"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("line1\nline2\n", file.readText())
    }

    @Test
    fun `execute creates parent directories if they do not exist`() = runTest {
        val file = File(tempDir, "subdir/nested/output.txt")
        assertPathDoesNotExist(file)

        val result = tool.execute(mapOf(
            "path" to file.absolutePath,
            "content" to "nested content"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(file.exists())
    }

    @Test
    fun `execute returns validation_error when path missing`() = runTest {
        val result = tool.execute(mapOf("content" to "data"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns validation_error when content missing`() = runTest {
        val result = tool.execute(mapOf("path" to File(tempDir, "out.txt").absolutePath))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns permission_denied for restricted path`() = runTest {
        val result = tool.execute(mapOf(
            "path" to "/data/data/com.example/evil.txt",
            "content" to "evil"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("permission_denied", result.errorType)
    }

    @Test
    fun `execute result includes bytes written count`() = runTest {
        val file = File(tempDir, "output.txt")
        val content = "Hello"
        val result = tool.execute(mapOf(
            "path" to file.absolutePath,
            "content" to content
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains(content.toByteArray().size.toString()))
    }

    private fun assertPathDoesNotExist(file: File) {
        check(!file.exists()) { "Expected $file to not exist before test" }
    }
}
