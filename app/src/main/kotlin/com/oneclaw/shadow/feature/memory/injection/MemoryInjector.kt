package com.oneclaw.shadow.feature.memory.injection

import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.model.MemorySearchResult
import com.oneclaw.shadow.feature.memory.search.HybridSearchEngine

/**
 * Builds the memory injection block for the system prompt.
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
