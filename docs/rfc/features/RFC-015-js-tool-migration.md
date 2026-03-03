# RFC-015: JavaScript Tool Migration & Library System

## Document Information
- **RFC ID**: RFC-015
- **Related PRD**: [FEAT-015 (JS Tool Migration & Library System)](../../prd/features/FEAT-015-js-tool-migration.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-012 (JavaScript Tool Engine)](RFC-012-js-tool-engine.md)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

RFC-012 introduced the JavaScript Tool Engine: a QuickJS runtime with native bridges (`fetch()`, `fs`, `console`) that enables user-defined JS tools. However, the four original built-in tools (`get_current_time`, `read_file`, `write_file`, `http_request`) remain as Kotlin classes in `tool/builtin/`. This creates two parallel tool authoring paths and prevents built-in tools from benefiting from the JS library ecosystem.

RFC-015 migrates all built-in Kotlin tools to JavaScript implementations backed by the same native bridges, adds a shared JS library loading system (`lib()`), bundles Turndown as the first shared library, and introduces a new `webfetch` tool that converts HTML pages to Markdown.

After this RFC, the Kotlin `tool/builtin/` package contains only `LoadSkillTool` (which requires direct access to `SkillRegistry` -- a Kotlin-only dependency). All other tools are JS files.

### Goals

1. Implement `LibraryBridge` -- a `lib()` function in QuickJS that loads shared JS libraries from assets
2. Bundle Turndown (~20KB minified) as the first shared library
3. Add `TimeBridge` for timezone-aware time formatting (required by `get_current_time`)
4. Enhance `FetchBridge` to return response headers (required by `webfetch`)
5. Enhance `FsBridge` to support `fs.appendFile()` (required by `write_file` append mode)
6. Extend `JsToolLoader` to load built-in JS tools from `assets/js/tools/`
7. Migrate `get_current_time`, `read_file`, `write_file`, `http_request` from Kotlin to JS
8. Add new `webfetch` built-in JS tool
9. Remove Kotlin tool classes: `GetCurrentTimeTool.kt`, `ReadFileTool.kt`, `WriteFileTool.kt`, `HttpRequestTool.kt`
10. Update `ToolModule` to load built-in JS tools from assets instead of instantiating Kotlin classes

### Non-Goals

- In-app library manager UI (V1 bundles libraries as assets only)
- npm or ES module import system
- Migrating `LoadSkillTool` to JS (it depends on Kotlin `SkillRegistry`)
- HTML truncation or token-budget awareness in `webfetch`
- Response caching in `webfetch`

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
│  │  ┌──────────────────┐                                  │  │
│  │  │   load_skill     │  Kotlin built-in (only one left) │  │
│  │  │    (Kotlin)      │                                  │  │
│  │  └──────────────────┘                                  │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ get_current_time │ │    read_file     │  Built-in   │  │
│  │  │    (JS/asset)    │ │    (JS/asset)    │  JS tools   │  │
│  │  └──────────────────┘ └──────────────────┘  [NEW]      │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │   write_file     │ │  http_request    │             │  │
│  │  │    (JS/asset)    │ │    (JS/asset)    │             │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  │  ┌──────────────────┐                                  │  │
│  │  │    webfetch      │  New built-in JS tool [NEW]      │  │
│  │  │    (JS/asset)    │                                  │  │
│  │  └──────────────────┘                                  │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ weather_lookup   │ │   csv_parser     │  User JS    │  │
│  │  │  (JsTool/user)   │ │  (JsTool/user)   │  tools      │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  └────────────────────────────────────────────────────────┘  │
│       │                                                      │
│       │  For JsTool.execute():                               │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                  JsExecutionEngine                      │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │             Bridge Functions                      │  │  │
│  │  │  ┌────────┐ ┌────┐ ┌─────────┐ ┌──────┐ ┌─────┐ │  │  │
│  │  │  │ fetch()│ │ fs │ │console  │ │_time │ │lib()│ │  │  │
│  │  │  └───┬────┘ └──┬─┘ └────┬────┘ └──┬───┘ └──┬──┘ │  │  │
│  │  │      │         │        │         │        │    │  │  │
│  │  └──────┼─────────┼────────┼─────────┼────────┼────┘  │  │
│  │         │         │        │         │        │       │  │
│  │    OkHttp    File I/O   Logcat   ZonedDT   Assets    │  │
│  └────────────────────────────────────────────────────────┘  │
├──────────────────────────────────────────────────────────────┤
│  ┌────────────────────────────────────────────────────────┐  │
│  │                   JsToolLoader                          │  │
│  │  1. loadBuiltinTools()  <- assets/js/tools/ [NEW]       │  │
│  │  2. loadTools()         <- file system (RFC-012)         │  │
│  │  User tools override built-in on name conflict [CHANGED]│  │
│  └────────────────────────────────────────────────────────┘  │
│       │              │              │                         │
│       v              v              v                         │
│  assets/js/     /sdcard/OCS/   {internal}/                   │
│   tools/          tools/         tools/                      │
│   lib/                                                       │
│    turndown.min.js                                           │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

**New:**
1. `LibraryBridge` -- Injects `lib()` function into QuickJS for loading shared JS libraries
2. `TimeBridge` -- Injects `_time()` function for timezone-aware time formatting
3. Built-in JS tool files (`assets/js/tools/*.js` + `*.json`)
4. Bundled library file (`assets/js/lib/turndown.min.js`)

**Modified:**
5. `FetchBridge` -- Add response headers to fetch result
6. `FsBridge` -- Add `fs.appendFile()`
7. `JsExecutionEngine` -- Inject `LibraryBridge` and `TimeBridge`
8. `JsToolLoader` -- Add `loadBuiltinTools()`, change name conflict policy
9. `ToolModule` -- Replace Kotlin tool registration with built-in JS tool loading

**Removed:**
10. `GetCurrentTimeTool.kt`, `ReadFileTool.kt`, `WriteFileTool.kt`, `HttpRequestTool.kt`

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── assets/                                  # NEW directory
│   └── js/
│       ├── lib/
│       │   └── turndown.min.js              # Bundled library (~20KB)
│       └── tools/
│           ├── get_current_time.js           # Migrated from Kotlin
│           ├── get_current_time.json
│           ├── read_file.js
│           ├── read_file.json
│           ├── write_file.js
│           ├── write_file.json
│           ├── http_request.js
│           ├── http_request.json
│           ├── webfetch.js                  # NEW tool
│           └── webfetch.json
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── GetCurrentTimeTool.kt        # DELETED
│   │   │   ├── ReadFileTool.kt              # DELETED
│   │   │   ├── WriteFileTool.kt             # DELETED
│   │   │   ├── HttpRequestTool.kt           # DELETED
│   │   │   └── LoadSkillTool.kt             # KEPT (unchanged)
│   │   └── js/
│   │       ├── bridge/
│   │       │   ├── ConsoleBridge.kt          # unchanged
│   │       │   ├── FetchBridge.kt            # MODIFIED (add response headers)
│   │       │   ├── FsBridge.kt               # MODIFIED (add appendFile)
│   │       │   ├── LibraryBridge.kt          # NEW
│   │       │   └── TimeBridge.kt             # NEW
│   │       ├── JsExecutionEngine.kt          # MODIFIED
│   │       ├── JsTool.kt                     # MODIFIED (support asset-based source)
│   │       └── JsToolLoader.kt               # MODIFIED
│   └── di/
│       └── ToolModule.kt                     # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── GetCurrentTimeToolTest.kt     # DELETED (replaced by JS test)
│   │   │   ├── ReadFileToolTest.kt           # DELETED
│   │   │   ├── WriteFileToolTest.kt          # DELETED
│   │   │   ├── HttpRequestToolTest.kt        # DELETED
│   │   │   └── LoadSkillToolTest.kt          # KEPT
│   │   └── js/
│   │       ├── bridge/
│   │       │   ├── LibraryBridgeTest.kt      # NEW
│   │       │   └── TimeBridgeTest.kt         # NEW
│   │       ├── BuiltinJsToolMigrationTest.kt # NEW
│   │       └── WebfetchToolTest.kt           # NEW
```

### LibraryBridge

```kotlin
/**
 * Located in: tool/js/bridge/LibraryBridge.kt
 *
 * Injects a lib() function into the QuickJS context that loads
 * shared JavaScript libraries from bundled assets or internal storage.
 *
 * Usage in JS: const TurndownService = lib('turndown');
 */
class LibraryBridge(private val context: Context) {

    companion object {
        private const val TAG = "LibraryBridge"
        private const val ASSETS_LIB_DIR = "js/lib"
        private const val INTERNAL_LIB_DIR = "js/lib"
    }

    // Cache evaluated library exports across tool executions within
    // the same app session. Libraries are pure and deterministic,
    // so caching is safe.
    // Key: library name, Value: JS source code
    private val sourceCache = mutableMapOf<String, String>()

    /**
     * Inject the lib() function into a QuickJS context.
     * Must be called before evaluating tool code.
     *
     * Because QuickJS contexts are fresh per execution, we cannot cache
     * evaluated JS objects across executions. Instead we cache the source
     * code and re-evaluate it per context. The evaluation cost for
     * Turndown (~20KB) is < 50ms.
     */
    fun inject(quickJs: QuickJs) {
        quickJs.function("__loadLibSource") { args: Array<Any?> ->
            val name = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("lib: name argument required")
            loadLibrarySource(name)
        }

        // The actual lib() wrapper evaluates the source and extracts exports
        // via the CommonJS module.exports / exports pattern.
    }

    /**
     * JS wrapper code evaluated in the QuickJS context to provide lib().
     * Must be evaluated after inject() and before tool code.
     */
    val LIB_WRAPPER_JS = """
        const __libCache = {};
        function lib(name) {
            if (__libCache[name]) return __libCache[name];
            const __source = __loadLibSource(name);
            // CommonJS-style module wrapper
            const module = { exports: {} };
            const exports = module.exports;
            const fn = new Function('module', 'exports', __source);
            fn(module, exports);
            const result = (Object.keys(module.exports).length > 0)
                ? module.exports
                : exports;
            __libCache[name] = result;
            return result;
        }
    """.trimIndent()

    private fun loadLibrarySource(name: String): String {
        // Check source cache first
        sourceCache[name]?.let { return it }

        // Sanitize: library name must be alphanumeric + hyphens + underscores
        if (!name.matches(Regex("^[a-zA-Z][a-zA-Z0-9_-]*$"))) {
            throw IllegalArgumentException("Invalid library name: '$name'")
        }

        // Try assets first
        val assetPath = "$ASSETS_LIB_DIR/$name.min.js"
        val assetFallbackPath = "$ASSETS_LIB_DIR/$name.js"

        val source = tryLoadFromAssets(assetPath)
            ?: tryLoadFromAssets(assetFallbackPath)
            ?: tryLoadFromInternal(name)
            ?: throw IllegalArgumentException(
                "Library '$name' not found. Searched: assets/$assetPath, assets/$assetFallbackPath, internal/$INTERNAL_LIB_DIR/"
            )

        sourceCache[name] = source
        return source
    }

    private fun tryLoadFromAssets(path: String): String? {
        return try {
            context.assets.open(path).bufferedReader().use { it.readText() }
        } catch (e: Exception) {
            null
        }
    }

    private fun tryLoadFromInternal(name: String): String? {
        val dir = File(context.filesDir, INTERNAL_LIB_DIR)
        // Try .min.js first, then .js
        val minFile = File(dir, "$name.min.js")
        if (minFile.exists()) return minFile.readText()
        val plainFile = File(dir, "$name.js")
        if (plainFile.exists()) return plainFile.readText()
        return null
    }
}
```

### TimeBridge

```kotlin
/**
 * Located in: tool/js/bridge/TimeBridge.kt
 *
 * Injects _time(timezone?, format?) into the QuickJS context.
 * Delegates to Java's ZonedDateTime for accurate timezone handling.
 *
 * QuickJS does not have the Intl API, so timezone-aware formatting
 * must be bridged to the host.
 */
object TimeBridge {

    fun inject(quickJs: QuickJs) {
        quickJs.function("_time") { args: Array<Any?> ->
            val timezone = args.getOrNull(0)?.toString()?.takeIf { it.isNotEmpty() }
            val format = args.getOrNull(1)?.toString() ?: "iso8601"
            getCurrentTime(timezone, format)
        }
    }

    private fun getCurrentTime(timezone: String?, format: String): String {
        val zone = if (timezone != null) {
            try {
                java.time.ZoneId.of(timezone)
            } catch (e: Exception) {
                throw IllegalArgumentException(
                    "Invalid timezone: '$timezone'. Use IANA format (e.g., 'America/New_York')."
                )
            }
        } else {
            java.time.ZoneId.systemDefault()
        }

        val now = java.time.ZonedDateTime.now(zone)

        return when (format) {
            "human_readable" -> {
                val formatter = java.time.format.DateTimeFormatter.ofPattern(
                    "EEEE, MMMM d, yyyy 'at' h:mm:ss a z"
                )
                now.format(formatter)
            }
            else -> now.format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME)
        }
    }
}
```

### FetchBridge Enhancement

Add response headers to the fetch result so JS tools (especially `webfetch`) can inspect `Content-Type`.

```kotlin
// In FetchBridge.performFetch(), change the result construction:

// BEFORE:
val result = buildJsonObject {
    put("status", response.code)
    put("statusText", response.message)
    put("body", responseBody)
}

// AFTER:
val result = buildJsonObject {
    put("status", response.code)
    put("statusText", response.message)
    put("body", responseBody)
    put("headers", buildJsonObject {
        response.headers.names().forEach { name ->
            put(name.lowercase(), JsonPrimitive(response.header(name) ?: ""))
        }
    })
}
```

Update the JS wrapper in `FETCH_WRAPPER_JS`:

```javascript
async function fetch(url, options) {
    const optionsJson = options ? JSON.stringify(options) : "{}";
    const responseJson = await __fetchImpl(url, optionsJson);
    const raw = JSON.parse(responseJson);
    return {
        ok: raw.status >= 200 && raw.status < 300,
        status: raw.status,
        statusText: raw.statusText,
        headers: raw.headers || {},
        _body: raw.body,
        async text() { return this._body; },
        async json() { return JSON.parse(this._body); }
    };
}
```

This change is backward-compatible. Existing JS tools that don't use `headers` are unaffected.

### FsBridge Enhancement

Add `fs.appendFile()` to support `write_file`'s append mode.

```kotlin
// In FsBridge.inject(), add inside quickJs.define("fs"):

// fs.appendFile(path, content) -> void (returns null)
function("appendFile") { args: Array<Any?> ->
    val path = args.getOrNull(0)?.toString()
        ?: throw IllegalArgumentException("appendFile: path argument required")
    val content = args.getOrNull(1)?.toString() ?: ""
    appendFile(path, content)
    null
}

// Add private method:
private fun appendFile(path: String, content: String) {
    val canonical = validatePath(path)
    val file = File(canonical)
    file.parentFile?.mkdirs()
    file.appendText(content, Charsets.UTF_8)
}
```

### JsExecutionEngine Changes

Inject `LibraryBridge` and `TimeBridge` into the QuickJS context.

```kotlin
/**
 * MODIFIED: JsExecutionEngine now accepts a LibraryBridge parameter
 * and injects TimeBridge + LibraryBridge into each QuickJS context.
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient,
    private val libraryBridge: LibraryBridge       // NEW parameter
) {
    // ... existing companion object unchanged ...

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        jsSource: String?,                          // NEW: alternative to file path for asset-based tools
        toolName: String,
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            // Inject bridges
            ConsoleBridge.inject(this, toolName)
            FsBridge.inject(this)
            FetchBridge.inject(this, okHttpClient)
            TimeBridge.inject(this)                 // NEW
            libraryBridge.inject(this)              // NEW

            // Load JS source -- from file or from pre-loaded string (assets)
            val jsCode = jsSource ?: File(jsFilePath).readText()

            val paramsJson = anyToJsonElement(paramsWithEnv).toString()

            val wrapperCode = """
                ${FetchBridge.FETCH_WRAPPER_JS}
                ${libraryBridge.LIB_WRAPPER_JS}

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
     * Execute from a file path (user JS tools -- existing behavior).
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
                executeInQuickJs(jsFilePath, null, toolName, params, env)
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

    /**
     * Execute from pre-loaded source code (built-in JS tools from assets).
     * NEW method for asset-based tools.
     */
    suspend fun executeFromSource(
        jsSource: String,
        toolName: String,
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs("", jsSource, toolName, params, env)
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

    // ... existing helper methods unchanged ...
}
```

### JsTool Changes

Support both file-based and source-based execution.

```kotlin
/**
 * MODIFIED: JsTool now supports two source modes:
 * - File-based (jsFilePath set, jsSource null): user tools from file system
 * - Source-based (jsSource set, jsFilePath empty): built-in tools from assets
 */
class JsTool(
    override val definition: ToolDefinition,
    private val jsFilePath: String = "",
    private val jsSource: String? = null,           // NEW: pre-loaded source for asset tools
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return if (jsSource != null) {
            jsExecutionEngine.executeFromSource(
                jsSource = jsSource,
                toolName = definition.name,
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        } else {
            jsExecutionEngine.execute(
                jsFilePath = jsFilePath,
                toolName = definition.name,
                params = parameters,
                env = envVarStore.getAll(),
                timeoutSeconds = definition.timeoutSeconds
            )
        }
    }
}
```

### JsToolLoader Changes

Add `loadBuiltinTools()` for loading from assets. Change name conflict policy so user tools override built-in ones.

```kotlin
/**
 * MODIFIED: JsToolLoader now supports loading built-in JS tools from assets
 * and allows user tools to override built-in tools.
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
    }

    // ... existing LoadResult, ToolLoadError unchanged ...

    /**
     * NEW: Load built-in JS tools from assets/js/tools/.
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
                val metadata = parseAndValidateMetadata(jsonContent, baseName)

                tools.add(JsTool(
                    definition = metadata,
                    jsSource = jsSource,
                    jsExecutionEngine = jsExecutionEngine,
                    envVarStore = envVarStore
                ))
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

    // ... existing loadTools() for user tools unchanged ...

    /**
     * CHANGED: Register tools into ToolRegistry.
     * Now supports overriding existing tools (for user tools overriding built-in).
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

    // ... existing parseAndValidateMetadata(), getToolDirectories() unchanged ...
}
```

### ToolRegistry Enhancement

Add `unregister()` method to support user tool overrides.

```kotlin
// Add to ToolRegistry:

/**
 * Remove a tool by name. Used when a user tool overrides a built-in tool.
 */
fun unregister(name: String) {
    tools.remove(name)
}
```

### Built-in JS Tool Files

#### get_current_time.json

```json
{
  "name": "get_current_time",
  "description": "Get the current date and time",
  "parameters": {
    "properties": {
      "timezone": {
        "type": "string",
        "description": "Timezone identifier (e.g., 'America/New_York', 'Asia/Shanghai'). Defaults to device timezone."
      },
      "format": {
        "type": "string",
        "description": "Output format: 'iso8601' or 'human_readable'. Defaults to 'iso8601'.",
        "enum": ["iso8601", "human_readable"]
      }
    },
    "required": []
  },
  "timeoutSeconds": 5
}
```

#### get_current_time.js

```javascript
function execute(params) {
    var timezone = params.timezone || "";
    var format = params.format || "iso8601";
    return _time(timezone, format);
}
```

#### read_file.json

```json
{
  "name": "read_file",
  "description": "Read the contents of a file from local storage",
  "parameters": {
    "properties": {
      "path": {
        "type": "string",
        "description": "The absolute file path to read (e.g., '/storage/emulated/0/Documents/notes.txt')"
      },
      "encoding": {
        "type": "string",
        "description": "File encoding. Defaults to 'UTF-8'.",
        "default": "UTF-8"
      }
    },
    "required": ["path"]
  },
  "requiredPermissions": [],
  "timeoutSeconds": 10
}
```

#### read_file.js

```javascript
function execute(params) {
    var path = params.path;
    if (!path) return { error: "Parameter 'path' is required" };
    return fs.readFile(path);
}
```

#### write_file.json

```json
{
  "name": "write_file",
  "description": "Write contents to a file on local storage",
  "parameters": {
    "properties": {
      "path": {
        "type": "string",
        "description": "The absolute file path to write (e.g., '/storage/emulated/0/Documents/output.txt')"
      },
      "content": {
        "type": "string",
        "description": "The content to write to the file"
      },
      "mode": {
        "type": "string",
        "description": "Write mode: 'overwrite' (replace file) or 'append' (add to end). Defaults to 'overwrite'.",
        "enum": ["overwrite", "append"],
        "default": "overwrite"
      }
    },
    "required": ["path", "content"]
  },
  "requiredPermissions": [],
  "timeoutSeconds": 10
}
```

#### write_file.js

```javascript
function execute(params) {
    var path = params.path;
    var content = params.content;
    var mode = params.mode || "overwrite";

    if (!path) return { error: "Parameter 'path' is required" };
    if (content === undefined || content === null) return { error: "Parameter 'content' is required" };

    if (mode === "append") {
        fs.appendFile(path, content);
    } else {
        fs.writeFile(path, content);
    }

    var bytes = new TextEncoder().encode(content).length;
    return "Successfully wrote " + bytes + " bytes to " + path + " (mode: " + mode + ")";
}
```

#### http_request.json

```json
{
  "name": "http_request",
  "description": "Make an HTTP request to a URL",
  "parameters": {
    "properties": {
      "url": {
        "type": "string",
        "description": "The URL to request"
      },
      "method": {
        "type": "string",
        "description": "HTTP method: GET, POST, PUT, DELETE. Defaults to GET.",
        "enum": ["GET", "POST", "PUT", "DELETE"],
        "default": "GET"
      },
      "headers": {
        "type": "object",
        "description": "Key-value pairs of HTTP headers (optional)"
      },
      "body": {
        "type": "string",
        "description": "Request body for POST/PUT requests (optional)"
      }
    },
    "required": ["url"]
  },
  "timeoutSeconds": 30
}
```

#### http_request.js

```javascript
async function execute(params) {
    var url = params.url;
    if (!url) return { error: "Parameter 'url' is required" };

    var method = (params.method || "GET").toUpperCase();
    var options = { method: method };

    if (params.headers) {
        options.headers = params.headers;
    }
    if (params.body && (method === "POST" || method === "PUT")) {
        options.body = params.body;
    }

    var response = await fetch(url, options);
    var body = await response.text();

    var result = "HTTP " + response.status + " " + response.statusText + "\n";

    if (response.headers["content-type"]) {
        result += "Content-Type: " + response.headers["content-type"] + "\n";
    }
    if (response.headers["content-length"]) {
        result += "Content-Length: " + response.headers["content-length"] + "\n";
    }

    result += "\n" + body;
    return result;
}
```

#### webfetch.json

```json
{
  "name": "webfetch",
  "description": "Fetch a web page and return its content as Markdown",
  "parameters": {
    "properties": {
      "url": {
        "type": "string",
        "description": "The URL to fetch"
      }
    },
    "required": ["url"]
  },
  "timeoutSeconds": 30
}
```

#### webfetch.js

```javascript
async function execute(params) {
    var url = params.url;
    if (!url) return { error: "Parameter 'url' is required" };

    var response = await fetch(url);

    if (!response.ok) {
        return {
            error: "HTTP " + response.status + ": " + response.statusText,
            url: url
        };
    }

    var body = await response.text();
    var contentType = (response.headers["content-type"] || "").toLowerCase();

    // If not HTML, return raw body
    if (contentType.indexOf("text/html") === -1) {
        return body;
    }

    // Strip non-content elements before Turndown conversion
    var cleaned = body
        .replace(/<script[^>]*>[\s\S]*?<\/script>/gi, "")
        .replace(/<style[^>]*>[\s\S]*?<\/style>/gi, "")
        .replace(/<nav[^>]*>[\s\S]*?<\/nav>/gi, "")
        .replace(/<header[^>]*>[\s\S]*?<\/header>/gi, "")
        .replace(/<footer[^>]*>[\s\S]*?<\/footer>/gi, "")
        .replace(/<noscript[^>]*>[\s\S]*?<\/noscript>/gi, "");

    // Convert HTML to Markdown using Turndown
    var TurndownService = lib("turndown");
    var td = new TurndownService({
        headingStyle: "atx",
        codeBlockStyle: "fenced",
        bulletListMarker: "-"
    });

    // Remove empty links and image-only links to reduce noise
    td.addRule("removeEmptyLinks", {
        filter: function(node) {
            return node.nodeName === "A" && !node.textContent.trim();
        },
        replacement: function() { return ""; }
    });

    var markdown = td.turndown(cleaned);
    return markdown;
}
```

### ToolModule Changes

```kotlin
val toolModule = module {

    single { JsExecutionEngine(get(), get()) }  // OkHttpClient, LibraryBridge  [CHANGED]

    single { EnvironmentVariableStore(androidContext()) }

    single { LibraryBridge(androidContext()) }  // NEW

    single { JsToolLoader(androidContext(), get(), get()) }

    single { SkillFileParser() }
    single { SkillRegistry(androidContext(), get()).apply { initialize() } }
    single { LoadSkillTool(get()) }

    single {
        ToolRegistry().apply {
            // Only Kotlin built-in: LoadSkillTool
            try {
                register(get<LoadSkillTool>())
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register load_skill: ${e.message}")
            }

            // Built-in JS tools from assets (replaces Kotlin tool registration)
            val loader: JsToolLoader = get()
            try {
                val builtinResult = loader.loadBuiltinTools()
                loader.registerTools(this, builtinResult.loadedTools, allowOverride = false)
                if (builtinResult.loadedTools.isNotEmpty()) {
                    Log.i("ToolModule", "Loaded ${builtinResult.loadedTools.size} built-in JS tool(s)")
                }
                builtinResult.errors.forEach { error ->
                    Log.e("ToolModule", "Built-in JS tool error [${error.fileName}]: ${error.error}")
                }
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to load built-in JS tools: ${e.message}")
            }

            // User JS tools from file system (can override built-in)
            try {
                val userResult = loader.loadTools()
                val conflicts = loader.registerTools(this, userResult.loadedTools, allowOverride = true)

                val totalErrors = userResult.errors + conflicts
                if (userResult.loadedTools.isNotEmpty()) {
                    Log.i("ToolModule", "Loaded ${userResult.loadedTools.size} user JS tool(s)")
                }
                totalErrors.forEach { error ->
                    Log.w("ToolModule", "User JS tool load error [${error.fileName}]: ${error.error}")
                }
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to load user JS tools: ${e.message}")
            }
        }
    }

    single { PermissionChecker(androidContext()) }

    single { ToolExecutionEngine(get(), get()) }
}
```

### Deleted Kotlin Tool Classes

The following files are deleted entirely:

- `tool/builtin/GetCurrentTimeTool.kt`
- `tool/builtin/ReadFileTool.kt`
- `tool/builtin/WriteFileTool.kt`
- `tool/builtin/HttpRequestTool.kt`

Their corresponding test files are also deleted:

- `tool/builtin/GetCurrentTimeToolTest.kt`
- `tool/builtin/ReadFileToolTest.kt`
- `tool/builtin/WriteFileToolTest.kt`
- `tool/builtin/HttpRequestToolTest.kt`

These are replaced by `BuiltinJsToolMigrationTest.kt` which tests the JS equivalents.

## Data Flow

### Flow: webfetch Tool Execution

```
1. AI model sends tool call: webfetch(url="https://example.com/article")
2. ToolExecutionEngine looks up "webfetch" in ToolRegistry
3. Found: JsTool (built-in, source-based)
4. JsTool.execute() -> JsExecutionEngine.executeFromSource()
5. Fresh QuickJS context created
6. Bridges injected: console, fs, fetch, _time, lib()
7. webfetch.js source evaluated
8. execute(params) called in JS:
   a. fetch("https://example.com/article") -> FetchBridge -> OkHttpClient
   b. Response received with headers (content-type: text/html)
   c. HTML stripped of <script>, <style>, <nav>, <header>, <footer>
   d. lib("turndown") called -> LibraryBridge loads turndown.min.js from assets
   e. TurndownService instantiated, turndown(html) called
   f. Markdown string returned
9. QuickJS context closed
10. ToolResult.success(markdown) returned to ToolExecutionEngine
11. Result sent back to AI model
```

### Flow: lib() Loading

```
1. JS tool code calls: lib("turndown")
2. lib() JS wrapper calls __loadLibSource("turndown")
3. __loadLibSource -> LibraryBridge.loadLibrarySource("turndown")
4. Check sourceCache -> miss on first call
5. Try assets: context.assets.open("js/lib/turndown.min.js") -> found
6. Read source text, store in sourceCache
7. Return source to JS
8. lib() wrapper creates CommonJS module scope
9. Evaluates library source with (module, exports) wrapper
10. Returns module.exports (TurndownService constructor)
11. Caches in __libCache for subsequent calls in same execution
```

### Flow: User Tool Override

```
1. App startup: ToolModule initializes ToolRegistry
2. Step 1: register LoadSkillTool (Kotlin)
3. Step 2: loadBuiltinTools() -> assets/js/tools/
   - Registers: get_current_time, read_file, write_file, http_request, webfetch
   - allowOverride = false (no conflicts expected at this point)
4. Step 3: loadTools() -> /sdcard/OneClaw/tools/ + {internal}/tools/
   - User has a custom http_request.js that adds API key headers automatically
   - registerTools(allowOverride = true)
   - "http_request" already exists -> unregister built-in, register user version
   - Log: "User JS tool 'http_request' overrides built-in"
5. Result: http_request now uses the user's custom implementation
```

## Testing Strategy

### Unit Tests

#### LibraryBridgeTest

```kotlin
class LibraryBridgeTest {
    // Test lib("turndown") loads successfully from assets
    // Test lib("nonexistent") throws with clear error message
    // Test lib() caches source across calls
    // Test library name validation rejects "../etc/passwd"
    // Test library name validation rejects empty string
    // Test internal storage fallback when asset not found
}
```

#### TimeBridgeTest

```kotlin
class TimeBridgeTest {
    // Test _time() returns ISO 8601 with device timezone
    // Test _time("Asia/Shanghai") returns time in correct timezone
    // Test _time("invalid_tz") throws with clear error
    // Test _time("", "human_readable") returns formatted string
}
```

#### BuiltinJsToolMigrationTest

Verifies behavioral equivalence between old Kotlin tools and new JS tools:

```kotlin
class BuiltinJsToolMigrationTest {
    // -- get_current_time --
    // Test: returns ISO 8601 string for default params
    // Test: respects timezone parameter
    // Test: respects format="human_readable"
    // Test: invalid timezone returns error

    // -- read_file --
    // Test: reads file content correctly
    // Test: returns error for non-existent path
    // Test: returns error for directory path
    // Test: returns error for restricted path (/data/data/)
    // Test: returns error for files > 1MB

    // -- write_file --
    // Test: writes content to new file
    // Test: overwrites existing file (mode=overwrite)
    // Test: appends to existing file (mode=append)
    // Test: creates parent directories
    // Test: returns error for restricted path

    // -- http_request --
    // Test: GET request returns status + body
    // Test: POST request with body
    // Test: custom headers are sent
    // Test: invalid URL returns error
    // Test: response format matches Kotlin version (HTTP NNN status\nheaders\n\nbody)
}
```

#### WebfetchToolTest

```kotlin
class WebfetchToolTest {
    // Test: HTML page returns clean Markdown
    // Test: <script>, <style>, <nav> are stripped before conversion
    // Test: non-HTML content (JSON, plain text) returned as-is
    // Test: HTTP error returns error object
    // Test: empty HTML body returns empty Markdown
    // Test: headings, links, code blocks are properly converted
}
```

#### JsToolLoaderTest Updates

```kotlin
// Add to existing JsToolLoaderTest:
// Test: loadBuiltinTools() loads all 5 tools from assets
// Test: user tool with same name overrides built-in when allowOverride=true
// Test: user tool with same name skipped when allowOverride=false
// Test: built-in tool with missing .js returns error
```

### Integration Test

A single integration test that verifies the full tool pipeline end-to-end:

```kotlin
class JsToolMigrationIntegrationTest {
    // Set up real ToolModule with all registrations
    // Verify all 5 built-in JS tools + LoadSkillTool are registered
    // Execute each built-in JS tool and verify results
    // Verify user tool override works
}
```

## Implementation Plan

Ordered implementation steps:

### Phase 1: New Bridges (no behavior change)

1. **Add `TimeBridge`**: New file `tool/js/bridge/TimeBridge.kt` + `TimeBridgeTest.kt`
2. **Add `LibraryBridge`**: New file `tool/js/bridge/LibraryBridge.kt` + `LibraryBridgeTest.kt`
3. **Enhance `FetchBridge`**: Add response headers to result object
4. **Enhance `FsBridge`**: Add `fs.appendFile()`
5. **Update `JsExecutionEngine`**: Accept `LibraryBridge`, inject `TimeBridge` + `LibraryBridge` into context
6. **Bundle `turndown.min.js`**: Download and place in `assets/js/lib/`

### Phase 2: Built-in JS Tools

7. **Create asset tool files**: All 5 tools (`.js` + `.json`) in `assets/js/tools/`
8. **Update `JsTool`**: Support `jsSource` parameter for asset-based tools
9. **Update `JsToolLoader`**: Add `loadBuiltinTools()`, add `allowOverride` parameter to `registerTools()`, add `unregister()` to `ToolRegistry`
10. **Write `BuiltinJsToolMigrationTest`** and **`WebfetchToolTest`**

### Phase 3: Switchover

11. **Update `ToolModule`**: Replace Kotlin tool registration with built-in JS tool loading
12. **Delete Kotlin tools**: Remove 4 Kotlin tool classes + their test files
13. **Run full test suite**: `./gradlew test` -- all must pass

### Phase 4: Verification

14. **Build**: `./gradlew assembleDebug` -- verify APK builds
15. **Layer 1A**: `./gradlew test` -- all unit tests pass
16. **Manual verification**: Install on device, test each tool via chat

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
