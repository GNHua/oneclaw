package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.schedule.usecase.UpdateScheduledTaskUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class UpdateScheduledTaskToolTest {

    private lateinit var repository: ScheduledTaskRepository
    private lateinit var updateScheduledTaskUseCase: UpdateScheduledTaskUseCase
    private lateinit var tool: UpdateScheduledTaskTool

    @BeforeEach
    fun setup() {
        repository = mockk()
        updateScheduledTaskUseCase = mockk()
        tool = UpdateScheduledTaskTool(repository, updateScheduledTaskUseCase)
    }

    private fun createTask(
        id: String = "task-1",
        name: String = "Morning Briefing",
        prompt: String = "Good morning",
        scheduleType: ScheduleType = ScheduleType.DAILY,
        hour: Int = 7,
        minute: Int = 0,
        dayOfWeek: Int? = null,
        dateMillis: Long? = null,
        isEnabled: Boolean = true
    ) = ScheduledTask(
        id = id,
        name = name,
        agentId = "agent-1",
        prompt = prompt,
        scheduleType = scheduleType,
        hour = hour,
        minute = minute,
        dayOfWeek = dayOfWeek,
        dateMillis = dateMillis,
        isEnabled = isEnabled,
        lastExecutionAt = null,
        lastExecutionStatus = null,
        lastExecutionSessionId = null,
        nextTriggerAt = null,
        createdAt = 100L,
        updatedAt = 100L
    )

    @Test
    fun `missing task_id returns validation error`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("task_id"))
    }

    @Test
    fun `null task_id returns validation error`() = runTest {
        val result = tool.execute(mapOf("task_id" to null))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `nonexistent task ID returns not-found error`() = runTest {
        coEvery { repository.getTaskById("unknown-id") } returns null

        val result = tool.execute(mapOf("task_id" to "unknown-id"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("unknown-id"))
    }

    @Test
    fun `partial update only changes provided fields`() = runTest {
        val existingTask = createTask(hour = 7, minute = 0)
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val updatedSlot = slot<ScheduledTask>()
        coEvery { updateScheduledTaskUseCase(capture(updatedSlot)) } returns AppResult.Success(Unit)

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "hour" to 8
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val updated = updatedSlot.captured
        assertEquals(8, updated.hour)
        assertEquals(0, updated.minute)  // unchanged
        assertEquals("Morning Briefing", updated.name)  // unchanged
        assertEquals("Good morning", updated.prompt)    // unchanged
    }

    @Test
    fun `can update name only`() = runTest {
        val existingTask = createTask()
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val updatedSlot = slot<ScheduledTask>()
        coEvery { updateScheduledTaskUseCase(capture(updatedSlot)) } returns AppResult.Success(Unit)

        tool.execute(mapOf(
            "task_id" to "task-1",
            "name" to "Evening Summary"
        ))

        assertEquals("Evening Summary", updatedSlot.captured.name)
        assertEquals("Good morning", updatedSlot.captured.prompt)  // unchanged
    }

    @Test
    fun `invalid schedule_type returns validation error`() = runTest {
        val existingTask = createTask()
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "schedule_type" to "monthly"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("monthly"))
    }

    @Test
    fun `invalid day_of_week returns validation error`() = runTest {
        val existingTask = createTask()
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "day_of_week" to "funday"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("funday"))
    }

    @Test
    fun `switching to weekly without day_of_week when existing dayOfWeek is null returns error`() = runTest {
        val existingTask = createTask(scheduleType = ScheduleType.DAILY, dayOfWeek = null)
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "schedule_type" to "weekly"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("day_of_week"))
    }

    @Test
    fun `can disable task via enabled=false`() = runTest {
        val existingTask = createTask(isEnabled = true)
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val updatedSlot = slot<ScheduledTask>()
        coEvery { updateScheduledTaskUseCase(capture(updatedSlot)) } returns AppResult.Success(Unit)

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "enabled" to false
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(false, updatedSlot.captured.isEnabled)
    }

    @Test
    fun `use case error propagates as update_failed`() = runTest {
        val existingTask = createTask()
        coEvery { repository.getTaskById("task-1") } returns existingTask
        coEvery { updateScheduledTaskUseCase(any()) } returns AppResult.Error(
            message = "Task name is required.",
            code = ErrorCode.VALIDATION_ERROR
        )

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "name" to "   "
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("update_failed", result.errorType)
    }

    @Test
    fun `invalid date format returns validation error`() = runTest {
        val existingTask = createTask(scheduleType = ScheduleType.ONE_TIME)
        coEvery { repository.getTaskById("task-1") } returns existingTask

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "date" to "15-03-2026"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("15-03-2026"))
    }

    @Test
    fun `success result includes changed fields summary`() = runTest {
        val existingTask = createTask(hour = 7)
        coEvery { repository.getTaskById("task-1") } returns existingTask
        coEvery { updateScheduledTaskUseCase(any()) } returns AppResult.Success(Unit)

        val result = tool.execute(mapOf(
            "task_id" to "task-1",
            "hour" to 9
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("hour"))
    }
}
