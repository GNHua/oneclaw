package com.tomandy.palmclaw.workspace

import java.io.File

class WorkspaceOperations(private val workspaceRoot: File) {

    companion object {
        const val MAX_READ_LINES = 500
        const val MAX_READ_BYTES = 20 * 1024 // 20KB
        const val MAX_LIST_ENTRIES = 500
    }

    /**
     * Resolve a relative path to an absolute File, ensuring it stays
     * within the workspace root. Rejects absolute paths and ".." traversal.
     *
     * @throws SecurityException if the path escapes the workspace
     */
    fun resolveSafePath(relativePath: String): File {
        if (relativePath.startsWith("/")) {
            throw SecurityException("Absolute paths not allowed: $relativePath")
        }
        val resolved = File(workspaceRoot, relativePath).canonicalFile
        if (!resolved.path.startsWith(workspaceRoot.canonicalPath)) {
            throw SecurityException("Path traversal not allowed: $relativePath")
        }
        return resolved
    }

    /**
     * Read file content with line numbers, respecting offset and limit.
     */
    fun readFile(file: File, offset: Int = 1, limit: Int = MAX_READ_LINES): ReadResult {
        val allLines = file.readLines()
        val totalLines = allLines.size
        val startIndex = (offset - 1).coerceAtLeast(0)
        val selectedLines = allLines.drop(startIndex).take(limit)

        val builder = StringBuilder()
        var bytesRead = 0
        var truncated = false
        var linesIncluded = 0

        for ((i, line) in selectedLines.withIndex()) {
            val lineNumber = startIndex + i + 1
            val formatted = "%6d | %s".format(lineNumber, line)
            val lineBytes = formatted.toByteArray().size + 1 // +1 for newline

            if (bytesRead + lineBytes > MAX_READ_BYTES) {
                truncated = true
                break
            }

            if (builder.isNotEmpty()) builder.append('\n')
            builder.append(formatted)
            bytesRead += lineBytes
            linesIncluded++
        }

        // Also truncated if we hit the line limit but more lines exist
        if (!truncated && startIndex + linesIncluded < totalLines) {
            truncated = true
        }

        return ReadResult(
            content = builder.toString(),
            totalLines = totalLines,
            bytesRead = bytesRead,
            truncated = truncated
        )
    }

    /**
     * Write content to a file, creating parent directories as needed.
     * Returns bytes written.
     */
    fun writeFile(file: File, content: String): Long {
        file.parentFile?.mkdirs()
        file.writeText(content)
        return content.toByteArray().size.toLong()
    }

    /**
     * Edit a file by replacing the first occurrence of [oldText] with [newText].
     * Uses exact string matching.
     */
    fun editFile(file: File, oldText: String, newText: String): EditResult {
        val content = file.readText()
        val index = content.indexOf(oldText)
        if (index == -1) {
            return EditResult.NoMatch("Exact text not found in file")
        }

        // Check for multiple occurrences
        val secondIndex = content.indexOf(oldText, index + oldText.length)
        if (secondIndex != -1) {
            val count = countOccurrences(content, oldText)
            return EditResult.NoMatch(
                "Found $count occurrences of old_text. " +
                    "Provide more surrounding context to make the match unique."
            )
        }

        val newContent = content.substring(0, index) + newText + content.substring(index + oldText.length)
        file.writeText(newContent)

        val matchLine = content.substring(0, index).count { it == '\n' } + 1
        val originalLines = content.lines()
        val newLines = newContent.lines()
        val oldLineCount = oldText.count { it == '\n' } + 1
        val newLineCount = newText.count { it == '\n' } + 1
        val diff = TextEditUtils.generateDiff(
            originalLines, newLines, matchLine, oldLineCount, newLineCount
        )

        return EditResult.Success(diff = diff, matchLine = matchLine)
    }

    /**
     * Edit a file using fuzzy whitespace-normalized matching.
     */
    fun editFileFuzzy(file: File, oldText: String, newText: String): EditResult {
        val content = file.readText()
        val match = TextEditUtils.fuzzyFind(content, oldText)
            ?: return EditResult.NoMatch(
                "Text not found even with whitespace normalization"
            )

        val newContent = content.substring(0, match.startIndex) +
            newText +
            content.substring(match.endIndex)
        file.writeText(newContent)

        val matchLine = content.substring(0, match.startIndex)
            .count { it == '\n' } + 1
        val originalLines = content.lines()
        val newLines = newContent.lines()
        val oldLineCount = match.matchedText.count { it == '\n' } + 1
        val newLineCount = newText.count { it == '\n' } + 1
        val diff = TextEditUtils.generateDiff(
            originalLines, newLines, matchLine, oldLineCount, newLineCount
        )

        return EditResult.Success(diff = diff, matchLine = matchLine)
    }

    /**
     * List directory entries, sorted alphabetically (case-insensitive).
     * Directories get "/" suffix. Files show their size.
     */
    fun listFiles(dir: File, limit: Int = 200): ListResult {
        val children = dir.listFiles()?.sortedBy { it.name.lowercase() } ?: emptyList()
        val totalCount = children.size
        val limited = children.take(limit)

        val entries = limited.map { child ->
            if (child.isDirectory) {
                "${child.name}/"
            } else {
                "${child.name}  (${TextEditUtils.formatSize(child.length())})"
            }
        }

        return ListResult(
            entries = entries,
            totalCount = totalCount,
            truncated = totalCount > limit
        )
    }

    private fun countOccurrences(content: String, text: String): Int {
        var count = 0
        var index = 0
        while (true) {
            index = content.indexOf(text, index)
            if (index == -1) break
            count++
            index += text.length
        }
        return count
    }
}

data class ReadResult(
    val content: String,
    val totalLines: Int,
    val bytesRead: Int,
    val truncated: Boolean
)

sealed class EditResult {
    data class Success(val diff: String, val matchLine: Int) : EditResult()
    data class NoMatch(val message: String) : EditResult()
}

data class ListResult(
    val entries: List<String>,
    val totalCount: Int,
    val truncated: Boolean
)
