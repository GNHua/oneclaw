# RFC-032: Search History Tool

## Document Information
- **RFC ID**: RFC-032
- **Related PRD**: [FEAT-032 (Search History Tool)](../../prd/features/FEAT-032-search-history.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-013 (Memory System)](RFC-013-agent-memory.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw's AI agent accumulates knowledge about the user across conversations through the memory system (MEMORY.md, daily logs) and through raw message history. However, the agent currently has no way to proactively search this accumulated knowledge at the user's request. When a user asks "what was that restaurant I mentioned last week?", the agent cannot look it up.

The memory system already has a `HybridSearchEngine` that combines BM25 keyword search and vector similarity search with time decay over indexed memory chunks. This RFC extends that capability by adding a `search_history` tool that the AI model can invoke to search across memory, message content, and session metadata.

### Goals

1. Implement `SearchHistoryTool.kt` as a Kotlin built-in tool in `tool/builtin/`
2. Implement `SearchHistoryUseCase.kt` to orchestrate searches across three data sources
3. Define `UnifiedSearchResult.kt` as the merged result model
4. Add `searchContent()` query to `MessageDao`
5. Add `searchByTitleOrPreview()` query to `SessionDao`
6. Register the tool in `ToolModule`

### Non-Goals

- Full-text search (FTS) indexing for messages or sessions
- Semantic/vector search over raw message content (only memory index uses embeddings)
- Search result caching or persistence
- UI for browsing search results outside of chat
- Regex or advanced query syntax support
- Cross-session context linking

## Technical Design

### Architecture Overview

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

### Core Components

**New:**
1. `SearchHistoryTool` -- Kotlin built-in tool that parses parameters and formats output
2. `SearchHistoryUseCase` -- Business logic orchestrating multi-source search
3. `UnifiedSearchResult` -- Data class representing a merged search result

**Modified:**
4. `MessageDao` -- Add `searchContent()` LIKE query
5. `SessionDao` -- Add `searchByTitleOrPreview()` LIKE query
6. `ToolModule` -- Register `SearchHistoryTool`

## Detailed Design

### Directory Structure (New & Changed Files)

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

### MessageDao Changes

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

### SessionDao Changes

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

### ToolModule Changes

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

### Imports Required for SearchHistoryTool

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

### Imports Required for SearchHistoryUseCase

```kotlin
import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.data.local.dao.SessionDao
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
```

## Implementation Plan

### Phase 1: Data Model & DAO Changes

1. Create `UnifiedSearchResult.kt` in `feature/search/model/`
2. Add `searchContent()` to `MessageDao.kt`
3. Add `searchByTitleOrPreview()` to `SessionDao.kt`

### Phase 2: Use Case Implementation

1. Create `SearchHistoryUseCase.kt` in `feature/search/usecase/`
2. Implement memory index search with scope/date filtering
3. Implement message content search with recency scoring
4. Implement session metadata search with recency scoring
5. Implement result deduplication and merging

### Phase 3: Tool Implementation

1. Create `SearchHistoryTool.kt` in `tool/builtin/`
2. Implement parameter parsing and validation
3. Implement output formatting

### Phase 4: Integration

1. Update `ToolModule.kt` to register `SearchHistoryUseCase` and `SearchHistoryTool`
2. Add Koin single registrations
3. Add registration in the `ToolRegistry.apply` block

### Phase 5: Testing

1. Create `SearchHistoryToolTest.kt` with unit tests
2. Create `SearchHistoryUseCaseTest.kt` with unit tests
3. Run Layer 1A tests (`./gradlew test`)
4. Run Layer 1B tests if emulator available
5. Manual testing with various search queries on device

## Data Model

No database schema changes. The existing `messages`, `sessions`, and `memory_index` tables are queried with new query methods that do not alter the schema. No Room migration is needed.

### Existing Tables Used

| Table | Columns Searched | Query Type |
|-------|-----------------|------------|
| `memory_index` | `chunk_text` (via BM25/vector) | HybridSearchEngine |
| `messages` | `content` | SQL LIKE (case-insensitive) |
| `sessions` | `title`, `last_message_preview` | SQL LIKE (case-insensitive) |

## API Design

### Tool Interface

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

### Output Format Examples

**Multiple results from different sources:**
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

**No results found:**
```
[Search Results for "quantum physics" (scope: all, 0 results)]

No matching results found. Try broader keywords or a different scope.
```

**Scoped search (memory only):**
```
[Search Results for "API key" (scope: memory, 2 results)]

--- Result 1 (score: 0.91, source: memory, date: 2026-02-28) ---
User has configured OpenAI API key for GPT-4. Prefers using gpt-4-turbo model.

--- Result 2 (score: 0.73, source: daily_log, date: 2026-02-20) ---
User added Anthropic API key. Discussed differences between Claude 3 models.
```

## Search Flow

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

## Error Handling

| Error | Cause | Error Type | Handling |
|-------|-------|------------|----------|
| Empty query | Blank or null `query` param | `validation_error` | Return immediately with error message |
| Invalid scope | Unrecognized `scope` value | `validation_error` | Return immediately with valid options |
| Invalid date format | `date_from`/`date_to` not YYYY-MM-DD | `validation_error` | Return immediately with format hint |
| Invalid date value | Parseable format but invalid date (e.g. 2026-13-45) | `validation_error` | Return immediately with error |
| Database error | Room query exception | `search_error` | Log and return error message |
| Search timeout | Search exceeds 30s tool timeout | N/A | Tool-level timeout terminates execution |
| No results | Query matches nothing | N/A (success) | Return success with "no results found" message |

## Security Considerations

1. **SQL Injection**: Room uses parameterized queries. The `LIKE '%' || :query || '%'` pattern safely handles user input through query parameter binding. Room prevents SQL injection by design.

2. **Data Access**: The tool only reads data that already exists in the app's local database. No new data access patterns are introduced. The memory index, messages, and sessions are all owned by the app.

3. **Output Size**: Result text is truncated to 500 characters per excerpt, and total results are capped at 50. This prevents excessively large tool results from overwhelming the AI model's context window.

4. **No External Network**: The search is entirely local. No network calls are made.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| Memory index search | 100-500ms | Depends on HybridSearchEngine (BM25 + optional vector) |
| Message LIKE search | 50-500ms | Depends on message count; indexed by created_at |
| Session LIKE search | 10-50ms | Typically < 1000 sessions |
| Result merging/dedup | < 10ms | In-memory operations on small result sets |
| Total (all scope) | 200ms-1s | Parallel searches could reduce this further |

Memory usage:
- Search results bounded by max_results (max 50 UnifiedSearchResult objects)
- Intermediate results from DAOs bounded by per-source limits (50 messages, 20 sessions)
- No persistent caching between calls

### Future Optimization

If message LIKE search becomes too slow with large databases (>100K messages):
- Add FTS (Full-Text Search) virtual table for messages
- Add SQLite `content` column index
- Cache search results with TTL

These optimizations are not needed for V1.

## Testing Strategy

### Unit Tests

**SearchHistoryToolTest.kt:**
- `testExecute_simpleQuery` -- Basic query returns results
- `testExecute_emptyQuery` -- Blank query returns validation error
- `testExecute_invalidScope` -- Unknown scope returns validation error
- `testExecute_invalidDateFormat` -- Bad date format returns validation error
- `testExecute_scopeMemory` -- Only memory results returned
- `testExecute_scopeDailyLog` -- Only daily log results returned
- `testExecute_scopeSessions` -- Only session/message results returned
- `testExecute_dateRange` -- Date filtering works correctly
- `testExecute_maxResults` -- Result count is limited
- `testExecute_maxResultsClamped` -- Values > 50 are clamped
- `testExecute_noResults` -- Returns "no results found" message
- `testDefinition` -- Tool definition has correct name and parameters

**SearchHistoryUseCaseTest.kt:**
- `testSearch_allScope` -- Searches all three sources
- `testSearch_memoryScope` -- Only searches memory
- `testSearch_sessionsScope` -- Searches messages and sessions
- `testSearch_dateFiltering` -- Date range correctly applied
- `testSearch_deduplication` -- Overlapping results are deduplicated
- `testSearch_scoring` -- Source weights applied correctly
- `testSearch_emptyDatabase` -- Returns empty list gracefully
- `testSearch_resultOrdering` -- Results sorted by finalScore descending

### Integration Tests (Layer 1B)

- Insert test messages and sessions, verify search finds them
- Insert memory index entries, verify hybrid search integration
- Test date range filtering with real Room database
- Test case-insensitive LIKE query behavior

### Manual Testing (Layer 2)

- Search for a word mentioned in a recent conversation
- Search with date range spanning a specific week
- Search with scope=memory and verify only memory results returned
- Search with a query that returns no results
- Search with special characters in the query
- Verify tool appears in agent tool list and is callable

## Alternatives Considered

### 1. Full-Text Search (FTS) for Messages

**Approach**: Create an FTS5 virtual table for message content, enabling fast full-text search with ranking.
**Rejected for V1**: Requires a database migration to create the FTS table and triggers to keep it in sync. SQL LIKE is sufficient for the expected data volumes in V1 (most users will have < 50K messages). FTS can be added as a performance optimization later.

### 2. Unified Embedding-Based Search

**Approach**: Generate embeddings for all messages and sessions, not just memory chunks, and use vector search across everything.
**Rejected for V1**: Embedding all messages would require significant storage and computation. The on-device embedding model is small and already used for memory chunks. Extending it to all messages would increase storage substantially and slow down message insertion. Can be explored as a future enhancement.

### 3. Single Search Query Across All Tables

**Approach**: Use a single SQL query with UNION across messages, sessions, and memory_index.
**Rejected**: The memory index search uses HybridSearchEngine (BM25 + vector), which is not a SQL query. The three data sources have fundamentally different search mechanisms and scoring, making a unified SQL approach impractical.

### 4. Search as a Use Case (Not a Tool)

**Approach**: Implement search as an internal use case that the memory system uses, not exposed as a tool.
**Rejected**: The user explicitly wants a tool that the AI can call. Making it a tool gives the AI model the ability to proactively search when the user asks about past information, which is the core use case.

## Dependencies

### External Dependencies

None. Uses only existing internal components and Android platform APIs.

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `ToolResult`, `ToolDefinition`, `ToolParametersSchema`, `ToolParameter` from `core/model/`
- `HybridSearchEngine` from `feature/memory/search/`
- `MessageDao` from `data/local/dao/`
- `SessionDao` from `data/local/dao/`
- `MemorySearchResult` from `feature/memory/model/`

## Future Extensions

- **FTS indexing**: Add FTS5 virtual table for faster full-text search on messages
- **Semantic message search**: Embed and vector-search raw message content
- **Search result highlighting**: Bold or highlight matching keywords in excerpts
- **Search history**: Track what the user has searched for, enabling "search again" patterns
- **Cross-session linking**: Link related results across sessions to provide context
- **Search UI**: Dedicated search screen for browsing results outside of chat
- **Attachment search**: Search file attachment names and metadata (depends on FEAT-026)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
