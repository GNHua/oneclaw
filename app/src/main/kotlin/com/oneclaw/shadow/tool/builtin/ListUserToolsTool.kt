package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.UserToolManager

/**
 * Lists all user-created JavaScript tools with their
 * name, description, and source file path.
 */
class ListUserToolsTool(
    private val userToolManager: UserToolManager
) : Tool {

    override val definition = ToolDefinition(
        name = "list_user_tools",
        description = "List all user-created JavaScript tools with their " +
            "name and description.",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val tools = userToolManager.listUserTools()

        if (tools.isEmpty()) {
            return ToolResult.success("No user-created tools found.")
        }

        val lines = mutableListOf<String>()
        lines.add("User-created tools (${tools.size}):")
        lines.add("")
        for (tool in tools) {
            lines.add("- ${tool.name}: ${tool.description}")
            lines.add("  File: ${tool.filePath}")
        }

        return ToolResult.success(lines.joinToString("\n"))
    }
}
