package com.oneclaw.shadow.feature.memory

import android.content.Context
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class LongTermMemoryManagerTest {

    @TempDir
    lateinit var tempDir: File

    private lateinit var manager: LongTermMemoryManager
    private lateinit var storage: MemoryFileStorage

    @BeforeEach
    fun setup() {
        val mockContext = mockk<Context>()
        every { mockContext.filesDir } returns tempDir
        storage = MemoryFileStorage(mockContext)
        manager = LongTermMemoryManager(storage)
    }

    @Test
    fun `readMemory returns empty string when file does not exist`() = runTest {
        assertEquals("", manager.readMemory())
    }

    @Test
    fun `writeMemory and readMemory round-trip`() = runTest {
        manager.writeMemory("# My Memory\n\n- I like Kotlin")
        assertEquals("# My Memory\n\n- I like Kotlin", manager.readMemory())
    }

    @Test
    fun `appendMemory creates file with header when empty`() = runTest {
        manager.appendMemory("- New fact")
        val content = manager.readMemory()
        assertTrue(content.contains("# Long-term Memory"))
        assertTrue(content.contains("- New fact"))
    }

    @Test
    fun `appendMemory appends to existing content`() = runTest {
        manager.appendMemory("- First fact")
        manager.appendMemory("- Second fact")
        val content = manager.readMemory()
        assertTrue(content.contains("- First fact"))
        assertTrue(content.contains("- Second fact"))
    }

    @Test
    fun `getInjectionContent returns empty string when no memory`() = runTest {
        assertEquals("", manager.getInjectionContent())
    }

    @Test
    fun `getInjectionContent respects maxLines limit`() = runTest {
        val lines = (1..10).map { "Line $it" }
        manager.writeMemory(lines.joinToString("\n"))
        val injected = manager.getInjectionContent(maxLines = 5)
        val injectedLines = injected.lines()
        assertTrue(injectedLines.size <= 5)
    }

    @Test
    fun `getInjectionContent returns all lines when within limit`() = runTest {
        val content = "Line 1\nLine 2\nLine 3"
        manager.writeMemory(content)
        val injected = manager.getInjectionContent(maxLines = 200)
        assertEquals(content, injected)
    }
}
