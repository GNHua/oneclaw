package com.oneclaw.shadow.feature.schedule.worker

import android.content.Context
import android.content.pm.ServiceInfo
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.oneclaw.shadow.R
import com.oneclaw.shadow.core.model.ExecutionStatus
import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.TaskExecutionRecord
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.ScheduledTaskRepository
import com.oneclaw.shadow.core.repository.TaskExecutionRecordRepository
import com.oneclaw.shadow.bridge.BridgeBroadcaster
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.feature.schedule.alarm.AlarmScheduler
import com.oneclaw.shadow.feature.schedule.alarm.NextTriggerCalculator
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.util.UUID

class ScheduledTaskWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params), KoinComponent {

    private val scheduledTaskRepository: ScheduledTaskRepository by inject()
    private val executionRecordRepository: TaskExecutionRecordRepository by inject()
    private val createSessionUseCase: CreateSessionUseCase by inject()
    private val sendMessageUseCase: SendMessageUseCase by inject()
    private val notificationHelper: NotificationHelper by inject()
    private val alarmScheduler: AlarmScheduler by inject()

    companion object {
        const val KEY_TASK_ID = "task_id"
        const val KEY_MANUAL_RUN = "manual_run"
        private const val FOREGROUND_NOTIFICATION_ID = 9001
    }

    override suspend fun doWork(): Result {
        val taskId = inputData.getString(KEY_TASK_ID) ?: return Result.failure()
        val manualRun = inputData.getBoolean(KEY_MANUAL_RUN, false)

        val task = scheduledTaskRepository.getTaskById(taskId) ?: return Result.failure()

        // Skip enabled check for manual runs
        if (!manualRun && !task.isEnabled) return Result.success()

        // Show foreground notification
        setForeground(createForegroundInfo(task.name))

        // Mark as running
        scheduledTaskRepository.updateExecutionResult(
            id = taskId,
            status = ExecutionStatus.RUNNING,
            sessionId = null,
            nextTriggerAt = task.nextTriggerAt,
            isEnabled = task.isEnabled
        )

        // RFC-028: Create execution record
        val recordId = UUID.randomUUID().toString()
        executionRecordRepository.createRecord(
            TaskExecutionRecord(
                id = recordId,
                taskId = taskId,
                status = ExecutionStatus.RUNNING,
                sessionId = null,
                startedAt = System.currentTimeMillis(),
                completedAt = null,
                errorMessage = null
            )
        )

        var sessionId: String? = null
        var responseText = ""
        var isSuccess = false

        try {
            // Create session
            val session = createSessionUseCase(
                agentId = task.agentId,
                title = "[Scheduled] ${task.name}"
            )
            sessionId = session.id

            // Execute agent loop
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
                    else -> { /* ignore other events */ }
                }
            }
        } catch (e: Exception) {
            responseText = e.message ?: "Unknown error"
            isSuccess = false
        }

        if (manualRun) {
            // Manual run: update execution status only, do not change enabled/nextTriggerAt
            scheduledTaskRepository.updateExecutionResult(
                id = taskId,
                status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                sessionId = sessionId,
                nextTriggerAt = task.nextTriggerAt,
                isEnabled = task.isEnabled
            )
        } else {
            // Alarm-triggered: calculate next trigger for recurring tasks
            val isOneTime = task.scheduleType == ScheduleType.ONE_TIME
            val nextEnabled = if (isOneTime) false else task.isEnabled
            val nextTriggerAt = if (isOneTime) null else NextTriggerCalculator.calculate(task)

            scheduledTaskRepository.updateExecutionResult(
                id = taskId,
                status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
                sessionId = sessionId,
                nextTriggerAt = nextTriggerAt,
                isEnabled = nextEnabled
            )

            // Schedule next alarm for recurring tasks
            if (!isOneTime && nextTriggerAt != null) {
                val updatedTask = task.copy(nextTriggerAt = nextTriggerAt)
                alarmScheduler.scheduleTask(updatedTask)
            }
        }

        // RFC-028: Update execution record
        executionRecordRepository.updateResult(
            id = recordId,
            status = if (isSuccess) ExecutionStatus.SUCCESS else ExecutionStatus.FAILED,
            completedAt = System.currentTimeMillis(),
            sessionId = sessionId,
            errorMessage = if (!isSuccess) responseText else null
        )

        // Send notification
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

        // Broadcast result to messaging bridge (no-op if bridge is not running)
        if (isSuccess && responseText.isNotBlank()) {
            BridgeBroadcaster.broadcast("[Scheduled] ${task.name}\n\n$responseText")
        }

        return Result.success()
    }

    private fun createForegroundInfo(taskName: String): ForegroundInfo {
        val notification = NotificationCompat.Builder(
            applicationContext,
            NotificationHelper.EXECUTION_CHANNEL_ID
        )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Running scheduled task")
            .setContentText(taskName)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        return ForegroundInfo(
            FOREGROUND_NOTIFICATION_ID,
            notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
    }
}
