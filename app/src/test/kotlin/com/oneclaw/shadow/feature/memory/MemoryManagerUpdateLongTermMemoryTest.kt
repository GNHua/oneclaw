package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.feature.memory.log.DailyLogWriter
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MemoryManagerUpdateLongTermMemoryTest {

    private lateinit var dailyLogWriter: DailyLogWriter
    private lateinit var longTermMemoryManager: LongTermMemoryManager
    private lateinit var hybridSearchEngine: HybridSearchEngine
    private lateinit var memoryInjector: MemoryInjector
    private lateinit var memoryIndexDao: MemoryIndexDao
    private lateinit var memoryFileStorage: MemoryFileStorage
    private lateinit var embeddingEngine: EmbeddingEngine
    private lateinit var memoryManager: MemoryManager

    @BeforeEach
    fun setup() {
        dailyLogWriter = mockk(relaxed = true)
        longTermMemoryManager = mockk(relaxed = true)
        hybridSearchEngine = mockk(relaxed = true)
        memoryInjector = mockk(relaxed = true)
        memoryIndexDao = mockk(relaxed = true)
        memoryFileStorage = mockk(relaxed = true)
        embeddingEngine = mockk(relaxed = true)

        every { embeddingEngine.isAvailable() } returns false
        coEvery { embeddingEngine.embed(any()) } returns null
        coEvery { memoryIndexDao.insertAll(any()) } returns Unit
        coEvery { memoryIndexDao.count() } returns 0
        coEvery { memoryIndexDao.deleteAll() } returns Unit
        coEvery { memoryFileStorage.listDailyLogDates() } returns emptyList()
        coEvery { memoryFileStorage.readMemoryFile() } returns null

        memoryManager = MemoryManager(
            dailyLogWriter = dailyLogWriter,
            longTermMemoryManager = longTermMemoryManager,
            hybridSearchEngine = hybridSearchEngine,
            memoryInjector = memoryInjector,
            memoryIndexDao = memoryIndexDao,
            memoryFileStorage = memoryFileStorage,
            embeddingEngine = embeddingEngine
        )
    }

    @Test
    fun `updateLongTermMemory triggers rebuildIndex on successful update`() = runTest {
        coEvery { longTermMemoryManager.replaceMemoryEntry(any(), any()) } returns 1

        memoryManager.updateLongTermMemory("old text", "new text")

        coVerify { memoryIndexDao.deleteAll() }
    }

    @Test
    fun `updateLongTermMemory does not rebuild index when no match`() = runTest {
        coEvery { longTermMemoryManager.replaceMemoryEntry(any(), any()) } returns 0

        memoryManager.updateLongTermMemory("missing text", "replacement")

        coVerify(exactly = 0) { memoryIndexDao.deleteAll() }
    }

    @Test
    fun `updateLongTermMemory returns failure when replaceMemoryEntry throws`() = runTest {
        coEvery { longTermMemoryManager.replaceMemoryEntry(any(), any()) } throws RuntimeException("IO error")

        val result = memoryManager.updateLongTermMemory("old", "new")

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertEquals("IO error", result.exceptionOrNull()!!.message)
    }

    @Test
    fun `updateLongTermMemory indexing failure does not cause overall failure`() = runTest {
        coEvery { longTermMemoryManager.replaceMemoryEntry(any(), any()) } returns 1
        coEvery { memoryIndexDao.deleteAll() } throws RuntimeException("Index error")

        val result = memoryManager.updateLongTermMemory("old text", "new text")

        assertTrue(result.isSuccess)
        assertEquals(1, result.getOrNull())
    }
}
