package com.tomandy.palmclaw.service

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Loads memory files from the workspace and builds a context block
 * to inject into the system prompt at session start.
 */
object MemoryBootstrap {

    private const val LONG_TERM_MAX_CHARS = 4000
    private const val TODAY_MAX_CHARS = 4000
    private const val YESTERDAY_MAX_CHARS = 2000

    /**
     * Load memory context from workspace files.
     *
     * Reads MEMORY.md (long-term), today's daily memory, and yesterday's
     * daily memory. Returns a formatted block to append to the system prompt,
     * or empty string if no memory files exist.
     */
    fun loadMemoryContext(workspaceRoot: File): String {
        val sections = mutableListOf<String>()

        // Long-term memory
        val longTermFile = File(workspaceRoot, "MEMORY.md")
        if (longTermFile.exists() && longTermFile.length() > 0) {
            val content = truncate(longTermFile.readText(), LONG_TERM_MAX_CHARS)
            sections.add("## Long-term Memory (MEMORY.md)\n$content")
        }

        // Today's daily memory
        val today = LocalDate.now()
        val todayStr = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val todayFile = File(workspaceRoot, "memory/$todayStr.md")
        if (todayFile.exists() && todayFile.length() > 0) {
            val content = truncate(todayFile.readText(), TODAY_MAX_CHARS)
            sections.add("## Today's Memory ($todayStr)\n$content")
        }

        // Yesterday's daily memory
        val yesterday = today.minusDays(1)
        val yesterdayStr = yesterday.format(DateTimeFormatter.ISO_LOCAL_DATE)
        val yesterdayFile = File(workspaceRoot, "memory/$yesterdayStr.md")
        if (yesterdayFile.exists() && yesterdayFile.length() > 0) {
            val content = truncate(yesterdayFile.readText(), YESTERDAY_MAX_CHARS)
            sections.add("## Yesterday's Memory ($yesterdayStr)\n$content")
        }

        if (sections.isEmpty()) return ""

        return buildString {
            appendLine("--- Your Memory ---")
            appendLine()
            appendLine(sections.joinToString("\n\n"))
            appendLine()
            appendLine("--- End of Memory ---")
            appendLine()
            append(MEMORY_INSTRUCTIONS)
        }
    }

    private fun truncate(text: String, maxChars: Int): String {
        if (text.length <= maxChars) return text
        return text.take(maxChars) + "\n[...truncated]"
    }

    private val MEMORY_INSTRUCTIONS = """
        |## Memory Instructions
        |You have persistent memory across conversations. Use it to remember important context.
        |- Use `write_file` to save memories to daily files at `memory/YYYY-MM-DD.md` (e.g. `memory/2026-02-16.md`).
        |- Write memories proactively when you learn something worth persisting -- don't wait to be asked.
        |- Your long-term memory (MEMORY.md) and recent daily memories are loaded above.
        |- Use `search_memory` to find past memories when relevant context might exist.
        |- To update long-term curated memory, use `read_file` and `write_file` on MEMORY.md directly.
    """.trimMargin()
}
