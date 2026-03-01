package com.oneclaw.shadow.core.notification

import android.content.Context
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class NotificationHelperTest {

    private lateinit var helper: NotificationHelper

    @BeforeEach
    fun setUp() {
        val context = mockk<Context>(relaxed = true)
        helper = NotificationHelper(context)
    }

    @Test
    fun `truncatePreview returns short text unchanged`() {
        val result = helper.truncatePreview("short text")
        assertEquals("short text", result)
    }

    @Test
    fun `truncatePreview truncates 150-char string to 100 chars plus ellipsis`() {
        val longText = "a".repeat(150)
        val result = helper.truncatePreview(longText)
        assertEquals("a".repeat(100) + "...", result)
        assertEquals(103, result.length)
    }

    @Test
    fun `truncatePreview returns exactly 100 chars unchanged`() {
        val text = "b".repeat(100)
        val result = helper.truncatePreview(text)
        assertEquals(text, result)
    }

    @Test
    fun `truncatePreview trims whitespace before truncating`() {
        val paddedText = "  " + "c".repeat(100) + "  "
        val result = helper.truncatePreview(paddedText)
        // After trim, exactly 100 chars, so no truncation
        assertEquals("c".repeat(100), result)
    }

    @Test
    fun `truncatePreview trims and then truncates if still over 100`() {
        val paddedText = "  " + "d".repeat(150) + "  "
        val result = helper.truncatePreview(paddedText)
        assertEquals("d".repeat(100) + "...", result)
    }
}
