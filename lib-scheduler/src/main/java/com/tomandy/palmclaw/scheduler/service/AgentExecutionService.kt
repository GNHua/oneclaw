package com.tomandy.palmclaw.scheduler.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.tomandy.palmclaw.scheduler.AgentExecutor
import com.tomandy.palmclaw.scheduler.CronjobManager
import com.tomandy.palmclaw.scheduler.R
import com.tomandy.palmclaw.scheduler.data.ExecutionStatus
import com.tomandy.palmclaw.scheduler.data.ScheduleType
import kotlinx.coroutines.*
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

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
class AgentExecutionService : Service(), KoinComponent {

    private val agentExecutor: AgentExecutor by inject()

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
            val result = executeAgentTask(
                cronjob.instruction,
                cronjobId,
                cronjob.conversationId
            )

            // Record successful execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.SUCCESS,
                resultSummary = result
            )

            // Send completion notification
            if (cronjob.notifyOnCompletion) {
                sendCompletionNotification(cronjob.instruction, result, cronjob.conversationId)
            }

        } catch (e: Exception) {
            // Record failed execution
            cronjobManager.recordExecutionComplete(
                logId = logId,
                status = ExecutionStatus.FAILED,
                errorMessage = e.message
            )

            // Send error notification
            sendErrorNotification(cronjob.instruction, e.message ?: "Unknown error", cronjob.conversationId)
        }

        // Disable one-time tasks after execution (keep for history)
        if (cronjob.scheduleType == ScheduleType.ONE_TIME) {
            cronjobManager.setEnabled(cronjobId, false)
        }
    }

    /**
     * Execute the agent task
     */
    private suspend fun executeAgentTask(
        instruction: String,
        cronjobId: String,
        conversationId: String?
    ): String {
        val result = agentExecutor.executeTask(
            instruction = instruction,
            cronjobId = cronjobId,
            triggerTime = System.currentTimeMillis(),
            conversationId = conversationId
        )

        return result.getOrThrow()
    }

    /**
     * Create the foreground notification
     */
    private fun createNotification(text: String) =
        NotificationCompat.Builder(this, SERVICE_CHANNEL_ID)
            .setContentTitle("PalmClaw Agent")
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_notification)
            .setOngoing(true)
            .build()
            .also { createNotificationChannels() }

    /**
     * Send completion notification
     */
    private fun sendCompletionNotification(instruction: String, result: String, conversationId: String?) {
        createNotificationChannels()

        val contentIntent = createContentIntent(conversationId)
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("Task completed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText(result))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    /**
     * Send error notification
     */
    private fun sendErrorNotification(instruction: String, error: String, conversationId: String?) {
        createNotificationChannels()

        val contentIntent = createContentIntent(conversationId)
        val notificationManager = getSystemService(NotificationManager::class.java)
        val notification = NotificationCompat.Builder(this, RESULT_CHANNEL_ID)
            .setContentTitle("Task failed")
            .setContentText(instruction.take(50))
            .setStyle(NotificationCompat.BigTextStyle().bigText("Error: $error"))
            .setSmallIcon(R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        notificationManager.notify(instruction.hashCode(), notification)
    }

    /**
     * Create notification channels
     */
    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val notificationManager = getSystemService(NotificationManager::class.java)

            // Low-importance channel for the foreground service (ongoing, silent)
            val serviceChannel = NotificationChannel(
                SERVICE_CHANNEL_ID,
                "Task Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shown while a scheduled task is running"
            }
            notificationManager.createNotificationChannel(serviceChannel)

            // High-importance channel for results (banner + lockscreen)
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
    }

    private fun createContentIntent(conversationId: String?): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName)
            ?.apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
                conversationId?.let { putExtra("extra_conversation_id", it) }
            } ?: return null

        return PendingIntent.getActivity(
            this,
            conversationId?.hashCode() ?: 0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    companion object {
        const val ACTION_EXECUTE_TASK = "com.tomandy.palmclaw.scheduler.EXECUTE_TASK"
        const val EXTRA_CRONJOB_ID = "cronjob_id"
        private const val SERVICE_CHANNEL_ID = "cronjob_service_channel"
        private const val RESULT_CHANNEL_ID = "cronjob_result_channel_v2"
        private const val NOTIFICATION_ID = 1001
    }
}
