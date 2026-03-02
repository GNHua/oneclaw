package com.oneclaw.shadow.feature.memory

import android.content.Context
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.longterm.MemorySections
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
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

    // Phase 2: replaceMemoryEntry tests

    @Test
    fun `replaceMemoryEntry replaces single occurrence and returns 1`() = runTest {
        manager.writeMemory("# Memory\n\n- User likes dark mode\n- User prefers Kotlin\n")

        val result = manager.replaceMemoryEntry("User likes dark mode", "User loves dark mode")

        assertEquals(1, result)
        val content = manager.readMemory()
        assertTrue(content.contains("User loves dark mode"))
        assertFalse(content.contains("User likes dark mode"))
    }

    @Test
    fun `replaceMemoryEntry returns 0 when target not found and content unchanged`() = runTest {
        manager.writeMemory("# Memory\n\n- User likes dark mode\n")

        val result = manager.replaceMemoryEntry("nonexistent text", "replacement")

        assertEquals(0, result)
        val content = manager.readMemory()
        assertTrue(content.contains("User likes dark mode"))
    }

    @Test
    fun `replaceMemoryEntry returns count without replacing when multiple occurrences found`() = runTest {
        manager.writeMemory("# Memory\n\n- dark mode\n- prefers dark mode\n")

        val result = manager.replaceMemoryEntry("dark mode", "light mode")

        assertEquals(2, result)
        // Content should be unchanged since multiple matches
        val content = manager.readMemory()
        assertTrue(content.contains("dark mode"))
    }

    @Test
    fun `replaceMemoryEntry with empty newText deletes entry and collapses blank lines`() = runTest {
        manager.writeMemory("# Memory\n\n- Entry to delete\n- Keep this\n")

        val result = manager.replaceMemoryEntry("- Entry to delete", "")

        assertEquals(1, result)
        val content = manager.readMemory()
        assertFalse(content.contains("Entry to delete"))
        assertTrue(content.contains("Keep this"))
        // No excessive blank lines
        assertFalse(content.contains("\n\n\n"))
    }

    // Phase 3: section operations tests

    @Test
    fun `appendToSection with empty file creates structured document`() = runTest {
        manager.appendToSection("Software engineer", "User Profile")

        val content = manager.readMemory()
        assertTrue(content.contains("# Long-term Memory"))
        assertTrue(content.contains("## User Profile"))
        assertTrue(content.contains("Software engineer"))
    }

    @Test
    fun `appendToSection to existing section inserts entry correctly`() = runTest {
        manager.writeMemory("# Long-term Memory\n\n## Preferences\n- Dark mode\n\n## Notes\n")

        manager.appendToSection("Light mode apps", "Preferences")

        val content = manager.readMemory()
        assertTrue(content.contains("## Preferences"))
        assertTrue(content.contains("Dark mode"))
        assertTrue(content.contains("Light mode apps"))
    }

    @Test
    fun `appendToSection to non-existent section creates section`() = runTest {
        manager.writeMemory("# Long-term Memory\n\n## Notes\n- A note\n")

        manager.appendToSection("New workflow step", "Workflow")

        val content = manager.readMemory()
        assertTrue(content.contains("## Workflow"))
        assertTrue(content.contains("New workflow step"))
    }

    @Test
    fun `readSections parses all sections correctly`() = runTest {
        manager.writeMemory(
            "# Long-term Memory\n\n## User Profile\n- Engineer\n\n## Preferences\n- Dark mode\n"
        )

        val sections = manager.readSections()

        assertTrue(sections.containsKey("User Profile"))
        assertTrue(sections["User Profile"]!!.contains("Engineer"))
        assertTrue(sections.containsKey("Preferences"))
        assertTrue(sections["Preferences"]!!.contains("Dark mode"))
    }

    @Test
    fun `parseSections handles edge cases with empty or no sections`() {
        // Empty sections
        val emptyResult = manager.parseSections("# Long-term Memory\n")
        assertTrue(emptyResult.isEmpty())

        // Section with no content
        val emptySection = manager.parseSections("## Notes\n")
        assertTrue(emptySection.containsKey("Notes"))
        assertTrue(emptySection["Notes"]!!.isEmpty())
    }

    @Test
    fun `appendToSection prefixes entry with dash if not already prefixed`() = runTest {
        manager.appendToSection("Likes Kotlin (no dash)", "Notes")

        val content = manager.readMemory()
        assertTrue(content.contains("- Likes Kotlin (no dash)"))
    }

    @Test
    fun `appendToSection preserves existing dash prefix`() = runTest {
        manager.appendToSection("- Already has dash", "Notes")

        val content = manager.readMemory()
        // Should not double-prefix
        assertFalse(content.contains("- - Already has dash"))
        assertTrue(content.contains("- Already has dash"))
    }

    @Test
    fun `MemorySections has correct standard sections`() {
        assertEquals(
            listOf("User Profile", "Preferences", "Interests", "Workflow", "Projects", "Notes"),
            MemorySections.STANDARD_SECTIONS
        )
    }
}
