package com.oneclaw.shadow.core.model

import java.io.File

/**
 * Sealed class representing the content of a file for preview purposes.
 */
sealed class FileContent {
    data class Text(val content: String, val lineCount: Int) : FileContent()
    data class Image(val file: File) : FileContent()
    data class Unsupported(val mimeType: String?) : FileContent()
}
