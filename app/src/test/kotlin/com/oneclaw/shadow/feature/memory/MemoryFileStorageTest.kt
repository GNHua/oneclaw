package com.oneclaw.shadow.feature.memory

import android.content.Context
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.every
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
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
}
