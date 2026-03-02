package com.oneclaw.shadow.feature.memory.longterm

import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standard section names for structured MEMORY.md.
 */
object MemorySections {
    const val HEADER = "# Long-term Memory"
    val STANDARD_SECTIONS = listOf(
        "User Profile",
        "Preferences",
        "Interests",
        "Workflow",
        "Projects",
        "Notes"
    )
}

/**
 * Manages the MEMORY.md long-term memory file.
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
        val existing = memoryFileStorage.readMemoryFile()
        val newContent = if (existing.isNullOrBlank()) {
            "# Long-term Memory\n\n$content\n"
        } else {
            "$existing\n$content\n"
        }
        memoryFileStorage.writeMemoryFile(newContent)
    }

    /**
     * Overwrite MEMORY.md with new content (for user manual editing or compaction).
     */
    suspend fun writeMemory(fullContent: String) = withContext(Dispatchers.IO) {
        memoryFileStorage.writeMemoryFile(fullContent)
    }

    /**
     * Get content for system prompt injection.
     * Returns at most the first [maxLines] lines.
     */
    suspend fun getInjectionContent(maxLines: Int = 200): String = withContext(Dispatchers.IO) {
        val content = memoryFileStorage.readMemoryFile() ?: return@withContext ""
        if (content.isBlank()) return@withContext ""
        content.lines().take(maxLines).joinToString("\n")
    }

    /**
     * Replace a specific text in MEMORY.md.
     * Returns the number of occurrences found.
     * Only performs the replacement if exactly 1 match is found.
     */
    suspend fun replaceMemoryEntry(oldText: String, newText: String): Int =
        withContext(Dispatchers.IO) {
            val content = memoryFileStorage.readMemoryFile() ?: return@withContext 0
            val trimmedOld = oldText.trim()

            val matchCount = countOccurrences(content, trimmedOld)
            if (matchCount != 1) return@withContext matchCount

            val newContent = if (newText.isEmpty()) {
                content.replace(trimmedOld, "")
                    .replace(Regex("\n{3,}"), "\n\n")
                    .trim() + "\n"
            } else {
                content.replace(trimmedOld, newText.trim())
            }

            memoryFileStorage.writeMemoryFile(newContent)
            1
        }

    /**
     * Append content under a specific section in MEMORY.md.
     * Creates the section if it doesn't exist.
     */
    suspend fun appendToSection(content: String, sectionName: String) =
        withContext(Dispatchers.IO) {
            val existing = memoryFileStorage.readMemoryFile()
            val newContent = when {
                existing.isNullOrBlank() -> buildStructuredMemory(mapOf(sectionName to content))
                !existing.contains("## $sectionName") ->
                    "$existing\n## $sectionName\n${toEntryLine(content)}\n"
                else -> insertIntoSection(existing, sectionName, content)
            }
            memoryFileStorage.writeMemoryFile(newContent)
        }

    /**
     * Read memory content parsed by section.
     */
    suspend fun readSections(): Map<String, String> = withContext(Dispatchers.IO) {
        val content = memoryFileStorage.readMemoryFile() ?: return@withContext emptyMap()
        parseSections(content)
    }

    internal fun parseSections(content: String): Map<String, String> {
        val sections = mutableMapOf<String, String>()
        val lines = content.lines()
        var currentSection = ""
        val sectionContent = StringBuilder()

        for (line in lines) {
            if (line.startsWith("## ")) {
                if (currentSection.isNotEmpty()) {
                    sections[currentSection] = sectionContent.toString().trim()
                }
                currentSection = line.removePrefix("## ").trim()
                sectionContent.clear()
            } else if (currentSection.isNotEmpty() && !line.startsWith("# ")) {
                sectionContent.appendLine(line)
            }
        }
        if (currentSection.isNotEmpty()) {
            sections[currentSection] = sectionContent.toString().trim()
        }
        return sections
    }

    private fun insertIntoSection(content: String, sectionName: String, newEntry: String): String {
        val lines = content.lines().toMutableList()
        val sectionHeader = "## $sectionName"
        val headerIndex = lines.indexOfFirst { it.trim() == sectionHeader }
        if (headerIndex < 0) return "$content\n$sectionHeader\n${toEntryLine(newEntry)}\n"

        var insertIndex = lines.size
        for (i in (headerIndex + 1) until lines.size) {
            if (lines[i].startsWith("## ")) {
                insertIndex = i
                break
            }
        }

        val entryLine = toEntryLine(newEntry)
        lines.add(insertIndex, entryLine)
        return lines.joinToString("\n")
    }

    private fun buildStructuredMemory(initialEntries: Map<String, String>): String {
        val builder = StringBuilder("${MemorySections.HEADER}\n\n")
        for (section in MemorySections.STANDARD_SECTIONS) {
            builder.appendLine("## $section")
            val entry = initialEntries[section]
            if (entry != null) {
                builder.appendLine(toEntryLine(entry))
            }
            builder.appendLine()
        }
        return builder.toString()
    }

    private fun toEntryLine(text: String): String =
        if (text.startsWith("- ")) text else "- $text"

    private fun countOccurrences(text: String, target: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = text.indexOf(target, index)
            if (index < 0) break
            count++
            index += target.length
        }
        return count
    }
}
