package com.tomandy.oneclaw.notificationmedia

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.provider.Settings
import android.service.notification.StatusBarNotification
import android.util.Log
import java.lang.ref.WeakReference

object NotificationMediaServiceManager {

    private const val TAG = "NotifMediaManager"

    private var serviceRef: WeakReference<OneClawNotificationListenerService>? = null
    private var promptCallback: NotificationListenerPromptCallback? = null

    fun registerService(service: OneClawNotificationListenerService) {
        serviceRef = WeakReference(service)
        Log.i(TAG, "Notification listener service registered")
    }

    fun unregisterService() {
        serviceRef = null
        Log.i(TAG, "Notification listener service unregistered")
    }

    fun isServiceConnected(): Boolean = serviceRef?.get() != null

    fun setPromptCallback(callback: NotificationListenerPromptCallback) {
        promptCallback = callback
    }

    fun promptEnableService() {
        promptCallback?.onNotificationListenerServiceNeeded()
    }

    fun getActiveNotifications(): List<StatusBarNotification> {
        val service = serviceRef?.get() ?: return emptyList()
        return try {
            service.activeNotifications?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active notifications", e)
            emptyList()
        }
    }

    fun dismissNotification(key: String): Boolean {
        val service = serviceRef?.get() ?: return false
        return try {
            service.cancelNotification(key)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dismiss notification: $key", e)
            false
        }
    }

    fun getActiveMediaSessions(context: Context): List<MediaController> {
        return try {
            val manager = context.getSystemService(Context.MEDIA_SESSION_SERVICE) as MediaSessionManager
            val component = ComponentName(context, OneClawNotificationListenerService::class.java)
            manager.getActiveSessions(component)
        } catch (e: SecurityException) {
            Log.e(TAG, "SecurityException getting media sessions -- listener not enabled?", e)
            emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get active media sessions", e)
            emptyList()
        }
    }

    fun openNotificationListenerSettings(context: Context) {
        val intent = Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}
