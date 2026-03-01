package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.schedule.usecase.RunScheduledTaskUseCase
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RunScheduledTaskToolTest {

    private lateinit var runScheduledTaskUseCase: RunScheduledTaskUseCase
    private lateinit var tool: RunScheduledTaskTool

    @BeforeEach
    fun setup() {
        runScheduledTaskUseCase = mockk()
        tool = RunScheduledTaskTool(runScheduledTaskUseCase)
    }

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
        coEvery { runScheduledTaskUseCase("unknown-id") } returns AppResult.Error(
            message = "Task not found with ID 'unknown-id'.",
            code = ErrorCode.NOT_FOUND
        )

        val result = tool.execute(mapOf("task_id" to "unknown-id"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("run_failed", result.errorType)
        assertTrue(result.errorMessage!!.contains("unknown-id"))
    }

    @Test
    fun `valid task ID returns success message with task name`() = runTest {
        coEvery { runScheduledTaskUseCase("task-123") } returns AppResult.Success("Morning Briefing")

        val result = tool.execute(mapOf("task_id" to "task-123"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Morning Briefing"))
        assertTrue(result.result!!.contains("queued"))
    }

    @Test
    fun `success message includes notification info`() = runTest {
        coEvery { runScheduledTaskUseCase("task-abc") } returns AppResult.Success("Weekly Summary")

        val result = tool.execute(mapOf("task_id" to "task-abc"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("notification"))
    }
}
