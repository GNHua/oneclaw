# RFC-015: JavaScript 工具迁移与库系统

## 文档信息
- **RFC ID**: RFC-015
- **关联 PRD**: [FEAT-015 (JS Tool Migration & Library System)](../../prd/features/FEAT-015-js-tool-migration.md)
- **关联架构**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-012 (JavaScript Tool Engine)](RFC-012-js-tool-engine.md)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

RFC-012 引入了 JavaScript 工具引擎：一个带有原生桥接（`fetch()`、`fs`、`console`）的 QuickJS 运行时，支持用户自定义 JS 工具。然而，四个原始内置工具（`get_current_time`、`read_file`、`write_file`、`http_request`）仍作为 Kotlin 类存留在 `tool/builtin/` 中。这造成了两条并行的工具开发路径，也使内置工具无法受益于 JS 库生态系统。

RFC-015 将所有内置 Kotlin 工具迁移为由相同原生桥接支持的 JavaScript 实现，新增共享 JS 库加载系统（`lib()`），将 Turndown 作为第一个共享库打包，并引入一个新的 `webfetch` 工具，用于将 HTML 页面转换为 Markdown。

完成本 RFC 后，Kotlin `tool/builtin/` 包中仅保留 `LoadSkillTool`（因其需要直接访问 `SkillRegistry` —— 这是一个仅 Kotlin 可用的依赖项）。所有其他工具均为 JS 文件。

### 目标

1. 实现 `LibraryBridge` —— 在 QuickJS 中注入 `lib()` 函数，用于从 assets 加载共享 JS 库
2. 将 Turndown（约 20KB 压缩版）作为第一个共享库打包
3. 新增 `TimeBridge`，支持时区感知的时间格式化（`get_current_time` 所需）
4. 增强 `FetchBridge`，在 fetch 结果中返回响应头（`webfetch` 所需）
5. 增强 `FsBridge`，支持 `fs.appendFile()`（`write_file` 追加模式所需）
6. 扩展 `JsToolLoader`，支持从 `assets/js/tools/` 加载内置 JS 工具
7. 将 `get_current_time`、`read_file`、`write_file`、`http_request` 从 Kotlin 迁移至 JS
8. 新增内置 JS 工具 `webfetch`
9. 删除 Kotlin 工具类：`GetCurrentTimeTool.kt`、`ReadFileTool.kt`、`WriteFileTool.kt`、`HttpRequestTool.kt`
10. 更新 `ToolModule`，改为从 assets 加载内置 JS 工具，而非实例化 Kotlin 类

### 非目标

- 应用内库管理器 UI（V1 仅将库作为 assets 打包）
- npm 或 ES 模块导入系统
- 将 `LoadSkillTool` 迁移为 JS（其依赖 Kotlin `SkillRegistry`）
- `webfetch` 中的 HTML 截断或 token 预算感知
- `webfetch` 中的响应缓存

## 技术设计

### 架构概览

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

### 核心组件

**新增：**
1. `LibraryBridge` —— 向 QuickJS 注入 `lib()` 函数，用于加载共享 JS 库
2. `TimeBridge` —— 注入 `_time()` 函数，支持时区感知的时间格式化
3. 内置 JS 工具文件（`assets/js/tools/*.js` + `*.json`）
4. 打包的库文件（`assets/js/lib/turndown.min.js`）

**修改：**
5. `FetchBridge` —— 在 fetch 结果中加入响应头
6. `FsBridge` —— 新增 `fs.appendFile()`
7. `JsExecutionEngine` —— 注入 `LibraryBridge` 和 `TimeBridge`
8. `JsToolLoader` —— 新增 `loadBuiltinTools()`，修改名称冲突策略
9. `ToolModule` —— 将 Kotlin 工具注册替换为从 assets 加载内置 JS 工具

**删除：**
10. `GetCurrentTimeTool.kt`、`ReadFileTool.kt`、`WriteFileTool.kt`、`HttpRequestTool.kt`

## 详细设计

### 目录结构（新增及变更文件）

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

### FetchBridge 增强

在 fetch 结果中加入响应头，使 JS 工具（尤其是 `webfetch`）能够检查 `Content-Type`。

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

更新 `FETCH_WRAPPER_JS` 中的 JS 包装代码：

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

此变更向后兼容。不使用 `headers` 的现有 JS 工具不受影响。

### FsBridge 增强

新增 `fs.appendFile()`，以支持 `write_file` 的追加模式。

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

### JsExecutionEngine 变更

将 `LibraryBridge` 和 `TimeBridge` 注入 QuickJS 上下文。

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

### JsTool 变更

同时支持基于文件路径和基于源代码的两种执行方式。

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

### JsToolLoader 变更

新增 `loadBuiltinTools()` 以支持从 assets 加载。修改名称冲突策略，允许用户工具覆盖内置工具。

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

### ToolRegistry 增强

新增 `unregister()` 方法，支持用户工具覆盖内置工具。

```kotlin
// Add to ToolRegistry:

/**
 * Remove a tool by name. Used when a user tool overrides a built-in tool.
 */
fun unregister(name: String) {
    tools.remove(name)
}
```

### 内置 JS 工具文件

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
  "description": "Read the contents of a file from app-private storage",
  "parameters": {
    "properties": {
      "path": {
        "type": "string",
        "description": "The file path to read (confined to app-private storage)"
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
  "description": "Write contents to a file in app-private storage",
  "parameters": {
    "properties": {
      "path": {
        "type": "string",
        "description": "The file path to write (confined to app-private storage)"
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

### ToolModule 变更

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

### 删除的 Kotlin 工具类

以下文件将被完全删除：

- `tool/builtin/GetCurrentTimeTool.kt`
- `tool/builtin/ReadFileTool.kt`
- `tool/builtin/WriteFileTool.kt`
- `tool/builtin/HttpRequestTool.kt`

对应的测试文件也一并删除：

- `tool/builtin/GetCurrentTimeToolTest.kt`
- `tool/builtin/ReadFileToolTest.kt`
- `tool/builtin/WriteFileToolTest.kt`
- `tool/builtin/HttpRequestToolTest.kt`

这些文件由 `BuiltinJsToolMigrationTest.kt` 替代，后者负责测试对应的 JS 实现。

## 数据流

### 流程：webfetch 工具执行

```
1. AI 模型发出工具调用：webfetch(url="https://example.com/article")
2. ToolExecutionEngine 在 ToolRegistry 中查找 "webfetch"
3. 找到：JsTool（内置，基于源代码）
4. JsTool.execute() -> JsExecutionEngine.executeFromSource()
5. 创建新的 QuickJS 上下文
6. 注入桥接：console、fs、fetch、_time、lib()
7. 评估 webfetch.js 源代码
8. 在 JS 中调用 execute(params)：
   a. fetch("https://example.com/article") -> FetchBridge -> OkHttpClient
   b. 收到响应及响应头（content-type: text/html）
   c. HTML 中剔除 <script>、<style>、<nav>、<header>、<footer>
   d. lib("turndown") 被调用 -> LibraryBridge 从 assets 加载 turndown.min.js
   e. 实例化 TurndownService，调用 turndown(html)
   f. 返回 Markdown 字符串
9. QuickJS 上下文关闭
10. ToolResult.success(markdown) 返回给 ToolExecutionEngine
11. 结果回传给 AI 模型
```

### 流程：lib() 加载

```
1. JS 工具代码调用：lib("turndown")
2. lib() JS 包装函数调用 __loadLibSource("turndown")
3. __loadLibSource -> LibraryBridge.loadLibrarySource("turndown")
4. 检查 sourceCache -> 首次调用未命中
5. 尝试 assets：context.assets.open("js/lib/turndown.min.js") -> 找到
6. 读取源码文本，存入 sourceCache
7. 将源码返回给 JS
8. lib() 包装函数创建 CommonJS 模块作用域
9. 使用 (module, exports) 包装器评估库源码
10. 返回 module.exports（TurndownService 构造函数）
11. 缓存到 __libCache，供同次执行中的后续调用使用
```

### 流程：用户工具覆盖

```
1. 应用启动：ToolModule 初始化 ToolRegistry
2. 第一步：注册 LoadSkillTool（Kotlin）
3. 第二步：loadBuiltinTools() -> assets/js/tools/
   - 注册：get_current_time、read_file、write_file、http_request、webfetch
   - allowOverride = false（此时不预期存在冲突）
4. 第三步：loadTools() -> /sdcard/OneClaw/tools/ + {internal}/tools/
   - 用户有一个自定义的 http_request.js，可自动附加 API 密钥头
   - registerTools(allowOverride = true)
   - "http_request" 已存在 -> 注销内置工具，注册用户版本
   - 日志："User JS tool 'http_request' overrides built-in"
5. 结果：http_request 现在使用用户的自定义实现
```

## 测试策略

### 单元测试

#### LibraryBridgeTest

```kotlin
class LibraryBridgeTest {
    // 测试 lib("turndown") 能从 assets 成功加载
    // 测试 lib("nonexistent") 抛出包含清晰信息的错误
    // 测试 lib() 对多次调用能正确缓存源码
    // 测试库名称校验拒绝 "../etc/passwd"
    // 测试库名称校验拒绝空字符串
    // 测试当 assets 中找不到时回退到内部存储
}
```

#### TimeBridgeTest

```kotlin
class TimeBridgeTest {
    // 测试 _time() 使用设备时区返回 ISO 8601 格式
    // 测试 _time("Asia/Shanghai") 返回正确时区的时间
    // 测试 _time("invalid_tz") 抛出包含清晰信息的错误
    // 测试 _time("", "human_readable") 返回格式化字符串
}
```

#### BuiltinJsToolMigrationTest

验证旧 Kotlin 工具与新 JS 工具之间的行为等价性：

```kotlin
class BuiltinJsToolMigrationTest {
    // -- get_current_time --
    // 测试：默认参数下返回 ISO 8601 字符串
    // 测试：正确遵守 timezone 参数
    // 测试：正确遵守 format="human_readable"
    // 测试：无效时区返回错误

    // -- read_file --
    // 测试：正确读取文件内容
    // 测试：路径不存在时返回错误
    // 测试：路径为目录时返回错误
    // 测试：受限路径（/data/data/）时返回错误
    // 测试：文件超过 1MB 时返回错误

    // -- write_file --
    // 测试：向新文件写入内容
    // 测试：覆盖已有文件（mode=overwrite）
    // 测试：追加到已有文件（mode=append）
    // 测试：自动创建父级目录
    // 测试：受限路径时返回错误

    // -- http_request --
    // 测试：GET 请求返回状态码和响应体
    // 测试：带 body 的 POST 请求
    // 测试：发送自定义请求头
    // 测试：无效 URL 返回错误
    // 测试：响应格式与 Kotlin 版本一致（HTTP NNN status\nheaders\n\nbody）
}
```

#### WebfetchToolTest

```kotlin
class WebfetchToolTest {
    // 测试：HTML 页面返回干净的 Markdown
    // 测试：转换前剔除 <script>、<style>、<nav>
    // 测试：非 HTML 内容（JSON、纯文本）原样返回
    // 测试：HTTP 错误返回错误对象
    // 测试：空 HTML body 返回空 Markdown
    // 测试：标题、链接、代码块被正确转换
}
```

#### JsToolLoaderTest 更新

```kotlin
// Add to existing JsToolLoaderTest:
// 测试：loadBuiltinTools() 从 assets 加载全部 5 个工具
// 测试：allowOverride=true 时同名用户工具覆盖内置工具
// 测试：allowOverride=false 时同名用户工具被跳过
// 测试：内置工具缺少对应 .js 文件时返回错误
```

### 集成测试

一个集成测试，用于端对端验证完整工具管道：

```kotlin
class JsToolMigrationIntegrationTest {
    // 使用所有注册配置初始化真实的 ToolModule
    // 验证全部 5 个内置 JS 工具 + LoadSkillTool 均已注册
    // 执行每个内置 JS 工具并验证结果
    // 验证用户工具覆盖功能正常
}
```

## 实施计划

按顺序执行的实施步骤：

### 阶段一：新增桥接（不改变现有行为）

1. **新增 `TimeBridge`**：新建 `tool/js/bridge/TimeBridge.kt` + `TimeBridgeTest.kt`
2. **新增 `LibraryBridge`**：新建 `tool/js/bridge/LibraryBridge.kt` + `LibraryBridgeTest.kt`
3. **增强 `FetchBridge`**：在结果对象中加入响应头
4. **增强 `FsBridge`**：新增 `fs.appendFile()`
5. **更新 `JsExecutionEngine`**：接受 `LibraryBridge` 参数，向上下文注入 `TimeBridge` + `LibraryBridge`
6. **打包 `turndown.min.js`**：下载并放置到 `assets/js/lib/`

### 阶段二：内置 JS 工具

7. **创建 assets 工具文件**：全部 5 个工具的 `.js` + `.json` 文件放至 `assets/js/tools/`
8. **更新 `JsTool`**：支持 `jsSource` 参数，用于基于 assets 的工具
9. **更新 `JsToolLoader`**：新增 `loadBuiltinTools()`，为 `registerTools()` 添加 `allowOverride` 参数，为 `ToolRegistry` 添加 `unregister()`
10. **编写 `BuiltinJsToolMigrationTest`** 和 **`WebfetchToolTest`**

### 阶段三：切换

11. **更新 `ToolModule`**：将 Kotlin 工具注册替换为内置 JS 工具加载
12. **删除 Kotlin 工具**：移除 4 个 Kotlin 工具类及其测试文件
13. **运行完整测试套件**：`./gradlew test` —— 所有测试必须通过

### 阶段四：验证

14. **构建**：`./gradlew assembleDebug` —— 验证 APK 能正常构建
15. **Layer 1A**：`./gradlew test` —— 所有单元测试通过
16. **手动验证**：安装到真机，通过聊天界面测试每个工具

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | 初始版本 | - |
