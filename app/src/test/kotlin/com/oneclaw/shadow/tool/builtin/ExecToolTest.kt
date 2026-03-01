package com.oneclaw.shadow.tool.builtin

import android.content.Context
import com.oneclaw.shadow.core.model.ToolResultStatus
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

class ExecToolTest {

    private lateinit var context: Context
    private lateinit var tool: ExecTool
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        tempDir = Files.createTempDirectory("exec_tool_test").toFile()
        context = mockk()
        every { context.filesDir } returns tempDir
        tool = ExecTool(context)
    }

    @Test
    fun testDefinition() {
        assertEquals("exec", tool.definition.name)
        assertTrue(tool.definition.parametersSchema.required.contains("command"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("command"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("timeout_seconds"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("working_directory"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("max_length"))
        // timeoutSeconds on definition should be MAX_TIMEOUT_SECONDS + 5 = 125
        assertEquals(125, tool.definition.timeoutSeconds)
    }

    @Test
    fun testDefinition_commandIsRequired() {
        assertTrue(tool.definition.parametersSchema.required.contains("command"))
        assertFalse(tool.definition.parametersSchema.required.contains("timeout_seconds"))
        assertFalse(tool.definition.parametersSchema.required.contains("working_directory"))
        assertFalse(tool.definition.parametersSchema.required.contains("max_length"))
    }

    @Test
    fun testDefinition_requiredPermissions_empty() {
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }

    @Test
    fun testExecute_emptyCommand() = runTest {
        val result = tool.execute(mapOf("command" to ""))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("command", ignoreCase = true))
    }

    @Test
    fun testExecute_blankCommand() = runTest {
        val result = tool.execute(mapOf("command" to "   "))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_missingCommand() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_invalidWorkingDir() = runTest {
        val result = tool.execute(
            mapOf(
                "command" to "echo hello",
                "working_directory" to "/nonexistent/path/that/does/not/exist"
            )
        )

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("/nonexistent/path/that/does/not/exist"))
    }

    @Test
    fun testExecute_workingDirIsFile() = runTest {
        val tempFile = File(tempDir, "somefile.txt")
        tempFile.writeText("content")

        val result = tool.execute(
            mapOf(
                "command" to "echo hello",
                "working_directory" to tempFile.absolutePath
            )
        )

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_simpleCommand() = runTest {
        val result = tool.execute(mapOf("command" to "echo hello"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 0]"))
        assertTrue(result.result!!.contains("hello"))
    }

    @Test
    fun testExecute_commandWithStderr() = runTest {
        // Write directly to stderr using sh
        val result = tool.execute(mapOf("command" to "echo 'error message' >&2"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[stderr]"))
        assertTrue(result.result!!.contains("error message"))
    }

    @Test
    fun testExecute_commandWithPipes() = runTest {
        val result = tool.execute(mapOf("command" to "echo hello | tr a-z A-Z"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 0]"))
        assertTrue(result.result!!.contains("HELLO"))
    }

    @Test
    fun testExecute_timeout() = runTest {
        // Use a very short timeout to trigger timeout handling
        val result = tool.execute(
            mapOf(
                "command" to "while true; do sleep 0.1; done",
                "timeout_seconds" to 2
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        // Timed-out commands return success with timeout indicator
        assertTrue(result.result!!.contains("timeout after 2s"))
        assertTrue(result.result!!.contains("Process killed after 2 seconds timeout."))
    }

    @Test
    fun testExecute_noOutput() = runTest {
        // A command that produces no stdout or stderr
        val result = tool.execute(mapOf("command" to "true"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 0]"))
        assertTrue(result.result!!.contains("(no output)"))
    }

    @Test
    fun testExecute_maxLength() = runTest {
        // Write a file with known content larger than max_length, then cat it
        val largeFile = File(tempDir, "large.txt")
        val largeContent = "A".repeat(500)
        largeFile.writeText(largeContent)

        val result = tool.execute(
            mapOf(
                "command" to "cat ${largeFile.absolutePath}",
                "max_length" to 50
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 0]"))

        // The captured stdout should be truncated to max_length (50 chars)
        // Extract stdout: content after "[Exit Code: 0]\n\n", before any "[stderr]" section
        val text = result.result!!
        val stdoutStart = text.indexOf("\n\n") + 2
        val stdoutEnd = if (text.contains("[stderr]")) text.indexOf("[stderr]") else text.length
        val stdoutPart = text.substring(stdoutStart, stdoutEnd).trim()

        // stdout should not contain more than 50 chars of 'A'
        assertTrue(
            stdoutPart.length <= 50,
            "stdout length ${stdoutPart.length} exceeds max_length 50. Content: '$stdoutPart'"
        )
        // And it should actually contain some 'A' characters
        assertTrue(stdoutPart.contains("A"))
    }

    @Test
    fun testExecute_exitCode() = runTest {
        val result = tool.execute(mapOf("command" to "exit 42"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 42]"))
    }

    @Test
    fun testExecute_customWorkingDirectory() = runTest {
        val subDir = File(tempDir, "subdir")
        subDir.mkdirs()

        val result = tool.execute(
            mapOf(
                "command" to "pwd",
                "working_directory" to subDir.absolutePath
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("[Exit Code: 0]"))
        // The working directory path should appear in the output
        assertTrue(result.result!!.contains(subDir.canonicalPath))
    }

    @Test
    fun testExecute_timeoutClamped() = runTest {
        // timeout_seconds > 120 should be clamped to 120; verify normal execution still works
        val result = tool.execute(
            mapOf(
                "command" to "echo clamped",
                "timeout_seconds" to 999
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("clamped"))
    }

    @Test
    fun testExecute_timeoutSeconds_asDouble() = runTest {
        // Verify parseIntParam handles Double (JSON numbers are often doubles)
        val result = tool.execute(
            mapOf(
                "command" to "echo hello",
                "timeout_seconds" to 30.0
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("hello"))
    }

    @Test
    fun testExecute_timeoutSeconds_asString() = runTest {
        // Verify parseIntParam handles String representation
        val result = tool.execute(
            mapOf(
                "command" to "echo hello",
                "timeout_seconds" to "30"
            )
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("hello"))
    }

    @Test
    fun testFormatOutput_bothStdoutAndStderr() = runTest {
        // Command that outputs to both stdout and stderr
        val result = tool.execute(
            mapOf("command" to "echo 'out'; echo 'err' >&2")
        )

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val text = result.result!!
        assertTrue(text.contains("[Exit Code: 0]"))
        assertTrue(text.contains("out"))
        assertTrue(text.contains("[stderr]"))
        assertTrue(text.contains("err"))
    }
}
