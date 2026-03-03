# RFC-012: JavaScript Tool Engine

## Document Information
- **RFC ID**: RFC-012
- **Related PRD**: [FEAT-012 (JavaScript Tool Engine)](../../prd/features/FEAT-012-js-tool-engine.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

The existing Tool System (RFC-004) provides a clean, extensible `Tool` interface with built-in Kotlin tools (`get_current_time`, `read_file`, `write_file`, `http_request`). However, adding a new tool currently requires writing Kotlin code, recompiling the app, and shipping a new release. This creates a high barrier to extending the agent's capabilities.

The JavaScript Tool Engine enables users to define custom tools as JavaScript files, executed via an embedded QuickJS runtime. JS tools integrate seamlessly into the existing ToolRegistry and ToolExecutionEngine -- from the AI model's perspective, a JS tool is indistinguishable from a built-in Kotlin tool. The primary tool creation workflow is asking the AI itself to write tools (using `write_file`), making tool creation as simple as describing what you want.

### Goals
1. Embed a QuickJS JavaScript runtime in the Android app
2. Implement `JsTool` -- a `Tool` implementation that loads and executes JavaScript files
3. Implement `JsToolLoader` to discover and load JS tools from the file system
4. Implement host bridge APIs (`fetch()`, `fs`, `console`) injected into the QuickJS context
5. Implement environment variable injection (`params._env`) for API keys
6. Integrate JS tools into the existing ToolRegistry, ToolExecutionEngine, and Agent tool selection
7. Add Settings UI for JS tool management and environment variables
8. Enforce timeout, memory limits, and path restrictions for JS tool execution

### Non-Goals
- In-app JavaScript code editor
- npm / Node.js module system (`require()`, `import` from npm packages)
- TypeScript support
- JS debugger or step-through execution
- Tool marketplace or distribution system
- Sandboxed process isolation (JS runs in-process via QuickJS)
- Hot reload via file system watcher (V1 uses manual "Reload tools" action)

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     Chat Layer (RFC-001)                      │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  tool call request from model                        │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   Tool Execution Engine (RFC-004)             │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ get_current_time │ │    read_file     │  Kotlin     │  │
│  │  │    (Kotlin)      │ │    (Kotlin)      │  built-in   │  │
│  │  └──────────────────┘ └──────────────────┘  tools      │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │   write_file     │ │  http_request    │             │  │
│  │  │    (Kotlin)      │ │    (Kotlin)      │             │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ weather_lookup   │ │   csv_parser     │  JS tools   │  │
│  │  │    (JsTool)      │ │    (JsTool)      │  (dynamic)  │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  └────────────────────────────────────────────────────────┘  │
│       │                                                      │
│       │  For JsTool.execute():                               │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                  JsExecutionEngine                      │  │
│  │  ┌──────────┐  ┌──────────────────────────────────┐    │  │
│  │  │ QuickJS  │  │     Bridge Functions              │    │  │
│  │  │ Runtime  │  │  ┌────────┐ ┌────┐ ┌─────────┐   │    │  │
│  │  │          │◄─┤  │ fetch()│ │ fs │ │console  │   │    │  │
│  │  │ execute( │  │  └───┬────┘ └──┬─┘ └────┬────┘   │    │  │
│  │  │  params) │  │      │         │        │        │    │  │
│  │  └──────────┘  └──────┼─────────┼────────┼────────┘    │  │
│  │                       │         │        │             │  │
│  │                  OkHttpClient  File I/O  Logcat        │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │                   JsToolLoader                          │  │
│  │  Scans directories for .js + .json file pairs           │  │
│  │  Parses metadata, creates JsTool instances               │  │
│  │  Registers into ToolRegistry                            │  │
│  └────────────────────────────────────────────────────────┘  │
│       │                      │                               │
│       v                      v                               │
│  /sdcard/OneClaw/   {app_internal}/                    │
│        tools/                 tools/                         │
│  weather_lookup.js        temperature_convert.js             │
│  weather_lookup.json      temperature_convert.json           │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

1. **JsTool (class implementing Tool)**
   - Responsibility: Wraps a JS tool file pair (`.js` + `.json`) as a standard `Tool` implementation
   - Interface: `Tool.definition` from parsed `.json`, `Tool.execute()` delegates to QuickJS
   - Dependencies: `JsExecutionEngine`, tool file paths

2. **JsToolLoader**
   - Responsibility: Scan tool directories, validate file pairs, parse metadata, create `JsTool` instances, register into `ToolRegistry`
   - Interface: `fun loadTools(registry: ToolRegistry): JsToolLoadResult`
   - Dependencies: File system, `JsExecutionEngine`

3. **JsExecutionEngine**
   - Responsibility: Manage QuickJS runtime lifecycle, inject bridge functions, execute JS code with timeout and memory limits
   - Interface: `suspend fun execute(jsFilePath: String, params: Map<String, Any?>, env: Map<String, String>, timeoutSeconds: Int): ToolResult`
   - Dependencies: QuickJS library, `OkHttpClient`, Android `Context`

4. **Bridge functions** (injected into QuickJS context)
   - `fetch()`: HTTP requests via OkHttpClient
   - `fs.readFile()`, `fs.writeFile()`, `fs.exists()`: File I/O with path restrictions
   - `console.log/warn/error`: Output to Logcat

5. **EnvironmentVariableStore**
   - Responsibility: Store and retrieve encrypted key-value pairs for JS tool environment variables
   - Interface: `getAll(): Map<String, String>`, `set(key, value)`, `delete(key)`, `getKeys(): List<String>`
   - Storage: EncryptedSharedPreferences (same mechanism as API keys)

### Technology Selection: QuickJS Library

**Selected: `dokar3/quickjs-kt`** (version 1.0.3)

| Criteria | Detail |
|----------|--------|
| Maven coordinates | `io.github.dokar3:quickjs-kt-android:1.0.3` |
| API style | Kotlin-first, DSL-based, suspend functions |
| Async/Promise | Deep coroutine integration, `async` functions supported |
| Host function injection | `function<>()` DSL, `define("obj") { property(...) }` for objects |
| Memory control | `memoryLimit`, `maxStackSize`, `gc()` |
| ABI support | arm64-v8a, armeabi-v7a, x86, x86_64 |
| Maintenance | Active (1.0.3 released Feb 2025), stable 1.x series |
| Android 15 | 16KB page size support since 1.0.1 |

**Timeout handling note**: `quickjs-kt` does not expose QuickJS's native `JS_SetInterruptHandler`. Timeout enforcement is handled by wrapping execution in `withTimeout()` at the coroutine level. For CPU-bound infinite loops in JS, the coroutine timeout will cancel the scope, and the QuickJS context is closed and discarded. This is sufficient for our use case since each execution uses a fresh context.

**Alternative considered**: `HarlonWang/quickjs-wrapper` -- Java-style API, less idiomatic Kotlin, no interrupt handler either. Rejected in favor of the Kotlin-first API.

## Detailed Design

### Directory Structure (New Files)

```
app/src/main/kotlin/com/oneclaw/shadow/
├── tool/
│   ├── engine/             # existing
│   │   ├── Tool.kt
│   │   ├── ToolRegistry.kt
│   │   └── ...
│   ├── builtin/            # existing
│   │   ├── GetCurrentTimeTool.kt
│   │   └── ...
│   └── js/                 # NEW
│       ├── JsTool.kt
│       ├── JsToolLoader.kt
│       ├── JsExecutionEngine.kt
│       ├── bridge/
│       │   ├── FetchBridge.kt
│       │   ├── FsBridge.kt
│       │   └── ConsoleBridge.kt
│       └── EnvironmentVariableStore.kt
├── ui/features/settings/   # existing, modified
│   ├── SettingsScreen.kt   # add JS tools section
│   └── SettingsViewModel.kt
│   ├── JsToolsSection.kt   # NEW
│   └── EnvVarsSection.kt   # NEW
└── di/
    └── ToolModule.kt       # modified
```

### JsTool

```kotlin
/**
 * Located in: tool/js/JsTool.kt
 *
 * A Tool implementation that executes a JavaScript file via QuickJS.
 * From the ToolRegistry's perspective, this is indistinguishable from a built-in Kotlin tool.
 */
class JsTool(
    override val definition: ToolDefinition,
    private val jsFilePath: String,
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return jsExecutionEngine.execute(
            jsFilePath = jsFilePath,
            toolName = definition.name,
            params = parameters,
            env = envVarStore.getAll(),
            timeoutSeconds = definition.timeoutSeconds
        )
    }
}
```

### JsToolLoader

```kotlin
/**
 * Located in: tool/js/JsToolLoader.kt
 *
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
        private const val EXTERNAL_TOOLS_DIR = "OneClaw/tools"
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

        // External storage: /sdcard/OneClaw/tools/
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
```

### JsExecutionEngine

```kotlin
/**
 * Located in: tool/js/JsExecutionEngine.kt
 *
 * Manages QuickJS runtime lifecycle and executes JS tool code.
 * Each execution gets a fresh QuickJS context for isolation.
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "JsExecutionEngine"
        private const val MAX_HEAP_SIZE = 16L * 1024 * 1024  // 16MB
        private const val MAX_STACK_SIZE = 1L * 1024 * 1024   // 1MB
    }

    /**
     * Execute a JS tool file with the given parameters.
     *
     * Creates a fresh QuickJS context, injects bridge functions,
     * loads the JS file, calls execute(params), and returns the result.
     */
    suspend fun execute(
        jsFilePath: String,
        toolName: String,
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs(jsFilePath, toolName, params, env)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "JS tool '$toolName' execution timed out after ${timeoutSeconds}s")
        } catch (e: CancellationException) {
            throw e  // propagate coroutine cancellation
        } catch (e: Exception) {
            Log.e(TAG, "JS tool '$toolName' execution failed", e)
            ToolResult.error("execution_error", "JS tool '$toolName' failed: ${e.message}")
        }
    }

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        toolName: String,
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        // Merge _env into params
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            // Configure memory limits
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            // Inject bridge: console
            ConsoleBridge.inject(this, toolName)

            // Inject bridge: fs
            FsBridge.inject(this)

            // Inject bridge: fetch
            FetchBridge.inject(this, okHttpClient)

            // Load and execute the JS file
            val jsCode = File(jsFilePath).readText()

            // Build the wrapper that calls execute() and captures the result.
            // We serialize params as JSON, parse it in JS, call execute(),
            // and serialize the result back.
            val paramsJson = Json.encodeToString(anyToJsonElement(paramsWithEnv))

            val wrapperCode = """
                $jsCode

                (async function __run__() {
                    const __params__ = JSON.parse(${quoteJsString(paramsJson)});
                    const __result__ = await execute(__params__);
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

    /**
     * Convert any Kotlin value to JsonElement for serialization.
     * Same helper used in provider adapters (RFC-004).
     */
    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Escape a string for safe embedding as a JS string literal.
     */
    private fun quoteJsString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
```

### Bridge Functions

#### FetchBridge

```kotlin
/**
 * Located in: tool/js/bridge/FetchBridge.kt
 *
 * Injects a fetch()-like API into the QuickJS context.
 * Delegates HTTP requests to OkHttpClient.
 */
object FetchBridge {

    private const val MAX_RESPONSE_SIZE = 100 * 1024  // 100KB, same as HttpRequestTool

    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // fetch(url, options?) -> Promise<Response>
        // Response = { ok, status, statusText, text(), json() }
        //
        // Implementation approach:
        // Register an async Kotlin function that performs the HTTP request
        // and returns a JSON-serialized response object.
        // On the JS side, wrap it in a Response-like object.

        quickJs.asyncFunction<String, String, String>("__fetchImpl") { url, optionsJson ->
            performFetch(okHttpClient, url, optionsJson)
        }

        // JS-side wrapper to provide the fetch() API
        quickJs.evaluate<Unit>("""
            async function fetch(url, options) {
                const optionsJson = options ? JSON.stringify(options) : "{}";
                const responseJson = await __fetchImpl(url, optionsJson);
                const raw = JSON.parse(responseJson);

                return {
                    ok: raw.status >= 200 && raw.status < 300,
                    status: raw.status,
                    statusText: raw.statusText,
                    _body: raw.body,
                    async text() { return this._body; },
                    async json() { return JSON.parse(this._body); }
                };
            }
        """.trimIndent())
    }

    private suspend fun performFetch(
        okHttpClient: OkHttpClient,
        url: String,
        optionsJson: String
    ): String {
        val options = try {
            Json.parseToJsonElement(optionsJson).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }

        val method = options["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val headers = options["headers"]?.jsonObject
        val body = options["body"]?.jsonPrimitive?.content

        val httpUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $url")

        val requestBuilder = Request.Builder().url(httpUrl)

        headers?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(requestBuilder.build()).execute()
        }

        val responseBody = response.body?.let { responseBody ->
            val bytes = responseBody.bytes()
            if (bytes.size > MAX_RESPONSE_SIZE) {
                val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                "$truncated\n\n(Response truncated. First ${MAX_RESPONSE_SIZE / 1024}KB of ${bytes.size / 1024}KB.)"
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } ?: ""

        // Return as JSON for the JS wrapper to parse
        val result = buildJsonObject {
            put("status", response.code)
            put("statusText", response.message)
            put("body", responseBody)
        }

        return result.toString()
    }
}
```

#### FsBridge

```kotlin
/**
 * Located in: tool/js/bridge/FsBridge.kt
 *
 * Injects file system functions into the QuickJS context.
 * Applies the same path restrictions as ReadFileTool / WriteFileTool.
 */
object FsBridge {

    private const val MAX_FILE_SIZE = 1024 * 1024  // 1MB, same as ReadFileTool

    private val RESTRICTED_PATHS = listOf(
        "/data/data/",
        "/data/user/",
        "/system/",
        "/proc/",
        "/sys/"
    )

    fun inject(quickJs: QuickJs) {
        quickJs.define("fs") {
            // fs.readFile(path) -> string
            function<String, String>("readFile") { path ->
                readFile(path)
            }

            // fs.writeFile(path, content) -> void (returns empty string)
            function<String, String, String>("writeFile") { path, content ->
                writeFile(path, content)
                ""
            }

            // fs.exists(path) -> boolean (returns "true" or "false" string, converted in JS)
            function<String, String>("exists") { path ->
                fileExists(path).toString()
            }
        }

        // JS wrapper to convert exists() return value to boolean
        quickJs.evaluate<Unit>("""
            const __origFsExists = fs.exists;
            fs.exists = function(path) {
                return __origFsExists(path) === "true";
            };
        """.trimIndent())
    }

    private fun validatePath(path: String): String {
        val canonicalPath = File(path).canonicalPath
        for (restricted in RESTRICTED_PATHS) {
            if (canonicalPath.startsWith(restricted)) {
                throw SecurityException("Access denied: path is restricted ($restricted)")
            }
        }
        return canonicalPath
    }

    private fun readFile(path: String): String {
        val canonical = validatePath(path)
        val file = File(canonical)

        if (!file.exists()) throw IllegalArgumentException("File not found: $path")
        if (!file.isFile) throw IllegalArgumentException("Path is a directory: $path")
        if (file.length() > MAX_FILE_SIZE) {
            throw IllegalArgumentException(
                "File too large (${file.length()} bytes). Maximum: $MAX_FILE_SIZE bytes."
            )
        }

        return file.readText(Charsets.UTF_8)
    }

    private fun writeFile(path: String, content: String) {
        val canonical = validatePath(path)
        val file = File(canonical)
        file.parentFile?.mkdirs()
        file.writeText(content, Charsets.UTF_8)
    }

    private fun fileExists(path: String): Boolean {
        return try {
            val canonical = validatePath(path)
            File(canonical).exists()
        } catch (e: SecurityException) {
            false  // restricted paths report as non-existent
        }
    }
}
```

#### ConsoleBridge

```kotlin
/**
 * Located in: tool/js/bridge/ConsoleBridge.kt
 *
 * Injects console.log/warn/error into the QuickJS context.
 * Output goes to Android Logcat.
 */
object ConsoleBridge {

    fun inject(quickJs: QuickJs, toolName: String) {
        val tag = "JSTool:$toolName"

        quickJs.define("console") {
            function<String, Unit>("log") { message ->
                Log.d(tag, message)
            }
            function<String, Unit>("warn") { message ->
                Log.w(tag, message)
            }
            function<String, Unit>("error") { message ->
                Log.e(tag, message)
            }
        }
    }
}
```

### EnvironmentVariableStore

```kotlin
/**
 * Located in: tool/js/EnvironmentVariableStore.kt
 *
 * Stores encrypted key-value pairs for JS tool environment variables.
 * Uses EncryptedSharedPreferences, same mechanism as API key storage.
 */
class EnvironmentVariableStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "js_tool_env_vars"
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        PREFS_NAME,
        MasterKey.DEFAULT_MASTER_KEY_ALIAS,
        context,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAll(): Map<String, String> {
        return prefs.all.mapValues { it.value.toString() }
    }

    fun get(key: String): String? {
        return prefs.getString(key, null)
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getKeys(): List<String> {
        return prefs.all.keys.toList().sorted()
    }
}
```

### Koin Dependency Injection Updates

```kotlin
// Updated ToolModule.kt
val toolModule = module {

    // Existing: Tool Registry - singleton, all tools registered at startup
    single {
        ToolRegistry().apply {
            // Built-in Kotlin tools
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient

            // JS tools: loaded from file system
            val loader: JsToolLoader = get()
            val loadResult = loader.loadTools()
            val conflicts = loader.registerTools(this, loadResult.loadedTools)

            // Log results
            val totalErrors = loadResult.errors + conflicts
            if (loadResult.loadedTools.isNotEmpty()) {
                Log.i("ToolModule", "Loaded ${loadResult.loadedTools.size} JS tool(s)")
            }
            totalErrors.forEach { error ->
                Log.w("ToolModule", "JS tool load error [${error.fileName}]: ${error.error}")
            }
        }
    }

    // Existing: Permission Checker
    single { PermissionChecker(androidContext()) }

    // Existing: Tool Execution Engine
    single { ToolExecutionEngine(get(), get()) }

    // NEW: JS Execution Engine
    single { JsExecutionEngine(get()) }  // get() = OkHttpClient

    // NEW: Environment Variable Store
    single { EnvironmentVariableStore(androidContext()) }

    // NEW: JS Tool Loader
    single { JsToolLoader(androidContext(), get(), get()) }  // JsExecutionEngine, EnvironmentVariableStore
}
```

### Settings UI

#### JsToolsSection

```kotlin
/**
 * Located in: ui/features/settings/JsToolsSection.kt
 *
 * Settings section showing loaded JS tools and reload button.
 */
@Composable
fun JsToolsSection(
    jsTools: List<JsToolInfo>,
    loadErrors: List<ToolLoadError>,
    onReloadClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        // Header row with title and reload button
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "JavaScript Tools",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "${jsTools.size} tool(s) loaded",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            TextButton(onClick = onReloadClick) {
                Icon(Icons.Default.Refresh, contentDescription = "Reload")
                Spacer(Modifier.width(4.dp))
                Text("Reload")
            }
        }

        // Tool list
        jsTools.forEach { tool ->
            JsToolRow(tool = tool)
        }

        // Errors
        loadErrors.forEach { error ->
            JsToolErrorRow(error = error)
        }
    }
}

@Composable
private fun JsToolRow(tool: JsToolInfo) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Green status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.primary,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = tool.name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun JsToolErrorRow(error: ToolLoadError) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Red status dot
        Box(
            modifier = Modifier
                .size(8.dp)
                .background(
                    color = MaterialTheme.colorScheme.error,
                    shape = CircleShape
                )
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = error.fileName,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = error.error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
```

#### EnvVarsSection

```kotlin
/**
 * Located in: ui/features/settings/EnvVarsSection.kt
 *
 * Settings section for managing environment variables.
 */
@Composable
fun EnvVarsSection(
    envVars: List<EnvVarEntry>,
    onAddClick: () -> Unit,
    onEditClick: (String) -> Unit,
    onDeleteClick: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Environment Variables",
                style = MaterialTheme.typography.titleMedium
            )
            TextButton(onClick = onAddClick) {
                Icon(Icons.Default.Add, contentDescription = "Add")
                Spacer(Modifier.width(4.dp))
                Text("Add")
            }
        }

        if (envVars.isEmpty()) {
            Text(
                text = "No environment variables configured",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            envVars.forEach { entry ->
                EnvVarRow(
                    entry = entry,
                    onEditClick = { onEditClick(entry.key) },
                    onDeleteClick = { onDeleteClick(entry.key) }
                )
            }
        }
    }
}

data class EnvVarEntry(
    val key: String,
    val maskedValue: String  // e.g., "sk-...3fa8"
)

@Composable
private fun EnvVarRow(
    entry: EnvVarEntry,
    onEditClick: () -> Unit,
    onDeleteClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = entry.key,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = entry.maskedValue,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = onEditClick) {
            Icon(Icons.Default.Edit, contentDescription = "Edit")
        }
        IconButton(onClick = onDeleteClick) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
    }
}
```

### SettingsViewModel Updates

```kotlin
// Add to SettingsViewModel:

// JS Tools state
private val _jsToolsState = MutableStateFlow(JsToolsUiState())
val jsToolsState: StateFlow<JsToolsUiState> = _jsToolsState.asStateFlow()

data class JsToolsUiState(
    val tools: List<JsToolInfo> = emptyList(),
    val errors: List<JsToolLoader.ToolLoadError> = emptyList(),
    val isReloading: Boolean = false
)

data class JsToolInfo(
    val name: String,
    val description: String,
    val filePath: String,
    val timeoutSeconds: Int
)

fun reloadJsTools() {
    viewModelScope.launch {
        _jsToolsState.update { it.copy(isReloading = true) }

        withContext(Dispatchers.IO) {
            // Unregister existing JS tools from registry
            val registry: ToolRegistry = get()
            registry.unregisterByType<JsTool>()

            // Reload from file system
            val loader: JsToolLoader = get()
            val loadResult = loader.loadTools()
            val conflicts = loader.registerTools(registry, loadResult.loadedTools)

            val allErrors = loadResult.errors + conflicts
            val toolInfos = loadResult.loadedTools.map { tool ->
                JsToolInfo(
                    name = tool.definition.name,
                    description = tool.definition.description,
                    filePath = tool.jsFilePath,
                    timeoutSeconds = tool.definition.timeoutSeconds
                )
            }

            _jsToolsState.update {
                JsToolsUiState(
                    tools = toolInfos,
                    errors = allErrors,
                    isReloading = false
                )
            }
        }
    }
}

// Environment variables state
private val _envVarsState = MutableStateFlow<List<EnvVarEntry>>(emptyList())
val envVarsState: StateFlow<List<EnvVarEntry>> = _envVarsState.asStateFlow()

fun loadEnvVars() {
    viewModelScope.launch(Dispatchers.IO) {
        val store: EnvironmentVariableStore = get()
        val entries = store.getKeys().map { key ->
            val value = store.get(key) ?: ""
            EnvVarEntry(
                key = key,
                maskedValue = maskValue(value)
            )
        }
        _envVarsState.value = entries
    }
}

fun addEnvVar(key: String, value: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val store: EnvironmentVariableStore = get()
        store.set(key, value)
        loadEnvVars()
    }
}

fun deleteEnvVar(key: String) {
    viewModelScope.launch(Dispatchers.IO) {
        val store: EnvironmentVariableStore = get()
        store.delete(key)
        loadEnvVars()
    }
}

private fun maskValue(value: String): String {
    if (value.length <= 8) return "****"
    return "${value.take(3)}...${value.takeLast(4)}"
}
```

### ToolRegistry Enhancement

A small addition to `ToolRegistry` to support unregistering JS tools on reload:

```kotlin
// Add to ToolRegistry:

/**
 * Unregister all tools of a specific type.
 * Used by JS tool reload to remove old JS tools before re-scanning.
 */
inline fun <reified T : Tool> unregisterByType() {
    val keysToRemove = tools.entries
        .filter { it.value is T }
        .map { it.key }
    keysToRemove.forEach { tools.remove(it) }
}
```

## Data Flow

### Flow: AI Creates a JS Tool

```
1. User: "Create a tool that calculates BMI"

2. AI calls write_file(path="/sdcard/OneClaw/tools/bmi_calculator.json", content=...)
   -> JSON metadata written to disk

3. AI calls write_file(path="/sdcard/OneClaw/tools/bmi_calculator.js", content=...)
   -> JS code written to disk

4. AI responds: "I've created a BMI calculator tool. Tap 'Reload tools' in Settings to activate it."

5. User opens Settings -> JS Tools section -> taps "Reload"

6. JsToolLoader.loadTools():
   a. Scans /sdcard/OneClaw/tools/
   b. Finds bmi_calculator.json + bmi_calculator.js
   c. Parses metadata, creates JsTool instance
   d. Registers into ToolRegistry

7. Tool appears in Settings JS tools list with green dot

8. User can now assign the tool to an Agent and use it in chat
```

### Flow: JS Tool Execution

```
1. Model response includes tool call:
   tool_name: "bmi_calculator", parameters: {"weight_kg": 70, "height_m": 1.75}

2. ToolExecutionEngine.executeTool("bmi_calculator", params, agentToolIds)
   a. ToolRegistry.getTool("bmi_calculator") -> JsTool instance
   b. Availability check -> OK
   c. Parameter validation -> OK
   d. Permission check -> no permissions required
   e. JsTool.execute(params) called

3. JsTool.execute() -> JsExecutionEngine.execute():
   a. Creates fresh QuickJS context
   b. Sets memoryLimit = 16MB, maxStackSize = 1MB
   c. Injects console bridge (tag: "JSTool:bmi_calculator")
   d. Injects fs bridge
   e. Injects fetch bridge
   f. Reads bmi_calculator.js from disk
   g. Constructs wrapper code:
      - Embeds tool JS code
      - Serializes params as JSON
      - Calls execute(params)
   h. Evaluates via quickJs { evaluate<String>(wrapperCode) }
   i. Result: "BMI: 22.86 (Normal weight)"
   j. QuickJS context closed and released

4. ToolResult.success("BMI: 22.86 (Normal weight)")

5. Result returned to model via SendMessageUseCase (same as any tool)
```

### Flow: JS Tool with fetch() Bridge

```
1. weather_lookup.js calls fetch():

   async function execute(params) {
       const response = await fetch("https://api.example.com/weather?city=" + params.city);
       const data = await response.json();
       return `Weather: ${data.temp}°C`;
   }

2. Execution trace:
   a. JS calls fetch(url, options)
   b. JS wrapper serializes options to JSON
   c. Calls __fetchImpl(url, optionsJson) -- Kotlin async function
   d. Kotlin FetchBridge.performFetch():
      - Parses options JSON
      - Builds OkHttp Request
      - Executes on Dispatchers.IO
      - Reads response body (truncates if > 100KB)
      - Serializes response as JSON {status, statusText, body}
   e. Returns JSON string to JS
   f. JS wrapper parses into Response-like object
   g. JS calls response.json() -> parses body as JSON
   h. JS returns formatted string
```

## Error Handling

### Error Scenarios and Behavior

| Scenario | Error Type | Error Message | What Happens |
|----------|-----------|---------------|--------------|
| `.json` without `.js` | (load error) | "Missing corresponding .js file" | Tool skipped, error shown in Settings |
| Invalid JSON metadata | (load error) | "Failed to load: ..." | Tool skipped, error shown in Settings |
| Name mismatch (json name != filename) | (load error) | "Tool name 'x' does not match filename 'y'" | Tool skipped |
| Name conflict with built-in | (load error) | "Name conflict with existing tool" | JS tool skipped, warning logged |
| JS syntax error | `execution_error` | "SyntaxError: ..." | First execution fails, error returned to model |
| Missing `execute` function | `execution_error` | "execute is not defined" | Error returned to model |
| `execute` throws Error | `execution_error` | Error message from JS | Error returned to model |
| Timeout | `timeout` | "JS tool timed out after Ns" | QuickJS context discarded |
| Memory exhaustion | `execution_error` | QuickJS OOM message | Context discarded |
| fetch() network error | `execution_error` | Network error propagated | JS can catch or let propagate |
| fs restricted path | `execution_error` | "Access denied: path is restricted" | JS exception, propagated |
| fs file not found | `execution_error` | "File not found: ..." | JS exception, propagated |

### Error Recovery

- **Load errors**: Other tools continue loading. Errors are displayed in Settings with red dots.
- **Execution errors**: Returned as `ToolResult.error()` to the model. The model can inform the user or try a different approach.
- **Timeout**: QuickJS context is discarded (not reused). No memory leak.
- **App crash prevention**: All JS execution is wrapped in try-catch. QuickJS native crashes are caught at the JNI boundary by the quickjs-kt library.

## Implementation Steps

### Phase 1: QuickJS Integration & Core Engine
1. [ ] Add `io.github.dokar3:quickjs-kt-android:1.0.3` dependency to `app/build.gradle.kts`
2. [ ] Create `tool/js/` package
3. [ ] Implement `JsExecutionEngine` -- QuickJS context lifecycle, timeout, memory limits
4. [ ] Write unit test: basic JS evaluation (arithmetic, string ops)
5. [ ] Write unit test: async JS evaluation (Promise)
6. [ ] Write unit test: timeout enforcement
7. [ ] Write unit test: memory limit enforcement

### Phase 2: Bridge Functions
8. [ ] Implement `ConsoleBridge` -- console.log/warn/error to Logcat
9. [ ] Implement `FsBridge` -- readFile, writeFile, exists with path restrictions
10. [ ] Implement `FetchBridge` -- fetch() API delegating to OkHttpClient
11. [ ] Write unit test: ConsoleBridge outputs to Logcat (verify via mock)
12. [ ] Write unit test: FsBridge readFile/writeFile/exists with temp files
13. [ ] Write unit test: FsBridge path restriction enforcement
14. [ ] Write unit test: FetchBridge GET/POST with MockWebServer
15. [ ] Write unit test: FetchBridge response truncation at 100KB

### Phase 3: Tool Loading & Registration
16. [ ] Implement `JsToolLoader` -- directory scanning, JSON parsing, validation
17. [ ] Implement `JsTool` -- Tool interface wrapper
18. [ ] Implement `EnvironmentVariableStore` -- EncryptedSharedPreferences for env vars
19. [ ] Add `unregisterByType<T>()` to `ToolRegistry`
20. [ ] Write unit test: JsToolLoader with valid tool files
21. [ ] Write unit test: JsToolLoader with missing .js file
22. [ ] Write unit test: JsToolLoader with invalid JSON
23. [ ] Write unit test: JsToolLoader name validation (snake_case)
24. [ ] Write unit test: JsToolLoader name conflict with built-in tool
25. [ ] Write unit test: JsTool.execute() end-to-end with simple JS
26. [ ] Write unit test: EnvironmentVariableStore CRUD operations

### Phase 4: DI & Integration
27. [ ] Update `ToolModule.kt` with new singletons (JsExecutionEngine, EnvironmentVariableStore, JsToolLoader)
28. [ ] Update `ToolRegistry` initialization to call JsToolLoader
29. [ ] Ensure tool directories are created on first launch
30. [ ] Integration test: JS tool loaded -> registered -> executed via ToolExecutionEngine
31. [ ] Integration test: JS tool with fetch() bridge making real HTTP request
32. [ ] Integration test: JS tool with fs bridge reading/writing files

### Phase 5: Settings UI
33. [ ] Implement `JsToolsSection` composable
34. [ ] Implement `EnvVarsSection` composable
35. [ ] Implement add/edit/delete environment variable dialogs
36. [ ] Add JS tools and env vars sections to `SettingsScreen`
37. [ ] Add `reloadJsTools()` and env var management to `SettingsViewModel`
38. [ ] Write screenshot tests for new Settings sections (Roborazzi)

### Phase 6: Testing
39. [ ] Run `./gradlew test` -- all unit tests pass
40. [ ] Run `./gradlew connectedAndroidTest` -- all instrumented tests pass
41. [ ] Run `./gradlew recordRoborazziDebug` + `verifyRoborazziDebug` -- screenshots pass
42. [ ] Layer 2: adb visual verification flows (see below)
43. [ ] Write test report

## Testing Strategy

### Unit Tests

- `JsExecutionEngine`: basic evaluation, async, timeout, memory limit, error handling
- `ConsoleBridge`: verify log output via mock/spy
- `FsBridge`: readFile, writeFile, exists -- with temp directory; restricted path rejection
- `FetchBridge`: GET/POST via MockWebServer; response truncation; error responses
- `JsToolLoader`: valid tool pair, missing .js, invalid JSON, name validation, name conflict, empty directory
- `JsTool`: end-to-end execute with mock JsExecutionEngine
- `EnvironmentVariableStore`: set/get/delete/getAll/getKeys
- `ToolRegistry.unregisterByType`: register mixed types, unregister one type

### Integration Tests (Instrumented)

- JS tool lifecycle: load from device storage, register, execute, verify result
- fetch() bridge with actual network request (requires emulator with internet)
- fs bridge with actual file read/write on emulator storage
- Environment variable injection into JS execution
- Reload: add new tool files, reload, verify new tool is available

### Edge Cases

- Tool directory does not exist (should be auto-created)
- Empty tools directory (zero JS tools, no error)
- `.json` file without `.js` (error, other tools still load)
- `.js` file without `.json` (silently ignored)
- JS file with syntax error (loads, fails on execution)
- `execute` returns number/object/array (converted via JSON.stringify)
- `execute` returns null/undefined (empty string result)
- `execute` not defined (clear error message)
- Infinite loop in JS (timeout triggers)
- fetch() to unreachable host (network error)
- fs.readFile on non-existent file (error)
- fs.writeFile to restricted path (error)
- Multiple JS tools, one invalid (others still load)
- JS tool name same as built-in tool (skipped)
- Very large string returned from execute (should work within memory limit)
- Unicode in JS files, parameters, and results

### Layer 2 Visual Verification Flows

#### Flow 12-1: JS Tool Loading and Settings Display

**Precondition:** Push test tool files to emulator:
```bash
adb shell mkdir -p /sdcard/OneClaw/tools
adb push test_tool.json /sdcard/OneClaw/tools/
adb push test_tool.js /sdcard/OneClaw/tools/
```

```
Goal: Verify JS tools are loaded on startup and displayed in Settings.

Steps:
1. Launch app (or tap Reload in Settings).
2. Navigate to Settings.
3. Screenshot -> Verify: "JavaScript Tools" section visible with "1 tool(s) loaded".
4. Verify: test_tool appears in the list with green dot.
5. Verify: Tool name and description are displayed.
```

---

#### Flow 12-2: JS Tool Execution via Chat

**Precondition:** Valid API key configured. test_tool registered (Flow 12-1 complete).

```
Goal: Verify a JS tool can be called by the AI model and returns results.

Steps:
1. Ensure test_tool is assigned to the current Agent's tool set.
2. Send a message that would trigger the test_tool call.
3. Wait for tool call to appear.
4. Screenshot -> Verify: Tool call card for test_tool with status PENDING/EXECUTING.
5. Wait for completion.
6. Screenshot -> Verify:
   - Tool call card shows SUCCESS.
   - Final AI response references the tool result.
```

---

#### Flow 12-3: JS Tool with fetch() Bridge

**Precondition:** Valid API key configured. A JS tool using fetch() is registered.

```
Goal: Verify fetch() bridge works in a JS tool.

Steps:
1. Register a JS tool that calls fetch("https://httpbin.org/get").
2. Send a message that triggers this tool.
3. Wait for completion.
4. Screenshot -> Verify:
   - Tool call shows SUCCESS.
   - AI response includes data from the httpbin response.
```

---

#### Flow 12-4: JS Tool Error Handling

**Precondition:** Register a JS tool with a deliberate error (e.g., throws Error).

```
Goal: Verify JS tool errors are handled gracefully (no app crash).

Steps:
1. Register a tool whose execute() throws new Error("test error").
2. Trigger the tool via chat.
3. Wait for result.
4. Screenshot -> Verify:
   - Tool call card shows ERROR status.
   - Error message visible.
   - App is still responsive (no crash).
```

---

#### Flow 12-5: Reload Tools in Settings

**Precondition:** App running, Settings open.

```
Goal: Verify "Reload tools" picks up new/removed tool files.

Steps:
1. Push a new JS tool via adb: adb push new_tool.json + new_tool.js
2. In Settings, tap "Reload" button.
3. Screenshot -> Verify: new_tool appears in the list with green dot.
4. Remove the tool files via adb: adb shell rm /sdcard/OneClaw/tools/new_tool.*
5. Tap "Reload" again.
6. Screenshot -> Verify: new_tool no longer appears in the list.
```

---

#### Flow 12-6: Environment Variables

**Precondition:** Settings open.

```
Goal: Verify environment variables can be added and are injected into JS tools.

Steps:
1. Navigate to Settings -> Environment Variables section.
2. Screenshot -> Verify: "No environment variables configured" text visible.
3. Tap "Add" button.
4. Enter key: "TEST_KEY", value: "test_value_123".
5. Confirm.
6. Screenshot -> Verify: TEST_KEY appears in the list with masked value.
```

## Security Considerations

1. **QuickJS sandbox**: JS code runs inside QuickJS's embedded runtime. It cannot access JVM objects, Android APIs, or Kotlin code directly. The only escape paths are the explicitly provided bridge functions.

2. **File access boundaries**: `FsBridge` applies the same path restrictions as the built-in `ReadFileTool`/`WriteFileTool` -- blocks `/data/data/`, `/data/user/`, `/system/`, `/proc/`, `/sys/`. Uses `File.canonicalPath` to prevent symlink attacks.

3. **No arbitrary code execution outside tools**: JS code is only evaluated within the `JsExecutionEngine.execute()` path. There is no `eval()` or other JS evaluation endpoint.

4. **Memory limits**: Each QuickJS context is limited to 16MB heap and 1MB stack. Memory exhaustion triggers an error, not an app crash.

5. **Timeout enforcement**: Prevents denial-of-service via infinite loops. The QuickJS context is discarded on timeout.

6. **Environment variable isolation**: Environment variables are stored encrypted via EncryptedSharedPreferences. They are injected as `params._env` (read-only from the JS perspective). They are not visible in the `.js` or `.json` files.

7. **Network**: `fetch()` bridge delegates to OkHttpClient with the same response size limits (100KB) as the built-in `HttpRequestTool`. No proxy or credential injection.

8. **Tool file provenance**: Users are responsible for the JS tools they install. The app does not verify tool file signatures or provenance. This is an acceptable trade-off for V1 -- the user explicitly places files or asks the AI to create them.

## Dependencies

### Depends On
- **RFC-004 (Tool System)**: Tool interface, ToolRegistry, ToolExecutionEngine, ToolResult, ToolDefinition
- **RFC-000 (Overall Architecture)**: Core models, Koin DI setup, OkHttpClient
- **FEAT-009 (Settings)**: Settings screen for UI additions
- **quickjs-kt library**: `io.github.dokar3:quickjs-kt-android:1.0.3`

### Depended On By
- **RFC-001 (Chat Interaction)**: JS tool calls displayed in chat (no changes needed -- they are standard Tool calls)
- **RFC-002 (Agent Management)**: Agents can select JS tools (no changes needed -- they appear in ToolRegistry)

## Alternatives Considered

### Alternative 1: JS tools call existing Kotlin tools (orchestration model)

Instead of providing direct `fetch()` and `fs` bridges, JS tools would call existing Kotlin tools (e.g., `callTool("http_request", {url: "..."})`) to compose capabilities.

- **Pros**: No bridge code needed, inherits all existing security/permission checks
- **Cons**: Awkward API for JS authors, tightly couples JS tools to Kotlin tool names, limits what JS can do to existing tool capabilities, latency overhead from tool dispatch
- **Rejected because**: Direct bridges provide a more natural JS development experience and don't limit JS tools to existing capabilities.

### Alternative 2: HarlonWang/quickjs-wrapper library

- **Pros**: More stars (254 vs 120), slightly more mature
- **Cons**: Java-style API, not Kotlin-idiomatic, no coroutine integration, same lack of interrupt handler
- **Rejected because**: Kotlin-first API of quickjs-kt is significantly better for this codebase.

### Alternative 3: Lua instead of JavaScript

- **Pros**: Lighter weight runtime, simpler language
- **Cons**: Much smaller developer audience, less familiar syntax, fewer available libraries, harder for AI to generate Lua tools
- **Rejected because**: JavaScript is far more widely known, and AI models are better at generating JS code.

## Open Questions

- [ ] Should `quickjs-kt` contexts be pooled for performance, or always fresh for isolation? (Current design: fresh, for simplicity and isolation. Can optimize later if profiling shows overhead.)
- [ ] Should `fetch()` bridge support request timeouts independent of the tool timeout? (Current design: fetch shares the overall tool timeout.)
- [ ] Should there be a maximum number of JS tools that can be loaded? (Current design: no limit. Can add if performance becomes an issue.)
- [ ] Should environment variables be global or per-tool? (Current design: global. Per-tool would require additional UI complexity.)
- [ ] The `quickjs-kt` `asyncFunction` API may have limitations for complex async patterns. Need to verify with real-world fetch() usage during implementation.

## Future Improvements

- [ ] **Hot reload**: File system watcher (FileObserver) to auto-detect tool file changes
- [ ] **Tool testing**: "Test tool" button in Settings that runs a tool with sample parameters
- [ ] **Share intent**: Receive `.js` + `.json` files from other apps via Android share intent
- [ ] **Tool templates**: Bundled template JS tools that demonstrate common patterns
- [ ] **AI-assisted reload**: After the AI writes tool files, automatically trigger a reload without manual Settings visit
- [ ] **Context pooling**: Pool and reuse QuickJS contexts for better performance (requires careful isolation)
- [ ] **ES modules**: Support `import`/`export` between JS files for shared utility code

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
