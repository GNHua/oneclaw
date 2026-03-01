package com.oneclaw.shadow.data.storage

import android.content.Context
import com.oneclaw.shadow.core.model.FileContent
import com.oneclaw.shadow.core.model.FileInfo
import java.io.File

/**
 * Central class managing the user_files/ directory under context.filesDir.
 * All user-facing files saved by AI agents are stored here.
 */
open class UserFileStorage(private val context: Context) {

    open val rootDir: File
        get() = File(context.filesDir, "user_files").also { it.mkdirs() }

    /**
     * List files and directories in the given relative path.
     * Returns sorted: directories first (alphabetical), then files (alphabetical).
     */
    fun listFiles(relativePath: String = ""): List<FileInfo> {
        val dir = resolveDir(relativePath)
        if (!dir.exists() || !dir.isDirectory) return emptyList()

        return dir.listFiles()
            ?.map { file -> fileToInfo(file) }
            ?.sortedWith(compareByDescending<FileInfo> { it.isDirectory }.thenBy { it.name.lowercase() })
            ?: emptyList()
    }

    /**
     * Read file content for preview.
     * Text files: read up to 1MB as string.
     * Image files: return the File reference.
     * Others: return Unsupported.
     */
    fun readFileContent(relativePath: String): FileContent {
        val file = resolveFile(relativePath)
        val mimeType = getMimeType(file)

        return when {
            isTextFile(mimeType, file) -> {
                if (file.length() > MAX_TEXT_SIZE) {
                    FileContent.Text(
                        content = file.inputStream().bufferedReader()
                            .use { it.readText().take(MAX_TEXT_SIZE.toInt()) },
                        lineCount = -1  // truncated
                    )
                } else {
                    val content = file.readText()
                    FileContent.Text(content, content.lines().size)
                }
            }
            isImageFile(mimeType) -> FileContent.Image(file)
            else -> FileContent.Unsupported(mimeType)
        }
    }

    /** Delete a file or directory recursively. */
    fun delete(relativePath: String): Boolean {
        val file = resolveFile(relativePath)
        if (!isUnderRoot(file)) return false
        return file.deleteRecursively()
    }

    /** Get total storage size of user_files. */
    fun getTotalSize(): Long {
        return rootDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Resolve a relative path to a File under user_files, with path traversal protection. */
    fun resolveFile(relativePath: String): File {
        val file = File(rootDir, relativePath).canonicalFile
        require(isUnderRoot(file)) { "Path traversal detected: $relativePath" }
        return file
    }

    private fun resolveDir(relativePath: String): File = resolveFile(relativePath)

    private fun isUnderRoot(file: File): Boolean =
        file.canonicalPath.startsWith(rootDir.canonicalPath)

    private fun fileToInfo(file: File): FileInfo {
        val relativePath = file.canonicalPath.removePrefix(rootDir.canonicalPath).trimStart('/')
        return FileInfo(
            name = file.name,
            absolutePath = file.canonicalPath,
            relativePath = relativePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0,
            lastModified = file.lastModified(),
            mimeType = if (file.isFile) getMimeType(file) else null,
            childCount = if (file.isDirectory) (file.listFiles()?.size ?: 0) else 0
        )
    }

    companion object {
        const val MAX_TEXT_SIZE = 1024 * 1024L  // 1MB

        private val TEXT_EXTENSIONS = setOf(
            "txt", "md", "json", "py", "kt", "java", "xml", "csv",
            "log", "yaml", "yml", "toml", "html", "css", "js", "ts",
            "sh", "bat", "ini", "cfg", "conf", "properties", "gradle",
            "sql", "r", "rb", "go", "rs", "c", "cpp", "h", "swift"
        )

        private val IMAGE_EXTENSIONS = setOf(
            "png", "jpg", "jpeg", "gif", "webp", "bmp"
        )

        fun isTextFile(mimeType: String?, file: File): Boolean {
            if (mimeType?.startsWith("text/") == true) return true
            return file.extension.lowercase() in TEXT_EXTENSIONS
        }

        fun isImageFile(mimeType: String?): Boolean {
            return mimeType?.startsWith("image/") == true
        }

        fun getMimeType(file: File): String? {
            val ext = file.extension.lowercase()
            return when (ext) {
                "txt" -> "text/plain"
                "md" -> "text/markdown"
                "json" -> "application/json"
                "py" -> "text/x-python"
                "kt" -> "text/x-kotlin"
                "java" -> "text/x-java"
                "xml" -> "text/xml"
                "html" -> "text/html"
                "css" -> "text/css"
                "js" -> "text/javascript"
                "csv" -> "text/csv"
                "yaml", "yml" -> "text/yaml"
                "png" -> "image/png"
                "jpg", "jpeg" -> "image/jpeg"
                "gif" -> "image/gif"
                "webp" -> "image/webp"
                "bmp" -> "image/bmp"
                else -> null
            }
        }
    }
}
