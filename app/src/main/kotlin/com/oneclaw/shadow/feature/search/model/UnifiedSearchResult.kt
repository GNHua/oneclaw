package com.oneclaw.shadow.feature.search.model

/**
 * Located in: feature/search/model/UnifiedSearchResult.kt
 *
 * A single search result from the unified multi-source search.
 * Results from different data sources (memory index, messages, sessions)
 * are normalized into this common format for ranking and display.
 */
data class UnifiedSearchResult(
    val id: String,
    val text: String,                // Excerpt of the matching content (max 500 chars)
    val sourceType: SourceType,      // Which data source this came from
    val sourceDate: String?,         // Date associated with this result (YYYY-MM-DD)
    val sessionTitle: String?,       // Session title (for message/session results)
    val rawScore: Float,             // Score from the source search (before normalization)
    val finalScore: Float,           // Final score after normalization, weighting, time decay
    val createdAt: Long              // Epoch millis of the original content
) {
    enum class SourceType(val label: String) {
        MEMORY("memory"),
        DAILY_LOG("daily_log"),
        MESSAGE("message"),
        SESSION("session")
    }
}
