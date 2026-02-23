package com.tomandy.oneclaw.workspace.embedding

import com.tomandy.oneclaw.workspace.MemoryPlugin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CosineSimilarityTest {

    @Test
    fun `identical vectors have similarity 1`() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        assertEquals(1.0f, MemoryPlugin.cosineSimilarity(a, a), 1e-6f)
    }

    @Test
    fun `opposite vectors have similarity -1`() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(-1.0f, -2.0f, -3.0f)
        assertEquals(-1.0f, MemoryPlugin.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `orthogonal vectors have similarity 0`() {
        val a = floatArrayOf(1.0f, 0.0f)
        val b = floatArrayOf(0.0f, 1.0f)
        assertEquals(0.0f, MemoryPlugin.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `empty arrays return 0`() {
        assertEquals(0.0f, MemoryPlugin.cosineSimilarity(FloatArray(0), FloatArray(0)), 0.0f)
    }

    @Test
    fun `different sized arrays return 0`() {
        val a = floatArrayOf(1.0f, 2.0f)
        val b = floatArrayOf(1.0f, 2.0f, 3.0f)
        assertEquals(0.0f, MemoryPlugin.cosineSimilarity(a, b), 0.0f)
    }

    @Test
    fun `zero vector returns 0`() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(0.0f, 0.0f, 0.0f)
        assertEquals(0.0f, MemoryPlugin.cosineSimilarity(a, b), 0.0f)
    }

    @Test
    fun `similar vectors have high similarity`() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(1.1f, 2.1f, 3.1f)
        val sim = MemoryPlugin.cosineSimilarity(a, b)
        assertTrue("Expected high similarity, got $sim", sim > 0.99f)
    }

    @Test
    fun `scaling does not affect similarity`() {
        val a = floatArrayOf(1.0f, 2.0f, 3.0f)
        val b = floatArrayOf(2.0f, 4.0f, 6.0f) // a * 2
        assertEquals(1.0f, MemoryPlugin.cosineSimilarity(a, b), 1e-6f)
    }

    @Test
    fun `works with 768-dimension vectors`() {
        val a = FloatArray(768) { i -> (i.toFloat() / 768f) }
        val b = FloatArray(768) { i -> ((768 - i).toFloat() / 768f) }
        val sim = MemoryPlugin.cosineSimilarity(a, b)
        assertTrue("Similarity should be between -1 and 1", sim in -1.0f..1.0f)
    }

    @Test
    fun `result is between -1 and 1 for random-like vectors`() {
        val a = FloatArray(100) { i -> if (i % 2 == 0) 0.5f else -0.3f }
        val b = FloatArray(100) { i -> if (i % 3 == 0) 0.7f else -0.1f }
        val sim = MemoryPlugin.cosineSimilarity(a, b)
        assertTrue("Expected range [-1, 1], got $sim", sim >= -1.0f && sim <= 1.0f)
    }
}
