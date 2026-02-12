package com.tomandy.palmclaw.scheduler.worker

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.ForegroundInfo
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.R
import com.tomandy.palmclaw.scheduler.data.ExecutionStatus

/**
 * WorkManager worker that executes scheduled agent tasks
 *
 * This worker is triggered by WorkManager at the scheduled time.
 * The app can be completely killed between executions - Android will
 * start this worker when needed, with zero background battery drain.
 */
class AgentTaskWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val cronjobManager = CronjobManager(context)

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

        // Set foreground to prevent being killed during execution
        setForeground(createForegroundInfo(cronjob.instruction))

        // Record execution start
        val logId = cronjobManager.recordExecutionStart(cronjobId)

        return try {
            // TODO: Integrate with actual agent execution system
            // For now, this is a placeholder that will be replaced
            // with actual agent loop integration
            val result = executeAgentTask(cronjob.instruction)

            // Record successful execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.SUCCESS,
                resultSummary = result
            )

            // Send notification if enabled
            if (cronjob.notifyOnCompletion) {
                sendCompletionNotification(cronjob.instruction, result)
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

    /**
     * Create foreground notification to prevent worker from being killed
     */
    private fun createForegroundInfo(instruction: String): ForegroundInfo {
        createNotificationChannel()

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Executing scheduled task")
            .setContentText(instruction.take(50))
            .setSmallIcon(R.drawable.ic_notification) // TODO: Add actual icon
            .setOngoing(true)
            .build()

        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    /**
     * Send notification when task completes
     */
    private fun sendCompletionNotification(instruction: String, result: String) {
        createNotificationChannel()

        val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
            as NotificationManager

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Task completed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(R.drawable.ic_notification) // TODO: Add actual icon
            .setAutoCancel(true)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    /**
     * Create notification channel for Android O+
     */
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Scheduled Tasks",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Notifications for scheduled agent tasks"
            }

            val notificationManager = applicationContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager
            notificationManager.createNotificationChannel(channel)
        }
    }

    /**
     * Execute the agent task
     *
     * TODO: This is a placeholder. Integrate with the actual agent loop.
     * The agent should:
     * 1. Load conversation context (if any)
     * 2. Execute the instruction using the LLM
     * 3. Execute any tool calls
     * 4. Return a summary of what was done
     */
    private suspend fun executeAgentTask(instruction: String): String {
        // Placeholder implementation
        // This will be replaced with actual agent execution
        return "Task executed: $instruction (placeholder - needs agent integration)"
    }

    companion object {
        const val KEY_CRONJOB_ID = "cronjob_id"
        private const val CHANNEL_ID = "cronjob_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
