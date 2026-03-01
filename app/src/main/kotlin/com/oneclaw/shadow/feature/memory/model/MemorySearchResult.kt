package com.oneclaw.shadow.feature.memory.model

/**
 * A single search result from the hybrid memory search.
 */
data class MemorySearchResult(
    val chunkId: String,
    val chunkText: String,
    val sourceType: String,           // "daily_log" or "long_term"
    val sourceDate: String?,          // Date for daily log entries
    val score: Float,                 // Final combined score (0.0 - 1.0)
    val bm25Score: Float,             // Raw BM25 component
    val vectorScore: Float,           // Raw vector similarity component
    val ageInDays: Int                // Age for display purposes
)

/**
 * Memory statistics data class.
 */
data class MemoryStats(
    val dailyLogCount: Int,
    val totalSizeBytes: Long,
    val indexedChunkCount: Int,
    val embeddingModelLoaded: Boolean
)
