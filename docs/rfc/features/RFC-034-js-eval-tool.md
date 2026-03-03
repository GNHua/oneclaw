# RFC-034: JavaScript Eval Tool

## Document Information
- **RFC ID**: RFC-034
- **Related PRD**: [FEAT-034 (JavaScript Eval Tool)](../../prd/features/FEAT-034-js-eval-tool.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw's AI agent has access to pre-defined JS tools (file read/write, HTTP, time) and Kotlin built-in tools (webfetch, browser, exec, etc.). However, the agent lacks the ability to write and execute arbitrary code on the fly. When the agent needs to perform a calculation, data transformation, or algorithmic task, it currently has no way to run custom logic beyond what pre-defined tools offer.

The project already includes QuickJS as a dependency and has a mature `JsExecutionEngine` that manages QuickJS lifecycle, bridge injection, memory limits, and timeout enforcement. Adding a `js_eval` tool simply wraps this engine in a new built-in tool that accepts raw JS source code as a parameter.

### Goals

1. Implement `JsEvalTool.kt` as a Kotlin built-in tool in `tool/builtin/`
2. Accept JavaScript source code as a string parameter and execute it via `JsExecutionEngine`
3. Support both simple expression evaluation and `main()` function entry point
4. Reuse all existing QuickJS bridges (console, fs, fetch, time, lib)
5. Enforce timeout and memory limits via existing `JsExecutionEngine` mechanisms
6. Register the tool in `ToolModule`

### Non-Goals

- Persistent JS context across multiple calls
- Structured input data parameters (beyond the code string)
- Custom bridge injection per-call
- npm/Node.js module support
- TypeScript compilation

## Technical Design

### Architecture Overview

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

### Core Components

**New:**
1. `JsEvalTool` -- Kotlin built-in tool that accepts JS code and delegates to `JsExecutionEngine`

**Modified:**
2. `ToolModule` -- Register `JsEvalTool` as a Kotlin built-in tool

**Reused (unchanged):**
3. `JsExecutionEngine` -- Existing QuickJS execution engine
4. All bridge classes (`ConsoleBridge`, `FsBridge`, `FetchBridge`, `TimeBridge`, `LibraryBridge`)

## Detailed Design

### Directory Structure (New & Changed Files)

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

### How the Wrapper Integrates with JsExecutionEngine

The `JsExecutionEngine.executeFromSource()` already wraps the JS code in:

```javascript
// (fetch wrapper)
// (lib wrapper)

<user code here>

const __params__ = JSON.parse(...);
const __result__ = await execute(__params__);
// result serialization
```

Since `js_eval` does not define an `execute()` function, `JsExecutionEngine` will call `execute(__params__)` which will fail. To avoid this, `JsEvalTool` uses `functionName = null` and the engine defaults to calling `execute()`. We need to ensure the wrapper code handles the case where no `execute()` function is defined.

**Option A (simpler):** Override by providing a wrapper that defines `execute()` itself:

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

This approach wraps the user's code inside an `execute()` function body, so `JsExecutionEngine`'s existing wrapper calls it naturally. If the user defines `main()`, it is called. Otherwise, the function body evaluates the code and returns `undefined` (but any side effects like console.log still work).

**However**, for expression-style code (e.g., `2 + 2`), we need the expression value to be returned. Revised wrapper:

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

This is getting complex. **Simplest correct approach**: always wrap in `execute()` and use `eval()` for the user code, then check for `main()`:

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

### Final Implementation (Recommended)

After analyzing `JsExecutionEngine`'s wrapper code, the simplest approach is:

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

### ToolModule Changes

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

### Imports Required for JsEvalTool

```kotlin
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import com.oneclaw.shadow.tool.js.EnvironmentVariableStore
import com.oneclaw.shadow.tool.js.JsExecutionEngine
```

## Implementation Plan

### Phase 1: JsEvalTool Implementation

1. Create `JsEvalTool.kt` in `tool/builtin/`
2. Implement `execute()` with parameter validation
3. Implement `buildExecuteWrapper()` for code wrapping
4. Handle timeout clamping

### Phase 2: Integration

1. Update `ToolModule.kt` to register `JsEvalTool`
2. Add `JsEvalTool` import and Koin single registration
3. Add registration in the `ToolRegistry.apply` block

### Phase 3: Testing

1. Create `JsEvalToolTest.kt` with unit tests
2. Run Layer 1A tests (`./gradlew test`)
3. Manual testing with various JS code snippets on device

## Data Model

No data model changes. `JsEvalTool` implements the existing `Tool` interface and reuses `JsExecutionEngine`.

## API Design

### Tool Interface

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

### Usage Examples

**Arithmetic:**
```json
{
  "name": "js_eval",
  "parameters": { "code": "Math.pow(2, 32)" }
}
// Result: "4294967296"
```

**Fibonacci:**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "function main() {\n  const fib = n => n <= 1 ? n : fib(n-1) + fib(n-2);\n  return fib(20);\n}"
  }
}
// Result: "6765"
```

**Data processing:**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "function main() {\n  const nums = [10, 20, 30, 40, 50];\n  return { sum: nums.reduce((a,b) => a+b), avg: nums.reduce((a,b) => a+b) / nums.length };\n}"
  }
}
// Result: '{"sum":150,"avg":30}'
```

**String processing:**
```json
{
  "name": "js_eval",
  "parameters": {
    "code": "'Hello World'.split('').reverse().join('')"
  }
}
// Result: "dlroW olleH"
```

## Error Handling

| Error | Cause | Error Type | Handling |
|-------|-------|------------|----------|
| Empty code | Blank or null `code` param | `validation_error` | Return immediately with error message |
| Syntax error | Invalid JS syntax in code | `execution_error` | QuickJS parse error, returned as ToolResult.error |
| Runtime error | ReferenceError, TypeError, etc. | `execution_error` | QuickJS runtime error, returned as ToolResult.error |
| Timeout | Code exceeds `timeout_seconds` | `timeout` | Coroutine timeout cancellation, QuickJS context destroyed |
| Memory exceeded | Code allocates > 16MB | `execution_error` | QuickJS throws OOM, returned as ToolResult.error |

## Security Considerations

1. **QuickJS Sandbox**: Code runs in a sandboxed QuickJS environment with no access to Android APIs, Java classes, or the host process beyond the injected bridge functions. This is fundamentally safer than `exec` which runs shell commands.

2. **Memory Limits**: 16MB heap and 1MB stack prevent memory exhaustion attacks.

3. **Timeout**: Prevents infinite loops and CPU exhaustion.

4. **No Persistent State**: Each `js_eval` call creates a fresh QuickJS context. No state leaks between calls.

5. **Bridge Access**: The code has access to fs, fetch, and other bridges -- same as existing JS tools. This is by design, as the AI agent already has these capabilities through other tools.

6. **No eval() Escape**: QuickJS `eval()` is confined to the sandbox. It cannot access the Android runtime or escape the QuickJS context.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| QuickJS context creation | ~10-30ms | Lightweight engine |
| Bridge injection | ~10-20ms | 5 bridges |
| Code evaluation | Depends on code | Bounded by timeout |
| Context cleanup | < 5ms | Automatic via quickJs {} block |

Memory usage:
- QuickJS context: ~2-4MB baseline
- User code allocations: capped at 16MB
- Context is destroyed after each call -- no leak

## Testing Strategy

### Unit Tests

**JsEvalToolTest.kt:**
- `testExecute_simpleExpression` -- `2 + 2` returns `"4"`
- `testExecute_stringExpression` -- `"hello".toUpperCase()` returns `"HELLO"`
- `testExecute_mainFunction` -- Code with `main()` returns its result
- `testExecute_asyncMainFunction` -- Code with `async main()` works
- `testExecute_objectResult` -- Returned object is JSON-serialized
- `testExecute_arrayResult` -- Returned array is JSON-serialized
- `testExecute_nullResult` -- `null` returns empty string
- `testExecute_emptyCode` -- Blank code returns validation error
- `testExecute_syntaxError` -- Invalid JS returns execution error
- `testExecute_runtimeError` -- Undefined variable returns execution error
- `testExecute_timeout` -- Infinite loop with timeout_seconds=2 triggers timeout
- `testExecute_timeoutClamped` -- timeout_seconds > 120 is clamped
- `testExecute_mathFunctions` -- `Math.sqrt(144)` returns `"12"`
- `testExecute_jsonProcessing` -- JSON.parse/stringify works correctly
- `testDefinition` -- Tool definition has correct name and parameters

### Manual Testing (Layer 2)

- Execute Fibonacci computation and verify correct result
- Execute data sorting/filtering and verify output
- Execute code that uses `fetch()` to make an HTTP request
- Execute code that uses `fs.readFile()` to read a file
- Execute code with intentional infinite loop and verify timeout
- Execute code that returns a complex nested JSON object

## Alternatives Considered

### 1. Add a `data` Parameter for Structured Input

**Approach**: Add a `data` parameter (JSON string) that is parsed and passed to `main(data)`.
**Deferred to V2**: Keeps V1 simple. The AI model can embed data directly in the code string. If needed later, easy to add as an optional parameter.

### 2. Use `exec` Tool with Node.js

**Approach**: Install Node.js on the device and use `exec` tool to run `node -e "code"`.
**Rejected**: Node.js is not available on Android without a terminal emulator. QuickJS is already integrated and much lighter.

### 3. Extend Existing JsTool to Accept Inline Code

**Approach**: Add an `inline_code` parameter to `JsTool` instead of creating a new tool.
**Rejected**: `JsTool` is designed for file-based tools with a specific lifecycle. A separate `JsEvalTool` is cleaner and avoids complicating `JsTool`'s design.

## Dependencies

### External Dependencies

- QuickJS Android library (already a project dependency: `libs.quickjs.android`)

### Internal Dependencies

- `Tool` interface from `tool/engine/`
- `JsExecutionEngine` from `tool/js/`
- `EnvironmentVariableStore` from `tool/js/`
- `ToolResult`, `ToolDefinition`, `ToolParametersSchema`, `ToolParameter` from `core/model/`

## Future Extensions

- **Structured input**: Add `data` parameter for passing input data to `main(data)`
- **Persistent context**: Option to keep a QuickJS context alive for multi-step computations
- **Console capture**: Return `console.log()` output alongside the result
- **Code templates**: Pre-defined utility functions the model can import (e.g., CSV parser, statistics)
- **WASM modules**: Load WASM modules into QuickJS for native-speed computation

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
