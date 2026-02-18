package com.tomandy.palmclaw.notificationmedia

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object NotificationPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "notifications",
            name = "Notification Manager",
            version = "1.0.0",
            description = "Read, inspect, and dismiss Android notifications",
            author = "PalmClaw Team",
            entryPoint = "NotificationPlugin",
            tools = listOf(
                listNotificationsTool(),
                getNotificationDetailsTool(),
                dismissNotificationTool()
            ),
            category = "notifications"
        )
    }

    private fun listNotificationsTool() = ToolDefinition(
        name = "list_notifications",
        description = """List active notifications on the device.
            |
            |Returns notification title, text, package name, timestamp, and key for each notification.
            |Use the key to get details or dismiss a specific notification.
            |
            |Requires the PalmClaw notification listener service to be enabled in system settings.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("package_filter") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Filter notifications by package name (substring match)"))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of notifications to return (default: 50)"))
                }
            }
        }
    )

    private fun getNotificationDetailsTool() = ToolDefinition(
        name = "get_notification_details",
        description = """Get full details of a specific notification by its key.
            |
            |Returns all notification extras, available actions, category, and other metadata.
            |Use list_notifications first to find the notification key.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The notification key (from list_notifications)"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("key"))
            }
        }
    )

    private fun dismissNotificationTool() = ToolDefinition(
        name = "dismiss_notification",
        description = """Dismiss a notification by its key.
            |
            |Use list_notifications first to find the notification key.
            |Some notifications (e.g., ongoing/foreground service) cannot be dismissed.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("key") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The notification key to dismiss"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("key"))
            }
        }
    )
}
