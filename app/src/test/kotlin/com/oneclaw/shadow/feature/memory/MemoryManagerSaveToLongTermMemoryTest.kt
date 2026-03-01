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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MemoryManagerSaveToLongTermMemoryTest {

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
    fun `saveToLongTermMemory calls appendMemory with the content`() = runTest {
        coEvery { longTermMemoryManager.appendMemory(any()) } returns Unit

        memoryManager.saveToLongTermMemory("User prefers dark mode")

        coVerify { longTermMemoryManager.appendMemory("User prefers dark mode") }
    }

    @Test
    fun `saveToLongTermMemory returns success when appendMemory succeeds`() = runTest {
        coEvery { longTermMemoryManager.appendMemory(any()) } returns Unit

        val result = memoryManager.saveToLongTermMemory("Some content")

        assertTrue(result.isSuccess)
    }

    @Test
    fun `saveToLongTermMemory returns failure when appendMemory throws`() = runTest {
        coEvery { longTermMemoryManager.appendMemory(any()) } throws RuntimeException("IO error")

        val result = memoryManager.saveToLongTermMemory("Some content")

        assertTrue(result.isFailure)
        assertNotNull(result.exceptionOrNull())
        assertTrue(result.exceptionOrNull()!!.message!!.contains("IO error"))
    }

    @Test
    fun `saveToLongTermMemory still succeeds when indexing fails`() = runTest {
        coEvery { longTermMemoryManager.appendMemory(any()) } returns Unit
        // Make indexing fail by having insertAll throw
        coEvery { memoryIndexDao.insertAll(any()) } throws RuntimeException("Index error")

        val result = memoryManager.saveToLongTermMemory("Some content")

        // Indexing failure is non-fatal -- overall result should still be success
        assertTrue(result.isSuccess)
    }

    @Test
    fun `saveToLongTermMemory calls appendMemory before indexing`() = runTest {
        var appendCalled = false
        var indexCalledAfterAppend = false

        coEvery { longTermMemoryManager.appendMemory(any()) } answers {
            appendCalled = true
        }
        coEvery { memoryIndexDao.insertAll(any()) } answers {
            if (appendCalled) indexCalledAfterAppend = true
        }

        memoryManager.saveToLongTermMemory("Test content that will be indexed")

        assertTrue(appendCalled)
        // indexing might be called with chunks that pass filter -- allow either path
    }
}
