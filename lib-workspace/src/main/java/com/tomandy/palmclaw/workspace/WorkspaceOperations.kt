package com.tomandy.palmclaw.workspace

import java.io.File
import java.util.concurrent.TimeUnit

class WorkspaceOperations(
    private val workspaceRoot: File,
    private val shellPath: String = DEFAULT_SHELL_PATH
) {

    companion object {
        const val MAX_READ_LINES = 500
        const val MAX_READ_BYTES = 20 * 1024 // 20KB
        const val MAX_LIST_ENTRIES = 500
        const val MAX_EXEC_OUTPUT = 16 * 1024 // 16KB tail truncation
        const val DEFAULT_EXEC_TIMEOUT = 30 // seconds
        const val MAX_EXEC_TIMEOUT = 120 // seconds
        const val DEFAULT_SHELL_PATH = "/system/bin/sh"
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

    /**
     * Execute a shell command with the given working directory and timeout.
     * Output is tail-truncated to [MAX_EXEC_OUTPUT] bytes.
     */
    fun execCommand(command: String, cwd: File, timeoutSeconds: Int): ExecResult {
        val process = ProcessBuilder(shellPath, "-c", command)
            .directory(cwd)
            .redirectErrorStream(true)
            .start()

        // Read output in a separate thread to prevent deadlock
        // (process can block if output buffer fills while we wait)
        val outputBuilder = StringBuilder()
        val readerThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var read: Int
                    while (reader.read(buffer).also { read = it } != -1) {
                        outputBuilder.append(buffer, 0, read)
                    }
                }
            } catch (_: Exception) {
                // Process was destroyed, stream closed -- expected on timeout
            }
        }
        readerThread.start()

        val completed = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
        if (!completed) {
            process.destroyForcibly()
            readerThread.join(2000) // Give reader thread a moment to flush
            val output = tailTruncate(outputBuilder.toString())
            return ExecResult(output = output, exitCode = -1, timedOut = true)
        }

        readerThread.join(5000)
        val output = tailTruncate(outputBuilder.toString())
        return ExecResult(
            output = output,
            exitCode = process.exitValue(),
            timedOut = false
        )
    }

    private fun tailTruncate(text: String): String {
        if (text.length <= MAX_EXEC_OUTPUT) return text
        val truncated = text.takeLast(MAX_EXEC_OUTPUT)
        return "[Truncated: ${text.length} chars total, showing last $MAX_EXEC_OUTPUT]\n$truncated"
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

data class ExecResult(
    val output: String,
    val exitCode: Int,
    val timedOut: Boolean
)
