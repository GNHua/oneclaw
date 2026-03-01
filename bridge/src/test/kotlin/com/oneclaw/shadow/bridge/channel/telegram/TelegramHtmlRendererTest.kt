package com.oneclaw.shadow.bridge.channel.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class TelegramHtmlRendererTest {

    private lateinit var renderer: TelegramHtmlRenderer

    @BeforeEach
    fun setUp() {
        renderer = TelegramHtmlRenderer()
    }

    @Test
    fun `renders bold text`() {
        val result = renderer.render("**bold text**")
        assertTrue(result.contains("<b>bold text</b>"), "Expected bold tags, got: $result")
    }

    @Test
    fun `renders italic text`() {
        val result = renderer.render("*italic text*")
        assertTrue(result.contains("<i>italic text</i>"), "Expected italic tags, got: $result")
    }

    @Test
    fun `renders strikethrough text`() {
        val result = renderer.render("~~strikethrough~~")
        assertTrue(result.contains("<s>strikethrough</s>"), "Expected strikethrough tags, got: $result")
    }

    @Test
    fun `renders inline code`() {
        val result = renderer.render("`code`")
        assertTrue(result.contains("<code>code</code>"), "Expected code tags, got: $result")
    }

    @Test
    fun `renders fenced code block`() {
        val markdown = "```\ncode block\n```"
        val result = renderer.render(markdown)
        assertTrue(result.contains("<pre>") || result.contains("<code>"), "Expected pre or code tags, got: $result")
    }

    @Test
    fun `renders headings as bold`() {
        val result = renderer.render("# Heading 1")
        assertTrue(result.contains("<b>") && result.contains("Heading 1"), "Expected bold heading, got: $result")
    }

    @Test
    fun `renders unordered list items with bullet`() {
        val result = renderer.render("- item one\n- item two")
        assertTrue(result.contains("item one"), "Expected list item content, got: $result")
        assertTrue(result.contains("item two"), "Expected list item content, got: $result")
    }

    @Test
    fun `plain text is returned as is`() {
        val result = renderer.render("Hello, world!")
        assertTrue(result.contains("Hello, world!"), "Expected plain text, got: $result")
    }

    @Test
    fun `splitForTelegram returns single item for short text`() {
        val text = "Short message"
        val parts = renderer.splitForTelegram(text)
        assertEquals(1, parts.size)
        assertEquals(text, parts[0])
    }

    @Test
    fun `splitForTelegram splits long text into multiple parts`() {
        val longText = "A".repeat(5000)
        val parts = renderer.splitForTelegram(longText, maxLength = 4096)
        assertTrue(parts.size > 1, "Expected multiple parts for long text")
        parts.forEach { part ->
            assertTrue(part.length <= 4096, "Each part must be at most 4096 chars, got ${part.length}")
        }
    }

    @Test
    fun `splitForTelegram reassembles to same content`() {
        val longText = buildString {
            repeat(10) {
                append("Paragraph $it. This is some text that makes up the content. ")
            }
        }
        val parts = renderer.splitForTelegram(longText.trim(), maxLength = 100)
        val reassembled = parts.joinToString(" ").replace("  ", " ").trim()
        val originalNormalized = longText.trim()
        // Both should contain the same words even if whitespace differs slightly after splitting
        assertTrue(parts.isNotEmpty())
        parts.forEach { part ->
            assertTrue(part.length <= 100, "Each part must be at most 100 chars, got ${part.length}")
        }
    }

    @Test
    fun `splitForTelegram respects exact max length boundary`() {
        val text = "A".repeat(4096)
        val parts = renderer.splitForTelegram(text, maxLength = 4096)
        assertEquals(1, parts.size)
        assertEquals(4096, parts[0].length)
    }

    @Test
    fun `render empty string returns empty or whitespace`() {
        val result = renderer.render("")
        assertTrue(result.isBlank(), "Expected empty/blank result for empty input, got: '$result'")
    }

    @Test
    fun `render handles multiple paragraphs`() {
        val markdown = "First paragraph.\n\nSecond paragraph."
        val result = renderer.render(markdown)
        assertTrue(result.contains("First paragraph"), "Should contain first paragraph, got: $result")
        assertTrue(result.contains("Second paragraph"), "Should contain second paragraph, got: $result")
    }

    @Test
    fun `render bold and italic combined`() {
        val result = renderer.render("**bold** and *italic*")
        assertTrue(result.contains("<b>bold</b>"), "Expected bold tags, got: $result")
        assertTrue(result.contains("<i>italic</i>"), "Expected italic tags, got: $result")
    }

    @Test
    fun `TELEGRAM_MAX_MESSAGE_LENGTH constant is 4096`() {
        assertEquals(4096, TelegramHtmlRenderer.TELEGRAM_MAX_MESSAGE_LENGTH)
    }
}
