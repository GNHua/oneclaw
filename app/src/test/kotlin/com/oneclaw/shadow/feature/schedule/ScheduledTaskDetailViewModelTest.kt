package com.oneclaw.shadow.feature.schedule

import androidx.lifecycle.SavedStateHandle
import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.feature.schedule.usecase.DeleteScheduledTaskUseCase
import com.oneclaw.shadow.feature.schedule.usecase.RunScheduledTaskNowUseCase
import com.oneclaw.shadow.feature.schedule.usecase.ToggleScheduledTaskUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ScheduledTaskDetailViewModelTest {

    private lateinit var scheduledTaskRepository: ScheduledTaskRepository
    private lateinit var executionRecordRepository: TaskExecutionRecordRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var toggleUseCase: ToggleScheduledTaskUseCase
    private lateinit var deleteUseCase: DeleteScheduledTaskUseCase
    private lateinit var runNowUseCase: RunScheduledTaskNowUseCase

    private val testDispatcher = StandardTestDispatcher()

    private val testTask = ScheduledTask(
        id = "task-1",
        name = "Morning Briefing",
        agentId = "agent-1",
        prompt = "Give me a briefing",
        scheduleType = ScheduleType.DAILY,
        hour = 7,
        minute = 0,
        dayOfWeek = null,
        dateMillis = null,
        isEnabled = true,
        lastExecutionAt = 1000L,
        lastExecutionStatus = ExecutionStatus.SUCCESS,
        lastExecutionSessionId = "session-last",
        nextTriggerAt = 9999L,
        createdAt = 500L,
        updatedAt = 600L
    )

    private val testAgent = Agent(
        id = "agent-1",
        name = "General Assistant",
        description = "A general assistant",
        systemPrompt = "",
        preferredProviderId = null,
        preferredModelId = null,
        isBuiltIn = true,
        createdAt = 0L,
        updatedAt = 0L
    )

    private val testRecords = listOf(
        TaskExecutionRecord(
            id = "record-1",
            taskId = "task-1",
            status = ExecutionStatus.SUCCESS,
            sessionId = "session-1",
            startedAt = 1000L,
            completedAt = 2000L,
            errorMessage = null
        )
    )

    @BeforeEach
    fun setup() {
        Dispatchers.setMain(testDispatcher)
        scheduledTaskRepository = mockk(relaxed = true)
        executionRecordRepository = mockk(relaxed = true)
        agentRepository = mockk(relaxed = true)
        toggleUseCase = mockk(relaxed = true)
        deleteUseCase = mockk(relaxed = true)
        runNowUseCase = mockk(relaxed = true)

        coEvery { scheduledTaskRepository.getTaskById("task-1") } returns testTask
        coEvery { agentRepository.getAgentById("agent-1") } returns testAgent
        every { executionRecordRepository.getRecordsByTaskId("task-1") } returns flowOf(testRecords)
    }

    @AfterEach
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun createViewModel(): ScheduledTaskDetailViewModel {
        val savedStateHandle = SavedStateHandle(mapOf("taskId" to "task-1"))
        return ScheduledTaskDetailViewModel(
            savedStateHandle = savedStateHandle,
            scheduledTaskRepository = scheduledTaskRepository,
            executionRecordRepository = executionRecordRepository,
            agentRepository = agentRepository,
            toggleUseCase = toggleUseCase,
            deleteUseCase = deleteUseCase,
            runNowUseCase = runNowUseCase
        )
    }

    @Test
    fun `init loads task and agent name`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(testTask, state.task)
        assertEquals("General Assistant", state.agentName)
    }

    @Test
    fun `init loads execution history`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.executionHistory.size)
        assertEquals("record-1", state.executionHistory[0].id)
    }

    @Test
    fun `toggleEnabled calls toggleUseCase and reloads task`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.toggleEnabled(false)
        advanceUntilIdle()

        coVerify { toggleUseCase("task-1", false) }
        coVerify(atLeast = 2) { scheduledTaskRepository.getTaskById("task-1") }
    }

    @Test
    fun `deleteTask calls deleteUseCase and sets isDeleted`() = runTest {
        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.deleteTask()
        advanceUntilIdle()

        coVerify { deleteUseCase("task-1") }
        assertTrue(viewModel.uiState.value.isDeleted)
    }

    @Test
    fun `runNow sets isRunningNow during execution`() = runTest {
        coEvery { runNowUseCase("task-1") } coAnswers {
            // delay to allow capturing running state in a real test; here we just return
            AppResult.Success("session-new")
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runNow()
        // Before advancing, isRunningNow should be true
        assertTrue(viewModel.uiState.value.isRunningNow)

        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isRunningNow)
    }

    @Test
    fun `runNow success clears error and reloads task`() = runTest {
        coEvery { runNowUseCase("task-1") } returns AppResult.Success("session-new")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runNow()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRunningNow)
        assertNull(state.errorMessage)
    }

    @Test
    fun `runNow failure sets errorMessage`() = runTest {
        coEvery { runNowUseCase("task-1") } returns AppResult.Error(message = "API key missing")

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runNow()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isRunningNow)
        assertEquals("API key missing", state.errorMessage)
    }

    @Test
    fun `runNow is ignored when already running`() = runTest {
        var callCount = 0
        coEvery { runNowUseCase("task-1") } coAnswers {
            callCount++
            AppResult.Success("session-new")
        }

        val viewModel = createViewModel()
        advanceUntilIdle()

        viewModel.runNow()
        viewModel.runNow() // second call should be ignored
        advanceUntilIdle()

        assertEquals(1, callCount)
    }

    @Test
    fun `unknown taskId results in null task`() = runTest {
        coEvery { scheduledTaskRepository.getTaskById("task-1") } returns null
        every { executionRecordRepository.getRecordsByTaskId("task-1") } returns flowOf(emptyList())

        val viewModel = createViewModel()
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertNull(state.task)
        assertEquals("", state.agentName)
    }
}
