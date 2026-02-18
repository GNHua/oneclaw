package com.tomandy.palmclaw.scheduler.util

import org.junit.Assert.assertEquals
import org.junit.Test

class CronFormatterTest {

    // --- formatCronExpression ---

    @Test
    fun `every minute`() {
        assertEquals("Every minute", formatCronExpression("* * * * *"))
    }

    @Test
    fun `every N minutes`() {
        assertEquals("Every 15 minutes", formatCronExpression("*/15 * * * *"))
    }

    @Test
    fun `every hour`() {
        assertEquals("Every hour", formatCronExpression("0 * * * *"))
    }

    @Test
    fun `every N hours`() {
        assertEquals("Every 3 hours", formatCronExpression("0 */3 * * *"))
    }

    @Test
    fun `daily at specific AM time`() {
        assertEquals("Daily at 9:30 AM", formatCronExpression("30 9 * * *"))
    }

    @Test
    fun `daily at PM time`() {
        assertEquals("Daily at 2:00 PM", formatCronExpression("0 14 * * *"))
    }

    @Test
    fun `daily at midnight`() {
        assertEquals("Daily at 12:00 AM", formatCronExpression("0 0 * * *"))
    }

    @Test
    fun `weekly single day`() {
        assertEquals("Every Monday at 9:00 AM", formatCronExpression("0 9 * * MON"))
    }

    @Test
    fun `weekly multiple days`() {
        assertEquals(
            "Every Monday, Wednesday, Friday at 9:00 AM",
            formatCronExpression("0 9 * * MON,WED,FRI")
        )
    }

    @Test
    fun `monthly on specific day`() {
        assertEquals("Monthly on the 15th at 9:00 AM", formatCronExpression("0 9 15 * *"))
    }

    @Test
    fun `yearly on specific date`() {
        assertEquals("Yearly on Jun 1 at 9:00 AM", formatCronExpression("0 9 1 6 *"))
    }

    @Test
    fun `invalid expression returns raw input`() {
        val raw = "* * *"
        assertEquals(raw, formatCronExpression(raw))
    }

    // --- formatIntervalMinutes ---

    @Test
    fun `interval under 60 minutes`() {
        assertEquals("Every 30 min", formatIntervalMinutes(30))
    }

    @Test
    fun `interval exactly 60 minutes`() {
        assertEquals("Every hour", formatIntervalMinutes(60))
    }

    @Test
    fun `interval multiple hours`() {
        assertEquals("Every 3 hours", formatIntervalMinutes(180))
    }

    @Test
    fun `interval one day`() {
        assertEquals("Every day", formatIntervalMinutes(1440))
    }

    @Test
    fun `interval one week`() {
        assertEquals("Every week", formatIntervalMinutes(10080))
    }

    @Test
    fun `interval multiple days`() {
        assertEquals("Every 3 days", formatIntervalMinutes(4320))
    }

    @Test
    fun `interval non-round minutes`() {
        assertEquals("Every 95 min", formatIntervalMinutes(95))
    }
}
