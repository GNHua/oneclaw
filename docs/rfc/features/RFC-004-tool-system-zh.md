# RFC-004: 工具系统

## 文档信息
- **RFC编号**: RFC-004
- **关联PRD**: [FEAT-004 (工具系统)](../../prd/features/FEAT-004-tool-system-zh.md)
- **关联设计**: [UI设计规范](../../design/ui-design-spec-zh.md)（聊天页面中的工具调用展示）
- **关联架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture-zh.md)
- **创建日期**: 2026-02-27
- **最后更新**: 2026-02-27（根据第二层测试实现修复更新）
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
工具系统是让 AI 模型能够执行文本生成之外的真实操作的框架 -- 读取文件、发起 HTTP 请求、查看时间等。这正是让 OneClaw 成为"AI Agent 运行时"而非仅仅是聊天应用的核心。该系统定义了工具注册、执行和结果格式化的标准接口，以及 Android 权限处理和提供商特定格式转换。

本 RFC 涵盖工具基础设施和全部 4 个内置工具。流式对话中的工具调用循环（模型请求工具 -> 执行 -> 返回结果 -> 模型继续）在 RFC-001（聊天交互）中覆盖。Agent 配置中的工具选择 UI 在 RFC-002（Agent 管理）中覆盖。

### 目标
1. 定义 `Tool` 接口和 `ToolDefinition` 数据模型
2. 实现 `ToolRegistry`（工具注册中心）
3. 实现带超时、权限检查和错误处理的 `ToolExecutionEngine`
4. 实现 Android 运行时权限处理的 `PermissionChecker`
5. 实现 4 个内置工具：`get_current_time`、`read_file`、`write_file`、`http_request`
6. 定义各提供商（OpenAI、Anthropic、Gemini）的工具定义格式转换
7. 实现基于 JSON Schema 的参数验证
8. 支持单次模型回复中多个工具调用的并行执行

### 非目标
- 流式对话中的工具调用循环（RFC-001）
- Agent 配置中的工具选择 UI（RFC-002）
- 聊天 UI 中的工具调用展示（RFC-001）
- 用户自定义工具
- 沙箱化工具执行（独立进程）
- 工具执行审批/确认流程
- 流式工具结果

## 技术方案

### 架构概览

```
┌──────────────────────────────────────────────────────┐
│                     聊天层 (RFC-001)                    │
│  SendMessageUseCase                                    │
│       │                                                │
│       │  来自模型的工具调用请求                            │
│       v                                                │
├──────────────────────────────────────────────────────┤
│                   工具执行引擎                           │
│  ┌────────────────────────────────────────────────┐   │
│  │ executeTool(name, params, availableToolIds)     │   │
│  │      │                                          │   │
│  │      ├── 1. 在 ToolRegistry 中查找工具           │   │
│  │      ├── 2. 验证可用性                           │   │
│  │      ├── 3. 检查权限 (PermissionChecker)         │   │
│  │      ├── 4. 带超时执行 (协程)                     │   │
│  │      └── 5. 返回 ToolResult                     │   │
│  └────────────────────────────────────────────────┘   │
│       │                                                │
│       v                                                │
│  ┌────────────────────────────────────────────────┐   │
│  │              ToolRegistry                       │   │
│  │  ┌──────────────────┐ ┌──────────────────┐     │   │
│  │  │ get_current_time │ │    read_file     │     │   │
│  │  └──────────────────┘ └──────────────────┘     │   │
│  │  ┌──────────────────┐ ┌──────────────────┐     │   │
│  │  │   write_file     │ │  http_request    │     │   │
│  │  └──────────────────┘ └──────────────────┘     │   │
│  └────────────────────────────────────────────────┘   │
├──────────────────────────────────────────────────────┤
│              提供商适配器层 (RFC-003)                     │
│  formatToolDefinitions() 将 ToolDefinition              │
│  转换为提供商特定格式 (OpenAI/Anthropic/Gemini)           │
└──────────────────────────────────────────────────────┘
```

### 核心组件

1. **Tool（接口）**
   - 职责：定义所有工具必须实现的契约
   - 属性：`definition: ToolDefinition`
   - 方法：`suspend fun execute(parameters: Map<String, Any?>): ToolResult`

2. **ToolDefinition（数据类）**
   - 职责：描述工具的元数据（名称、描述、参数 schema、权限、超时）
   - 使用方：ToolRegistry、Agent 配置、提供商适配器

3. **ToolRegistry**
   - 职责：存储所有已注册的工具，提供按名称查找、按 ID 列表过滤的功能
   - 单例：整个应用一个实例

4. **ToolExecutionEngine**
   - 职责：编排工具执行 -- 查找、权限检查、超时、错误处理
   - 依赖：ToolRegistry、PermissionChecker

5. **PermissionChecker**
   - 职责：检查和请求 Android 运行时权限
   - 机制：挂起协程等待 Activity 权限回调

6. **ToolParameterValidator**
   - 职责：根据工具的 JSON Schema 验证工具调用参数
   - 使用方：ToolExecutionEngine（执行前）

## 数据模型

### 领域模型

全部定义在 `core/model/` 中。`ToolDefinition` 和 `ToolResult` 已在 RFC-000 中声明。本节提供完整定义。

#### ToolDefinition

```kotlin
data class ToolDefinition(
    val name: String,                          // 唯一工具名称，snake_case（如 "read_file"）
    val description: String,                   // 人类可读描述（一句话）
    val parametersSchema: ToolParametersSchema, // 结构化参数 schema
    val requiredPermissions: List<String>,      // 所需 Android 权限（如 "android.permission.ACCESS_FINE_LOCATION"）
    val timeoutSeconds: Int                    // 最大执行时间
)
```

**与 RFC-000 的变更**：`parametersSchema` 原为 `String`（原始 JSON Schema）。现改为类型化的 `ToolParametersSchema` 数据类，便于在 Kotlin 代码中操作，同时仍可序列化为 JSON Schema 用于 API 调用。

#### ToolParametersSchema

```kotlin
data class ToolParametersSchema(
    val properties: Map<String, ToolParameter>,   // 参数名 -> 定义
    val required: List<String> = emptyList()       // 必填参数名称
)

data class ToolParameter(
    val type: String,                  // "string", "integer", "number", "boolean", "object", "array"
    val description: String,           // 人类可读描述
    val enum: List<String>? = null,    // 允许的值（如果有限制）
    val default: Any? = null           // 默认值（如果可选）
)
```

这是覆盖 V1 所有工具参数需求的 JSON Schema 简化子集。序列化用于 API 调用时，生成标准 JSON Schema 格式：

```json
{
  "type": "object",
  "properties": {
    "path": { "type": "string", "description": "The absolute file path to read" }
  },
  "required": ["path"]
}
```

#### ToolResult

```kotlin
data class ToolResult(
    val status: ToolResultStatus,
    val result: String?,               // 结果数据（成功时）
    val errorType: String?,            // 错误类型标识（错误时）
    val errorMessage: String?          // 人类可读错误消息（错误时）
) {
    companion object {
        fun success(result: String): ToolResult = ToolResult(
            status = ToolResultStatus.SUCCESS,
            result = result,
            errorType = null,
            errorMessage = null
        )

        fun error(errorType: String, errorMessage: String): ToolResult = ToolResult(
            status = ToolResultStatus.ERROR,
            result = null,
            errorType = errorType,
            errorMessage = errorMessage
        )
    }
}

enum class ToolResultStatus {
    SUCCESS, ERROR
}
```

#### 序列化为 JSON

工具结果在发送回模型时序列化为 JSON：

```kotlin
fun ToolResult.toJsonString(): String {
    return if (status == ToolResultStatus.SUCCESS) {
        Json.encodeToString(mapOf("status" to "success", "result" to result))
    } else {
        Json.encodeToString(mapOf(
            "status" to "error",
            "error_type" to errorType,
            "message" to errorMessage
        ))
    }
}
```

## Tool 接口

```kotlin
/**
 * 所有工具必须实现的接口。
 * 位于：tool/engine/Tool.kt
 */
interface Tool {

    /**
     * 工具的元数据：名称、描述、参数 schema、权限、超时。
     */
    val definition: ToolDefinition

    /**
     * 使用给定参数执行工具。
     *
     * 此方法在后台调度器（Dispatchers.IO）上调用。
     * 内部不应切换调度器。
     *
     * @param parameters 参数名到值的键值对 Map。
     *   值的类型基于参数 schema：
     *   - "string" -> String
     *   - "integer" -> Int 或 Long
     *   - "number" -> Double
     *   - "boolean" -> Boolean
     *   - "object" -> Map<String, Any?>
     *   - "array" -> List<Any?>
     *
     * @return ToolResult，包含成功数据或错误信息。
     */
    suspend fun execute(parameters: Map<String, Any?>): ToolResult
}
```

## 工具注册中心

```kotlin
/**
 * 所有可用工具的注册中心。单例，在应用启动时创建。
 * 位于：tool/engine/ToolRegistry.kt
 */
class ToolRegistry {

    private val tools = mutableMapOf<String, Tool>()

    /**
     * 注册一个工具。如果同名工具已注册，抛出 IllegalArgumentException。
     */
    fun register(tool: Tool) {
        val name = tool.definition.name
        require(!tools.containsKey(name)) {
            "Tool '$name' is already registered"
        }
        tools[name] = tool
    }

    /**
     * 按名称获取工具。未找到返回 null。
     */
    fun getTool(name: String): Tool? = tools[name]

    /**
     * 获取所有已注册的工具定义。
     */
    fun getAllToolDefinitions(): List<ToolDefinition> = tools.values.map { it.definition }

    /**
     * 获取特定工具名称集合的工具定义。
     * 用于获取特定 Agent 的工具集。
     * 未知名称被静默忽略。
     */
    fun getToolDefinitionsByNames(names: List<String>): List<ToolDefinition> =
        names.mapNotNull { tools[it]?.definition }

    /**
     * 检查工具名称是否存在于注册中心。
     */
    fun hasTool(name: String): Boolean = tools.containsKey(name)

    /**
     * 获取所有已注册的工具名称。
     */
    fun getAllToolNames(): List<String> = tools.keys.toList()
}
```

## 工具执行引擎

```kotlin
/**
 * 编排工具执行：查找、权限检查、超时、错误处理。
 * 位于：tool/engine/ToolExecutionEngine.kt
 */
class ToolExecutionEngine(
    private val registry: ToolRegistry,
    private val permissionChecker: PermissionChecker
) {

    /**
     * 执行单个工具调用。
     */
    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): ToolResult {
        // 1. 查找工具
        val tool = registry.getTool(toolName)
            ?: return ToolResult.error(
                "tool_not_found",
                "Tool '$toolName' not found"
            )

        // 2. 检查可用性（此工具是否在 Agent 的工具集中？）
        if (toolName !in availableToolNames) {
            return ToolResult.error(
                "tool_not_available",
                "Tool '$toolName' is not available for this agent"
            )
        }

        // 3. 验证参数
        val validationError = validateParameters(parameters, tool.definition.parametersSchema)
        if (validationError != null) {
            return ToolResult.error("validation_error", validationError)
        }

        // 4. 检查 Android 权限
        val missingPermissions = permissionChecker.getMissingPermissions(
            tool.definition.requiredPermissions
        )
        if (missingPermissions.isNotEmpty()) {
            val granted = permissionChecker.requestPermissions(missingPermissions)
            if (!granted) {
                return ToolResult.error(
                    "permission_denied",
                    "Required permissions were denied: ${missingPermissions.joinToString(", ")}"
                )
            }
        }

        // 5. 在 IO 调度器上带超时执行
        return try {
            withContext(Dispatchers.IO) {
                withTimeout(tool.definition.timeoutSeconds * 1000L) {
                    tool.execute(parameters)
                }
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error(
                "timeout",
                "Tool execution timed out after ${tool.definition.timeoutSeconds}s"
            )
        } catch (e: CancellationException) {
            throw e  // 不捕获协程取消
        } catch (e: Exception) {
            ToolResult.error(
                "execution_error",
                "Tool execution failed: ${e.message ?: "Unknown error"}"
            )
        }
    }

    /**
     * 并行执行多个工具调用。
     * 用于单次模型回复包含多个工具调用请求时。
     */
    suspend fun executeToolsParallel(
        toolCalls: List<ToolCallRequest>,
        availableToolNames: List<String>
    ): List<ToolCallResponse> = coroutineScope {
        toolCalls.map { call ->
            async {
                val startTime = System.currentTimeMillis()
                val result = executeTool(call.toolName, call.parameters, availableToolNames)
                val duration = System.currentTimeMillis() - startTime
                ToolCallResponse(
                    toolCallId = call.toolCallId,
                    toolName = call.toolName,
                    result = result,
                    durationMs = duration
                )
            }
        }.map { deferred ->
            deferred.await()
        }
    }

    /**
     * 执行单个工具调用并测量耗时。
     */
    suspend fun executeToolTimed(
        toolName: String,
        parameters: Map<String, Any?>,
        availableToolNames: List<String>
    ): Pair<ToolResult, Long> {
        val startTime = System.currentTimeMillis()
        val result = executeTool(toolName, parameters, availableToolNames)
        val duration = System.currentTimeMillis() - startTime
        return Pair(result, duration)
    }

    /**
     * 根据工具的 schema 验证参数。
     * 有效返回 null，无效返回错误消息字符串。
     */
    private fun validateParameters(
        parameters: Map<String, Any?>,
        schema: ToolParametersSchema
    ): String? {
        // 检查必填参数是否存在
        for (requiredParam in schema.required) {
            if (!parameters.containsKey(requiredParam) || parameters[requiredParam] == null) {
                return "Missing required parameter: '$requiredParam'"
            }
        }

        // 检查参数类型
        for ((name, value) in parameters) {
            if (value == null) continue
            val paramDef = schema.properties[name] ?: continue

            val typeError = validateType(name, value, paramDef.type)
            if (typeError != null) return typeError

            // 检查枚举约束
            if (paramDef.enum != null && value is String && value !in paramDef.enum) {
                return "Parameter '$name' must be one of: ${paramDef.enum.joinToString(", ")}"
            }
        }

        return null  // 有效
    }

    private fun validateType(name: String, value: Any, expectedType: String): String? {
        val valid = when (expectedType) {
            "string" -> value is String
            "integer" -> value is Int || value is Long
            "number" -> value is Number
            "boolean" -> value is Boolean
            "object" -> value is Map<*, *>
            "array" -> value is List<*>
            else -> true
        }
        return if (!valid) {
            "Parameter '$name' expected type '$expectedType' but got ${value::class.simpleName}"
        } else null
    }
}

/**
 * 表示来自模型的工具调用请求。
 */
data class ToolCallRequest(
    val toolCallId: String,            // 提供商分配的工具调用 ID
    val toolName: String,
    val parameters: Map<String, Any?>
)

/**
 * 表示工具调用执行的结果。
 */
data class ToolCallResponse(
    val toolCallId: String,
    val toolName: String,
    val result: ToolResult,
    val durationMs: Long
)
```

## 权限检查器

```kotlin
/**
 * 处理 Android 运行时权限检查和请求。
 * 使用挂起方式：当需要请求权限时，协程挂起直到用户响应权限对话框。
 *
 * 位于：tool/engine/PermissionChecker.kt
 */
class PermissionChecker(private val context: Context) {

    private var pendingContinuation: CancellableContinuation<Boolean>? = null
    private var permissionLauncher: ActivityResultLauncher<Array<String>>? = null

    /**
     * 绑定到 Activity 的权限结果启动器。
     * 在 MainActivity 初始化时调用。
     */
    fun bindToActivity(launcher: ActivityResultLauncher<Array<String>>) {
        this.permissionLauncher = launcher
    }

    /**
     * Activity 销毁时解绑。
     */
    fun unbind() {
        this.permissionLauncher = null
        pendingContinuation?.cancel()
        pendingContinuation = null
    }

    /**
     * 检查哪些权限尚未授予。
     */
    fun getMissingPermissions(permissions: List<String>): List<String> {
        if (permissions.isEmpty()) return emptyList()
        return permissions.filter {
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 请求给定的权限。挂起直到用户响应。
     */
    suspend fun requestPermissions(permissions: List<String>): Boolean {
        val launcher = permissionLauncher
            ?: return false

        return suspendCancellableCoroutine { continuation ->
            pendingContinuation = continuation
            continuation.invokeOnCancellation {
                pendingContinuation = null
            }
            launcher.launch(permissions.toTypedArray())
        }
    }

    /**
     * Activity 收到权限结果时调用。恢复挂起的协程。
     */
    fun onPermissionResult(permissions: Map<String, Boolean>) {
        val allGranted = permissions.values.all { it }
        pendingContinuation?.resume(allGranted)
        pendingContinuation = null
    }

}
```

### Activity 集成

```kotlin
// 在 MainActivity.kt 中
class MainActivity : ComponentActivity() {

    private val permissionChecker: PermissionChecker by inject()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        permissionChecker.onPermissionResult(permissions)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        permissionChecker.bindToActivity(permissionLauncher)
        // ... 其余 onCreate 代码
    }

    override fun onDestroy() {
        permissionChecker.unbind()
        super.onDestroy()
    }
}
```

## 内置工具

### 1. GetCurrentTimeTool

```kotlin
/**
 * 位于：tool/builtin/GetCurrentTimeTool.kt
 */
class GetCurrentTimeTool : Tool {

    override val definition = ToolDefinition(
        name = "get_current_time",
        description = "Get the current date and time",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "timezone" to ToolParameter(
                    type = "string",
                    description = "Timezone identifier (e.g., 'America/New_York', 'Asia/Shanghai'). Defaults to device timezone.",
                    default = null
                ),
                "format" to ToolParameter(
                    type = "string",
                    description = "Output format: 'iso8601' or 'human_readable'. Defaults to 'iso8601'.",
                    enum = listOf("iso8601", "human_readable"),
                    default = "iso8601"
                )
            ),
            required = emptyList()
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        return try {
            val timezoneId = parameters["timezone"] as? String
            val format = parameters["format"] as? String ?: "iso8601"

            val zone = if (timezoneId != null) {
                try {
                    ZoneId.of(timezoneId)
                } catch (e: Exception) {
                    return ToolResult.error(
                        "validation_error",
                        "Invalid timezone: '$timezoneId'. Use IANA timezone format (e.g., 'America/New_York')."
                    )
                }
            } else {
                ZoneId.systemDefault()
            }

            val now = ZonedDateTime.now(zone)

            val result = when (format) {
                "human_readable" -> {
                    val formatter = DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm:ss a z")
                    now.format(formatter)
                }
                else -> {
                    now.format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
                }
            }

            ToolResult.success(result)
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to get current time: ${e.message}")
        }
    }
}
```

### 2. ReadFileTool

```kotlin
/**
 * 位于：tool/builtin/ReadFileTool.kt
 *
 * 文件访问限定在应用私有存储（context.filesDir）内。
 * 路径通过 FsBridge 允许列表验证，确保只能访问应用存储范围内的文件。
 * 无需额外 Android 权限。
 */
class ReadFileTool : Tool {

    companion object {
        private const val MAX_FILE_SIZE = 1024 * 1024  // 文本文件最大读取 1MB
    }

    override val definition = ToolDefinition(
        name = "read_file",
        description = "Read the contents of a file from app-private storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The file path to read (confined to app-private storage)"
                ),
                "encoding" to ToolParameter(
                    type = "string",
                    description = "File encoding. Defaults to 'UTF-8'.",
                    default = "UTF-8"
                )
            ),
            required = listOf("path")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val encoding = parameters["encoding"] as? String ?: "UTF-8"

        // 安全性：通过 FsBridge 允许列表验证路径在应用私有存储范围内
        val normalizedPath = File(path).canonicalPath
        if (!isAllowedPath(normalizedPath)) {
            return ToolResult.error(
                "path_not_allowed",
                "Path outside app storage: access is confined to app-private storage"
            )
        }

        val file = File(normalizedPath)

        if (!file.exists()) {
            return ToolResult.error("file_not_found", "File not found: $path")
        }

        if (!file.isFile) {
            return ToolResult.error("validation_error", "Path is a directory, not a file: $path")
        }

        if (file.length() > MAX_FILE_SIZE) {
            return ToolResult.error(
                "file_too_large",
                "File is too large (${file.length()} bytes). Maximum supported size is ${MAX_FILE_SIZE} bytes (1MB)."
            )
        }

        return try {
            val charset = try {
                Charset.forName(encoding)
            } catch (e: Exception) {
                return ToolResult.error("validation_error", "Unsupported encoding: '$encoding'")
            }

            val content = file.readText(charset)
            ToolResult.success(content)
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot read $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to read file: ${e.message}")
        }
    }

    private fun isAllowedPath(canonicalPath: String): Boolean {
        // FsBridge 允许列表验证：路径必须在应用私有存储（context.filesDir）内
        return canonicalPath.startsWith(context.filesDir.canonicalPath)
    }
}
```

### 3. WriteFileTool

```kotlin
/**
 * 位于：tool/builtin/WriteFileTool.kt
 *
 * 文件写入限定在应用私有存储（context.filesDir）内。
 * 路径通过 FsBridge 允许列表验证，无需额外 Android 权限。
 */
class WriteFileTool : Tool {

    override val definition = ToolDefinition(
        name = "write_file",
        description = "Write contents to a file in app-private storage",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "path" to ToolParameter(
                    type = "string",
                    description = "The file path to write (confined to app-private storage)"
                ),
                "content" to ToolParameter(
                    type = "string",
                    description = "The content to write to the file"
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "Write mode: 'overwrite' (replace file) or 'append' (add to end). Defaults to 'overwrite'.",
                    enum = listOf("overwrite", "append"),
                    default = "overwrite"
                )
            ),
            required = listOf("path", "content")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 10
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val path = parameters["path"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'path' is required")
        val content = parameters["content"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'content' is required")
        val mode = parameters["mode"] as? String ?: "overwrite"

        // 安全性：通过 FsBridge 允许列表验证路径在应用私有存储范围内
        val normalizedPath = File(path).canonicalPath
        if (!isAllowedPath(normalizedPath)) {
            return ToolResult.error(
                "path_not_allowed",
                "Path outside app storage: access is confined to app-private storage"
            )
        }

        val file = File(normalizedPath)

        return try {
            file.parentFile?.mkdirs()

            when (mode) {
                "append" -> file.appendText(content)
                else -> file.writeText(content)
            }

            val bytesWritten = content.toByteArray().size
            ToolResult.success(
                "Successfully wrote $bytesWritten bytes to $path (mode: $mode)"
            )
        } catch (e: SecurityException) {
            ToolResult.error("permission_denied", "Permission denied: cannot write to $path")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "Failed to write file: ${e.message}")
        }
    }

    private fun isAllowedPath(canonicalPath: String): Boolean {
        // FsBridge 允许列表验证：路径必须在应用私有存储（context.filesDir）内
        return canonicalPath.startsWith(context.filesDir.canonicalPath)
    }
}
```

### 4. HttpRequestTool

```kotlin
/**
 * 位于：tool/builtin/HttpRequestTool.kt
 */
class HttpRequestTool(private val okHttpClient: OkHttpClient) : Tool {

    companion object {
        private const val MAX_RESPONSE_SIZE = 100 * 1024  // 响应体最大 100KB
    }

    override val definition = ToolDefinition(
        name = "http_request",
        description = "Make an HTTP request to a URL",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "The URL to request"
                ),
                "method" to ToolParameter(
                    type = "string",
                    description = "HTTP method: GET, POST, PUT, DELETE. Defaults to GET.",
                    enum = listOf("GET", "POST", "PUT", "DELETE"),
                    default = "GET"
                ),
                "headers" to ToolParameter(
                    type = "object",
                    description = "Key-value pairs of HTTP headers (optional)"
                ),
                "body" to ToolParameter(
                    type = "string",
                    description = "Request body for POST/PUT requests (optional)"
                )
            ),
            required = listOf("url")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val url = parameters["url"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'url' is required")
        val method = (parameters["method"] as? String ?: "GET").uppercase()
        val headers = parameters["headers"] as? Map<*, *>
        val body = parameters["body"] as? String

        val httpUrl = url.toHttpUrlOrNull()
            ?: return ToolResult.error("validation_error", "Invalid URL: $url")

        val requestBuilder = Request.Builder().url(httpUrl)

        headers?.forEach { (key, value) ->
            if (key is String && value is String) {
                requestBuilder.addHeader(key, value)
            }
        }

        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> {
                if (requestBody != null) requestBuilder.delete(requestBody)
                else requestBuilder.delete()
            }
            else -> return ToolResult.error("validation_error", "Unsupported HTTP method: $method")
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            val responseBody = response.body?.let { responseBody ->
                val bytes = responseBody.bytes()
                if (bytes.size > MAX_RESPONSE_SIZE) {
                    val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                    "$truncated\n\n(Response truncated. Showing first ${MAX_RESPONSE_SIZE / 1024}KB of ${bytes.size / 1024}KB total.)"
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            } ?: "(empty response body)"

            val responseHeaders = buildString {
                response.header("Content-Type")?.let { append("Content-Type: $it\n") }
                response.header("Content-Length")?.let { append("Content-Length: $it\n") }
            }.trimEnd()

            val result = buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                if (responseHeaders.isNotEmpty()) {
                    appendLine(responseHeaders)
                }
                appendLine()
                append(responseBody)
            }

            ToolResult.success(result)
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("network_error", "Cannot resolve host: ${httpUrl.host}")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("timeout", "HTTP request timed out")
        } catch (e: java.net.ConnectException) {
            ToolResult.error("network_error", "Connection refused: $url")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "HTTP request failed: ${e.message}")
        }
    }
}
```

## 工具定义格式转换

不同的 AI 提供商期望不同格式的工具定义。转换在提供商适配器层处理（来自 RFC-003）。本节定义 `ToolDefinition` 如何转换为各提供商格式。

### JSON Schema 序列化辅助器

```kotlin
/**
 * 将 ToolParametersSchema 序列化为 JSON Schema map。
 * 被所有提供商适配器使用。
 *
 * 位于：tool/engine/ToolSchemaSerializer.kt
 */
object ToolSchemaSerializer {

    fun toJsonSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
        val properties = schema.properties.map { (name, param) ->
            val paramMap = mutableMapOf<String, Any>(
                "type" to param.type,
                "description" to param.description
            )
            param.enum?.let { paramMap["enum"] = it }
            name to paramMap
        }.toMap()

        val result = mutableMapOf<String, Any>(
            "type" to "object",
            "properties" to properties
        )
        if (schema.required.isNotEmpty()) {
            result["required"] = schema.required
        }
        return result
    }
}
```

**重要序列化说明（来自第二层测试 bug 修复）：**

`toJsonSchemaMap()` 返回 `Map<String, Any>`。当此 map 被嵌入到 `buildJsonObject { }` 中（kotlinx.serialization）时，嵌套的 `Map` 和 `List` 值必须转换为 `JsonElement` —— 不能直接传入原始 Kotlin 对象。对 `Map<String, Any>` 使用 `.toString()` 会产生 Kotlin 语法（`{key=value}`）而非 JSON。

每个适配器在将 schema map 嵌入请求体时必须使用 `anyToJsonElement()` 辅助函数：

```kotlin
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
```

各适配器中的使用方式：

```kotlin
// AnthropicAdapter —— 在 buildAnthropicRequest() 中
put("input_schema", anyToJsonElement(
    ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
))

// OpenAiAdapter —— 在 buildOpenAiRequest() 中
put("parameters", anyToJsonElement(
    ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
))

// GeminiAdapter —— 在 buildGeminiRequest() 中
put("parameters", anyToJsonElement(
    toGeminiSchemaMap(tool.parametersSchema)
))
```

### OpenAI 格式

OpenAI 的 function calling 格式是基准。我们的内部格式设计与之对齐。

```json
{
  "type": "function",
  "function": {
    "name": "read_file",
    "description": "Read the contents of a file from local storage",
    "parameters": {
      "type": "object",
      "properties": {
        "path": { "type": "string", "description": "The absolute file path to read" }
      },
      "required": ["path"]
    }
  }
}
```

```kotlin
// 在 OpenAiAdapter 中
fun formatToolDefinitions(tools: List<ToolDefinition>): List<Map<String, Any>> {
    return tools.map { tool ->
        mapOf(
            "type" to "function",
            "function" to mapOf(
                "name" to tool.name,
                "description" to tool.description,
                "parameters" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
            )
        )
    }
}
```

### Anthropic 格式

Anthropic 的 tool use 格式略有不同：没有 `"type": "function"` 包装，schema key 是 `input_schema` 而非 `parameters`。

```json
{
  "name": "read_file",
  "description": "Read the contents of a file from local storage",
  "input_schema": {
    "type": "object",
    "properties": {
      "path": { "type": "string", "description": "The absolute file path to read" }
    },
    "required": ["path"]
  }
}
```

```kotlin
// 在 AnthropicAdapter 中
fun formatToolDefinitions(tools: List<ToolDefinition>): List<Map<String, Any>> {
    return tools.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "input_schema" to ToolSchemaSerializer.toJsonSchemaMap(tool.parametersSchema)
        )
    }
}
```

### Gemini 格式

Gemini 在 `tools` 数组中使用 `function_declarations`，类型名称使用大写。

```json
{
  "function_declarations": [
    {
      "name": "read_file",
      "description": "Read the contents of a file from local storage",
      "parameters": {
        "type": "OBJECT",
        "properties": {
          "path": { "type": "STRING", "description": "The absolute file path to read" }
        },
        "required": ["path"]
      }
    }
  ]
}
```

```kotlin
// 在 GeminiAdapter 中
fun formatToolDefinitions(tools: List<ToolDefinition>): Map<String, Any> {
    val declarations = tools.map { tool ->
        mapOf(
            "name" to tool.name,
            "description" to tool.description,
            "parameters" to toGeminiSchemaMap(tool.parametersSchema)
        )
    }
    return mapOf("function_declarations" to declarations)
}

private fun toGeminiSchemaMap(schema: ToolParametersSchema): Map<String, Any> {
    val properties = schema.properties.map { (name, param) ->
        val paramMap = mutableMapOf<String, Any>(
            "type" to param.type.uppercase(),
            "description" to param.description
        )
        param.enum?.let { paramMap["enum"] = it }
        name to paramMap
    }.toMap()

    val result = mutableMapOf<String, Any>(
        "type" to "OBJECT",
        "properties" to properties
    )
    if (schema.required.isNotEmpty()) {
        result["required"] = schema.required
    }
    return result
}
```

### 工具结果格式转换

工具结果在发送回模型时也需要按提供商不同格式化。

**OpenAI**: 工具结果作为 `role: "tool"` 消息发送：
```json
{"role": "tool", "tool_call_id": "call_abc123", "content": "{\"status\": \"success\", \"result\": \"...\"}"}
```

**Anthropic**: 工具结果作为 `tool_result` 内容块发送：
```json
{"role": "user", "content": [{"type": "tool_result", "tool_use_id": "toolu_abc123", "content": "..."}]}
```

**Gemini**: 工具结果作为 `functionResponse` 部分发送：
```json
{"role": "function", "parts": [{"functionResponse": {"name": "read_file", "response": {"status": "success", "result": "..."}}}]}
```

这些格式转换将在各提供商适配器的 `sendMessageStream()` 方法中实现（RFC-001）。本 RFC 记录预期格式以供 RFC-001 参考。

## Koin 依赖注入

```kotlin
// ToolModule.kt
val toolModule = module {

    // Tool Registry - 单例，启动时注册所有工具
    single {
        ToolRegistry().apply {
            register(GetCurrentTimeTool())
            register(ReadFileTool())
            register(WriteFileTool())
            register(HttpRequestTool(get()))  // get() = OkHttpClient
        }
    }

    // Permission Checker - 单例，绑定到 Activity 生命周期
    single { PermissionChecker(androidContext()) }

    // Tool Execution Engine - 单例
    single { ToolExecutionEngine(get(), get()) }  // ToolRegistry, PermissionChecker
}
```

## 数据流

### 流程：模型请求单个工具调用

```
1. 模型回复（通过流式传输）包含工具调用：
   tool_name: "read_file", parameters: {"path": "/storage/emulated/0/notes.txt"}
   （流式工具调用的解析由 RFC-001 处理）

2. SendMessageUseCase (RFC-001) 接收到解析后的工具调用
   -> 在数据库中创建 TOOL_CALL 消息（状态：PENDING）
   -> UI 以紧凑模式显示 "Calling read_file..."

3. SendMessageUseCase 调用 ToolExecutionEngine.executeToolTimed(
     toolName = "read_file",
     parameters = {"path": "/storage/emulated/0/notes.txt"},
     availableToolNames = agent.toolIds
   )

4. ToolExecutionEngine:
   a. 在 ToolRegistry 中查找 "read_file" -> 找到
   b. 检查 "read_file" 在 Agent 工具集中 -> 是
   c. 验证参数 -> path 存在，有效
   d. 检查权限 -> 无需额外权限（应用私有存储）
      - 继续执行
   e. 在 Dispatchers.IO 上执行 ReadFileTool.execute()，10秒超时
   f. ReadFileTool 读取文件，返回 ToolResult.success(fileContents)

5. ToolExecutionEngine 返回 (ToolResult.success, durationMs=45)

6. SendMessageUseCase:
   -> 更新 TOOL_CALL 消息状态为 SUCCESS，存储耗时
   -> 创建 TOOL_RESULT 消息包含结果内容
   -> 将工具结果发送回模型（按提供商格式化）
   -> 模型处理并继续

7. UI 更新：
   -> 工具调用卡片显示 "read_file - Done (45ms)"
   -> 模型的下一个回复流式传入
```

### 流程：模型请求多个工具调用（并行）

```
1. 模型回复包含 2 个工具调用：
   - tool_call_1: get_current_time, parameters: {}
   - tool_call_2: http_request, parameters: {"url": "https://api.example.com/data"}

2. SendMessageUseCase 创建 2 个 TOOL_CALL 消息（都是 PENDING）

3. SendMessageUseCase 调用 ToolExecutionEngine.executeToolsParallel([
     ToolCallRequest("call_1", "get_current_time", {}),
     ToolCallRequest("call_2", "http_request", {"url": "..."})
   ], agent.toolIds)

4. ToolExecutionEngine 通过 coroutineScope + async 并行启动：
   - async { executeTool("get_current_time", ...) }  -> 2ms 完成
   - async { executeTool("http_request", ...) }       -> 500ms 完成

5. 收集所有结果。总墙钟时间：~500ms（不是 502ms）。

6. SendMessageUseCase 处理两个结果：
   -> 更新两个 TOOL_CALL 消息
   -> 创建 2 个 TOOL_RESULT 消息
   -> 将两个结果发送回模型
```

## 错误处理

### 错误场景和行为

| 场景 | 错误类型 | 错误消息 | 结果 |
|------|---------|---------|------|
| 工具名不存在 | `tool_not_found` | "Tool 'xyz' not found" | 模型被告知，可尝试替代方案 |
| 工具不在 Agent 集中 | `tool_not_available` | "Tool 'xyz' is not available for this agent" | 模型被告知 |
| 缺少必填参数 | `validation_error` | "Missing required parameter: 'path'" | 模型可用正确参数重试 |
| 参数类型错误 | `validation_error` | "Parameter 'path' expected type 'string' but got Int" | 模型可重试 |
| 权限被拒绝 | `permission_denied` | "Required permissions were denied: ..." | 模型告知用户 |
| 路径超出应用存储范围 | `path_not_allowed` | "Path outside app storage: access is confined to app-private storage" | 模型告知用户文件操作仅限于应用私有存储 |
| 文件未找到 | `file_not_found` | "File not found: /path/to/file" | 模型可告知用户或尝试其他路径 |
| 文件太大 | `file_too_large` | "File is too large (X bytes). Maximum: 1MB." | 模型可告知用户 |
| 受限路径 | `permission_denied` | "Access denied: cannot read app-internal or system files" | 安全边界强制执行 |
| HTTP DNS 失败 | `network_error` | "Cannot resolve host: example.com" | 模型可告知用户 |
| HTTP 超时 | `timeout` | "HTTP request timed out" | 模型可重试或告知用户 |
| 工具执行超时 | `timeout` | "Tool execution timed out after Xs" | 模型可重试或告知用户 |
| 工具崩溃 | `execution_error` | "Tool execution failed: [exception message]" | 应用保持运行，模型被告知 |

应用永远不会因工具错误崩溃。模型对每个工具调用都会收到结果（成功或错误）。

## 实现步骤

### 阶段 1：核心基础设施
1. [ ] 在 `core/model/` 定义 `ToolParametersSchema` 和 `ToolParameter`
2. [ ] 更新 `ToolDefinition`，使用 `ToolParametersSchema` 替代 `String`
3. [ ] 为 `ToolResult` 添加 `success()` 和 `error()` 伴生方法
4. [ ] 实现 `ToolSchemaSerializer`（转 JSON Schema map）
5. [ ] 在 `tool/engine/` 实现 `Tool` 接口
6. [ ] 在 `tool/engine/` 实现 `ToolRegistry`
7. [ ] 在 `ToolExecutionEngine` 中实现参数验证逻辑
8. [ ] 实现 `ToolCallRequest` 和 `ToolCallResponse` 数据类

### 阶段 2：权限系统
9. [ ] 实现 `PermissionChecker`，包含挂起式权限请求
10. [ ] 将 `PermissionChecker` 与 `MainActivity` 集成（绑定/解绑生命周期）

### 阶段 3：执行引擎
12. [ ] 实现 `ToolExecutionEngine.executeTool()`（单工具）
13. [ ] 实现 `ToolExecutionEngine.executeToolTimed()`（带耗时）
14. [ ] 实现 `ToolExecutionEngine.executeToolsParallel()`（并行）
15. [ ] 添加 `withTimeout` 超时处理
16. [ ] 添加错误捕获（所有异常 -> ToolResult.error）

### 阶段 4：内置工具
17. [ ] 实现 `GetCurrentTimeTool`
18. [ ] 实现 `ReadFileTool`，包含路径安全检查
19. [ ] 实现 `WriteFileTool`，包含路径安全检查
20. [ ] 实现 `HttpRequestTool`，包含响应截断

### 阶段 5：提供商格式转换
21. [ ] 在 `OpenAiAdapter` 中实现 `formatToolDefinitions()`
22. [ ] 在 `AnthropicAdapter` 中实现 `formatToolDefinitions()`
23. [ ] 在 `GeminiAdapter` 中实现 `formatToolDefinitions()`
24. [ ] 记录各提供商的工具结果格式（供 RFC-001 参考）

### 阶段 6：DI 与集成
25. [ ] 在 Koin 中设置 `ToolModule`
26. [ ] 在 `ToolRegistry` 中注册所有内置工具
27. [ ] 在 AndroidManifest.xml 中添加 `INTERNET` 权限
28. [ ] 使用 mock 数据对所有工具进行单元测试
29. [ ] 集成测试：工具注册 -> 执行 -> 结果

## 测试策略

### 单元测试
- `ToolRegistry`：注册、查找、重复名称拒绝、按名称获取
- `ToolExecutionEngine.validateParameters()`：必填参数、类型检查、枚举验证
- `ToolExecutionEngine.executeTool()`：mock 工具，验证超时，验证错误捕获
- `GetCurrentTimeTool`：默认时区、指定时区、无效时区、两种格式
- `ReadFileTool`：文件存在、文件不存在、受限路径、文件过大、编码
- `WriteFileTool`：覆写、追加、受限路径、父目录创建
- `HttpRequestTool`：GET/POST、headers、响应截断、网络错误
- `ToolSchemaSerializer`：验证 JSON Schema 输出匹配预期格式
- 格式转换：验证 OpenAI/Anthropic/Gemini 工具定义格式

### 集成测试（Instrumented）
- 权限流程：请求 -> 授予 -> 工具执行
- 权限流程：请求 -> 拒绝 -> 返回错误
- 在实际设备存储上的文件读写
- 向测试服务器发起 HTTP 请求
- 并行工具执行时间测量

### 边缘情况
- 使用空参数 map 调用工具
- 使用多余未知参数调用工具（应被忽略）
- 非常大的 HTTP 响应体（验证截断）
- 读取二进制文件
- 写入父目录不存在的路径
- 应用在后台时请求权限
- 并发权限请求（应串行化）
- 工具执行被取消（协程取消）

### 第二层视觉验证流程

每个流程相互独立。所有流程均需已配置带有效 API key 的提供商。
每个标注"截图"的步骤后截图并验证。

---

#### 流程 4-1：get_current_time 工具 — 单工具调用

**前置条件：** 有效 API key 已配置。导航至聊天界面。

```
目标：验证 get_current_time 工具执行后结果显示在聊天中。

步骤：
1. 发送消息："现在是什么时间？"
2. 截图 -> 验证：用户消息气泡显示在右侧。
3. 等待工具调用开始（最多 5 秒）。
4. 截图 -> 验证：工具调用卡片可见，显示：
   - 工具名称："get_current_time"
   - 状态：PENDING 或 EXECUTING（旋转器或指示器）
5. 等待工具完成（最多 5 秒）。
6. 截图 -> 验证：
   - 工具调用卡片状态更新为 SUCCESS（绿色或勾选标记）。
   - 工具结果显示（当前时间字符串）。
   - AI 最终响应可见，内容引用了当前时间。
```

---

#### 流程 4-2：read_file 工具 — 读取文件

**前置条件：** 有效 API key 已配置。设备上存在已知文件路径
（如先执行：`adb shell "echo 'hello world' > /sdcard/test.txt"`）。

```
目标：验证 read_file 工具能读取文件并将内容返回给模型。

步骤：
1. adb shell "echo 'hello world' > /sdcard/test.txt"
2. 发送消息："请读取 /sdcard/test.txt 文件并告诉我内容。"
3. 等待工具调用出现。
4. 截图 -> 验证：显示"read_file"的工具调用卡片，状态为 PENDING/EXECUTING。
5. 等待完成。
6. 截图 -> 验证：
   - 工具调用卡片显示 SUCCESS。
   - AI 最终响应提到"hello world"（文件内容）。
```

---

#### 流程 4-3：http_request 工具 — HTTP GET

**前置条件：** 有效 API key 已配置。设备可访问互联网。

```
目标：验证 http_request 工具能执行 GET 请求并返回响应。

步骤：
1. 发送消息："向 https://httpbin.org/get 发起 HTTP GET 请求并展示响应内容。"
2. 等待工具调用出现。
3. 截图 -> 验证：显示"http_request"的工具调用卡片，状态为 PENDING/EXECUTING。
4. 等待完成（最多 15 秒，取决于网络）。
5. 截图 -> 验证：
   - 工具调用卡片显示 SUCCESS。
   - AI 最终响应包含 httpbin 响应内容（如"url"、"headers"字段）。
```

---

#### 流程 4-4：并行工具调用

**前置条件：** 有效 API key 已配置。测试文件已存在（先执行流程 4-2 的前置条件）。

```
目标：验证模型在单次响应中发起多个工具调用时，所有工具都能执行并显示结果。

步骤：
1. 发送消息："同时做两件事：(1) 告诉我当前时间，(2) 读取 /sdcard/test.txt 的内容。"
2. 等待模型发起工具调用。
3. 截图 -> 验证：两个工具调用卡片可见（get_current_time 和 read_file），均为 PENDING 或 EXECUTING。
4. 等待两个工具完成。
5. 截图 -> 验证：
   - 两个工具调用卡片均显示 SUCCESS。
   - AI 最终响应同时引用两个结果（当前时间和文件内容）。
```

---

#### 流程 4-5：工具调用错误 — 受限文件路径

**前置条件：** 有效 API key 已配置。

```
目标：验证读取受限路径时返回错误结果，不会崩溃。

步骤：
1. 发送消息："请读取文件 /data/data/com.oneclaw.shadow/databases/oneclaw.db"
2. 等待工具调用出现。
3. 截图 -> 验证：显示"read_file"的工具调用卡片。
4. 等待结果。
5. 截图 -> 验证：
   - 工具调用卡片显示 ERROR 状态（红色或错误图标）。
   - 卡片中的错误消息说明拒绝访问或路径受限。
   - AI 最终响应优雅地处理了错误（应用不崩溃）。
```

## 安全考虑

1. **文件访问边界**：`ReadFileTool` 和 `WriteFileTool` 的文件访问限定在应用私有存储（context.filesDir）内，通过 FsBridge 允许列表验证路径，无需外部存储权限。
2. **无任意代码执行**：工具编译在应用中。V1 不支持用户定义或下载的工具。
3. **HTTP 工具**：不限制 HTTP vs HTTPS，因为用户可能有意访问本地/HTTP 服务。工具不添加任何凭据 -- 用户控制 AI 访问哪些 URL。
4. **数据库中的工具结果**：工具结果（包括文件内容和 HTTP 响应）存储在 messages 表中。与其他消息安全级别相同。工具结果中的敏感数据由用户负责。
5. **API key 隔离**：工具执行永远无法访问 API key。工具不能读取 EncryptedSharedPreferences。

## 依赖关系

### 依赖的 RFC
- **RFC-000（整体架构）**：核心模型（ToolDefinition、ToolResult）、项目结构
- **RFC-003（提供商管理）**：提供商适配器（用于格式转换方法）
- Android API：文件 I/O、OkHttp（HTTP 工具）、java.time（时间工具）、权限系统

### 被依赖的 RFC
- **RFC-001（聊天交互）**：流式对话中的工具调用循环、工具调用 UI 数据
- **RFC-002（Agent 管理）**：Agent 的工具集引用 ToolRegistry 中的工具名称

## 开放问题

- [ ] **HTTP 响应体改进**：V1 使用简单的 100KB 截断。未来改进：对 `text/html` 响应，去除 `<script>`、`<style>`、`<nav>`、`<footer>` 标签并转为纯文本后再截断。这将大大提高网页抓取的实用性。需要 HTML 解析库（如 Jsoup），推迟到 V1 之后。
- [ ] **文件大小限制**：`read_file` 最大 1MB。是否应可配置？V1 硬编码即可。
- [x] **~~Play Store 上的 MANAGE_EXTERNAL_STORAGE~~**：已解决 -- 文件访问现已限定在应用私有存储（context.filesDir），使用 FsBridge 允许列表方式，不再需要 MANAGE_EXTERNAL_STORAGE 权限。

## 未来改进

- [ ] **HTML 感知的 HTTP 响应处理**：去除 scripts/styles，提取主要内容，转为干净文本后再截断。将使 `http_request` 在网页浏览任务中更加实用。
- [ ] **沙箱化工具执行**：在独立进程中运行工具以获得更好的隔离性。
- [ ] **用户自定义工具**：允许用户通过脚本创建工具（如 JavaScript/Lua）。
- [ ] **工具专用 UI**：`read_file` 的文件选择器、图片/媒体工具的富展示。
- [ ] **流式工具结果**：对于长时间运行的工具，渐进式返回部分结果。
- [ ] **更多内置工具**：剪贴板、设备信息、应用启动器、通知、日历、联系人。
- [ ] **工具执行审批模式**：敏感工具执行前的可选确认对话框。

## 参考资料

- [FEAT-004 PRD](../../prd/features/FEAT-004-tool-system-zh.md) -- 功能需求
- [RFC-000 整体架构](../architecture/RFC-000-overall-architecture-zh.md) -- 工具接口和引擎概要
- [RFC-003 提供商管理](RFC-003-provider-management-zh.md) -- 提供商适配器用于格式转换
- [OpenAI Function Calling](https://platform.openai.com/docs/guides/function-calling)
- [Anthropic Tool Use](https://docs.anthropic.com/en/docs/build-with-claude/tool-use)
- [Gemini Function Calling](https://ai.google.dev/gemini-api/docs/function-calling)
## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|----------|--------|
| 2026-02-27 | 0.1 | 初始版本 | - |
