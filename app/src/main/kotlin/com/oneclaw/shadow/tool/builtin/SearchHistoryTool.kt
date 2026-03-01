package com.oneclaw.shadow.tool.builtin

import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
import com.oneclaw.shadow.feature.search.usecase.SearchHistoryUseCase
import com.oneclaw.shadow.tool.engine.Tool

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
            try {
                Log.e(TAG, "Search failed", e)
            } catch (_: Exception) {
                // Log call may fail in test environments
            }
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
