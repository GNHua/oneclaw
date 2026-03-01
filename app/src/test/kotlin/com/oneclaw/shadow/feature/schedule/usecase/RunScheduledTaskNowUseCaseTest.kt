package com.oneclaw.shadow.feature.schedule.usecase

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class RunScheduledTaskNowUseCaseTest {

    private lateinit var scheduledTaskRepository: ScheduledTaskRepository
    private lateinit var executionRecordRepository: TaskExecutionRecordRepository
    private lateinit var createSessionUseCase: CreateSessionUseCase
    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var useCase: RunScheduledTaskNowUseCase

    private val responseCompleteMessage = Message(
        id = "msg-1",
        sessionId = "session-new",
        type = MessageType.AI_RESPONSE,
        content = "Here is your briefing.",
        thinkingContent = null,
        toolCallId = null,
        toolName = null,
        toolInput = null,
        toolOutput = null,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = null,
        tokenCountOutput = null,
        modelId = null,
        providerId = null,
        createdAt = 0L
    )

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
        lastExecutionAt = null,
        lastExecutionStatus = null,
        lastExecutionSessionId = null,
        nextTriggerAt = 9999L,
        createdAt = 500L,
        updatedAt = 600L
    )

    private val testSession = Session(
        id = "session-new",
        title = "[Run Now] Morning Briefing",
        currentAgentId = "agent-1",
        messageCount = 0,
        lastMessagePreview = null,
        isActive = false,
        deletedAt = null,
        createdAt = 0L,
        updatedAt = 0L
    )

    @BeforeEach
    fun setup() {
        scheduledTaskRepository = mockk(relaxed = true)
        executionRecordRepository = mockk(relaxed = true)
        createSessionUseCase = mockk(relaxed = true)
        sendMessageUseCase = mockk(relaxed = true)
        notificationHelper = mockk(relaxed = true)

        useCase = RunScheduledTaskNowUseCase(
            scheduledTaskRepository = scheduledTaskRepository,
            executionRecordRepository = executionRecordRepository,
            createSessionUseCase = createSessionUseCase,
            sendMessageUseCase = sendMessageUseCase,
            notificationHelper = notificationHelper
        )

        coEvery { scheduledTaskRepository.getTaskById("task-1") } returns testTask
        coEvery { createSessionUseCase(agentId = "agent-1", title = "[Run Now] Morning Briefing") } returns testSession
        every {
            sendMessageUseCase.execute(
                sessionId = any(),
                userText = any(),
                agentId = any(),
                pendingMessages = any()
            )
        } returns flowOf(ChatEvent.ResponseComplete(responseCompleteMessage, null))
    }

    @Test
    fun `returns error when task not found`() = runTest {
        coEvery { scheduledTaskRepository.getTaskById("nonexistent") } returns null

        val result = useCase("nonexistent")

        assertTrue(result is AppResult.Error)
        assertEquals("Task not found", (result as AppResult.Error).message)
    }

    @Test
    fun `creates execution record with RUNNING status at start`() = runTest {
        val recordSlot = slot<TaskExecutionRecord>()
        coEvery { executionRecordRepository.createRecord(capture(recordSlot)) } returns Unit

        useCase("task-1")

        coVerify { executionRecordRepository.createRecord(any()) }
        assertEquals(ExecutionStatus.RUNNING, recordSlot.captured.status)
        assertEquals("task-1", recordSlot.captured.taskId)
        assertTrue(recordSlot.captured.startedAt > 0)
    }

    @Test
    fun `creates session with correct title`() = runTest {
        useCase("task-1")

        coVerify { createSessionUseCase(agentId = "agent-1", title = "[Run Now] Morning Briefing") }
    }

    @Test
    fun `updates record with SUCCESS on completion`() = runTest {
        useCase("task-1")

        coVerify {
            executionRecordRepository.updateResult(
                id = any(),
                status = ExecutionStatus.SUCCESS,
                completedAt = any(),
                sessionId = "session-new",
                errorMessage = null
            )
        }
    }

    @Test
    fun `updates record with FAILED on exception`() = runTest {
        every {
            sendMessageUseCase.execute(
                sessionId = any(),
                userText = any(),
                agentId = any(),
                pendingMessages = any()
            )
        } throws RuntimeException("Network failure")

        useCase("task-1")

        coVerify {
            executionRecordRepository.updateResult(
                id = any(),
                status = ExecutionStatus.FAILED,
                completedAt = any(),
                sessionId = any(),
                errorMessage = "Network failure"
            )
        }
    }

    @Test
    fun `updates record with FAILED on ChatEvent Error`() = runTest {
        every {
            sendMessageUseCase.execute(
                sessionId = any(),
                userText = any(),
                agentId = any(),
                pendingMessages = any()
            )
        } returns flowOf(ChatEvent.Error("API error", ErrorCode.UNKNOWN, false))

        useCase("task-1")

        coVerify {
            executionRecordRepository.updateResult(
                id = any(),
                status = ExecutionStatus.FAILED,
                completedAt = any(),
                sessionId = any(),
                errorMessage = "API error"
            )
        }
    }

    @Test
    fun `does NOT change task nextTriggerAt or isEnabled on success`() = runTest {
        useCase("task-1")

        coVerify {
            scheduledTaskRepository.updateExecutionResult(
                id = "task-1",
                status = ExecutionStatus.SUCCESS,
                sessionId = any(),
                nextTriggerAt = 9999L,  // unchanged from original task
                isEnabled = true         // unchanged
            )
        }
    }

    @Test
    fun `sends completed notification on success`() = runTest {
        useCase("task-1")

        coVerify {
            notificationHelper.sendScheduledTaskCompletedNotification(
                taskName = "Morning Briefing",
                sessionId = any(),
                responsePreview = any()
            )
        }
    }

    @Test
    fun `sends failed notification on failure`() = runTest {
        every {
            sendMessageUseCase.execute(
                sessionId = any(),
                userText = any(),
                agentId = any(),
                pendingMessages = any()
            )
        } returns flowOf(ChatEvent.Error("Timeout", ErrorCode.TIMEOUT_ERROR, false))

        useCase("task-1")

        coVerify {
            notificationHelper.sendScheduledTaskFailedNotification(
                taskName = "Morning Briefing",
                sessionId = any(),
                errorMessage = any()
            )
        }
    }

    @Test
    fun `returns Success with sessionId on success`() = runTest {
        val result = useCase("task-1")

        assertTrue(result is AppResult.Success)
        assertEquals("session-new", (result as AppResult.Success).data)
    }

    @Test
    fun `returns Error on failure`() = runTest {
        every {
            sendMessageUseCase.execute(
                sessionId = any(),
                userText = any(),
                agentId = any(),
                pendingMessages = any()
            )
        } returns flowOf(ChatEvent.Error("Failed to connect", ErrorCode.NETWORK_ERROR, false))

        val result = useCase("task-1")

        assertTrue(result is AppResult.Error)
    }
}
