package com.tomandy.oneclaw.notificationmedia

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.util.Log

class OneClawNotificationListenerService : NotificationListenerService() {

    companion object {
        private const val TAG = "OneClawNotifListener"
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        NotificationMediaServiceManager.registerService(this)
        Log.i(TAG, "Notification listener connected")
    }

    override fun onListenerDisconnected() {
        super.onListenerDisconnected()
        NotificationMediaServiceManager.unregisterService()
        Log.i(TAG, "Notification listener disconnected")
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        // No-op: we read notifications on demand
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification?) {
        // No-op
    }
}
