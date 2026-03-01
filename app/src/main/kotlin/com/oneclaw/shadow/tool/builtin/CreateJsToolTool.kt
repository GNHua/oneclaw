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
 * Creates a new JavaScript tool from AI-generated code,
 * saves it to the file system, and registers it in ToolRegistry.
 */
class CreateJsToolTool(
    private val userToolManager: UserToolManager
) : Tool {

    companion object {
        private const val DEFAULT_TIMEOUT = 30
    }

    override val definition = ToolDefinition(
        name = "create_js_tool",
        description = "Create a new JavaScript tool and register it for use. " +
            "The tool will be saved to the device and available across app restarts. " +
            "Always show the generated code to the user for review before calling this tool.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "name" to ToolParameter(
                    type = "string",
                    description = "Tool name: lowercase letters, numbers, and underscores. " +
                        "2-50 characters, must start with a letter. Example: 'fetch_weather'"
                ),
                "description" to ToolParameter(
                    type = "string",
                    description = "What the tool does. This is shown to the AI to decide " +
                        "when to use the tool. Be specific and clear."
                ),
                "parameters_schema" to ToolParameter(
                    type = "string",
                    description = "JSON string defining the tool's parameters. Format: " +
                        "{\"properties\": {\"param_name\": {\"type\": \"string\", " +
                        "\"description\": \"...\"}}, \"required\": [\"param_name\"]}"
                ),
                "js_code" to ToolParameter(
                    type = "string",
                    description = "JavaScript source code. Must define an execute(params) " +
                        "function (sync or async). Available APIs: fetch(url, options), " +
                        "fs.readFile(path), fs.writeFile(path, content), " +
                        "fs.appendFile(path, content), fs.exists(path), " +
                        "console.log/warn/error(), _time(timezone, format), lib(name)."
                ),
                "required_permissions" to ToolParameter(
                    type = "string",
                    description = "Comma-separated Android permission names the tool needs. " +
                        "Common: 'android.permission.MANAGE_EXTERNAL_STORAGE' for file access. " +
                        "Omit if no special permissions needed."
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Execution timeout in seconds (default: 30). " +
                        "Use higher values for tools that make HTTP requests."
                )
            ),
            required = listOf("name", "description", "parameters_schema", "js_code")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val name = parameters["name"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'name' is required")
        val description = parameters["description"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'description' is required")
        val schemaJson = parameters["parameters_schema"]?.toString()
            ?: return ToolResult.error(
                "validation_error",
                "Parameter 'parameters_schema' is required"
            )
        val jsCode = parameters["js_code"]?.toString()
            ?: return ToolResult.error("validation_error", "Parameter 'js_code' is required")
        val permissionsStr = parameters["required_permissions"]?.toString()
        val timeoutSeconds = (parameters["timeout_seconds"] as? Number)?.toInt()
            ?: DEFAULT_TIMEOUT

        // Parse parameters schema
        val schema = try {
            parseParametersSchema(schemaJson)
        } catch (e: Exception) {
            return ToolResult.error(
                "validation_error",
                "Invalid parameters_schema JSON: ${e.message}"
            )
        }

        // Parse permissions
        val permissions = permissionsStr
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // Delegate to UserToolManager
        return when (val result = userToolManager.create(
            name = name,
            description = description,
            parametersSchema = schema,
            jsCode = jsCode,
            requiredPermissions = permissions,
            timeoutSeconds = timeoutSeconds
        )) {
            is AppResult.Success -> ToolResult.success(result.data)
            is AppResult.Error -> ToolResult.error("create_failed", result.message)
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
