package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.search.BM25Scorer
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.memory.search.VectorSearcher
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HybridSearchEngineTest {

    private val now = System.currentTimeMillis()

    private fun makeEntity(id: String, text: String): MemoryIndexEntity = MemoryIndexEntity(
        id = id, sourceType = "daily_log", sourceDate = "2026-02-28",
        chunkText = text, embedding = null, createdAt = now, updatedAt = now
    )

    private fun engine(chunks: List<MemoryIndexEntity>, vectorAvailable: Boolean = false): HybridSearchEngine {
        val dao = mockk<MemoryIndexDao>()
        coEvery { dao.getAll() } returns chunks

        val embeddingEngine = mockk<EmbeddingEngine>()
        every { embeddingEngine.isAvailable() } returns vectorAvailable

        return HybridSearchEngine(
            memoryIndexDao = dao,
            embeddingEngine = embeddingEngine,
            bm25Scorer = BM25Scorer(),
            vectorSearcher = VectorSearcher(embeddingEngine)
        )
    }

    @Test
    fun `search returns empty list when index is empty`() = runTest {
        val e = engine(emptyList())
        val results = e.search("anything")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search with BM25 only ranks relevant chunk higher`() = runTest {
        val chunks = listOf(
            makeEntity("1", "Kotlin coroutines and flows are used extensively"),
            makeEntity("2", "The weather today is sunny")
        )
        val e = engine(chunks, vectorAvailable = false)
        val results = e.search("Kotlin coroutines", topK = 2)
        assertTrue(results.isNotEmpty())
        assertEquals("1", results.first().chunkId)
    }

    @Test
    fun `search respects topK parameter`() = runTest {
        val chunks = (1..10).map { makeEntity(it.toString(), "keyword $it") }
        val e = engine(chunks, vectorAvailable = false)
        val results = e.search("keyword", topK = 3)
        assertTrue(results.size <= 3)
    }

    @Test
    fun `search returns only chunks with positive score`() = runTest {
        val chunks = listOf(
            makeEntity("1", "completely irrelevant text"),
            makeEntity("2", "another irrelevant chunk")
        )
        val e = engine(chunks, vectorAvailable = false)
        // A query that matches no terms results in zero BM25 scores
        val results = e.search("zzz_no_match_zzz")
        assertTrue(results.isEmpty())
    }

    @Test
    fun `search results are sorted by descending score`() = runTest {
        val chunks = listOf(
            makeEntity("1", "relevant topic mentioned once"),
            makeEntity("2", "relevant relevant relevant topic"),
            makeEntity("3", "something else entirely")
        )
        val e = engine(chunks, vectorAvailable = false)
        val results = e.search("relevant topic", topK = 5)
        val scores = results.map { it.score }
        assertEquals(scores, scores.sortedDescending())
    }
}
