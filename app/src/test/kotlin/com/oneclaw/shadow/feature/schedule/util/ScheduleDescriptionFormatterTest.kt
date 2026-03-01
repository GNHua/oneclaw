package com.oneclaw.shadow.feature.schedule.util

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.time.ZoneId

class ScheduleDescriptionFormatterTest {

    private fun createTask(
        scheduleType: ScheduleType = ScheduleType.DAILY,
        hour: Int = 8,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        dateMillis: Long? = null
    ) = ScheduledTask(
        id = "task-1",
        name = "Test",
        agentId = "agent-1",
        prompt = "prompt",
        scheduleType = scheduleType,
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        dateMillis = dateMillis,
        isEnabled = true,
        lastExecutionAt = null,
        lastExecutionStatus = null,
        lastExecutionSessionId = null,
        nextTriggerAt = null,
        createdAt = 100L,
        updatedAt = 100L
    )

    @Test
    fun `daily schedule formats correctly`() {
        val task = createTask(scheduleType = ScheduleType.DAILY, hour = 7, minute = 0)
        assertEquals("Daily at 07:00", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `daily schedule pads hour and minute with zeros`() {
        val task = createTask(scheduleType = ScheduleType.DAILY, hour = 9, minute = 5)
        assertEquals("Daily at 09:05", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `weekly schedule formats correctly with day name`() {
        // DayOfWeek.MONDAY = 1
        val task = createTask(scheduleType = ScheduleType.WEEKLY, hour = 9, minute = 0, dayOfWeek = 1)
        assertEquals("Every Monday at 09:00", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `weekly schedule formats correctly for Wednesday`() {
        // DayOfWeek.WEDNESDAY = 3
        val task = createTask(scheduleType = ScheduleType.WEEKLY, hour = 10, minute = 30, dayOfWeek = 3)
        assertEquals("Every Wednesday at 10:30", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `weekly schedule falls back to Monday when dayOfWeek is null`() {
        val task = createTask(scheduleType = ScheduleType.WEEKLY, hour = 7, minute = 45, dayOfWeek = null)
        assertEquals("Every Monday at 07:45", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `one_time schedule formats correctly with date`() {
        val dateMillis = LocalDate.of(2026, 3, 15)
            .atTime(10, 30)
            .atZone(ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val task = createTask(scheduleType = ScheduleType.ONE_TIME, hour = 10, minute = 30, dateMillis = dateMillis)
        assertEquals("One-time on 2026-03-15 at 10:30", ScheduleDescriptionFormatter.format(task))
    }

    @Test
    fun `one_time schedule shows unknown date when dateMillis is null`() {
        val task = createTask(scheduleType = ScheduleType.ONE_TIME, hour = 8, minute = 0, dateMillis = null)
        val description = ScheduleDescriptionFormatter.format(task)
        assertTrue(description.contains("unknown date"))
        assertTrue(description.contains("08:00"))
    }
}
