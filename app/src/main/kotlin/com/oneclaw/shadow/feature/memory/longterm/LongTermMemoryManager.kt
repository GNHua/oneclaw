package com.oneclaw.shadow.feature.memory.longterm

import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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
        val content = memoryFileStorage.readMemoryFile() ?: return@withContext ""
        if (content.isBlank()) return@withContext ""
        content.lines().take(maxLines).joinToString("\n")
    }
}
