package com.oneclaw.shadow.bridge.channel.telegram

import org.commonmark.ext.gfm.strikethrough.StrikethroughExtension
import org.commonmark.parser.Parser
import org.commonmark.renderer.html.HtmlRenderer

class TelegramHtmlRenderer {

    private val extensions = listOf(StrikethroughExtension.create())
    private val parser = Parser.builder().extensions(extensions).build()

    fun render(markdown: String): String {
        val document = parser.parse(markdown)
        val html = HtmlRenderer.builder().extensions(extensions).build().render(document)
        return convertToTelegramHtml(html)
    }

    private fun convertToTelegramHtml(html: String): String {
        var result = html
        // Telegram HTML supports: <b>, <i>, <u>, <s>, <code>, <pre>, <a>
        // Remove unsupported tags but keep content
        result = result.replace(Regex("<p>(.*?)</p>", RegexOption.DOT_MATCHES_ALL)) { match ->
            match.groupValues[1] + "\n\n"
        }
        result = result.replace(Regex("<h[1-6]>(.*?)</h[1-6]>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<b>${match.groupValues[1]}</b>\n\n"
        }
        result = result.replace(Regex("<strong>(.*?)</strong>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<b>${match.groupValues[1]}</b>"
        }
        result = result.replace(Regex("<em>(.*?)</em>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<i>${match.groupValues[1]}</i>"
        }
        result = result.replace(Regex("<del>(.*?)</del>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<s>${match.groupValues[1]}</s>"
        }
        result = result.replace(Regex("<ul>(.*?)</ul>", RegexOption.DOT_MATCHES_ALL)) { match ->
            match.groupValues[1]
        }
        result = result.replace(Regex("<ol>(.*?)</ol>", RegexOption.DOT_MATCHES_ALL)) { match ->
            match.groupValues[1]
        }
        result = result.replace(Regex("<li>(.*?)</li>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "• ${match.groupValues[1]}\n"
        }
        result = result.replace(Regex("<blockquote>(.*?)</blockquote>", RegexOption.DOT_MATCHES_ALL)) { match ->
            "<i>${match.groupValues[1]}</i>"
        }
        // Remove any remaining unsupported HTML tags
        result = result.replace(Regex("<(?!/?(?:b|i|u|s|code|pre|a)[^>]*>)[^>]+>"), "")
        // Trim trailing whitespace
        result = result.trim()
        return result
    }

    fun splitForTelegram(text: String, maxLength: Int = TELEGRAM_MAX_MESSAGE_LENGTH): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val parts = mutableListOf<String>()
        var remaining = text

        while (remaining.length > maxLength) {
            var splitAt = maxLength

            // Try to split at paragraph boundary
            val paragraphEnd = remaining.lastIndexOf("\n\n", maxLength)
            if (paragraphEnd > maxLength / 2) {
                splitAt = paragraphEnd + 2
            } else {
                // Try sentence boundary
                val sentenceEnd = remaining.lastIndexOf(". ", maxLength)
                if (sentenceEnd > maxLength / 2) {
                    splitAt = sentenceEnd + 1
                } else {
                    // Try word boundary
                    val wordEnd = remaining.lastIndexOf(' ', maxLength)
                    if (wordEnd > maxLength / 2) {
                        splitAt = wordEnd
                    }
                }
            }

            parts.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }

        if (remaining.isNotEmpty()) parts.add(remaining)
        return parts
    }

    companion object {
        const val TELEGRAM_MAX_MESSAGE_LENGTH = 4096
    }
}
