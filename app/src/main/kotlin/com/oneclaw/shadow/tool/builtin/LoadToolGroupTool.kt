package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.engine.ToolRegistry

/**
 * Meta-tool that loads all tools in a tool group on demand.
 * Mirrors the LoadSkillTool pattern from RFC-014.
 * After this tool is called successfully, SendMessageUseCase expands the
 * active tool list with the group's tools for subsequent LLM turns.
 */
class LoadToolGroupTool(
    private val toolRegistry: ToolRegistry
) : Tool {

    override val definition = ToolDefinition(
        name = "load_tool_group",
        description = "Load all tools in a tool group to make them available for use. " +
            "You MUST load a tool group before you can use any tools in it. " +
            "After loading, the tools will be available for the rest of this conversation.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "group_name" to ToolParameter(
                    type = "string",
                    description = "The name of the tool group to load"
                )
            ),
            required = listOf("group_name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val groupName = parameters["group_name"] as? String
            ?: return ToolResult.error(
                "missing_parameter",
                "Required parameter 'group_name' is missing."
            )

        val groupDef = toolRegistry.getGroupDefinition(groupName)
            ?: run {
                val available = toolRegistry.getAllGroupDefinitions()
                    .joinToString(", ") { it.name }
                return ToolResult.error(
                    "not_found",
                    "Tool group '$groupName' not found. Available groups: $available"
                )
            }

        val toolDefs = toolRegistry.getGroupToolDefinitions(groupName)
        if (toolDefs.isEmpty()) {
            return ToolResult.error(
                "empty_group",
                "Tool group '$groupName' has no available tools."
            )
        }

        val toolList = toolDefs.joinToString("\n") { def ->
            "- ${def.name}: ${def.description}"
        }

        return ToolResult.success(
            "Loaded ${toolDefs.size} tools from group '${groupDef.displayName}':\n$toolList"
        )
    }
}
