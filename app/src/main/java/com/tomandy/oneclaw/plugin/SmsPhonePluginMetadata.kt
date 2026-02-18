package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object SmsPhonePluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "sms-phone",
            name = "SMS & Phone",
            version = "1.0.0",
            description = "Send/read SMS messages, search texts, view call log, and dial numbers",
            author = "OneClaw",
            entryPoint = "SmsPhonePlugin",
            tools = listOf(
                smsSendTool(),
                smsListTool(),
                smsSearchTool(),
                smsGetThreadTool(),
                phoneDialTool(),
                phoneCallLogTool()
            ),
            permissions = listOf("SEND_SMS", "READ_SMS", "READ_CALL_LOG"),
            category = "phone"
        )
    }

    private fun smsSendTool() = ToolDefinition(
        name = "sms_send",
        description = """Send an SMS text message to a phone number.
            |
            |Long messages are automatically split into multi-part SMS.
            |Requires SEND_SMS permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("to") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Phone number to send the message to")
                    )
                }
                putJsonObject("message") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("The text message content")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("to"))
                add(JsonPrimitive("message"))
            }
        }
    )

    private fun smsListTool() = ToolDefinition(
        name = "sms_list",
        description = """List recent SMS messages from inbox, sent, or all folders.
            |
            |Returns messages sorted by date (most recent first).
            |Requires READ_SMS permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("folder") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("SMS folder to list: inbox (default), sent, or all")
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("inbox"))
                        add(JsonPrimitive("sent"))
                        add(JsonPrimitive("all"))
                    }
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of messages to return (default 20, max 50)")
                    )
                }
            }
        }
    )

    private fun smsSearchTool() = ToolDefinition(
        name = "sms_search",
        description = """Search SMS messages by text content.
            |
            |Case-insensitive search across all message bodies.
            |Requires READ_SMS permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Text to search for in message bodies")
                    )
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of results (default 20, max 50)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    )

    private fun smsGetThreadTool() = ToolDefinition(
        name = "sms_get_thread",
        description = """Get the SMS conversation thread with a specific contact/number.
            |
            |Returns messages exchanged with the given address, sorted by date.
            |Requires READ_SMS permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("address") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Phone number or contact name to get thread for")
                    )
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of messages (default 20, max 50)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("address"))
            }
        }
    )

    private fun phoneDialTool() = ToolDefinition(
        name = "phone_dial",
        description = """Open the phone dialer with a number pre-filled.
            |
            |This opens the dialer app but does not initiate the call.
            |The user must press the call button. No permissions required.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("number") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Phone number to dial")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("number"))
            }
        }
    )

    private fun phoneCallLogTool() = ToolDefinition(
        name = "phone_call_log",
        description = """View recent phone call history.
            |
            |Returns call log entries with number, contact name, call type,
            |date, and duration. Requires READ_CALL_LOG permission.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of entries (default 20, max 50)")
                    )
                }
                putJsonObject("type") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Filter by call type: all (default), incoming, outgoing, or missed")
                    )
                    putJsonArray("enum") {
                        add(JsonPrimitive("all"))
                        add(JsonPrimitive("incoming"))
                        add(JsonPrimitive("outgoing"))
                        add(JsonPrimitive("missed"))
                    }
                }
            }
        }
    )
}
