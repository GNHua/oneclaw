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
                listFilesTool(),
                execTool(),
                javascriptEvalTool()
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

    private fun execTool() = ToolDefinition(
        name = "exec",
        description = """Execute a shell command in the workspace directory.
            |
            |Runs the command via /system/bin/sh with stdout and stderr merged.
            |The working directory defaults to the workspace root.
            |
            |Available utilities include: ls, cat, cp, mv, rm, mkdir, chmod,
            |grep, sed, awk, sort, uniq, wc, head, tail, cut, tr, diff, find,
            |xargs, tee, date, env, sleep, tar, gzip, and pipe chaining.
            |
            |Output is tail-truncated to 16KB (most recent output kept).
            |Default timeout is 30s, max 120s. Process is killed on timeout.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Shell command to execute (passed to sh -c)"
                    ))
                }
                putJsonObject("timeout") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive(
                        "Timeout in seconds (default: 30, max: 120)"
                    ))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(120))
                }
                putJsonObject("cwd") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "Working directory relative to workspace root (default: workspace root)"
                    ))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("command"))
            }
        }
    )

    private fun javascriptEvalTool() = ToolDefinition(
        name = "javascript_eval",
        description = """Evaluate JavaScript code and return the result.
            |
            |Use for math, string manipulation, data transformations, JSON
            |processing, or any computation that needs a real programming language.
            |The script runs in a sandboxed QuickJS engine with no filesystem,
            |network, or system access.
            |
            |The last expression value is returned. For objects/arrays, they are
            |automatically JSON-stringified. Multi-statement scripts are supported.
            |
            |Examples:
            |  "1847 * 293 + 17" -> "541388"
            |  "Math.sqrt(144)" -> "12"
            |  "JSON.stringify([1,2,3].map(x => x * 2))" -> "[2,4,6]"
            |  "'hello'.split('').reverse().join('')" -> "olleh"
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("code") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive(
                        "JavaScript code to evaluate. The last expression value is returned."
                    ))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("code"))
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
