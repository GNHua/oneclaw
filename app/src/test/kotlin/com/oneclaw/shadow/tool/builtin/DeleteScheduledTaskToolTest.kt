package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.feature.schedule.usecase.DeleteScheduledTaskUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class DeleteScheduledTaskToolTest {

    private lateinit var repository: ScheduledTaskRepository
    private lateinit var deleteScheduledTaskUseCase: DeleteScheduledTaskUseCase
    private lateinit var tool: DeleteScheduledTaskTool

    @BeforeEach
    fun setup() {
        repository = mockk()
        deleteScheduledTaskUseCase = mockk(relaxed = true)
        tool = DeleteScheduledTaskTool(repository, deleteScheduledTaskUseCase)
    }

    private fun createTask(
        id: String = "task-1",
        name: String = "Morning Briefing"
    ) = ScheduledTask(
        id = id,
        name = name,
        agentId = "agent-1",
        prompt = "Good morning",
        scheduleType = ScheduleType.DAILY,
        hour = 7,
        minute = 0,
        dayOfWeek = null,
        dateMillis = null,
        isEnabled = true,
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
    fun `valid task ID calls delete use case`() = runTest {
        val task = createTask()
        coEvery { repository.getTaskById("task-1") } returns task

        tool.execute(mapOf("task_id" to "task-1"))

        coVerify { deleteScheduledTaskUseCase("task-1") }
    }

    @Test
    fun `valid task ID returns success message with task name`() = runTest {
        val task = createTask(name = "Morning Briefing")
        coEvery { repository.getTaskById("task-1") } returns task

        val result = tool.execute(mapOf("task_id" to "task-1"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Morning Briefing"))
        assertTrue(result.result!!.contains("deleted"))
    }

    @Test
    fun `success message mentions alarm cancellation`() = runTest {
        val task = createTask(name = "Weekly Summary")
        coEvery { repository.getTaskById("task-2") } returns task

        val result = tool.execute(mapOf("task_id" to "task-2"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("alarm"))
    }
}
