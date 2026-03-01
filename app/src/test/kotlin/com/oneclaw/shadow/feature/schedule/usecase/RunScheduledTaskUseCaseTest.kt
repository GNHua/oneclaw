package com.oneclaw.shadow.feature.schedule.usecase

import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.WorkManager
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.schedule.worker.ScheduledTaskWorker
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RunScheduledTaskUseCaseTest {

    private lateinit var repository: ScheduledTaskRepository
    private lateinit var workManager: WorkManager
    private lateinit var useCase: RunScheduledTaskUseCase

    @BeforeEach
    fun setup() {
        repository = mockk()
        workManager = mockk(relaxed = true)
        useCase = RunScheduledTaskUseCase(repository, workManager)
    }

    private fun createTask(
        id: String = "task-123",
        name: String = "Morning Briefing",
        isEnabled: Boolean = true
    ) = ScheduledTask(
        id = id,
        name = name,
        agentId = "agent-1",
        prompt = "Hello world",
        scheduleType = ScheduleType.DAILY,
        hour = 7,
        minute = 0,
        dayOfWeek = null,
        dateMillis = null,
        isEnabled = isEnabled,
        lastExecutionAt = null,
        lastExecutionStatus = null,
        lastExecutionSessionId = null,
        nextTriggerAt = null,
        createdAt = 100L,
        updatedAt = 100L
    )

    @Test
    fun `returns error when task not found`() = runTest {
        coEvery { repository.getTaskById("missing-id") } returns null

        val result = useCase("missing-id")

        assertTrue(result is AppResult.Error)
        val error = result as AppResult.Error
        assertEquals(ErrorCode.NOT_FOUND, error.code)
        assertTrue(error.message.contains("missing-id"))
    }

    @Test
    fun `enqueues work with correct task ID and manual run flag`() = runTest {
        val task = createTask()
        coEvery { repository.getTaskById("task-123") } returns task

        val nameSlot = slot<String>()
        val policySlot = slot<ExistingWorkPolicy>()
        val requestSlot = slot<OneTimeWorkRequest>()

        useCase("task-123")

        verify {
            workManager.enqueueUniqueWork(
                capture(nameSlot),
                capture(policySlot),
                capture(requestSlot)
            )
        }

        assertTrue(nameSlot.captured.startsWith("scheduled_task_manual_"))
        assertTrue(nameSlot.captured.contains("task-123"))
        assertEquals(ExistingWorkPolicy.REPLACE, policySlot.captured)

        val inputData = requestSlot.captured.workSpec.input
        assertEquals("task-123", inputData.getString(ScheduledTaskWorker.KEY_TASK_ID))
        assertEquals(true, inputData.getBoolean(ScheduledTaskWorker.KEY_MANUAL_RUN, false))
    }

    @Test
    fun `returns success with task name`() = runTest {
        val task = createTask(name = "Morning Briefing")
        coEvery { repository.getTaskById("task-123") } returns task

        val result = useCase("task-123")

        assertTrue(result is AppResult.Success)
        assertEquals("Morning Briefing", (result as AppResult.Success).data)
    }

    @Test
    fun `also runs disabled tasks`() = runTest {
        val task = createTask(isEnabled = false)
        coEvery { repository.getTaskById("task-123") } returns task

        val result = useCase("task-123")

        assertTrue(result is AppResult.Success)
        verify { workManager.enqueueUniqueWork(any(), any(), any<OneTimeWorkRequest>()) }
    }

    @Test
    fun `uses scheduled_task_manual_ prefix for work name`() = runTest {
        val task = createTask(id = "abc-xyz")
        coEvery { repository.getTaskById("abc-xyz") } returns task

        val nameSlot = slot<String>()
        useCase("abc-xyz")

        verify { workManager.enqueueUniqueWork(capture(nameSlot), any(), any<OneTimeWorkRequest>()) }
        assertEquals("scheduled_task_manual_abc-xyz", nameSlot.captured)
    }
}
