package com.tomandy.oneclaw.workspace

import android.util.Log
import com.tomandy.oneclaw.engine.Plugin
import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import com.tomandy.oneclaw.workspace.embedding.EmbeddingService
import com.tomandy.oneclaw.workspace.embedding.EmbeddingStore
import com.tomandy.oneclaw.workspace.embedding.MemoryIndexer
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.math.sqrt

class MemoryPlugin : Plugin, WorkspaceWriteListener {

    private lateinit var workspaceRoot: File
    private lateinit var memoryDir: File
    private lateinit var context: PluginContext

    private var embeddingStore: EmbeddingStore? = null
    private var embeddingService: EmbeddingService? = null
    private var memoryIndexer: MemoryIndexer? = null

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }
        memoryDir = File(workspaceRoot, "memory").also { it.mkdirs() }

        initEmbeddings()
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "search_memory" -> searchMemory(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {
        embeddingStore?.close()
    }

    override suspend fun onFileWritten(relativePath: String) {
        val indexer = memoryIndexer ?: return
        try {
            val file = File(workspaceRoot, relativePath)
            indexer.syncFile(relativePath, file)
            Log.d(TAG, "Indexed memory file: $relativePath")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to index $relativePath: ${e.message}")
        }
    }

    private suspend fun initEmbeddings() {
        try {
            val apiKey = context.getProviderCredential("Google Gemini")
            if (apiKey.isNullOrBlank()) {
                Log.d(TAG, "No Gemini API key found, semantic search disabled")
                return
            }
            val dbDir = File(workspaceRoot, ".embeddings")
            embeddingStore = EmbeddingStore(context.getApplicationContext(), dbDir)
            embeddingService = EmbeddingService(apiKey)
            memoryIndexer = MemoryIndexer(embeddingStore!!, embeddingService!!, workspaceRoot)
            Log.d(TAG, "Semantic search initialized")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embeddings: ${e.message}")
            embeddingStore = null
            embeddingService = null
            memoryIndexer = null
        }
    }

    private suspend fun searchMemory(arguments: JsonObject): ToolResult {
        return try {
            val query = arguments["query"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: query")
            val mode = arguments["mode"]?.jsonPrimitive?.content ?: "auto"

            val keywordResults = if (mode != "semantic") keywordSearch(query) else emptyList()
            val semanticResults = if (mode != "keyword") semanticSearch(query) else emptyList()

            val combined = mergeResults(keywordResults, semanticResults)

            if (combined.isEmpty()) {
                ToolResult.Success(output = "No results found for: $query")
            } else {
                val output = combined.joinToString("\n---\n")
                ToolResult.Success(
                    output = "${combined.size} result(s) for \"$query\":\n\n$output"
                )
            }
        } catch (e: Exception) {
            ToolResult.Failure("Failed to search memory: ${e.message}", e)
        }
    }

    // TODO: replace brute-force cosine similarity with sqlite-vec KNN queries
    //  when chunk count grows large enough to matter. Requires a custom SQLite
    //  build (Android's built-in SQLite doesn't support loadExtension).
    //  See: https://github.com/asg017/sqlite-vec
    private suspend fun semanticSearch(query: String): List<SearchResult> {
        val indexer = memoryIndexer ?: return emptyList()
        val store = embeddingStore ?: return emptyList()
        val service = embeddingService ?: return emptyList()

        try {
            // Initial indexing on cold start (empty index).
            // Subsequent updates are handled eagerly via onFileWritten.
            if (indexer.isEmpty()) {
                indexer.syncAll()
            }

            val queryEmbedding = service.embedQuery(query)
            val allChunks = store.getAllEmbeddings()

            if (allChunks.isEmpty()) return emptyList()

            return allChunks
                .map { chunk ->
                    val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
                    SearchResult(
                        filePath = chunk.filePath,
                        content = chunk.content,
                        score = similarity,
                        source = "semantic"
                    )
                }
                .filter { it.score >= SIMILARITY_THRESHOLD }
                .sortedByDescending { it.score }
                .take(MAX_SEMANTIC_RESULTS)
        } catch (e: Exception) {
            Log.e(TAG, "Semantic search failed, falling back: ${e.message}")
            return emptyList()
        }
    }

    private fun keywordSearch(query: String): List<SearchResult> {
        val results = mutableListOf<SearchResult>()
        val contextLines = 1

        val longTermFile = File(workspaceRoot, "MEMORY.md")
        if (longTermFile.exists()) {
            searchFile(longTermFile, "MEMORY.md", query, contextLines).forEach {
                results.add(SearchResult(filePath = "MEMORY.md", content = it, score = 1.0f, source = "keyword"))
            }
        }

        val dailyFiles = memoryDir.listFiles { f ->
            f.isFile && f.name.endsWith(".md")
        }?.sortedByDescending { it.name } ?: emptyArray<File>().toList()

        for (file in dailyFiles) {
            if (results.size >= MAX_KEYWORD_RESULTS) break
            searchFile(file, "memory/${file.name}", query, contextLines).forEach {
                results.add(SearchResult(filePath = "memory/${file.name}", content = it, score = 1.0f, source = "keyword"))
            }
        }

        return results.take(MAX_KEYWORD_RESULTS)
    }

    private fun mergeResults(
        keywordResults: List<SearchResult>,
        semanticResults: List<SearchResult>
    ): List<String> {
        val combined = mutableListOf<String>()
        val seen = mutableSetOf<String>()

        // Semantic results first (they have similarity scores)
        for (result in semanticResults) {
            val key = "${result.filePath}:${result.content.take(80)}"
            if (seen.add(key)) {
                val scoreStr = "%.2f".format(result.score)
                combined.add("**${result.filePath}** (similarity: $scoreStr):\n${result.content}")
            }
        }

        // Then keyword results (skip if content already covered by semantic)
        for (result in keywordResults) {
            val key = "${result.filePath}:${result.content.take(80)}"
            if (seen.add(key)) {
                combined.add(result.content)
            }
        }

        return combined.take(MAX_TOTAL_RESULTS)
    }

    private fun searchFile(
        file: File,
        displayPath: String,
        query: String,
        contextLines: Int
    ): List<String> {
        val lines = file.readLines()
        val lowerQuery = query.lowercase()
        val matches = mutableListOf<String>()

        for ((index, line) in lines.withIndex()) {
            if (line.lowercase().contains(lowerQuery)) {
                val start = (index - contextLines).coerceAtLeast(0)
                val end = (index + contextLines).coerceAtMost(lines.size - 1)
                val snippet = (start..end).joinToString("\n") { i ->
                    val prefix = if (i == index) ">" else " "
                    "$prefix ${i + 1}: ${lines[i]}"
                }
                matches.add("**$displayPath** (line ${index + 1}):\n```\n$snippet\n```")
            }
        }
        return matches
    }

    private data class SearchResult(
        val filePath: String,
        val content: String,
        val score: Float,
        val source: String
    )

    companion object {
        private const val TAG = "MemoryPlugin"
        private const val SIMILARITY_THRESHOLD = 0.3f
        private const val MAX_SEMANTIC_RESULTS = 10
        private const val MAX_KEYWORD_RESULTS = 20
        private const val MAX_TOTAL_RESULTS = 20

        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            if (a.size != b.size || a.isEmpty()) return 0f
            var dot = 0f
            var normA = 0f
            var normB = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                normA += a[i] * a[i]
                normB += b[i] * b[i]
            }
            val denom = sqrt(normA) * sqrt(normB)
            return if (denom == 0f) 0f else dot / denom
        }
    }
}
