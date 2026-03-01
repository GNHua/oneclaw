package com.oneclaw.shadow.tool.js

import android.content.Context
import android.os.Environment
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.int
import java.io.File

/**
 * Scans tool directories for .json + .js file pairs, validates them,
 * and creates JsTool instances ready for registration.
 */
class JsToolLoader(
    private val context: Context,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) {
    companion object {
        private const val TAG = "JsToolLoader"
        private const val EXTERNAL_TOOLS_DIR = "OneClawShadow/tools"
        private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]*$")
    }

    data class LoadResult(
        val loadedTools: List<JsTool>,
        val errors: List<ToolLoadError>
    )

    data class ToolLoadError(
        val fileName: String,
        val error: String
    )

    /**
     * Scan tool directories and return all valid JsTool instances.
     * Invalid tools are skipped with errors recorded.
     */
    fun loadTools(): LoadResult {
        val tools = mutableListOf<JsTool>()
        val errors = mutableListOf<ToolLoadError>()

        val directories = getToolDirectories()

        for (dir in directories) {
            if (!dir.exists()) {
                dir.mkdirs()
                continue
            }

            val jsonFiles = dir.listFiles { file ->
                file.extension == "json" && file.isFile
            } ?: continue

            for (jsonFile in jsonFiles) {
                val baseName = jsonFile.nameWithoutExtension
                val jsFile = File(dir, "$baseName.js")

                if (!jsFile.exists()) {
                    errors.add(ToolLoadError(
                        jsonFile.name,
                        "Missing corresponding .js file: ${jsFile.name}"
                    ))
                    continue
                }

                try {
                    val tool = loadSingleTool(jsonFile, jsFile)
                    tools.add(tool)
                } catch (e: Exception) {
                    errors.add(ToolLoadError(
                        jsonFile.name,
                        "Failed to load: ${e.message}"
                    ))
                }
            }
        }

        return LoadResult(tools, errors)
    }

    private fun loadSingleTool(jsonFile: File, jsFile: File): JsTool {
        val jsonContent = jsonFile.readText()
        val metadata = parseAndValidateMetadata(jsonContent, jsonFile.nameWithoutExtension)

        return JsTool(
            definition = metadata,
            jsFilePath = jsFile.absolutePath,
            jsExecutionEngine = jsExecutionEngine,
            envVarStore = envVarStore
        )
    }

    /**
     * Parse JSON metadata and validate against ToolDefinition schema.
     */
    private fun parseAndValidateMetadata(jsonContent: String, expectedName: String): ToolDefinition {
        val json = Json.parseToJsonElement(jsonContent).jsonObject

        val name = json["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required field: 'name'")

        if (name != expectedName) {
            throw IllegalArgumentException(
                "Tool name '$name' does not match filename '$expectedName'"
            )
        }

        if (!TOOL_NAME_REGEX.matches(name)) {
            throw IllegalArgumentException(
                "Tool name '$name' must be snake_case (lowercase letters, digits, underscores)"
            )
        }

        val description = json["description"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required field: 'description'")

        val parametersObj = json["parameters"]?.jsonObject
        val parametersSchema = if (parametersObj != null) {
            parseParametersSchema(parametersObj)
        } else {
            ToolParametersSchema(properties = emptyMap(), required = emptyList())
        }

        val requiredPermissions = json["requiredPermissions"]?.jsonArray
            ?.map { it.jsonPrimitive.content } ?: emptyList()

        val timeoutSeconds = json["timeoutSeconds"]?.jsonPrimitive?.int ?: 30

        return ToolDefinition(
            name = name,
            description = description,
            parametersSchema = parametersSchema,
            requiredPermissions = requiredPermissions,
            timeoutSeconds = timeoutSeconds
        )
    }

    private fun parseParametersSchema(obj: JsonObject): ToolParametersSchema {
        val propertiesObj = obj["properties"]?.jsonObject ?: return ToolParametersSchema(
            properties = emptyMap(), required = emptyList()
        )

        val properties = propertiesObj.entries.associate { (name, value) ->
            val paramObj = value.jsonObject
            name to ToolParameter(
                type = paramObj["type"]?.jsonPrimitive?.content ?: "string",
                description = paramObj["description"]?.jsonPrimitive?.content ?: "",
                enum = paramObj["enum"]?.jsonArray?.map { it.jsonPrimitive.content },
                default = paramObj["default"]?.let { extractDefault(it) }
            )
        }

        val required = obj["required"]?.jsonArray?.map { it.jsonPrimitive.content } ?: emptyList()

        return ToolParametersSchema(properties = properties, required = required)
    }

    private fun extractDefault(element: JsonElement): Any? = when {
        element is JsonNull -> null
        element is JsonPrimitive && element.isString -> element.content
        element is JsonPrimitive -> element.content  // number or boolean as string
        else -> element.toString()
    }

    private fun getToolDirectories(): List<File> {
        val dirs = mutableListOf<File>()

        // External storage: /sdcard/OneClawShadow/tools/
        val externalDir = File(
            Environment.getExternalStorageDirectory(),
            EXTERNAL_TOOLS_DIR
        )
        dirs.add(externalDir)

        // App internal: {app_files}/tools/
        val internalDir = File(context.filesDir, "tools")
        dirs.add(internalDir)

        return dirs
    }

    /**
     * Register loaded JS tools into the ToolRegistry.
     * Skips tools that conflict with already-registered names (built-in tools win).
     */
    fun registerTools(registry: ToolRegistry, tools: List<JsTool>): List<ToolLoadError> {
        val conflicts = mutableListOf<ToolLoadError>()

        for (tool in tools) {
            if (registry.hasTool(tool.definition.name)) {
                conflicts.add(ToolLoadError(
                    "${tool.definition.name}.json",
                    "Name conflict with existing tool '${tool.definition.name}' (skipped)"
                ))
                Log.w(TAG, "JS tool '${tool.definition.name}' skipped: name conflicts with existing tool")
                continue
            }
            registry.register(tool)
            Log.i(TAG, "Registered JS tool: ${tool.definition.name}")
        }

        return conflicts
    }
}
