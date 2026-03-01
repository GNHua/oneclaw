package com.oneclaw.shadow.feature.search.usecase

import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult

/**
 * Located in: feature/search/usecase/SearchHistoryUseCase.kt
 *
 * Orchestrates searching across multiple data sources:
 * 1. Memory index via HybridSearchEngine (BM25 + vector + time decay)
 * 2. Message content via MessageDao.searchContent() (SQL LIKE)
 * 3. Session metadata via SessionDao.searchByTitleOrPreview() (SQL LIKE)
 *
 * Results are normalized, weighted by source, and merged into a single
 * ranked list of UnifiedSearchResult.
 */
class SearchHistoryUseCase(
    private val hybridSearchEngine: HybridSearchEngine,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao
) {
    companion object {
        private const val MEMORY_WEIGHT = 1.0f
        private const val MESSAGE_WEIGHT = 0.6f
        private const val SESSION_WEIGHT = 0.5f
        private const val MAX_EXCERPT_LENGTH = 500
        private const val DEDUP_OVERLAP_THRESHOLD = 0.8f
    }

    /**
     * Search across data sources based on scope.
     *
     * @param query       Search keywords
     * @param scope       Which data sources to search ("all", "memory", "daily_log", "sessions")
     * @param dateFrom    Optional start date epoch millis (inclusive)
     * @param dateTo      Optional end date epoch millis (inclusive, end of day)
     * @param maxResults  Maximum results to return
     */
    suspend fun search(
        query: String,
        scope: String,
        dateFrom: Long?,
        dateTo: Long?,
        maxResults: Int
    ): List<UnifiedSearchResult> {
        val createdAfter = dateFrom ?: 0L
        val createdBefore = dateTo ?: Long.MAX_VALUE

        val allResults = mutableListOf<UnifiedSearchResult>()

        // 1. Search memory index (if scope includes it)
        if (scope in listOf("all", "memory", "daily_log")) {
            val memoryResults = searchMemoryIndex(query, scope, createdAfter, createdBefore)
            allResults.addAll(memoryResults)
        }

        // 2. Search message content (if scope includes it)
        if (scope in listOf("all", "sessions")) {
            val messageResults = searchMessages(query, createdAfter, createdBefore)
            allResults.addAll(messageResults)
        }

        // 3. Search session metadata (if scope includes it)
        if (scope in listOf("all", "sessions")) {
            val sessionResults = searchSessions(query, createdAfter, createdBefore)
            allResults.addAll(sessionResults)
        }

        // 4. Deduplicate and rank
        return deduplicate(allResults)
            .sortedByDescending { it.finalScore }
            .take(maxResults)
    }

    private suspend fun searchMemoryIndex(
        query: String,
        scope: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        // Use a generous topK since we'll filter and re-rank
        val results = hybridSearchEngine.search(query, topK = 50)

        return results
            .filter { result ->
                // Date filtering
                val chunkDate = result.sourceDate
                if (chunkDate != null) {
                    val chunkEpoch = dateStringToEpoch(chunkDate)
                    chunkEpoch in createdAfter..createdBefore
                } else {
                    // MEMORY.md chunks have no date, include unless date filtering is strict
                    createdAfter == 0L
                }
            }
            .filter { result ->
                // Scope filtering for daily_log
                if (scope == "daily_log") {
                    result.sourceType == "daily_log"
                } else {
                    true
                }
            }
            .map { result ->
                val sourceType = if (result.sourceType == "daily_log") {
                    UnifiedSearchResult.SourceType.DAILY_LOG
                } else {
                    UnifiedSearchResult.SourceType.MEMORY
                }

                UnifiedSearchResult(
                    id = "mem_${result.chunkId}",
                    text = result.chunkText.take(MAX_EXCERPT_LENGTH),
                    sourceType = sourceType,
                    sourceDate = result.sourceDate,
                    sessionTitle = null,
                    rawScore = result.score,
                    finalScore = result.score * MEMORY_WEIGHT,
                    createdAt = result.sourceDate?.let { dateStringToEpoch(it) }
                        ?: System.currentTimeMillis()
                )
            }
    }

    private suspend fun searchMessages(
        query: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        val messages = messageDao.searchContent(
            query = query,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            limit = 50
        )

        if (messages.isEmpty()) return emptyList()

        // Score by recency (newest = 1.0, oldest = 0.1)
        val newestTime = messages.first().createdAt.toFloat()
        val oldestTime = messages.last().createdAt.toFloat()
        val timeRange = (newestTime - oldestTime).coerceAtLeast(1f)

        return messages.map { msg ->
            val recencyScore = 0.1f + 0.9f * ((msg.createdAt - oldestTime) / timeRange)

            UnifiedSearchResult(
                id = "msg_${msg.id}",
                text = msg.content.take(MAX_EXCERPT_LENGTH),
                sourceType = UnifiedSearchResult.SourceType.MESSAGE,
                sourceDate = epochToDateString(msg.createdAt),
                sessionTitle = null, // Could be enriched by joining with sessions
                rawScore = recencyScore,
                finalScore = recencyScore * MESSAGE_WEIGHT,
                createdAt = msg.createdAt
            )
        }
    }

    private suspend fun searchSessions(
        query: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        val sessions = sessionDao.searchByTitleOrPreview(
            query = query,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            limit = 20
        )

        if (sessions.isEmpty()) return emptyList()

        // Score by recency
        val newestTime = sessions.first().updatedAt.toFloat()
        val oldestTime = sessions.last().updatedAt.toFloat()
        val timeRange = (newestTime - oldestTime).coerceAtLeast(1f)

        return sessions.map { session ->
            val recencyScore = 0.1f + 0.9f * ((session.updatedAt - oldestTime) / timeRange)
            val previewText = buildString {
                append("Session: \"${session.title}\" (${session.messageCount} messages)")
                session.lastMessagePreview?.let {
                    append("\nPreview: ${it.take(200)}")
                }
            }

            UnifiedSearchResult(
                id = "sess_${session.id}",
                text = previewText.take(MAX_EXCERPT_LENGTH),
                sourceType = UnifiedSearchResult.SourceType.SESSION,
                sourceDate = epochToDateString(session.updatedAt),
                sessionTitle = session.title,
                rawScore = recencyScore,
                finalScore = recencyScore * SESSION_WEIGHT,
                createdAt = session.createdAt
            )
        }
    }

    /**
     * Remove results with > 80% text overlap, keeping the highest scored one.
     */
    private fun deduplicate(results: List<UnifiedSearchResult>): List<UnifiedSearchResult> {
        if (results.size <= 1) return results

        val sorted = results.sortedByDescending { it.finalScore }
        val kept = mutableListOf<UnifiedSearchResult>()

        for (result in sorted) {
            val isDuplicate = kept.any { existing ->
                textOverlap(existing.text, result.text) >= DEDUP_OVERLAP_THRESHOLD
            }
            if (!isDuplicate) {
                kept.add(result)
            }
        }

        return kept
    }

    /**
     * Jaccard similarity between two texts based on word sets.
     */
    private fun textOverlap(a: String, b: String): Float {
        val wordsA = a.lowercase().split("\\s+".toRegex()).toSet()
        val wordsB = b.lowercase().split("\\s+".toRegex()).toSet()
        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1f
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun dateStringToEpoch(dateStr: String): Long {
        return try {
            java.time.LocalDate.parse(dateStr)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    private fun epochToDateString(epochMillis: Long): String {
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
