package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.tool.engine.Tool

class UpdateMemoryTool(
    private val memoryManager: MemoryManager
) : Tool {

    override val definition = ToolDefinition(
        name = "update_memory",
        description = """Update or delete an existing entry in long-term memory (MEMORY.md).

Use this tool to:
- Correct outdated information (e.g., update a preference that has changed)
- Remove entries that are no longer relevant
- Merge duplicate entries into one

Provide the exact text to find (old_text) and the replacement text (new_text).
To delete an entry, set new_text to empty string.
The old_text must match exactly (after trimming whitespace) -- use the memory section in the system prompt to find the precise text.""",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "old_text" to ToolParameter(
                    type = "string",
                    description = "The exact text to find in MEMORY.md. Must match an existing entry. " +
                        "Use the memory section in the system prompt to find the precise wording."
                ),
                "new_text" to ToolParameter(
                    type = "string",
                    description = "The replacement text. Set to empty string to delete the entry."
                )
            ),
            required = listOf("old_text", "new_text")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val oldText = (parameters["old_text"] as? String)?.trim()
        if (oldText.isNullOrEmpty()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'old_text' is required and must be non-empty."
            )
        }

        val newText = (parameters["new_text"] as? String)?.trim() ?: ""

        if (oldText == newText) {
            return ToolResult.error(
                "validation_error",
                "old_text and new_text are identical. No update needed."
            )
        }

        val result = memoryManager.updateLongTermMemory(oldText, newText)
        return result.fold(
            onSuccess = { matchCount ->
                when {
                    matchCount == 0 -> ToolResult.error(
                        "not_found",
                        "The specified text was not found in MEMORY.md. " +
                            "Check the memory section in the system prompt for the exact wording."
                    )
                    matchCount > 1 -> ToolResult.error(
                        "ambiguous_match",
                        "The specified text matches $matchCount locations in MEMORY.md. " +
                            "Provide more surrounding context to make the match unique."
                    )
                    else -> {
                        val action = if (newText.isEmpty()) "deleted" else "updated"
                        ToolResult.success("Memory entry $action successfully.")
                    }
                }
            },
            onFailure = { e ->
                ToolResult.error("update_failed", "Failed to update memory: ${e.message}")
            }
        )
    }
}
