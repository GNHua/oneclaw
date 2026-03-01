package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.feature.memory.embedding.EmbeddingSerializer
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class EmbeddingSerializerTest {

    @Test
    fun `round-trip serialization preserves values`() {
        val original = FloatArray(384) { it.toFloat() / 384f }
        val bytes = EmbeddingSerializer.toByteArray(original)
        val restored = EmbeddingSerializer.fromByteArray(bytes)
        assertArrayEquals(original, restored, 1e-6f)
    }

    @Test
    fun `byte array length is 4 times float array length`() {
        val floats = FloatArray(384) { 0.5f }
        val bytes = EmbeddingSerializer.toByteArray(floats)
        assertEquals(384 * 4, bytes.size)
    }

    @Test
    fun `zero vector round-trips correctly`() {
        val zeros = FloatArray(10) { 0f }
        val bytes = EmbeddingSerializer.toByteArray(zeros)
        val restored = EmbeddingSerializer.fromByteArray(bytes)
        assertArrayEquals(zeros, restored, 0f)
    }

    @Test
    fun `negative values round-trip correctly`() {
        val floats = floatArrayOf(-1f, -0.5f, 0f, 0.5f, 1f)
        val bytes = EmbeddingSerializer.toByteArray(floats)
        val restored = EmbeddingSerializer.fromByteArray(bytes)
        assertArrayEquals(floats, restored, 1e-6f)
    }
}
