# RFC-018: JavaScript Tool Group

## Document Information
- **RFC ID**: RFC-018
- **Related PRD**: [FEAT-018 (JavaScript Tool Group)](../../prd/features/FEAT-018-js-tool-group.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-012 (JavaScript Tool Engine)](RFC-012-js-tool-engine.md), [RFC-015 (JS Tool Migration)](RFC-015-js-tool-migration.md)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

RFC-012 and RFC-015 established a JS tool system where each tool is a pair of files: one `.js` (logic) and one `.json` (metadata). This works well for individual tools, but as integrations grow (e.g., Google Drive with 6 operations, Gmail with 5 operations), the file-per-tool model leads to proliferation and prevents helper code sharing within a service.

RFC-018 extends the JS tool format to support **tool groups**: a single `.js` file containing multiple named functions, paired with a `.json` manifest that is a JSON array of tool definitions. Each entry in the array specifies a `"function"` field pointing to the JS function to call. Individual tools are registered into the ToolRegistry as independent entries -- the AI model and execution engine treat them identically to single-file tools.

### Goals

1. Support JSON array format in `.json` manifest files (group mode)
2. Add `"function"` field to tool definitions for named function dispatch
3. Automatically detect single-tool (object) vs group (array) JSON format
4. Pass `functionName` through `JsTool` -> `JsExecutionEngine` to call the correct JS function
5. Support partial load: skip invalid entries in a group, load valid ones
6. Share JS source in memory across all tools in a group (no duplication)
7. Maintain full backward compatibility with existing single-tool format

### Non-Goals

- Reorganizing existing built-in tools (FEAT-015) into groups
- Group-level permissions, timeout, or enable/disable
- Group metadata (name, description, version) at the manifest level
- Settings UI changes for group display (deferred)

## Technical Design

### Architecture Overview

The change is narrow -- it touches three files and adds no new files:

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

### Format Detection

When `JsToolLoader` reads a `.json` file, it parses the top-level JSON element:

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

### Execution Flow

```
Single-tool mode (functionName = null):
  wrapper calls: execute(__params__)      ← existing behavior, unchanged

Group mode (functionName = "readFile"):
  wrapper calls: readFile(__params__)     ← new dispatch
```

## Detailed Design

### JsTool Changes

Add `functionName` parameter. Pass it through to the execution engine.

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

### JsExecutionEngine Changes

Add `functionName` parameter to all execution methods. Use it in the wrapper code.

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

**Security note on `entryFunction`**: The `functionName` value comes from the `.json` manifest file (authored by the tool creator), not from external input. It is validated during loading (see `JsToolLoader` below) to match `^[a-zA-Z_$][a-zA-Z0-9_$]*$`, preventing code injection.

### JsToolLoader Changes

The loader gains the ability to detect and parse group manifests. Changes are concentrated in three areas:

1. A new `parseGroupManifest()` method
2. A top-level `parseJsonManifest()` that dispatches based on JSON type
3. Both `loadTools()` and `loadBuiltinTools()` updated to use the new parser

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

### Deleted Code

The old `parseAndValidateMetadata()` and `loadSingleTool()` methods are removed and replaced by `parseJsonManifest()`, `parseGroupManifest()`, and `parseToolEntry()`.

## Data Flow

### Flow: Loading a Tool Group

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

### Flow: Executing a Group Tool

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

### Flow: Single-Tool (Backward Compatibility)

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

## Testing Strategy

### New Tests

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

### Modified Tests

Existing tests in `BuiltinJsToolMigrationTest.kt` and `WebfetchToolTest.kt` should continue to pass unchanged, since single-tool mode is backward-compatible (functionName defaults to null).

### Integration Test

```kotlin
class JsToolGroupIntegrationTest {
    // Test: create a group manifest + JS file on disk, load via JsToolLoader,
    //       register all tools, execute each one and verify correct function dispatched.
    // Test: mix of group files and single-tool files in the same directory -> all load correctly.
    // Test: user group tool with same name as built-in -> override works.
}
```

## Implementation Plan

This is a small, focused change across 3 files.

### Step 1: JsExecutionEngine

- Add `functionName: String? = null` parameter to `execute()`, `executeFromSource()`, `executeInQuickJs()`
- Change wrapper to use `val entryFunction = functionName ?: "execute"`
- Fully backward-compatible: existing callers pass null (default)

### Step 2: JsTool

- Add `functionName: String? = null` constructor parameter
- Pass `functionName` to engine in `execute()`
- Fully backward-compatible: existing construction sites pass null (default)

### Step 3: JsToolLoader

- Add `FUNCTION_NAME_REGEX` constant
- Rename `parseAndValidateMetadata()` to `parseToolEntry()` with `requireNameMatch: String?`
- Add `parseJsonManifest()` that detects object vs array
- Add `parseGroupManifest()` for array entries
- Update `loadBuiltinTools()` and `loadTools()` to use `parseJsonManifest()`
- Remove old `loadSingleTool()` method

### Step 4: Tests

- Add `JsToolGroupTest.kt`
- Run `./gradlew test` -- all must pass (including existing tests)

### Step 5: Build Verification

- `./gradlew compileDebugUnitTestKotlin`
- `./gradlew test`

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
