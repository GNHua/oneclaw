package com.oneclaw.shadow.bridge.channel.slack

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SlackMrkdwnRendererTest {

    @Test
    fun `renders bold`() {
        val result = SlackMrkdwnRenderer.render("**bold text**")
        assertEquals("*bold text*", result)
    }

    @Test
    fun `renders italic`() {
        val result = SlackMrkdwnRenderer.render("*italic text*")
        assertEquals("_italic text_", result)
    }

    @Test
    fun `renders strikethrough`() {
        val result = SlackMrkdwnRenderer.render("~~strikethrough~~")
        assertEquals("~strikethrough~", result)
    }

    @Test
    fun `renders inline code`() {
        val result = SlackMrkdwnRenderer.render("`code`")
        assertEquals("`code`", result)
    }

    @Test
    fun `renders code block`() {
        val result = SlackMrkdwnRenderer.render("```\ncode block\n```")
        assertEquals("```\ncode block\n```", result)
    }

    @Test
    fun `renders fenced code block with language`() {
        val result = SlackMrkdwnRenderer.render("```kotlin\nval x = 1\n```")
        assertEquals("```\nval x = 1\n```", result)
    }

    @Test
    fun `renders link`() {
        val result = SlackMrkdwnRenderer.render("[click](https://example.com)")
        assertEquals("<https://example.com|click>", result)
    }

    @Test
    fun `renders blockquote`() {
        val result = SlackMrkdwnRenderer.render("> quoted text")
        assertEquals("> quoted text", result)
    }

    @Test
    fun `renders heading as bold`() {
        val result = SlackMrkdwnRenderer.render("# Heading 1")
        assertEquals("*Heading 1*", result)
    }

    @Test
    fun `renders unordered list`() {
        val result = SlackMrkdwnRenderer.render("- item one\n- item two")
        assertEquals("\u2022 item one\n\u2022 item two", result)
    }

    @Test
    fun `renders ordered list`() {
        val result = SlackMrkdwnRenderer.render("1. first\n2. second\n3. third")
        assertEquals("1. first\n2. second\n3. third", result)
    }

    @Test
    fun `renders mixed content`() {
        val markdown = "# Title\n\nSome **bold** intro.\n\n- item A\n- item B"
        val result = SlackMrkdwnRenderer.render(markdown)
        assertEquals("*Title*\n\nSome *bold* intro.\n\n\u2022 item A\n\u2022 item B", result)
    }

    @Test
    fun `blank input returns empty string`() {
        assertEquals("", SlackMrkdwnRenderer.render(""))
        assertEquals("", SlackMrkdwnRenderer.render("   "))
    }

    @Test
    fun `renders plain text as is`() {
        val result = SlackMrkdwnRenderer.render("Hello, world!")
        assertEquals("Hello, world!", result)
    }

    @Test
    fun `renders two paragraphs with single blank line separator`() {
        val result = SlackMrkdwnRenderer.render("First paragraph.\n\nSecond paragraph.")
        assertEquals("First paragraph.\n\nSecond paragraph.", result)
    }

    @Test
    fun `renders image as link with alt text`() {
        val result = SlackMrkdwnRenderer.render("![alt](https://example.com/img.png)")
        assertEquals("<https://example.com/img.png|alt>", result)
    }

    @Test
    fun `renders image without alt as link with image placeholder`() {
        val result = SlackMrkdwnRenderer.render("![](https://example.com/img.png)")
        assertEquals("<https://example.com/img.png|image>", result)
    }
}
