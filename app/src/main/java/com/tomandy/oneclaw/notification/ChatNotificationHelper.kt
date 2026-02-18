package com.tomandy.oneclaw.notification

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.tomandy.oneclaw.MainActivity

object ChatNotificationHelper {

    private const val CHANNEL_ID = "chat_response_channel_v2"
    private const val CHANNEL_NAME = "Chat Responses"
    private const val CHANNEL_DESCRIPTION = "Notifications for new AI responses in chat"
    const val EXTRA_CONVERSATION_ID = "extra_conversation_id"

    private const val TAG = "ChatNotification"

    fun notifyIfNeeded(
        context: Context,
        conversationId: String,
        conversationTitle: String,
        responseText: String,
        force: Boolean = false
    ) {
        val activeId = ChatScreenTracker.activeConversationId
        Log.d(TAG, "notifyIfNeeded: activeConversationId=$activeId, targetConversationId=$conversationId, force=$force")
        if (!force && activeId == conversationId) {
            Log.d(TAG, "Skipping notification: user is viewing this conversation")
            return
        }

        ensureChannel(context)

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CONVERSATION_ID, conversationId)
        }
        val pendingIntent = PendingIntent.getActivity(
            context,
            conversationId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(conversationTitle)
            .setContentText(responseText.take(100))
            .setStyle(NotificationCompat.BigTextStyle().bigText(responseText.take(500)))
            .setSmallIcon(com.tomandy.oneclaw.scheduler.R.drawable.ic_notification)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        val mgr = NotificationManagerCompat.from(context)
        Log.d(TAG, "Posting notification: enabled=${mgr.areNotificationsEnabled()}, id=${conversationId.hashCode()}")
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            mgr.notify(conversationId.hashCode(), notification)
        } else {
            Log.w(TAG, "POST_NOTIFICATIONS permission not granted, skipping notification")
        }
    }

    fun dismiss(context: Context, conversationId: String) {
        NotificationManagerCompat.from(context).cancel(conversationId.hashCode())
    }

    private fun ensureChannel(context: Context) {
        val channel = NotificationChannel(
            CHANNEL_ID,
            CHANNEL_NAME,
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = CHANNEL_DESCRIPTION
            lockscreenVisibility = NotificationCompat.VISIBILITY_PUBLIC
        }
        val mgr = context.getSystemService(NotificationManager::class.java)
        mgr.deleteNotificationChannel("chat_response_channel") // cleanup old channel
        mgr.createNotificationChannel(channel)
    }
}
