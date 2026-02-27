package com.oneclaw.shadow.tool.builtin

import android.os.Build
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import java.io.File
import java.nio.charset.Charset

class ReadFileTool : Tool {

    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024  // 1MB

        private val RESTRICTED_PATHS = listOf(
            "/data/data/",
            "/data/user/",
            "/system/",
            "/proc/",
            "/sys/"
        )
    }

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file from local storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The absolute file path to read (e.g., '/storage/emulated/0/Documents/notes.txt')"
                ),
                "encoding" to ToolParameter(
                    type = "string",
                    description = "File encoding. Defaults to 'UTF-8'.",
                    default = "UTF-8"
                )
            ),
            required = listOf("path")
        ),
        requiredPermissions = buildFileReadPermissions(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val encoding = parameters["encoding"] as? String ?: "UTF-8"

        val normalizedPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return ToolResult.error("validation_error", "Invalid path: $path")
        }

        if (isRestrictedPath(normalizedPath)) {
            return ToolResult.error(
                "permission_denied",
                "Access denied: cannot read app-internal or system files"
            )
        }

        val file = File(normalizedPath)

        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }

        if (!file.isFile) {
            return ToolResult.error("validation_error", "Path is a directory, not a file: $path")
        }

        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult.error(
                "file_too_large",
                "File is too large (${file.length()} bytes). Maximum supported size is $MAX_FILE_SIZE bytes (1MB)."
            )
        }

        return try {
            val charset = try {
                Charset.forName(encoding)
            } catch (e: Exception) {
                return ToolResult.error("validation_error", "Unsupported encoding: '$encoding'")
            }
            val content = file.readText(charset)
            ToolResult.success(content)
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot read $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to read file: ${e.message}")
        }
    }

    private fun isRestrictedPath(canonicalPath: String): Boolean =
        RESTRICTED_PATHS.any { canonicalPath.startsWith(it) }
}

private fun buildFileReadPermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    } else {
        listOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
    }
}
