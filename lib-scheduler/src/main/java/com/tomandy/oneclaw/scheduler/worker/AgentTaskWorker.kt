package com.tomandy.oneclaw.scheduler.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import com.tomandy.oneclaw.scheduler.AgentExecutor
import com.tomandy.oneclaw.scheduler.CronjobManager
import com.tomandy.oneclaw.scheduler.R
import com.tomandy.oneclaw.scheduler.TaskExecutionResult
import com.tomandy.oneclaw.scheduler.data.ExecutionStatus

class AgentTaskWorker(
    context: Context,
    params: WorkerParameters,
    private val agentExecutor: AgentExecutor,
    private val cronjobManager: CronjobManager
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val cronjobId = inputData.getString(KEY_CRONJOB_ID)
            ?: return Result.failure()

        // Get the cronjob configuration
        val cronjob = cronjobManager.getById(cronjobId)
            ?: return Result.failure()

        // Check if still enabled
        if (!cronjob.enabled) {
            return Result.success()
        }

        // Use title if available, otherwise fall back to instruction
        val displayName = cronjob.title.ifBlank { cronjob.instruction }

        // Set foreground to prevent being killed during execution
        setForeground(createForegroundInfo(displayName))

        // Record execution start
        val logId = cronjobManager.recordExecutionStart(cronjobId)

        return try {
            val taskResult = executeAgentTask(cronjob.instruction)

            // Record successful execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.SUCCESS,
                resultSummary = taskResult.summary,
                conversationId = taskResult.conversationId
            )

            // Send notification if enabled
            if (cronjob.notifyOnCompletion) {
                sendCompletionNotification(displayName, taskResult.summary, cronjob.conversationId)
            }

            Result.success()
        } catch (e: Exception) {
            // Record failed execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.FAILED,
                errorMessage = e.message
            )

            // Retry with exponential backoff
            Result.retry()
        }
    }

    private fun createForegroundInfo(instruction: String): ForegroundInfo {
        createNotificationChannels()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Executing scheduled task")
            .setContentText(instruction.take(50))
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    private fun sendCompletionNotification(instruction: String, result: String, conversationId: String?) {
        createNotificationChannels()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        val notification = NotificationCompat.Builder(applicationContext, RESULT_CHANNEL_ID)
            .setContentTitle("Task completed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(createContentIntent(conversationId))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    private fun createNotificationChannels() {
        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        val serviceChannel = NotificationChannel(
            CHANNEL_ID,
            "Scheduled Tasks",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Notifications for scheduled agent tasks"
        }
        notificationManager.createNotificationChannel(serviceChannel)

        val resultChannel = NotificationChannel(
            RESULT_CHANNEL_ID,
            "Task Results",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Notifications when scheduled tasks complete or fail"
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        notificationManager.createNotificationChannel(resultChannel)

        // Cleanup old channel
        notificationManager.deleteNotificationChannel("cronjob_result_channel")
    }

    private suspend fun executeAgentTask(instruction: String): TaskExecutionResult {
        val result = agentExecutor.executeTask(
            instruction = instruction,
            cronjobId = inputData.getString(KEY_CRONJOB_ID) ?: "",
            triggerTime = System.currentTimeMillis()
        )

        return result.getOrElse { e ->
            throw e // Re-throw to trigger retry
        }
    }

    private fun createContentIntent(conversationId: String?): PendingIntent? {
        val intent = applicationContext.packageManager.getLaunchIntentForPackage(applicationContext.packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                conversationId?.let { putExtra("extra_conversation_id", it) }
            } ?: return null

        return PendingIntent.getActivity(
            applicationContext,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val KEY_CRONJOB_ID = "cronjob_id"
        private const val CHANNEL_ID = "cronjob_channel"
        private const val RESULT_CHANNEL_ID = "cronjob_result_channel_v2"
        private const val NOTIFICATION_ID = 1001
    }
}
