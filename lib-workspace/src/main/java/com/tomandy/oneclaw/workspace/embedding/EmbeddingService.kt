package com.tomandy.oneclaw.workspace.embedding

import com.google.genai.Client
import com.google.genai.types.EmbedContentConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible

class EmbeddingService(apiKey: String) {

    private val client: Client = Client.builder().apiKey(apiKey).build()

    suspend fun embed(texts: List<String>): List<FloatArray> = runInterruptible(Dispatchers.IO) {
        val config = EmbedContentConfig.builder()
            .taskType("RETRIEVAL_DOCUMENT")
            .outputDimensionality(DIMENSIONS)
            .build()
        val response = client.models.embedContent(MODEL, texts, config)
        response.embeddings().orElse(emptyList()).map { embedding ->
            embedding.values().orElse(emptyList()).let { values ->
                FloatArray(values.size) { i -> values[i] }
            }
        }
    }

    suspend fun embedQuery(text: String): FloatArray = runInterruptible(Dispatchers.IO) {
        val config = EmbedContentConfig.builder()
            .taskType("RETRIEVAL_QUERY")
            .outputDimensionality(DIMENSIONS)
            .build()
        val response = client.models.embedContent(MODEL, text, config)
        response.embeddings().orElse(emptyList()).first().let { embedding ->
            embedding.values().orElse(emptyList()).let { values ->
                FloatArray(values.size) { i -> values[i] }
            }
        }
    }

    companion object {
        private const val MODEL = "gemini-embedding-001"
        private const val DIMENSIONS = 768
    }
}
