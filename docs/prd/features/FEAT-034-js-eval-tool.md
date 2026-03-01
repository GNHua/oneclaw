# JavaScript Eval Tool

## Feature Information
- **Feature ID**: FEAT-034
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-034 (pending)

## User Story

**As** an AI agent using OneClawShadow,
**I want** a tool that executes arbitrary JavaScript code I write on the fly in a sandboxed QuickJS environment,
**so that** I can perform computations, data transformations, algorithmic tasks, and general-purpose programming without relying on pre-defined tool files.

### Typical Scenarios

1. The user asks "What is the 50th Fibonacci number?" -- the agent writes a JS function to compute it and returns the result.
2. The user pastes a CSV table and asks the agent to calculate averages -- the agent writes JS to parse and compute the statistics.
3. The agent needs to sort a list of items by multiple criteria -- it writes a JS comparator and runs it.
4. The user asks to convert a Unix timestamp to a human-readable date -- the agent writes a quick JS Date conversion.
5. The agent needs to perform regex extraction on a large text block -- it writes JS with regex logic and returns the matches.
6. The user asks to encode/decode Base64, URL-encode strings, or perform hash computations -- the agent writes the appropriate JS code.
7. The agent needs to generate structured JSON data from unstructured text -- it writes JS parsing logic.
8. The user asks a math question involving formulas -- the agent writes JS with Math.* functions to compute the answer.

## Feature Description

### Overview

FEAT-034 adds a Kotlin built-in `js_eval` tool that accepts JavaScript source code as a string parameter and executes it in a sandboxed QuickJS environment. The tool reuses the existing `JsExecutionEngine` (from RFC-004) to run the code, giving the AI model the same bridge functions available to JS-based tools (console, fs, fetch, time, lib). The result of the last expression (or the return value of an `execute()` function if defined) is returned to the AI model.

This is distinct from existing JS tools which are pre-defined files loaded from disk or assets. `js_eval` lets the AI model write code dynamically, making it a general-purpose computation tool.

### Architecture Overview

```
AI Model
    | tool call: js_eval(code="return 2 + 2;")
    v
 ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
 ToolRegistry
    |
    v
 JsEvalTool  [NEW - Kotlin built-in tool]
    |
    v
 JsExecutionEngine  (existing, reused)
    |
    +-- QuickJS sandbox
    |       |
    |       +-- Memory limit: 16MB
    |       +-- Stack limit: 1MB
    |       +-- Timeout enforcement
    |       +-- Bridge functions: console, fs, fetch, time, lib
    |       |
    |       +-- Evaluate code, return result
    |
    +-- Result formatting
            |
            +-- Return string/JSON result
            +-- Return error if execution fails
```

### Tool Definition

| Field | Value |
|-------|-------|
| Name | `js_eval` |
| Description | Execute JavaScript code in a sandboxed QuickJS environment and return the result. Useful for computation, data processing, and algorithmic tasks. |
| Parameters | `code` (string, required): The JavaScript source code to execute |
| | `timeout_seconds` (integer, optional): Maximum execution time in seconds. Default: 30 |
| Required Permissions | None |
| Timeout | Controlled by `timeout_seconds` parameter (max 120 seconds) |
| Returns | The result of the evaluation as a string, or error object |

### Code Execution Model

The `code` parameter is wrapped and executed as follows:

1. If the code defines a function called `main()`, that function is called and its return value is the result.
2. Otherwise, the entire code block is evaluated as a top-level script, and the value of the last expression is the result.
3. String results are returned as-is. Objects/arrays are JSON-serialized. `null`/`undefined` returns empty string.

This allows both simple one-liners and structured multi-function scripts:

**Simple expression:**
```javascript
// code: "2 + 2"
// result: "4"
```

**Function-based:**
```javascript
// code:
function main() {
  const fib = (n) => n <= 1 ? n : fib(n-1) + fib(n-2);
  return fib(10);
}
// result: "55"
```

**Data processing:**
```javascript
// code:
function main() {
  const data = [3, 1, 4, 1, 5, 9, 2, 6];
  const sorted = data.sort((a, b) => a - b);
  const sum = data.reduce((a, b) => a + b, 0);
  return JSON.stringify({ sorted, sum, avg: sum / data.length });
}
// result: '{"sorted":[1,1,2,3,4,5,6,9],"sum":31,"avg":3.875}'
```

### Available Bridge Functions

The code has access to the same bridges as JS-based tools:

| Bridge | Functions | Description |
|--------|-----------|-------------|
| `console` | `log()`, `warn()`, `error()` | Logging (output to Android logcat) |
| `fs` | `readFile()`, `writeFile()`, `listDir()`, etc. | File system access |
| `fetch` | `fetch(url, options)` | HTTP requests |
| `_time` | `now()`, `format()` | Time utilities |
| `lib()` | `lib(name)` | Load JS libraries |

### Output Format

The tool returns the result directly as a string:

- Primitive values (number, boolean, string) are converted to their string representation
- Objects and arrays are JSON-serialized
- `null` or `undefined` returns empty string
- If an error occurs, `ToolResult.error()` is returned with the error message

### User Interaction Flow

```
1. User: "Calculate the compound interest on $10,000 at 5% for 10 years"
2. AI writes JS code to compute compound interest
3. AI calls js_eval(code="function main() { ... }")
4. JsEvalTool:
   a. Passes code to JsExecutionEngine.executeFromSource()
   b. QuickJS evaluates the code in a sandboxed context
   c. Returns the computed result
5. AI receives "16288.95", formats a response for the user
6. Chat shows the js_eval tool call and result
```

## Acceptance Criteria

Must pass (all required):

- [ ] `js_eval` tool is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] Tool accepts a `code` string parameter containing JavaScript source code
- [ ] Code is executed in QuickJS sandbox via existing `JsExecutionEngine`
- [ ] Simple expressions return their evaluated value (e.g., `2+2` returns `"4"`)
- [ ] Code defining a `main()` function calls it and returns the result
- [ ] Objects/arrays are JSON-serialized in the result
- [ ] `timeout_seconds` parameter controls maximum execution time (default: 30s, max: 120s)
- [ ] Memory limits are enforced (16MB heap, 1MB stack -- existing QuickJS limits)
- [ ] Empty or blank `code` returns validation error
- [ ] JS syntax errors return an error with the parse error message
- [ ] JS runtime errors (e.g., undefined variable) return an error with the message
- [ ] Bridge functions (console, fs, fetch, time, lib) are available in the sandbox
- [ ] All Layer 1A tests pass

Optional (nice to have):

- [ ] Console output captured and included in result metadata
- [ ] Ability to pass structured input data as a parameter

## UI/UX Requirements

This feature has no new UI. The tool operates transparently:
- Same tool call display in chat as other tools
- Output shown in the tool result area
- No additional settings screen needed for V1

## Feature Boundary

### Included

- Kotlin `JsEvalTool` implementation wrapping `JsExecutionEngine`
- Support for both expression evaluation and `main()` function calling
- Configurable timeout with clamping to max 120s
- Reuse of all existing QuickJS bridges (console, fs, fetch, time, lib)
- Registration in `ToolModule`

### Not Included (V1)

- Persistent JS context across multiple calls (each call is isolated)
- Custom bridge function injection per-call
- Structured input parameters (beyond the code string)
- Code size limits (QuickJS memory limits are sufficient)
- Code approval UI (user confirmation before execution)
- npm/node module support
- TypeScript support

## Business Rules

1. Each `js_eval` call creates a fresh QuickJS context -- no state persists between calls
2. Default timeout is 30 seconds; maximum allowed timeout is 120 seconds
3. If `timeout_seconds` exceeds 120, it is clamped to 120
4. Memory is limited to 16MB heap and 1MB stack (existing QuickJS engine limits)
5. If code defines a `main()` function, it is called automatically; otherwise the last expression value is returned
6. All existing JS bridges are injected into the context
7. The tool name is `js_eval` to distinguish from pre-defined JS tools

## Non-Functional Requirements

### Performance

- Context creation: < 50ms (QuickJS is lightweight)
- Simple computations: < 10ms
- Bridge injection: < 20ms
- Total overhead per call: < 100ms (excluding code execution time)

### Memory

- QuickJS heap limited to 16MB per execution
- QuickJS stack limited to 1MB per execution
- Context is destroyed after each execution -- no memory leak

### Compatibility

- Works on all supported Android versions (API 26+)
- QuickJS library already included as a dependency
- No additional native libraries required

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **RFC-004 JS execution**: JsExecutionEngine, QuickJS bridges

### Depended On By

- None currently

### External Dependencies

- QuickJS Android library (already a project dependency)

## Error Handling

### Error Scenarios

1. **Empty code**
   - Cause: `code` parameter is empty or blank
   - Handling: Return `ToolResult.error("validation_error", "Parameter 'code' is required and cannot be empty")`

2. **Syntax error**
   - Cause: Invalid JavaScript syntax
   - Handling: Return `ToolResult.error("execution_error", "JS syntax error: <message>")`

3. **Runtime error**
   - Cause: ReferenceError, TypeError, etc. during execution
   - Handling: Return `ToolResult.error("execution_error", "JS runtime error: <message>")`

4. **Timeout**
   - Cause: Code exceeds the timeout limit (e.g., infinite loop)
   - Handling: Return `ToolResult.error("timeout", "Execution timed out after <N>s")`

5. **Memory exceeded**
   - Cause: Code allocates more than 16MB
   - Handling: QuickJS throws an error, returned as `ToolResult.error("execution_error", "...")`

## Test Points

### Functional Tests

- Verify `js_eval` evaluates a simple arithmetic expression and returns the result
- Verify `js_eval` calls `main()` when defined and returns its result
- Verify `js_eval` returns JSON-serialized objects
- Verify `js_eval` returns JSON-serialized arrays
- Verify `js_eval` returns empty string for `null`/`undefined`
- Verify `js_eval` handles string results correctly
- Verify `timeout_seconds` parameter kills long-running code
- Verify empty code returns validation error
- Verify JS syntax errors return appropriate error
- Verify JS runtime errors return appropriate error
- Verify console.log works in the sandbox
- Verify bridge functions (fs, fetch, time) are available

### Edge Cases

- Code that produces a very large string result
- Code with infinite loop (should timeout)
- Code that allocates excessive memory (should hit QuickJS limit)
- Code using async/await with fetch
- Code with Unicode characters
- Code using ES2020+ features supported by QuickJS
- `timeout_seconds` set to 0 or negative value
- `timeout_seconds` set above 120 (should be clamped)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
