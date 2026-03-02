package com.oneclaw.shadow.bridge.channel.telegram

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TelegramHtmlRendererTest {

    @Test
    fun `renders bold text`() {
        val result = TelegramHtmlRenderer.render("**bold text**")
        assertEquals("<b>bold text</b>", result)
    }

    @Test
    fun `renders italic text`() {
        val result = TelegramHtmlRenderer.render("*italic text*")
        assertEquals("<i>italic text</i>", result)
    }

    @Test
    fun `renders strikethrough text`() {
        val result = TelegramHtmlRenderer.render("~~strikethrough~~")
        assertEquals("<s>strikethrough</s>", result)
    }

    @Test
    fun `renders inline code`() {
        val result = TelegramHtmlRenderer.render("`code`")
        assertEquals("<code>code</code>", result)
    }

    @Test
    fun `renders fenced code block`() {
        val result = TelegramHtmlRenderer.render("```\ncode block\n```")
        assertEquals("<pre>code block</pre>", result)
    }

    @Test
    fun `renders fenced code block with language`() {
        val result = TelegramHtmlRenderer.render("```kotlin\nval x = 1\n```")
        assertEquals("<pre><code class=\"language-kotlin\">val x = 1</code></pre>", result)
    }

    @Test
    fun `renders headings as bold`() {
        val result = TelegramHtmlRenderer.render("# Heading 1")
        assertEquals("<b>Heading 1</b>", result)
    }

    @Test
    fun `renders heading followed by paragraph with single blank line`() {
        val result = TelegramHtmlRenderer.render("# Title\n\nSome text.")
        assertEquals("<b>Title</b>\n\nSome text.", result)
    }

    @Test
    fun `renders two paragraphs with single blank line separator`() {
        val result = TelegramHtmlRenderer.render("First paragraph.\n\nSecond paragraph.")
        assertEquals("First paragraph.\n\nSecond paragraph.", result)
    }

    @Test
    fun `renders three paragraphs without excess blank lines`() {
        val result = TelegramHtmlRenderer.render("One.\n\nTwo.\n\nThree.")
        assertEquals("One.\n\nTwo.\n\nThree.", result)
    }

    @Test
    fun `renders unordered list items with bullet`() {
        val result = TelegramHtmlRenderer.render("- item one\n- item two")
        assertEquals("\u2022 item one\n\u2022 item two", result)
    }

    @Test
    fun `renders ordered list items with numbers`() {
        val result = TelegramHtmlRenderer.render("1. first\n2. second\n3. third")
        assertEquals("1. first\n2. second\n3. third", result)
    }

    @Test
    fun `renders blockquote with native tag`() {
        val result = TelegramHtmlRenderer.render("> quoted text")
        assertEquals("<blockquote>quoted text</blockquote>", result)
    }

    @Test
    fun `renders thematic break as horizontal line`() {
        val result = TelegramHtmlRenderer.render("Above\n\n---\n\nBelow")
        assertEquals("Above\n\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\nBelow", result)
    }

    @Test
    fun `plain text is returned as is`() {
        val result = TelegramHtmlRenderer.render("Hello, world!")
        assertEquals("Hello, world!", result)
    }

    @Test
    fun `render empty string returns empty`() {
        val result = TelegramHtmlRenderer.render("")
        assertEquals("", result)
    }

    @Test
    fun `render blank string returns empty`() {
        val result = TelegramHtmlRenderer.render("   ")
        assertEquals("", result)
    }

    @Test
    fun `escapes HTML entities in text`() {
        val result = TelegramHtmlRenderer.render("a < b & c > d")
        assertEquals("a &lt; b &amp; c &gt; d", result)
    }

    @Test
    fun `renders bold and italic combined`() {
        val result = TelegramHtmlRenderer.render("**bold** and *italic*")
        assertEquals("<b>bold</b> and <i>italic</i>", result)
    }

    @Test
    fun `renders link`() {
        val result = TelegramHtmlRenderer.render("[click](https://example.com)")
        assertEquals("<a href=\"https://example.com\">click</a>", result)
    }

    @Test
    fun `renders mixed content with heading, paragraph, and list`() {
        val markdown = "# Title\n\nSome intro.\n\n- item A\n- item B"
        val result = TelegramHtmlRenderer.render(markdown)
        assertEquals("<b>Title</b>\n\nSome intro.\n\n\u2022 item A\n\u2022 item B", result)
    }

    @Test
    fun `list between paragraphs has correct spacing`() {
        val markdown = "Before.\n\n- a\n- b\n\nAfter."
        val result = TelegramHtmlRenderer.render(markdown)
        // List items each end with \n, then appendBlockSeparator adds \n\n for top-level list with next sibling
        assertEquals("Before.\n\n\u2022 a\n\u2022 b\n\n\nAfter.", result)
    }

    @Test
    fun `splitForTelegram returns single item for short text`() {
        val text = "Short message"
        val parts = TelegramHtmlRenderer.splitForTelegram(text)
        assertEquals(1, parts.size)
        assertEquals(text, parts[0])
    }

    @Test
    fun `splitForTelegram splits long text into multiple parts`() {
        val longText = "A".repeat(5000)
        val parts = TelegramHtmlRenderer.splitForTelegram(longText, maxLength = 4096)
        assertTrue(parts.size > 1)
        parts.forEach { part ->
            assertTrue(part.length <= 4096)
        }
    }

    @Test
    fun `splitForTelegram respects exact max length boundary`() {
        val text = "A".repeat(4096)
        val parts = TelegramHtmlRenderer.splitForTelegram(text, maxLength = 4096)
        assertEquals(1, parts.size)
        assertEquals(4096, parts[0].length)
    }

    @Test
    fun `TELEGRAM_MAX_MESSAGE_LENGTH constant is 4096`() {
        assertEquals(4096, TelegramHtmlRenderer.TELEGRAM_MAX_MESSAGE_LENGTH)
    }

    @Test
    fun `soft line break renders as newline`() {
        val result = TelegramHtmlRenderer.render("line one\nline two")
        assertEquals("line one\nline two", result)
    }

    @Test
    fun `blockquote strips trailing newlines`() {
        val result = TelegramHtmlRenderer.render("> line one\n> line two")
        assertEquals("<blockquote>line one\nline two</blockquote>", result)
    }
}
