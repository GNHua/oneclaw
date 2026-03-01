package com.oneclaw.shadow.feature.schedule.usecase

import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import java.util.UUID

class RunScheduledTaskNowUseCase(
    private val scheduledTaskRepository: ScheduledTaskRepository,
    private val executionRecordRepository: TaskExecutionRecordRepository,
    private val createSessionUseCase: CreateSessionUseCase,
    private val sendMessageUseCase: SendMessageUseCase,
    private val notificationHelper: NotificationHelper
) {
    suspend operator fun invoke(taskId: String): AppResult<String> {
        // 1. Read task from DB
        val task = scheduledTaskRepository.getTaskById(taskId)
            ?: return AppResult.Error(message = "Task not found", code = ErrorCode.UNKNOWN)

        // 2. Create execution record (status = RUNNING)
        val recordId = UUID.randomUUID().toString()
        val startedAt = System.currentTimeMillis()
        executionRecordRepository.createRecord(
            TaskExecutionRecord(
                id = recordId,
                taskId = taskId,
                status = ExecutionStatus.RUNNING,
                sessionId = null,
                startedAt = startedAt,
                completedAt = null,
                errorMessage = null
            )
        )

        // 3. Create session and run agent loop
        var sessionId: String? = null
        var responseText = ""
        var isSuccess = false

        try {
            val session = createSessionUseCase(
                agentId = task.agentId,
                title = "[Run Now] ${task.name}"
            )
            sessionId = session.id

            sendMessageUseCase.execute(
                sessionId = session.id,
                userText = task.prompt,
                agentId = task.agentId
            ).collect { event ->
                when (event) {
                    is ChatEvent.StreamingText -> responseText += event.text
                    is ChatEvent.ResponseComplete -> isSuccess = true
                    is ChatEvent.Error -> {
                        responseText = event.message
                        isSuccess = false
                    }
                    else -> {}
                }
            }
        } catch (e: Exception) {
            responseText = e.message ?: "Unknown error"
            isSuccess = false
        }

        // 4. Update execution record
        val completedAt = System.currentTimeMillis()
        executionRecordRepository.updateResult(
            id = recordId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            completedAt = completedAt,
            sessionId = sessionId,
            errorMessage = if (!isSuccess) responseText else null
        )

        // 5. Update task's last execution fields (without changing scheduling lifecycle)
        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            sessionId = sessionId,
            nextTriggerAt = task.nextTriggerAt,
            isEnabled = task.isEnabled
        )

        // 6. Send notification
        if (isSuccess) {
            notificationHelper.sendScheduledTaskCompletedNotification(
                taskName = task.name,
                sessionId = sessionId,
                responsePreview = responseText
            )
        } else {
            notificationHelper.sendScheduledTaskFailedNotification(
                taskName = task.name,
                sessionId = sessionId,
                errorMessage = responseText
            )
        }

        return if (isSuccess) {
            AppResult.Success(sessionId ?: recordId)
        } else {
            AppResult.Error(message = responseText, code = ErrorCode.UNKNOWN)
        }
    }
}
