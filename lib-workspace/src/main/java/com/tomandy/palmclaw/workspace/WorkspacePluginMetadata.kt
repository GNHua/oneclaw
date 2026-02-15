package com.tomandy.palmclaw.workspace

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.*

object WorkspacePluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "workspace",
            name = "Workspace Files",
            version = "1.0.0",
            description = "Read, write, edit, and list files in the workspace directory",
            author = "PalmClaw Team",
            entryPoint = "WorkspacePlugin",
            tools = listOf(
                readFileTool(),
                writeFileTool(),
                editFileTool(),
                listFilesTool()
            )
        )
    }

    private fun readFileTool() = ToolDefinition(
        name = "read_file",
        description = """Read the contents of a file in the workspace.
            |
            |Returns the file content with line numbers. For large files, use offset
            |and limit to read specific sections.
            |
            |Limits: 500 lines or 20KB per read, whichever is hit first.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Relative path to the file within the workspace"
                    ))
                }
                putJsonObject("offset") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(
                        "Line number to start reading from (1-indexed, default: 1)"
                    ))
                    put("minimum", JsonPrimitive(1))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(
                        "Maximum number of lines to return (default: 500)"
                    ))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(500))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
            }
        }
    )

    private fun writeFileTool() = ToolDefinition(
        name = "write_file",
        description = """Write content to a file in the workspace.
            |
            |Creates the file if it doesn't exist. Overwrites if it does.
            |Parent directories are created automatically.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Relative path to the file within the workspace"
                    ))
                }
                putJsonObject("content") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Content to write to the file"
                    ))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("content"))
            }
        }
    )

    private fun editFileTool() = ToolDefinition(
        name = "edit_file",
        description = """Edit a file by replacing text in the workspace.
            |
            |Finds the first occurrence of old_text and replaces it with new_text.
            |The old_text must match uniquely (not appear multiple times).
            |If an exact match is not found, attempts fuzzy matching with
            |whitespace normalization.
            |
            |Returns a unified diff snippet showing the change.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Relative path to the file within the workspace"
                    ))
                }
                putJsonObject("old_text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "The exact text to find and replace (must be unique in the file)"
                    ))
                }
                putJsonObject("new_text") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "The replacement text"
                    ))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("path"))
                add(JsonPrimitive("old_text"))
                add(JsonPrimitive("new_text"))
            }
        }
    )

    private fun listFilesTool() = ToolDefinition(
        name = "list_files",
        description = """List files and directories in the workspace.
            |
            |Returns an alphabetically sorted listing. Directories are shown
            |with a trailing "/". Files show their size.
            |
            |If no path is provided, lists the workspace root.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("path") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Relative directory path within the workspace (default: root)"
                    ))
                }
                putJsonObject("limit") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(
                        "Maximum number of entries to return (default: 200)"
                    ))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(500))
                }
            }
        }
    )
}
