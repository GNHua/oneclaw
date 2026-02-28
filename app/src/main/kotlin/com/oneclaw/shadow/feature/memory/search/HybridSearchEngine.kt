package com.oneclaw.shadow.feature.memory.search

import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.model.MemorySearchResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Hybrid search engine combining BM25 and vector search with time decay.
 *
 * Score formula:
 *   combined = BM25_WEIGHT * bm25 + VECTOR_WEIGHT * vector
 *   final    = combined * timeDecay
 *
 * When the embedding engine is not available, falls back to BM25-only search
 * with weight = 1.0.
 */
class HybridSearchEngine(
    private val memoryIndexDao: MemoryIndexDao,
    private val embeddingEngine: EmbeddingEngine,
    private val bm25Scorer: BM25Scorer = BM25Scorer(),
    private val vectorSearcher: VectorSearcher = VectorSearcher(embeddingEngine)
) {
    companion object {
        const val BM25_WEIGHT = 0.3f
        const val VECTOR_WEIGHT = 0.7f
        const val DEFAULT_TOP_K = 5
    }

    /**
     * Perform hybrid search over the memory index.
     *
     * 1. Load all indexed chunks from Room
     * 2. Compute BM25 scores
     * 3. Compute vector similarity scores (if embedding engine available)
     * 4. Normalize each score set to [0, 1]
     * 5. Combine: score = BM25_WEIGHT * bm25 + VECTOR_WEIGHT * vector
     * 6. Apply time decay: finalScore = score * timeDecay
     * 7. Return top-K results
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val chunks = memoryIndexDao.getAll()
        if (chunks.isEmpty()) return@withContext emptyList()

        // BM25 scores
        val bm25Scores = bm25Scorer.score(query, chunks)

        // Vector scores (may be empty if embedding engine unavailable)
        val vectorScores = if (embeddingEngine.isAvailable()) {
            vectorSearcher.score(query, chunks)
        } else {
            emptyMap()
        }

        // Normalize scores to [0, 1]
        val normalizedBm25 = normalize(bm25Scores)
        val normalizedVector = normalize(vectorScores)

        // Determine effective weights
        val effectiveBm25Weight: Float
        val effectiveVectorWeight: Float
        if (vectorScores.isEmpty()) {
            // Fallback: BM25 only
            effectiveBm25Weight = 1.0f
            effectiveVectorWeight = 0.0f
        } else {
            effectiveBm25Weight = BM25_WEIGHT
            effectiveVectorWeight = VECTOR_WEIGHT
        }

        // Combine and rank
        chunks.map { chunk ->
            val bm25 = normalizedBm25[chunk.id] ?: 0f
            val vector = normalizedVector[chunk.id] ?: 0f
            val combinedScore = effectiveBm25Weight * bm25 + effectiveVectorWeight * vector
            val timeDecay = TimeDecayCalculator.calculate(chunk.createdAt)
            val finalScore = combinedScore * timeDecay
            val ageInDays = ((System.currentTimeMillis() - chunk.createdAt) /
                (1000L * 60 * 60 * 24)).toInt()

            MemorySearchResult(
                chunkId = chunk.id,
                chunkText = chunk.chunkText,
                sourceType = chunk.sourceType,
                sourceDate = chunk.sourceDate,
                score = finalScore,
                bm25Score = bm25,
                vectorScore = vector,
                ageInDays = ageInDays
            )
        }
            .filter { it.score > 0f }
            .sortedByDescending { it.score }
            .take(topK)
    }

    private fun normalize(scores: Map<String, Float>): Map<String, Float> {
        if (scores.isEmpty()) return scores
        val maxScore = scores.values.maxOrNull() ?: return scores
        if (maxScore == 0f) return scores
        return scores.mapValues { it.value / maxScore }
    }
}
