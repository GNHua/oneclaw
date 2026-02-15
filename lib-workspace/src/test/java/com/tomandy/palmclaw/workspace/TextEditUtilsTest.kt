package com.tomandy.palmclaw.workspace

import org.junit.Assert.*
import org.junit.Test

class TextEditUtilsTest {

    // --- normalizeWhitespace ---

    @Test
    fun `normalizeWhitespace trims lines`() {
        val input = "  hello  \n  world  "
        assertEquals("hello\nworld", TextEditUtils.normalizeWhitespace(input))
    }

    @Test
    fun `normalizeWhitespace collapses internal whitespace`() {
        val input = "hello   world\nfoo\tbar"
        assertEquals("hello world\nfoo bar", TextEditUtils.normalizeWhitespace(input))
    }

    @Test
    fun `normalizeWhitespace handles empty string`() {
        assertEquals("", TextEditUtils.normalizeWhitespace(""))
    }

    @Test
    fun `normalizeWhitespace handles single line`() {
        assertEquals("hello world", TextEditUtils.normalizeWhitespace("  hello   world  "))
    }

    // --- fuzzyFind ---

    @Test
    fun `fuzzyFind matches with different indentation`() {
        val content = "fun hello() {\n    println(\"hi\")\n}"
        val search = "fun hello() {\nprintln(\"hi\")\n}"

        val match = TextEditUtils.fuzzyFind(content, search)

        assertNotNull(match)
        assertTrue(match!!.matchedText.contains("println"))
    }

    @Test
    fun `fuzzyFind returns null when no match`() {
        val content = "fun hello() { println(\"hi\") }"
        val search = "completely different text"

        val match = TextEditUtils.fuzzyFind(content, search)

        assertNull(match)
    }

    @Test
    fun `fuzzyFind matches with trailing whitespace differences`() {
        val content = "line one   \nline two  "
        val search = "line one\nline two"

        val match = TextEditUtils.fuzzyFind(content, search)

        assertNotNull(match)
    }

    // --- generateDiff ---

    @Test
    fun `generateDiff shows removed and added lines`() {
        val original = listOf("a", "b", "old", "d")
        val new = listOf("a", "b", "new", "d")

        val diff = TextEditUtils.generateDiff(original, new, 3, 1, 1)

        assertTrue(diff.contains("-old"))
        assertTrue(diff.contains("+new"))
        assertTrue(diff.contains("@@"))
    }

    @Test
    fun `generateDiff includes context lines`() {
        val original = listOf("ctx1", "ctx2", "ctx3", "old", "ctx4", "ctx5", "ctx6")
        val new = listOf("ctx1", "ctx2", "ctx3", "new", "ctx4", "ctx5", "ctx6")

        val diff = TextEditUtils.generateDiff(original, new, 4, 1, 1)

        // Should include context before and after
        assertTrue(diff.contains(" ctx3"))
        assertTrue(diff.contains(" ctx4"))
    }

    @Test
    fun `generateDiff handles multiline replacement`() {
        val original = listOf("a", "old1", "old2", "b")
        val new = listOf("a", "new1", "new2", "new3", "b")

        val diff = TextEditUtils.generateDiff(original, new, 2, 2, 3)

        assertTrue(diff.contains("-old1"))
        assertTrue(diff.contains("-old2"))
        assertTrue(diff.contains("+new1"))
        assertTrue(diff.contains("+new2"))
        assertTrue(diff.contains("+new3"))
    }

    @Test
    fun `generateDiff handles edit at beginning of file`() {
        val original = listOf("old", "b", "c")
        val new = listOf("new", "b", "c")

        val diff = TextEditUtils.generateDiff(original, new, 1, 1, 1)

        assertTrue(diff.contains("-old"))
        assertTrue(diff.contains("+new"))
    }

    // --- formatSize ---

    @Test
    fun `formatSize bytes`() {
        assertEquals("0 B", TextEditUtils.formatSize(0))
        assertEquals("512 B", TextEditUtils.formatSize(512))
        assertEquals("1023 B", TextEditUtils.formatSize(1023))
    }

    @Test
    fun `formatSize kilobytes`() {
        assertEquals("1.0 KB", TextEditUtils.formatSize(1024))
        assertEquals("1.5 KB", TextEditUtils.formatSize(1536))
    }

    @Test
    fun `formatSize megabytes`() {
        assertEquals("1.0 MB", TextEditUtils.formatSize(1024 * 1024))
        assertEquals("2.5 MB", TextEditUtils.formatSize((2.5 * 1024 * 1024).toLong()))
    }
}
