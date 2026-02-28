# RFC-013: Agent Memory System

## Document Information
- **RFC ID**: RFC-013
- **Related PRD**: [FEAT-013 (Agent Memory System)](../../prd/features/FEAT-013-memory.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md), [RFC-005 (Session Management)](RFC-005-session-management.md)
- **Depended On By**: None currently (future: RFC for FEAT-011 Auto Compact)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
OneClawShadow currently stores full message history in Room DB and sends the entire history to the model on each request. There is no mechanism for the agent to recall information from previous sessions, and no context management beyond raw message history. As conversations grow, this approach hits context window limits, and users must repeat preferences and context in every new session.

This RFC introduces an OpenClaw-style memory system with two layers of persistent memory (Daily Logs and Long-term Memory stored as Markdown files), a hybrid search engine combining BM25 keyword search and vector semantic search, and a local on-device embedding model for vector computation. Retrieved memories are injected into the system prompt to give the agent cross-session awareness.

### Goals
1. Implement daily conversation log summarization using the active AI model
2. Implement long-term memory (MEMORY.md) with AI auto-extraction and user manual editing
3. Implement a local embedding engine using ONNX Runtime with a small MiniLM model (~22MB)
4. Implement hybrid search combining BM25 (30%) and vector cosine similarity (70%) with time decay
5. Implement memory injection into the system prompt with configurable token budget
6. Implement trigger mechanisms for daily log writing (session end, app background, session switch, day change, pre-compaction flush)
7. Implement message tracking with `lastLoggedMessageId` to avoid duplicate summarization
8. Define the integration point for FEAT-011 Auto Compact pre-compaction flush
9. Provide concrete Kotlin code signatures and data models for AI-assisted implementation

### Non-Goals
- Cloud sync of memory files (FEAT-007 scope)
- Per-agent isolated memory pools (future enhancement)
- Real-time memory updates during streaming
- Memory encryption beyond standard Android file security
- Voice or image-based memory
- Automated memory expiry or cleanup policies
- Memory-aware conversation branching

## Technical Design

### Architecture Overview

```
+--------------------------------------------------------------------------+
|                              UI Layer                                      |
|  Settings > MemoryScreen                                                   |
|    |-- MemoryEditorView (MEMORY.md full-text editor)                      |
|    |-- DailyLogBrowser (list of dates, read-only viewer)                  |
|    |-- MemoryStatsView (file count, size, index count)                    |
|    |-- RebuildIndexButton                                                  |
+--------------------------------------------------------------------------+
|                            Domain Layer                                    |
|  MemoryManager (facade / coordinator)                                      |
|    |-- DailyLogWriter                                                      |
|    |     |-- Extracts new messages since lastLoggedMessageId               |
|    |     |-- Calls AI model for summarization                              |
|    |     |-- Writes to daily log file + promotes to MEMORY.md              |
|    |                                                                       |
|    |-- LongTermMemoryManager                                               |
|    |     |-- Reads/writes MEMORY.md                                        |
|    |     |-- Provides content for system prompt injection                  |
|    |                                                                       |
|    |-- HybridSearchEngine                                                  |
|    |     |-- BM25Scorer (keyword search)                                   |
|    |     |-- VectorSearcher (cosine similarity)                            |
|    |     |-- TimeDecayCalculator                                           |
|    |     |-- Score merging and ranking                                      |
|    |                                                                       |
|    |-- MemoryInjector                                                      |
|          |-- Formats retrieved memories for system prompt                  |
|          |-- Enforces token budget                                         |
+--------------------------------------------------------------------------+
|                             Data Layer                                     |
|  EmbeddingEngine (ONNX Runtime + MiniLM model)                            |
|    |-- Loads ONNX model lazily                                             |
|    |-- Produces 384-dim float vectors                                      |
|                                                                            |
|  MemoryIndexDao (Room)                                                     |
|    |-- memory_index table (chunk text, embedding blob, metadata)          |
|                                                                            |
|  MemoryFileStorage                                                         |
|    |-- Reads/writes Markdown files at getFilesDir()/memory/               |
+--------------------------------------------------------------------------+
```

### Core Components

1. **MemoryManager**
   - Responsibility: Top-level facade coordinating all memory operations. Entry point for trigger handlers and search requests.
   - Interface: `flushDailyLog(sessionId)`, `searchMemory(query)`, `getInjectionContent(query)`, `rebuildIndex()`
   - Dependencies: DailyLogWriter, LongTermMemoryManager, HybridSearchEngine, MemoryInjector

2. **DailyLogWriter**
   - Responsibility: Extract unprocessed messages from a session, call the AI model for summarization, write the summary to the daily log file, and promote long-term facts to MEMORY.md.
   - Interface: `writeDailyLog(sessionId): Result<Unit>`
   - Dependencies: MessageRepository, ModelApiAdapterFactory, ProviderRepository, AgentRepository, ApiKeyStorage, MemoryFileStorage, LongTermMemoryManager, MemoryIndexDao

3. **LongTermMemoryManager**
   - Responsibility: Read, write, and manage MEMORY.md. Provide content for system prompt injection.
   - Interface: `readMemory(): String`, `appendMemory(content)`, `writeMemory(fullContent)`, `getInjectionContent(maxLines): String`
   - Dependencies: MemoryFileStorage

4. **HybridSearchEngine**
   - Responsibility: Execute hybrid BM25 + vector search over the memory index, apply time decay, and return ranked results.
   - Interface: `search(query, topK): List<MemorySearchResult>`
   - Dependencies: EmbeddingEngine, MemoryIndexDao, BM25Scorer

5. **EmbeddingEngine**
   - Responsibility: Load the ONNX MiniLM model and produce embedding vectors for text.
   - Interface: `embed(text): FloatArray`, `isAvailable(): Boolean`
   - Dependencies: ONNX Runtime, model asset file

6. **MemoryInjector**
   - Responsibility: Format search results and MEMORY.md content into a system prompt section, respecting a token budget.
   - Interface: `buildInjection(query, tokenBudget): String`
   - Dependencies: HybridSearchEngine, LongTermMemoryManager

## Data Model

### MemoryIndex (Room Entity)

```kotlin
/**
 * Room entity for the memory search index.
 * Each row represents one chunk of memory content (a daily log entry or MEMORY.md section).
 * Located in: core/database/entity/MemoryIndexEntity.kt
 */
@Entity(tableName = "memory_index")
data class MemoryIndexEntity(
    @PrimaryKey
    val id: String,                    // UUID

    @ColumnInfo(name = "source_type")
    val sourceType: String,            // "daily_log" or "long_term"

    @ColumnInfo(name = "source_date")
    val sourceDate: String?,           // "2026-02-28" for daily logs, null for MEMORY.md

    @ColumnInfo(name = "chunk_text")
    val chunkText: String,             // The actual text content of this chunk

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray?,         // 384-dim float vector serialized as bytes (null if embedding failed)

    @ColumnInfo(name = "created_at")
    val createdAt: Long,               // Epoch millis when this chunk was indexed

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long                // Epoch millis of last update
)
```

### MemoryIndexDao

```kotlin
/**
 * DAO for memory search index operations.
 * Located in: core/database/dao/MemoryIndexDao.kt
 */
@Dao
interface MemoryIndexDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(entries: List<MemoryIndexEntity>)

    @Query("SELECT * FROM memory_index WHERE source_type = :sourceType")
    suspend fun getBySourceType(sourceType: String): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index WHERE source_date = :date")
    suspend fun getByDate(date: String): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index WHERE embedding IS NOT NULL")
    suspend fun getAllWithEmbeddings(): List<MemoryIndexEntity>

    @Query("SELECT * FROM memory_index")
    suspend fun getAll(): List<MemoryIndexEntity>

    @Query("DELETE FROM memory_index WHERE source_type = :sourceType")
    suspend fun deleteBySourceType(sourceType: String)

    @Query("DELETE FROM memory_index WHERE source_date = :date")
    suspend fun deleteByDate(date: String)

    @Query("DELETE FROM memory_index")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_index")
    suspend fun count(): Int
}
```

### MemorySearchResult (Domain Model)

```kotlin
/**
 * A single search result from the hybrid memory search.
 * Located in: feature/memory/model/MemorySearchResult.kt
 */
data class MemorySearchResult(
    val chunkId: String,
    val chunkText: String,
    val sourceType: String,           // "daily_log" or "long_term"
    val sourceDate: String?,          // Date for daily log entries
    val score: Float,                 // Final combined score (0.0 - 1.0)
    val bm25Score: Float,             // Raw BM25 component
    val vectorScore: Float,           // Raw vector similarity component
    val ageInDays: Int                // Age for display purposes
)
```

### Session Extension -- lastLoggedMessageId

The existing `Session` model needs a new field to track the last message processed for daily log extraction:

```kotlin
/**
 * Extension to the Session model (core/model/Session.kt).
 * Add this field to the existing Session data class.
 */
data class Session(
    // ... existing fields ...
    val lastLoggedMessageId: String? = null  // ID of the last message processed for daily log
)
```

The corresponding Room entity (`SessionEntity`) and DAO also need updating:

```kotlin
// Add to SessionEntity:
@ColumnInfo(name = "last_logged_message_id")
val lastLoggedMessageId: String? = null

// Add to SessionDao:
@Query("UPDATE sessions SET last_logged_message_id = :messageId WHERE id = :sessionId")
suspend fun updateLastLoggedMessageId(sessionId: String, messageId: String)
```

### File Structure

```
getFilesDir()/
  memory/
    MEMORY.md                          # Long-term memory file
    daily/
      2026-02-28.md                    # Daily log for Feb 28
      2026-02-27.md                    # Daily log for Feb 27
      ...
  models/
    minilm-l6-v2.onnx                 # Embedding model (if downloaded, not bundled)
```

## Local Embedding Model Integration

### Model Selection

**Model**: `all-MiniLM-L6-v2` (or equivalent quantized ONNX export)
- **Size**: ~22MB (quantized INT8) to ~90MB (FP32). Use quantized version.
- **Output**: 384-dimensional float vector
- **Performance**: < 200ms per embedding on mid-range Android device
- **Runtime**: ONNX Runtime for Android

### ONNX Runtime Setup

```kotlin
/**
 * Local embedding engine using ONNX Runtime.
 * Located in: feature/memory/embedding/EmbeddingEngine.kt
 */
class EmbeddingEngine(
    private val context: Context
) {
    private var session: OrtSession? = null
    private var environment: OrtEnvironment? = null
    private var tokenizer: BertTokenizer? = null

    private val embeddingDim = 384
    private val maxSequenceLength = 128

    /**
     * Lazily initialize the ONNX model.
     * Called on first embed() request, not at app startup.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (session != null) return@withContext true
        try {
            environment = OrtEnvironment.getEnvironment()
            val modelPath = getModelPath()
            session = environment!!.createSession(modelPath)
            tokenizer = BertTokenizer.fromAsset(context, "models/vocab.txt")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize embedding model", e)
            false
        }
    }

    /**
     * Generate embedding vector for the given text.
     * Returns null if the model is not available.
     */
    suspend fun embed(text: String): FloatArray? = withContext(Dispatchers.IO) {
        if (session == null && !initialize()) return@withContext null
        try {
            val tokens = tokenizer!!.tokenize(text, maxSequenceLength)
            val inputIds = OnnxTensor.createTensor(environment, tokens.inputIds)
            val attentionMask = OnnxTensor.createTensor(environment, tokens.attentionMask)
            val tokenTypeIds = OnnxTensor.createTensor(environment, tokens.tokenTypeIds)

            val inputs = mapOf(
                "input_ids" to inputIds,
                "attention_mask" to attentionMask,
                "token_type_ids" to tokenTypeIds
            )

            val result = session!!.run(inputs)
            val output = result[0].value as Array<FloatArray>

            // Mean pooling over token embeddings
            meanPool(output, tokens.attentionMask)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed", e)
            null
        }
    }

    /**
     * Check if the embedding engine is ready.
     */
    fun isAvailable(): Boolean = session != null

    /**
     * Release model resources.
     */
    fun close() {
        session?.close()
        environment?.close()
        session = null
        environment = null
    }

    private fun meanPool(
        tokenEmbeddings: Array<FloatArray>,
        attentionMask: LongArray
    ): FloatArray {
        val result = FloatArray(embeddingDim)
        var count = 0f
        for (i in tokenEmbeddings.indices) {
            if (attentionMask[i] == 1L) {
                for (j in result.indices) {
                    result[j] += tokenEmbeddings[i][j]
                }
                count++
            }
        }
        if (count > 0) {
            for (j in result.indices) {
                result[j] /= count
            }
        }
        // L2 normalize
        val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (j in result.indices) {
                result[j] /= norm
            }
        }
        return result
    }

    private fun getModelPath(): String {
        // Check external storage first (downloaded model)
        val externalPath = File(context.filesDir, "models/minilm-l6-v2.onnx")
        if (externalPath.exists()) return externalPath.absolutePath

        // Fall back to bundled asset -- copy to cache for ORT
        val cachedPath = File(context.cacheDir, "models/minilm-l6-v2.onnx")
        if (!cachedPath.exists()) {
            cachedPath.parentFile?.mkdirs()
            context.assets.open("models/minilm-l6-v2.onnx").use { input ->
                cachedPath.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
        }
        return cachedPath.absolutePath
    }

    companion object {
        private const val TAG = "EmbeddingEngine"
    }
}
```

### Embedding Serialization

Embeddings (384 floats) are stored as BLOBs in Room:

```kotlin
/**
 * Utility for serializing/deserializing embedding vectors.
 * Located in: feature/memory/embedding/EmbeddingSerializer.kt
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
```

## Hybrid Search Implementation

### BM25 Scorer

```kotlin
/**
 * BM25 keyword scoring for memory search.
 * Located in: feature/memory/search/BM25Scorer.kt
 */
class BM25Scorer {
    private val k1 = 1.2f   // Term frequency saturation
    private val b = 0.75f   // Length normalization

    /**
     * Score all chunks against the query using BM25.
     * Returns a map of chunkId -> BM25 score.
     */
    fun score(
        query: String,
        chunks: List<MemoryIndexEntity>
    ): Map<String, Float> {
        val queryTerms = tokenize(query)
        val avgDocLength = chunks.map { tokenize(it.chunkText).size }.average().toFloat()
        val n = chunks.size

        // IDF for each query term
        val idf = mutableMapOf<String, Float>()
        for (term in queryTerms) {
            val docFreq = chunks.count { tokenize(it.chunkText).contains(term) }
            idf[term] = ln((n - docFreq + 0.5f) / (docFreq + 0.5f) + 1.0f)
        }

        return chunks.associate { chunk ->
            val docTerms = tokenize(chunk.chunkText)
            val docLength = docTerms.size.toFloat()
            val termFreqs = docTerms.groupingBy { it }.eachCount()

            var bm25Score = 0f
            for (term in queryTerms) {
                val tf = termFreqs[term] ?: 0
                val termIdf = idf[term] ?: 0f
                val numerator = tf * (k1 + 1)
                val denominator = tf + k1 * (1 - b + b * docLength / avgDocLength)
                bm25Score += termIdf * numerator / denominator
            }
            chunk.id to bm25Score
        }
    }

    private fun tokenize(text: String): List<String> {
        return text.lowercase()
            .replace(Regex("[^a-z0-9\\u4e00-\\u9fff\\s]"), " ")
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }
    }
}
```

### Vector Searcher

```kotlin
/**
 * Vector similarity search using cosine distance.
 * Located in: feature/memory/search/VectorSearcher.kt
 */
class VectorSearcher(
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * Score all chunks against the query using cosine similarity.
     * Returns a map of chunkId -> cosine similarity score.
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

    private fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
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
```

### Time Decay

```kotlin
/**
 * Time decay calculator for memory search scoring.
 * Located in: feature/memory/search/TimeDecayCalculator.kt
 */
object TimeDecayCalculator {
    /**
     * Calculate time decay factor.
     * Returns a value between 0 and 1, where 1 = today, decaying over time.
     *
     * Formula: decay = exp(-lambda * ageInDays)
     * With lambda = 0.01, half-life is ~69 days.
     */
    fun calculate(createdAtMillis: Long, lambda: Float = 0.01f): Float {
        val ageInDays = ((System.currentTimeMillis() - createdAtMillis) /
            (1000 * 60 * 60 * 24)).toFloat()
        return exp(-lambda * maxOf(ageInDays, 0f))
    }
}
```

### HybridSearchEngine

```kotlin
/**
 * Hybrid search engine combining BM25 and vector search with time decay.
 * Located in: feature/memory/search/HybridSearchEngine.kt
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
                (1000 * 60 * 60 * 24)).toInt()

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
```

## Daily Log Extraction Flow

### DailyLogWriter

```kotlin
/**
 * Handles daily log extraction and writing.
 * Located in: feature/memory/log/DailyLogWriter.kt
 */
class DailyLogWriter(
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory,
    private val memoryFileStorage: MemoryFileStorage,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val memoryIndexDao: MemoryIndexDao,
    private val embeddingEngine: EmbeddingEngine
) {
    companion object {
        private const val TAG = "DailyLogWriter"
    }

    /**
     * Extract and summarize unprocessed messages from a session into the daily log.
     *
     * Flow:
     * 1. Load the session and its lastLoggedMessageId
     * 2. Fetch messages after lastLoggedMessageId
     * 3. If no new messages, return early
     * 4. Build a summarization prompt with the new messages
     * 5. Call the AI model (non-streaming) for summarization
     * 6. Parse the response into: daily_summary + long_term_facts
     * 7. Append daily_summary to today's daily log file
     * 8. Append long_term_facts to MEMORY.md (if any)
     * 9. Index the new chunks (embed + store in Room)
     * 10. Update the session's lastLoggedMessageId
     */
    suspend fun writeDailyLog(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found"))

            // Fetch new messages since last logged
            val allMessages = messageRepository.getMessagesBySessionId(sessionId)
            val newMessages = if (session.lastLoggedMessageId != null) {
                val lastIndex = allMessages.indexOfFirst { it.id == session.lastLoggedMessageId }
                if (lastIndex < 0) allMessages else allMessages.drop(lastIndex + 1)
            } else {
                allMessages
            }

            if (newMessages.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            // Filter to meaningful messages (USER and AI_RESPONSE only)
            val meaningfulMessages = newMessages.filter {
                it.type == MessageType.USER || it.type == MessageType.AI_RESPONSE
            }
            if (meaningfulMessages.isEmpty()) {
                // Update pointer even if no meaningful messages
                sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)
                return@withContext Result.success(Unit)
            }

            // Resolve model for summarization
            val agent = agentRepository.getAgentById(session.currentAgentId)
                ?: return@withContext Result.failure(Exception("Agent not found"))
            val (model, provider) = resolveModel(agent)
                ?: return@withContext Result.failure(Exception("No model available"))
            val apiKey = apiKeyStorage.getApiKey(provider.id)
                ?: return@withContext Result.failure(Exception("No API key"))

            // Build summarization prompt
            val conversationText = meaningfulMessages.joinToString("\n") { msg ->
                val role = if (msg.type == MessageType.USER) "User" else "Assistant"
                "$role: ${msg.content}"
            }
            val prompt = buildSummarizationPrompt(conversationText)

            // Call model for summarization (non-streaming)
            val adapter = adapterFactory.create(provider.type)
            val response = adapter.sendNonStreaming(
                model = model,
                provider = provider,
                apiKey = apiKey,
                messages = listOf(Message(
                    id = UUID.randomUUID().toString(),
                    sessionId = sessionId,
                    type = MessageType.USER,
                    content = prompt,
                    createdAt = System.currentTimeMillis()
                )),
                systemPrompt = SUMMARIZATION_SYSTEM_PROMPT
            )

            // Parse response
            val (dailySummary, longTermFacts) = parseSummarizationResponse(response)

            // Write daily log
            val today = LocalDate.now().toString() // "2026-02-28"
            if (dailySummary.isNotBlank()) {
                memoryFileStorage.appendToDailyLog(today, dailySummary)
                indexChunks(dailySummary, "daily_log", today)
            }

            // Promote long-term facts
            if (longTermFacts.isNotBlank()) {
                longTermMemoryManager.appendMemory(longTermFacts)
                indexChunks(longTermFacts, "long_term", null)
            }

            // Update lastLoggedMessageId
            sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write daily log for session $sessionId", e)
            Result.failure(e)
        }
    }

    private suspend fun indexChunks(text: String, sourceType: String, sourceDate: String?) {
        // Split text into paragraphs as chunks
        val chunks = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        val now = System.currentTimeMillis()

        val entities = chunks.map { chunkText ->
            val embedding = embeddingEngine.embed(chunkText)
            MemoryIndexEntity(
                id = UUID.randomUUID().toString(),
                sourceType = sourceType,
                sourceDate = sourceDate,
                chunkText = chunkText.trim(),
                embedding = embedding?.let { EmbeddingSerializer.toByteArray(it) },
                createdAt = now,
                updatedAt = now
            )
        }

        memoryIndexDao.insertAll(entities)
    }

    private fun buildSummarizationPrompt(conversationText: String): String {
        return """
            |Summarize the following conversation. Provide two sections:
            |
            |## Daily Summary
            |A concise summary of key topics discussed, decisions made, tasks completed,
            |and any notable information. Use bullet points.
            |
            |## Long-term Facts
            |Extract any stable facts, user preferences, or important knowledge that should
            |be remembered permanently. If none, write "None".
            |
            |---
            |Conversation:
            |$conversationText
        """.trimMargin()
    }

    private fun parseSummarizationResponse(
        response: String
    ): Pair<String, String> {
        val dailySummaryRegex = Regex(
            "## Daily Summary\\s*\\n([\\s\\S]*?)(?=## Long-term Facts|$)",
            RegexOption.IGNORE_CASE
        )
        val longTermRegex = Regex(
            "## Long-term Facts\\s*\\n([\\s\\S]*?)$",
            RegexOption.IGNORE_CASE
        )

        val dailySummary = dailySummaryRegex.find(response)?.groupValues?.get(1)?.trim() ?: response
        val longTermRaw = longTermRegex.find(response)?.groupValues?.get(1)?.trim() ?: ""
        val longTermFacts = if (longTermRaw.equals("None", ignoreCase = true) ||
            longTermRaw.equals("None.", ignoreCase = true)) "" else longTermRaw

        return dailySummary to longTermFacts
    }

    private suspend fun resolveModel(agent: Agent): Pair<AiModel, Provider>? {
        val modelId = agent.modelId ?: return null
        val model = providerRepository.getModelById(modelId) ?: return null
        val provider = providerRepository.getProviderById(model.providerId) ?: return null
        return model to provider
    }

    companion object {
        private val SUMMARIZATION_SYSTEM_PROMPT = """
            |You are a memory extraction assistant. Your job is to summarize conversations
            |and extract important long-term facts. Be concise and factual.
            |Format your response exactly as requested with the two sections:
            |"## Daily Summary" and "## Long-term Facts".
        """.trimMargin()
    }
}
```

### Summarization Prompt Design

The summarization prompt asks the model to produce two clearly separated sections:

```
## Daily Summary
- Key topic 1: brief description
- Decision made: what was decided
- Task completed: what was done

## Long-term Facts
- User prefers concise answers
- User's project is named "ProjectX" and uses Kotlin
```

This structure is parsed by `parseSummarizationResponse()` to separate daily content from long-term facts.

## Long-term Memory Management

### LongTermMemoryManager

```kotlin
/**
 * Manages the MEMORY.md long-term memory file.
 * Located in: feature/memory/longterm/LongTermMemoryManager.kt
 */
class LongTermMemoryManager(
    private val memoryFileStorage: MemoryFileStorage
) {
    /**
     * Read the full content of MEMORY.md.
     * Returns empty string if the file doesn't exist yet.
     */
    suspend fun readMemory(): String = withContext(Dispatchers.IO) {
        memoryFileStorage.readMemoryFile() ?: ""
    }

    /**
     * Append content to MEMORY.md.
     * Creates the file if it doesn't exist.
     */
    suspend fun appendMemory(content: String) = withContext(Dispatchers.IO) {
        val existing = readMemory()
        val newContent = if (existing.isBlank()) {
            "# Long-term Memory\n\n$content\n"
        } else {
            "$existing\n$content\n"
        }
        memoryFileStorage.writeMemoryFile(newContent)
    }

    /**
     * Overwrite MEMORY.md with new content (for user manual editing).
     */
    suspend fun writeMemory(fullContent: String) = withContext(Dispatchers.IO) {
        memoryFileStorage.writeMemoryFile(fullContent)
    }

    /**
     * Get content for system prompt injection.
     * Returns at most the first [maxLines] lines.
     */
    suspend fun getInjectionContent(maxLines: Int = 200): String = withContext(Dispatchers.IO) {
        val content = readMemory()
        if (content.isBlank()) return@withContext ""
        content.lines().take(maxLines).joinToString("\n")
    }
}
```

### MemoryFileStorage

```kotlin
/**
 * Handles file I/O for memory Markdown files.
 * Located in: feature/memory/storage/MemoryFileStorage.kt
 */
class MemoryFileStorage(
    private val context: Context
) {
    private val memoryDir: File
        get() = File(context.filesDir, "memory").also { it.mkdirs() }

    private val dailyLogDir: File
        get() = File(memoryDir, "daily").also { it.mkdirs() }

    private val memoryFile: File
        get() = File(memoryDir, "MEMORY.md")

    /**
     * Read MEMORY.md content. Returns null if file doesn't exist.
     */
    fun readMemoryFile(): String? {
        return if (memoryFile.exists()) memoryFile.readText() else null
    }

    /**
     * Write full content to MEMORY.md.
     */
    fun writeMemoryFile(content: String) {
        memoryFile.writeText(content)
    }

    /**
     * Append content to a daily log file.
     * Creates the file with a header if it doesn't exist.
     */
    fun appendToDailyLog(date: String, content: String) {
        val file = File(dailyLogDir, "$date.md")
        if (!file.exists()) {
            file.writeText("# Daily Log - $date\n\n")
        }
        file.appendText("$content\n\n---\n\n")
    }

    /**
     * Read a daily log file. Returns null if it doesn't exist.
     */
    fun readDailyLog(date: String): String? {
        val file = File(dailyLogDir, "$date.md")
        return if (file.exists()) file.readText() else null
    }

    /**
     * List all daily log dates (sorted descending).
     */
    fun listDailyLogDates(): List<String> {
        return dailyLogDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * Get total size of all memory files in bytes.
     */
    fun getTotalSize(): Long {
        return memoryDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * Get count of daily log files.
     */
    fun getDailyLogCount(): Int {
        return dailyLogDir.listFiles()?.count { it.extension == "md" } ?: 0
    }
}
```

## Memory Injection

### MemoryInjector

```kotlin
/**
 * Builds the memory injection block for the system prompt.
 * Located in: feature/memory/injection/MemoryInjector.kt
 */
class MemoryInjector(
    private val hybridSearchEngine: HybridSearchEngine,
    private val longTermMemoryManager: LongTermMemoryManager
) {
    companion object {
        const val DEFAULT_TOKEN_BUDGET = 2000
        const val CHARS_PER_TOKEN_ESTIMATE = 4  // Rough estimate for budget enforcement
    }

    /**
     * Build the memory injection content for the system prompt.
     *
     * Structure:
     * ```
     * ## Long-term Memory
     * [MEMORY.md content, first 200 lines]
     *
     * ## Relevant Memories
     * [Top-K search results with source attribution]
     * ```
     *
     * Returns empty string if no memory content is available.
     */
    suspend fun buildInjection(
        query: String,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET
    ): String {
        val charBudget = tokenBudget * CHARS_PER_TOKEN_ESTIMATE
        val builder = StringBuilder()

        // Always include MEMORY.md content
        val memoryContent = longTermMemoryManager.getInjectionContent(maxLines = 200)
        if (memoryContent.isNotBlank()) {
            builder.appendLine("## Long-term Memory")
            builder.appendLine(memoryContent)
            builder.appendLine()
        }

        // Search for relevant memories
        val remainingBudget = charBudget - builder.length
        if (remainingBudget > 100 && query.isNotBlank()) {
            val results = hybridSearchEngine.search(query, topK = 5)
            if (results.isNotEmpty()) {
                builder.appendLine("## Relevant Memories")
                for (result in results) {
                    val entryText = formatSearchResult(result)
                    if (builder.length + entryText.length > charBudget) break
                    builder.appendLine(entryText)
                }
            }
        }

        return builder.toString().trimEnd()
    }

    private fun formatSearchResult(result: MemorySearchResult): String {
        val source = when (result.sourceType) {
            "daily_log" -> "Daily log ${result.sourceDate}"
            "long_term" -> "Long-term memory"
            else -> "Memory"
        }
        return "- [$source] ${result.chunkText}"
    }
}
```

### Integration with SendMessageUseCase

The `MemoryInjector` is called during the message send flow to enrich the system prompt:

```kotlin
/**
 * Modification to SendMessageUseCase.execute()
 *
 * Before sending messages to the model, inject memory context into the system prompt.
 */
// In SendMessageUseCase:

suspend fun execute(
    sessionId: String,
    userText: String,
    agentId: String
): Flow<ChatEvent> = flow {
    // ... existing setup code ...

    // Resolve agent and its system prompt
    val agent = agentRepository.getAgentById(agentId) ?: /* error */
    var systemPrompt = agent.systemPrompt ?: ""

    // --- NEW: Memory injection ---
    val memoryInjection = memoryInjector.buildInjection(query = userText)
    if (memoryInjection.isNotBlank()) {
        systemPrompt = if (systemPrompt.isBlank()) {
            memoryInjection
        } else {
            "$systemPrompt\n\n$memoryInjection"
        }
    }
    // --- END NEW ---

    // ... rest of the existing flow (send to model, handle streaming, tool calls, etc.) ...
}
```

## Trigger Mechanisms

### MemoryTriggerManager

```kotlin
/**
 * Coordinates memory trigger events.
 * Located in: feature/memory/trigger/MemoryTriggerManager.kt
 */
class MemoryTriggerManager(
    private val memoryManager: MemoryManager,
    private val sessionRepository: SessionRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    /**
     * Called when the app goes to the background.
     * Registered via ProcessLifecycleOwner in Application class.
     */
    fun onAppBackground() {
        scope.launch {
            flushActiveSession()
        }
    }

    /**
     * Called when the user switches from one session to another.
     */
    fun onSessionSwitch(previousSessionId: String) {
        scope.launch {
            flushSession(previousSessionId)
        }
    }

    /**
     * Called when the date changes during an active session.
     * Detected by a periodic check or system broadcast.
     */
    fun onDayChange(activeSessionId: String) {
        scope.launch {
            flushSession(activeSessionId)
        }
    }

    /**
     * Called before FEAT-011 Auto Compact compresses message history.
     * This is the pre-compaction flush integration point.
     */
    suspend fun onPreCompaction(sessionId: String) {
        flushSession(sessionId)
    }

    /**
     * Called when a session ends (user explicitly closes it).
     */
    fun onSessionEnd(sessionId: String) {
        scope.launch {
            flushSession(sessionId)
        }
    }

    private suspend fun flushSession(sessionId: String) {
        // Use mutex to prevent concurrent flush for the same session
        mutex.withLock {
            memoryManager.flushDailyLog(sessionId)
        }
    }

    private suspend fun flushActiveSession() {
        val activeSession = sessionRepository.getActiveSession()
        if (activeSession != null) {
            flushSession(activeSession.id)
        }
    }
}
```

### ProcessLifecycleOwner Registration

```kotlin
/**
 * Register the app background trigger in the Application class.
 * Add to OneclawApplication.kt:
 */
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... existing init ...

        // Register memory trigger for app background
        val memoryTriggerManager: MemoryTriggerManager = get() // Koin inject
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // App went to background
                    memoryTriggerManager.onAppBackground()
                }
            }
        )
    }
}
```

## MemoryManager (Facade)

```kotlin
/**
 * Top-level coordinator for all memory operations.
 * Located in: feature/memory/MemoryManager.kt
 */
class MemoryManager(
    private val dailyLogWriter: DailyLogWriter,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val hybridSearchEngine: HybridSearchEngine,
    private val memoryInjector: MemoryInjector,
    private val memoryIndexDao: MemoryIndexDao,
    private val memoryFileStorage: MemoryFileStorage,
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * Flush daily log for a session.
     * Called by trigger mechanisms.
     */
    suspend fun flushDailyLog(sessionId: String): Result<Unit> {
        return dailyLogWriter.writeDailyLog(sessionId)
    }

    /**
     * Search memory for relevant content.
     */
    suspend fun searchMemory(query: String, topK: Int = 5): List<MemorySearchResult> {
        return hybridSearchEngine.search(query, topK)
    }

    /**
     * Get the injection content for the system prompt.
     */
    suspend fun getInjectionContent(query: String, tokenBudget: Int = 2000): String {
        return memoryInjector.buildInjection(query, tokenBudget)
    }

    /**
     * Rebuild the entire search index from files.
     * Used when the index is corrupted or after manual edits.
     */
    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        memoryIndexDao.deleteAll()

        // Re-index all daily logs
        for (date in memoryFileStorage.listDailyLogDates()) {
            val content = memoryFileStorage.readDailyLog(date) ?: continue
            indexContent(content, "daily_log", date)
        }

        // Re-index MEMORY.md
        val memoryContent = memoryFileStorage.readMemoryFile()
        if (memoryContent != null) {
            indexContent(memoryContent, "long_term", null)
        }
    }

    /**
     * Get memory statistics.
     */
    suspend fun getStats(): MemoryStats {
        return MemoryStats(
            dailyLogCount = memoryFileStorage.getDailyLogCount(),
            totalSizeBytes = memoryFileStorage.getTotalSize(),
            indexedChunkCount = memoryIndexDao.count(),
            embeddingModelLoaded = embeddingEngine.isAvailable()
        )
    }

    private suspend fun indexContent(text: String, sourceType: String, sourceDate: String?) {
        val chunks = text.split(Regex("\n{2,}"))
            .filter { it.isNotBlank() && !it.startsWith("#") && it.trim() != "---" }

        val now = System.currentTimeMillis()
        val entities = chunks.map { chunkText ->
            val embedding = embeddingEngine.embed(chunkText.trim())
            MemoryIndexEntity(
                id = UUID.randomUUID().toString(),
                sourceType = sourceType,
                sourceDate = sourceDate,
                chunkText = chunkText.trim(),
                embedding = embedding?.let { EmbeddingSerializer.toByteArray(it) },
                createdAt = now,
                updatedAt = now
            )
        }
        memoryIndexDao.insertAll(entities)
    }
}

/**
 * Memory statistics data class.
 */
data class MemoryStats(
    val dailyLogCount: Int,
    val totalSizeBytes: Long,
    val indexedChunkCount: Int,
    val embeddingModelLoaded: Boolean
)
```

## Dependency Injection

### MemoryModule (Koin)

```kotlin
/**
 * Koin module for memory system dependencies.
 * Located in: di/MemoryModule.kt
 *
 * Register this module in the Koin application setup alongside
 * existing modules (AppModule, FeatureModule, etc.).
 */
val memoryModule = module {
    // Data layer
    single { MemoryFileStorage(androidContext()) }
    single { EmbeddingEngine(androidContext()) }

    // Search components
    factory { BM25Scorer() }
    factory { VectorSearcher(get()) }
    factory { HybridSearchEngine(get(), get(), get(), get()) }

    // Domain layer
    single { LongTermMemoryManager(get()) }
    single {
        DailyLogWriter(
            messageRepository = get(),
            sessionRepository = get(),
            agentRepository = get(),
            providerRepository = get(),
            apiKeyStorage = get(),
            adapterFactory = get(),
            memoryFileStorage = get(),
            longTermMemoryManager = get(),
            memoryIndexDao = get(),
            embeddingEngine = get()
        )
    }
    single { MemoryInjector(get(), get()) }
    single {
        MemoryManager(
            dailyLogWriter = get(),
            longTermMemoryManager = get(),
            hybridSearchEngine = get(),
            memoryInjector = get(),
            memoryIndexDao = get(),
            memoryFileStorage = get(),
            embeddingEngine = get()
        )
    }

    // Trigger manager
    single { MemoryTriggerManager(get(), get()) }
}
```

### Database Migration

Add `MemoryIndexDao` to the existing `AppDatabase`:

```kotlin
/**
 * Add to AppDatabase (core/database/AppDatabase.kt):
 * 1. Add MemoryIndexEntity to the @Database entities list
 * 2. Add the MemoryIndexDao abstract function
 * 3. Increment the database version
 * 4. Add a migration
 */
@Database(
    entities = [
        // ... existing entities ...,
        MemoryIndexEntity::class
    ],
    version = N + 1  // Increment version
)
abstract class AppDatabase : RoomDatabase() {
    // ... existing DAOs ...
    abstract fun memoryIndexDao(): MemoryIndexDao
}

// Migration:
val MIGRATION_N_N1 = object : Migration(N, N + 1) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("""
            CREATE TABLE IF NOT EXISTS memory_index (
                id TEXT NOT NULL PRIMARY KEY,
                source_type TEXT NOT NULL,
                source_date TEXT,
                chunk_text TEXT NOT NULL,
                embedding BLOB,
                created_at INTEGER NOT NULL,
                updated_at INTEGER NOT NULL
            )
        """)
        db.execSQL("""
            ALTER TABLE sessions ADD COLUMN last_logged_message_id TEXT
        """)
    }
}
```

### DatabaseModule Update

```kotlin
/**
 * Add to DatabaseModule (di/DatabaseModule.kt):
 */
single { get<AppDatabase>().memoryIndexDao() }
```

## Pre-compaction Flush Integration (FEAT-011)

When FEAT-011 Auto Compact is implemented, it will call into the memory system before compressing messages:

```kotlin
/**
 * Integration point for FEAT-011 Auto Compact.
 * This is called by the auto-compact use case BEFORE it compresses
 * the message history for a session.
 *
 * Pseudo-code for the future Auto Compact flow:
 */
class AutoCompactUseCase(
    private val memoryTriggerManager: MemoryTriggerManager,
    // ... other dependencies ...
) {
    suspend fun compact(sessionId: String) {
        // Step 1: Flush memories before compaction
        memoryTriggerManager.onPreCompaction(sessionId)

        // Step 2: Proceed with message compression
        // ... compact messages ...
    }
}
```

This ensures that important context is preserved in the memory system before the raw message history is compressed.

## Implementation Phases

### Phase 1: Foundation (Memory Files + Basic Infrastructure)
1. [ ] Create `MemoryFileStorage` with read/write for MEMORY.md and daily logs
2. [ ] Create `LongTermMemoryManager`
3. [ ] Add `MemoryIndexEntity` and `MemoryIndexDao` to Room database
4. [ ] Add `lastLoggedMessageId` field to Session model, entity, and DAO
5. [ ] Database migration
6. [ ] Create `MemoryModule` (Koin) with basic registrations
7. [ ] Unit tests for file storage and long-term memory manager

### Phase 2: Daily Log Writing
1. [ ] Implement `DailyLogWriter` with summarization prompt
2. [ ] Implement `MemoryTriggerManager` with all trigger hooks
3. [ ] Register `ProcessLifecycleOwner` observer in Application class
4. [ ] Wire session switch and session end triggers in ChatViewModel
5. [ ] Implement day change detection
6. [ ] Unit tests for daily log extraction and parsing
7. [ ] Integration test: full daily log flow

### Phase 3: Embedding Engine
1. [ ] Integrate ONNX Runtime dependency
2. [ ] Obtain and bundle quantized MiniLM ONNX model
3. [ ] Implement `EmbeddingEngine` with lazy initialization
4. [ ] Implement `EmbeddingSerializer`
5. [ ] Implement `BertTokenizer` (or use a library)
6. [ ] Unit tests for embedding generation and serialization
7. [ ] Performance benchmarks on target devices

### Phase 4: Search + Injection
1. [ ] Implement `BM25Scorer`
2. [ ] Implement `VectorSearcher`
3. [ ] Implement `TimeDecayCalculator`
4. [ ] Implement `HybridSearchEngine`
5. [ ] Implement `MemoryInjector`
6. [ ] Implement `MemoryManager` facade
7. [ ] Integrate `MemoryInjector` into `SendMessageUseCase`
8. [ ] Unit tests for search scoring and ranking
9. [ ] Integration test: end-to-end memory retrieval

### Phase 5: UI + Polish
1. [ ] Create Memory settings screen (MEMORY.md editor, daily log browser, stats)
2. [ ] Add "Rebuild Index" functionality
3. [ ] Wire settings screen into the navigation
4. [ ] Manual testing of full flow
5. [ ] Performance optimization (background threading, lazy loading)
6. [ ] Edge case handling (empty memory, model unavailable, disk full)

## Testing Strategy

### Unit Tests
| Component | Test Focus |
|-----------|-----------|
| `MemoryFileStorage` | File creation, reading, appending, listing, edge cases (missing dirs) |
| `LongTermMemoryManager` | Read/write/append MEMORY.md, injection content truncation |
| `BM25Scorer` | Scoring accuracy, tokenization, empty input, CJK text |
| `VectorSearcher` | Cosine similarity calculation, handling null embeddings |
| `TimeDecayCalculator` | Decay values at various ages, edge cases (future dates) |
| `HybridSearchEngine` | Score merging, normalization, ranking, fallback when no embeddings |
| `EmbeddingSerializer` | Round-trip serialization/deserialization |
| `MemoryInjector` | Format output, token budget enforcement, empty memory |
| `DailyLogWriter` | Response parsing, message filtering, lastLoggedMessageId tracking |

### Integration Tests
| Test | Description |
|------|-------------|
| Daily log flow | Create session with messages -> trigger flush -> verify daily log file created |
| Memory injection | Insert test memories -> send message -> verify system prompt contains memory |
| Hybrid search accuracy | Index known chunks -> query -> verify correct chunks ranked highest |
| Rebuild index | Delete index -> rebuild -> verify all files re-indexed |
| Concurrent triggers | Fire multiple triggers rapidly -> verify no duplication or corruption |

### Performance Tests
| Test | Target |
|------|--------|
| Embedding latency | < 200ms on Pixel 6 |
| Search latency (100 chunks) | < 200ms |
| Search latency (1000 chunks) | < 500ms |
| Daily log write (50 messages) | < 5s (dominated by API call) |
| Model loading (cold start) | < 3s |
| Memory footprint (model loaded) | < 100MB |

## Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|------------|-----------|
| Embedding model too large for APK size | High | Medium | Use quantized INT8 model (~22MB); or download on first launch |
| ONNX Runtime compatibility issues on older devices | Medium | Low | Minimum API 26 already ensures ARM64 support; test on low-end devices |
| Summarization API call costs | Low | High | Daily log writes are infrequent; use the user's existing API key |
| Search latency grows with memory size | Medium | Medium | Limit index scan; consider SQLite FTS for BM25 in future |
| Concurrent file writes corrupt Markdown files | Medium | Low | Mutex in MemoryTriggerManager prevents concurrent writes |
| Model not available for summarization | Medium | Medium | Skip daily log gracefully; retry on next trigger |

## Alternative Approaches Considered

### Option A: MemoryOS-style (Heavy, Structured)
A more complex system with separate global memory, session memory, and working memory layers, each with its own storage format and lifecycle management.

- **Pros**: More structured, granular control
- **Cons**: Much more complex to implement, higher maintenance burden, overkill for a mobile app
- **Why not chosen**: The OpenClaw-style approach (Option B, what this RFC describes) provides the right balance of simplicity and capability for a mobile app.

### Option B: API-based Embedding (Cloud)
Use the AI provider's embedding API instead of a local model.

- **Pros**: No model bundling, potentially better embeddings
- **Cons**: Requires network, costs money per embedding call, privacy concerns
- **Why not chosen**: The user confirmed local-only embedding to maintain zero-backend-dependency and user data control.

### Option C: No Vector Search (BM25 Only)
Skip the embedding model entirely and use only BM25 keyword search.

- **Pros**: Much simpler, no model to bundle, faster
- **Cons**: Misses semantic matches (e.g., "favorite color" won't match "I like blue")
- **Why not chosen**: Hybrid search significantly improves recall quality. The local model approach keeps it local-only while adding semantic understanding.

## Future Extensions

- Per-agent memory isolation (each agent has its own MEMORY.md and daily logs)
- SQLite FTS5 for more efficient BM25 scoring at scale
- Incremental index updates (only re-embed changed chunks)
- Memory-aware auto-complete suggestions
- Quantized model download on first launch instead of bundling
- Integration with FEAT-007 for cloud backup of memory files
- User-facing memory search tool callable by the agent

## Open Questions

- [ ] Exact quantized ONNX model to use (all-MiniLM-L6-v2-int8 vs alternatives)
- [ ] Whether to bundle the model in APK or download on first launch (APK size tradeoff)
- [ ] Token budget default (2000 tokens) -- needs tuning based on real usage patterns
- [ ] Whether `sendNonStreaming()` method exists on `ModelApiAdapter` or needs to be added

## References

- [ONNX Runtime for Android](https://onnxruntime.ai/docs/get-started/with-java.html)
- [all-MiniLM-L6-v2 on Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- [BM25 Algorithm](https://en.wikipedia.org/wiki/Okapi_BM25)
- [OpenClaw Memory System](https://github.com/anthropics/claude-code) (inspiration)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
