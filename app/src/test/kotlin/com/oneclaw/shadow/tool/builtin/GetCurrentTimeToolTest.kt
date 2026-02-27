package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GetCurrentTimeToolTest {

    private val tool = GetCurrentTimeTool()

    @Test
    fun `execute with no parameters returns iso8601 result`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        // ISO 8601 offset format: contains 'T' and offset like +05:00 or Z
        assertTrue(result.result!!.contains("T"), "Expected ISO 8601 format, got: ${result.result}")
    }

    @Test
    fun `execute with iso8601 format returns ISO formatted string`() = runTest {
        val result = tool.execute(mapOf("format" to "iso8601"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("T"))
    }

    @Test
    fun `execute with human_readable format returns readable string`() = runTest {
        val result = tool.execute(mapOf("format" to "human_readable"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        // Human readable contains "at" and AM/PM
        val r = result.result!!
        assertTrue(r.contains(" at ") || r.contains(","), "Unexpected format: $r")
    }

    @Test
    fun `execute with valid timezone succeeds`() = runTest {
        val result = tool.execute(mapOf("timezone" to "America/New_York"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }

    @Test
    fun `execute with invalid timezone returns validation error`() = runTest {
        val result = tool.execute(mapOf("timezone" to "Invalid/Zone"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("Invalid/Zone"))
    }

    @Test
    fun `execute with Shanghai timezone succeeds`() = runTest {
        val result = tool.execute(mapOf("timezone" to "Asia/Shanghai"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
    }
}
