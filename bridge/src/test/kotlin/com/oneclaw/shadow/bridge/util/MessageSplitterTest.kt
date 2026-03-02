package com.oneclaw.shadow.bridge.util

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MessageSplitterTest {

    @Test
    fun `text within limit returns single part`() {
        val text = "Short message"
        val parts = MessageSplitter.split(text, 100)
        assertEquals(1, parts.size)
        assertEquals(text, parts[0])
    }

    @Test
    fun `text at exact limit returns single part`() {
        val text = "A".repeat(100)
        val parts = MessageSplitter.split(text, 100)
        assertEquals(1, parts.size)
        assertEquals(text, parts[0])
    }

    @Test
    fun `splits at paragraph boundary`() {
        val part1 = "A".repeat(60)
        val part2 = "B".repeat(60)
        val text = "$part1\n\n$part2"
        val parts = MessageSplitter.split(text, 100)
        assertEquals(2, parts.size)
        assertEquals(part1, parts[0])
        assertEquals(part2, parts[1])
    }

    @Test
    fun `splits at sentence boundary when no paragraph found`() {
        val part1 = "A".repeat(55) + "."
        val part2 = "B".repeat(55)
        val text = "$part1 $part2"
        val parts = MessageSplitter.split(text, 100)
        assertEquals(2, parts.size)
        assertTrue(parts[0].endsWith("."))
        assertEquals(part2, parts[1])
    }

    @Test
    fun `splits at word boundary when no sentence found`() {
        val part1 = "A".repeat(55)
        val part2 = "B".repeat(55)
        val text = "$part1 $part2"
        val parts = MessageSplitter.split(text, 100)
        assertEquals(2, parts.size)
        assertEquals(part1, parts[0])
        assertEquals(part2, parts[1])
    }

    @Test
    fun `hard splits when no boundary found`() {
        val text = "A".repeat(150)
        val parts = MessageSplitter.split(text, 100)
        assertEquals(2, parts.size)
        assertEquals(100, parts[0].length)
        assertEquals(50, parts[1].length)
    }

    @Test
    fun `multiple splits for very long text`() {
        val text = "A".repeat(5000)
        val parts = MessageSplitter.split(text, 2000)
        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertTrue(part.length <= 2000)
        }
        // Verify all content is preserved
        assertEquals(5000, parts.sumOf { it.length })
    }

    @Test
    fun `trims whitespace from parts`() {
        val text = "  Hello  \n\n  World  "
        val parts = MessageSplitter.split(text, 15)
        parts.forEach { part ->
            assertEquals(part.trim(), part)
        }
    }

    @Test
    fun `empty text returns single empty part`() {
        val parts = MessageSplitter.split("", 100)
        assertEquals(1, parts.size)
        assertEquals("", parts[0])
    }

    @Test
    fun `paragraph boundary preferred over sentence boundary`() {
        // Construct text where both boundaries exist but paragraph is preferred
        // Total length must exceed maxLength=100 for splitting to occur
        val beforeParagraph = "Sentence one. " + "A".repeat(55)
        val afterParagraph = "B".repeat(55)
        val text = "$beforeParagraph\n\n$afterParagraph"
        // text.length = 69 + 2 + 55 = 126 > 100
        val parts = MessageSplitter.split(text, 100)
        assertEquals(2, parts.size)
        assertEquals(beforeParagraph, parts[0])
    }
}
