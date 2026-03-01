package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.schedule.usecase.CreateScheduledTaskUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class CreateScheduledTaskToolTest {

    private lateinit var createScheduledTaskUseCase: CreateScheduledTaskUseCase
    private lateinit var tool: CreateScheduledTaskTool

    @BeforeEach
    fun setup() {
        createScheduledTaskUseCase = mockk()
        tool = CreateScheduledTaskTool(createScheduledTaskUseCase)
    }

    private fun baseParams(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val defaults: Map<String, Any?> = mapOf(
            "name" to "Morning Briefing",
            "prompt" to "Give me a morning news summary",
            "schedule_type" to "daily",
            "hour" to 7,
            "minute" to 0
        )
        return defaults + overrides
    }

    private fun createdTask(
        name: String = "Morning Briefing",
        scheduleType: ScheduleType = ScheduleType.DAILY,
        hour: Int = 7,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        dateMillis: Long? = null,
        nextTriggerAt: Long? = 1740700000000L
    ) = ScheduledTask(
        id = "task-123",
        name = name,
        agentId = "agent-general-assistant",
        prompt = "Give me a morning news summary",
        scheduleType = scheduleType,
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        dateMillis = dateMillis,
        isEnabled = true,
        lastExecutionAt = null,
        lastExecutionStatus = null,
        lastExecutionSessionId = null,
        nextTriggerAt = nextTriggerAt,
        createdAt = 1000L,
        updatedAt = 1000L
    )

    @Test
    fun `missing name param returns error`() = runTest {
        val result = tool.execute(baseParams(mapOf("name" to null)))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("name"))
    }

    @Test
    fun `missing prompt param returns error`() = runTest {
        val result = tool.execute(baseParams(mapOf("prompt" to null)))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("prompt"))
    }

    @Test
    fun `missing schedule_type param returns error`() = runTest {
        val result = tool.execute(baseParams(mapOf("schedule_type" to null)))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("schedule_type"))
    }

    @Test
    fun `invalid schedule_type returns error`() = runTest {
        val result = tool.execute(baseParams(mapOf("schedule_type" to "monthly")))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("monthly"))
    }

    @Test
    fun `missing hour param returns error`() = runTest {
        val result = tool.execute(baseParams(mapOf("hour" to null)))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("hour"))
    }

    @Test
    fun `successful daily task creation returns success message`() = runTest {
        val task = createdTask()
        coEvery { createScheduledTaskUseCase(any()) } returns AppResult.Success(task)

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Morning Briefing"))
        assertTrue(result.result!!.contains("Daily at 07:00"))
    }

    @Test
    fun `successful weekly task creation parses day_of_week correctly`() = runTest {
        val task = createdTask(
            scheduleType = ScheduleType.WEEKLY,
            hour = 9,
            minute = 0,
            dayOfWeek = 1
        )
        coEvery { createScheduledTaskUseCase(any()) } returns AppResult.Success(task)

        val params = baseParams(mapOf(
            "schedule_type" to "weekly",
            "hour" to 9,
            "minute" to 0,
            "day_of_week" to "monday"
        ))
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Monday"))
        assertTrue(result.result!!.contains("09:00"))
    }

    @Test
    fun `successful one_time task creation parses date correctly`() = runTest {
        val dateMillis = java.time.LocalDate.parse("2026-03-15")
            .atTime(10, 30)
            .atZone(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val task = createdTask(
            scheduleType = ScheduleType.ONE_TIME,
            hour = 10,
            minute = 30,
            dateMillis = dateMillis,
            nextTriggerAt = dateMillis
        )
        coEvery { createScheduledTaskUseCase(any()) } returns AppResult.Success(task)

        val params = baseParams(mapOf(
            "schedule_type" to "one_time",
            "hour" to 10,
            "minute" to 30,
            "date" to "2026-03-15"
        ))
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("2026-03-15"))
        assertTrue(result.result!!.contains("10:30"))
    }

    @Test
    fun `invalid date format returns error`() = runTest {
        val params = baseParams(mapOf(
            "schedule_type" to "one_time",
            "date" to "15-03-2026"
        ))
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("15-03-2026"))
    }

    @Test
    fun `invalid day_of_week returns error`() = runTest {
        val params = baseParams(mapOf(
            "schedule_type" to "weekly",
            "day_of_week" to "funday"
        ))
        val result = tool.execute(params)

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("funday"))
    }

    @Test
    fun `useCase returning AppResult Error propagates as ToolResult error`() = runTest {
        coEvery { createScheduledTaskUseCase(any()) } returns AppResult.Error(
            message = "Scheduled time is in the past.",
            code = ErrorCode.VALIDATION_ERROR
        )

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("creation_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("past"))
    }
}
