package com.oneclaw.shadow.feature.memory.embedding

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Utility for serializing/deserializing embedding vectors.
 * Stores 384-float vectors as little-endian byte arrays.
 */
object EmbeddingSerializer {
    fun toByteArray(embedding: FloatArray): ByteArray {
        val buffer = ByteBuffer.allocate(embedding.size * 4)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        embedding.forEach { buffer.putFloat(it) }
        return buffer.array()
    }

    fun fromByteArray(bytes: ByteArray): FloatArray {
        val buffer = ByteBuffer.wrap(bytes)
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(bytes.size / 4) { buffer.getFloat() }
    }
}
