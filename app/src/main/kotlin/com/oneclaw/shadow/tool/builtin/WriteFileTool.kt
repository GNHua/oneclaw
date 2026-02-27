package com.oneclaw.shadow.tool.builtin

import android.os.Build
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import java.io.File

class WriteFileTool : Tool {

    companion object {
        private val RESTRICTED_PATHS = listOf(
            "/data/data/",
            "/data/user/",
            "/system/",
            "/proc/",
            "/sys/"
        )
    }

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write contents to a file on local storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The absolute file path to write (e.g., '/storage/emulated/0/Documents/output.txt')"
                ),
                "content" to ToolParameter(
                    type = "string",
                    description = "The content to write to the file"
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "Write mode: 'overwrite' (replace file) or 'append' (add to end). Defaults to 'overwrite'.",
                    enum = listOf("overwrite", "append"),
                    default = "overwrite"
                )
            ),
            required = listOf("path", "content")
        ),
        requiredPermissions = buildFileWritePermissions(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val content = parameters["content"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'content' is required")
        val mode = parameters["mode"] as? String ?: "overwrite"

        val normalizedPath = try {
            File(path).canonicalPath
        } catch (e: Exception) {
            return ToolResult.error("validation_error", "Invalid path: $path")
        }

        if (isRestrictedPath(normalizedPath)) {
            return ToolResult.error(
                "permission_denied",
                "Access denied: cannot write to app-internal or system paths"
            )
        }

        val file = File(normalizedPath)

        return try {
            file.parentFile?.mkdirs()

            when (mode) {
                "append" -> file.appendText(content)
                else -> file.writeText(content)
            }

            val bytesWritten = content.toByteArray().size
            ToolResult.success("Successfully wrote $bytesWritten bytes to $path (mode: $mode)")
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot write to $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to write file: ${e.message}")
        }
    }

    private fun isRestrictedPath(canonicalPath: String): Boolean =
        RESTRICTED_PATHS.any { canonicalPath.startsWith(it) }
}

private fun buildFileWritePermissions(): List<String> {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        listOf(android.Manifest.permission.MANAGE_EXTERNAL_STORAGE)
    } else {
        listOf(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
    }
}
