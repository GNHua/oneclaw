package com.oneclaw.shadow.core.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ToolResultTruncatorTest {

    @Test
    fun `returns input unchanged when length is within limit`() {
        val input = "short result"
        assertEquals(input, ToolResultTruncator.truncate(input))
    }

    @Test
    fun `returns input unchanged when length equals MAX_CHARS`() {
        val input = "a".repeat(ToolResultTruncator.MAX_CHARS)
        assertEquals(input, ToolResultTruncator.truncate(input))
    }

    @Test
    fun `truncates input when length exceeds MAX_CHARS`() {
        val input = "a".repeat(ToolResultTruncator.MAX_CHARS + 100)
        val result = ToolResultTruncator.truncate(input)
        assertTrue(result.startsWith("a".repeat(ToolResultTruncator.MAX_CHARS)))
        assertTrue(result.contains("[... content truncated"))
    }

    @Test
    fun `truncation message includes original length`() {
        val extraChars = 500
        val totalLength = ToolResultTruncator.MAX_CHARS + extraChars
        val input = "x".repeat(totalLength)
        val result = ToolResultTruncator.truncate(input)
        assertTrue(result.contains("$totalLength characters"))
    }

    @Test
    fun `truncation message includes MAX_CHARS`() {
        val input = "x".repeat(ToolResultTruncator.MAX_CHARS + 1)
        val result = ToolResultTruncator.truncate(input)
        assertTrue(result.contains("${ToolResultTruncator.MAX_CHARS}"))
    }

    @Test
    fun `returns empty string unchanged`() {
        assertEquals("", ToolResultTruncator.truncate(""))
    }

    @Test
    fun `MAX_CHARS constant is 30000`() {
        assertEquals(30_000, ToolResultTruncator.MAX_CHARS)
    }

    @Test
    fun `truncated result starts with first MAX_CHARS chars of input`() {
        val prefix = "unique-prefix-"
        val input = prefix + "b".repeat(ToolResultTruncator.MAX_CHARS)
        val result = ToolResultTruncator.truncate(input)
        assertTrue(result.startsWith(prefix))
        assertEquals(ToolResultTruncator.MAX_CHARS, result.indexOf("[...").let {
            if (it >= 0) it else result.length
        }.coerceAtMost(ToolResultTruncator.MAX_CHARS))
    }
}
