package com.oneclaw.shadow.feature.memory.storage

import android.content.Context
import java.io.File

/**
 * Handles file I/O for memory Markdown files.
 * Files are stored at getFilesDir()/memory/
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
