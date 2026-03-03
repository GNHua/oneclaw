# RFC-018: JavaScript 工具组

## 文档信息
- **RFC ID**: RFC-018
- **关联 PRD**: [FEAT-018 (JavaScript 工具组)](../../prd/features/FEAT-018-js-tool-group.md)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**: [RFC-004 (工具系统)](RFC-004-tool-system.md)、[RFC-012 (JavaScript 工具引擎)](RFC-012-js-tool-engine.md)、[RFC-015 (JS 工具迁移)](RFC-015-js-tool-migration.md)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

RFC-012 与 RFC-015 建立了一套 JS 工具系统，其中每个工具由一对文件组成：一个 `.js`（逻辑实现）和一个 `.json`（元数据描述）。这种模式对单独工具运作良好，但随着集成规模扩大（例如 Google Drive 涵盖 6 个操作、Gmail 涵盖 5 个操作），按文件计工具的模式会导致文件数量急剧膨胀，同时无法在同一服务内共享辅助代码。

RFC-018 对 JS 工具格式进行扩展，引入**工具组**支持：单个 `.js` 文件中包含多个具名函数，与之配对的 `.json` 清单文件为一个 JSON 数组，数组中每个条目描述一个工具定义，并通过 `"function"` 字段指定对应要调用的 JS 函数。各工具作为独立条目注册到 ToolRegistry 中——AI 模型和执行引擎对其处理方式与单文件工具完全相同。

### 目标

1. 在 `.json` 清单文件中支持 JSON 数组格式（组模式）
2. 在工具定义中新增 `"function"` 字段，用于具名函数派发
3. 自动检测单工具（对象）与工具组（数组）两种 JSON 格式
4. 将 `functionName` 从 `JsTool` 传递至 `JsExecutionEngine`，以调用正确的 JS 函数
5. 支持部分加载：跳过组内无效条目，继续加载有效条目
6. 在同一组的所有工具间共享内存中的 JS 源码（避免重复）
7. 完全向后兼容现有的单工具格式

### 非目标

- 将现有内置工具（FEAT-015）重组为工具组
- 组级别的权限、超时或启用/禁用控制
- 清单层面的工具组元数据（名称、描述、版本等）
- 工具组展示的设置界面变更（延后实现）

## 技术设计

### 架构概览

本次改动范围较窄——仅涉及三个文件，不新增任何文件：

```
Modified:
  tool/js/JsTool.kt              -- add functionName parameter
  tool/js/JsExecutionEngine.kt   -- use functionName in wrapper code
  tool/js/JsToolLoader.kt        -- detect array JSON, parse group entries

No new Kotlin files.
No bridge changes.
No ToolModule changes.
No ToolRegistry changes.
```

### 格式检测

`JsToolLoader` 读取 `.json` 文件后，解析顶层 JSON 元素：

```
JSON.parseToJsonElement(content)
        │
        ├── is JsonObject  → single-tool mode (existing)
        │                     calls parseAndValidateMetadata()
        │                     returns 1 JsTool with functionName = null
        │
        └── is JsonArray   → group mode (new)
                              iterates each JsonObject entry
                              extracts "function" field
                              returns N JsTool instances sharing the same JS source
                              each with a distinct functionName
```

### 执行流程

```
Single-tool mode (functionName = null):
  wrapper calls: execute(__params__)      ← existing behavior, unchanged

Group mode (functionName = "readFile"):
  wrapper calls: readFile(__params__)     ← new dispatch
```

## 详细设计

### JsTool 变更

新增 `functionName` 参数，并将其传递给执行引擎。

```kotlin
/**
 * Located in: tool/js/JsTool.kt
 *
 * MODIFIED: Added functionName parameter for tool group support.
 * When null, the engine calls execute(params) (single-tool mode).
 * When set, the engine calls the named function (group mode).
 */
class JsTool(
    override val definition: ToolDefinition,
    val jsFilePath: String = "",
    private val jsSource: String? = null,
    private val functionName: String? = null,       // NEW
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return if (jsSource != null) {
            jsExecutionEngine.executeFromSource(
                jsSource = jsSource,
                toolName = definition.name,
                functionName = functionName,        // NEW
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        } else {
            jsExecutionEngine.execute(
                jsFilePath = jsFilePath,
                toolName = definition.name,
                functionName = functionName,        // NEW
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        }
    }
}
```

### JsExecutionEngine 变更

在所有执行方法中新增 `functionName` 参数，并在包装代码中使用该参数。

```kotlin
/**
 * Located in: tool/js/JsExecutionEngine.kt
 *
 * MODIFIED: All execution methods accept functionName.
 * The wrapper calls functionName(params) instead of execute(params) when set.
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient,
    private val libraryBridge: LibraryBridge
) {
    // ... companion object unchanged ...

    suspend fun execute(
        jsFilePath: String,
        toolName: String,
        functionName: String? = null,               // NEW
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs(jsFilePath, null, toolName, functionName, params, env)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "JS tool '$toolName' execution timed out after ${timeoutSeconds}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "JS tool '$toolName' execution failed", e)
            ToolResult.error("execution_error", "JS tool '$toolName' failed: ${e.message}")
        }
    }

    suspend fun executeFromSource(
        jsSource: String,
        toolName: String,
        functionName: String? = null,               // NEW
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs("", jsSource, toolName, functionName, params, env)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "JS tool '$toolName' execution timed out after ${timeoutSeconds}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "JS tool '$toolName' execution failed", e)
            ToolResult.error("execution_error", "JS tool '$toolName' failed: ${e.message}")
        }
    }

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        jsSource: String?,
        toolName: String,
        functionName: String?,                      // NEW
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            ConsoleBridge.inject(this, toolName)
            FsBridge.inject(this)
            FetchBridge.inject(this, okHttpClient)
            TimeBridge.inject(this)
            libraryBridge.inject(this)

            val jsCode = jsSource ?: File(jsFilePath).readText()
            val paramsJson = anyToJsonElement(paramsWithEnv).toString()

            // Use the named function if provided, otherwise default to execute()
            val entryFunction = functionName ?: "execute"

            val wrapperCode = """
                ${FetchBridge.FETCH_WRAPPER_JS}
                ${libraryBridge.LIB_WRAPPER_JS}

                $jsCode

                (async function __run__() {
                    const __params__ = JSON.parse(${quoteJsString(paramsJson)});
                    const __result__ = await $entryFunction(__params__);
                    if (__result__ === null || __result__ === undefined) {
                        return "";
                    }
                    if (typeof __result__ === "string") {
                        return __result__;
                    }
                    return JSON.stringify(__result__);
                })()
            """.trimIndent()

            evaluate<String>(wrapperCode)
        }

        return ToolResult.success(result ?: "")
    }

    // ... anyToJsonElement(), quoteJsString() unchanged ...
}
```

**关于 `entryFunction` 的安全说明**：`functionName` 的值来源于 `.json` 清单文件（由工具作者编写），而非外部输入。在加载阶段（详见下文 `JsToolLoader`），会对其进行校验，要求匹配正则 `^[a-zA-Z_$][a-zA-Z0-9_$]*$`，从而防止代码注入。

### JsToolLoader 变更

加载器新增了检测和解析组清单的能力。变更集中在三个方面：

1. 新增 `parseGroupManifest()` 方法
2. 新增顶层 `parseJsonManifest()`，根据 JSON 类型进行分派
3. `loadTools()` 和 `loadBuiltinTools()` 均更新为使用新解析器

```kotlin
/**
 * Located in: tool/js/JsToolLoader.kt
 *
 * MODIFIED: Supports both single-tool (object) and group (array) JSON manifests.
 */
class JsToolLoader(
    private val context: Context,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) {
    companion object {
        private const val TAG = "JsToolLoader"
        private const val EXTERNAL_TOOLS_DIR = "OneClaw/tools"
        private const val ASSETS_TOOLS_DIR = "js/tools"
        private val TOOL_NAME_REGEX = Regex("^[a-z][a-z0-9_]*$")
        private val FUNCTION_NAME_REGEX = Regex("^[a-zA-Z_$][a-zA-Z0-9_$]*$")
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

    // ── Built-in tools (from assets) ──

    fun loadBuiltinTools(): LoadResult {
        val tools = mutableListOf<JsTool>()
        val errors = mutableListOf<ToolLoadError>()

        val assetFiles = try {
            context.assets.list(ASSETS_TOOLS_DIR) ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "Cannot list assets/$ASSETS_TOOLS_DIR: ${e.message}")
            return LoadResult(emptyList(), emptyList())
        }

        val jsonFiles = assetFiles.filter { it.endsWith(".json") }

        for (jsonFileName in jsonFiles) {
            val baseName = jsonFileName.removeSuffix(".json")
            val jsFileName = "$baseName.js"

            if (jsFileName !in assetFiles) {
                errors.add(ToolLoadError(jsonFileName, "Missing corresponding .js file: $jsFileName"))
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
                errors.add(ToolLoadError(jsonFileName, "Failed to load: ${e.message}"))
            }
        }

        return LoadResult(tools, errors)
    }

    // ── User tools (from file system) ──

    fun loadTools(): LoadResult {
        val tools = mutableListOf<JsTool>()
        val errors = mutableListOf<ToolLoadError>()

        for (dir in getToolDirectories()) {
            if (!dir.exists()) { dir.mkdirs(); continue }

            val jsonFiles = dir.listFiles { file ->
                file.extension == "json" && file.isFile
            } ?: continue

            for (jsonFile in jsonFiles) {
                val baseName = jsonFile.nameWithoutExtension
                val jsFile = File(dir, "$baseName.js")

                if (!jsFile.exists()) {
                    errors.add(ToolLoadError(jsonFile.name, "Missing corresponding .js file: ${jsFile.name}"))
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
                    errors.add(ToolLoadError(jsonFile.name, "Failed to load: ${e.message}"))
                }
            }
        }

        return LoadResult(tools, errors)
    }

    // ── JSON manifest parsing ──

    /**
     * Parse a JSON manifest, detecting single-tool (object) or group (array) format.
     * Returns a list of (ToolDefinition, functionName?) pairs.
     *
     * - Object format: returns [(definition, null)]  -- single tool, calls execute()
     * - Array format:  returns [(def1, fn1), (def2, fn2), ...]  -- group, calls named functions
     */
    private fun parseJsonManifest(
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
            element is kotlinx.serialization.json.JsonArray -> {
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
        array: kotlinx.serialization.json.JsonArray,
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

    // ── Helpers (unchanged) ──

    private fun readAsset(path: String): String {
        return context.assets.open(path).bufferedReader().use { it.readText() }
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
        element is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun getToolDirectories(): List<File> {
        val dirs = mutableListOf<File>()
        val externalDir = File(Environment.getExternalStorageDirectory(), EXTERNAL_TOOLS_DIR)
        dirs.add(externalDir)
        val internalDir = File(context.filesDir, "tools")
        dirs.add(internalDir)
        return dirs
    }

    /**
     * Register loaded JS tools into the ToolRegistry.
     * Unchanged from RFC-015.
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
```

### 删除的代码

旧有的 `parseAndValidateMetadata()` 和 `loadSingleTool()` 方法将被移除，由 `parseJsonManifest()`、`parseGroupManifest()` 和 `parseToolEntry()` 替代。

## 数据流

### 流程：加载工具组

```
App startup -> ToolModule -> JsToolLoader.loadTools()
    │
    │  Scans /sdcard/OneClaw/tools/
    │  Finds: google_drive.json + google_drive.js
    │
    │  Reads google_drive.json
    │  Json.parseToJsonElement() -> JsonArray  (group mode detected)
    │
    │  parseGroupManifest():
    │    Entry 0: name="google_drive_list_files", function="listFiles"  -> OK
    │    Entry 1: name="google_drive_read_file", function="readFile"    -> OK
    │    Entry 2: name="google_drive_upload_file", function="uploadFile"-> OK
    │
    │  Creates 3 JsTool instances:
    │    JsTool(def=google_drive_list_files, jsFilePath=".../google_drive.js", functionName="listFiles")
    │    JsTool(def=google_drive_read_file,  jsFilePath=".../google_drive.js", functionName="readFile")
    │    JsTool(def=google_drive_upload_file,jsFilePath=".../google_drive.js", functionName="uploadFile")
    │
    │  All 3 share the same jsFilePath (or jsSource for assets)
    │
    └─> registerTools() -> ToolRegistry now has 3 independent tool entries
```

### 流程：执行工具组中的工具

```
AI model calls: google_drive_read_file(file_id="abc123")
    │
    └─> ToolExecutionEngine.executeTool("google_drive_read_file", params)
        │
        └─> ToolRegistry.get("google_drive_read_file")
            │  Returns: JsTool(functionName="readFile", jsFilePath=".../google_drive.js")
            │
            └─> JsTool.execute(params)
                │
                └─> JsExecutionEngine.execute(
                        jsFilePath=".../google_drive.js",
                        toolName="google_drive_read_file",
                        functionName="readFile",   ← group dispatch
                        params={file_id: "abc123"},
                        ...
                    )
                    │
                    └─> QuickJS wrapper code:
                        │
                        │  // bridges injected...
                        │  // google_drive.js evaluated (all functions defined)
                        │  (async function __run__() {
                        │      const params = ...;
                        │      const result = await readFile(params);  ← calls readFile, not execute
                        │      return JSON.stringify(result);
                        │  })()
                        │
                        └─> readFile() runs, returns result
```

### 流程：单工具（向后兼容）

```
AI model calls: weather_lookup(city="Tokyo")
    │
    └─> ToolRegistry.get("weather_lookup")
        │  Returns: JsTool(functionName=null, jsFilePath=".../weather_lookup.js")
        │
        └─> JsExecutionEngine.execute(functionName=null, ...)
            │
            │  entryFunction = functionName ?: "execute"  → "execute"
            │
            └─> wrapper calls: execute(params)   ← unchanged behavior
```

## 测试策略

### 新增测试

#### JsToolGroupTest

```kotlin
/**
 * Located in: test/tool/js/JsToolGroupTest.kt
 *
 * Tests for tool group (array manifest) functionality.
 */
class JsToolGroupTest {
    // ── Format detection ──
    // Test: JSON object -> single-tool mode (returns 1 tool, functionName=null)
    // Test: JSON array -> group mode (returns N tools, each with functionName)

    // ── Group parsing ──
    // Test: valid group with 3 entries -> 3 ToolDefinition+functionName pairs
    // Test: each entry has correct name, description, parameters, functionName
    // Test: entry missing "function" field -> skipped, others loaded
    // Test: entry missing "name" field -> skipped, others loaded
    // Test: duplicate name within group -> first wins, second skipped
    // Test: empty array -> returns empty list with warning
    // Test: array exceeding MAX_GROUP_SIZE -> throws exception

    // ── Function name validation ──
    // Test: valid camelCase function name "listFiles" -> accepted
    // Test: valid single word "search" -> accepted
    // Test: invalid function name "../inject" -> rejected
    // Test: empty function name "" -> rejected

    // ── Execution dispatch ──
    // Test: JsTool with functionName="readFile" calls executeFromSource with functionName
    // Test: JsTool with functionName=null calls execute without functionName (backward compat)

    // ── JsExecutionEngine wrapper ──
    // Test: functionName=null -> wrapper calls execute(params)
    // Test: functionName="readFile" -> wrapper calls readFile(params)
}
```

### 修改的测试

`BuiltinJsToolMigrationTest.kt` 和 `WebfetchToolTest.kt` 中的现有测试应保持不变并继续通过，因为单工具模式向后兼容（`functionName` 默认为 null）。

### 集成测试

```kotlin
class JsToolGroupIntegrationTest {
    // Test: create a group manifest + JS file on disk, load via JsToolLoader,
    //       register all tools, execute each one and verify correct function dispatched.
    // Test: mix of group files and single-tool files in the same directory -> all load correctly.
    // Test: user group tool with same name as built-in -> override works.
}
```

## 实施计划

本次变更小而聚焦，仅涉及 3 个文件。

### 第一步：JsExecutionEngine

- 在 `execute()`、`executeFromSource()`、`executeInQuickJs()` 中新增 `functionName: String? = null` 参数
- 将包装代码改为使用 `val entryFunction = functionName ?: "execute"`
- 完全向后兼容：现有调用方传入 null（使用默认值）

### 第二步：JsTool

- 新增构造函数参数 `functionName: String? = null`
- 在 `execute()` 中将 `functionName` 传递给执行引擎
- 完全向后兼容：现有构造调用方传入 null（使用默认值）

### 第三步：JsToolLoader

- 新增常量 `FUNCTION_NAME_REGEX`
- 将 `parseAndValidateMetadata()` 重命名为 `parseToolEntry()`，并新增 `requireNameMatch: String?` 参数
- 新增 `parseJsonManifest()`，用于检测对象与数组格式
- 新增 `parseGroupManifest()`，用于处理数组条目
- 将 `loadBuiltinTools()` 和 `loadTools()` 更新为使用 `parseJsonManifest()`
- 移除旧有的 `loadSingleTool()` 方法

### 第四步：测试

- 新增 `JsToolGroupTest.kt`
- 运行 `./gradlew test`，全部测试必须通过（包括现有测试）

### 第五步：构建验证

- `./gradlew compileDebugUnitTestKotlin`
- `./gradlew test`

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | 初始版本 | - |
