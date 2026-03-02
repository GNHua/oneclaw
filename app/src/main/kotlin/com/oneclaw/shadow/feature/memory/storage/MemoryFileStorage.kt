package com.oneclaw.shadow.feature.memory.storage

import android.content.Context
import java.io.File
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

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

    companion object {
        const val MAX_BACKUPS = 5
        private const val BACKUP_PREFIX = "MEMORY_backup_"
        private const val BACKUP_SUFFIX = ".md"
        private val BACKUP_TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    }

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
     * Create a timestamped backup of MEMORY.md.
     * Returns the backup file name, or null if there is nothing to back up.
     */
    fun createBackup(): String? {
        val content = readMemoryFile() ?: return null
        if (content.isBlank()) return null

        val timestamp = LocalDateTime.now().format(BACKUP_TIMESTAMP_FORMAT)
        val backupName = "$BACKUP_PREFIX$timestamp$BACKUP_SUFFIX"
        val backupFile = File(memoryDir, backupName)
        backupFile.writeText(content)
        return backupName
    }

    /**
     * Prune old backups, keeping only the most recent [maxBackups].
     */
    fun pruneOldBackups(maxBackups: Int = MAX_BACKUPS) {
        val backups = memoryDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(BACKUP_SUFFIX)
        }?.sortedByDescending { it.lastModified() } ?: return

        if (backups.size > maxBackups) {
            backups.drop(maxBackups).forEach { it.delete() }
        }
    }

    /**
     * List all backup files, most recent first.
     */
    fun listBackups(): List<String> {
        return memoryDir.listFiles { file ->
            file.name.startsWith(BACKUP_PREFIX) && file.name.endsWith(BACKUP_SUFFIX)
        }?.sortedByDescending { it.lastModified() }
            ?.map { it.name }
            ?: emptyList()
    }

    /**
     * Restore MEMORY.md from a specific backup file.
     * Returns true if restored successfully.
     */
    fun restoreFromBackup(backupName: String): Boolean {
        val backupFile = File(memoryDir, backupName)
        if (!backupFile.exists()) return false
        val content = backupFile.readText()
        if (content.isBlank()) return false
        writeMemoryFile(content)
        return true
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
