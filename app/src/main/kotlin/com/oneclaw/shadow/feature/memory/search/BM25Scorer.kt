package com.oneclaw.shadow.feature.memory.search

import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import kotlin.math.ln

/**
 * BM25 keyword scoring for memory search.
 */
class BM25Scorer {
    private val k1 = 1.2f   // Term frequency saturation
    private val b = 0.75f   // Length normalization

    /**
     * Score all chunks against the query using BM25.
     * Returns a map of chunkId -> BM25 score.
     */
    fun score(
        query: String,
        chunks: List<MemoryIndexEntity>
    ): Map<String, Float> {
        if (chunks.isEmpty()) return emptyMap()

        val queryTerms = tokenize(query)
        if (queryTerms.isEmpty()) return chunks.associate { it.id to 0f }

        val tokenizedChunks = chunks.map { it to tokenize(it.chunkText) }
        val avgDocLength = tokenizedChunks.map { it.second.size }.average().toFloat()
        val n = chunks.size.toFloat()

        // IDF for each query term
        val idf = mutableMapOf<String, Float>()
        for (term in queryTerms) {
            val docFreq = tokenizedChunks.count { (_, terms) -> terms.contains(term) }
            idf[term] = ln((n - docFreq + 0.5f) / (docFreq + 0.5f) + 1.0f)
        }

        return tokenizedChunks.associate { (chunk, docTerms) ->
            val docLength = docTerms.size.toFloat()
            val termFreqs = docTerms.groupingBy { it }.eachCount()

            var bm25Score = 0f
            for (term in queryTerms) {
                val tf = termFreqs[term] ?: 0
                val termIdf = idf[term] ?: 0f
                if (tf == 0) continue
                val numerator = tf * (k1 + 1)
                val denominator = tf + k1 * (1 - b + b * docLength / avgDocLength)
                bm25Score += termIdf * numerator / denominator
            }
            chunk.id to bm25Score
        }
    }

    internal fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\u4e00-\u9fff\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
