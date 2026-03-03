# RFC-013: Agent 记忆系统

## 文档信息
- **RFC编号**: RFC-013
- **关联PRD**: [FEAT-013 (Agent 记忆系统)](../../prd/features/FEAT-013-memory-zh.md)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **依赖**: [RFC-001 (对话交互)](RFC-001-chat-interaction-zh.md)、[RFC-005 (会话管理)](RFC-005-session-management-zh.md)
- **被依赖**: 暂无（未来：FEAT-011 自动压缩的 RFC）
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
OneClaw 目前将完整的消息历史存储在 Room 数据库中，每次请求都向模型发送全部历史。没有任何机制让 Agent 回忆前次会话的信息，也没有超出原始消息历史的上下文管理。随着对话增长，这种方式会达到上下文窗口限制，用户必须在每个新会话中重复偏好和上下文。

本 RFC 引入 OpenClaw 风格的记忆系统，包含两层持久化记忆（每日日志和长期记忆，以 Markdown 文件存储）、混合搜索引擎（结合 BM25 关键词搜索和向量语义搜索），以及本地设备端嵌入模型用于向量计算。检索到的记忆注入到系统提示中，赋予 Agent 跨会话感知能力。

### 目标
1. 使用活跃 AI 模型实现每日对话日志摘要
2. 实现长期记忆（MEMORY.md），支持 AI 自动提取和用户手动编辑
3. 使用 ONNX Runtime 和小型 MiniLM 模型（约 22MB）实现本地嵌入引擎
4. 实现混合搜索，结合 BM25（30%）和向量余弦相似度（70%），带时间衰减
5. 实现记忆注入到系统提示，支持可配置的 token 预算
6. 实现每日日志写入的触发机制（会话结束、应用进入后台、会话切换、日期变更、压缩前刷写）
7. 使用 `lastLoggedMessageId` 实现消息追踪，避免重复摘要
8. 定义与 FEAT-011 自动压缩的压缩前刷写集成点
9. 提供具体的 Kotlin 代码签名和数据模型，便于 AI 辅助实现

### 非目标
- 记忆文件的云同步（FEAT-007 范围）
- 按 Agent 隔离的记忆池（未来增强）
- 流式传输期间的实时记忆更新
- 超出 Android 标准文件安全的记忆加密
- 语音或图片类型的记忆
- 自动记忆过期或清理策略
- 基于记忆的对话分支感知

## 技术方案

### 整体设计

```
+--------------------------------------------------------------------------+
|                              UI 层                                         |
|  设置 > 记忆界面                                                            |
|    |-- MemoryEditorView (MEMORY.md 全文编辑器)                             |
|    |-- DailyLogBrowser (日期列表，只读查看)                                  |
|    |-- MemoryStatsView (文件数量、大小、索引数量)                             |
|    |-- 重建索引按钮                                                         |
+--------------------------------------------------------------------------+
|                            领域层                                          |
|  MemoryManager (外观/协调器)                                                |
|    |-- DailyLogWriter                                                      |
|    |     |-- 提取 lastLoggedMessageId 之后的新消息                           |
|    |     |-- 调用 AI 模型进行摘要                                            |
|    |     |-- 写入每日日志文件 + 提升到 MEMORY.md                              |
|    |                                                                       |
|    |-- LongTermMemoryManager                                               |
|    |     |-- 读写 MEMORY.md                                                 |
|    |     |-- 提供系统提示注入内容                                             |
|    |                                                                       |
|    |-- HybridSearchEngine                                                  |
|    |     |-- BM25Scorer (关键词搜索)                                        |
|    |     |-- VectorSearcher (余弦相似度)                                     |
|    |     |-- TimeDecayCalculator (时间衰减)                                  |
|    |     |-- 分数合并与排序                                                  |
|    |                                                                       |
|    |-- MemoryInjector                                                      |
|          |-- 格式化检索到的记忆用于系统提示                                    |
|          |-- 执行 token 预算限制                                             |
+--------------------------------------------------------------------------+
|                             数据层                                         |
|  EmbeddingEngine (ONNX Runtime + MiniLM 模型)                              |
|    |-- 惰性加载 ONNX 模型                                                   |
|    |-- 生成 384 维浮点向量                                                   |
|                                                                            |
|  MemoryIndexDao (Room)                                                     |
|    |-- memory_index 表 (块文本、嵌入 blob、元数据)                           |
|                                                                            |
|  MemoryFileStorage                                                         |
|    |-- 在 getFilesDir()/memory/ 读写 Markdown 文件                          |
+--------------------------------------------------------------------------+
```

### 核心组件

1. **MemoryManager**
   - 职责：顶层外观，协调所有记忆操作。触发处理器和搜索请求的入口点。
   - 接口：`flushDailyLog(sessionId)`、`searchMemory(query)`、`getInjectionContent(query)`、`rebuildIndex()`
   - 依赖：DailyLogWriter、LongTermMemoryManager、HybridSearchEngine、MemoryInjector

2. **DailyLogWriter**
   - 职责：从会话中提取未处理的消息，调用 AI 模型进行摘要，将摘要写入每日日志文件，并将长期事实提升到 MEMORY.md。
   - 接口：`writeDailyLog(sessionId): Result<Unit>`
   - 依赖：MessageRepository、ModelApiAdapterFactory、ProviderRepository、AgentRepository、ApiKeyStorage、MemoryFileStorage、LongTermMemoryManager、MemoryIndexDao

3. **LongTermMemoryManager**
   - 职责：读取、写入和管理 MEMORY.md。提供系统提示注入内容。
   - 接口：`readMemory(): String`、`appendMemory(content)`、`writeMemory(fullContent)`、`getInjectionContent(maxLines): String`
   - 依赖：MemoryFileStorage

4. **HybridSearchEngine**
   - 职责：对记忆索引执行混合 BM25 + 向量搜索，应用时间衰减，返回排序结果。
   - 接口：`search(query, topK): List<MemorySearchResult>`
   - 依赖：EmbeddingEngine、MemoryIndexDao、BM25Scorer

5. **EmbeddingEngine**
   - 职责：加载 ONNX MiniLM 模型并生成文本的嵌入向量。
   - 接口：`embed(text): FloatArray`、`isAvailable(): Boolean`
   - 依赖：ONNX Runtime、模型资源文件

6. **MemoryInjector**
   - 职责：将搜索结果和 MEMORY.md 内容格式化为系统提示区块，遵守 token 预算。
   - 接口：`buildInjection(query, tokenBudget): String`
   - 依赖：HybridSearchEngine、LongTermMemoryManager

## 数据模型

### MemoryIndex (Room 实体)

```kotlin
/**
 * 记忆搜索索引的 Room 实体。
 * 每行代表一个记忆内容块（每日日志条目或 MEMORY.md 区段）。
 * 位置：core/database/entity/MemoryIndexEntity.kt
 */
@Entity(tableName = "memory_index")
data class MemoryIndexEntity(
    @PrimaryKey
    val id: String,                    // UUID

    @ColumnInfo(name = "source_type")
    val sourceType: String,            // "daily_log" 或 "long_term"

    @ColumnInfo(name = "source_date")
    val sourceDate: String?,           // 每日日志为 "2026-02-28"，MEMORY.md 为 null

    @ColumnInfo(name = "chunk_text")
    val chunkText: String,             // 此块的实际文本内容

    @ColumnInfo(name = "embedding", typeAffinity = ColumnInfo.BLOB)
    val embedding: ByteArray?,         // 384 维浮点向量序列化为字节（嵌入失败时为 null）

    @ColumnInfo(name = "created_at")
    val createdAt: Long,               // 索引创建时间（毫秒时间戳）

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long                // 最后更新时间（毫秒时间戳）
)
```

### MemoryIndexDao

```kotlin
/**
 * 记忆搜索索引操作的 DAO。
 * 位置：core/database/dao/MemoryIndexDao.kt
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

### MemorySearchResult (领域模型)

```kotlin
/**
 * 混合记忆搜索的单个搜索结果。
 * 位置：feature/memory/model/MemorySearchResult.kt
 */
data class MemorySearchResult(
    val chunkId: String,
    val chunkText: String,
    val sourceType: String,           // "daily_log" 或 "long_term"
    val sourceDate: String?,          // 每日日志条目的日期
    val score: Float,                 // 最终组合分数 (0.0 - 1.0)
    val bm25Score: Float,             // 原始 BM25 分量
    val vectorScore: Float,           // 原始向量相似度分量
    val ageInDays: Int                // 用于显示的天数
)
```

### Session 扩展 -- lastLoggedMessageId

现有的 `Session` 模型需要新增字段来追踪每日日志提取的最后处理消息：

```kotlin
/**
 * Session 模型扩展 (core/model/Session.kt)。
 * 将此字段添加到现有的 Session data class。
 */
data class Session(
    // ... 现有字段 ...
    val lastLoggedMessageId: String? = null  // 每日日志已处理的最后消息 ID
)
```

相应的 Room 实体（`SessionEntity`）和 DAO 也需要更新：

```kotlin
// 添加到 SessionEntity：
@ColumnInfo(name = "last_logged_message_id")
val lastLoggedMessageId: String? = null

// 添加到 SessionDao：
@Query("UPDATE sessions SET last_logged_message_id = :messageId WHERE id = :sessionId")
suspend fun updateLastLoggedMessageId(sessionId: String, messageId: String)
```

### 文件结构

```
getFilesDir()/
  memory/
    MEMORY.md                          # 长期记忆文件
    daily/
      2026-02-28.md                    # 2月28日的每日日志
      2026-02-27.md                    # 2月27日的每日日志
      ...
  models/
    minilm-l6-v2.onnx                 # 嵌入模型（如下载而非打包）
```

## 本地嵌入模型集成

### 模型选择

**模型**：`all-MiniLM-L6-v2`（或等效的量化 ONNX 导出版本）
- **大小**：约 22MB（INT8 量化版）至约 90MB（FP32 版）。使用量化版本。
- **输出**：384 维浮点向量
- **性能**：中端 Android 设备每次嵌入 < 200ms
- **运行时**：ONNX Runtime for Android

### ONNX Runtime 设置

```kotlin
/**
 * 使用 ONNX Runtime 的本地嵌入引擎。
 * 位置：feature/memory/embedding/EmbeddingEngine.kt
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
     * 惰性初始化 ONNX 模型。
     * 在第一次 embed() 请求时调用，而非应用启动时。
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
     * 为给定文本生成嵌入向量。
     * 如果模型不可用则返回 null。
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

            // 对 token 嵌入进行均值池化
            meanPool(output, tokens.attentionMask)
        } catch (e: Exception) {
            Log.e(TAG, "Embedding failed", e)
            null
        }
    }

    /**
     * 检查嵌入引擎是否就绪。
     */
    fun isAvailable(): Boolean = session != null

    /**
     * 释放模型资源。
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
        // L2 归一化
        val norm = sqrt(result.sumOf { (it * it).toDouble() }).toFloat()
        if (norm > 0) {
            for (j in result.indices) {
                result[j] /= norm
            }
        }
        return result
    }

    private fun getModelPath(): String {
        // 首先检查外部存储（下载的模型）
        val externalPath = File(context.filesDir, "models/minilm-l6-v2.onnx")
        if (externalPath.exists()) return externalPath.absolutePath

        // 回退到打包的资源 -- 复制到缓存供 ORT 使用
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

### 嵌入序列化

嵌入向量（384 个浮点数）以 BLOB 形式存储在 Room 中：

```kotlin
/**
 * 嵌入向量序列化/反序列化工具。
 * 位置：feature/memory/embedding/EmbeddingSerializer.kt
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

## 混合搜索实现

### BM25 评分器

```kotlin
/**
 * 记忆搜索的 BM25 关键词评分。
 * 位置：feature/memory/search/BM25Scorer.kt
 */
class BM25Scorer {
    private val k1 = 1.2f   // 词频饱和度
    private val b = 0.75f   // 长度归一化

    /**
     * 使用 BM25 对所有块进行查询评分。
     * 返回 chunkId -> BM25 分数的映射。
     */
    fun score(
        query: String,
        chunks: List<MemoryIndexEntity>
    ): Map<String, Float> {
        val queryTerms = tokenize(query)
        val avgDocLength = chunks.map { tokenize(it.chunkText).size }.average().toFloat()
        val n = chunks.size

        // 每个查询词的 IDF
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

### 向量搜索器

```kotlin
/**
 * 使用余弦距离的向量相似度搜索。
 * 位置：feature/memory/search/VectorSearcher.kt
 */
class VectorSearcher(
    private val embeddingEngine: EmbeddingEngine
) {
    /**
     * 使用余弦相似度对所有块进行查询评分。
     * 返回 chunkId -> 余弦相似度分数的映射。
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

### 时间衰减

```kotlin
/**
 * 记忆搜索评分的时间衰减计算器。
 * 位置：feature/memory/search/TimeDecayCalculator.kt
 */
object TimeDecayCalculator {
    /**
     * 计算时间衰减因子。
     * 返回 0 到 1 之间的值，其中 1 = 今天，随时间衰减。
     *
     * 公式：decay = exp(-lambda * ageInDays)
     * 当 lambda = 0.01 时，半衰期约为 69 天。
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
 * 结合 BM25 和向量搜索的混合搜索引擎，带时间衰减。
 * 位置：feature/memory/search/HybridSearchEngine.kt
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
     * 对记忆索引执行混合搜索。
     *
     * 1. 从 Room 加载所有已索引的块
     * 2. 计算 BM25 分数
     * 3. 计算向量相似度分数（如果嵌入引擎可用）
     * 4. 将每组分数归一化到 [0, 1]
     * 5. 组合：score = BM25_WEIGHT * bm25 + VECTOR_WEIGHT * vector
     * 6. 应用时间衰减：finalScore = score * timeDecay
     * 7. 返回 Top-K 结果
     */
    suspend fun search(
        query: String,
        topK: Int = DEFAULT_TOP_K
    ): List<MemorySearchResult> = withContext(Dispatchers.IO) {
        val chunks = memoryIndexDao.getAll()
        if (chunks.isEmpty()) return@withContext emptyList()

        // BM25 分数
        val bm25Scores = bm25Scorer.score(query, chunks)

        // 向量分数（如果嵌入引擎不可用则为空）
        val vectorScores = if (embeddingEngine.isAvailable()) {
            vectorSearcher.score(query, chunks)
        } else {
            emptyMap()
        }

        // 将分数归一化到 [0, 1]
        val normalizedBm25 = normalize(bm25Scores)
        val normalizedVector = normalize(vectorScores)

        // 确定有效权重
        val effectiveBm25Weight: Float
        val effectiveVectorWeight: Float
        if (vectorScores.isEmpty()) {
            // 回退：仅 BM25
            effectiveBm25Weight = 1.0f
            effectiveVectorWeight = 0.0f
        } else {
            effectiveBm25Weight = BM25_WEIGHT
            effectiveVectorWeight = VECTOR_WEIGHT
        }

        // 组合和排序
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

## 每日日志提取流程

### DailyLogWriter

```kotlin
/**
 * 处理每日日志提取和写入。
 * 位置：feature/memory/log/DailyLogWriter.kt
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
     * 从会话中提取并摘要未处理的消息到每日日志。
     *
     * 流程：
     * 1. 加载会话及其 lastLoggedMessageId
     * 2. 获取 lastLoggedMessageId 之后的消息
     * 3. 如果没有新消息，提前返回
     * 4. 用新消息构建摘要提示词
     * 5. 调用 AI 模型（非流式）进行摘要
     * 6. 解析响应为：daily_summary + long_term_facts
     * 7. 将 daily_summary 追加到今天的每日日志文件
     * 8. 将 long_term_facts 追加到 MEMORY.md（如有）
     * 9. 索引新的块（嵌入 + 存入 Room）
     * 10. 更新会话的 lastLoggedMessageId
     */
    suspend fun writeDailyLog(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found"))

            // 获取最后一次记录之后的新消息
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

            // 过滤有意义的消息（仅 USER 和 AI_RESPONSE）
            val meaningfulMessages = newMessages.filter {
                it.type == MessageType.USER || it.type == MessageType.AI_RESPONSE
            }
            if (meaningfulMessages.isEmpty()) {
                // 即使没有有意义的消息也更新指针
                sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)
                return@withContext Result.success(Unit)
            }

            // 解析用于摘要的模型
            val agent = agentRepository.getAgentById(session.currentAgentId)
                ?: return@withContext Result.failure(Exception("Agent not found"))
            val (model, provider) = resolveModel(agent)
                ?: return@withContext Result.failure(Exception("No model available"))
            val apiKey = apiKeyStorage.getApiKey(provider.id)
                ?: return@withContext Result.failure(Exception("No API key"))

            // 构建摘要提示词
            val conversationText = meaningfulMessages.joinToString("\n") { msg ->
                val role = if (msg.type == MessageType.USER) "User" else "Assistant"
                "$role: ${msg.content}"
            }
            val prompt = buildSummarizationPrompt(conversationText)

            // 调用模型进行摘要（非流式）
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

            // 解析响应
            val (dailySummary, longTermFacts) = parseSummarizationResponse(response)

            // 写入每日日志
            val today = LocalDate.now().toString() // "2026-02-28"
            if (dailySummary.isNotBlank()) {
                memoryFileStorage.appendToDailyLog(today, dailySummary)
                indexChunks(dailySummary, "daily_log", today)
            }

            // 提升长期事实
            if (longTermFacts.isNotBlank()) {
                longTermMemoryManager.appendMemory(longTermFacts)
                indexChunks(longTermFacts, "long_term", null)
            }

            // 更新 lastLoggedMessageId
            sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write daily log for session $sessionId", e)
            Result.failure(e)
        }
    }

    private suspend fun indexChunks(text: String, sourceType: String, sourceDate: String?) {
        // 按段落拆分为块
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

### 摘要提示词设计

摘要提示词要求模型产生两个明确分隔的区段：

```
## Daily Summary
- 关键主题1：简要描述
- 做出的决策：决策内容
- 完成的任务：完成内容

## Long-term Facts
- 用户偏好简洁的回答
- 用户的项目名为 "ProjectX"，使用 Kotlin
```

该结构由 `parseSummarizationResponse()` 解析，将每日内容与长期事实分离。

## 长期记忆管理

### LongTermMemoryManager

```kotlin
/**
 * 管理 MEMORY.md 长期记忆文件。
 * 位置：feature/memory/longterm/LongTermMemoryManager.kt
 */
class LongTermMemoryManager(
    private val memoryFileStorage: MemoryFileStorage
) {
    /**
     * 读取 MEMORY.md 的完整内容。
     * 如果文件不存在则返回空字符串。
     */
    suspend fun readMemory(): String = withContext(Dispatchers.IO) {
        memoryFileStorage.readMemoryFile() ?: ""
    }

    /**
     * 追加内容到 MEMORY.md。
     * 如果文件不存在则创建。
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
     * 覆盖 MEMORY.md 的全部内容（用于用户手动编辑）。
     */
    suspend fun writeMemory(fullContent: String) = withContext(Dispatchers.IO) {
        memoryFileStorage.writeMemoryFile(fullContent)
    }

    /**
     * 获取用于系统提示注入的内容。
     * 最多返回前 [maxLines] 行。
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
 * 处理记忆 Markdown 文件的文件 I/O。
 * 位置：feature/memory/storage/MemoryFileStorage.kt
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
     * 读取 MEMORY.md 内容。如果文件不存在返回 null。
     */
    fun readMemoryFile(): String? {
        return if (memoryFile.exists()) memoryFile.readText() else null
    }

    /**
     * 将完整内容写入 MEMORY.md。
     */
    fun writeMemoryFile(content: String) {
        memoryFile.writeText(content)
    }

    /**
     * 追加内容到每日日志文件。
     * 如果文件不存在则创建并添加标题。
     */
    fun appendToDailyLog(date: String, content: String) {
        val file = File(dailyLogDir, "$date.md")
        if (!file.exists()) {
            file.writeText("# Daily Log - $date\n\n")
        }
        file.appendText("$content\n\n---\n\n")
    }

    /**
     * 读取每日日志文件。如果不存在返回 null。
     */
    fun readDailyLog(date: String): String? {
        val file = File(dailyLogDir, "$date.md")
        return if (file.exists()) file.readText() else null
    }

    /**
     * 列出所有每日日志日期（降序排列）。
     */
    fun listDailyLogDates(): List<String> {
        return dailyLogDir.listFiles()
            ?.filter { it.extension == "md" }
            ?.map { it.nameWithoutExtension }
            ?.sortedDescending()
            ?: emptyList()
    }

    /**
     * 获取所有记忆文件的总大小（字节）。
     */
    fun getTotalSize(): Long {
        return memoryDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /**
     * 获取每日日志文件数量。
     */
    fun getDailyLogCount(): Int {
        return dailyLogDir.listFiles()?.count { it.extension == "md" } ?: 0
    }
}
```

## 记忆注入

### MemoryInjector

```kotlin
/**
 * 构建用于系统提示的记忆注入块。
 * 位置：feature/memory/injection/MemoryInjector.kt
 */
class MemoryInjector(
    private val hybridSearchEngine: HybridSearchEngine,
    private val longTermMemoryManager: LongTermMemoryManager
) {
    companion object {
        const val DEFAULT_TOKEN_BUDGET = 2000
        const val CHARS_PER_TOKEN_ESTIMATE = 4  // 用于预算执行的粗略估计
    }

    /**
     * 构建系统提示的记忆注入内容。
     *
     * 结构：
     * ```
     * ## Long-term Memory
     * [MEMORY.md 内容，前 200 行]
     *
     * ## Relevant Memories
     * [Top-K 搜索结果，带来源标注]
     * ```
     *
     * 如果没有可用的记忆内容则返回空字符串。
     */
    suspend fun buildInjection(
        query: String,
        tokenBudget: Int = DEFAULT_TOKEN_BUDGET
    ): String {
        val charBudget = tokenBudget * CHARS_PER_TOKEN_ESTIMATE
        val builder = StringBuilder()

        // 始终包含 MEMORY.md 内容
        val memoryContent = longTermMemoryManager.getInjectionContent(maxLines = 200)
        if (memoryContent.isNotBlank()) {
            builder.appendLine("## Long-term Memory")
            builder.appendLine(memoryContent)
            builder.appendLine()
        }

        // 搜索相关记忆
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

### 与 SendMessageUseCase 集成

在消息发送流程中调用 `MemoryInjector` 来丰富系统提示：

```kotlin
/**
 * 对 SendMessageUseCase.execute() 的修改
 *
 * 在向模型发送消息之前，将记忆上下文注入到系统提示中。
 */
// 在 SendMessageUseCase 中：

suspend fun execute(
    sessionId: String,
    userText: String,
    agentId: String
): Flow<ChatEvent> = flow {
    // ... 现有的设置代码 ...

    // 解析 Agent 及其系统提示
    val agent = agentRepository.getAgentById(agentId) ?: /* error */
    var systemPrompt = agent.systemPrompt ?: ""

    // --- 新增：记忆注入 ---
    val memoryInjection = memoryInjector.buildInjection(query = userText)
    if (memoryInjection.isNotBlank()) {
        systemPrompt = if (systemPrompt.isBlank()) {
            memoryInjection
        } else {
            "$systemPrompt\n\n$memoryInjection"
        }
    }
    // --- 新增结束 ---

    // ... 后续现有流程（发送到模型、处理流式响应、工具调用等）...
}
```

## 触发机制

### MemoryTriggerManager

```kotlin
/**
 * 协调记忆触发事件。
 * 位置：feature/memory/trigger/MemoryTriggerManager.kt
 */
class MemoryTriggerManager(
    private val memoryManager: MemoryManager,
    private val sessionRepository: SessionRepository
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val mutex = Mutex()

    /**
     * 应用进入后台时调用。
     * 通过 Application 类中的 ProcessLifecycleOwner 注册。
     */
    fun onAppBackground() {
        scope.launch {
            flushActiveSession()
        }
    }

    /**
     * 用户从一个会话切换到另一个会话时调用。
     */
    fun onSessionSwitch(previousSessionId: String) {
        scope.launch {
            flushSession(previousSessionId)
        }
    }

    /**
     * 活跃会话期间日期变更时调用。
     * 通过定期检查或系统广播检测。
     */
    fun onDayChange(activeSessionId: String) {
        scope.launch {
            flushSession(activeSessionId)
        }
    }

    /**
     * FEAT-011 自动压缩消息历史之前调用。
     * 这是压缩前刷写的集成点。
     */
    suspend fun onPreCompaction(sessionId: String) {
        flushSession(sessionId)
    }

    /**
     * 会话结束时调用（用户主动关闭）。
     */
    fun onSessionEnd(sessionId: String) {
        scope.launch {
            flushSession(sessionId)
        }
    }

    private suspend fun flushSession(sessionId: String) {
        // 使用互斥锁防止同一会话的并发刷写
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

### ProcessLifecycleOwner 注册

```kotlin
/**
 * 在 Application 类中注册应用后台触发器。
 * 添加到 OneclawApplication.kt：
 */
class OneclawApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // ... 现有初始化 ...

        // 注册记忆触发器用于应用进入后台
        val memoryTriggerManager: MemoryTriggerManager = get() // Koin 注入
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStop(owner: LifecycleOwner) {
                    // 应用进入后台
                    memoryTriggerManager.onAppBackground()
                }
            }
        )
    }
}
```

## MemoryManager（外观）

```kotlin
/**
 * 所有记忆操作的顶层协调器。
 * 位置：feature/memory/MemoryManager.kt
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
     * 刷写会话的每日日志。
     * 由触发机制调用。
     */
    suspend fun flushDailyLog(sessionId: String): Result<Unit> {
        return dailyLogWriter.writeDailyLog(sessionId)
    }

    /**
     * 搜索相关记忆内容。
     */
    suspend fun searchMemory(query: String, topK: Int = 5): List<MemorySearchResult> {
        return hybridSearchEngine.search(query, topK)
    }

    /**
     * 获取用于系统提示的注入内容。
     */
    suspend fun getInjectionContent(query: String, tokenBudget: Int = 2000): String {
        return memoryInjector.buildInjection(query, tokenBudget)
    }

    /**
     * 从文件重建整个搜索索引。
     * 用于索引损坏或手动编辑后。
     */
    suspend fun rebuildIndex() = withContext(Dispatchers.IO) {
        memoryIndexDao.deleteAll()

        // 重新索引所有每日日志
        for (date in memoryFileStorage.listDailyLogDates()) {
            val content = memoryFileStorage.readDailyLog(date) ?: continue
            indexContent(content, "daily_log", date)
        }

        // 重新索引 MEMORY.md
        val memoryContent = memoryFileStorage.readMemoryFile()
        if (memoryContent != null) {
            indexContent(memoryContent, "long_term", null)
        }
    }

    /**
     * 获取记忆统计信息。
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
 * 记忆统计信息数据类。
 */
data class MemoryStats(
    val dailyLogCount: Int,
    val totalSizeBytes: Long,
    val indexedChunkCount: Int,
    val embeddingModelLoaded: Boolean
)
```

## 依赖注入

### MemoryModule (Koin)

```kotlin
/**
 * 记忆系统依赖的 Koin 模块。
 * 位置：di/MemoryModule.kt
 *
 * 在 Koin 应用配置中与现有模块一起注册此模块
 *（AppModule、FeatureModule 等）。
 */
val memoryModule = module {
    // 数据层
    single { MemoryFileStorage(androidContext()) }
    single { EmbeddingEngine(androidContext()) }

    // 搜索组件
    factory { BM25Scorer() }
    factory { VectorSearcher(get()) }
    factory { HybridSearchEngine(get(), get(), get(), get()) }

    // 领域层
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

    // 触发管理器
    single { MemoryTriggerManager(get(), get()) }
}
```

### 数据库迁移

将 `MemoryIndexDao` 添加到现有的 `AppDatabase`：

```kotlin
/**
 * 添加到 AppDatabase (core/database/AppDatabase.kt)：
 * 1. 将 MemoryIndexEntity 添加到 @Database entities 列表
 * 2. 添加 MemoryIndexDao 抽象函数
 * 3. 递增数据库版本
 * 4. 添加迁移
 */
@Database(
    entities = [
        // ... 现有实体 ...,
        MemoryIndexEntity::class
    ],
    version = N + 1  // 递增版本
)
abstract class AppDatabase : RoomDatabase() {
    // ... 现有 DAO ...
    abstract fun memoryIndexDao(): MemoryIndexDao
}

// 迁移：
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

### DatabaseModule 更新

```kotlin
/**
 * 添加到 DatabaseModule (di/DatabaseModule.kt)：
 */
single { get<AppDatabase>().memoryIndexDao() }
```

## 压缩前刷写集成（FEAT-011）

当 FEAT-011 自动压缩实现时，它会在压缩消息之前调用记忆系统：

```kotlin
/**
 * FEAT-011 自动压缩的集成点。
 * 在自动压缩用例压缩会话的消息历史之前调用。
 *
 * 未来自动压缩流程的伪代码：
 */
class AutoCompactUseCase(
    private val memoryTriggerManager: MemoryTriggerManager,
    // ... 其他依赖 ...
) {
    suspend fun compact(sessionId: String) {
        // 步骤1：压缩前刷写记忆
        memoryTriggerManager.onPreCompaction(sessionId)

        // 步骤2：继续消息压缩
        // ... 压缩消息 ...
    }
}
```

这确保了在原始消息历史被压缩之前，重要的上下文已被保存到记忆系统中。

## 实现阶段

### 第一阶段：基础设施（记忆文件 + 基本基础设施）
1. [ ] 创建 `MemoryFileStorage`，支持 MEMORY.md 和每日日志的读写
2. [ ] 创建 `LongTermMemoryManager`
3. [ ] 向 Room 数据库添加 `MemoryIndexEntity` 和 `MemoryIndexDao`
4. [ ] 向 Session 模型、实体和 DAO 添加 `lastLoggedMessageId` 字段
5. [ ] 数据库迁移
6. [ ] 创建 `MemoryModule`（Koin），包含基本注册
7. [ ] 文件存储和长期记忆管理器的单元测试

### 第二阶段：每日日志写入
1. [ ] 实现 `DailyLogWriter`，包含摘要提示词
2. [ ] 实现 `MemoryTriggerManager`，包含所有触发钩子
3. [ ] 在 Application 类中注册 `ProcessLifecycleOwner` 观察者
4. [ ] 在 ChatViewModel 中接入会话切换和会话结束触发器
5. [ ] 实现日期变更检测
6. [ ] 每日日志提取和解析的单元测试
7. [ ] 集成测试：完整的每日日志流程

### 第三阶段：嵌入引擎
1. [ ] 集成 ONNX Runtime 依赖
2. [ ] 获取并打包量化的 MiniLM ONNX 模型
3. [ ] 实现 `EmbeddingEngine`，支持惰性初始化
4. [ ] 实现 `EmbeddingSerializer`
5. [ ] 实现 `BertTokenizer`（或使用库）
6. [ ] 嵌入生成和序列化的单元测试
7. [ ] 在目标设备上的性能基准测试

### 第四阶段：搜索 + 注入
1. [ ] 实现 `BM25Scorer`
2. [ ] 实现 `VectorSearcher`
3. [ ] 实现 `TimeDecayCalculator`
4. [ ] 实现 `HybridSearchEngine`
5. [ ] 实现 `MemoryInjector`
6. [ ] 实现 `MemoryManager` 外观
7. [ ] 将 `MemoryInjector` 集成到 `SendMessageUseCase`
8. [ ] 搜索评分和排序的单元测试
9. [ ] 集成测试：端到端记忆检索

### 第五阶段：UI + 完善
1. [ ] 创建记忆设置界面（MEMORY.md 编辑器、每日日志浏览器、统计信息）
2. [ ] 添加"重建索引"功能
3. [ ] 将设置界面接入导航
4. [ ] 完整流程的手动测试
5. [ ] 性能优化（后台线程、惰性加载）
6. [ ] 边界情况处理（空记忆、模型不可用、磁盘已满）

## 测试策略

### 单元测试
| 组件 | 测试重点 |
|------|---------|
| `MemoryFileStorage` | 文件创建、读取、追加、列出、边界情况（目录缺失） |
| `LongTermMemoryManager` | 读写追加 MEMORY.md、注入内容截断 |
| `BM25Scorer` | 评分准确性、分词、空输入、中日韩文本 |
| `VectorSearcher` | 余弦相似度计算、处理空嵌入 |
| `TimeDecayCalculator` | 不同天数的衰减值、边界情况（未来日期） |
| `HybridSearchEngine` | 分数合并、归一化、排序、无嵌入时的回退 |
| `EmbeddingSerializer` | 序列化/反序列化往返一致性 |
| `MemoryInjector` | 格式化输出、token 预算执行、空记忆 |
| `DailyLogWriter` | 响应解析、消息过滤、lastLoggedMessageId 追踪 |

### 集成测试
| 测试 | 描述 |
|------|------|
| 每日日志流程 | 创建含消息的会话 -> 触发刷写 -> 验证每日日志文件创建 |
| 记忆注入 | 插入测试记忆 -> 发送消息 -> 验证系统提示包含记忆 |
| 混合搜索准确性 | 索引已知块 -> 查询 -> 验证正确的块排名最高 |
| 重建索引 | 删除索引 -> 重建 -> 验证所有文件重新索引 |
| 并发触发 | 快速触发多个触发器 -> 验证无重复或损坏 |

### 性能测试
| 测试 | 目标 |
|------|------|
| 嵌入延迟 | Pixel 6 上 < 200ms |
| 搜索延迟（100 块） | < 200ms |
| 搜索延迟（1000 块） | < 500ms |
| 每日日志写入（50 条消息） | < 5s（主要受 API 调用影响） |
| 模型加载（冷启动） | < 3s |
| 内存占用（模型已加载） | < 100MB |

## 风险与应对

| 风险 | 影响 | 概率 | 应对措施 |
|------|------|------|---------|
| 嵌入模型对 APK 大小影响过大 | 高 | 中 | 使用 INT8 量化模型（约 22MB）；或首次启动时下载 |
| ONNX Runtime 在旧设备上的兼容性问题 | 中 | 低 | 最低 API 26 已确保 ARM64 支持；在低端设备上测试 |
| 摘要 API 调用成本 | 低 | 高 | 每日日志写入不频繁；使用用户现有的 API key |
| 搜索延迟随记忆量增长 | 中 | 中 | 限制索引扫描范围；未来考虑使用 SQLite FTS 优化 BM25 |
| 并发文件写入导致 Markdown 文件损坏 | 中 | 低 | MemoryTriggerManager 中的互斥锁防止并发写入 |
| 摘要时模型不可用 | 中 | 中 | 优雅跳过每日日志；下次触发时重试 |

## 考虑过的替代方案

### 方案 A：MemoryOS 风格（重量级、结构化）
更复杂的系统，包含独立的全局记忆、会话记忆和工作记忆层，每层都有自己的存储格式和生命周期管理。

- **优点**：更结构化，控制粒度更细
- **缺点**：实现复杂度高得多，维护负担大，对移动应用来说过度设计
- **未选择原因**：OpenClaw 风格方案（方案 B，即本 RFC 描述的方案）为移动应用提供了简单性与能力的适当平衡。

### 方案 B：基于 API 的嵌入（云端）
使用 AI provider 的嵌入 API 而非本地模型。

- **优点**：无需打包模型，嵌入质量可能更高
- **缺点**：需要网络，每次嵌入调用产生费用，隐私问题
- **未选择原因**：用户确认使用纯本地嵌入，以保持零后端依赖和用户数据控制。

### 方案 C：无向量搜索（仅 BM25）
完全跳过嵌入模型，只使用 BM25 关键词搜索。

- **优点**：大幅简化，无需打包模型，更快
- **缺点**：会错过语义匹配（例如"最喜欢的颜色"不会匹配到"我喜欢蓝色"）
- **未选择原因**：混合搜索显著提升召回质量。本地模型方案在保持纯本地运行的同时增加了语义理解能力。

## 未来扩展

- 按 Agent 隔离的记忆（每个 Agent 有自己的 MEMORY.md 和每日日志）
- SQLite FTS5 用于更高效的大规模 BM25 评分
- 增量索引更新（仅重新嵌入变更的块）
- 基于记忆的自动补全建议
- 首次启动下载量化模型而非打包
- 与 FEAT-007 集成实现记忆文件云备份
- 用户可用的记忆搜索工具（Agent 可调用）

## 开放问题

- [ ] 具体使用哪个量化 ONNX 模型（all-MiniLM-L6-v2-int8 vs 其他）
- [ ] 是否在 APK 中打包模型或首次启动时下载（APK 大小权衡）
- [ ] Token 预算默认值（2000 tokens）-- 需要基于实际使用模式调优
- [ ] `ModelApiAdapter` 上是否存在 `sendNonStreaming()` 方法或需要添加

## 参考资料

- [ONNX Runtime for Android](https://onnxruntime.ai/docs/get-started/with-java.html)
- [all-MiniLM-L6-v2 on Hugging Face](https://huggingface.co/sentence-transformers/all-MiniLM-L6-v2)
- [BM25 算法](https://en.wikipedia.org/wiki/Okapi_BM25)
- [OpenClaw 记忆系统](https://github.com/anthropics/claude-code)（灵感来源）

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|---------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
