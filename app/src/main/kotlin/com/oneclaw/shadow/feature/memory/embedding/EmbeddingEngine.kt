package com.oneclaw.shadow.feature.memory.embedding

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Local embedding engine.
 * Currently operates in fallback mode (no ONNX Runtime bundled).
 * When the ONNX model file is present at getFilesDir()/models/minilm-l6-v2.onnx,
 * actual inference will be attempted via reflection to avoid a compile-time
 * dependency on the optional onnxruntime-android artifact.
 *
 * In fallback mode isAvailable() returns false, and embed() returns null.
 * The HybridSearchEngine gracefully falls back to BM25-only search in this case.
 */
class EmbeddingEngine(
    private val context: Context
) {
    private val embeddingDim = 384
    private val maxSequenceLength = 128

    // Reflective handles for optional ONNX Runtime -- null when not available
    private var ortSession: Any? = null
    private var ortEnvironment: Any? = null
    private var initialized = false

    companion object {
        private const val TAG = "EmbeddingEngine"
        private const val MODEL_FILE_NAME = "minilm-l6-v2.onnx"
    }

    /**
     * Lazily initialize the ONNX model if available.
     * Returns true if the model was loaded, false if unavailable.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext ortSession != null
        initialized = true

        val modelFile = getModelFile() ?: run {
            Log.d(TAG, "ONNX model not found -- running in BM25-only mode")
            return@withContext false
        }

        try {
            // Attempt to load ONNX Runtime via reflection (optional dependency)
            val ortEnvClass = Class.forName("ai.onnxruntime.OrtEnvironment")
            val ortSessionClass = Class.forName("ai.onnxruntime.OrtSession")
            val getEnvMethod = ortEnvClass.getMethod("getEnvironment")

            ortEnvironment = getEnvMethod.invoke(null)
            val createSessionMethod = ortEnvClass.getMethod("createSession", String::class.java)
            ortSession = createSessionMethod.invoke(ortEnvironment, modelFile.absolutePath)
            Log.i(TAG, "ONNX embedding model loaded successfully")
            true
        } catch (e: ClassNotFoundException) {
            Log.d(TAG, "ONNX Runtime not available -- running in BM25-only mode")
            false
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding model", e)
            false
        }
    }

    /**
     * Generate an embedding vector for the given text.
     * Returns null if the model is not available or inference fails.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (ortSession == null && !initialize()) return@withContext null
        if (ortSession == null) return@withContext null

        try {
            // Tokenization and inference would go here when ONNX Runtime is available.
            // Returning null causes the search engine to fall back to BM25.
            null
        } catch (e: Exception) {
            Log.e(TAG, "Embedding inference failed", e)
            null
        }
    }

    /**
     * Returns true only when the ONNX model is loaded and ready.
     */
    fun isAvailable(): Boolean = ortSession != null

    /**
     * Release model resources.
     */
    fun close() {
        try {
            ortSession?.let { session ->
                session.javaClass.getMethod("close").invoke(session)
            }
            ortEnvironment?.let { env ->
                env.javaClass.getMethod("close").invoke(env)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error closing ONNX session", e)
        }
        ortSession = null
        ortEnvironment = null
        initialized = false
    }

    private fun getModelFile(): File? {
        // Check app files directory first (user-downloaded model)
        val filesDir = File(context.filesDir, "models/$MODEL_FILE_NAME")
        if (filesDir.exists()) return filesDir

        // Check cache directory
        val cacheFile = File(context.cacheDir, "models/$MODEL_FILE_NAME")
        if (cacheFile.exists()) return cacheFile

        return null
    }
}
