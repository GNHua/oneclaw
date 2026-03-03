# RFC-035: JS Tool Creator

## Document Information
- **RFC ID**: RFC-035
- **Related PRD**: [FEAT-035 (JS Tool Creator)](../../prd/features/FEAT-035-js-tool-creator.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-014 (Agent Skills)](RFC-014-agent-skill.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw has a mature JavaScript tool system powered by QuickJS (RFC-004). Users can extend the agent's capabilities by placing `.json` manifest and `.js` source files in the tools directory. However, this requires users to manually create files with correct formats outside the app -- a barrier for non-technical users.

This RFC adds the ability to create, update, list, and delete custom JS tools directly from the chat screen. The AI generates the tool code based on the user's natural language description, and a set of built-in Kotlin tools handle the file I/O and registry operations. A companion skill (`create-tool`) guides the AI through the creation workflow with user confirmation before saving.

### Goals

1. Implement four built-in Kotlin tools: `CreateJsToolTool`, `ListUserToolsTool`, `UpdateJsToolTool`, `DeleteJsToolTool`
2. Create the `create-tool` skill to guide AI-assisted tool creation
3. Persist created tools to the file system for survival across app restarts
4. Register created tools in ToolRegistry immediately (no restart required)
5. Protect built-in tools from modification or deletion
6. Add unit tests for all new tools

### Non-Goals

- Visual code editor or IDE-like tool editing UI
- Tool sharing, export/import between devices
- Tool group creation (RFC-018 multi-tool format) -- single tool per file only
- Tool versioning or rollback
- Automated testing of created tools before registration
- JS syntax validation beyond basic checks

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                      Chat Layer (RFC-001)                         │
│  SendMessageUseCase                                              │
│       │                                                          │
│       │  1. AI loads /create-tool skill (or user describes need) │
│       │  2. AI designs tool, shows code for review               │
│       │  3. User confirms                                        │
│       │  4. AI calls create_js_tool / update / delete / list     │
│       v                                                          │
├──────────────────────────────────────────────────────────────────┤
│                   Tool Execution Engine (RFC-004)                 │
│  executeTool(name, params, availableToolIds)                     │
│       │                                                          │
│       v                                                          │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                      ToolRegistry                          │  │
│  │                                                            │  │
│  │  ┌────────────────┐  ┌─────────────────┐                  │  │
│  │  │ create_js_tool │  │ list_user_tools │                  │  │
│  │  │(CreateJsTool   │  │(ListUserTools   │                  │  │
│  │  │        Tool)   │  │         Tool)   │                  │  │
│  │  └───────┬────────┘  └────────┬────────┘                  │  │
│  │          │                    │                            │  │
│  │  ┌────────────────┐  ┌─────────────────┐                  │  │
│  │  │ update_js_tool │  │ delete_js_tool  │                  │  │
│  │  │(UpdateJsTool   │  │(DeleteJsTool    │                  │  │
│  │  │        Tool)   │  │         Tool)   │                  │  │
│  │  └───────┬────────┘  └────────┬────────┘                  │  │
│  │          │                    │                            │  │
│  │          v                    v                            │  │
│  │  ┌─────────────────────────────────────────────────────┐  │  │
│  │  │              UserToolManager [NEW]                   │  │  │
│  │  │  - create(name, definition, jsCode)                 │  │  │
│  │  │  - update(name, definition?, jsCode?)               │  │  │
│  │  │  - delete(name)                                     │  │  │
│  │  │  - listUserTools()                                  │  │  │
│  │  │  - getUserTool(name)                                │  │  │
│  │  └──────────┬──────────────────────────────────────────┘  │  │
│  │             │                                             │  │
│  │             v                                             │  │
│  │  ┌─────────────────┐  ┌──────────────────┐               │  │
│  │  │  File System     │  │   ToolRegistry   │               │  │
│  │  │  {filesDir}/     │  │   register()     │               │  │
│  │  │  tools/          │  │   unregister()   │               │  │
│  │  │  *.json + *.js   │  │                  │               │  │
│  │  └─────────────────┘  └──────────────────┘               │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘

Skill Layer:
┌──────────────────────────────────────────┐
│  assets/skills/create-tool/SKILL.md      │
│  - Guides AI through tool creation       │
│  - Documents available bridge APIs       │
│  - Requires user confirmation            │
└──────────────────────────────────────────┘
```

### Core Components

**New:**
1. `UserToolManager` -- Shared logic for user tool CRUD operations (file I/O + registry)
2. `CreateJsToolTool` -- Built-in tool to create a new JS tool
3. `ListUserToolsTool` -- Built-in tool to list user-created JS tools
4. `UpdateJsToolTool` -- Built-in tool to update an existing user JS tool
5. `DeleteJsToolTool` -- Built-in tool to delete a user JS tool
6. `create-tool` skill -- SKILL.md guiding the creation workflow

**Modified:**
7. `ToolModule` -- Register the four new tools
8. `JsToolLoader` -- Add `getUserToolsDir()` public accessor

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── assets/
│   └── skills/
│       └── create-tool/
│           └── SKILL.md                         # NEW
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── CreateJsToolTool.kt              # NEW
│   │   │   ├── ListUserToolsTool.kt             # NEW
│   │   │   ├── UpdateJsToolTool.kt              # NEW
│   │   │   ├── DeleteJsToolTool.kt              # NEW
│   │   │   ├── LoadSkillTool.kt                 # unchanged
│   │   │   └── ...                              # unchanged
│   │   ├── js/
│   │   │   ├── UserToolManager.kt               # NEW
│   │   │   ├── JsToolLoader.kt                  # MODIFIED
│   │   │   └── ...                              # unchanged
│   │   └── engine/
│   │       └── ...                              # unchanged
│   └── di/
│       └── ToolModule.kt                        # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   ├── CreateJsToolToolTest.kt          # NEW
        │   ├── ListUserToolsToolTest.kt         # NEW
        │   ├── UpdateJsToolToolTest.kt          # NEW
        │   └── DeleteJsToolToolTest.kt          # NEW
        └── js/
            └── UserToolManagerTest.kt           # NEW
```

### UserToolManager

```kotlin
/**
 * Located in: tool/js/UserToolManager.kt
 *
 * Manages user-created JS tools: file I/O, validation, and registry
 * operations. Shared by all CRUD tool implementations.
 */
class UserToolManager(
    private val context: Context,
    private val toolRegistry: ToolRegistry,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) {

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
     * @param parametersSchema Parsed parameters schema map
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
                "Invalid tool name '$name'. Must be 2-50 lowercase " +
                    "letters, numbers, and underscores, starting with a letter."
            )
        }

        // Check for duplicates
        if (toolRegistry.hasTool(name)) {
            return AppResult.Error("Tool '$name' already exists.")
        }

        // Validate JS code is not blank
        if (jsCode.isBlank()) {
            return AppResult.Error("JavaScript code cannot be empty.")
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
                jsSource = jsCode,
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
            AppResult.Error("Failed to create tool: ${e.message}")
        }
    }

    /**
     * List all user-created tools.
     *
     * @return List of pairs (toolName, description) for user tools
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
            ?: return AppResult.Error("Tool '$name' not found.")

        // Verify it's a user tool
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error("Cannot update tool '$name': not a user-created tool.")
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
            val jsTool = JsTool(
                definition = newDefinition,
                jsSource = newJsCode,
                jsExecutionEngine = jsExecutionEngine,
                envVarStore = envVarStore
            )
            val newSourceInfo = ToolSourceInfo(
                type = ToolSourceType.JS_EXTENSION,
                filePath = File(toolsDir, "$name.js").absolutePath
            )
            toolRegistry.register(jsTool, newSourceInfo)

            Log.i(TAG, "Updated user tool: $name")
            AppResult.Success("Tool '$name' updated successfully.")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update tool: $name", e)
            AppResult.Error("Failed to update tool: ${e.message}")
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
            return AppResult.Error("Tool '$name' not found.")
        }

        // Verify it's a user tool
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error("Cannot delete tool '$name': not a user-created tool.")
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
            AppResult.Error("Failed to delete tool: ${e.message}")
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
                                    enumValues.forEach { add(it) }
                                }
                            }
                        }
                    }
                }
                if (definition.parametersSchema.required.isNotEmpty()) {
                    putJsonArray("required") {
                        definition.parametersSchema.required.forEach { add(it) }
                    }
                }
            }
            if (definition.requiredPermissions.isNotEmpty()) {
                putJsonArray("requiredPermissions") {
                    definition.requiredPermissions.forEach { add(it) }
                }
            }
            put("timeoutSeconds", definition.timeoutSeconds)
        }
        return Json { prettyPrint = true }.encodeToString(json)
    }
}

data class UserToolInfo(
    val name: String,
    val description: String,
    val filePath: String
)
```

### CreateJsToolTool

```kotlin
/**
 * Located in: tool/builtin/CreateJsToolTool.kt
 *
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
                        "Example: 'android.permission.ACCESS_FINE_LOCATION' for location access. " +
                        "Omit if no special permissions needed (most tools need none)."
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

    private fun parseParametersSchema(json: String): ToolParametersSchema {
        val element = Json.parseToJsonElement(json).jsonObject
        val propertiesObj = element["properties"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'properties' field")
        val requiredList = element["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        val properties = propertiesObj.entries.associate { (key, value) ->
            val paramObj = value.jsonObject
            key to ToolParameter(
                type = paramObj["type"]?.jsonPrimitive?.content ?: "string",
                description = paramObj["description"]?.jsonPrimitive?.content ?: "",
                enum = paramObj["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            )
        }

        return ToolParametersSchema(
            properties = properties,
            required = requiredList
        )
    }
}
```

### ListUserToolsTool

```kotlin
/**
 * Located in: tool/builtin/ListUserToolsTool.kt
 *
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
```

### UpdateJsToolTool

```kotlin
/**
 * Located in: tool/builtin/UpdateJsToolTool.kt
 *
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

    private fun parseParametersSchema(json: String): ToolParametersSchema {
        val element = Json.parseToJsonElement(json).jsonObject
        val propertiesObj = element["properties"]?.jsonObject
            ?: throw IllegalArgumentException("Missing 'properties' field")
        val requiredList = element["required"]?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: emptyList()

        val properties = propertiesObj.entries.associate { (key, value) ->
            val paramObj = value.jsonObject
            key to ToolParameter(
                type = paramObj["type"]?.jsonPrimitive?.content ?: "string",
                description = paramObj["description"]?.jsonPrimitive?.content ?: "",
                enum = paramObj["enum"]?.jsonArray?.map { it.jsonPrimitive.content }
            )
        }

        return ToolParametersSchema(
            properties = properties,
            required = requiredList
        )
    }
}
```

### DeleteJsToolTool

```kotlin
/**
 * Located in: tool/builtin/DeleteJsToolTool.kt
 *
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
```

### create-tool Skill

```markdown
---
name: create-tool
display_name: Create Tool
description: Guide the user through creating a custom JavaScript tool
version: "1.0"
tools_required:
  - create_js_tool
parameters:
  - name: idea
    type: string
    required: false
    description: Brief description of what the tool should do
---

# Create Tool

You are helping the user create a custom JavaScript tool for the OneClaw AI agent.

## Your Workflow

1. **Understand Requirements**: Ask the user what the tool should do. If they provided
   an {{idea}}, start from that. Clarify:
   - What data does the tool work with? (inputs)
   - What should it return? (outputs)
   - Does it need network access? (fetch API)
   - Does it need file system access? (fs API)
   - Are there any edge cases to handle?

2. **Design the Tool**: Based on the requirements, design:
   - A clear, descriptive tool name (snake_case, e.g., `parse_csv`, `fetch_weather`)
   - A description that helps the AI know when to use this tool
   - Parameters with types and descriptions
   - The JavaScript implementation

3. **Show for Review**: Present the complete tool to the user:
   - Tool name and description
   - Parameters table
   - Full JavaScript code
   - Ask: "Should I create this tool?"

4. **Create**: Only after the user confirms, call `create_js_tool` with:
   - name
   - description
   - parameters_schema (JSON string)
   - js_code
   - required_permissions (if needed)
   - timeout_seconds (if non-default)

5. **Verify**: After creation, suggest the user try the tool.

## Available JavaScript APIs

The following APIs are available to the tool's JavaScript code:

### HTTP Requests
```javascript
// Async fetch (like browser fetch API)
const response = await fetch(url, {
    method: "GET", // or "POST", "PUT", "DELETE"
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data) // for POST/PUT
});
const text = await response.text();
const json = await response.json();
// response.ok, response.status, response.statusText, response.headers
```

### File System
```javascript
fs.readFile(path)              // Returns file content as string
fs.writeFile(path, content)    // Write content to file (overwrite)
fs.appendFile(path, content)   // Append content to file
fs.exists(path)                // Returns true/false
// Note: restricted paths blocked (/data/data/, /system/, /proc/, /sys/)
// File size limit: 1MB per file
```

### Time
```javascript
_time(timezone, format)
// timezone: IANA format (e.g., "America/New_York"), empty for device timezone
// format: "iso8601" (default) or "human_readable"
```

### Console (for debugging)
```javascript
console.log("debug info")     // Logs to Android Logcat
console.warn("warning")
console.error("error")
```

### Libraries
```javascript
const TurndownService = lib('turndown'); // HTML to Markdown
```

## Tool Code Template

```javascript
// Synchronous tool
function execute(params) {
    // params contains all parameters defined in the schema
    var result = "...";
    return result; // Return a string or object
}

// Asynchronous tool (for HTTP requests)
async function execute(params) {
    var response = await fetch(params.url);
    var data = await response.text();
    return data;
}
```

## Important Rules

- ALWAYS show the code to the user and wait for confirmation before creating
- Tool names must be lowercase letters, numbers, and underscores (2-50 chars)
- The function must be named `execute` and accept a `params` argument
- Return a string for simple results, or an object that will be JSON-serialized
- Handle errors gracefully -- return descriptive error messages
- Keep tools focused -- one tool should do one thing well
- Add helpful parameter descriptions so the AI knows how to use the tool
```

### ToolModule Changes

```kotlin
// In ToolModule.kt, add:

import com.oneclaw.shadow.tool.builtin.CreateJsToolTool
import com.oneclaw.shadow.tool.builtin.ListUserToolsTool
import com.oneclaw.shadow.tool.builtin.UpdateJsToolTool
import com.oneclaw.shadow.tool.builtin.DeleteJsToolTool
import com.oneclaw.shadow.tool.js.UserToolManager

val toolModule = module {
    // ... existing declarations ...

    // RFC-035: User tool manager
    single {
        UserToolManager(
            context = androidContext(),
            toolRegistry = get(),
            jsExecutionEngine = get(),
            envVarStore = get()
        )
    }

    // RFC-035: JS tool CRUD tools
    single { CreateJsToolTool(get()) }
    single { ListUserToolsTool(get()) }
    single { UpdateJsToolTool(get()) }
    single { DeleteJsToolTool(get()) }

    single {
        ToolRegistry().apply {
            // ... existing tool registrations ...

            // RFC-035: JS tool CRUD tools
            try {
                register(get<CreateJsToolTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register create_js_tool: ${e.message}")
            }
            try {
                register(get<ListUserToolsTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register list_user_tools: ${e.message}")
            }
            try {
                register(get<UpdateJsToolTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register update_js_tool: ${e.message}")
            }
            try {
                register(get<DeleteJsToolTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register delete_js_tool: ${e.message}")
            }

            // ... JS tool loading (unchanged) ...
        }
    }

    // ... rest of module unchanged ...
}
```

**Note on circular dependency**: `UserToolManager` depends on `ToolRegistry`, which is also a singleton. Koin resolves this because `UserToolManager` is declared before `ToolRegistry` but uses lazy `get()` at runtime, not at declaration time. Alternatively, `UserToolManager` can accept `ToolRegistry` via a `lateinit` setter if needed.

### JsToolLoader Changes

```kotlin
// In JsToolLoader.kt, add a public accessor for the user tools directory
// so that UserToolManager uses the same path.

class JsToolLoader(
    private val context: Context,
    // ...
) {
    // Existing code...

    /** Public accessor for the user tools directory path. */
    fun getUserToolsDir(): File {
        return File(context.filesDir, "tools").also { it.mkdirs() }
    }

    // ... rest unchanged ...
}
```

This ensures `UserToolManager` and `JsToolLoader` use the same directory. `UserToolManager` can either call `JsToolLoader.getUserToolsDir()` or use the same `File(context.filesDir, "tools")` path directly (as shown in the design above).

## Implementation Plan

### Phase 1: UserToolManager

1. [ ] Create `UserToolManager.kt` in `tool/js/`
2. [ ] Create `UserToolManagerTest.kt` with unit tests
3. [ ] Verify `./gradlew test` passes

### Phase 2: CreateJsToolTool

1. [ ] Create `CreateJsToolTool.kt` in `tool/builtin/`
2. [ ] Create `CreateJsToolToolTest.kt` with unit tests
3. [ ] Register in `ToolModule.kt`
4. [ ] Verify `./gradlew test` passes

### Phase 3: ListUserToolsTool

1. [ ] Create `ListUserToolsTool.kt` in `tool/builtin/`
2. [ ] Create `ListUserToolsToolTest.kt` with unit tests
3. [ ] Register in `ToolModule.kt`
4. [ ] Verify `./gradlew test` passes

### Phase 4: UpdateJsToolTool and DeleteJsToolTool

1. [ ] Create `UpdateJsToolTool.kt` in `tool/builtin/`
2. [ ] Create `DeleteJsToolTool.kt` in `tool/builtin/`
3. [ ] Create test files for both
4. [ ] Register in `ToolModule.kt`
5. [ ] Verify `./gradlew test` passes

### Phase 5: create-tool Skill

1. [ ] Create `assets/skills/create-tool/SKILL.md`
2. [ ] Verify skill loads in SkillRegistry
3. [ ] Verify `./gradlew test` passes

### Phase 6: Integration Testing

1. [ ] Run full Layer 1A test suite (`./gradlew test`)
2. [ ] Run Layer 1B tests if emulator available
3. [ ] Manual testing: create, list, update, delete tools via chat
4. [ ] Manual testing: verify created tools work correctly
5. [ ] Manual testing: verify tools persist across app restart
6. [ ] Write test report

## Data Model

No database changes. Created tools are stored as files:
- `{context.filesDir}/tools/{name}.json` -- Tool manifest (same format as manually-created tools)
- `{context.filesDir}/tools/{name}.js` -- JavaScript source code

The `UserToolInfo` data class is used only for in-memory listing:

```kotlin
data class UserToolInfo(
    val name: String,
    val description: String,
    val filePath: String
)
```

## API Design

### Tool Interfaces

```
Tool: create_js_tool
Parameters:
  - name: string (required) -- Tool name (lowercase, 2-50 chars)
  - description: string (required) -- What the tool does
  - parameters_schema: string (required) -- JSON parameters schema
  - js_code: string (required) -- JavaScript source code
  - required_permissions: string (optional) -- Comma-separated permissions
  - timeout_seconds: integer (optional, default: 30) -- Timeout
Returns on success:
  "Tool '<name>' created and registered successfully."
Returns on error:
  ToolResult.error with error type and message

Tool: list_user_tools
Parameters: (none)
Returns on success:
  Formatted list of user tools with name, description, file path
Returns when empty:
  "No user-created tools found."

Tool: update_js_tool
Parameters:
  - name: string (required) -- Name of tool to update
  - description: string (optional) -- New description
  - parameters_schema: string (optional) -- New schema JSON
  - js_code: string (optional) -- New JS code
  - required_permissions: string (optional) -- New permissions
  - timeout_seconds: integer (optional) -- New timeout
Returns on success:
  "Tool '<name>' updated successfully."
Returns on error:
  ToolResult.error with error type and message

Tool: delete_js_tool
Parameters:
  - name: string (required) -- Name of tool to delete
Returns on success:
  "Tool '<name>' deleted successfully."
Returns on error:
  ToolResult.error with error type and message
```

## Error Handling

| Error Type | Cause | Response |
|------------|-------|----------|
| `validation_error` | Missing required parameter or invalid format | `ToolResult.error("validation_error", "...")` |
| `create_failed` | Name validation, duplicate, JSON parse, or I/O error | `ToolResult.error("create_failed", "...")` |
| `update_failed` | Tool not found, protected tool, JSON parse, or I/O error | `ToolResult.error("update_failed", "...")` |
| `delete_failed` | Tool not found, protected tool, or I/O error | `ToolResult.error("delete_failed", "...")` |

All errors follow the existing `ToolResult.error(errorType, errorMessage)` pattern. Error messages are descriptive enough for the AI to understand and communicate to the user.

## Security Considerations

1. **Tool name validation**: Names are validated against `^[a-z][a-z0-9_]{0,48}[a-z0-9]$` to prevent path traversal attacks. Characters like `/`, `..`, spaces are not allowed.

2. **QuickJS sandbox**: Created tools run in the same QuickJS sandbox as all other JS tools. Each execution gets a fresh runtime with 16MB heap limit and 1MB stack limit. No access to native Android APIs beyond the provided bridges.

3. **Bridge API restrictions**: The `FsBridge` blocks access to restricted paths (`/data/data/`, `/system/`, `/proc/`, `/sys/`). The `FetchBridge` limits response bodies to 100KB. These protections apply equally to AI-generated tools.

4. **Built-in tool protection**: The `UserToolManager` explicitly checks `ToolSourceInfo` to ensure only user-created tools can be modified or deleted. Built-in Kotlin tools and built-in JS tools are protected.

5. **User confirmation**: The `create-tool` skill instructs the AI to show generated code to the user and wait for confirmation before calling `create_js_tool`. This provides a human review step for AI-generated code.

6. **No override of built-in tools**: `create_js_tool` checks for name collisions with all existing tools (including built-in) and rejects duplicates.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| `create_js_tool` | < 500ms | JSON serialization + 2 file writes + registry insert |
| `list_user_tools` | < 100ms | In-memory registry scan, no I/O |
| `update_js_tool` | < 500ms | File read + JSON serialization + file writes + re-register |
| `delete_js_tool` | < 200ms | Registry remove + 2 file deletes |

Storage per tool: ~1-10KB for manifest + source (negligible).

## Testing Strategy

### Unit Tests

**UserToolManagerTest.kt:**
- `testCreate_success` -- Creates files and registers tool
- `testCreate_invalidName` -- Rejects names not matching regex
- `testCreate_duplicateName` -- Rejects names already in registry
- `testCreate_emptyJsCode` -- Rejects empty JS code
- `testCreate_cleansUpOnFailure` -- Deletes partial files if registration fails
- `testListUserTools_empty` -- Returns empty list when no user tools
- `testListUserTools_filtersBuiltIn` -- Does not include built-in tools
- `testListUserTools_returnsUserTools` -- Returns user-created tools
- `testUpdate_success` -- Updates specified fields, preserves others
- `testUpdate_toolNotFound` -- Returns error for nonexistent tool
- `testUpdate_protectedTool` -- Returns error for built-in tool
- `testUpdate_partialUpdate` -- Only updates fields that are non-null
- `testDelete_success` -- Unregisters tool and deletes files
- `testDelete_toolNotFound` -- Returns error for nonexistent tool
- `testDelete_protectedTool` -- Returns error for built-in tool

**CreateJsToolToolTest.kt:**
- `testDefinition` -- Correct name, parameters, permissions
- `testExecute_missingName` -- Returns validation error
- `testExecute_missingDescription` -- Returns validation error
- `testExecute_missingSchema` -- Returns validation error
- `testExecute_missingJsCode` -- Returns validation error
- `testExecute_invalidSchemaJson` -- Returns validation error
- `testExecute_success` -- Delegates to UserToolManager and returns success
- `testExecute_withPermissions` -- Parses comma-separated permissions
- `testExecute_withTimeout` -- Passes custom timeout value

**ListUserToolsToolTest.kt:**
- `testDefinition` -- Correct name, no parameters
- `testExecute_noTools` -- Returns "no tools found" message
- `testExecute_withTools` -- Returns formatted tool list

**UpdateJsToolToolTest.kt:**
- `testDefinition` -- Correct name, parameters
- `testExecute_missingName` -- Returns validation error
- `testExecute_success` -- Delegates to UserToolManager
- `testExecute_invalidSchema` -- Returns validation error

**DeleteJsToolToolTest.kt:**
- `testDefinition` -- Correct name, parameters
- `testExecute_missingName` -- Returns validation error
- `testExecute_success` -- Delegates to UserToolManager

### Integration Tests (Manual)

1. Create a simple tool (e.g., `reverse_string`) via chat
2. Verify the tool appears in `list_user_tools` output
3. Use the created tool in a conversation
4. Update the tool to add a parameter
5. Delete the tool and verify it's gone
6. Create a tool using the `/create-tool` skill
7. Restart the app and verify created tools are still available
8. Verify created tool cannot override a built-in tool

## Alternatives Considered

### 1. Skill-only approach (no dedicated tools)

**Approach**: Only add a `/create-tool` skill that instructs the AI to use existing `write_file` tool to write .json and .js files, then reload tools.

**Rejected because**: The `write_file` tool writes to app-private storage via FsBridge. There's no tool to reload the registry after writing files. The AI would need complex multi-step file operations prone to errors. A dedicated tool provides atomic create-and-register operations with validation.

### 2. Single `manage_js_tool` with action parameter

**Approach**: One tool with an `action` parameter ("create", "list", "update", "delete").

**Rejected because**: The create action has 6 parameters while delete has 1. Combining them makes the parameter schema confusing for AI models. Following the project's pattern (e.g., separate PDF tools in RFC-033), separate tools with focused schemas produce better AI tool-calling accuracy.

### 3. Store tools in Room database

**Approach**: Store tool manifests and JS source in the Room database instead of the file system.

**Rejected because**: The existing JS tool infrastructure (`JsToolLoader`) is file-based. Storing in Room would require duplicating the loading logic or bridging between Room and the file system. File-based storage is simpler, compatible with the existing loader, and allows users to manually edit tools if desired.

### 4. In-memory only (no persistence)

**Approach**: Only register tools in the in-memory ToolRegistry without writing files.

**Rejected because**: User feedback indicated that persistence across app restarts is essential. Losing custom tools on restart would be frustrating. Writing files to the tools directory enables automatic reload via JsToolLoader.

## Dependencies

### External Dependencies

| Dependency | Version | Size | License |
|------------|---------|------|---------|
| (none) | - | - | - |

No new external dependencies. Uses existing QuickJS engine, Kotlinx Serialization, and Android file system APIs.

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `ToolResult`, `ToolDefinition`, `ToolParametersSchema`, `ToolParameter` from `core/model/`
- `ToolRegistry`, `ToolSourceInfo`, `ToolSourceType` from `tool/engine/` and `core/model/`
- `JsTool` from `tool/js/`
- `JsExecutionEngine` from `tool/js/`
- `EnvironmentVariableStore` from `tool/js/`
- `AppResult` from `core/util/`
- `SkillRegistry` from `tool/skill/` (loads the create-tool skill)
- `Context` from Android framework (injected via Koin)

## Future Extensions

- **Tool groups**: Support creating RFC-018 tool groups (multiple tools per JS file)
- **JS syntax validation**: Use QuickJS to parse the JS code before saving, catching syntax errors early
- **Dry-run execution**: Run the tool with mock parameters to verify it works before registering
- **Tool templates**: Provide common tool templates (HTTP API wrapper, file parser, data transformer)
- **Tool export/import**: Share tools as `.json` + `.js` file pairs or as a single archive
- **Tool marketplace**: Community-shared tool repository within the app
- **Tool versioning**: Track changes to tools with the ability to rollback

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
