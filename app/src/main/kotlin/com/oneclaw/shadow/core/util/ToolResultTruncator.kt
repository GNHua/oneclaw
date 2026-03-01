package com.oneclaw.shadow.core.util

object ToolResultTruncator {
    const val MAX_CHARS = 30_000

    fun truncate(result: String): String {
        if (result.length <= MAX_CHARS) return result
        val suffix = "\n\n[... content truncated, showing first ${MAX_CHARS} of ${result.length} characters ...]"
        return result.substring(0, MAX_CHARS) + suffix
    }
}
