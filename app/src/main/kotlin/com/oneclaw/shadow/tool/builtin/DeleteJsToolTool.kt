package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.UserToolManager

/**
 * Deletes a user-created JavaScript tool: unregisters from
 * ToolRegistry and removes the manifest and source files.
 */
class DeleteJsToolTool(
    private val userToolManager: UserToolManager
) : Tool {

    override val definition = ToolDefinition(
        name = "delete_js_tool",
        description = "Delete a user-created JavaScript tool. " +
            "Removes the tool from the registry and deletes its files. " +
            "Cannot delete built-in tools.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Name of the tool to delete"
                )
            ),
            required = listOf("name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'name' is required")

        return when (val result = userToolManager.delete(name)) {
            is AppResult.Success -> ToolResult.success(result.data)
            is AppResult.Error -> ToolResult.error("delete_failed", result.message)
        }
    }
}
