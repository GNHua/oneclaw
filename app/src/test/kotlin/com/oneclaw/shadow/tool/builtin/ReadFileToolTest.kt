package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class ReadFileToolTest {

    private val tool = ReadFileTool()
    private lateinit var tempDir: File
    private lateinit var tempFile: File

    @BeforeEach
    fun setup() {
        tempDir = createTempDir("oneclaw_test")
        tempFile = File(tempDir, "test.txt").also {
            it.writeText("Hello, World!")
        }
    }

    @AfterEach
    fun teardown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `execute reads file content successfully`() = runTest {
        val result = tool.execute(mapOf("path" to tempFile.absolutePath))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("Hello, World!", result.result)
    }

    @Test
    fun `execute returns file_not_found for nonexistent path`() = runTest {
        val result = tool.execute(mapOf("path" to "/nonexistent/path/file.txt"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("file_not_found", result.errorType)
    }

    @Test
    fun `execute returns validation_error for directory path`() = runTest {
        val result = tool.execute(mapOf("path" to tempDir.absolutePath))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns validation_error when path parameter missing`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns permission_denied for restricted path`() = runTest {
        val result = tool.execute(mapOf("path" to "/data/data/com.example/prefs.xml"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("permission_denied", result.errorType)
    }

    @Test
    fun `execute returns validation_error for unsupported encoding`() = runTest {
        val result = tool.execute(mapOf(
            "path" to tempFile.absolutePath,
            "encoding" to "INVALID_ENCODING"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute reads file with explicit UTF-8 encoding`() = runTest {
        val result = tool.execute(mapOf(
            "path" to tempFile.absolutePath,
            "encoding" to "UTF-8"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("Hello, World!", result.result)
    }

    @Test
    fun `execute returns file_too_large for oversized files`() = runTest {
        // Write a file larger than 1MB
        val largeFile = File(tempDir, "large.txt")
        val sb = StringBuilder()
        repeat(1100) { sb.append("x".repeat(1000)) }  // ~1.1MB
        largeFile.writeText(sb.toString())

        val result = tool.execute(mapOf("path" to largeFile.absolutePath))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("file_too_large", result.errorType)
    }
}
