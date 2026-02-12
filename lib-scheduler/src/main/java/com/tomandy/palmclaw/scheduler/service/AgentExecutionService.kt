package com.tomandy.palmclaw.scheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.R
import com.tomandy.palmclaw.scheduler.data.ExecutionStatus
import kotlinx.coroutines.*

/**
 * Foreground service that executes scheduled agent tasks
 *
 * This service is started by CronjobAlarmReceiver when an alarm fires.
 * It runs as a foreground service to:
 * 1. Comply with Android 8+ background execution limits
 * 2. Prevent the process from being killed during task execution
 * 3. Allow execution time beyond BroadcastReceiver's 10-second limit
 *
 * Once the task completes, the service stops itself, returning to
 * zero background battery drain.
 */
class AgentExecutionService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private lateinit var cronjobManager: CronjobManager

    override fun onCreate() {
        super.onCreate()
        cronjobManager = CronjobManager(applicationContext)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_EXECUTE_TASK) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        val cronjobId = intent.getStringExtra(EXTRA_CRONJOB_ID)
        if (cronjobId == null) {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Start as foreground service immediately
        startForeground(NOTIFICATION_ID, createNotification("Executing scheduled task..."))

        // Execute the task asynchronously
        serviceScope.launch {
            try {
                executeTask(cronjobId)
            } finally {
                // Stop the service when done
                stopSelf(startId)
            }
        }

        // Don't restart if killed
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Execute the cronjob task
     */
    private suspend fun executeTask(cronjobId: String) {
        // Get the cronjob
        val cronjob = cronjobManager.getById(cronjobId)
        if (cronjob == null || !cronjob.enabled) {
            return
        }

        // Update notification with task details
        val notificationManager = getSystemService(NotificationManager::class.java)
        notificationManager.notify(
            NOTIFICATION_ID,
            createNotification(cronjob.instruction.take(50))
        )

        // Record execution start
        val logId = cronjobManager.recordExecutionStart(cronjobId)

        try {
            // Execute the agent task
            val result = executeAgentTask(cronjob.instruction, cronjobId)

            // Record successful execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.SUCCESS,
                resultSummary = result
            )

            // Send completion notification
            if (cronjob.notifyOnCompletion) {
                sendCompletionNotification(cronjob.instruction, result)
            }

        } catch (e: Exception) {
            // Record failed execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.FAILED,
                errorMessage = e.message
            )

            // Send error notification
            sendErrorNotification(cronjob.instruction, e.message ?: "Unknown error")
        }
    }

    /**
     * Execute the agent task
     */
    private suspend fun executeAgentTask(instruction: String, cronjobId: String): String {
        val executor = AgentExecutor.instance
            ?: return "Error: Agent executor not configured"

        val result = executor.executeTask(
            instruction = instruction,
            cronjobId = cronjobId,
            triggerTime = System.currentTimeMillis()
        )

        return result.getOrThrow()
    }

    /**
     * Create the foreground notification
     */
    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PalmClaw Agent")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification) // TODO: Add actual icon
            .setOngoing(true)
            .build()
            .also { createNotificationChannel() }

    /**
     * Send completion notification
     */
    private fun sendCompletionNotification(instruction: String, result: String) {
        createNotificationChannel()

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Task completed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    /**
     * Send error notification
     */
    private fun sendErrorNotification(instruction: String, error: String) {
        createNotificationChannel()

        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Task failed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText("Error: $error"))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    /**
     * Create notification channel
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

            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.tomandy.palmclaw.scheduler.EXECUTE_TASK"
        const val EXTRA_CRONJOB_ID = "cronjob_id"
        private const val CHANNEL_ID = "cronjob_channel"
        private const val NOTIFICATION_ID = 1001
    }
}
