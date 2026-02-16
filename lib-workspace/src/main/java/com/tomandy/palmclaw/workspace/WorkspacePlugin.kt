package com.tomandy.palmclaw.workspace

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.serialization.json.*
import java.io.File

class WorkspacePlugin : Plugin {

    private lateinit var workspaceRoot: File
    private lateinit var ops: WorkspaceOperations

    override suspend fun onLoad(context: PluginContext) {
        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }
        ops = WorkspaceOperations(workspaceRoot)
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "read_file" -> readFile(arguments)
            "write_file" -> writeFile(arguments)
            "edit_file" -> editFile(arguments)
            "list_files" -> listFiles(arguments)
            "exec" -> execCommand(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {}

    private fun readFile(arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: path")
            val offset = arguments["offset"]?.jsonPrimitive?.intOrNull ?: 1
            val limit = arguments["limit"]?.jsonPrimitive?.intOrNull
                ?: WorkspaceOperations.MAX_READ_LINES

            val file = ops.resolveSafePath(path)
            if (!file.exists()) {
                return ToolResult.Failure("File not found: $path")
            }
            if (!file.isFile) {
                return ToolResult.Failure("Not a file: $path (use list_files for directories)")
            }

            val result = ops.readFile(file, offset, limit)
            val truncationNote = if (result.truncated) {
                "\n\n[Output truncated. Total lines: ${result.totalLines}. " +
                    "Use offset/limit to read remaining content.]"
            } else ""

            ToolResult.Success(
                output = result.content + truncationNote,
                metadata = mapOf(
                    "total_lines" to result.totalLines.toString(),
                    "bytes_read" to result.bytesRead.toString()
                )
            )
        } catch (e: SecurityException) {
            ToolResult.Failure("Security error: ${e.message}")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to read file: ${e.message}", e)
        }
    }

    private fun writeFile(arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: path")
            val content = arguments["content"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: content")

            val file = ops.resolveSafePath(path)
            val bytesWritten = ops.writeFile(file, content)

            ToolResult.Success(output = "Wrote $bytesWritten bytes to $path")
        } catch (e: SecurityException) {
            ToolResult.Failure("Security error: ${e.message}")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to write file: ${e.message}", e)
        }
    }

    private fun editFile(arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: path")
            val oldText = arguments["old_text"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: old_text")
            val newText = arguments["new_text"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: new_text")

            val file = ops.resolveSafePath(path)
            if (!file.exists()) {
                return ToolResult.Failure("File not found: $path")
            }

            // Try exact match first
            var result = ops.editFile(file, oldText, newText)

            // Fall back to fuzzy match if exact fails
            if (result is EditResult.NoMatch) {
                result = ops.editFileFuzzy(file, oldText, newText)
            }

            when (result) {
                is EditResult.Success -> ToolResult.Success(
                    output = "Edited $path (matched at line ${result.matchLine})\n\n${result.diff}"
                )
                is EditResult.NoMatch -> ToolResult.Failure(result.message)
            }
        } catch (e: SecurityException) {
            ToolResult.Failure("Security error: ${e.message}")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to edit file: ${e.message}", e)
        }
    }

    private fun execCommand(arguments: JsonObject): ToolResult {
        return try {
            val command = arguments["command"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required field: command")
            val timeout = (arguments["timeout"]?.jsonPrimitive?.intOrNull
                ?: WorkspaceOperations.DEFAULT_EXEC_TIMEOUT)
                .coerceIn(1, WorkspaceOperations.MAX_EXEC_TIMEOUT)
            val cwdPath = arguments["cwd"]?.jsonPrimitive?.content

            val cwdFile = if (cwdPath != null) {
                val dir = ops.resolveSafePath(cwdPath)
                if (!dir.isDirectory) {
                    return ToolResult.Failure("Not a directory: $cwdPath")
                }
                dir
            } else {
                workspaceRoot
            }

            val result = ops.execCommand(command, cwdFile, timeout)

            val header = if (result.timedOut) {
                "Command timed out after ${timeout}s (killed)\n\n"
            } else {
                "Exit code: ${result.exitCode}\n\n"
            }

            ToolResult.Success(
                output = header + result.output,
                metadata = mapOf(
                    "exit_code" to result.exitCode.toString(),
                    "timed_out" to result.timedOut.toString()
                )
            )
        } catch (e: SecurityException) {
            ToolResult.Failure("Security error: ${e.message}")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to execute command: ${e.message}", e)
        }
    }

    private fun listFiles(arguments: JsonObject): ToolResult {
        return try {
            val path = arguments["path"]?.jsonPrimitive?.content ?: ""
            val limit = arguments["limit"]?.jsonPrimitive?.intOrNull ?: 200

            val dir = if (path.isEmpty()) workspaceRoot
                      else ops.resolveSafePath(path)

            if (!dir.exists()) {
                return ToolResult.Failure("Directory not found: ${path.ifEmpty { "." }}")
            }
            if (!dir.isDirectory) {
                return ToolResult.Failure(
                    "Not a directory: $path (use read_file for files)"
                )
            }

            val result = ops.listFiles(dir, limit)

            if (result.entries.isEmpty()) {
                return ToolResult.Success(
                    output = "${path.ifEmpty { "." }} (empty directory)"
                )
            }

            val truncationNote = if (result.truncated) {
                "\n\n[Listing truncated at $limit entries. " +
                    "${result.totalCount} total entries.]"
            } else ""

            val header = path.ifEmpty { "." }
            val output = "$header (${result.totalCount} entries):\n" +
                result.entries.joinToString("\n") + truncationNote

            ToolResult.Success(output = output)
        } catch (e: SecurityException) {
            ToolResult.Failure("Security error: ${e.message}")
        } catch (e: Exception) {
            ToolResult.Failure("Failed to list files: ${e.message}", e)
        }
    }
}
