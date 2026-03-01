package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.feature.memory.MemoryManager
import com.oneclaw.shadow.tool.engine.Tool

class SaveMemoryTool(
    private val memoryManager: MemoryManager
) : Tool {

    override val definition = ToolDefinition(
        name = "save_memory",
        description = "Save important information to long-term memory (MEMORY.md). " +
            "Use this when the user asks you to remember something, or when you identify " +
            "critical information that should persist across conversations. " +
            "The content will be appended to MEMORY.md and available in future conversations.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "content" to ToolParameter(
                    type = "string",
                    description = "The text to save to long-term memory. Should be well-formatted " +
                        "and self-contained. Max 5,000 characters."
                )
            ),
            required = listOf("content")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Extract and validate content parameter
        val content = (parameters["content"] as? String)?.trim()
        if (content.isNullOrEmpty()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' is required and must be non-empty."
            )
        }
        if (content.length > MAX_CONTENT_LENGTH) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'content' must be $MAX_CONTENT_LENGTH characters or less. " +
                    "Current length: ${content.length}."
            )
        }

        // 2. Save to long-term memory
        val result = memoryManager.saveToLongTermMemory(content)
        return result.fold(
            onSuccess = {
                ToolResult.success(
                    "Memory saved successfully. The content has been appended to MEMORY.md " +
                        "and will be available in future conversations."
                )
            },
            onFailure = { e ->
                ToolResult.error(
                    "save_failed",
                    "Failed to save memory: ${e.message}"
                )
            }
        )
    }

    companion object {
        const val MAX_CONTENT_LENGTH = 5_000
    }
}
