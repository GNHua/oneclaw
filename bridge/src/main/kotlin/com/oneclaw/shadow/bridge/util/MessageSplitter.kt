package com.oneclaw.shadow.bridge.util

/**
 * Splits long text messages at natural boundaries to fit within
 * a channel's character limit. Used by all channels that enforce
 * a maximum message length.
 *
 * Splitting strategy (greedy, in priority order):
 * 1. Paragraph boundary (\n\n) if found after 50% of maxLength
 * 2. Sentence boundary (". ") if found after 50% of maxLength
 * 3. Word boundary (" ") if found after 50% of maxLength
 * 4. Hard split at maxLength
 */
object MessageSplitter {

    fun split(text: String, maxLength: Int): List<String> {
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
}
