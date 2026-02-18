package com.tomandy.oneclaw.workspace

object TextEditUtils {

    /**
     * Generate a unified diff snippet around the edit location.
     * Shows [contextSize] lines of context before and after the change.
     */
    fun generateDiff(
        originalLines: List<String>,
        newLines: List<String>,
        matchStartLine: Int,
        oldLineCount: Int,
        newLineCount: Int,
        contextSize: Int = 3
    ): String {
        val startLine = (matchStartLine - 1 - contextSize).coerceAtLeast(0)
        val endLineOld = (matchStartLine - 1 + oldLineCount + contextSize)
            .coerceAtMost(originalLines.size)
        val endLineNew = (matchStartLine - 1 + newLineCount + contextSize)
            .coerceAtMost(newLines.size)

        val builder = StringBuilder()
        builder.appendLine(
            "@@ -${startLine + 1},${endLineOld - startLine} " +
                "+${startLine + 1},${endLineNew - startLine} @@"
        )

        // Context before
        for (i in startLine until (matchStartLine - 1).coerceAtMost(originalLines.size)) {
            builder.appendLine(" ${originalLines[i]}")
        }
        // Removed lines
        for (i in (matchStartLine - 1) until
            (matchStartLine - 1 + oldLineCount).coerceAtMost(originalLines.size)) {
            builder.appendLine("-${originalLines[i]}")
        }
        // Added lines
        for (i in (matchStartLine - 1) until
            (matchStartLine - 1 + newLineCount).coerceAtMost(newLines.size)) {
            builder.appendLine("+${newLines[i]}")
        }
        // Context after (from the new file)
        val afterStart = matchStartLine - 1 + newLineCount
        for (i in afterStart until endLineNew.coerceAtMost(newLines.size)) {
            builder.appendLine(" ${newLines[i]}")
        }

        return builder.toString().trimEnd()
    }

    /**
     * Normalize whitespace for fuzzy matching.
     * Trims each line and collapses consecutive whitespace to a single space.
     */
    fun normalizeWhitespace(text: String): String {
        return text.lines()
            .joinToString("\n") { line ->
                line.trim().replace(Regex("\\s+"), " ")
            }
    }

    /**
     * Find [oldText] in [content] using whitespace-normalized matching.
     * Returns the actual substring from the original content that matched,
     * or null if no match.
     */
    fun fuzzyFind(content: String, oldText: String): FuzzyMatch? {
        val normalizedOld = normalizeWhitespace(oldText)
        val normalizedContent = normalizeWhitespace(content)

        val normIndex = normalizedContent.indexOf(normalizedOld)
        if (normIndex == -1) return null

        // Map normalized position back to original by working line-by-line.
        val normBeforeLine = normalizedContent.substring(0, normIndex)
            .count { it == '\n' }
        val normMatchLines = normalizedOld.count { it == '\n' } + 1

        val origLines = content.lines()
        val startLineIdx = normBeforeLine
        val endLineIdx = (normBeforeLine + normMatchLines - 1)
            .coerceAtMost(origLines.size - 1)

        // Calculate character offsets in the original content
        var origStart = 0
        for (i in 0 until startLineIdx) {
            origStart += origLines[i].length + 1 // +1 for newline
        }

        var origEnd = 0
        for (i in 0..endLineIdx) {
            origEnd += origLines[i].length + 1
        }
        // Remove trailing newline from last line
        origEnd = (origEnd - 1).coerceAtMost(content.length)

        return FuzzyMatch(
            startIndex = origStart,
            endIndex = origEnd,
            matchedText = content.substring(origStart, origEnd)
        )
    }

    /**
     * Format a file size in human-readable form.
     */
    fun formatSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        else -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    }
}

data class FuzzyMatch(
    val startIndex: Int,
    val endIndex: Int,
    val matchedText: String
)
