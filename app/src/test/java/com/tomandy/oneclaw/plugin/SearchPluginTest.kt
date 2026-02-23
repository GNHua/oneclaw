package com.tomandy.oneclaw.plugin

import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.engine.ToolResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchPluginTest {

    private lateinit var mockMessageDao: MessageDao
    private lateinit var mockConversationDao: ConversationDao
    private lateinit var plugin: SearchPlugin

    private fun args(vararg pairs: Pair<String, Any>) = buildJsonObject {
        pairs.forEach { (k, v) ->
            when (v) {
                is String -> put(k, JsonPrimitive(v))
                is Int -> put(k, JsonPrimitive(v))
                else -> put(k, JsonPrimitive(v.toString()))
            }
        }
    }

    private fun conv(id: String, title: String = "Conv $id", updatedAt: Long = 1000L) =
        ConversationEntity(id = id, title = title, updatedAt = updatedAt)

    private fun msg(
        convId: String,
        content: String = "message content",
        role: String = "user",
        timestamp: Long = 1000L
    ) = MessageEntity(conversationId = convId, role = role, content = content, timestamp = timestamp)

    @Before
    fun setup() {
        mockMessageDao = mockk()
        mockConversationDao = mockk()
        plugin = SearchPlugin(mockMessageDao, mockConversationDao)
    }

    @Test
    fun `execute returns Failure for unknown tool name`() = runTest {
        val result = plugin.execute("unknown_tool", buildJsonObject {})
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `searchConversations fails when query is missing`() = runTest {
        val result = plugin.execute("search_conversations", buildJsonObject {})
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Missing required field: query"))
    }

    @Test
    fun `searchConversations fails when query is too short`() = runTest {
        val result = plugin.execute("search_conversations", args("query" to "a"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("at least 2 characters"))
    }

    @Test
    fun `searchConversations fails when limit is zero`() = runTest {
        val result = plugin.execute("search_conversations", args("query" to "test", "limit" to 0))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("between 1 and 100"))
    }

    @Test
    fun `searchConversations uses default limit of 20`() = runTest {
        coEvery { mockConversationDao.searchConversations("test", null, null, 20) } returns emptyList()
        coEvery { mockMessageDao.searchMessages("test", null, null, 20) } returns emptyList()

        plugin.execute("search_conversations", args("query" to "test"))

        coVerify { mockConversationDao.searchConversations("test", null, null, 20) }
    }

    @Test
    fun `searchConversations accepts ISO 8601 instant format for time_from`() = runTest {
        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns emptyList()

        val result = plugin.execute("search_conversations",
            args("query" to "test", "time_from" to "2026-02-10T00:00:00Z"))

        assertTrue(result is ToolResult.Success)
        coVerify {
            mockConversationDao.searchConversations("test", match { it != null && it > 0 }, null, 20)
        }
    }

    @Test
    fun `searchConversations fails with invalid time_from format`() = runTest {
        val result = plugin.execute("search_conversations",
            args("query" to "test", "time_from" to "not-a-date"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid time_from"))
    }

    @Test
    fun `searchConversations fails with invalid time_to format`() = runTest {
        val result = plugin.execute("search_conversations",
            args("query" to "test", "time_to" to "garbage"))
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Invalid time_to"))
    }

    @Test
    fun `searchConversations returns no results message when nothing found`() = runTest {
        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns emptyList()

        val result = plugin.execute("search_conversations", args("query" to "nothing"))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("No results found"))
    }

    @Test
    fun `searchConversations combines conversation title matches and message matches`() = runTest {
        val titleConv = conv("conv1", title = "test conversation")
        val msgConv = conv("conv2", title = "other conversation")

        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns listOf(titleConv)
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns listOf(
            msg("conv2", "test message content")
        )
        coEvery { mockConversationDao.getConversationOnce("conv1") } returns titleConv
        coEvery { mockConversationDao.getConversationOnce("conv2") } returns msgConv

        val result = plugin.execute("search_conversations", args("query" to "test"))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("test conversation"))
        assertTrue(output.contains("other conversation"))
        assertTrue(output.contains("2 conversation(s)"))
    }

    @Test
    fun `searchConversations deduplicates conversations matched by both title and messages`() = runTest {
        val conv1 = conv("conv1", title = "test title")

        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns listOf(conv1)
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns listOf(
            msg("conv1", "test content")
        )
        coEvery { mockConversationDao.getConversationOnce("conv1") } returns conv1

        val result = plugin.execute("search_conversations", args("query" to "test"))

        assertTrue(result is ToolResult.Success)
        val output = (result as ToolResult.Success).output
        assertTrue(output.contains("1 conversation(s)"))
    }

    @Test
    fun `searchConversations truncates message snippets at 200 chars`() = runTest {
        val longContent = "x".repeat(300)
        val conv1 = conv("conv1")

        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns listOf(
            msg("conv1", longContent)
        )
        coEvery { mockConversationDao.getConversationOnce("conv1") } returns conv1

        val result = plugin.execute("search_conversations", args("query" to "xx"))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("..."))
    }

    @Test
    fun `searchConversations limits messages per conversation to 5`() = runTest {
        val conv1 = conv("conv1")
        val messages = (1..8).map { msg("conv1", "message $it", timestamp = it.toLong()) }

        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } returns emptyList()
        coEvery { mockMessageDao.searchMessages(any(), any(), any(), any()) } returns messages
        coEvery { mockConversationDao.getConversationOnce("conv1") } returns conv1

        val result = plugin.execute("search_conversations", args("query" to "message"))

        assertTrue(result is ToolResult.Success)
        assertTrue((result as ToolResult.Success).output.contains("3 more match(es)"))
    }

    @Test
    fun `searchConversations handles DAO exception gracefully`() = runTest {
        coEvery { mockConversationDao.searchConversations(any(), any(), any(), any()) } throws
            RuntimeException("DB error")

        val result = plugin.execute("search_conversations", args("query" to "test"))

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Search failed"))
    }
}
