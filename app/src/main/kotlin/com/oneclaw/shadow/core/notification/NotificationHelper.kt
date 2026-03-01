package com.oneclaw.shadow.core.notification

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.oneclaw.shadow.MainActivity
import com.oneclaw.shadow.R
import java.util.concurrent.atomic.AtomicInteger

class NotificationHelper(
    private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "agent_tasks"
        const val CHANNEL_NAME = "Agent Tasks"
        const val SCHEDULED_TASKS_CHANNEL_ID = "scheduled_task_results"
        const val EXECUTION_CHANNEL_ID = "scheduled_task_execution"
        const val EXTRA_SESSION_ID = "notification_session_id"
        private val notificationIdCounter = AtomicInteger(1000)
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)

            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed or failed AI agent tasks"
            }
            manager.createNotificationChannel(channel)

            val scheduledChannel = NotificationChannel(
                SCHEDULED_TASKS_CHANNEL_ID,
                "Scheduled Task Results",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for scheduled task completion or failure"
            }
            manager.createNotificationChannel(scheduledChannel)

            val executionChannel = NotificationChannel(
                EXECUTION_CHANNEL_ID,
                "Scheduled Task Execution",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Ongoing notification while a scheduled task is executing"
            }
            manager.createNotificationChannel(executionChannel)
        }
    }

    fun sendTaskCompletedNotification(sessionId: String, responsePreview: String) {
        sendNotification(
            title = "Task completed",
            body = truncatePreview(responsePreview),
            sessionId = sessionId
        )
    }

    fun sendTaskFailedNotification(sessionId: String, errorMessage: String) {
        sendNotification(
            title = "Task failed",
            body = truncatePreview(errorMessage),
            sessionId = sessionId
        )
    }

    private fun sendNotification(title: String, body: String, sessionId: String) {
        val intent = Intent(context, MainActivity::class.java).apply {
            putExtra(EXTRA_SESSION_ID, sessionId)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            sessionId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationIdCounter.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+; silently ignore
        }
    }

    fun sendScheduledTaskCompletedNotification(
        taskName: String,
        sessionId: String?,
        responsePreview: String
    ) {
        sendScheduledNotification(
            title = taskName,
            body = truncatePreview(responsePreview),
            sessionId = sessionId
        )
    }

    fun sendScheduledTaskFailedNotification(
        taskName: String,
        sessionId: String?,
        errorMessage: String
    ) {
        sendScheduledNotification(
            title = "Task failed: $taskName",
            body = truncatePreview(errorMessage),
            sessionId = sessionId
        )
    }

    private fun sendScheduledNotification(title: String, body: String, sessionId: String?) {
        val intent = Intent(context, MainActivity::class.java).apply {
            if (sessionId != null) {
                putExtra(EXTRA_SESSION_ID, sessionId)
            }
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

        val pendingIntent = PendingIntent.getActivity(
            context,
            (sessionId ?: title).hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, SCHEDULED_TASKS_CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(title)
            .setContentText(body)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(notificationIdCounter.getAndIncrement(), notification)
        } catch (_: SecurityException) {
            // POST_NOTIFICATIONS permission not granted on Android 13+; silently ignore
        }
    }

    internal fun truncatePreview(text: String): String {
        val cleaned = text.trim()
        return if (cleaned.length > 100) {
            cleaned.take(100) + "..."
        } else {
            cleaned
        }
    }
}
