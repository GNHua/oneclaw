package com.oneclaw.shadow.feature.memory.search

import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingSerializer
import kotlin.math.sqrt

/**
 * Vector similarity search using cosine distance.
 */
class VectorSearcher(
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * Score all chunks against the query using cosine similarity.
     * Returns a map of chunkId -> cosine similarity score.
     * Returns empty map if the embedding engine is unavailable.
     */
    suspend fun score(
        query: String,
        chunks: List<MemoryIndexEntity>
    ): Map<String, Float> {
        val queryEmbedding = embeddingEngine.embed(query) ?: return emptyMap()

        return chunks
            .filter { it.embedding != null }
            .associate { chunk ->
                val chunkEmbedding = EmbeddingSerializer.fromByteArray(chunk.embedding!!)
                chunk.id to cosineSimilarity(queryEmbedding, chunkEmbedding)
            }
    }

    internal fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
        var dot = 0f
        var normA = 0f
        var normB = 0f
        for (i in a.indices) {
            dot += a[i] * b[i]
            normA += a[i] * a[i]
            normB += b[i] * b[i]
        }
        val denom = sqrt(normA) * sqrt(normB)
        return if (denom > 0) dot / denom else 0f
    }
}
