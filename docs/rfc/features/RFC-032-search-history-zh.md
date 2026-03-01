# RFC-032: 搜索历史工具

## 文档信息
- **RFC ID**: RFC-032
- **关联 PRD**: [FEAT-032（搜索历史工具）](../../prd/features/FEAT-032-search-history.md)
- **关联架构**: [RFC-000（整体架构）](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**: [RFC-004（工具系统）](RFC-004-tool-system.md)、[RFC-013（记忆系统）](RFC-013-agent-memory.md)
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClawShadow 的 AI Agent 通过记忆系统（MEMORY.md、每日日志）和原始消息历史，在多轮对话中持续积累用户相关的知识。然而，当用户主动询问时，Agent 目前无法在这些已积累的知识中主动检索。例如，当用户问"我上周提到的那家餐厅是什么？"时，Agent 无法查找到相关信息。

记忆系统中已有一个 `HybridSearchEngine`，可对已索引的记忆片段执行结合 BM25 关键词搜索、向量相似度搜索与时间衰减的混合检索。本 RFC 在此基础上新增一个 `search_history` 工具，供 AI 模型调用，跨记忆内容、消息内容和会话元数据进行搜索。

### 目标

1. 在 `tool/builtin/` 中实现 `SearchHistoryTool.kt`（Kotlin 内置工具）
2. 实现 `SearchHistoryUseCase.kt`，编排跨三个数据源的搜索逻辑
3. 定义 `UnifiedSearchResult.kt` 作为合并后的结果模型
4. 在 `MessageDao` 中新增 `searchContent()` 查询方法
5. 在 `SessionDao` 中新增 `searchByTitleOrPreview()` 查询方法
6. 在 `ToolModule` 中注册该工具

### 非目标

- 对消息或会话进行全文搜索（FTS）索引
- 对原始消息内容进行语义/向量搜索（仅记忆索引使用 embedding）
- 搜索结果的缓存或持久化
- 在聊天界面之外提供搜索结果浏览 UI
- 支持正则表达式或高级查询语法
- 跨会话的上下文关联

## 技术设计

### 架构概览

```
+-----------------------------------------------------------------+
|                     Chat Layer (RFC-001)                          |
|  SendMessageUseCase                                              |
|       |                                                          |
|       |  tool call: search_history(query="restaurant")           |
|       v                                                          |
+------------------------------------------------------------------+
|                   Tool Execution Engine (RFC-004)                  |
|  executeTool(name, params, availableToolIds)                      |
|       |                                                           |
|       v                                                           |
|  +--------------------------------------------------------------+ |
|  |                    ToolRegistry                                | |
|  |  +------------------------+                                   | |
|  |  |    search_history       |  Kotlin built-in [NEW]           | |
|  |  |  (SearchHistoryTool.kt) |                                  | |
|  |  +-----------+------------+                                   | |
|  |              |                                                 | |
|  |              v                                                 | |
|  |  +------------------------------------------------------+    | |
|  |  |            SearchHistoryUseCase [NEW]                  |    | |
|  |  |                                                        |    | |
|  |  |  1. Parse scope & date filters                         |    | |
|  |  |  2. Search memory index (HybridSearchEngine)           |    | |
|  |  |  3. Search message content (MessageDao)                |    | |
|  |  |  4. Search session metadata (SessionDao)               |    | |
|  |  |  5. Normalize, weight, merge, deduplicate              |    | |
|  |  |  6. Return ranked UnifiedSearchResult list             |    | |
|  |  +------------------------------------------------------+    | |
|  |       |              |                |                        | |
|  |       v              v                v                        | |
|  |  HybridSearch   MessageDao      SessionDao                    | |
|  |  Engine         .searchContent  .searchByTitle                | |
|  |  (existing)     (new query)     OrPreview                     | |
|  |                                 (new query)                    | |
|  +--------------------------------------------------------------+ |
+-------------------------------------------------------------------+
```

### 核心组件

**新增：**
1. `SearchHistoryTool` -- Kotlin 内置工具，负责参数解析和输出格式化
2. `SearchHistoryUseCase` -- 业务逻辑层，编排多数据源搜索
3. `UnifiedSearchResult` -- 数据类，表示合并后的单条搜索结果

**修改：**
4. `MessageDao` -- 新增 `searchContent()` LIKE 查询
5. `SessionDao` -- 新增 `searchByTitleOrPreview()` LIKE 查询
6. `ToolModule` -- 注册 `SearchHistoryTool`

## 详细设计

### 目录结构（新增及修改文件）

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   └── builtin/
│   │       ├── SearchHistoryTool.kt        # NEW
│   │       ├── WebfetchTool.kt            # unchanged
│   │       ├── BrowserTool.kt             # unchanged
│   │       └── ...
│   ├── feature/
│   │   └── search/
│   │       ├── usecase/
│   │       │   └── SearchHistoryUseCase.kt # NEW
│   │       └── model/
│   │           └── UnifiedSearchResult.kt  # NEW
│   ├── data/
│   │   └── local/
│   │       └── dao/
│   │           ├── MessageDao.kt           # MODIFIED (add searchContent)
│   │           └── SessionDao.kt           # MODIFIED (add searchByTitleOrPreview)
│   └── di/
│       └── ToolModule.kt                   # MODIFIED (register SearchHistoryTool)

app/src/test/kotlin/com/oneclaw/shadow/
    ├── tool/
    │   └── builtin/
    │       └── SearchHistoryToolTest.kt     # NEW
    └── feature/
        └── search/
            └── usecase/
                └── SearchHistoryUseCaseTest.kt  # NEW
```

### UnifiedSearchResult

```kotlin
/**
 * Located in: feature/search/model/UnifiedSearchResult.kt
 *
 * A single search result from the unified multi-source search.
 * Results from different data sources (memory index, messages, sessions)
 * are normalized into this common format for ranking and display.
 */
data class UnifiedSearchResult(
    val id: String,
    val text: String,                // Excerpt of the matching content (max 500 chars)
    val sourceType: SourceType,      // Which data source this came from
    val sourceDate: String?,         // Date associated with this result (YYYY-MM-DD)
    val sessionTitle: String?,       // Session title (for message/session results)
    val rawScore: Float,             // Score from the source search (before normalization)
    val finalScore: Float,           // Final score after normalization, weighting, time decay
    val createdAt: Long              // Epoch millis of the original content
) {
    enum class SourceType(val label: String) {
        MEMORY("memory"),
        DAILY_LOG("daily_log"),
        MESSAGE("message"),
        SESSION("session")
    }
}
```

### MessageDao 变更

```kotlin
/**
 * Added to: data/local/dao/MessageDao.kt
 *
 * Search message content using SQL LIKE. Returns messages whose content
 * contains the query string (case-insensitive). Results are ordered by
 * creation time descending (newest first).
 *
 * Optional date range filtering via createdAfter and createdBefore
 * (epoch millis). Pass 0 for createdAfter and Long.MAX_VALUE for
 * createdBefore to disable date filtering.
 */
@Query(
    """
    SELECT * FROM messages
    WHERE content LIKE '%' || :query || '%' COLLATE NOCASE
      AND type IN ('USER', 'AI_RESPONSE')
      AND created_at >= :createdAfter
      AND created_at <= :createdBefore
    ORDER BY created_at DESC
    LIMIT :limit
    """
)
suspend fun searchContent(
    query: String,
    createdAfter: Long = 0,
    createdBefore: Long = Long.MAX_VALUE,
    limit: Int = 50
): List<MessageEntity>
```

### SessionDao 变更

```kotlin
/**
 * Added to: data/local/dao/SessionDao.kt
 *
 * Search sessions by title or last message preview using SQL LIKE.
 * Only searches non-deleted sessions. Returns sessions whose title
 * or preview contains the query string (case-insensitive).
 *
 * Optional date range filtering via createdAfter and createdBefore
 * (epoch millis).
 */
@Query(
    """
    SELECT * FROM sessions
    WHERE deleted_at IS NULL
      AND (title LIKE '%' || :query || '%' COLLATE NOCASE
           OR last_message_preview LIKE '%' || :query || '%' COLLATE NOCASE)
      AND created_at >= :createdAfter
      AND created_at <= :createdBefore
    ORDER BY updated_at DESC
    LIMIT :limit
    """
)
suspend fun searchByTitleOrPreview(
    query: String,
    createdAfter: Long = 0,
    createdBefore: Long = Long.MAX_VALUE,
    limit: Int = 20
): List<SessionEntity>
```

### SearchHistoryUseCase

```kotlin
/**
 * Located in: feature/search/usecase/SearchHistoryUseCase.kt
 *
 * Orchestrates searching across multiple data sources:
 * 1. Memory index via HybridSearchEngine (BM25 + vector + time decay)
 * 2. Message content via MessageDao.searchContent() (SQL LIKE)
 * 3. Session metadata via SessionDao.searchByTitleOrPreview() (SQL LIKE)
 *
 * Results are normalized, weighted by source, and merged into a single
 * ranked list of UnifiedSearchResult.
 */
class SearchHistoryUseCase(
    private val hybridSearchEngine: HybridSearchEngine,
    private val messageDao: MessageDao,
    private val sessionDao: SessionDao
) {
    companion object {
        private const val MEMORY_WEIGHT = 1.0f
        private const val MESSAGE_WEIGHT = 0.6f
        private const val SESSION_WEIGHT = 0.5f
        private const val MAX_EXCERPT_LENGTH = 500
        private const val DEDUP_OVERLAP_THRESHOLD = 0.8f
    }

    /**
     * Search across data sources based on scope.
     *
     * @param query       Search keywords
     * @param scope       Which data sources to search ("all", "memory", "daily_log", "sessions")
     * @param dateFrom    Optional start date epoch millis (inclusive)
     * @param dateTo      Optional end date epoch millis (inclusive, end of day)
     * @param maxResults  Maximum results to return
     */
    suspend fun search(
        query: String,
        scope: String,
        dateFrom: Long?,
        dateTo: Long?,
        maxResults: Int
    ): List<UnifiedSearchResult> {
        val createdAfter = dateFrom ?: 0L
        val createdBefore = dateTo ?: Long.MAX_VALUE

        val allResults = mutableListOf<UnifiedSearchResult>()

        // 1. Search memory index (if scope includes it)
        if (scope in listOf("all", "memory", "daily_log")) {
            val memoryResults = searchMemoryIndex(query, scope, createdAfter, createdBefore)
            allResults.addAll(memoryResults)
        }

        // 2. Search message content (if scope includes it)
        if (scope in listOf("all", "sessions")) {
            val messageResults = searchMessages(query, createdAfter, createdBefore)
            allResults.addAll(messageResults)
        }

        // 3. Search session metadata (if scope includes it)
        if (scope in listOf("all", "sessions")) {
            val sessionResults = searchSessions(query, createdAfter, createdBefore)
            allResults.addAll(sessionResults)
        }

        // 4. Deduplicate and rank
        return deduplicate(allResults)
            .sortedByDescending { it.finalScore }
            .take(maxResults)
    }

    private suspend fun searchMemoryIndex(
        query: String,
        scope: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        // Use a generous topK since we'll filter and re-rank
        val results = hybridSearchEngine.search(query, topK = 50)

        return results
            .filter { result ->
                // Date filtering
                val chunkDate = result.sourceDate
                if (chunkDate != null) {
                    val chunkEpoch = dateStringToEpoch(chunkDate)
                    chunkEpoch in createdAfter..createdBefore
                } else {
                    // MEMORY.md chunks have no date, include unless date filtering is strict
                    createdAfter == 0L
                }
            }
            .filter { result ->
                // Scope filtering for daily_log
                if (scope == "daily_log") {
                    result.sourceType == "daily_log"
                } else {
                    true
                }
            }
            .map { result ->
                val sourceType = if (result.sourceType == "daily_log") {
                    UnifiedSearchResult.SourceType.DAILY_LOG
                } else {
                    UnifiedSearchResult.SourceType.MEMORY
                }

                UnifiedSearchResult(
                    id = "mem_${result.chunkId}",
                    text = result.chunkText.take(MAX_EXCERPT_LENGTH),
                    sourceType = sourceType,
                    sourceDate = result.sourceDate,
                    sessionTitle = null,
                    rawScore = result.score,
                    finalScore = result.score * MEMORY_WEIGHT,
                    createdAt = result.sourceDate?.let { dateStringToEpoch(it) }
                        ?: System.currentTimeMillis()
                )
            }
    }

    private suspend fun searchMessages(
        query: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        val messages = messageDao.searchContent(
            query = query,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            limit = 50
        )

        if (messages.isEmpty()) return emptyList()

        // Score by recency (newest = 1.0, oldest = 0.1)
        val newestTime = messages.first().createdAt.toFloat()
        val oldestTime = messages.last().createdAt.toFloat()
        val timeRange = (newestTime - oldestTime).coerceAtLeast(1f)

        return messages.map { msg ->
            val recencyScore = 0.1f + 0.9f * ((msg.createdAt - oldestTime) / timeRange)

            UnifiedSearchResult(
                id = "msg_${msg.id}",
                text = msg.content.take(MAX_EXCERPT_LENGTH),
                sourceType = UnifiedSearchResult.SourceType.MESSAGE,
                sourceDate = epochToDateString(msg.createdAt),
                sessionTitle = null, // Could be enriched by joining with sessions
                rawScore = recencyScore,
                finalScore = recencyScore * MESSAGE_WEIGHT,
                createdAt = msg.createdAt
            )
        }
    }

    private suspend fun searchSessions(
        query: String,
        createdAfter: Long,
        createdBefore: Long
    ): List<UnifiedSearchResult> {
        val sessions = sessionDao.searchByTitleOrPreview(
            query = query,
            createdAfter = createdAfter,
            createdBefore = createdBefore,
            limit = 20
        )

        if (sessions.isEmpty()) return emptyList()

        // Score by recency
        val newestTime = sessions.first().updatedAt.toFloat()
        val oldestTime = sessions.last().updatedAt.toFloat()
        val timeRange = (newestTime - oldestTime).coerceAtLeast(1f)

        return sessions.map { session ->
            val recencyScore = 0.1f + 0.9f * ((session.updatedAt - oldestTime) / timeRange)
            val previewText = buildString {
                append("Session: \"${session.title}\" (${session.messageCount} messages)")
                session.lastMessagePreview?.let {
                    append("\nPreview: ${it.take(200)}")
                }
            }

            UnifiedSearchResult(
                id = "sess_${session.id}",
                text = previewText.take(MAX_EXCERPT_LENGTH),
                sourceType = UnifiedSearchResult.SourceType.SESSION,
                sourceDate = epochToDateString(session.updatedAt),
                sessionTitle = session.title,
                rawScore = recencyScore,
                finalScore = recencyScore * SESSION_WEIGHT,
                createdAt = session.createdAt
            )
        }
    }

    /**
     * Remove results with > 80% text overlap, keeping the highest scored one.
     */
    private fun deduplicate(results: List<UnifiedSearchResult>): List<UnifiedSearchResult> {
        if (results.size <= 1) return results

        val sorted = results.sortedByDescending { it.finalScore }
        val kept = mutableListOf<UnifiedSearchResult>()

        for (result in sorted) {
            val isDuplicate = kept.any { existing ->
                textOverlap(existing.text, result.text) >= DEDUP_OVERLAP_THRESHOLD
            }
            if (!isDuplicate) {
                kept.add(result)
            }
        }

        return kept
    }

    /**
     * Jaccard similarity between two texts based on word sets.
     */
    private fun textOverlap(a: String, b: String): Float {
        val wordsA = a.lowercase().split("\\s+".toRegex()).toSet()
        val wordsB = b.lowercase().split("\\s+".toRegex()).toSet()
        if (wordsA.isEmpty() && wordsB.isEmpty()) return 1f
        val intersection = wordsA.intersect(wordsB).size
        val union = wordsA.union(wordsB).size
        return if (union == 0) 0f else intersection.toFloat() / union.toFloat()
    }

    private fun dateStringToEpoch(dateStr: String): Long {
        return try {
            java.time.LocalDate.parse(dateStr)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            0L
        }
    }

    private fun epochToDateString(epochMillis: Long): String {
        return java.time.Instant.ofEpochMilli(epochMillis)
            .atZone(java.time.ZoneId.systemDefault())
            .toLocalDate()
            .toString()
    }
}
```

### SearchHistoryTool

```kotlin
/**
 * Located in: tool/builtin/SearchHistoryTool.kt
 *
 * Kotlin built-in tool that searches past conversation history,
 * memory files, and daily logs. Delegates to SearchHistoryUseCase
 * for the actual search logic.
 */
class SearchHistoryTool(
    private val searchHistoryUseCase: SearchHistoryUseCase
) : Tool {

    companion object {
        private const val TAG = "SearchHistoryTool"
        private const val DEFAULT_MAX_RESULTS = 10
        private const val MAX_MAX_RESULTS = 50
        private val VALID_SCOPES = setOf("all", "memory", "daily_log", "sessions")
        private val DATE_PATTERN = Regex("""\d{4}-\d{2}-\d{2}""")
    }

    override val definition = ToolDefinition(
        name = "search_history",
        description = "Search past conversation history, memory, and daily logs for information the user mentioned before",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "query" to ToolParameter(
                    type = "string",
                    description = "Search keywords or phrase"
                ),
                "scope" to ToolParameter(
                    type = "string",
                    description = "Data sources to search: \"all\" (default), \"memory\", \"daily_log\", \"sessions\""
                ),
                "date_from" to ToolParameter(
                    type = "string",
                    description = "Start date filter in YYYY-MM-DD format"
                ),
                "date_to" to ToolParameter(
                    type = "string",
                    description = "End date filter in YYYY-MM-DD format"
                ),
                "max_results" to ToolParameter(
                    type = "integer",
                    description = "Maximum number of results to return. Default: 10, Max: 50"
                )
            ),
            required = listOf("query")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Parse and validate query
        val query = parameters["query"]?.toString()?.trim()
        if (query.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'query' is required and cannot be empty"
            )
        }

        // 2. Parse and validate scope
        val scope = parameters["scope"]?.toString()?.trim()?.lowercase() ?: "all"
        if (scope !in VALID_SCOPES) {
            return ToolResult.error(
                "validation_error",
                "Invalid scope '$scope'. Must be one of: ${VALID_SCOPES.joinToString()}"
            )
        }

        // 3. Parse and validate date parameters
        val dateFrom = parameters["date_from"]?.toString()?.trim()
        val dateTo = parameters["date_to"]?.toString()?.trim()

        val dateFromEpoch = if (dateFrom != null) {
            if (!DATE_PATTERN.matches(dateFrom)) {
                return ToolResult.error(
                    "validation_error",
                    "Date must be in YYYY-MM-DD format: $dateFrom"
                )
            }
            parseDateToEpoch(dateFrom) ?: return ToolResult.error(
                "validation_error",
                "Invalid date: $dateFrom"
            )
        } else null

        val dateToEpoch = if (dateTo != null) {
            if (!DATE_PATTERN.matches(dateTo)) {
                return ToolResult.error(
                    "validation_error",
                    "Date must be in YYYY-MM-DD format: $dateTo"
                )
            }
            // End of day: add 24 hours minus 1 ms
            val epoch = parseDateToEpoch(dateTo) ?: return ToolResult.error(
                "validation_error",
                "Invalid date: $dateTo"
            )
            epoch + 24 * 60 * 60 * 1000 - 1
        } else null

        // 4. Parse max_results
        val maxResults = parseIntParam(parameters["max_results"])
            ?.coerceIn(1, MAX_MAX_RESULTS)
            ?: DEFAULT_MAX_RESULTS

        // 5. Execute search
        return try {
            val results = searchHistoryUseCase.search(
                query = query,
                scope = scope,
                dateFrom = dateFromEpoch,
                dateTo = dateToEpoch,
                maxResults = maxResults
            )

            ToolResult.success(formatResults(query, scope, results))
        } catch (e: Exception) {
            Log.e(TAG, "Search failed", e)
            ToolResult.error("search_error", "Search failed: ${e.message}")
        }
    }

    private fun formatResults(
        query: String,
        scope: String,
        results: List<UnifiedSearchResult>
    ): String {
        val sb = StringBuilder()
        sb.appendLine("[Search Results for \"$query\" (scope: $scope, ${results.size} results)]")

        if (results.isEmpty()) {
            sb.appendLine()
            sb.appendLine("No matching results found. Try broader keywords or a different scope.")
            return sb.toString().trimEnd()
        }

        results.forEachIndexed { index, result ->
            sb.appendLine()
            sb.append("--- Result ${index + 1} (score: ${"%.2f".format(result.finalScore)}")
            sb.append(", source: ${result.sourceType.label}")
            result.sourceDate?.let { sb.append(", date: $it") }
            result.sessionTitle?.let { sb.append(", session: \"$it\"") }
            sb.appendLine(") ---")
            sb.appendLine(result.text)
        }

        return sb.toString().trimEnd()
    }

    private fun parseDateToEpoch(dateStr: String): Long? {
        return try {
            java.time.LocalDate.parse(dateStr)
                .atStartOfDay(java.time.ZoneId.systemDefault())
                .toInstant()
                .toEpochMilli()
        } catch (e: Exception) {
            null
        }
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
```

### ToolModule 变更

```kotlin
// In ToolModule.kt -- additions only

import com.oneclaw.shadow.tool.builtin.SearchHistoryTool
import com.oneclaw.shadow.feature.search.usecase.SearchHistoryUseCase

val toolModule = module {
    // ... existing registrations ...

    // RFC-032: search_history built-in tool
    single { SearchHistoryUseCase(get(), get(), get()) }
    single { SearchHistoryTool(get()) }

    single {
        ToolRegistry().apply {
            // ... existing tool registrations ...

            try {
                register(get<SearchHistoryTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register search_history: ${e.message}")
            }

            // ... rest of initialization ...
        }
    }
}
```

### SearchHistoryTool 所需导入

```kotlin
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
import com.oneclaw.shadow.feature.search.usecase.SearchHistoryUseCase
import com.oneclaw.shadow.tool.engine.Tool
```

### SearchHistoryUseCase 所需导入

```kotlin
import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
```

## 实现计划

### 阶段一：数据模型与 DAO 变更

1. 在 `feature/search/model/` 中创建 `UnifiedSearchResult.kt`
2. 在 `MessageDao.kt` 中添加 `searchContent()`
3. 在 `SessionDao.kt` 中添加 `searchByTitleOrPreview()`

### 阶段二：Use Case 实现

1. 在 `feature/search/usecase/` 中创建 `SearchHistoryUseCase.kt`
2. 实现带范围/日期过滤的记忆索引搜索
3. 实现带时效性评分的消息内容搜索
4. 实现带时效性评分的会话元数据搜索
5. 实现结果去重与合并

### 阶段三：工具实现

1. 在 `tool/builtin/` 中创建 `SearchHistoryTool.kt`
2. 实现参数解析与校验
3. 实现输出格式化

### 阶段四：集成

1. 更新 `ToolModule.kt`，注册 `SearchHistoryUseCase` 和 `SearchHistoryTool`
2. 添加 Koin single 注册
3. 在 `ToolRegistry.apply` 块中添加注册逻辑

### 阶段五：测试

1. 创建 `SearchHistoryToolTest.kt` 并编写单元测试
2. 创建 `SearchHistoryUseCaseTest.kt` 并编写单元测试
3. 运行 Layer 1A 测试（`./gradlew test`）
4. 如有模拟器，运行 Layer 1B 测试
5. 在设备上使用多种搜索词进行手动测试

## 数据模型

无数据库结构变更。现有的 `messages`、`sessions` 和 `memory_index` 表通过新增查询方法访问，不修改表结构，无需 Room 迁移。

### 使用的现有数据表

| 数据表 | 检索列 | 查询类型 |
|-------|-----------------|------------|
| `memory_index` | `chunk_text`（通过 BM25/向量） | HybridSearchEngine |
| `messages` | `content` | SQL LIKE（大小写不敏感） |
| `sessions` | `title`、`last_message_preview` | SQL LIKE（大小写不敏感） |

## API 设计

### 工具接口

```
Tool Name: search_history
Parameters:
  - query: string (required) -- Search keywords or phrase
  - scope: string (optional, default: "all") -- "all", "memory", "daily_log", "sessions"
  - date_from: string (optional) -- Start date in YYYY-MM-DD format
  - date_to: string (optional) -- End date in YYYY-MM-DD format
  - max_results: integer (optional, default: 10, max: 50) -- Max results

Returns on success:
  Formatted text with search results (source, date, score, excerpt)

Returns on error:
  ToolResult.error with descriptive message
```

### 输出格式示例

**多数据源的多条结果：**
```
[Search Results for "restaurant" (scope: all, 3 results)]

--- Result 1 (score: 0.87, source: daily_log, date: 2026-02-25) ---
Discussed dinner plans. User mentioned wanting to try "Sakura Sushi" in Shibuya.
The user said they had been recommended this restaurant by a friend.

--- Result 2 (score: 0.64, source: message, date: 2026-02-25) ---
User: Can you help me find a good sushi restaurant near Shibuya station?

--- Result 3 (score: 0.52, source: session, date: 2026-02-20, session: "Restaurant Recommendations") ---
Session: "Restaurant Recommendations" (12 messages)
Preview: Looking for Italian restaurants in Roppongi...
```

**未找到结果：**
```
[Search Results for "quantum physics" (scope: all, 0 results)]

No matching results found. Try broader keywords or a different scope.
```

**限定范围搜索（仅 memory）：**
```
[Search Results for "API key" (scope: memory, 2 results)]

--- Result 1 (score: 0.91, source: memory, date: 2026-02-28) ---
User has configured OpenAI API key for GPT-4. Prefers using gpt-4-turbo model.

--- Result 2 (score: 0.73, source: daily_log, date: 2026-02-20) ---
User added Anthropic API key. Discussed differences between Claude 3 models.
```

## 搜索流程

```
execute() called with (query, scope, date_from, date_to, max_results)
    |
    v
Validate parameters
    |
    v
SearchHistoryUseCase.search()
    |
    +-- scope includes memory?
    |       |
    |       v
    |   HybridSearchEngine.search(query, topK=50)
    |       |
    |       +-- BM25 scoring over memory_index chunks
    |       +-- Vector similarity scoring (if available)
    |       +-- Time decay
    |       +-- Filter by scope (daily_log only?)
    |       +-- Filter by date range
    |       v
    |   List<UnifiedSearchResult> (weighted by 1.0)
    |
    +-- scope includes sessions?
    |       |
    |       v
    |   MessageDao.searchContent(query, dateRange)
    |       |
    |       +-- SQL LIKE '%query%' COLLATE NOCASE
    |       +-- Score by recency
    |       v
    |   List<UnifiedSearchResult> (weighted by 0.6)
    |       |
    |       v
    |   SessionDao.searchByTitleOrPreview(query, dateRange)
    |       |
    |       +-- SQL LIKE '%query%' COLLATE NOCASE
    |       +-- Score by recency
    |       v
    |   List<UnifiedSearchResult> (weighted by 0.5)
    |
    v
Merge all results
    |
    v
Deduplicate (Jaccard similarity > 0.8 = duplicate)
    |
    v
Sort by finalScore descending
    |
    v
Take top max_results
    |
    v
Format as structured text
    |
    v
ToolResult.success(formatted)
```

## 错误处理

| 错误 | 原因 | 错误类型 | 处理方式 |
|-------|-------|------------|----------|
| 查询为空 | `query` 参数为空或 null | `validation_error` | 立即返回并附带错误信息 |
| 无效范围 | 未识别的 `scope` 值 | `validation_error` | 立即返回并列出有效选项 |
| 日期格式错误 | `date_from`/`date_to` 不符合 YYYY-MM-DD 格式 | `validation_error` | 立即返回并提示正确格式 |
| 日期值无效 | 格式正确但日期不合法（如 2026-13-45） | `validation_error` | 立即返回并附带错误信息 |
| 数据库错误 | Room 查询异常 | `search_error` | 记录日志并返回错误信息 |
| 搜索超时 | 搜索超过工具 30 秒超时限制 | N/A | 工具级超时终止执行 |
| 无结果 | 查询未匹配任何内容 | N/A（成功） | 返回成功并附带"未找到结果"提示 |

## 安全考量

1. **SQL 注入**：Room 使用参数化查询。`LIKE '%' || :query || '%'` 模式通过查询参数绑定安全处理用户输入，Room 从设计上防止 SQL 注入。

2. **数据访问**：该工具仅读取应用本地数据库中已有的数据，不引入新的数据访问模式。记忆索引、消息和会话均由应用自身拥有。

3. **输出大小**：每条结果的文本截断至 500 个字符，总结果数上限为 50 条，防止工具输出过大而占满 AI 模型的上下文窗口。

4. **无外部网络请求**：搜索完全在本地执行，不发起任何网络调用。

## 性能

| 操作 | 预期耗时 | 备注 |
|-----------|--------------|-------|
| 记忆索引搜索 | 100-500ms | 取决于 HybridSearchEngine（BM25 + 可选向量） |
| 消息 LIKE 搜索 | 50-500ms | 取决于消息数量；已按 created_at 建立索引 |
| 会话 LIKE 搜索 | 10-50ms | 通常会话数量不超过 1000 条 |
| 结果合并/去重 | < 10ms | 对小规模结果集的内存操作 |
| 总计（全范围） | 200ms-1s | 并行搜索可进一步缩短时间 |

内存占用：
- 搜索结果受 max_results 限制（最多 50 个 UnifiedSearchResult 对象）
- 来自 DAO 的中间结果受各数据源上限约束（50 条消息、20 个会话）
- 调用之间不进行持久化缓存

### 未来优化方向

若消息 LIKE 搜索在大数据量（>10 万条消息）时性能下降：
- 为消息添加 FTS（全文搜索）虚拟表
- 为 SQLite `content` 列添加索引
- 添加带 TTL 的搜索结果缓存

以上优化在 V1 阶段不是必需项。

## 测试策略

### 单元测试

**SearchHistoryToolTest.kt：**
- `testExecute_simpleQuery` -- 基本查询返回结果
- `testExecute_emptyQuery` -- 空查询返回校验错误
- `testExecute_invalidScope` -- 未知范围返回校验错误
- `testExecute_invalidDateFormat` -- 错误日期格式返回校验错误
- `testExecute_scopeMemory` -- 仅返回 memory 结果
- `testExecute_scopeDailyLog` -- 仅返回 daily log 结果
- `testExecute_scopeSessions` -- 仅返回 session/message 结果
- `testExecute_dateRange` -- 日期过滤正常生效
- `testExecute_maxResults` -- 结果数量受到限制
- `testExecute_maxResultsClamped` -- 超过 50 的值被截断
- `testExecute_noResults` -- 返回"未找到结果"消息
- `testDefinition` -- 工具定义包含正确名称和参数

**SearchHistoryUseCaseTest.kt：**
- `testSearch_allScope` -- 搜索全部三个数据源
- `testSearch_memoryScope` -- 仅搜索记忆
- `testSearch_sessionsScope` -- 搜索消息和会话
- `testSearch_dateFiltering` -- 日期范围正确应用
- `testSearch_deduplication` -- 重叠结果被去重
- `testSearch_scoring` -- 数据源权重正确应用
- `testSearch_emptyDatabase` -- 数据库为空时优雅返回空列表
- `testSearch_resultOrdering` -- 结果按 finalScore 降序排列

### 集成测试（Layer 1B）

- 插入测试消息和会话，验证搜索能找到它们
- 插入记忆索引条目，验证混合搜索集成
- 用真实 Room 数据库测试日期范围过滤
- 测试大小写不敏感的 LIKE 查询行为

### 手动测试（Layer 2）

- 搜索最近对话中提到过的词语
- 使用跨特定一周的日期范围搜索
- 使用 scope=memory 搜索，验证仅返回记忆结果
- 使用无匹配结果的查询词搜索
- 搜索查询词中包含特殊字符
- 验证工具出现在 Agent 工具列表中且可被调用

## 备选方案评估

### 1. 为消息使用全文搜索（FTS）

**方案**：为消息内容创建 FTS5 虚拟表，支持快速全文检索与排名。
**V1 中放弃原因**：需要数据库迁移以创建 FTS 表并设置同步触发器。SQL LIKE 对 V1 预期数据量已经足够（大多数用户消息数量不超过 5 万条）。FTS 可在后续作为性能优化项添加。

### 2. 统一基于 Embedding 的搜索

**方案**：为所有消息和会话（而不仅仅是记忆片段）生成 embedding，并对所有内容进行向量搜索。
**V1 中放弃原因**：为所有消息生成 embedding 需要大量存储和计算资源。设备端 embedding 模型较小且已用于记忆片段；将其扩展至所有消息会显著增加存储占用并拖慢消息写入速度。可作为未来增强方向探索。

### 3. 跨所有数据表的单一查询

**方案**：使用跨 messages、sessions 和 memory_index 的 UNION SQL 查询。
**放弃原因**：记忆索引搜索使用 HybridSearchEngine（BM25 + 向量），并非 SQL 查询。三个数据源的搜索机制和评分方式根本不同，统一 SQL 方案不具可行性。

### 4. 将搜索实现为 Use Case（而非工具）

**方案**：将搜索作为记忆系统内部使用的 Use Case，不对外暴露为工具。
**放弃原因**：用户明确需要 AI 可调用的工具。将其实现为工具，使 AI 模型在用户询问历史信息时能够主动搜索，这正是核心使用场景。

## 依赖关系

### 外部依赖

无。仅使用现有内部组件和 Android 平台 API。

### 内部依赖

- `tool/engine/` 中的 `Tool` 接口
- `core/model/` 中的 `ToolResult`、`ToolDefinition`、`ToolParametersSchema`、`ToolParameter`
- `feature/memory/search/` 中的 `HybridSearchEngine`
- `data/local/dao/` 中的 `MessageDao`
- `data/local/dao/` 中的 `SessionDao`
- `feature/memory/model/` 中的 `MemorySearchResult`

## 未来扩展

- **FTS 索引**：为消息添加 FTS5 虚拟表，实现更快的全文搜索
- **消息语义搜索**：对原始消息内容进行 embedding 和向量搜索
- **搜索结果高亮**：在摘录中对匹配关键词加粗或高亮显示
- **搜索历史记录**：追踪用户的搜索词，支持"再次搜索"模式
- **跨会话关联**：关联不同会话中的相关结果以提供上下文
- **搜索 UI**：提供在聊天界面之外浏览搜索结果的专用搜索页面
- **附件搜索**：搜索文件附件名称和元数据（依赖 FEAT-026）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
