package com.oneclaw.shadow.feature.search.usecase

import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.data.local.entity.MessageEntity
import com.oneclaw.shadow.data.local.entity.SessionEntity
import com.oneclaw.shadow.feature.memory.model.MemorySearchResult
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchHistoryUseCaseTest {

    private lateinit var hybridSearchEngine: HybridSearchEngine
    private lateinit var messageDao: MessageDao
    private lateinit var sessionDao: SessionDao
    private lateinit var useCase: SearchHistoryUseCase

    private val baseTime = 1_000_000L // Small fixed epoch millis for Float precision in tests

    @BeforeEach
    fun setup() {
        hybridSearchEngine = mockk()
        messageDao = mockk()
        sessionDao = mockk()
        useCase = SearchHistoryUseCase(hybridSearchEngine, messageDao, sessionDao)

        // Default: no results from any source
        coEvery { hybridSearchEngine.search(any(), any()) } returns emptyList()
        coEvery { messageDao.searchContent(any(), any(), any(), any()) } returns emptyList()
        coEvery { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) } returns emptyList()
    }

    // --- Helpers ---

    private fun makeMemoryResult(
        id: String,
        text: String,
        score: Float,
        sourceType: String = "daily_log",
        sourceDate: String? = "2026-02-20"
    ) = MemorySearchResult(
        chunkId = id,
        chunkText = text,
        sourceType = sourceType,
        sourceDate = sourceDate,
        score = score,
        bm25Score = score,
        vectorScore = 0f,
        ageInDays = 10
    )

    private fun makeMessageEntity(
        id: String,
        content: String,
        createdAt: Long = baseTime
    ) = MessageEntity(
        id = id,
        sessionId = "sess-1",
        type = "USER",
        content = content,
        thinkingContent = null,
        toolCallId = null,
        toolName = null,
        toolInput = null,
        toolOutput = null,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = null,
        tokenCountOutput = null,
        modelId = null,
        providerId = null,
        createdAt = createdAt
    )

    private fun makeSessionEntity(
        id: String,
        title: String,
        preview: String? = null,
        updatedAt: Long = baseTime,
        createdAt: Long = baseTime
    ) = SessionEntity(
        id = id,
        title = title,
        currentAgentId = "agent-1",
        messageCount = 5,
        lastMessagePreview = preview,
        isActive = false,
        deletedAt = null,
        createdAt = createdAt,
        updatedAt = updatedAt
    )

    // --- Scope tests ---

    @Test
    fun `search allScope queries all three sources`() = runTest {
        useCase.search("test", scope = "all", dateFrom = null, dateTo = null, maxResults = 10)

        coVerify { hybridSearchEngine.search("test", topK = 50) }
        coVerify { messageDao.searchContent("test", 0L, Long.MAX_VALUE, 50) }
        coVerify { sessionDao.searchByTitleOrPreview("test", 0L, Long.MAX_VALUE, 20) }
    }

    @Test
    fun `search memoryScope only queries memory index`() = runTest {
        useCase.search("test", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        coVerify { hybridSearchEngine.search("test", topK = 50) }
        coVerify(exactly = 0) { messageDao.searchContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) }
    }

    @Test
    fun `search dailyLogScope only queries memory index`() = runTest {
        useCase.search("test", scope = "daily_log", dateFrom = null, dateTo = null, maxResults = 10)

        coVerify { hybridSearchEngine.search("test", topK = 50) }
        coVerify(exactly = 0) { messageDao.searchContent(any(), any(), any(), any()) }
        coVerify(exactly = 0) { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) }
    }

    @Test
    fun `search sessionsScope queries messages and sessions but not memory`() = runTest {
        useCase.search("test", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        coVerify(exactly = 0) { hybridSearchEngine.search(any(), any()) }
        coVerify { messageDao.searchContent("test", 0L, Long.MAX_VALUE, 50) }
        coVerify { sessionDao.searchByTitleOrPreview("test", 0L, Long.MAX_VALUE, 20) }
    }

    // --- Date filter tests ---

    @Test
    fun `search passes dateFrom and dateTo to DAOs`() = runTest {
        val from = 1_690_000_000_000L
        val to = 1_700_000_000_000L

        useCase.search("test", scope = "sessions", dateFrom = from, dateTo = to, maxResults = 10)

        coVerify { messageDao.searchContent("test", from, to, 50) }
        coVerify { sessionDao.searchByTitleOrPreview("test", from, to, 20) }
    }

    @Test
    fun `null dateFrom defaults to 0`() = runTest {
        useCase.search("test", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        coVerify { messageDao.searchContent("test", 0L, Long.MAX_VALUE, 50) }
    }

    // --- Result ordering and scoring tests ---

    @Test
    fun `results are sorted by finalScore descending`() = runTest {
        val memResults = listOf(
            makeMemoryResult("m1", "low score memory", score = 0.2f),
            makeMemoryResult("m2", "high score memory", score = 0.9f)
        )
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("memory", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        assertTrue(results.size == 2)
        assertTrue(results[0].finalScore >= results[1].finalScore)
        assertEquals("mem_m2", results[0].id)
    }

    @Test
    fun `memory results have MEMORY_WEIGHT of 1_0 applied`() = runTest {
        val memResults = listOf(makeMemoryResult("m1", "some memory text", score = 0.5f))
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("text", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        assertEquals(1, results.size)
        assertEquals(0.5f, results[0].rawScore)
        assertEquals(0.5f, results[0].finalScore) // 0.5 * 1.0
    }

    @Test
    fun `memory sourceType classified correctly for daily_log`() = runTest {
        val memResults = listOf(makeMemoryResult("m1", "log entry", score = 0.5f, sourceType = "daily_log"))
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("log", scope = "daily_log", dateFrom = null, dateTo = null, maxResults = 10)

        assertEquals(UnifiedSearchResult.SourceType.DAILY_LOG, results[0].sourceType)
    }

    @Test
    fun `memory sourceType classified correctly for long_term`() = runTest {
        val memResults = listOf(makeMemoryResult("m1", "long term memory", score = 0.5f, sourceType = "long_term", sourceDate = null))
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("memory", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        assertEquals(UnifiedSearchResult.SourceType.MEMORY, results[0].sourceType)
    }

    @Test
    fun `message results have MESSAGE_WEIGHT of 0_6 applied`() = runTest {
        // With two messages at different times, the newest gets recencyScore=1.0
        val oldTime = baseTime - 1000L
        val messages = listOf(
            makeMessageEntity("msg1", "test message content newer", createdAt = baseTime),
            makeMessageEntity("msg2", "test message content older", createdAt = oldTime)
        )
        coEvery { messageDao.searchContent(any(), any(), any(), any()) } returns messages

        val results = useCase.search("message", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        val newestMessage = results.firstOrNull { it.id == "msg_msg1" }
        assertTrue(newestMessage != null)
        // Newest message: recencyScore = 1.0 (0.1 + 0.9 * 1.0), finalScore = 1.0 * 0.6 = 0.6
        assertEquals(0.6f, newestMessage!!.finalScore, 0.001f)
    }

    @Test
    fun `session results have SESSION_WEIGHT of 0_5 applied`() = runTest {
        // With two sessions at different times, the newest gets recencyScore=1.0
        val oldTime = baseTime - 1000L
        val sessions = listOf(
            makeSessionEntity("s1", "Newest Session", updatedAt = baseTime),
            makeSessionEntity("s2", "Older Session", updatedAt = oldTime)
        )
        coEvery { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) } returns sessions

        val results = useCase.search("session", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        val newestSession = results.firstOrNull { it.id == "sess_s1" }
        assertTrue(newestSession != null)
        // Newest session: recencyScore = 1.0 (0.1 + 0.9 * 1.0), finalScore = 1.0 * 0.5 = 0.5
        assertEquals(0.5f, newestSession!!.finalScore, 0.001f)
    }

    // --- maxResults tests ---

    @Test
    fun `maxResults limits the returned results`() = runTest {
        val memResults = (1..20).map { makeMemoryResult("m$it", "memory text $it", score = it * 0.05f) }
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("memory", scope = "memory", dateFrom = null, dateTo = null, maxResults = 5)

        assertEquals(5, results.size)
    }

    // --- Empty results tests ---

    @Test
    fun `returns empty list when no sources return results`() = runTest {
        val results = useCase.search("nothing", scope = "all", dateFrom = null, dateTo = null, maxResults = 10)

        assertTrue(results.isEmpty())
    }

    // --- Deduplication tests ---

    @Test
    fun `deduplication removes highly overlapping results`() = runTest {
        val identicalText = "This is the exact same text content for deduplication testing"
        // Two memory results with identical text but different IDs
        val memResults = listOf(
            makeMemoryResult("m1", identicalText, score = 0.9f),
            makeMemoryResult("m2", identicalText, score = 0.8f)
        )
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("dedup", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        // Only one should survive deduplication (the higher-scored one)
        assertEquals(1, results.size)
        assertEquals("mem_m1", results[0].id)
    }

    @Test
    fun `deduplication keeps distinct results`() = runTest {
        val memResults = listOf(
            makeMemoryResult("m1", "about restaurants and food in Tokyo", score = 0.9f),
            makeMemoryResult("m2", "programming Kotlin Android development", score = 0.8f)
        )
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("test", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        assertEquals(2, results.size)
    }

    // --- ID prefix tests ---

    @Test
    fun `message result IDs are prefixed with msg_`() = runTest {
        val messages = listOf(makeMessageEntity("abc-123", "some content"))
        coEvery { messageDao.searchContent(any(), any(), any(), any()) } returns messages

        val results = useCase.search("content", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        assertTrue(results.any { it.id == "msg_abc-123" })
    }

    @Test
    fun `session result IDs are prefixed with sess_`() = runTest {
        val sessions = listOf(makeSessionEntity("sess-xyz", "My Session"))
        coEvery { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) } returns sessions

        val results = useCase.search("Session", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        assertTrue(results.any { it.id == "sess_sess-xyz" })
    }

    @Test
    fun `memory result IDs are prefixed with mem_`() = runTest {
        val memResults = listOf(makeMemoryResult("chunk-99", "memory text", score = 0.5f))
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("memory", scope = "memory", dateFrom = null, dateTo = null, maxResults = 10)

        assertTrue(results.any { it.id == "mem_chunk-99" })
    }

    // --- Session format tests ---

    @Test
    fun `session result text includes title and preview`() = runTest {
        val sessions = listOf(makeSessionEntity("s1", "Dinner Plans", preview = "Looking for restaurants"))
        coEvery { sessionDao.searchByTitleOrPreview(any(), any(), any(), any()) } returns sessions

        val results = useCase.search("dinner", scope = "sessions", dateFrom = null, dateTo = null, maxResults = 10)

        val sessionResult = results.firstOrNull { it.sourceType == UnifiedSearchResult.SourceType.SESSION }
        assertTrue(sessionResult != null)
        assertTrue(sessionResult!!.text.contains("Dinner Plans"))
        assertTrue(sessionResult.text.contains("restaurants"))
        assertEquals("Dinner Plans", sessionResult.sessionTitle)
    }

    // --- Daily_log scope filter test ---

    @Test
    fun `dailyLog scope filters out non-daily_log memory chunks`() = runTest {
        val memResults = listOf(
            makeMemoryResult("m1", "daily log entry", score = 0.9f, sourceType = "daily_log"),
            makeMemoryResult("m2", "long term memory", score = 0.8f, sourceType = "long_term", sourceDate = null)
        )
        coEvery { hybridSearchEngine.search(any(), any()) } returns memResults

        val results = useCase.search("entry", scope = "daily_log", dateFrom = null, dateTo = null, maxResults = 10)

        assertEquals(1, results.size)
        assertEquals(UnifiedSearchResult.SourceType.DAILY_LOG, results[0].sourceType)
    }
}
