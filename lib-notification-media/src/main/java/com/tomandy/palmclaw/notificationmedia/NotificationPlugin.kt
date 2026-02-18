package com.tomandy.palmclaw.notificationmedia

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class NotificationPlugin : Plugin {

    private lateinit var context: PluginContext

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        if (!NotificationMediaServiceManager.isServiceConnected()) {
            NotificationMediaServiceManager.promptEnableService()
            return ToolResult.Failure(
                "Notification listener service is not enabled. " +
                    "The user must enable PalmClaw in Settings > Notifications > Notification access before notification tools can be used."
            )
        }

        return when (toolName) {
            "list_notifications" -> listNotifications(arguments)
            "get_notification_details" -> getNotificationDetails(arguments)
            "dismiss_notification" -> dismissNotification(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun listNotifications(arguments: JsonObject): ToolResult {
        val packageFilter = arguments["package_filter"]?.jsonPrimitive?.content
        val limit = arguments["limit"]?.jsonPrimitive?.int ?: 50
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        var notifications = NotificationMediaServiceManager.getActiveNotifications()

        if (packageFilter != null) {
            notifications = notifications.filter { it.packageName.contains(packageFilter, ignoreCase = true) }
        }

        notifications = notifications.take(limit)

        if (notifications.isEmpty()) {
            return ToolResult.Success("No active notifications found.")
        }

        val output = buildString {
            appendLine("Active notifications (${notifications.size}):")
            appendLine()
            for (sbn in notifications) {
                val extras = sbn.notification.extras
                val title = extras.getCharSequence("android.title")?.toString() ?: ""
                val text = extras.getCharSequence("android.text")?.toString() ?: ""
                val time = dateFormat.format(Date(sbn.postTime))

                appendLine("- Key: ${sbn.key}")
                appendLine("  Package: ${sbn.packageName}")
                if (title.isNotEmpty()) appendLine("  Title: $title")
                if (text.isNotEmpty()) appendLine("  Text: $text")
                appendLine("  Time: $time")
                if (sbn.isOngoing) appendLine("  Ongoing: true")
                appendLine()
            }
        }

        return ToolResult.Success(output.trim())
    }

    private fun getNotificationDetails(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: key")

        val notifications = NotificationMediaServiceManager.getActiveNotifications()
        val sbn = notifications.find { it.key == key }
            ?: return ToolResult.Failure("Notification with key '$key' not found. It may have been dismissed.")

        val notification = sbn.notification
        val extras = notification.extras
        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

        val output = buildString {
            appendLine("Notification details:")
            appendLine("  Key: ${sbn.key}")
            appendLine("  Package: ${sbn.packageName}")
            appendLine("  Post time: ${dateFormat.format(Date(sbn.postTime))}")
            appendLine("  Ongoing: ${sbn.isOngoing}")
            appendLine("  Category: ${notification.category ?: "none"}")

            val title = extras.getCharSequence("android.title")?.toString()
            val text = extras.getCharSequence("android.text")?.toString()
            val bigText = extras.getCharSequence("android.bigText")?.toString()
            val subText = extras.getCharSequence("android.subText")?.toString()
            val infoText = extras.getCharSequence("android.infoText")?.toString()
            val summaryText = extras.getCharSequence("android.summaryText")?.toString()

            appendLine()
            appendLine("  Content:")
            if (title != null) appendLine("    Title: $title")
            if (text != null) appendLine("    Text: $text")
            if (bigText != null && bigText != text) appendLine("    Big text: $bigText")
            if (subText != null) appendLine("    Sub text: $subText")
            if (infoText != null) appendLine("    Info: $infoText")
            if (summaryText != null) appendLine("    Summary: $summaryText")

            val actions = notification.actions
            if (actions != null && actions.isNotEmpty()) {
                appendLine()
                appendLine("  Actions:")
                for (action in actions) {
                    appendLine("    - ${action.title}")
                }
            }
        }

        return ToolResult.Success(output.trim())
    }

    private fun dismissNotification(arguments: JsonObject): ToolResult {
        val key = arguments["key"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: key")

        val success = NotificationMediaServiceManager.dismissNotification(key)
        return if (success) {
            ToolResult.Success("Notification dismissed.")
        } else {
            ToolResult.Failure("Failed to dismiss notification. The notification listener service may not be connected.")
        }
    }
}
