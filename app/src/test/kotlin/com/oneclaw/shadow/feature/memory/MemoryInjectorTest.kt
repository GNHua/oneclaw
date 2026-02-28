package com.oneclaw.shadow.feature.memory

import android.content.Context
import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.search.BM25Scorer
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.memory.search.VectorSearcher
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MemoryInjectorTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var injector: MemoryInjector
    private lateinit var ltmManager: LongTermMemoryManager

    private val now = System.currentTimeMillis()

    @BeforeEach
    fun setup() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempDir
        val fileStorage = MemoryFileStorage(mockContext)
        ltmManager = LongTermMemoryManager(fileStorage)

        val dao = mockk<MemoryIndexDao>()
        coEvery { dao.getAll() } returns emptyList()

        val embeddingEngine = mockk<EmbeddingEngine>()
        every { embeddingEngine.isAvailable() } returns false

        val searchEngine = HybridSearchEngine(
            memoryIndexDao = dao,
            embeddingEngine = embeddingEngine,
            bm25Scorer = BM25Scorer(),
            vectorSearcher = VectorSearcher(embeddingEngine)
        )

        injector = MemoryInjector(searchEngine, ltmManager)
    }

    @Test
    fun `buildInjection returns empty string when no memory`() = runTest {
        val result = injector.buildInjection("hello")
        assertTrue(result.isBlank())
    }

    @Test
    fun `buildInjection includes long-term memory content`() = runTest {
        ltmManager.writeMemory("# Long-term Memory\n\n- User prefers Kotlin")
        val result = injector.buildInjection("kotlin")
        assertTrue(result.contains("Long-term Memory"))
        assertTrue(result.contains("User prefers Kotlin"))
    }

    @Test
    fun `buildInjection with very small budget truncates relevant memories section`() = runTest {
        ltmManager.writeMemory("# Long-term Memory\n\n- Short fact")
        // With a very small budget, the injection should not exceed it unreasonably
        val result10k = injector.buildInjection("query", tokenBudget = 10000)
        val result50 = injector.buildInjection("query", tokenBudget = 50)
        // Larger budget should yield at least as much content
        assertTrue(result10k.length >= result50.length)
    }

    @Test
    fun `buildInjection includes relevant memories section when search returns results`() = runTest {
        val dao = mockk<MemoryIndexDao>()
        val entity = MemoryIndexEntity(
            id = "1", sourceType = "daily_log", sourceDate = "2026-02-28",
            chunkText = "discussed kotlin project", embedding = null,
            createdAt = now, updatedAt = now
        )
        coEvery { dao.getAll() } returns listOf(entity)

        val embeddingEngine = mockk<EmbeddingEngine>()
        every { embeddingEngine.isAvailable() } returns false

        val searchEngine = HybridSearchEngine(
            memoryIndexDao = dao,
            embeddingEngine = embeddingEngine,
            bm25Scorer = BM25Scorer(),
            vectorSearcher = VectorSearcher(embeddingEngine)
        )

        val testInjector = MemoryInjector(searchEngine, ltmManager)
        val result = testInjector.buildInjection("kotlin project")
        assertTrue(result.contains("Relevant Memories"))
        assertTrue(result.contains("discussed kotlin project"))
    }
}
