package com.tomandy.oneclaw.smsphone

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.CallLog
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class SmsPhonePlugin : Plugin {

    private lateinit var pluginContext: PluginContext

    override suspend fun onLoad(context: PluginContext) {
        pluginContext = context
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "sms_send" -> smsSend(arguments)
            "sms_list" -> smsList(arguments)
            "sms_search" -> smsSearch(arguments)
            "sms_get_thread" -> smsGetThread(arguments)
            "phone_dial" -> phoneDial(arguments)
            "phone_call_log" -> phoneCallLog(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun smsSend(arguments: JsonObject): ToolResult {
        val to = arguments["to"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: to")
        val message = arguments["message"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: message")

        if (!hasPermission(Manifest.permission.SEND_SMS)) {
            return ToolResult.Failure(
                "SEND_SMS permission not granted. Please grant SMS permission in app settings."
            )
        }

        return try {
            val smsManager = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                pluginContext.getApplicationContext()
                    .getSystemService(SmsManager::class.java)
            } else {
                @Suppress("DEPRECATION")
                SmsManager.getDefault()
            }

            val parts = smsManager.divideMessage(message)
            if (parts.size == 1) {
                smsManager.sendTextMessage(to, null, message, null, null)
            } else {
                smsManager.sendMultipartTextMessage(to, null, parts, null, null)
            }

            ToolResult.Success("SMS sent to $to (${parts.size} part(s), ${message.length} chars)")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to send SMS: ${e.message}", e)
        }
    }

    private fun smsList(arguments: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return ToolResult.Failure(
                "READ_SMS permission not granted. Please grant SMS permission in app settings."
            )
        }

        val folder = arguments["folder"]?.jsonPrimitive?.content ?: "inbox"
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, MAX_RESULTS)

        val uri = when (folder) {
            "inbox" -> Telephony.Sms.Inbox.CONTENT_URI
            "sent" -> Telephony.Sms.Sent.CONTENT_URI
            "all" -> Telephony.Sms.CONTENT_URI
            else -> return ToolResult.Failure("Invalid folder: $folder. Use inbox, sent, or all.")
        }

        return try {
            val messages = querySms(uri, null, null, limit)
            if (messages.isEmpty()) {
                ToolResult.Success("No messages found in $folder.")
            } else {
                ToolResult.Success(
                    "Found ${messages.size} message(s) in $folder:\n\n${messages.joinToString("\n\n")}"
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to list SMS: ${e.message}", e)
        }
    }

    private fun smsSearch(arguments: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return ToolResult.Failure(
                "READ_SMS permission not granted. Please grant SMS permission in app settings."
            )
        }

        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: query")
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, MAX_RESULTS)

        return try {
            val messages = querySms(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.BODY} LIKE ?",
                arrayOf("%$query%"),
                limit
            )
            if (messages.isEmpty()) {
                ToolResult.Success("No messages matching \"$query\".")
            } else {
                ToolResult.Success(
                    "Found ${messages.size} message(s) matching \"$query\":\n\n${messages.joinToString("\n\n")}"
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to search SMS: ${e.message}", e)
        }
    }

    private fun smsGetThread(arguments: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_SMS)) {
            return ToolResult.Failure(
                "READ_SMS permission not granted. Please grant SMS permission in app settings."
            )
        }

        val address = arguments["address"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: address")
        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, MAX_RESULTS)

        return try {
            val messages = querySms(
                Telephony.Sms.CONTENT_URI,
                "${Telephony.Sms.ADDRESS} LIKE ?",
                arrayOf("%$address%"),
                limit
            )
            if (messages.isEmpty()) {
                ToolResult.Success("No messages found with $address.")
            } else {
                ToolResult.Success(
                    "Thread with $address (${messages.size} message(s)):\n\n${messages.joinToString("\n\n")}"
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to get thread: ${e.message}", e)
        }
    }

    private fun phoneDial(arguments: JsonObject): ToolResult {
        val number = arguments["number"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: number")

        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                data = Uri.parse("tel:${Uri.encode(number)}")
            }
            pluginContext.launchIntent(intent)
            ToolResult.Success("Opened dialer with number: $number")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to open dialer: ${e.message}", e)
        }
    }

    private fun phoneCallLog(arguments: JsonObject): ToolResult {
        if (!hasPermission(Manifest.permission.READ_CALL_LOG)) {
            return ToolResult.Failure(
                "READ_CALL_LOG permission not granted. Please grant Call Log permission in app settings."
            )
        }

        val limit = (arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20).coerceIn(1, MAX_RESULTS)
        val type = arguments["type"]?.jsonPrimitive?.content ?: "all"

        val selection = when (type) {
            "incoming" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.INCOMING_TYPE}"
            "outgoing" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.OUTGOING_TYPE}"
            "missed" -> "${CallLog.Calls.TYPE} = ${CallLog.Calls.MISSED_TYPE}"
            "all" -> null
            else -> return ToolResult.Failure(
                "Invalid type: $type. Use all, incoming, outgoing, or missed."
            )
        }

        return try {
            val context = pluginContext.getApplicationContext()
            val entries = mutableListOf<String>()

            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER,
                    CallLog.Calls.CACHED_NAME,
                    CallLog.Calls.TYPE,
                    CallLog.Calls.DATE,
                    CallLog.Calls.DURATION
                ),
                selection,
                null,
                "${CallLog.Calls.DATE} DESC"
            )?.use { cursor ->
                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val number = cursor.getString(0) ?: "Unknown"
                    val name = cursor.getString(1)
                    val callType = when (cursor.getInt(2)) {
                        CallLog.Calls.INCOMING_TYPE -> "Incoming"
                        CallLog.Calls.OUTGOING_TYPE -> "Outgoing"
                        CallLog.Calls.MISSED_TYPE -> "Missed"
                        CallLog.Calls.REJECTED_TYPE -> "Rejected"
                        else -> "Other"
                    }
                    val date = formatTimestamp(cursor.getLong(3))
                    val duration = cursor.getLong(4)

                    val contact = if (name != null) "$name ($number)" else number
                    entries.add("[$callType] $contact - $date (${formatDuration(duration)})")
                    count++
                }
            }

            if (entries.isEmpty()) {
                ToolResult.Success("No call log entries found.")
            } else {
                ToolResult.Success(
                    "Call log (${entries.size} entries):\n\n${entries.joinToString("\n")}"
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to read call log: ${e.message}", e)
        }
    }

    private fun querySms(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<String>?,
        limit: Int
    ): List<String> {
        val context = pluginContext.getApplicationContext()
        val messages = mutableListOf<String>()

        context.contentResolver.query(
            uri,
            arrayOf(
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE,
                Telephony.Sms.TYPE
            ),
            selection,
            selectionArgs,
            "${Telephony.Sms.DATE} DESC"
        )?.use { cursor ->
            var count = 0
            while (cursor.moveToNext() && count < limit) {
                val address = cursor.getString(0) ?: "Unknown"
                val body = cursor.getString(1) ?: ""
                val date = formatTimestamp(cursor.getLong(2))
                val direction = if (cursor.getInt(3) == Telephony.Sms.MESSAGE_TYPE_SENT) {
                    "Sent"
                } else {
                    "Received"
                }

                messages.add("[$direction] $address ($date):\n$body")
                count++
            }
        }

        return messages
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            pluginContext.getApplicationContext(),
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    private fun formatTimestamp(millis: Long): String {
        val dt = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(millis),
            ZoneId.systemDefault()
        )
        return dt.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a"))
    }

    private fun formatDuration(seconds: Long): String {
        return when {
            seconds < 60 -> "${seconds}s"
            seconds < 3600 -> "${seconds / 60}m ${seconds % 60}s"
            else -> "${seconds / 3600}h ${(seconds % 3600) / 60}m"
        }
    }

    companion object {
        private const val MAX_RESULTS = 50
    }
}
