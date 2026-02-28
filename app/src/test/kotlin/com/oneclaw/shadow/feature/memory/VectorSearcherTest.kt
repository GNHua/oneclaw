package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.feature.memory.search.VectorSearcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class VectorSearcherTest {

    private val searcher = VectorSearcher(io.mockk.mockk())

    @Test
    fun `cosine similarity of identical vectors is 1`() {
        val v = floatArrayOf(1f, 0f, 0f)
        val result = searcher.cosineSimilarity(v, v)
        assertEquals(1f, result, 1e-5f)
    }

    @Test
    fun `cosine similarity of orthogonal vectors is 0`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(0f, 1f, 0f)
        val result = searcher.cosineSimilarity(a, b)
        assertEquals(0f, result, 1e-5f)
    }

    @Test
    fun `cosine similarity of opposite vectors is -1`() {
        val a = floatArrayOf(1f, 0f, 0f)
        val b = floatArrayOf(-1f, 0f, 0f)
        val result = searcher.cosineSimilarity(a, b)
        assertEquals(-1f, result, 1e-5f)
    }

    @Test
    fun `cosine similarity of zero vectors returns 0`() {
        val zero = floatArrayOf(0f, 0f, 0f)
        val result = searcher.cosineSimilarity(zero, zero)
        assertEquals(0f, result, 0f)
    }

    @Test
    fun `cosine similarity is symmetric`() {
        val a = floatArrayOf(0.5f, 0.3f, 0.8f)
        val b = floatArrayOf(0.1f, 0.9f, 0.4f)
        val ab = searcher.cosineSimilarity(a, b)
        val ba = searcher.cosineSimilarity(b, a)
        assertEquals(ab, ba, 1e-6f)
    }

    @Test
    fun `cosine similarity of L2-normalized vectors stays within valid range`() {
        val a = floatArrayOf(0.577f, 0.577f, 0.577f)
        val b = floatArrayOf(0.707f, 0.707f, 0f)
        val result = searcher.cosineSimilarity(a, b)
        assertTrue(result in -1f..1f)
    }
}
