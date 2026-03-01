package com.oneclaw.shadow.tool.js.bridge

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import java.io.File

/**
 * Injects file system functions into the QuickJS context.
 * Applies the same path restrictions as ReadFileTool / WriteFileTool.
 */
object FsBridge {

    private const val MAX_FILE_SIZE = 1024 * 1024  // 1MB, same as ReadFileTool

    private val RESTRICTED_PATHS = listOf(
        "/data/data/",
        "/data/user/",
        "/system/",
        "/proc/",
        "/sys/"
    )

    fun inject(quickJs: QuickJs) {
        quickJs.define("fs") {
            // fs.readFile(path) -> string
            function("readFile") { args: Array<Any?> ->
                val path = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("readFile: path argument required")
                readFile(path)
            }

            // fs.writeFile(path, content) -> void (returns null)
            function("writeFile") { args: Array<Any?> ->
                val path = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("writeFile: path argument required")
                val content = args.getOrNull(1)?.toString() ?: ""
                writeFile(path, content)
                null
            }

            // fs.exists(path) -> boolean
            function("exists") { args: Array<Any?> ->
                val path = args.getOrNull(0)?.toString()
                    ?: throw IllegalArgumentException("exists: path argument required")
                fileExists(path)
            }
        }
    }

    private fun validatePath(path: String): String {
        val canonicalPath = File(path).canonicalPath
        for (restricted in RESTRICTED_PATHS) {
            if (canonicalPath.startsWith(restricted)) {
                throw SecurityException("Access denied: path is restricted ($restricted)")
            }
        }
        return canonicalPath
    }

    private fun readFile(path: String): String {
        val canonical = validatePath(path)
        val file = File(canonical)

        if (!file.exists()) throw IllegalArgumentException("File not found: $path")
        if (!file.isFile) throw IllegalArgumentException("Path is a directory: $path")
        if (file.length() > MAX_FILE_SIZE) {
            throw IllegalArgumentException(
                "File too large (${file.length()} bytes). Maximum: $MAX_FILE_SIZE bytes."
            )
        }

        return file.readText(Charsets.UTF_8)
    }

    private fun writeFile(path: String, content: String) {
        val canonical = validatePath(path)
        val file = File(canonical)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun fileExists(path: String): Boolean {
        return try {
            val canonical = validatePath(path)
            File(canonical).exists()
        } catch (e: SecurityException) {
            false  // restricted paths report as non-existent
        }
    }
}
