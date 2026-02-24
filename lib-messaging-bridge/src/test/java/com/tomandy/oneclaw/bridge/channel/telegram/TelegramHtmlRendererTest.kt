package com.tomandy.oneclaw.bridge.channel.telegram

import org.junit.Assert.assertEquals
import org.junit.Test

class TelegramHtmlRendererTest {

    @Test
    fun `empty input returns empty`() {
        assertEquals("", TelegramHtmlRenderer.render(""))
    }

    @Test
    fun `blank input returns empty`() {
        assertEquals("", TelegramHtmlRenderer.render("   "))
    }

    @Test
    fun `plain text escapes HTML entities`() {
        assertEquals("a &amp; b &lt; c &gt; d", TelegramHtmlRenderer.render("a & b < c > d"))
    }

    @Test
    fun `bold text`() {
        assertEquals("This is <b>bold</b> text", TelegramHtmlRenderer.render("This is **bold** text"))
    }

    @Test
    fun `bold with underscores`() {
        assertEquals("This is <b>bold</b> text", TelegramHtmlRenderer.render("This is __bold__ text"))
    }

    @Test
    fun `italic text with asterisks`() {
        assertEquals("This is <i>italic</i> text", TelegramHtmlRenderer.render("This is *italic* text"))
    }

    @Test
    fun `italic text with underscores`() {
        assertEquals("This is <i>italic</i> text", TelegramHtmlRenderer.render("This is _italic_ text"))
    }

    @Test
    fun `bold and italic`() {
        assertEquals("<b>bold</b> and <i>italic</i>", TelegramHtmlRenderer.render("**bold** and *italic*"))
    }

    @Test
    fun `inline code`() {
        assertEquals("Use <code>println()</code> here", TelegramHtmlRenderer.render("Use `println()` here"))
    }

    @Test
    fun `inline code escapes HTML inside`() {
        assertEquals(
            "Use <code>&lt;div&gt;</code> tag",
            TelegramHtmlRenderer.render("Use `<div>` tag")
        )
    }

    @Test
    fun `fenced code block with language`() {
        val input = "```kotlin\nfun main() {\n    println(\"hello\")\n}\n```"
        val result = TelegramHtmlRenderer.render(input)
        assertEquals(
            "<pre><code class=\"language-kotlin\">fun main() {\n    println(\"hello\")\n}</code></pre>",
            result
        )
    }

    @Test
    fun `fenced code block without language`() {
        val input = "```\nsome code\n```"
        val result = TelegramHtmlRenderer.render(input)
        assertEquals("<pre>some code</pre>", result)
    }

    @Test
    fun `fenced code block escapes HTML inside`() {
        val input = "```\n<b>not bold</b> & stuff\n```"
        val result = TelegramHtmlRenderer.render(input)
        assertEquals("<pre>&lt;b&gt;not bold&lt;/b&gt; &amp; stuff</pre>", result)
    }

    @Test
    fun `link`() {
        assertEquals(
            "<a href=\"https://example.com\">Click here</a>",
            TelegramHtmlRenderer.render("[Click here](https://example.com)")
        )
    }

    @Test
    fun `image degrades to link`() {
        assertEquals(
            "<a href=\"https://example.com/img.png\">photo</a>",
            TelegramHtmlRenderer.render("![photo](https://example.com/img.png)")
        )
    }

    @Test
    fun `image with empty alt`() {
        assertEquals(
            "<a href=\"https://example.com/img.png\">image</a>",
            TelegramHtmlRenderer.render("![](https://example.com/img.png)")
        )
    }

    @Test
    fun `block quote single line`() {
        assertEquals(
            "<blockquote>This is a quote</blockquote>",
            TelegramHtmlRenderer.render("> This is a quote")
        )
    }

    @Test
    fun `block quote multiline`() {
        assertEquals(
            "<blockquote>line one\nline two</blockquote>",
            TelegramHtmlRenderer.render("> line one\n> line two")
        )
    }

    @Test
    fun `strikethrough`() {
        assertEquals("<s>deleted</s>", TelegramHtmlRenderer.render("~~deleted~~"))
    }

    @Test
    fun `heading renders as bold`() {
        assertEquals("<b>Title</b>", TelegramHtmlRenderer.render("# Title"))
    }

    @Test
    fun `h2 heading renders as bold`() {
        assertEquals("<b>Subtitle</b>", TelegramHtmlRenderer.render("## Subtitle"))
    }

    @Test
    fun `h3 heading renders as bold`() {
        assertEquals("<b>Section</b>", TelegramHtmlRenderer.render("### Section"))
    }

    @Test
    fun `unordered list with dashes`() {
        val result = TelegramHtmlRenderer.render("- one\n- two\n- three")
        assertEquals("\u2022 one\n\u2022 two\n\u2022 three", result)
    }

    @Test
    fun `unordered list with asterisks`() {
        val result = TelegramHtmlRenderer.render("* one\n* two")
        assertEquals("\u2022 one\n\u2022 two", result)
    }

    @Test
    fun `ordered list`() {
        val result = TelegramHtmlRenderer.render("1. first\n2. second\n3. third")
        assertEquals("1. first\n2. second\n3. third", result)
    }

    @Test
    fun `horizontal rule`() {
        val result = TelegramHtmlRenderer.render("above\n\n---\n\nbelow")
        assertEquals("above\n\n\u2500\u2500\u2500\u2500\u2500\u2500\u2500\u2500\n\nbelow", result)
    }

    @Test
    fun `multiple paragraphs preserved`() {
        val result = TelegramHtmlRenderer.render("First paragraph.\n\nSecond paragraph.")
        assertEquals("First paragraph.\n\nSecond paragraph.", result)
    }

    @Test
    fun `code block content not processed as markdown`() {
        val input = "```\n**not bold** and *not italic*\n```"
        val result = TelegramHtmlRenderer.render(input)
        // Code block content should be literal, not converted
        assertEquals("<pre>**not bold** and *not italic*</pre>", result)
    }

    @Test
    fun `inline code content not processed as markdown`() {
        val result = TelegramHtmlRenderer.render("Use `**not bold**` here")
        assertEquals("Use <code>**not bold**</code> here", result)
    }

    @Test
    fun `escapeHtml function`() {
        assertEquals("a &amp; b", TelegramHtmlRenderer.escapeHtml("a & b"))
        assertEquals("&lt;tag&gt;", TelegramHtmlRenderer.escapeHtml("<tag>"))
        assertEquals("no escaping needed", TelegramHtmlRenderer.escapeHtml("no escaping needed"))
    }

    @Test
    fun `complex mixed content`() {
        val input = """
            # Welcome

            This is **important** info.

            - Use `code` here
            - Visit [docs](https://example.com)

            ```python
            print("hello!")
            ```

            > Remember this.
        """.trimIndent()

        val result = TelegramHtmlRenderer.render(input)

        assert(result.contains("<b>Welcome</b>")) { "Heading should be bold" }
        assert(result.contains("<b>important</b>")) { "Bold should use <b> tag" }
        assert(result.contains("<code>code</code>")) { "Inline code should use <code> tag" }
        assert(result.contains("<a href=\"https://example.com\">docs</a>")) { "Link should use <a> tag" }
        assert(result.contains("<pre><code class=\"language-python\">")) { "Code block should use <pre><code>" }
        assert(result.contains("<blockquote>Remember this.</blockquote>")) { "Block quote should use <blockquote>" }
    }
}
