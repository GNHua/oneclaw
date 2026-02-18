package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.engine.PluginMetadata
import com.tomandy.oneclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object SearchPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "search",
            name = "Conversation Search",
            version = "1.0.0",
            description = "Search conversation history by keywords and time range",
            author = "OneClaw",
            entryPoint = "SearchPlugin",
            tools = listOf(searchConversationsTool())
        )
    }

    private fun searchConversationsTool() = ToolDefinition(
        name = "search_conversations",
        description = """Search across all conversation history by keywords.
            |
            |Searches both conversation titles and message content. Returns matching
            |conversations with message snippets containing the search terms.
            |
            |Use this tool when the user asks to:
            |- Find or recall past conversations ("what did we discuss about X?")
            |- Search for specific topics ("find the conversation about design")
            |- Remember information from previous chats ("remind me what we talked about last week")
            |
            |The search is case-insensitive and matches partial words.
            |Results are grouped by conversation with timestamps.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive("Search keywords or phrase to find in messages and conversation titles")
                    )
                }
                putJsonObject("time_from") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Optional start of time range in ISO 8601 format (e.g. 2026-02-10T00:00:00)"
                        )
                    )
                }
                putJsonObject("time_to") {
                    put("type", JsonPrimitive("string"))
                    put(
                        "description",
                        JsonPrimitive(
                            "Optional end of time range in ISO 8601 format (e.g. 2026-02-16T23:59:59)"
                        )
                    )
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put(
                        "description",
                        JsonPrimitive("Maximum number of matching messages to return (default 20, max 100)")
                    )
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    )
}
