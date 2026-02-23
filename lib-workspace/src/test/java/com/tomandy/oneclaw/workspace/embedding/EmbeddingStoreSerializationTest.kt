package com.tomandy.oneclaw.workspace.embedding

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class EmbeddingStoreSerializationTest {

    // --- floatArrayToBlob / blobToFloatArray round-trip ---

    @Test
    fun `round-trip simple float array`() {
        val original = floatArrayOf(1.0f, 2.0f, 3.0f, -1.5f, 0.0f)
        val blob = EmbeddingStore.floatArrayToBlob(original)
        val restored = EmbeddingStore.blobToFloatArray(blob)
        assertArrayEquals(original, restored, 0.0f)
    }

    @Test
    fun `round-trip empty float array`() {
        val original = FloatArray(0)
        val blob = EmbeddingStore.floatArrayToBlob(original)
        val restored = EmbeddingStore.blobToFloatArray(blob)
        assertEquals(0, restored.size)
    }

    @Test
    fun `round-trip 768-dimension embedding`() {
        val original = FloatArray(768) { i -> (i.toFloat() - 384f) / 384f }
        val blob = EmbeddingStore.floatArrayToBlob(original)
        val restored = EmbeddingStore.blobToFloatArray(blob)
        assertEquals(768, restored.size)
        assertArrayEquals(original, restored, 0.0f)
    }

    @Test
    fun `blob size is 4 bytes per float`() {
        val array = FloatArray(768)
        val blob = EmbeddingStore.floatArrayToBlob(array)
        assertEquals(768 * 4, blob.size)
    }

    @Test
    fun `round-trip preserves special float values`() {
        val original = floatArrayOf(
            Float.MAX_VALUE,
            Float.MIN_VALUE,
            Float.NEGATIVE_INFINITY,
            Float.POSITIVE_INFINITY,
            -0.0f
        )
        val blob = EmbeddingStore.floatArrayToBlob(original)
        val restored = EmbeddingStore.blobToFloatArray(blob)
        assertArrayEquals(original, restored, 0.0f)
    }

    @Test
    fun `round-trip preserves small differences`() {
        val original = floatArrayOf(0.123456789f, -0.987654321f, 0.000001f)
        val blob = EmbeddingStore.floatArrayToBlob(original)
        val restored = EmbeddingStore.blobToFloatArray(blob)
        assertArrayEquals(original, restored, 0.0f)
    }
}
