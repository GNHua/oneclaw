package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.skill.SkillRegistry

/**
 * Built-in tool that loads the full prompt instructions for a skill.
 * Enables AI self-invocation of skills (Trigger Path 3 in RFC-014).
 */
class LoadSkillTool(
    private val skillRegistry: SkillRegistry
) : Tool {

    override val definition = ToolDefinition(
        name = "load_skill",
        description = "Load the full prompt instructions for a skill. " +
            "Use this when the user requests a skill or when you recognize " +
            "that a task matches an available skill.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "The skill name to load (e.g., 'summarize-file')"
                )
            ),
            required = listOf("name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"] as? String
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'name' is required and must be a string"
            )

        val skill = skillRegistry.getSkill(name)
            ?: return ToolResult.error(
                "skill_not_found",
                "Skill '$name' not found. Available skills: " +
                    skillRegistry.getAllSkills().joinToString(", ") { it.name }
            )

        return when (val result = skillRegistry.loadSkillContent(name)) {
            is AppResult.Success -> {
                val header = buildString {
                    appendLine("# Skill: ${skill.displayName}")
                    appendLine("Description: ${skill.description}")
                    if (skill.toolsRequired.isNotEmpty()) {
                        appendLine("Required tools: ${skill.toolsRequired.joinToString(", ")}")
                    }
                    if (skill.parameters.isNotEmpty()) {
                        appendLine("Parameters:")
                        skill.parameters.forEach { param ->
                            val req = if (param.required) "(required)" else "(optional)"
                            appendLine("  - ${param.name} $req: ${param.description}")
                        }
                    }
                    appendLine()
                    appendLine("---")
                    appendLine()
                }
                ToolResult.success(header + result.data)
            }
            is AppResult.Error -> {
                ToolResult.error("load_error", "Failed to load skill '$name': ${result.message}")
            }
        }
    }
}
