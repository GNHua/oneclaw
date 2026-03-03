# RFC-012: JavaScript 工具引擎

## 文档信息
- **RFC编号**: RFC-012
- **关联PRD**: [FEAT-012 (JavaScript 工具引擎)](../../prd/features/FEAT-012-js-tool-engine-zh.md)
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **关联RFC**: [RFC-004 (工具系统)](RFC-004-tool-system-zh.md)
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景

现有的工具系统（RFC-004）提供了一套简洁、可扩展的 `Tool` 接口以及内置的 Kotlin 工具（`get_current_time`、`read_file`、`write_file`、`http_request`）。然而，添加新工具目前需要编写 Kotlin 代码、重新编译应用并发布新版本，这为扩展 Agent 能力设置了很高的门槛。

JavaScript 工具引擎允许用户将自定义工具定义为 JavaScript 文件，通过嵌入式 QuickJS 运行时执行。JS 工具无缝集成到现有的 ToolRegistry 和 ToolExecutionEngine 中 -- 从 AI 模型的角度来看，JS 工具与内置 Kotlin 工具没有区别。主要的工具创建工作流是让 AI 自己来编写工具（使用 `write_file`），使工具创建变得像描述你想要什么一样简单。

### 目标
1. 在 Android 应用中嵌入 QuickJS JavaScript 运行时
2. 实现 `JsTool` -- 一个加载和执行 JavaScript 文件的 `Tool` 实现
3. 实现 `JsToolLoader` 从文件系统发现和加载 JS 工具
4. 实现宿主桥接 API（`fetch()`、`fs`、`console`）注入到 QuickJS 上下文
5. 实现环境变量注入（`params._env`）用于 API 密钥
6. 将 JS 工具集成到现有的 ToolRegistry、ToolExecutionEngine 和 Agent 工具选择中
7. 添加设置界面用于 JS 工具管理和环境变量
8. 对 JS 工具执行强制超时、内存限制和路径限制

### 非目标
- 应用内 JavaScript 代码编辑器
- npm / Node.js 模块系统（`require()`、从 npm 包 `import`）
- TypeScript 支持
- JS 调试器或单步执行
- 工具市场或分发系统
- 沙箱进程隔离（JS 通过 QuickJS 在进程内运行）
- 通过文件系统监听器热重载（V1 使用手动"重新加载工具"操作）

## 技术方案

### 整体设计

```
┌──────────────────────────────────────────────────────────────┐
│                     聊天层 (RFC-001)                          │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  模型发出的工具调用请求                                  │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   工具执行引擎 (RFC-004)                       │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ get_current_time │ │    read_file     │  Kotlin     │  │
│  │  │    (Kotlin)      │ │    (Kotlin)      │  内置工具    │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │   write_file     │ │  http_request    │             │  │
│  │  │    (Kotlin)      │ │    (Kotlin)      │             │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  │  ┌──────────────────┐ ┌──────────────────┐             │  │
│  │  │ weather_lookup   │ │   csv_parser     │  JS 工具    │  │
│  │  │    (JsTool)      │ │    (JsTool)      │  (动态)     │  │
│  │  └──────────────────┘ └──────────────────┘             │  │
│  └────────────────────────────────────────────────────────┘  │
│       │                                                      │
│       │  对于 JsTool.execute():                               │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                  JsExecutionEngine                      │  │
│  │  ┌──────────┐  ┌──────────────────────────────────┐    │  │
│  │  │ QuickJS  │  │       桥接函数                     │    │  │
│  │  │ 运行时    │  │  ┌────────┐ ┌────┐ ┌─────────┐   │    │  │
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
│  │  扫描目录中的 .js + .json 文件对                          │  │
│  │  解析元数据，创建 JsTool 实例                              │  │
│  │  注册到 ToolRegistry                                     │  │
│  └────────────────────────────────────────────────────────┘  │
│       │                      │                               │
│       v                      v                               │
│  /sdcard/OneClaw/   {app_internal}/                    │
│        tools/                 tools/                         │
│  weather_lookup.js        temperature_convert.js             │
│  weather_lookup.json      temperature_convert.json           │
└──────────────────────────────────────────────────────────────┘
```

### 核心组件

1. **JsTool（实现 Tool 接口的类）**
   - 职责：将 JS 工具文件对（`.js` + `.json`）封装为标准的 `Tool` 实现
   - 接口：`Tool.definition` 来自解析的 `.json`，`Tool.execute()` 委托给 QuickJS
   - 依赖：`JsExecutionEngine`、工具文件路径

2. **JsToolLoader**
   - 职责：扫描工具目录，验证文件对，解析元数据，创建 `JsTool` 实例，注册到 `ToolRegistry`
   - 接口：`fun loadTools(registry: ToolRegistry): JsToolLoadResult`
   - 依赖：文件系统、`JsExecutionEngine`

3. **JsExecutionEngine**
   - 职责：管理 QuickJS 运行时生命周期，注入桥接函数，在超时和内存限制下执行 JS 代码
   - 接口：`suspend fun execute(jsFilePath: String, params: Map<String, Any?>, env: Map<String, String>, timeoutSeconds: Int): ToolResult`
   - 依赖：QuickJS 库、`OkHttpClient`、Android `Context`

4. **桥接函数**（注入到 QuickJS 上下文）
   - `fetch()`：通过 OkHttpClient 的 HTTP 请求
   - `fs.readFile()`、`fs.writeFile()`、`fs.exists()`：带路径限制的文件 I/O
   - `console.log/warn/error`：输出到 Logcat

5. **EnvironmentVariableStore**
   - 职责：存储和检索加密的键值对，用于 JS 工具环境变量
   - 接口：`getAll(): Map<String, String>`、`set(key, value)`、`delete(key)`、`getKeys(): List<String>`
   - 存储：EncryptedSharedPreferences（与 API 密钥相同的机制）

### 技术选型：QuickJS 库

**选定：`dokar3/quickjs-kt`**（版本 1.0.3）

| 标准 | 详情 |
|------|------|
| Maven 坐标 | `io.github.dokar3:quickjs-kt-android:1.0.3` |
| API 风格 | Kotlin 优先，基于 DSL，suspend 函数 |
| 异步/Promise | 深度协程集成，支持 `async` 函数 |
| 宿主函数注入 | `function<>()` DSL，`define("obj") { property(...) }` 用于对象 |
| 内存控制 | `memoryLimit`、`maxStackSize`、`gc()` |
| ABI 支持 | arm64-v8a、armeabi-v7a、x86、x86_64 |
| 维护状态 | 活跃（1.0.3 发布于 2025 年 2 月），稳定的 1.x 系列 |
| Android 15 | 从 1.0.1 开始支持 16KB 页大小 |

**超时处理说明**：`quickjs-kt` 未暴露 QuickJS 原生的 `JS_SetInterruptHandler`。超时强制通过协程级别的 `withTimeout()` 实现。对于 JS 中的 CPU 密集型无限循环，协程超时会取消 scope，QuickJS 上下文被关闭并丢弃。这对我们的用例足够了，因为每次执行使用全新的上下文。

**替代方案**：`HarlonWang/quickjs-wrapper` -- Java 风格 API，Kotlin 不够惯用，同样没有中断处理器。因为 Kotlin 优先的 API 而被放弃。

## 详细设计

### 目录结构（新文件）

```
app/src/main/kotlin/com/oneclaw/shadow/
├── tool/
│   ├── engine/             # 已有
│   │   ├── Tool.kt
│   │   ├── ToolRegistry.kt
│   │   └── ...
│   ├── builtin/            # 已有
│   │   ├── GetCurrentTimeTool.kt
│   │   └── ...
│   └── js/                 # 新增
│       ├── JsTool.kt
│       ├── JsToolLoader.kt
│       ├── JsExecutionEngine.kt
│       ├── bridge/
│       │   ├── FetchBridge.kt
│       │   ├── FsBridge.kt
│       │   └── ConsoleBridge.kt
│       └── EnvironmentVariableStore.kt
├── ui/features/settings/   # 已有，修改
│   ├── SettingsScreen.kt   # 添加 JS 工具区域
│   └── SettingsViewModel.kt
│   ├── JsToolsSection.kt   # 新增
│   └── EnvVarsSection.kt   # 新增
└── di/
    └── ToolModule.kt       # 修改
```

### JsTool

```kotlin
/**
 * 位于: tool/js/JsTool.kt
 *
 * 通过 QuickJS 执行 JavaScript 文件的 Tool 实现。
 * 从 ToolRegistry 的角度来看，与内置 Kotlin 工具没有区别。
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
 * 位于: tool/js/JsToolLoader.kt
 *
 * 扫描工具目录中的 .json + .js 文件对，验证它们，
 * 并创建准备注册的 JsTool 实例。
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
     * 扫描工具目录并返回所有有效的 JsTool 实例。
     * 无效的工具被跳过并记录错误。
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
                        "缺少对应的 .js 文件: ${jsFile.name}"
                    ))
                    continue
                }

                try {
                    val tool = loadSingleTool(jsonFile, jsFile)
                    tools.add(tool)
                } catch (e: Exception) {
                    errors.add(ToolLoadError(
                        jsonFile.name,
                        "加载失败: ${e.message}"
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
     * 解析 JSON 元数据并根据 ToolDefinition schema 验证。
     */
    private fun parseAndValidateMetadata(jsonContent: String, expectedName: String): ToolDefinition {
        val json = Json.parseToJsonElement(jsonContent).jsonObject

        val name = json["name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少必填字段: 'name'")

        if (name != expectedName) {
            throw IllegalArgumentException(
                "工具名称 '$name' 与文件名 '$expectedName' 不匹配"
            )
        }

        if (!TOOL_NAME_REGEX.matches(name)) {
            throw IllegalArgumentException(
                "工具名称 '$name' 必须为 snake_case（小写字母、数字、下划线）"
            )
        }

        val description = json["description"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("缺少必填字段: 'description'")

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
        element is JsonPrimitive -> element.content
        else -> element.toString()
    }

    private fun getToolDirectories(): List<File> {
        val dirs = mutableListOf<File>()

        // 外部存储: /sdcard/OneClaw/tools/
        val externalDir = File(
            Environment.getExternalStorageDirectory(),
            EXTERNAL_TOOLS_DIR
        )
        dirs.add(externalDir)

        // 应用内部: {app_files}/tools/
        val internalDir = File(context.filesDir, "tools")
        dirs.add(internalDir)

        return dirs
    }

    /**
     * 将加载的 JS 工具注册到 ToolRegistry。
     * 跳过与已注册名称冲突的工具（内置工具优先）。
     */
    fun registerTools(registry: ToolRegistry, tools: List<JsTool>): List<ToolLoadError> {
        val conflicts = mutableListOf<ToolLoadError>()

        for (tool in tools) {
            if (registry.hasTool(tool.definition.name)) {
                conflicts.add(ToolLoadError(
                    "${tool.definition.name}.json",
                    "与已有工具 '${tool.definition.name}' 名称冲突（已跳过）"
                ))
                Log.w(TAG, "JS 工具 '${tool.definition.name}' 已跳过: 名称与已有工具冲突")
                continue
            }
            registry.register(tool)
            Log.i(TAG, "已注册 JS 工具: ${tool.definition.name}")
        }

        return conflicts
    }
}
```

### JsExecutionEngine

```kotlin
/**
 * 位于: tool/js/JsExecutionEngine.kt
 *
 * 管理 QuickJS 运行时生命周期并执行 JS 工具代码。
 * 每次执行使用全新的 QuickJS 上下文以确保隔离。
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
     * 执行 JS 工具文件。
     *
     * 创建全新的 QuickJS 上下文，注入桥接函数，
     * 加载 JS 文件，调用 execute(params)，并返回结果。
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
            ToolResult.error("timeout", "JS 工具 '$toolName' 执行超时（${timeoutSeconds}秒）")
        } catch (e: CancellationException) {
            throw e  // 传播协程取消
        } catch (e: Exception) {
            Log.e(TAG, "JS 工具 '$toolName' 执行失败", e)
            ToolResult.error("execution_error", "JS 工具 '$toolName' 失败: ${e.message}")
        }
    }

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        toolName: String,
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        // 将 _env 合并到 params
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            // 配置内存限制
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            // 注入桥接: console
            ConsoleBridge.inject(this, toolName)

            // 注入桥接: fs
            FsBridge.inject(this)

            // 注入桥接: fetch
            FetchBridge.inject(this, okHttpClient)

            // 加载并执行 JS 文件
            val jsCode = File(jsFilePath).readText()

            // 构建调用 execute() 并捕获结果的包装代码。
            // 我们将 params 序列化为 JSON，在 JS 中解析，调用 execute()，
            // 并将结果序列化回来。
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
     * 将任意 Kotlin 值转换为 JsonElement 用于序列化。
     * 与 provider adapter 中使用的相同辅助函数 (RFC-004)。
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
     * 转义字符串以安全嵌入为 JS 字符串字面量。
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

### 桥接函数

#### FetchBridge

```kotlin
/**
 * 位于: tool/js/bridge/FetchBridge.kt
 *
 * 将类 fetch() API 注入到 QuickJS 上下文中。
 * 将 HTTP 请求委托给 OkHttpClient。
 */
object FetchBridge {

    private const val MAX_RESPONSE_SIZE = 100 * 1024  // 100KB，与 HttpRequestTool 相同

    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // fetch(url, options?) -> Promise<Response>
        // Response = { ok, status, statusText, text(), json() }
        //
        // 实现方式:
        // 注册一个 Kotlin async 函数来执行 HTTP 请求
        // 并返回 JSON 序列化的响应对象。
        // JS 端将其包装为类 Response 对象。

        quickJs.asyncFunction<String, String, String>("__fetchImpl") { url, optionsJson ->
            performFetch(okHttpClient, url, optionsJson)
        }

        // JS 端包装器提供 fetch() API
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
            ?: throw IllegalArgumentException("无效 URL: $url")

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
            else -> throw IllegalArgumentException("不支持的 HTTP 方法: $method")
        }

        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(requestBuilder.build()).execute()
        }

        val responseBody = response.body?.let { responseBody ->
            val bytes = responseBody.bytes()
            if (bytes.size > MAX_RESPONSE_SIZE) {
                val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                "$truncated\n\n(响应已截断。显示 ${bytes.size / 1024}KB 中的前 ${MAX_RESPONSE_SIZE / 1024}KB。)"
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } ?: ""

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
 * 位于: tool/js/bridge/FsBridge.kt
 *
 * 将文件系统函数注入到 QuickJS 上下文中。
 * 应用与 ReadFileTool / WriteFileTool 相同的路径限制。
 */
object FsBridge {

    private const val MAX_FILE_SIZE = 1024 * 1024  // 1MB，与 ReadFileTool 相同

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

            // fs.writeFile(path, content) -> void（返回空字符串）
            function<String, String, String>("writeFile") { path, content ->
                writeFile(path, content)
                ""
            }

            // fs.exists(path) -> boolean（返回 "true" 或 "false" 字符串，在 JS 中转换）
            function<String, String>("exists") { path ->
                fileExists(path).toString()
            }
        }

        // JS 包装器将 exists() 的返回值转换为 boolean
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
                throw SecurityException("访问被拒绝: 路径受限 ($restricted)")
            }
        }
        return canonicalPath
    }

    private fun readFile(path: String): String {
        val canonical = validatePath(path)
        val file = File(canonical)

        if (!file.exists()) throw IllegalArgumentException("文件未找到: $path")
        if (!file.isFile) throw IllegalArgumentException("路径是目录: $path")
        if (file.length() > MAX_FILE_SIZE) {
            throw IllegalArgumentException(
                "文件过大（${file.length()} 字节）。最大: $MAX_FILE_SIZE 字节。"
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
            false  // 受限路径报告为不存在
        }
    }
}
```

#### ConsoleBridge

```kotlin
/**
 * 位于: tool/js/bridge/ConsoleBridge.kt
 *
 * 将 console.log/warn/error 注入到 QuickJS 上下文中。
 * 输出到 Android Logcat。
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
 * 位于: tool/js/EnvironmentVariableStore.kt
 *
 * 存储加密的键值对用于 JS 工具环境变量。
 * 使用 EncryptedSharedPreferences，与 API 密钥存储相同的机制。
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

### Koin 依赖注入更新

```kotlin
// 更新的 ToolModule.kt
val toolModule = module {

    // 已有: Tool Registry - 单例，启动时注册所有工具
    single {
        ToolRegistry().apply {
            // 内置 Kotlin 工具
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient

            // JS 工具: 从文件系统加载
            val loader: JsToolLoader = get()
            val loadResult = loader.loadTools()
            val conflicts = loader.registerTools(this, loadResult.loadedTools)

            // 记录结果
            val totalErrors = loadResult.errors + conflicts
            if (loadResult.loadedTools.isNotEmpty()) {
                Log.i("ToolModule", "已加载 ${loadResult.loadedTools.size} 个 JS 工具")
            }
            totalErrors.forEach { error ->
                Log.w("ToolModule", "JS 工具加载错误 [${error.fileName}]: ${error.error}")
            }
        }
    }

    // 已有: Permission Checker
    single { PermissionChecker(androidContext()) }

    // 已有: Tool Execution Engine
    single { ToolExecutionEngine(get(), get()) }

    // 新增: JS Execution Engine
    single { JsExecutionEngine(get()) }  // get() = OkHttpClient

    // 新增: Environment Variable Store
    single { EnvironmentVariableStore(androidContext()) }

    // 新增: JS Tool Loader
    single { JsToolLoader(androidContext(), get(), get()) }  // JsExecutionEngine, EnvironmentVariableStore
}
```

### 设置界面

设置界面的 UI 代码与英文版 RFC 中的完全相同（Compose 代码是语言无关的），包含：

- **JsToolsSection**：显示已加载的 JS 工具列表、加载错误、重新加载按钮
- **EnvVarsSection**：管理环境变量的键值对列表，支持添加/编辑/删除
- **SettingsViewModel 更新**：添加 `reloadJsTools()`、环境变量管理方法

详细的 Composable 代码参见英文版 RFC。

### ToolRegistry 增强

在 `ToolRegistry` 中添加一个小功能以支持重新加载时注销 JS 工具：

```kotlin
// 添加到 ToolRegistry:

/**
 * 注销特定类型的所有工具。
 * 用于 JS 工具重新加载时移除旧的 JS 工具。
 */
inline fun <reified T : Tool> unregisterByType() {
    val keysToRemove = tools.entries
        .filter { it.value is T }
        .map { it.key }
    keysToRemove.forEach { tools.remove(it) }
}
```

## 数据流

### 流程: AI 创建 JS 工具

```
1. 用户: "创建一个计算 BMI 的工具"

2. AI 调用 write_file(path="/sdcard/OneClaw/tools/bmi_calculator.json", content=...)
   -> JSON 元数据写入磁盘

3. AI 调用 write_file(path="/sdcard/OneClaw/tools/bmi_calculator.js", content=...)
   -> JS 代码写入磁盘

4. AI 回复: "已创建 BMI 计算器工具。请在设置中点击'重新加载工具'来激活它。"

5. 用户打开设置 -> JS 工具区域 -> 点击"重新加载"

6. JsToolLoader.loadTools():
   a. 扫描 /sdcard/OneClaw/tools/
   b. 找到 bmi_calculator.json + bmi_calculator.js
   c. 解析元数据，创建 JsTool 实例
   d. 注册到 ToolRegistry

7. 工具出现在设置 JS 工具列表中，显示绿色圆点

8. 用户现在可以将工具分配给 Agent 并在聊天中使用
```

### 流程: JS 工具执行

```
1. 模型响应包含工具调用:
   tool_name: "bmi_calculator", parameters: {"weight_kg": 70, "height_m": 1.75}

2. ToolExecutionEngine.executeTool("bmi_calculator", params, agentToolIds)
   a. ToolRegistry.getTool("bmi_calculator") -> JsTool 实例
   b. 可用性检查 -> OK
   c. 参数验证 -> OK
   d. 权限检查 -> 不需要权限
   e. 调用 JsTool.execute(params)

3. JsTool.execute() -> JsExecutionEngine.execute():
   a. 创建全新的 QuickJS 上下文
   b. 设置 memoryLimit = 16MB, maxStackSize = 1MB
   c. 注入 console 桥接 (tag: "JSTool:bmi_calculator")
   d. 注入 fs 桥接
   e. 注入 fetch 桥接
   f. 从磁盘读取 bmi_calculator.js
   g. 构建包装代码:
      - 嵌入工具 JS 代码
      - 将 params 序列化为 JSON
      - 调用 execute(params)
   h. 通过 quickJs { evaluate<String>(wrapperCode) } 执行
   i. 结果: "BMI: 22.86 (正常体重)"
   j. QuickJS 上下文关闭并释放

4. ToolResult.success("BMI: 22.86 (正常体重)")

5. 结果通过 SendMessageUseCase 返回给模型（与其他工具相同）
```

### 流程: 使用 fetch() 桥接的 JS 工具

```
1. weather_lookup.js 调用 fetch():

   async function execute(params) {
       const response = await fetch("https://api.example.com/weather?city=" + params.city);
       const data = await response.json();
       return `天气: ${data.temp}°C`;
   }

2. 执行跟踪:
   a. JS 调用 fetch(url, options)
   b. JS 包装器将 options 序列化为 JSON
   c. 调用 __fetchImpl(url, optionsJson) -- Kotlin async 函数
   d. Kotlin FetchBridge.performFetch():
      - 解析 options JSON
      - 构建 OkHttp Request
      - 在 Dispatchers.IO 上执行
      - 读取响应体（超过 100KB 则截断）
      - 序列化响应为 JSON {status, statusText, body}
   e. 返回 JSON 字符串给 JS
   f. JS 包装器解析为类 Response 对象
   g. JS 调用 response.json() -> 将 body 解析为 JSON
   h. JS 返回格式化字符串
```

## 错误处理

### 错误场景和行为

| 场景 | 错误类型 | 错误消息 | 处理方式 |
|------|---------|---------|---------|
| `.json` 没有 `.js` | (加载错误) | "缺少对应的 .js 文件" | 工具跳过，设置中显示错误 |
| 无效的 JSON 元数据 | (加载错误) | "加载失败: ..." | 工具跳过，设置中显示错误 |
| 名称不匹配 | (加载错误) | "工具名称与文件名不匹配" | 工具跳过 |
| 与内置工具名称冲突 | (加载错误) | "与已有工具名称冲突" | JS 工具跳过，记录警告 |
| JS 语法错误 | `execution_error` | "SyntaxError: ..." | 首次执行失败，错误返回给模型 |
| 缺少 `execute` 函数 | `execution_error` | "execute is not defined" | 错误返回给模型 |
| `execute` 抛出 Error | `execution_error` | JS 的错误消息 | 错误返回给模型 |
| 超时 | `timeout` | "JS 工具超时" | QuickJS 上下文丢弃 |
| 内存耗尽 | `execution_error` | QuickJS OOM 消息 | 上下文丢弃 |
| fetch() 网络错误 | `execution_error` | 网络错误传播 | JS 可捕获或让其传播 |
| fs 受限路径 | `execution_error` | "访问被拒绝: 路径受限" | JS 异常，传播 |
| fs 文件未找到 | `execution_error` | "文件未找到: ..." | JS 异常，传播 |

### 错误恢复

- **加载错误**：其他工具继续加载。错误在设置中以红色圆点显示。
- **执行错误**：作为 `ToolResult.error()` 返回给模型。模型可以通知用户或尝试不同方法。
- **超时**：QuickJS 上下文被丢弃（不复用）。无内存泄漏。
- **应用崩溃预防**：所有 JS 执行都包裹在 try-catch 中。QuickJS 原生崩溃在 JNI 边界被 quickjs-kt 库捕获。

## 实现步骤

### 阶段 1: QuickJS 集成 & 核心引擎
1. [ ] 在 `app/build.gradle.kts` 中添加 `io.github.dokar3:quickjs-kt-android:1.0.3` 依赖
2. [ ] 创建 `tool/js/` 包
3. [ ] 实现 `JsExecutionEngine` -- QuickJS 上下文生命周期、超时、内存限制
4. [ ] 编写单元测试: 基本 JS 求值（算术、字符串操作）
5. [ ] 编写单元测试: 异步 JS 求值（Promise）
6. [ ] 编写单元测试: 超时强制
7. [ ] 编写单元测试: 内存限制强制

### 阶段 2: 桥接函数
8. [ ] 实现 `ConsoleBridge` -- console.log/warn/error 到 Logcat
9. [ ] 实现 `FsBridge` -- readFile、writeFile、exists 带路径限制
10. [ ] 实现 `FetchBridge` -- 委托给 OkHttpClient 的 fetch() API
11. [ ] 编写单元测试: ConsoleBridge 输出到 Logcat（通过 mock 验证）
12. [ ] 编写单元测试: FsBridge readFile/writeFile/exists 使用临时文件
13. [ ] 编写单元测试: FsBridge 路径限制强制
14. [ ] 编写单元测试: FetchBridge GET/POST 使用 MockWebServer
15. [ ] 编写单元测试: FetchBridge 100KB 响应截断

### 阶段 3: 工具加载 & 注册
16. [ ] 实现 `JsToolLoader` -- 目录扫描、JSON 解析、验证
17. [ ] 实现 `JsTool` -- Tool 接口包装器
18. [ ] 实现 `EnvironmentVariableStore` -- EncryptedSharedPreferences 用于环境变量
19. [ ] 在 `ToolRegistry` 中添加 `unregisterByType<T>()`
20. [ ] 编写单元测试: JsToolLoader 使用有效工具文件
21. [ ] 编写单元测试: JsToolLoader 缺少 .js 文件
22. [ ] 编写单元测试: JsToolLoader 无效 JSON
23. [ ] 编写单元测试: JsToolLoader 名称验证（snake_case）
24. [ ] 编写单元测试: JsToolLoader 与内置工具名称冲突
25. [ ] 编写单元测试: JsTool.execute() 端到端使用简单 JS
26. [ ] 编写单元测试: EnvironmentVariableStore CRUD 操作

### 阶段 4: DI & 集成
27. [ ] 更新 `ToolModule.kt` 添加新单例（JsExecutionEngine、EnvironmentVariableStore、JsToolLoader）
28. [ ] 更新 `ToolRegistry` 初始化以调用 JsToolLoader
29. [ ] 确保首次启动时创建工具目录
30. [ ] 集成测试: JS 工具加载 -> 注册 -> 通过 ToolExecutionEngine 执行
31. [ ] 集成测试: JS 工具使用 fetch() 桥接发起真实 HTTP 请求
32. [ ] 集成测试: JS 工具使用 fs 桥接读写文件

### 阶段 5: 设置界面
33. [ ] 实现 `JsToolsSection` composable
34. [ ] 实现 `EnvVarsSection` composable
35. [ ] 实现添加/编辑/删除环境变量对话框
36. [ ] 将 JS 工具和环境变量区域添加到 `SettingsScreen`
37. [ ] 在 `SettingsViewModel` 中添加 `reloadJsTools()` 和环境变量管理
38. [ ] 编写新设置区域的截图测试（Roborazzi）

### 阶段 6: 测试
39. [ ] 运行 `./gradlew test` -- 所有单元测试通过
40. [ ] 运行 `./gradlew connectedAndroidTest` -- 所有仪器测试通过
41. [ ] 运行 `./gradlew recordRoborazziDebug` + `verifyRoborazziDebug` -- 截图通过
42. [ ] Layer 2: adb 可视化验证流程（见下方）
43. [ ] 编写测试报告

## 测试策略

### 单元测试

- `JsExecutionEngine`: 基本求值、异步、超时、内存限制、错误处理
- `ConsoleBridge`: 通过 mock/spy 验证日志输出
- `FsBridge`: readFile、writeFile、exists -- 使用临时目录；受限路径拒绝
- `FetchBridge`: 通过 MockWebServer 的 GET/POST；响应截断；错误响应
- `JsToolLoader`: 有效工具对、缺少 .js、无效 JSON、名称验证、名称冲突、空目录
- `JsTool`: 使用 mock JsExecutionEngine 的端到端 execute
- `EnvironmentVariableStore`: set/get/delete/getAll/getKeys
- `ToolRegistry.unregisterByType`: 注册混合类型，注销一种类型

### 集成测试（仪器化）

- JS 工具生命周期: 从设备存储加载、注册、执行、验证结果
- fetch() 桥接使用实际网络请求（需要有互联网的模拟器）
- fs 桥接使用模拟器存储的实际文件读写
- 环境变量注入到 JS 执行
- 重新加载: 添加新工具文件、重新加载、验证新工具可用

### 边界测试

- 工具目录不存在（应自动创建）
- 空工具目录（零个 JS 工具，无错误）
- `.json` 文件没有 `.js`（错误，其他工具仍加载）
- `.js` 文件没有 `.json`（静默忽略）
- 有语法错误的 JS 文件（加载，执行时失败）
- `execute` 返回 number/object/array（通过 JSON.stringify 转换）
- `execute` 返回 null/undefined（空字符串结果）
- `execute` 未定义（清晰的错误消息）
- JS 中的无限循环（超时触发）
- fetch() 到不可达主机（网络错误）
- fs.readFile 不存在的文件（错误）
- fs.writeFile 到受限路径（错误）
- 多个 JS 工具，一个无效（其他仍加载）
- JS 工具名称与内置工具相同（跳过）
- execute 返回超大字符串（在内存限制内应可工作）
- JS 文件、参数和结果中的 Unicode

### Layer 2 可视化验证流程

#### 流程 12-1: JS 工具加载和设置显示

**前置条件:** 推送测试工具文件到模拟器:
```bash
adb shell mkdir -p /sdcard/OneClaw/tools
adb push test_tool.json /sdcard/OneClaw/tools/
adb push test_tool.js /sdcard/OneClaw/tools/
```

```
目标: 验证 JS 工具在启动时被加载并在设置中显示。

步骤:
1. 启动应用（或在设置中点击重新加载）。
2. 导航到设置。
3. 截图 -> 验证: "JavaScript Tools" 区域可见，显示 "1 tool(s) loaded"。
4. 验证: test_tool 出现在列表中，有绿色圆点。
5. 验证: 工具名称和描述已显示。
```

---

#### 流程 12-2: 通过聊天执行 JS 工具

**前置条件:** 已配置有效 API 密钥。test_tool 已注册（流程 12-1 完成）。

```
目标: 验证 JS 工具可以被 AI 模型调用并返回结果。

步骤:
1. 确保 test_tool 已分配给当前 Agent 的工具集。
2. 发送一条会触发 test_tool 调用的消息。
3. 等待工具调用出现。
4. 截图 -> 验证: test_tool 的工具调用卡片显示 PENDING/EXECUTING 状态。
5. 等待完成。
6. 截图 -> 验证:
   - 工具调用卡片显示 SUCCESS。
   - 最终 AI 回复引用了工具结果。
```

---

#### 流程 12-3: 使用 fetch() 桥接的 JS 工具

**前置条件:** 已配置有效 API 密钥。一个使用 fetch() 的 JS 工具已注册。

```
目标: 验证 fetch() 桥接在 JS 工具中正常工作。

步骤:
1. 注册一个调用 fetch("https://httpbin.org/get") 的 JS 工具。
2. 发送一条触发此工具的消息。
3. 等待完成。
4. 截图 -> 验证:
   - 工具调用显示 SUCCESS。
   - AI 回复包含来自 httpbin 响应的数据。
```

---

#### 流程 12-4: JS 工具错误处理

**前置条件:** 注册一个有故意错误的 JS 工具（例如 throws Error）。

```
目标: 验证 JS 工具错误被优雅处理（应用不崩溃）。

步骤:
1. 注册一个 execute() 抛出 new Error("test error") 的工具。
2. 通过聊天触发该工具。
3. 等待结果。
4. 截图 -> 验证:
   - 工具调用卡片显示 ERROR 状态。
   - 错误消息可见。
   - 应用仍然响应（没有崩溃）。
```

---

#### 流程 12-5: 在设置中重新加载工具

**前置条件:** 应用运行中，设置已打开。

```
目标: 验证"重新加载工具"能识别新的/删除的工具文件。

步骤:
1. 通过 adb 推送新的 JS 工具: adb push new_tool.json + new_tool.js
2. 在设置中点击"重新加载"按钮。
3. 截图 -> 验证: new_tool 出现在列表中，有绿色圆点。
4. 通过 adb 删除工具文件: adb shell rm /sdcard/OneClaw/tools/new_tool.*
5. 再次点击"重新加载"。
6. 截图 -> 验证: new_tool 不再出现在列表中。
```

---

#### 流程 12-6: 环境变量

**前置条件:** 设置已打开。

```
目标: 验证环境变量可以被添加并注入到 JS 工具中。

步骤:
1. 导航到设置 -> 环境变量区域。
2. 截图 -> 验证: "No environment variables configured" 文本可见。
3. 点击"添加"按钮。
4. 输入 key: "TEST_KEY"，value: "test_value_123"。
5. 确认。
6. 截图 -> 验证: TEST_KEY 出现在列表中，值已遮罩。
```

## 安全性考虑

1. **QuickJS 沙箱**: JS 代码在 QuickJS 的嵌入式运行时中运行。它不能直接访问 JVM 对象、Android API 或 Kotlin 代码。唯一的逃逸路径是明确提供的桥接函数。

2. **文件访问边界**: `FsBridge` 应用与内置 `ReadFileTool`/`WriteFileTool` 相同的路径限制 -- 阻止 `/data/data/`、`/data/user/`、`/system/`、`/proc/`、`/sys/`。使用 `File.canonicalPath` 防止符号链接攻击。

3. **工具外无任意代码执行**: JS 代码只在 `JsExecutionEngine.execute()` 路径中被求值。没有 `eval()` 或其他 JS 求值入口。

4. **内存限制**: 每个 QuickJS 上下文限制为 16MB 堆和 1MB 栈。内存耗尽触发错误，而非应用崩溃。

5. **超时强制**: 通过无限循环防止拒绝服务。超时时 QuickJS 上下文被丢弃。

6. **环境变量隔离**: 环境变量通过 EncryptedSharedPreferences 加密存储。它们作为 `params._env`（从 JS 角度只读）注入。它们在 `.js` 或 `.json` 文件中不可见。

7. **网络**: `fetch()` 桥接委托给 OkHttpClient，与内置 `HttpRequestTool` 相同的响应大小限制（100KB）。无代理或凭证注入。

8. **工具文件来源**: 用户负责他们安装的 JS 工具。应用不验证工具文件签名或来源。这是 V1 可接受的权衡 -- 用户明确放置文件或让 AI 创建。

## 依赖关系

### 依赖的 RFC
- **RFC-004（工具系统）**: Tool 接口、ToolRegistry、ToolExecutionEngine、ToolResult、ToolDefinition
- **RFC-000（整体架构）**: 核心模型、Koin DI 设置、OkHttpClient
- **FEAT-009（设置）**: 设置界面用于 UI 添加
- **quickjs-kt 库**: `io.github.dokar3:quickjs-kt-android:1.0.3`

### 被依赖的 RFC
- **RFC-001（聊天交互）**: JS 工具调用在聊天中显示（无需更改 -- 它们是标准 Tool 调用）
- **RFC-002（Agent 管理）**: Agent 可以选择 JS 工具（无需更改 -- 它们出现在 ToolRegistry 中）

## 替代方案

### 方案 A: JS 工具调用现有 Kotlin 工具（编排模式）

不提供直接的 `fetch()` 和 `fs` 桥接，而是让 JS 工具调用现有的 Kotlin 工具（例如 `callTool("http_request", {url: "..."})`）来组合能力。

- **优点**: 不需要桥接代码，继承所有现有的安全/权限检查
- **缺点**: 对 JS 作者来说 API 不够自然，将 JS 工具与 Kotlin 工具名称紧密耦合，限制 JS 能做的事情只能是现有工具能力，工具调度的延迟开销
- **放弃原因**: 直接桥接提供更自然的 JS 开发体验，不限制 JS 工具只能使用现有能力。

### 方案 B: HarlonWang/quickjs-wrapper 库

- **优点**: 更多 star（254 vs 120），略微更成熟
- **缺点**: Java 风格 API，不是 Kotlin 惯用，没有协程集成，同样缺少中断处理器
- **放弃原因**: quickjs-kt 的 Kotlin 优先 API 对本代码库明显更好。

### 方案 C: 用 Lua 代替 JavaScript

- **优点**: 更轻量的运行时，更简单的语言
- **缺点**: 开发者受众少得多，语法不够熟悉，可用库更少，AI 生成 Lua 工具更难
- **放弃原因**: JavaScript 的知名度远高于 Lua，AI 模型生成 JS 代码的能力更强。

## 开放问题

- [ ] `quickjs-kt` 上下文应该池化以提升性能，还是始终全新以确保隔离？（当前设计: 全新，为了简单和隔离。如果分析显示开销可以后续优化。）
- [ ] `fetch()` 桥接是否应支持独立于工具超时的请求超时？（当前设计: fetch 共享整体工具超时。）
- [ ] 是否应限制可加载的 JS 工具最大数量？（当前设计: 无限制。如果性能成为问题可以添加。）
- [ ] 环境变量应该全局还是按工具配置？（当前设计: 全局。按工具需要额外的 UI 复杂性。）
- [ ] `quickjs-kt` 的 `asyncFunction` API 对复杂异步模式可能有限制。需要在实现期间使用真实的 fetch() 用法验证。

## 未来改进

- [ ] **热重载**: 文件系统监听器（FileObserver）自动检测工具文件变化
- [ ] **工具测试**: 设置中的"测试工具"按钮，用示例参数运行工具
- [ ] **分享意图**: 通过 Android 分享意图从其他应用接收 `.js` + `.json` 文件
- [ ] **工具模板**: 捆绑的模板 JS 工具，演示常见模式
- [ ] **AI 辅助重载**: AI 写完工具文件后自动触发重新加载，无需手动访问设置
- [ ] **上下文池化**: 池化并复用 QuickJS 上下文以获得更好性能（需要仔细隔离）
- [ ] **ES 模块**: 支持 JS 文件之间的 `import`/`export` 用于共享工具代码

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
