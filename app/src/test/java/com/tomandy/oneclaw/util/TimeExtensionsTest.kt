package com.tomandy.oneclaw.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.TimeUnit

class TimeExtensionsTest {

    @Test
    fun `formatTimestamp returns Just now for less than 1 minute ago`() {
        val timestamp = System.currentTimeMillis() - TimeUnit.SECONDS.toMillis(30)
        assertEquals("Just now", formatTimestamp(timestamp))
    }

    @Test
    fun `formatTimestamp returns minutes ago for 1 to 59 minutes`() {
        val timestamp = System.currentTimeMillis() - TimeUnit.MINUTES.toMillis(5)
        assertEquals("5 min ago", formatTimestamp(timestamp))
    }

    @Test
    fun `formatTimestamp returns time only for earlier today`() {
        val timestamp = System.currentTimeMillis() - TimeUnit.HOURS.toMillis(3)
        val result = formatTimestamp(timestamp)
        // Should match pattern like "2:34 PM"
        assertTrue("Expected time format (h:mm a), got: $result",
            result.matches(Regex("\\d{1,2}:\\d{2} [AP]M")))
    }

    @Test
    fun `formatTimestamp returns day and time for within past week`() {
        val timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(3)
        val result = formatTimestamp(timestamp)
        // Should match pattern like "Mon 2:34 PM"
        assertTrue("Expected day+time format (EEE h:mm a), got: $result",
            result.matches(Regex("\\w{3} \\d{1,2}:\\d{2} [AP]M")))
    }

    @Test
    fun `formatTimestamp returns month and day for older than a week`() {
        val timestamp = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(30)
        val result = formatTimestamp(timestamp)
        // Should match pattern like "Jan 18"
        assertTrue("Expected date format (MMM d), got: $result",
            result.matches(Regex("\\w{3} \\d{1,2}")))
    }

    @Test
    fun `formatFullTimestamp returns full date and time format`() {
        val result = formatFullTimestamp(System.currentTimeMillis())
        // Should match pattern like "Feb 17, 2026 at 3:45 PM"
        assertTrue("Expected full timestamp format, got: $result",
            result.matches(Regex("\\w{3} \\d{1,2}, \\d{4} at \\d{1,2}:\\d{2} [AP]M")))
    }
}
