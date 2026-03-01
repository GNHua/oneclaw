package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.UserToolManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Updates an existing user-created JavaScript tool.
 * Only the specified fields are updated; others are preserved.
 */
class UpdateJsToolTool(
    private val userToolManager: UserToolManager
) : Tool {

    override val definition = ToolDefinition(
        name = "update_js_tool",
        description = "Update an existing user-created JavaScript tool. " +
            "Only specify the fields you want to change; others are preserved. " +
            "Cannot update built-in tools.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Name of the tool to update"
                ),
                "description" to ToolParameter(
                    type = "string",
                    description = "New description for the tool"
                ),
                "parameters_schema" to ToolParameter(
                    type = "string",
                    description = "New parameters schema JSON string"
                ),
                "js_code" to ToolParameter(
                    type = "string",
                    description = "New JavaScript source code"
                ),
                "required_permissions" to ToolParameter(
                    type = "string",
                    description = "New comma-separated Android permission names"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "New execution timeout in seconds"
                )
            ),
            required = listOf("name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'name' is required")
        val description = parameters["description"]?.toString()
        val schemaJson = parameters["parameters_schema"]?.toString()
        val jsCode = parameters["js_code"]?.toString()
        val permissionsStr = parameters["required_permissions"]?.toString()
        val timeoutSeconds = (parameters["timeout_seconds"] as? Number)?.toInt()

        // Parse schema if provided
        val schema = if (schemaJson != null) {
            try {
                parseParametersSchema(schemaJson)
            } catch (e: Exception) {
                return ToolResult.error(
                    "validation_error",
                    "Invalid parameters_schema JSON: ${e.message}"
                )
            }
        } else null

        // Parse permissions if provided
        val permissions = permissionsStr
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }

        return when (val result = userToolManager.update(
            name = name,
            description = description,
            parametersSchema = schema,
            jsCode = jsCode,
            requiredPermissions = permissions,
            timeoutSeconds = timeoutSeconds
        )) {
            is AppResult.Success -> ToolResult.success(result.data)
            is AppResult.Error -> ToolResult.error("update_failed", result.message)
        }
    }

    private fun parseParametersSchema(json: String): com.oneclaw.shadow.core.model.ToolParametersSchema {
        val element = Json.parseToJsonElement(json).jsonObject
        val propertiesObj = element["properties"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'properties' field")
        val requiredList = element["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        val properties = propertiesObj.entries.associate { (key, value) ->
            val paramObj = value.jsonObject
            key to com.oneclaw.shadow.core.model.ToolParameter(
                type = paramObj["type"]?.jsonPrimitive?.content ?: "string",
                description = paramObj["description"]?.jsonPrimitive?.content ?: "",
                enum = paramObj["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            )
        }

        return com.oneclaw.shadow.core.model.ToolParametersSchema(
            properties = properties,
            required = requiredList
        )
    }
}
