package com.oneclaw.shadow.tool.js

import android.content.Context
import android.os.Environment
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
        private const val ASSETS_TOOLS_DIR = "js/tools"
        private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]*$")
        private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_\$][a-zA-Z0-9_\$]*$")
        private const val MAX_GROUP_SIZE = 50
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
     * Load built-in JS tools from assets/js/tools/.
     * Scans for .json + .js pairs in the assets directory.
     */
    fun loadBuiltinTools(): LoadResult {
        val tools = mutableListOf<JsTool>()
        val errors = mutableListOf<ToolLoadError>()

        val assetFiles = try {
            context.assets.list(ASSETS_TOOLS_DIR) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list assets/$ASSETS_TOOLS_DIR: ${e.message}")
            return LoadResult(emptyList(), emptyList())
        }

        // Find all .json files and look for matching .js files
        val jsonFiles = assetFiles.filter { it.endsWith(".json") }

        for (jsonFileName in jsonFiles) {
            val baseName = jsonFileName.removeSuffix(".json")
            val jsFileName = "$baseName.js"

            if (jsFileName !in assetFiles) {
                errors.add(ToolLoadError(
                    jsonFileName,
                    "Missing corresponding .js file: $jsFileName"
                ))
                continue
            }

            try {
                val jsonContent = readAsset("$ASSETS_TOOLS_DIR/$jsonFileName")
                val jsSource = readAsset("$ASSETS_TOOLS_DIR/$jsFileName")
                val parsed = parseJsonManifest(jsonContent, baseName, jsonFileName)

                for ((definition, functionName) in parsed) {
                    tools.add(JsTool(
                        definition = definition,
                        jsSource = jsSource,
                        functionName = functionName,
                        jsExecutionEngine = jsExecutionEngine,
                        envVarStore = envVarStore
                    ))
                }
            } catch (e: Exception) {
                errors.add(ToolLoadError(
                    jsonFileName,
                    "Failed to load built-in tool: ${e.message}"
                ))
            }
        }

        return LoadResult(tools, errors)
    }

    private fun readAsset(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
    }

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
                    val jsonContent = jsonFile.readText()
                    val parsed = parseJsonManifest(jsonContent, baseName, jsonFile.name)

                    for ((definition, functionName) in parsed) {
                        tools.add(JsTool(
                            definition = definition,
                            jsFilePath = jsFile.absolutePath,
                            functionName = functionName,
                            jsExecutionEngine = jsExecutionEngine,
                            envVarStore = envVarStore
                        ))
                    }
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

    /**
     * Parse a JSON manifest, detecting single-tool (object) or group (array) format.
     * Returns a list of (ToolDefinition, functionName?) pairs.
     *
     * - Object format: returns [(definition, null)]  -- single tool, calls execute()
     * - Array format:  returns [(def1, fn1), (def2, fn2), ...]  -- group, calls named functions
     */
    internal fun parseJsonManifest(
        jsonContent: String,
        baseName: String,
        fileName: String
    ): List<Pair<ToolDefinition, String?>> {
        val element = Json.parseToJsonElement(jsonContent)

        return when {
            element is JsonObject -> {
                // Single-tool mode (existing behavior)
                val definition = parseToolEntry(element, requireNameMatch = baseName)
                listOf(Pair(definition, null))
            }
            element is JsonArray -> {
                // Group mode (new)
                parseGroupManifest(element, baseName, fileName)
            }
            else -> throw IllegalArgumentException("JSON must be an object or array")
        }
    }

    /**
     * Parse a group manifest (JSON array).
     * Each entry must have "name", "description", "function".
     * Invalid entries are logged and skipped; valid entries are returned.
     */
    private fun parseGroupManifest(
        array: JsonArray,
        baseName: String,
        fileName: String
    ): List<Pair<ToolDefinition, String?>> {
        if (array.isEmpty()) {
            Log.w(TAG, "Empty tool group in '$fileName'")
            return emptyList()
        }

        if (array.size > MAX_GROUP_SIZE) {
            throw IllegalArgumentException(
                "Tool group in '$fileName' has ${array.size} entries (maximum: $MAX_GROUP_SIZE)"
            )
        }

        val results = mutableListOf<Pair<ToolDefinition, String?>>()
        val seenNames = mutableSetOf<String>()

        for ((index, entry) in array.withIndex()) {
            try {
                val obj = entry.jsonObject
                val name = obj["name"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException("Missing 'name'")

                // Duplicate check within this group
                if (name in seenNames) {
                    Log.w(TAG, "Duplicate tool name '$name' in group '$fileName' (entry $index skipped)")
                    continue
                }

                val functionName = obj["function"]?.jsonPrimitive?.content
                    ?: throw IllegalArgumentException(
                        "Tool '$name' in group '$fileName' missing required 'function' field"
                    )

                // Validate function name (prevent code injection)
                if (!FUNCTION_NAME_REGEX.matches(functionName)) {
                    throw IllegalArgumentException(
                        "Invalid function name '$functionName' for tool '$name'"
                    )
                }

                // Parse tool definition (no filename-name match requirement for groups)
                val definition = parseToolEntry(obj, requireNameMatch = null)

                seenNames.add(name)
                results.add(Pair(definition, functionName))
            } catch (e: Exception) {
                Log.w(TAG, "Skipping entry $index in group '$fileName': ${e.message}")
            }
        }

        return results
    }

    /**
     * Parse a single tool entry (used for both single-tool and group entries).
     *
     * @param requireNameMatch If non-null, the tool name must match this string
     *                         (for single-tool mode: name must match filename).
     *                         Null for group mode (names are independent of filename).
     */
    private fun parseToolEntry(
        json: JsonObject,
        requireNameMatch: String?
    ): ToolDefinition {
        val name = json["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing required field: 'name'")

        if (requireNameMatch != null && name != requireNameMatch) {
            throw IllegalArgumentException(
                "Tool name '$name' does not match filename '$requireNameMatch'"
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
     * When allowOverride=true, user tools can override existing tools (e.g., built-ins).
     * When allowOverride=false, conflicts are skipped and recorded as errors.
     */
    fun registerTools(
        registry: ToolRegistry,
        tools: List<JsTool>,
        allowOverride: Boolean = false
    ): List<ToolLoadError> {
        val conflicts = mutableListOf<ToolLoadError>()

        for (tool in tools) {
            if (registry.hasTool(tool.definition.name)) {
                if (allowOverride) {
                    registry.unregister(tool.definition.name)
                    registry.register(tool)
                    Log.i(TAG, "User JS tool '${tool.definition.name}' overrides built-in")
                } else {
                    conflicts.add(ToolLoadError(
                        "${tool.definition.name}.json",
                        "Name conflict with existing tool '${tool.definition.name}' (skipped)"
                    ))
                    Log.w(TAG, "JS tool '${tool.definition.name}' skipped: name conflict")
                }
                continue
            }
            registry.register(tool)
            Log.i(TAG, "Registered JS tool: ${tool.definition.name}")
        }

        return conflicts
    }
}
