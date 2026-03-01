# Search History Tool

## Feature Information
- **Feature ID**: FEAT-032
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-032 (pending)

## User Story

**As** an AI agent using OneClawShadow,
**I want** a tool that searches past conversation history, memory files, and daily logs,
**so that** I can find information the user has previously mentioned -- such as a restaurant name, a place they visited, a code snippet they discussed, or any other detail from prior interactions.

### Typical Scenarios

1. The user asks "What was that restaurant I mentioned last week?" -- the agent searches daily logs and message history to find the restaurant name.
2. The user asks "What did we discuss about the database migration?" -- the agent searches session titles, message content, and memory to find relevant context.
3. The user asks "Where did I go last Tuesday?" -- the agent searches daily logs within a date range.
4. The user asks "Find that code snippet about parsing JSON" -- the agent searches message content for code blocks mentioning JSON parsing.
5. The user asks "What API keys have I configured?" -- the agent searches memory (MEMORY.md) for stored configuration notes.
6. The user asks "Summarize what we talked about yesterday" -- the agent searches all sources for the previous day's interactions.

## Feature Description

### Overview

FEAT-032 adds a Kotlin built-in `search_history` tool that searches across three data sources to find information from past interactions:

1. **Memory index** -- MEMORY.md content and daily log chunks stored in the `memory_index` table, searched via the existing `HybridSearchEngine` (BM25 + vector similarity + time decay).
2. **Message content** -- Raw message text stored in the `messages` table, searched via SQL `LIKE` queries.
3. **Session metadata** -- Session titles and last-message previews stored in the `sessions` table, searched via SQL `LIKE` queries.

Results from all sources are independently scored, normalized, merged with configurable weights, and returned as a ranked list. The tool supports filtering by scope (which data sources to search), date range, and maximum result count.

### Architecture Overview

```
AI Model
    | tool call: search_history(query="restaurant", scope="all")
    v
 ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
 ToolRegistry
    |
    v
 SearchHistoryTool  [NEW - Kotlin built-in tool]
    |
    v
 SearchHistoryUseCase  [NEW - business logic]
    |
    +-- HybridSearchEngine  (existing, unchanged)
    |       |
    |       +-- memory_index table (BM25 + vector + time decay)
    |
    +-- MessageDao.searchContent()  [NEW query]
    |       |
    |       +-- messages table (SQL LIKE)
    |
    +-- SessionDao.searchByTitleOrPreview()  [NEW query]
            |
            +-- sessions table (SQL LIKE)
    |
    v
 Result merging & ranking
    |
    v
 Formatted text output to AI model
```

### Tool Definition

| Field | Value |
|-------|-------|
| Name | `search_history` |
| Description | Search past conversation history, memory, and daily logs for information the user mentioned before |
| Parameters | `query` (string, required): Search keywords or phrase |
| | `scope` (string, optional): Data sources to search. One of: "all" (default), "memory", "daily_log", "sessions". |
| | `date_from` (string, optional): Start date filter in YYYY-MM-DD format |
| | `date_to` (string, optional): End date filter in YYYY-MM-DD format |
| | `max_results` (integer, optional): Maximum number of results to return. Default: 10 |
| Required Permissions | None |
| Timeout | 30 seconds |
| Returns | Ranked list of matching results with source, date, score, and text excerpt |

### Scope Parameter

| Scope Value | Data Sources Searched |
|-------------|----------------------|
| `all` | Memory index + messages + sessions |
| `memory` | Memory index only (MEMORY.md + daily log chunks) |
| `daily_log` | Memory index filtered to `source_type = "daily_log"` only |
| `sessions` | Session metadata (titles + previews) + message content |

### Output Format

The tool returns a structured text output:

```
[Search Results for "restaurant" (scope: all, 3 results)]

--- Result 1 (score: 0.87, source: daily_log, date: 2026-02-25) ---
Discussed dinner plans. User mentioned wanting to try "Sakura Sushi" in Shibuya.
The user said they had been recommended this restaurant by a friend.

--- Result 2 (score: 0.64, source: message, date: 2026-02-25, session: "Dinner Planning") ---
User: Can you help me find a good sushi restaurant near Shibuya station?

--- Result 3 (score: 0.52, source: session, date: 2026-02-20) ---
Session: "Restaurant Recommendations" (12 messages, last active: 2026-02-20)
Preview: Looking for Italian restaurants in Roppongi...
```

When no results are found:

```
[Search Results for "quantum physics" (scope: all, 0 results)]

No matching results found. Try broader keywords or a different scope.
```

### User Interaction Flow

```
1. User: "What was that restaurant I mentioned last week?"
2. AI calls search_history(query="restaurant", date_from="2026-02-22", date_to="2026-02-28")
3. SearchHistoryTool:
   a. Delegates to SearchHistoryUseCase
   b. UseCase runs parallel searches across memory index, messages, sessions
   c. Each source returns independently scored results
   d. UseCase normalizes scores, applies source weights, merges, deduplicates
   e. Returns top-K results formatted as text
4. AI receives the search results, extracts the restaurant name, and tells the user
5. Chat shows the search_history tool call result
```

### Result Merging Strategy

Results from each data source are scored independently then merged:

1. **Per-source scoring**: Each source produces results with scores in its own range
2. **Normalization**: Scores from each source are normalized to [0, 1] range (divide by max score in that source)
3. **Source weighting**: Normalized scores are multiplied by source weight:
   - Memory index: 1.0 (highest -- curated, summarized content)
   - Messages: 0.6 (raw conversation text, may contain noise)
   - Sessions: 0.5 (title/preview metadata only)
4. **Time decay**: All scores are multiplied by a time decay factor (exponential, half-life ~69 days)
5. **Deduplication**: Results with >80% text overlap are deduplicated (keep highest score)
6. **Final ranking**: Sort by final score descending, take top `max_results`

## Acceptance Criteria

Must pass (all required):

- [ ] `search_history` tool is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] Tool accepts `query` string parameter and returns matching results
- [ ] `scope` parameter filters which data sources are searched (default: "all")
- [ ] `date_from` and `date_to` parameters filter results by date range
- [ ] `max_results` parameter controls the number of returned results (default: 10)
- [ ] Memory index search uses existing `HybridSearchEngine`
- [ ] Message content search uses SQL LIKE query via `MessageDao.searchContent()`
- [ ] Session metadata search uses SQL LIKE query via `SessionDao.searchByTitleOrPreview()`
- [ ] Results from all sources are normalized, weighted, and merged correctly
- [ ] Output is formatted as structured text with source attribution
- [ ] Empty query returns validation error
- [ ] No results returns a helpful "no results found" message
- [ ] Date range filtering works correctly with YYYY-MM-DD format
- [ ] Invalid date format returns validation error
- [ ] All Layer 1A tests pass

Optional (nice to have):

- [ ] Highlighted keyword matches in result excerpts
- [ ] Result grouping by date

## UI/UX Requirements

This feature has no new UI. The tool operates transparently:
- Same tool call display in chat as other tools
- Output shown in the tool result area
- No additional settings screen needed for V1

## Feature Boundary

### Included

- Kotlin `SearchHistoryTool` implementation
- `SearchHistoryUseCase` for business logic orchestration
- `UnifiedSearchResult` data model for merged results
- New `MessageDao.searchContent()` LIKE query
- New `SessionDao.searchByTitleOrPreview()` LIKE query
- Result normalization, weighting, and merging
- Date range filtering
- Scope filtering
- Registration in `ToolModule`

### Not Included (V1)

- Full-text search (FTS) indexing for messages or sessions
- Regex or advanced query syntax
- Search result caching
- Search history tracking (what was searched)
- UI for browsing search results outside of chat
- Semantic search over message content (only memory index uses vector search)
- Cross-session context linking
- Export or share search results

## Business Rules

1. The `query` parameter must be non-empty and non-blank
2. Default `scope` is "all" which searches all three data sources
3. Default `max_results` is 10; maximum allowed is 50
4. If `max_results` exceeds 50, it is clamped to 50
5. Date parameters must be in YYYY-MM-DD format; invalid formats return validation error
6. If only `date_from` is provided, search from that date to now
7. If only `date_to` is provided, search from the beginning of time to that date
8. Memory index search reuses the existing `HybridSearchEngine` (BM25 + vector + time decay)
9. Message and session searches use SQL `LIKE '%query%'` (case-insensitive)
10. Result text excerpts are truncated to 500 characters maximum
11. No database schema changes -- only new queries on existing tables

## Non-Functional Requirements

### Performance

- Memory index search: Depends on `HybridSearchEngine` performance (typically < 500ms)
- Message LIKE search: < 1s for databases with < 100K messages
- Session LIKE search: < 100ms (typically < 1000 sessions)
- Total search time: < 2s for "all" scope

### Memory

- Search results held in memory are bounded by `max_results` (max 50)
- No persistent caching of search results
- Large message content is truncated in results (max 500 chars per excerpt)

### Compatibility

- Works on all supported Android versions (API 26+)
- No new external dependencies
- No database migration required

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-013 (Agent Memory System)**: `HybridSearchEngine`, `MemoryIndexDao`, memory index data

### Depended On By

- None currently

### External Dependencies

- None (uses existing internal components only)

## Error Handling

### Error Scenarios

1. **Empty query**
   - Cause: `query` parameter is empty or blank
   - Handling: Return `ToolResult.error("validation_error", "Parameter 'query' is required and cannot be empty")`

2. **Invalid date format**
   - Cause: `date_from` or `date_to` is not in YYYY-MM-DD format
   - Handling: Return `ToolResult.error("validation_error", "Date must be in YYYY-MM-DD format: <value>")`

3. **Invalid scope**
   - Cause: `scope` is not one of the recognized values
   - Handling: Return `ToolResult.error("validation_error", "Invalid scope '<value>'. Must be one of: all, memory, daily_log, sessions")`

4. **Database error**
   - Cause: Room query fails
   - Handling: Return `ToolResult.error("search_error", "Search failed: <message>")`

5. **Timeout**
   - Cause: Search takes too long (e.g., very large database)
   - Handling: Tool-level timeout (30s) will terminate the search; partial results if available

## Test Points

### Functional Tests

- Verify `search_history` executes a simple query and returns results
- Verify `scope=memory` only searches memory index
- Verify `scope=daily_log` only searches daily log entries
- Verify `scope=sessions` searches session metadata and message content
- Verify `scope=all` searches all three data sources
- Verify `date_from` filters out results before the specified date
- Verify `date_to` filters out results after the specified date
- Verify `date_from` and `date_to` together form a valid date range
- Verify `max_results` limits the number of returned results
- Verify results are sorted by score descending
- Verify source weights are applied correctly (memory: 1.0, messages: 0.6, sessions: 0.5)
- Verify empty query returns validation error
- Verify invalid date format returns validation error
- Verify invalid scope returns validation error
- Verify no results returns a helpful message

### Edge Cases

- Query that matches in all three data sources
- Query that matches in only one data source
- Query with special characters (quotes, backslashes, SQL injection attempts)
- Very long query string (> 1000 characters)
- `max_results` set to 0 or negative (should default to 10)
- `max_results` set above 50 (should be clamped to 50)
- Date range where `date_from` is after `date_to`
- Date range in the future
- Empty memory index (no daily logs or MEMORY.md indexed)
- Empty message database (fresh install)
- Message content containing tool call JSON (should still be searchable)
- Duplicate results across data sources (deduplication)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
