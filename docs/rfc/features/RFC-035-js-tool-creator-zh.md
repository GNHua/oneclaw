# RFC-035: JS 工具创建器

## 文档信息
- **RFC ID**: RFC-035
- **相关 PRD**: [FEAT-035 (JS 工具创建器)](../../prd/features/FEAT-035-js-tool-creator.md)
- **相关架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **相关 RFC**: [RFC-004 (工具系统)](RFC-004-tool-system.md), [RFC-014 (Agent 技能)](RFC-014-agent-skill.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **作者**: TBD

## 概览

### 背景

OneClaw 拥有一套基于 QuickJS 的成熟 JavaScript 工具系统（RFC-004）。用户可以通过在工具目录中放置 `.json` 清单文件和 `.js` 源文件来扩展 Agent 的能力。然而，这要求用户在应用外部手动创建格式正确的文件——对非技术用户存在一定门槛。

本 RFC 新增了直接在聊天界面中创建、更新、列出和删除自定义 JS 工具的能力。AI 根据用户的自然语言描述生成工具代码，一组内置 Kotlin 工具负责处理文件 I/O 和注册表操作。配套技能（`create-tool`）会引导 AI 完成创建工作流，并在保存前要求用户确认。

### 目标

1. 实现四个内置 Kotlin 工具：`CreateJsToolTool`、`ListUserToolsTool`、`UpdateJsToolTool`、`DeleteJsToolTool`
2. 创建 `create-tool` 技能，引导 AI 辅助完成工具创建
3. 将创建的工具持久化到文件系统，以便在应用重启后保留
4. 立即将创建的工具注册到 ToolRegistry（无需重启）
5. 保护内置工具免受修改或删除
6. 为所有新工具添加单元测试

### 非目标

- 可视化代码编辑器或类 IDE 的工具编辑界面
- 工具共享、设备间导入/导出
- 工具组创建（RFC-018 多工具格式）——每个文件仅支持单个工具
- 工具版本控制或回滚
- 注册前对创建的工具进行自动化测试
- 超出基本检查范围的 JS 语法验证

## 技术设计

### 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                      Chat Layer (RFC-001)                         │
│  SendMessageUseCase                                              │
│       │                                                          │
│       │  1. AI 加载 /create-tool 技能（或用户描述需求）          │
│       │  2. AI 设计工具，展示代码供审查                          │
│       │  3. 用户确认                                             │
│       │  4. AI 调用 create_js_tool / update / delete / list      │
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
│  │  │              UserToolManager [新增]                  │  │  │
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

技能层：
┌──────────────────────────────────────────┐
│  assets/skills/create-tool/SKILL.md      │
│  - 引导 AI 完成工具创建流程              │
│  - 记录可用的桥接 API                    │
│  - 要求用户确认                          │
└──────────────────────────────────────────┘
```

### 核心组件

**新增：**
1. `UserToolManager` -- 用户工具 CRUD 操作的共享逻辑（文件 I/O + 注册表）
2. `CreateJsToolTool` -- 用于创建新 JS 工具的内置工具
3. `ListUserToolsTool` -- 用于列出用户创建的 JS 工具的内置工具
4. `UpdateJsToolTool` -- 用于更新已有用户 JS 工具的内置工具
5. `DeleteJsToolTool` -- 用于删除用户 JS 工具的内置工具
6. `create-tool` 技能 -- 引导创建工作流的 SKILL.md

**修改：**
7. `ToolModule` -- 注册四个新工具
8. `JsToolLoader` -- 新增 `getUserToolsDir()` 公开访问器

## 详细设计

### 目录结构（新增及修改文件）

```
app/src/main/
├── assets/
│   └── skills/
│       └── create-tool/
│           └── SKILL.md                         # 新增
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── CreateJsToolTool.kt              # 新增
│   │   │   ├── ListUserToolsTool.kt             # 新增
│   │   │   ├── UpdateJsToolTool.kt              # 新增
│   │   │   ├── DeleteJsToolTool.kt              # 新增
│   │   │   ├── LoadSkillTool.kt                 # 不变
│   │   │   └── ...                              # 不变
│   │   ├── js/
│   │   │   ├── UserToolManager.kt               # 新增
│   │   │   ├── JsToolLoader.kt                  # 修改
│   │   │   └── ...                              # 不变
│   │   └── engine/
│   │       └── ...                              # 不变
│   └── di/
│       └── ToolModule.kt                        # 修改

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   ├── CreateJsToolToolTest.kt          # 新增
        │   ├── ListUserToolsToolTest.kt         # 新增
        │   ├── UpdateJsToolToolTest.kt          # 新增
        │   └── DeleteJsToolToolTest.kt          # 新增
        └── js/
            └── UserToolManagerTest.kt           # 新增
```

### UserToolManager

```kotlin
/**
 * Located in: tool/js/UserToolManager.kt
 *
 * 管理用户创建的 JS 工具：文件 I/O、验证和注册表操作。
 * 由所有 CRUD 工具实现共享。
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

    /** 存储用户工具的目录。 */
    val toolsDir: File
        get() = File(context.filesDir, TOOLS_DIR).also { it.mkdirs() }

    /**
     * 创建一个新的 JS 工具。
     *
     * @param name 工具名称（通过 NAME_REGEX 验证）
     * @param description 提供给 AI 的工具描述
     * @param parametersSchema 已解析的参数 schema 映射
     * @param jsCode JavaScript 源代码
     * @param requiredPermissions Android 权限列表
     * @param timeoutSeconds 执行超时时间
     * @return AppResult，包含成功消息或错误
     */
    fun create(
        name: String,
        description: String,
        parametersSchema: ToolParametersSchema,
        jsCode: String,
        requiredPermissions: List<String>,
        timeoutSeconds: Int
    ): AppResult<String> {
        // 验证名称
        if (!NAME_REGEX.matches(name)) {
            return AppResult.Error(
                "Invalid tool name '$name'. Must be 2-50 lowercase " +
                    "letters, numbers, and underscores, starting with a letter."
            )
        }

        // 检查重复
        if (toolRegistry.hasTool(name)) {
            return AppResult.Error("Tool '$name' already exists.")
        }

        // 验证 JS 代码不为空
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
            // 写入清单文件
            val manifestFile = File(toolsDir, "$name.json")
            val manifest = buildManifestJson(definition)
            manifestFile.writeText(manifest)

            // 写入 JS 源文件
            val jsFile = File(toolsDir, "$name.js")
            jsFile.writeText(jsCode)

            // 创建 JsTool 并注册
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
            // 清理不完整的文件
            File(toolsDir, "$name.json").delete()
            File(toolsDir, "$name.js").delete()
            AppResult.Error("Failed to create tool: ${e.message}")
        }
    }

    /**
     * 列出所有用户创建的工具。
     *
     * @return 用户工具的 (toolName, description) 对列表
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
     * 更新已有的用户工具。
     *
     * @param name 要更新的工具名称
     * @param description 新描述（null 表示保留现有值）
     * @param parametersSchema 新 schema（null 表示保留现有值）
     * @param jsCode 新 JS 代码（null 表示保留现有值）
     * @param requiredPermissions 新权限列表（null 表示保留现有值）
     * @param timeoutSeconds 新超时时间（null 表示保留现有值）
     * @return AppResult，包含成功消息或错误
     */
    fun update(
        name: String,
        description: String?,
        parametersSchema: ToolParametersSchema?,
        jsCode: String?,
        requiredPermissions: List<String>?,
        timeoutSeconds: Int?
    ): AppResult<String> {
        // 验证工具存在
        val existingTool = toolRegistry.getTool(name)
            ?: return AppResult.Error("Tool '$name' not found.")

        // 验证是用户工具
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error("Cannot update tool '$name': not a user-created tool.")
        }

        // 与现有定义合并
        val existingDef = existingTool.definition
        val newDefinition = ToolDefinition(
            name = name,
            description = description ?: existingDef.description,
            parametersSchema = parametersSchema ?: existingDef.parametersSchema,
            requiredPermissions = requiredPermissions ?: existingDef.requiredPermissions,
            timeoutSeconds = timeoutSeconds ?: existingDef.timeoutSeconds
        )

        // 如果不更新 JS 代码，则读取现有代码
        val newJsCode = jsCode ?: File(toolsDir, "$name.js").readText()

        return try {
            // 注销旧工具
            toolRegistry.unregister(name)

            // 写入更新后的清单
            val manifestFile = File(toolsDir, "$name.json")
            manifestFile.writeText(buildManifestJson(newDefinition))

            // 写入更新后的 JS 源文件（如有变更）
            if (jsCode != null) {
                val jsFile = File(toolsDir, "$name.js")
                jsFile.writeText(jsCode)
            }

            // 重新注册
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
     * 删除一个用户工具。
     *
     * @param name 要删除的工具名称
     * @return AppResult，包含成功消息或错误
     */
    fun delete(name: String): AppResult<String> {
        // 验证工具存在
        if (!toolRegistry.hasTool(name)) {
            return AppResult.Error("Tool '$name' not found.")
        }

        // 验证是用户工具
        val sourceInfo = toolRegistry.getToolSourceInfo(name)
        if (sourceInfo.type != ToolSourceType.JS_EXTENSION ||
            sourceInfo.filePath?.startsWith(toolsDir.absolutePath) != true
        ) {
            return AppResult.Error("Cannot delete tool '$name': not a user-created tool.")
        }

        return try {
            // 注销
            toolRegistry.unregister(name)

            // 删除文件
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
     * 从 ToolDefinition 构建 JSON 清单字符串。
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
 * 从 AI 生成的代码中创建一个新的 JavaScript 工具，
 * 将其保存到文件系统并注册到 ToolRegistry。
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
                        "File access uses app-private storage and needs no permissions. " +
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

        // 解析参数 schema
        val schema = try {
            parseParametersSchema(schemaJson)
        } catch (e: Exception) {
            return ToolResult.error(
                "validation_error",
                "Invalid parameters_schema JSON: ${e.message}"
            )
        }

        // 解析权限
        val permissions = permissionsStr
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

        // 委托给 UserToolManager
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
 * 列出所有用户创建的 JavaScript 工具，
 * 包括名称、描述和源文件路径。
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
 * 更新已有的用户创建 JavaScript 工具。
 * 仅更新指定字段，其余字段保持不变。
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

        // 如有提供则解析 schema
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

        // 如有提供则解析权限
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
 * 删除一个用户创建的 JavaScript 工具：从 ToolRegistry 注销，
 * 并删除清单文件和源文件。
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

### create-tool 技能

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

你正在帮助用户为 OneClaw AI Agent 创建一个自定义 JavaScript 工具。

## 工作流程

1. **理解需求**：询问用户希望工具做什么。如果用户提供了 {{idea}}，从此出发。明确以下几点：
   - 工具处理什么数据？（输入）
   - 应该返回什么？（输出）
   - 是否需要网络访问？（fetch API）
   - 是否需要文件系统访问？（fs API）
   - 是否有边界情况需要处理？

2. **设计工具**：根据需求设计：
   - 清晰、描述性的工具名称（snake_case，例如 `parse_csv`、`fetch_weather`）
   - 帮助 AI 判断何时使用该工具的描述
   - 带有类型和描述的参数
   - JavaScript 实现

3. **展示供审查**：向用户呈现完整工具：
   - 工具名称和描述
   - 参数表格
   - 完整 JavaScript 代码
   - 询问："要创建这个工具吗？"

4. **创建**：仅在用户确认后，调用 `create_js_tool`，传入：
   - name
   - description
   - parameters_schema（JSON 字符串）
   - js_code
   - required_permissions（如需要）
   - timeout_seconds（如非默认值）

5. **验证**：创建完成后，建议用户试用该工具。

## 可用 JavaScript API

工具 JavaScript 代码可使用以下 API：

### HTTP 请求
```javascript
// 异步 fetch（类似浏览器 fetch API）
const response = await fetch(url, {
    method: "GET", // 或 "POST", "PUT", "DELETE"
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(data) // 用于 POST/PUT
});
const text = await response.text();
const json = await response.json();
// response.ok, response.status, response.statusText, response.headers
```

### 文件系统
```javascript
fs.readFile(path)              // 以字符串形式返回文件内容
fs.writeFile(path, content)    // 将内容写入文件（覆盖）
fs.appendFile(path, content)   // 向文件追加内容
fs.exists(path)                // 返回 true/false
// 注意：受限路径被阻止（/data/data/、/system/、/proc/、/sys/）
// 文件大小限制：每个文件 1MB
```

### 时间
```javascript
_time(timezone, format)
// timezone：IANA 格式（例如 "America/New_York"），留空使用设备时区
// format："iso8601"（默认）或 "human_readable"
```

### 控制台（用于调试）
```javascript
console.log("debug info")     // 输出到 Android Logcat
console.warn("warning")
console.error("error")
```

### 库
```javascript
const TurndownService = lib('turndown'); // HTML 转 Markdown
```

## 工具代码模板

```javascript
// 同步工具
function execute(params) {
    // params 包含 schema 中定义的所有参数
    var result = "...";
    return result; // 返回字符串或对象
}

// 异步工具（用于 HTTP 请求）
async function execute(params) {
    var response = await fetch(params.url);
    var data = await response.text();
    return data;
}
```

## 重要规则

- 始终向用户展示代码并等待确认后再创建
- 工具名称必须为小写字母、数字和下划线（2-50 个字符）
- 函数必须命名为 `execute` 且接受 `params` 参数
- 简单结果返回字符串，或返回将被 JSON 序列化的对象
- 优雅处理错误——返回描述性的错误信息
- 保持工具专注——一个工具只做一件事
- 添加有用的参数描述，让 AI 知道如何使用该工具
```

### ToolModule 变更

```kotlin
// 在 ToolModule.kt 中添加：

import com.oneclaw.shadow.tool.builtin.CreateJsToolTool
import com.oneclaw.shadow.tool.builtin.ListUserToolsTool
import com.oneclaw.shadow.tool.builtin.UpdateJsToolTool
import com.oneclaw.shadow.tool.builtin.DeleteJsToolTool
import com.oneclaw.shadow.tool.js.UserToolManager

val toolModule = module {
    // ... 现有声明 ...

    // RFC-035：用户工具管理器
    single {
        UserToolManager(
            context = androidContext(),
            toolRegistry = get(),
            jsExecutionEngine = get(),
            envVarStore = get()
        )
    }

    // RFC-035：JS 工具 CRUD 工具
    single { CreateJsToolTool(get()) }
    single { ListUserToolsTool(get()) }
    single { UpdateJsToolTool(get()) }
    single { DeleteJsToolTool(get()) }

    single {
        ToolRegistry().apply {
            // ... 现有工具注册 ...

            // RFC-035：JS 工具 CRUD 工具
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

            // ... JS 工具加载（不变）...
        }
    }

    // ... 模块其余部分不变 ...
}
```

**关于循环依赖的说明**：`UserToolManager` 依赖 `ToolRegistry`，而 `ToolRegistry` 也是一个单例。Koin 能够解决这个问题，因为 `UserToolManager` 在声明时早于 `ToolRegistry`，但在运行时通过懒加载的 `get()` 使用它，而非在声明阶段。如有需要，也可以让 `UserToolManager` 通过 `lateinit` setter 接收 `ToolRegistry`。

### JsToolLoader 变更

```kotlin
// 在 JsToolLoader.kt 中，新增用户工具目录的公开访问器，
// 以便 UserToolManager 使用相同的路径。

class JsToolLoader(
    private val context: Context,
    // ...
) {
    // 现有代码...

    /** 用户工具目录路径的公开访问器。 */
    fun getUserToolsDir(): File {
        return File(context.filesDir, "tools").also { it.mkdirs() }
    }

    // ... 其余部分不变 ...
}
```

这确保了 `UserToolManager` 和 `JsToolLoader` 使用相同的目录。`UserToolManager` 可以调用 `JsToolLoader.getUserToolsDir()`，也可以直接使用相同的 `File(context.filesDir, "tools")` 路径（如上述设计所示）。

## 实施计划

### 阶段一：UserToolManager

1. [ ] 在 `tool/js/` 中创建 `UserToolManager.kt`
2. [ ] 创建带有单元测试的 `UserToolManagerTest.kt`
3. [ ] 验证 `./gradlew test` 通过

### 阶段二：CreateJsToolTool

1. [ ] 在 `tool/builtin/` 中创建 `CreateJsToolTool.kt`
2. [ ] 创建带有单元测试的 `CreateJsToolToolTest.kt`
3. [ ] 在 `ToolModule.kt` 中注册
4. [ ] 验证 `./gradlew test` 通过

### 阶段三：ListUserToolsTool

1. [ ] 在 `tool/builtin/` 中创建 `ListUserToolsTool.kt`
2. [ ] 创建带有单元测试的 `ListUserToolsToolTest.kt`
3. [ ] 在 `ToolModule.kt` 中注册
4. [ ] 验证 `./gradlew test` 通过

### 阶段四：UpdateJsToolTool 和 DeleteJsToolTool

1. [ ] 在 `tool/builtin/` 中创建 `UpdateJsToolTool.kt`
2. [ ] 在 `tool/builtin/` 中创建 `DeleteJsToolTool.kt`
3. [ ] 为两者创建测试文件
4. [ ] 在 `ToolModule.kt` 中注册
5. [ ] 验证 `./gradlew test` 通过

### 阶段五：create-tool 技能

1. [ ] 创建 `assets/skills/create-tool/SKILL.md`
2. [ ] 验证技能在 SkillRegistry 中正常加载
3. [ ] 验证 `./gradlew test` 通过

### 阶段六：集成测试

1. [ ] 运行完整的 Layer 1A 测试套件（`./gradlew test`）
2. [ ] 如有模拟器，运行 Layer 1B 测试
3. [ ] 手动测试：通过聊天创建、列出、更新、删除工具
4. [ ] 手动测试：验证创建的工具正常运行
5. [ ] 手动测试：验证工具在应用重启后持久保留
6. [ ] 撰写测试报告

## 数据模型

无数据库变更。创建的工具以文件形式存储：
- `{context.filesDir}/tools/{name}.json` -- 工具清单（与手动创建工具格式相同）
- `{context.filesDir}/tools/{name}.js` -- JavaScript 源代码

`UserToolInfo` 数据类仅用于内存中的列表操作：

```kotlin
data class UserToolInfo(
    val name: String,
    val description: String,
    val filePath: String
)
```

## API 设计

### 工具接口

```
Tool: create_js_tool
Parameters:
  - name: string（必填）-- 工具名称（小写，2-50 个字符）
  - description: string（必填）-- 工具功能说明
  - parameters_schema: string（必填）-- JSON 参数 schema
  - js_code: string（必填）-- JavaScript 源代码
  - required_permissions: string（可选）-- 逗号分隔的权限列表
  - timeout_seconds: integer（可选，默认：30）-- 超时时间
成功返回：
  "Tool '<name>' created and registered successfully."
错误返回：
  ToolResult.error，包含错误类型和消息

Tool: list_user_tools
Parameters: （无）
成功返回：
  格式化的用户工具列表，包含名称、描述、文件路径
为空时返回：
  "No user-created tools found."

Tool: update_js_tool
Parameters:
  - name: string（必填）-- 要更新的工具名称
  - description: string（可选）-- 新描述
  - parameters_schema: string（可选）-- 新 schema JSON
  - js_code: string（可选）-- 新 JS 代码
  - required_permissions: string（可选）-- 新权限列表
  - timeout_seconds: integer（可选）-- 新超时时间
成功返回：
  "Tool '<name>' updated successfully."
错误返回：
  ToolResult.error，包含错误类型和消息

Tool: delete_js_tool
Parameters:
  - name: string（必填）-- 要删除的工具名称
成功返回：
  "Tool '<name>' deleted successfully."
错误返回：
  ToolResult.error，包含错误类型和消息
```

## 错误处理

| 错误类型 | 原因 | 响应 |
|----------|------|------|
| `validation_error` | 缺少必填参数或格式无效 | `ToolResult.error("validation_error", "...")` |
| `create_failed` | 名称验证失败、重复、JSON 解析失败或 I/O 错误 | `ToolResult.error("create_failed", "...")` |
| `update_failed` | 工具不存在、受保护的工具、JSON 解析失败或 I/O 错误 | `ToolResult.error("update_failed", "...")` |
| `delete_failed` | 工具不存在、受保护的工具或 I/O 错误 | `ToolResult.error("delete_failed", "...")` |

所有错误均遵循现有的 `ToolResult.error(errorType, errorMessage)` 模式。错误信息足够详细，使 AI 能够理解并向用户传达。

## 安全考虑

1. **工具名称验证**：名称通过 `^[a-z][a-z0-9_]{0,48}[a-z0-9]$` 验证，以防止路径遍历攻击。`/`、`..`、空格等字符均不被允许。

2. **QuickJS 沙箱**：创建的工具在与所有其他 JS 工具相同的 QuickJS 沙箱中运行。每次执行获得一个全新的运行时，具有 16MB 堆限制和 1MB 栈限制。除提供的桥接 API 外，无法访问原生 Android API。

3. **桥接 API 限制**：`FsBridge` 阻止访问受限路径（`/data/data/`、`/system/`、`/proc/`、`/sys/`）。`FetchBridge` 将响应体限制为 100KB。这些保护措施同样适用于 AI 生成的工具。

4. **内置工具保护**：`UserToolManager` 明确检查 `ToolSourceInfo`，确保只有用户创建的工具才能被修改或删除。内置 Kotlin 工具和内置 JS 工具均受到保护。

5. **用户确认**：`create-tool` 技能指示 AI 在调用 `create_js_tool` 前向用户展示生成的代码并等待确认。这为 AI 生成的代码提供了人工审查步骤。

6. **禁止覆盖内置工具**：`create_js_tool` 检查与所有现有工具（包括内置工具）的名称冲突，并拒绝重复项。

## 性能

| 操作 | 预期耗时 | 说明 |
|------|---------|------|
| `create_js_tool` | < 500ms | JSON 序列化 + 2 次文件写入 + 注册表插入 |
| `list_user_tools` | < 100ms | 内存中注册表扫描，无 I/O |
| `update_js_tool` | < 500ms | 文件读取 + JSON 序列化 + 文件写入 + 重新注册 |
| `delete_js_tool` | < 200ms | 注册表移除 + 2 次文件删除 |

每个工具的存储占用：约 1-10KB 用于清单和源文件（可忽略不计）。

## 测试策略

### 单元测试

**UserToolManagerTest.kt：**
- `testCreate_success` -- 创建文件并注册工具
- `testCreate_invalidName` -- 拒绝不符合正则的名称
- `testCreate_duplicateName` -- 拒绝注册表中已有的名称
- `testCreate_emptyJsCode` -- 拒绝空 JS 代码
- `testCreate_cleansUpOnFailure` -- 注册失败时删除不完整文件
- `testListUserTools_empty` -- 无用户工具时返回空列表
- `testListUserTools_filtersBuiltIn` -- 不包含内置工具
- `testListUserTools_returnsUserTools` -- 返回用户创建的工具
- `testUpdate_success` -- 更新指定字段，保留其他字段
- `testUpdate_toolNotFound` -- 对不存在的工具返回错误
- `testUpdate_protectedTool` -- 对内置工具返回错误
- `testUpdate_partialUpdate` -- 仅更新非 null 字段
- `testDelete_success` -- 注销工具并删除文件
- `testDelete_toolNotFound` -- 对不存在的工具返回错误
- `testDelete_protectedTool` -- 对内置工具返回错误

**CreateJsToolToolTest.kt：**
- `testDefinition` -- 正确的名称、参数、权限
- `testExecute_missingName` -- 返回验证错误
- `testExecute_missingDescription` -- 返回验证错误
- `testExecute_missingSchema` -- 返回验证错误
- `testExecute_missingJsCode` -- 返回验证错误
- `testExecute_invalidSchemaJson` -- 返回验证错误
- `testExecute_success` -- 委托给 UserToolManager 并返回成功
- `testExecute_withPermissions` -- 解析逗号分隔的权限
- `testExecute_withTimeout` -- 传递自定义超时值

**ListUserToolsToolTest.kt：**
- `testDefinition` -- 正确的名称，无参数
- `testExecute_noTools` -- 返回"未找到工具"消息
- `testExecute_withTools` -- 返回格式化的工具列表

**UpdateJsToolToolTest.kt：**
- `testDefinition` -- 正确的名称、参数
- `testExecute_missingName` -- 返回验证错误
- `testExecute_success` -- 委托给 UserToolManager
- `testExecute_invalidSchema` -- 返回验证错误

**DeleteJsToolToolTest.kt：**
- `testDefinition` -- 正确的名称、参数
- `testExecute_missingName` -- 返回验证错误
- `testExecute_success` -- 委托给 UserToolManager

### 集成测试（手动）

1. 通过聊天创建一个简单工具（例如 `reverse_string`）
2. 验证工具出现在 `list_user_tools` 输出中
3. 在对话中使用创建的工具
4. 更新工具以添加参数
5. 删除工具并验证其已消失
6. 使用 `/create-tool` 技能创建工具
7. 重启应用并验证创建的工具仍然可用
8. 验证创建的工具不能覆盖内置工具

## 备选方案考量

### 1. 仅使用技能方式（无专用工具）

**方案**：仅添加 `/create-tool` 技能，指示 AI 使用现有的 `write_file` 工具写入 .json 和 .js 文件，然后重新加载工具。

**被拒绝原因**：`write_file` 工具写入应用私有存储。写入文件后没有工具可以重新加载注册表。AI 需要执行容易出错的复杂多步文件操作。专用工具提供带验证的原子性创建并注册操作。

### 2. 单个带 action 参数的 `manage_js_tool`

**方案**：一个带有 `action` 参数的工具（"create"、"list"、"update"、"delete"）。

**被拒绝原因**：创建操作有 6 个参数，而删除操作只有 1 个。合并后参数 schema 对 AI 模型来说令人困惑。遵循项目模式（例如 RFC-033 中的独立 PDF 工具），具有专注 schema 的独立工具能产生更好的 AI 工具调用准确性。

### 3. 将工具存储在 Room 数据库中

**方案**：将工具清单和 JS 源代码存储在 Room 数据库而非文件系统中。

**被拒绝原因**：现有 JS 工具基础设施（`JsToolLoader`）是基于文件的。存储在 Room 中需要复制加载逻辑或在 Room 和文件系统之间建立桥接。基于文件的存储更简单，与现有加载器兼容，并允许用户在需要时手动编辑工具。

### 4. 仅内存（不持久化）

**方案**：仅在内存中的 ToolRegistry 注册工具，不写入文件。

**被拒绝原因**：用户反馈表明，应用重启后的持久性至关重要。重启后丢失自定义工具会令用户沮丧。将文件写入工具目录可通过 JsToolLoader 实现自动重新加载。

## 依赖关系

### 外部依赖

| 依赖 | 版本 | 大小 | 许可证 |
|------|------|------|--------|
| （无） | - | - | - |

无新的外部依赖。使用现有的 QuickJS 引擎、Kotlinx Serialization 和 Android 文件系统 API。

### 内部依赖

- `tool/engine/` 中的 `Tool` 接口
- `core/model/` 中的 `ToolResult`、`ToolDefinition`、`ToolParametersSchema`、`ToolParameter`
- `tool/engine/` 和 `core/model/` 中的 `ToolRegistry`、`ToolSourceInfo`、`ToolSourceType`
- `tool/js/` 中的 `JsTool`
- `tool/js/` 中的 `JsExecutionEngine`
- `tool/js/` 中的 `EnvironmentVariableStore`
- `core/util/` 中的 `AppResult`
- `tool/skill/` 中的 `SkillRegistry`（加载 create-tool 技能）
- Android 框架中的 `Context`（通过 Koin 注入）

## 未来扩展

- **工具组**：支持创建 RFC-018 工具组（每个 JS 文件包含多个工具）
- **JS 语法验证**：使用 QuickJS 在保存前解析 JS 代码，提前捕获语法错误
- **试运行执行**：在注册前使用模拟参数运行工具以验证其正常工作
- **工具模板**：提供常用工具模板（HTTP API 封装、文件解析器、数据转换器）
- **工具导入/导出**：将工具作为 `.json` + `.js` 文件对或单个压缩包进行共享
- **工具市场**：应用内社区共享工具仓库
- **工具版本控制**：跟踪工具变更并支持回滚

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
