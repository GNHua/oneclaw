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

class NotificationHelper(
    private val context: Context
) {
    companion object {
        const val CHANNEL_ID = "agent_tasks"
        const val CHANNEL_NAME = "Agent Tasks"
        const val EXTRA_SESSION_ID = "notification_session_id"
        private var notificationIdCounter = 1000
    }

    fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications for completed or failed AI agent tasks"
            }
            val manager = context.getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
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
            NotificationManagerCompat.from(context).notify(notificationIdCounter++, notification)
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
