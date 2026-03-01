# RFC-034: JavaScript Eval 工具

## 文档信息
- **RFC ID**: RFC-034
- **Related PRD**: [FEAT-034 (JavaScript Eval Tool)](../../prd/features/FEAT-034-js-eval-tool.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## 概述

### 背景

OneClawShadow 的 AI Agent 已经可以访问预定义的 JS 工具（文件读写、HTTP、时间）以及 Kotlin 内置工具（webfetch、browser、exec 等）。然而，Agent 缺乏即时编写并执行任意代码的能力。当 Agent 需要进行计算、数据转换或算法任务时，目前除了预定义工具所提供的功能外，没有任何方式可以运行自定义逻辑。

项目已将 QuickJS 作为依赖项引入，并拥有一个成熟的 `JsExecutionEngine`，负责管理 QuickJS 生命周期、桥接注入、内存限制和超时强制执行。添加 `js_eval` 工具，只需将该引擎封装为一个新的内置工具，接受原始 JS 源代码作为参数即可。

### 目标

1. 在 `tool/builtin/` 中实现 `JsEvalTool.kt`，作为 Kotlin 内置工具
2. 接受 JavaScript 源代码字符串作为参数，并通过 `JsExecutionEngine` 执行
3. 同时支持简单表达式求值和 `main()` 函数入口点两种模式
4. 复用所有现有的 QuickJS 桥接（console、fs、fetch、time、lib）
5. 通过现有的 `JsExecutionEngine` 机制强制执行超时和内存限制
6. 在 `ToolModule` 中注册该工具

### 非目标

- 跨多次调用的持久化 JS 上下文
- 结构化输入数据参数（代码字符串之外）
- 每次调用的自定义桥接注入
- npm/Node.js 模块支持
- TypeScript 编译

## 技术设计

### 架构概览

```
+------------------------------------------------------------------+
|                    Chat Layer (RFC-001)                            |
|  SendMessageUseCase                                               |
|       |                                                           |
|       |  tool call: js_eval(code="function main() { ... }")       |
|       v                                                           |
+------------------------------------------------------------------+
|                  Tool Execution Engine (RFC-004)                   |
|  executeTool(name, params, availableToolIds)                      |
|       |                                                           |
|       v                                                           |
|  +---------------------------------------------------------------+|
|  |                   ToolRegistry                                 ||
|  |  +--------------------+                                        ||
|  |  |      js_eval       |  Kotlin built-in [NEW]                 ||
|  |  |  (JsEvalTool.kt)   |                                       ||
|  |  +---------+----------+                                        ||
|  |            |                                                   ||
|  |            v                                                   ||
|  |  +--------------------------------------------------------+   ||
|  |  |               JsExecutionEngine (existing)              |   ||
|  |  |  1. Create fresh QuickJS context                        |   ||
|  |  |  2. Set memory limits (16MB heap, 1MB stack)            |   ||
|  |  |  3. Inject bridges (console, fs, fetch, time, lib)      |   ||
|  |  |  4. Wrap code with entry point detection                |   ||
|  |  |  5. Evaluate code                                       |   ||
|  |  |  6. Return result                                       |   ||
|  |  +--------------------------------------------------------+   ||
|  +---------------------------------------------------------------+|
+------------------------------------------------------------------+
```

### 核心组件

**新增：**
1. `JsEvalTool` -- 接受 JS 代码并委托给 `JsExecutionEngine` 执行的 Kotlin 内置工具

**修改：**
2. `ToolModule` -- 将 `JsEvalTool` 注册为 Kotlin 内置工具

**复用（不变）：**
3. `JsExecutionEngine` -- 现有的 QuickJS 执行引擎
4. 所有桥接类（`ConsoleBridge`、`FsBridge`、`FetchBridge`、`TimeBridge`、`LibraryBridge`）

## 详细设计

### 目录结构（新增与变更文件）

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   └── builtin/
│   │       ├── JsEvalTool.kt              # NEW
│   │       ├── ExecTool.kt                # unchanged
│   │       ├── WebfetchTool.kt            # unchanged
│   │       ├── BrowserTool.kt             # unchanged
│   │       ├── LoadSkillTool.kt           # unchanged
│   │       ├── CreateScheduledTaskTool.kt  # unchanged
│   │       └── CreateAgentTool.kt         # unchanged
│   └── di/
│       └── ToolModule.kt                  # MODIFIED

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        └── builtin/
            └── JsEvalToolTest.kt           # NEW
```

### JsEvalTool

```kotlin
/**
 * Located in: tool/builtin/JsEvalTool.kt
 *
 * Kotlin built-in tool that executes arbitrary JavaScript code
 * in a sandboxed QuickJS environment via JsExecutionEngine.
 * Useful for computation, data processing, and algorithmic tasks.
 */
class JsEvalTool(
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MAX_TIMEOUT_SECONDS = 120
    }

    override val definition = ToolDefinition(
        name = "js_eval",
        description = "Execute JavaScript code in a sandboxed environment and return the result. " +
            "Useful for computation, data processing, math, string manipulation, and algorithmic tasks. " +
            "If the code defines a main() function, it will be called and its return value used as the result. " +
            "Otherwise, the value of the last expression is returned. " +
            "Objects and arrays are JSON-serialized in the result.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "code" to ToolParameter(
                    type = "string",
                    description = "The JavaScript source code to execute"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Maximum execution time in seconds. Default: 30, Max: 120"
                )
            ),
            required = listOf("code")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = MAX_TIMEOUT_SECONDS + 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        // 1. Parse and validate parameters
        val code = parameters["code"]?.toString()
        if (code.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'code' is required and cannot be empty"
            )
        }

        val timeoutSeconds = parseIntParam(parameters["timeout_seconds"])
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        // 2. Wrap code to support both expression and main() patterns
        val wrappedCode = wrapCode(code)

        // 3. Execute via JsExecutionEngine
        return jsExecutionEngine.executeFromSource(
            jsSource = wrappedCode,
            toolName = "js_eval",
            functionName = null,  // entry point handled by wrapper
            params = emptyMap(),
            env = envVarStore.getAll(),
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Wrap user code to detect and call main() if defined,
     * otherwise evaluate the code as-is (last expression = result).
     *
     * The JsExecutionEngine already wraps code in an async context
     * and handles result serialization, so we just need to add
     * the main() detection logic.
     */
    private fun wrapCode(code: String): String {
        return """
            $code

            if (typeof main === 'function') {
                main();
            }
        """.trimIndent()
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
```

### 包装器与 JsExecutionEngine 的集成方式

`JsExecutionEngine.executeFromSource()` 已经将 JS 代码包裹在以下结构中：

```javascript
// (fetch wrapper)
// (lib wrapper)

<user code here>

const __params__ = JSON.parse(...);
const __result__ = await execute(__params__);
// result serialization
```

由于 `js_eval` 不定义 `execute()` 函数，`JsExecutionEngine` 调用 `execute(__params__)` 时会失败。为了避免这个问题，`JsEvalTool` 使用 `functionName = null`，引擎默认调用 `execute()`。我们需要确保包装代码能够处理没有定义 `execute()` 函数的情况。

**方案 A（更简单）：** 通过提供一个自行定义 `execute()` 的包装器来覆盖：

```kotlin
private fun wrapCode(code: String): String {
    return """
        async function execute(params) {
            $code

            if (typeof main === 'function') {
                return await main();
            }
        }
    """.trimIndent()
}
```

这种方式将用户代码包裹在 `execute()` 函数体内，`JsExecutionEngine` 的现有包装器可以自然地调用它。如果用户定义了 `main()`，则会被调用；否则，函数体执行代码并返回 `undefined`（但 `console.log` 等副作用仍然生效）。

**然而**，对于表达式风格的代码（例如 `2 + 2`），我们需要返回表达式的值。修订后的包装器如下：

```kotlin
private fun wrapCode(code: String): String {
    return """
        async function execute(params) {
            const __eval_fn__ = new Function(`
                $code
            `);

            // Check if user code defines main()
            const __module__ = {};
            const __check_fn__ = new Function('module', `
                $code
                if (typeof main === 'function') { module.main = main; }
            `);
            __check_fn__(__module__);

            if (__module__.main) {
                return await __module__.main();
            }

            // For expression-style code, use eval to get the last expression value
            return eval($codeAsString);
        }
    """.trimIndent()
}
```

这种方案较为复杂。**最简洁的正确做法**：始终包裹在 `execute()` 中，对用户代码使用 `eval()`，然后检查 `main()`：

```kotlin
private fun wrapCode(code: String): String {
    // Escape the code for embedding as a JS string
    val escapedCode = code
        .replace("\\", "\\\\")
        .replace("`", "\\`")
        .replace("\$", "\\\$")
    return """
        async function execute(params) {
            const __result__ = eval(`$escapedCode`);
            if (typeof main === 'function') {
                return await main();
            }
            return __result__;
        }
    """.trimIndent()
}
```

### 最终实现（推荐方案）

分析 `JsExecutionEngine` 的包装代码后，最简洁的方案如下：

```kotlin
class JsEvalTool(
    private val jsExecutionEngine: JsExecutionEngine,
    private val envVarStore: EnvironmentVariableStore
) : Tool {

    companion object {
        private const val DEFAULT_TIMEOUT_SECONDS = 30
        private const val MAX_TIMEOUT_SECONDS = 120
    }

    override val definition = ToolDefinition(
        name = "js_eval",
        description = "Execute JavaScript code in a sandboxed environment and return the result. " +
            "Useful for computation, data processing, math, string manipulation, and algorithmic tasks. " +
            "If the code defines a main() function, it will be called and its return value used as the result. " +
            "Otherwise, the value of the last expression is returned. " +
            "Objects and arrays are JSON-serialized in the result.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "code" to ToolParameter(
                    type = "string",
                    description = "The JavaScript source code to execute"
                ),
                "timeout_seconds" to ToolParameter(
                    type = "integer",
                    description = "Maximum execution time in seconds. Default: 30, Max: 120"
                )
            ),
            required = listOf("code")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = MAX_TIMEOUT_SECONDS + 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val code = parameters["code"]?.toString()
        if (code.isNullOrBlank()) {
            return ToolResult.error(
                "validation_error",
                "Parameter 'code' is required and cannot be empty"
            )
        }

        val timeoutSeconds = parseIntParam(parameters["timeout_seconds"])
            ?.coerceIn(1, MAX_TIMEOUT_SECONDS)
            ?: DEFAULT_TIMEOUT_SECONDS

        val wrappedCode = buildExecuteWrapper(code)

        return jsExecutionEngine.executeFromSource(
            jsSource = wrappedCode,
            toolName = "js_eval",
            functionName = null,
            params = emptyMap(),
            env = envVarStore.getAll(),
            timeoutSeconds = timeoutSeconds
        )
    }

    /**
     * Build a wrapper that defines execute() for JsExecutionEngine compatibility.
     *
     * Strategy:
     * 1. Define execute() as the entry point (required by JsExecutionEngine).
     * 2. Inside execute(), eval() the user code to get the last expression value.
     * 3. If the user code defines a main() function, call it instead.
     * 4. This supports both "2 + 2" expressions and multi-function scripts.
     */
    private fun buildExecuteWrapper(code: String): String {
        val escaped = code
            .replace("\\", "\\\\")
            .replace("`", "\\`")
            .replace("\$", "\\$")
        return """
            async function execute(params) {
                const __result__ = eval(`$escaped`);
                if (typeof main === 'function') {
                    return await main();
                }
                return __result__;
            }
        """.trimIndent()
    }

    private fun parseIntParam(value: Any?): Int? {
        return when (value) {
            is Int -> value
            is Long -> value.toInt()
            is Double -> value.toInt()
            is Number -> value.toInt()
            is String -> value.toIntOrNull()
            else -> null
        }
    }
}
```

### ToolModule 变更

```kotlin
// In ToolModule.kt

val toolModule = module {
    // ... existing registrations ...

    // RFC-034: js_eval built-in tool
    single { JsEvalTool(get(), get()) }

    single {
        ToolRegistry().apply {
            // ... existing tool registrations ...

            try {
                register(get<JsEvalTool>(), ToolSourceInfo.BUILTIN)
            } catch (e: Exception) {
                Log.e("ToolModule", "Failed to register js_eval: ${e.message}")
            }

            // ... rest of initialization ...
        }
    }
}
```

### JsEvalTool 所需导入

```kotlin
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
```

## 实施计划

### 阶段一：JsEvalTool 实现

1. 在 `tool/builtin/` 中创建 `JsEvalTool.kt`
2. 实现带参数校验的 `execute()`
3. 实现用于代码包装的 `buildExecuteWrapper()`
4. 处理超时时间的范围限制

### 阶段二：集成

1. 更新 `ToolModule.kt`，注册 `JsEvalTool`
2. 添加 `JsEvalTool` 的导入和 Koin single 注册
3. 在 `ToolRegistry.apply` 块中添加注册

### 阶段三：测试

1. 创建 `JsEvalToolTest.kt` 并编写单元测试
2. 运行 Layer 1A 测试（`./gradlew test`）
3. 在设备上用各种 JS 代码片段进行手动测试

## 数据模型

无数据模型变更。`JsEvalTool` 实现现有的 `Tool` 接口，并复用 `JsExecutionEngine`。

## API 设计

### 工具接口

```
Tool Name: js_eval
Parameters:
  - code: string (required) -- The JavaScript source code to execute
  - timeout_seconds: integer (optional, default: 30, max: 120) -- Timeout

Returns on success:
  String result of the evaluation (primitives as strings, objects as JSON)

Returns on error:
  ToolResult.error with descriptive message
```

### 使用示例

**算术运算：**
```json
{
  "name": "js_eval",
  "parameters": { "code": "Math.pow(2, 32)" }
}
// Result: "4294967296"
```

**斐波那契数列：**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "function main() {\n  const fib = n => n <= 1 ? n : fib(n-1) + fib(n-2);\n  return fib(20);\n}"
  }
}
// Result: "6765"
```

**数据处理：**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "function main() {\n  const nums = [10, 20, 30, 40, 50];\n  return { sum: nums.reduce((a,b) => a+b), avg: nums.reduce((a,b) => a+b) / nums.length };\n}"
  }
}
// Result: '{"sum":150,"avg":30}'
```

**字符串处理：**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "'Hello World'.split('').reverse().join('')"
  }
}
// Result: "dlroW olleH"
```

## 错误处理

| 错误 | 原因 | 错误类型 | 处理方式 |
|------|------|----------|----------|
| 代码为空 | `code` 参数为空或 null | `validation_error` | 立即返回错误信息 |
| 语法错误 | 代码中存在无效的 JS 语法 | `execution_error` | QuickJS 解析错误，以 ToolResult.error 返回 |
| 运行时错误 | ReferenceError、TypeError 等 | `execution_error` | QuickJS 运行时错误，以 ToolResult.error 返回 |
| 执行超时 | 代码执行超过 `timeout_seconds` | `timeout` | 协程超时取消，QuickJS 上下文被销毁 |
| 内存超限 | 代码分配内存超过 16MB | `execution_error` | QuickJS 抛出 OOM，以 ToolResult.error 返回 |

## 安全考虑

1. **QuickJS 沙箱**：代码在沙箱化的 QuickJS 环境中运行，无法访问 Android API、Java 类或注入桥接函数之外的宿主进程。这从根本上比运行 shell 命令的 `exec` 更加安全。

2. **内存限制**：16MB 堆内存和 1MB 栈内存，防止内存耗尽攻击。

3. **超时机制**：防止无限循环和 CPU 耗尽。

4. **无持久状态**：每次 `js_eval` 调用都会创建一个全新的 QuickJS 上下文，调用之间不存在状态泄漏。

5. **桥接访问**：代码可以访问 fs、fetch 等桥接——与现有 JS 工具相同。这是有意为之的设计，AI Agent 已经通过其他工具拥有这些能力。

6. **无 eval() 逃逸**：QuickJS 的 `eval()` 被限制在沙箱内，无法访问 Android 运行时或逃逸出 QuickJS 上下文。

## 性能

| 操作 | 预期耗时 | 备注 |
|------|----------|------|
| QuickJS 上下文创建 | ~10-30ms | 轻量级引擎 |
| 桥接注入 | ~10-20ms | 5 个桥接 |
| 代码求值 | 取决于代码 | 受超时时间限制 |
| 上下文清理 | < 5ms | 通过 quickJs {} 块自动完成 |

内存使用情况：
- QuickJS 上下文：约 2-4MB 基线
- 用户代码分配：上限为 16MB
- 每次调用后上下文被销毁，无内存泄漏

## 测试策略

### 单元测试

**JsEvalToolTest.kt：**
- `testExecute_simpleExpression` -- `2 + 2` 返回 `"4"`
- `testExecute_stringExpression` -- `"hello".toUpperCase()` 返回 `"HELLO"`
- `testExecute_mainFunction` -- 含 `main()` 的代码返回其结果
- `testExecute_asyncMainFunction` -- 含 `async main()` 的代码正常运行
- `testExecute_objectResult` -- 返回的对象被 JSON 序列化
- `testExecute_arrayResult` -- 返回的数组被 JSON 序列化
- `testExecute_nullResult` -- `null` 返回空字符串
- `testExecute_emptyCode` -- 空代码返回校验错误
- `testExecute_syntaxError` -- 无效 JS 返回执行错误
- `testExecute_runtimeError` -- 未定义变量返回执行错误
- `testExecute_timeout` -- 设置 timeout_seconds=2 的无限循环触发超时
- `testExecute_timeoutClamped` -- timeout_seconds > 120 被截断
- `testExecute_mathFunctions` -- `Math.sqrt(144)` 返回 `"12"`
- `testExecute_jsonProcessing` -- JSON.parse/stringify 正常工作
- `testDefinition` -- 工具定义包含正确的名称和参数

### 手动测试（Layer 2）

- 执行斐波那契数列计算并验证结果正确
- 执行数据排序/过滤并验证输出
- 执行使用 `fetch()` 发起 HTTP 请求的代码
- 执行使用 `fs.readFile()` 读取文件的代码
- 执行带有故意无限循环的代码并验证超时触发
- 执行返回复杂嵌套 JSON 对象的代码

## 备选方案

### 1. 添加 `data` 参数支持结构化输入

**方案**：添加一个 `data` 参数（JSON 字符串），解析后传入 `main(data)`。
**推迟至 V2**：保持 V1 简洁。AI 模型可以直接将数据嵌入代码字符串中。如有需要，后续可轻松作为可选参数添加。

### 2. 使用 `exec` 工具结合 Node.js

**方案**：在设备上安装 Node.js，通过 `exec` 工具运行 `node -e "code"`。
**已拒绝**：在没有终端模拟器的情况下，Android 设备上无法使用 Node.js。QuickJS 已集成且更为轻量。

### 3. 扩展现有 JsTool 以接受内联代码

**方案**：向 `JsTool` 添加 `inline_code` 参数，而非创建新工具。
**已拒绝**：`JsTool` 是为基于文件的工具设计的，具有特定的生命周期。独立的 `JsEvalTool` 更加简洁，避免了对 `JsTool` 设计的复杂化。

## 依赖关系

### 外部依赖

- QuickJS Android 库（已是项目依赖：`libs.quickjs.android`）

### 内部依赖

- `tool/engine/` 中的 `Tool` 接口
- `tool/js/` 中的 `JsExecutionEngine`
- `tool/js/` 中的 `EnvironmentVariableStore`
- `core/model/` 中的 `ToolResult`、`ToolDefinition`、`ToolParametersSchema`、`ToolParameter`

## 未来扩展

- **结构化输入**：添加 `data` 参数，用于向 `main(data)` 传入输入数据
- **持久化上下文**：提供选项以保持 QuickJS 上下文存活，支持多步计算
- **Console 捕获**：在结果中一并返回 `console.log()` 的输出
- **代码模板**：提供模型可导入的预定义实用函数（例如 CSV 解析器、统计工具）
- **WASM 模块**：在 QuickJS 中加载 WASM 模块，实现原生速度的计算

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
