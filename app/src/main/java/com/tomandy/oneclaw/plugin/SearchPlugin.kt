package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
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
import java.time.format.DateTimeParseException

class SearchPlugin(
    private val messageDao: MessageDao,
    private val conversationDao: ConversationDao
) : Plugin {

    override suspend fun onLoad(context: PluginContext) {}

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "search_conversations" -> searchConversations(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private suspend fun searchConversations(arguments: JsonObject): ToolResult {
        val query = arguments["query"]?.jsonPrimitive?.content
            ?: return ToolResult.Failure("Missing required field: query")

        if (query.length < 2) {
            return ToolResult.Failure("Query must be at least 2 characters")
        }

        val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 20
        if (limit !in 1..100) {
            return ToolResult.Failure("limit must be between 1 and 100")
        }

        val timeFrom = arguments["time_from"]?.jsonPrimitive?.content?.let {
            parseTimestamp(it) ?: return ToolResult.Failure(
                "Invalid time_from: expected ISO 8601 (e.g. 2026-02-10T00:00:00)"
            )
        }
        val timeTo = arguments["time_to"]?.jsonPrimitive?.content?.let {
            parseTimestamp(it) ?: return ToolResult.Failure(
                "Invalid time_to: expected ISO 8601 (e.g. 2026-02-16T23:59:59)"
            )
        }

        return try {
            val matchingConversations = conversationDao.searchConversations(
                query = query, timeFrom = timeFrom, timeTo = timeTo, limit = limit
            )
            val matchingMessages = messageDao.searchMessages(
                query = query, timeFrom = timeFrom, timeTo = timeTo, limit = limit
            )

            val messagesByConversation = matchingMessages.groupBy { it.conversationId }
            val conversationIds = (
                matchingConversations.map { it.id } + messagesByConversation.keys
            ).distinct()

            if (conversationIds.isEmpty()) {
                return ToolResult.Success("No results found for \"$query\".")
            }

            val output = buildResults(
                query, conversationIds, matchingConversations, messagesByConversation
            )
            ToolResult.Success(output)
        } catch (e: Exception) {
            ToolResult.Failure("Search failed: ${e.message}", e)
        }
    }

    private suspend fun buildResults(
        query: String,
        conversationIds: List<String>,
        matchingConversations: List<ConversationEntity>,
        messagesByConversation: Map<String, List<MessageEntity>>
    ): String = buildString {
        append("Found ${conversationIds.size} conversation(s) matching \"$query\":\n\n")

        for (convId in conversationIds.take(MAX_CONVERSATIONS)) {
            val conv = conversationDao.getConversationOnce(convId) ?: continue
            val titleMatched = matchingConversations.any { it.id == convId }
            val messages = messagesByConversation[convId] ?: emptyList()

            append("## ${conv.title} (${formatDate(conv.updatedAt)})\n")
            if (titleMatched && messages.isEmpty()) {
                append("   Title matched query.\n")
            }

            for (msg in messages.take(MAX_MESSAGES_PER_CONVERSATION)) {
                val role = if (msg.role == "user") "User" else "Assistant"
                val preview = msg.content.take(SNIPPET_LENGTH).replace("\n", " ")
                val ellipsis = if (msg.content.length > SNIPPET_LENGTH) "..." else ""
                append("   - [$role, ${formatTimestamp(msg.timestamp)}]: $preview$ellipsis\n")
            }

            val remaining = messages.size - MAX_MESSAGES_PER_CONVERSATION
            if (remaining > 0) {
                append("   ($remaining more match(es) in this conversation)\n")
            }
            append("\n")
        }

        val hidden = conversationIds.size - MAX_CONVERSATIONS
        if (hidden > 0) {
            append("($hidden more conversation(s) not shown)\n")
        }
    }

    private fun parseTimestamp(value: String): Long? {
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: DateTimeParseException) {
            try {
                LocalDateTime.parse(value)
                    .atZone(ZoneId.systemDefault())
                    .toInstant()
                    .toEpochMilli()
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    private fun formatTimestamp(millis: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return dt.format(DateTimeFormatter.ofPattern("MMM d 'at' h:mm a"))
    }

    private fun formatDate(millis: Long): String {
        val dt = LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault())
        return dt.format(DateTimeFormatter.ofPattern("MMM d, yyyy"))
    }

    companion object {
        private const val MAX_CONVERSATIONS = 20
        private const val MAX_MESSAGES_PER_CONVERSATION = 5
        private const val SNIPPET_LENGTH = 200
    }
}
