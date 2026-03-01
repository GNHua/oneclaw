package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingSerializer
import com.oneclaw.shadow.feature.memory.injection.MemoryInjector
import com.oneclaw.shadow.feature.memory.log.DailyLogWriter
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.model.MemorySearchResult
import com.oneclaw.shadow.feature.memory.model.MemoryStats
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Top-level coordinator for all memory operations.
 * Entry point for trigger handlers and search requests.
 */
class MemoryManager(
    private val dailyLogWriter: DailyLogWriter,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val hybridSearchEngine: HybridSearchEngine,
    private val memoryInjector: MemoryInjector,
    private val memoryIndexDao: MemoryIndexDao,
    private val memoryFileStorage: MemoryFileStorage,
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * Flush daily log for a session.
     * Called by trigger mechanisms.
     */
    suspend fun flushDailyLog(sessionId: String): Result<Unit> {
        return dailyLogWriter.writeDailyLog(sessionId)
    }

    /**
     * Search memory for relevant content.
     */
    suspend fun searchMemory(query: String, topK: Int = 5): List<MemorySearchResult> {
        return hybridSearchEngine.search(query, topK)
    }

    /**
     * Get the injection content for the system prompt.
     */
    suspend fun getInjectionContent(query: String, tokenBudget: Int = 2000): String {
        return memoryInjector.buildInjection(query, tokenBudget)
    }

    /**
     * Save content directly to long-term memory (MEMORY.md).
     * Called by SaveMemoryTool when the AI proactively saves information.
     * Content is appended and indexed for search.
     */
    suspend fun saveToLongTermMemory(content: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            // 1. Append to MEMORY.md
            longTermMemoryManager.appendMemory(content)

            // 2. Index the new content for search (failure is non-fatal -- content is already saved)
            try {
                indexContent(content, "long_term", null)
            } catch (_: Exception) {
                // Silently ignore indexing failures
            }

            Result.success(Unit)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Rebuild the entire search index from files.
     * Used when the index is corrupted or after manual edits.
     */
    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        memoryIndexDao.deleteAll()

        // Re-index all daily logs
        for (date in memoryFileStorage.listDailyLogDates()) {
            val content = memoryFileStorage.readDailyLog(date) ?: continue
            indexContent(content, "daily_log", date)
        }

        // Re-index MEMORY.md
        val memoryContent = memoryFileStorage.readMemoryFile()
        if (memoryContent != null) {
            indexContent(memoryContent, "long_term", null)
        }
    }

    /**
     * Get memory statistics.
     */
    suspend fun getStats(): MemoryStats = withContext(Dispatchers.IO) {
        MemoryStats(
            dailyLogCount = memoryFileStorage.getDailyLogCount(),
            totalSizeBytes = memoryFileStorage.getTotalSize(),
            indexedChunkCount = memoryIndexDao.count(),
            embeddingModelLoaded = embeddingEngine.isAvailable()
        )
    }

    private suspend fun indexContent(text: String, sourceType: String, sourceDate: String?) {
        val chunks = text.split(Regex("\n{2,}"))
            .filter { it.isNotBlank() && !it.startsWith("#") && it.trim() != "---" }

        val now = System.currentTimeMillis()
        val entities = chunks.map { chunkText ->
            val embedding = embeddingEngine.embed(chunkText.trim())
            MemoryIndexEntity(
                id = UUID.randomUUID().toString(),
                sourceType = sourceType,
                sourceDate = sourceDate,
                chunkText = chunkText.trim(),
                embedding = embedding?.let { EmbeddingSerializer.toByteArray(it) },
                createdAt = now,
                updatedAt = now
            )
        }
        if (entities.isNotEmpty()) {
            memoryIndexDao.insertAll(entities)
        }
    }
}
