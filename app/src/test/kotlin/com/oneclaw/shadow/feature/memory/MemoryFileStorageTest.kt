package com.oneclaw.shadow.feature.memory

import android.content.Context
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class MemoryFileStorageTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var storage: MemoryFileStorage

    @BeforeEach
    fun setup() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempDir
        storage = MemoryFileStorage(mockContext)
    }

    @Test
    fun `readMemoryFile returns null when file does not exist`() {
        assertNull(storage.readMemoryFile())
    }

    @Test
    fun `writeMemoryFile and readMemoryFile round-trip`() {
        val content = "# Long-term Memory\n\n- User prefers Kotlin"
        storage.writeMemoryFile(content)
        assertEquals(content, storage.readMemoryFile())
    }

    @Test
    fun `writeMemoryFile overwrites existing content`() {
        storage.writeMemoryFile("first content")
        storage.writeMemoryFile("second content")
        assertEquals("second content", storage.readMemoryFile())
    }

    @Test
    fun `appendToDailyLog creates file with header if not exists`() {
        storage.appendToDailyLog("2026-02-28", "- Discussed coroutines")
        val content = storage.readDailyLog("2026-02-28")
        assertTrue(content!!.contains("# Daily Log - 2026-02-28"))
        assertTrue(content.contains("Discussed coroutines"))
    }

    @Test
    fun `appendToDailyLog appends to existing file`() {
        storage.appendToDailyLog("2026-02-28", "first entry")
        storage.appendToDailyLog("2026-02-28", "second entry")
        val content = storage.readDailyLog("2026-02-28")
        assertTrue(content!!.contains("first entry"))
        assertTrue(content.contains("second entry"))
    }

    @Test
    fun `readDailyLog returns null when file does not exist`() {
        assertNull(storage.readDailyLog("2020-01-01"))
    }

    @Test
    fun `listDailyLogDates returns dates sorted descending`() {
        storage.appendToDailyLog("2026-01-01", "day 1")
        storage.appendToDailyLog("2026-03-15", "day 3")
        storage.appendToDailyLog("2026-02-10", "day 2")
        val dates = storage.listDailyLogDates()
        assertEquals(listOf("2026-03-15", "2026-02-10", "2026-01-01"), dates)
    }

    @Test
    fun `listDailyLogDates returns empty when no logs`() {
        assertTrue(storage.listDailyLogDates().isEmpty())
    }

    @Test
    fun `getDailyLogCount returns correct count`() {
        storage.appendToDailyLog("2026-01-01", "entry")
        storage.appendToDailyLog("2026-01-02", "entry")
        assertEquals(2, storage.getDailyLogCount())
    }

    @Test
    fun `getTotalSize returns positive size after writing`() {
        storage.writeMemoryFile("some content here")
        assertTrue(storage.getTotalSize() > 0)
    }

    // Backup tests

    @Test
    fun `createBackup with existing MEMORY_md creates timestamped backup file`() {
        storage.writeMemoryFile("# Long-term Memory\n\n- A fact")

        val backupName = storage.createBackup()

        assertNotNull(backupName)
        assertTrue(backupName!!.startsWith("MEMORY_backup_"))
        assertTrue(backupName.endsWith(".md"))
        val backups = storage.listBackups()
        assertTrue(backups.contains(backupName))
    }

    @Test
    fun `createBackup with empty MEMORY_md returns null`() {
        storage.writeMemoryFile("   ")

        val backupName = storage.createBackup()

        assertNull(backupName)
    }

    @Test
    fun `createBackup with no MEMORY_md returns null`() {
        val backupName = storage.createBackup()

        assertNull(backupName)
    }

    @Test
    fun `pruneOldBackups with more than max keeps only most recent`() {
        // Create 7 backup files directly with distinct names to avoid timestamp collision
        val memDir = File(tempDir, "memory").also { it.mkdirs() }
        (1..7).forEach { i ->
            File(memDir, "MEMORY_backup_2026-03-02_10-00-0$i.md").writeText("content $i")
        }

        storage.pruneOldBackups(maxBackups = 5)

        val remaining = storage.listBackups()
        assertEquals(5, remaining.size)
    }

    @Test
    fun `pruneOldBackups with fewer than max deletes nothing`() {
        val memDir = File(tempDir, "memory").also { it.mkdirs() }
        (1..3).forEach { i ->
            File(memDir, "MEMORY_backup_2026-03-02_10-00-0$i.md").writeText("content $i")
        }

        storage.pruneOldBackups(maxBackups = 5)

        val remaining = storage.listBackups()
        assertEquals(3, remaining.size)
    }

    @Test
    fun `listBackups returns files sorted most recent first`() {
        val memDir = File(tempDir, "memory").also { it.mkdirs() }
        val firstFile = File(memDir, "MEMORY_backup_2026-03-02_10-00-01.md")
        firstFile.writeText("version 1")
        Thread.sleep(20) // ensure distinct lastModified
        val secondFile = File(memDir, "MEMORY_backup_2026-03-02_10-00-02.md")
        secondFile.writeText("version 2")

        val backups = storage.listBackups()

        assertEquals(2, backups.size)
        // Most recent first (sorted by lastModified)
        assertEquals("MEMORY_backup_2026-03-02_10-00-02.md", backups[0])
        assertEquals("MEMORY_backup_2026-03-02_10-00-01.md", backups[1])
    }

    @Test
    fun `restoreFromBackup with valid backup overwrites MEMORY_md and returns true`() {
        storage.writeMemoryFile("original content")
        val backupName = storage.createBackup()!!
        storage.writeMemoryFile("modified content")

        val restored = storage.restoreFromBackup(backupName)

        assertTrue(restored)
        assertEquals("original content", storage.readMemoryFile())
    }

    @Test
    fun `restoreFromBackup with non-existent file returns false`() {
        val restored = storage.restoreFromBackup("MEMORY_backup_nonexistent.md")

        assertFalse(restored)
    }
}
