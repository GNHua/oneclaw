package com.oneclaw.shadow.tool.js

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import java.io.File

/**
 * Manages user-created JS tools: file I/O, validation, and registry operations.
 * Shared by all CRUD tool implementations (CreateJsToolTool, ListUserToolsTool,
 * UpdateJsToolTool, DeleteJsToolTool).
 *
 * The [toolRegistryProvider] is a lazy lambda to avoid a circular dependency with
 * ToolRegistry: UserToolManager is declared in Koin before ToolRegistry, so direct
 * injection would fail. The lambda is only evaluated when the first method is called,
 * by which time ToolRegistry is fully constructed.
 */
class UserToolManager(
    private val context: Context,
    private val toolRegistryProvider: () -> ToolRegistry,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) {
    private val toolRegistry: ToolRegistry get() = toolRegistryProvider()

    companion object {
        private const val TAG = "UserToolManager"
        private val NAME_REGEX = Regex("^[a-z][a-z0-9_]{0,48}[a-z0-9]$")
        private const val TOOLS_DIR = "tools"
    }

    /** Directory where user tools are stored. */
    val toolsDir: File
        get() = File(context.filesDir, TOOLS_DIR).also { it.mkdirs() }

    /**
     * Create a new JS tool.
     *
     * @param name Tool name (validated against NAME_REGEX)
     * @param description Tool description for AI
     * @param parametersSchema Parsed parameters schema
     * @param jsCode JavaScript source code
     * @param requiredPermissions List of Android permissions
     * @param timeoutSeconds Execution timeout
     * @return AppResult with success message or error
     */
    fun create(
        name: String,
        description: String,
        parametersSchema: ToolParametersSchema,
        jsCode: String,
        requiredPermissions: List<String>,
        timeoutSeconds: Int
    ): AppResult<String> {
        // Validate name
        if (!NAME_REGEX.matches(name)) {
            return AppResult.Error(
                message = "Invalid tool name '$name'. Must be 2-50 lowercase " +
                    "letters, numbers, and underscores, starting with a letter."
            )
        }

        // Check for duplicates
        if (toolRegistry.hasTool(name)) {
            return AppResult.Error(message = "Tool '$name' already exists.")
        }

        // Validate JS code is not blank
        if (jsCode.isBlank()) {
            return AppResult.Error(message = "JavaScript code cannot be empty.")
        }

        val definition = ToolDefinition(
            name = name,
            description = description,
            parametersSchema = parametersSchema,
            requiredPermissions = requiredPermissions,
            timeoutSeconds = timeoutSeconds
        )

        return try {
            // Write manifest file
            val manifestFile = File(toolsDir, "$name.json")
            val manifest = buildManifestJson(definition)
            manifestFile.writeText(manifest)

            // Write JS source file
            val jsFile = File(toolsDir, "$name.js")
            jsFile.writeText(jsCode)

            // Create JsTool and register
            val jsTool = JsTool(
                definition = definition,
                jsFilePath = jsFile.absolutePath,
                jsExecutionEngine = jsExecutionEngine,
                envVarStore = envVarStore
            )
            val sourceInfo = ToolSourceInfo(
                type = ToolSourceType.JS_EXTENSION,
                filePath = jsFile.absolutePath
            )
            toolRegistry.register(jsTool, sourceInfo)

            Log.i(TAG, "Created user tool: $name")
            AppResult.Success("Tool '$name' created and registered successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create tool: $name", e)
            // Clean up partial files
            File(toolsDir, "$name.json").delete()
            File(toolsDir, "$name.js").delete()
            AppResult.Error(message = "Failed to create tool: ${e.message}")
        }
    }

    /**
     * List all user-created tools.
     *
     * @return List of UserToolInfo for user tools
     */
    fun listUserTools(): List<UserToolInfo> {
        val allSourceInfo = toolRegistry.getAllToolSourceInfo()
        return allSourceInfo
            .filter { (_, info) ->
                info.type == ToolSourceType.JS_EXTENSION &&
                    info.filePath?.startsWith(toolsDir.absolutePath) == true
            }
            .mapNotNull { (name, info) ->
                val tool = toolRegistry.getTool(name) ?: return@mapNotNull null
                UserToolInfo(
                    name = name,
                    description = tool.definition.description,
                    filePath = info.filePath ?: ""
                )
            }
            .sortedBy { it.name }
    }

    /**
     * Update an existing user tool.
     *
     * @param name Tool name to update
     * @param description New description (null to keep existing)
     * @param parametersSchema New schema (null to keep existing)
     * @param jsCode New JS code (null to keep existing)
     * @param requiredPermissions New permissions (null to keep existing)
     * @param timeoutSeconds New timeout (null to keep existing)
     * @return AppResult with success message or error
     */
    fun update(
        name: String,
        description: String?,
        parametersSchema: ToolParametersSchema?,
        jsCode: String?,
        requiredPermissions: List<String>?,
        timeoutSeconds: Int?
    ): AppResult<String> {
        // Verify tool exists
        val existingTool = toolRegistry.getTool(name)
            ?: return AppResult.Error(message = "Tool '$name' not found.")

        // Verify it's a user tool
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error(message = "Cannot update tool '$name': not a user-created tool.")
        }

        // Merge with existing definition
        val existingDef = existingTool.definition
        val newDefinition = ToolDefinition(
            name = name,
            description = description ?: existingDef.description,
            parametersSchema = parametersSchema ?: existingDef.parametersSchema,
            requiredPermissions = requiredPermissions ?: existingDef.requiredPermissions,
            timeoutSeconds = timeoutSeconds ?: existingDef.timeoutSeconds
        )

        // Read existing JS code if not updating
        val newJsCode = jsCode ?: File(toolsDir, "$name.js").readText()

        return try {
            // Unregister old tool
            toolRegistry.unregister(name)

            // Write updated manifest
            val manifestFile = File(toolsDir, "$name.json")
            manifestFile.writeText(buildManifestJson(newDefinition))

            // Write updated JS source (if changed)
            if (jsCode != null) {
                val jsFile = File(toolsDir, "$name.js")
                jsFile.writeText(jsCode)
            }

            // Re-register
            val jsFile = File(toolsDir, "$name.js")
            val jsTool = JsTool(
                definition = newDefinition,
                jsFilePath = jsFile.absolutePath,
                jsExecutionEngine = jsExecutionEngine,
                envVarStore = envVarStore
            )
            val newSourceInfo = ToolSourceInfo(
                type = ToolSourceType.JS_EXTENSION,
                filePath = jsFile.absolutePath
            )
            toolRegistry.register(jsTool, newSourceInfo)

            Log.i(TAG, "Updated user tool: $name")
            AppResult.Success("Tool '$name' updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tool: $name", e)
            AppResult.Error(message = "Failed to update tool: ${e.message}")
        }
    }

    /**
     * Delete a user tool.
     *
     * @param name Tool name to delete
     * @return AppResult with success message or error
     */
    fun delete(name: String): AppResult<String> {
        // Verify tool exists
        if (!toolRegistry.hasTool(name)) {
            return AppResult.Error(message = "Tool '$name' not found.")
        }

        // Verify it's a user tool
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error(message = "Cannot delete tool '$name': not a user-created tool.")
        }

        return try {
            // Unregister
            toolRegistry.unregister(name)

            // Delete files
            File(toolsDir, "$name.json").delete()
            File(toolsDir, "$name.js").delete()

            Log.i(TAG, "Deleted user tool: $name")
            AppResult.Success("Tool '$name' deleted successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete tool: $name", e)
            AppResult.Error(message = "Failed to delete tool: ${e.message}")
        }
    }

    /**
     * Build JSON manifest string from a ToolDefinition.
     */
    private fun buildManifestJson(definition: ToolDefinition): String {
        val json = buildJsonObject {
            put("name", definition.name)
            put("description", definition.description)
            putJsonObject("parameters") {
                putJsonObject("properties") {
                    for ((paramName, param) in definition.parametersSchema.properties) {
                        putJsonObject(paramName) {
                            put("type", param.type)
                            put("description", param.description)
                            param.enum?.let { enumValues ->
                                putJsonArray("enum") {
                                    enumValues.forEach { add(JsonPrimitive(it)) }
                                }
                            }
                        }
                    }
                }
                if (definition.parametersSchema.required.isNotEmpty()) {
                    putJsonArray("required") {
                        definition.parametersSchema.required.forEach { add(JsonPrimitive(it)) }
                    }
                }
            }
            if (definition.requiredPermissions.isNotEmpty()) {
                putJsonArray("requiredPermissions") {
                    definition.requiredPermissions.forEach { add(JsonPrimitive(it)) }
                }
            }
            put("timeoutSeconds", definition.timeoutSeconds)
        }
        return Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), json)
    }
}

data class UserToolInfo(
    val name: String,
    val description: String,
    val filePath: String
)
