# JavaScript Tool Engine

## Feature Information
- **Feature ID**: FEAT-012
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-012 (TBD)

## User Story

**As** a user of OneClaw,
**I want** to extend the AI agent's capabilities by adding custom tools written in JavaScript,
**so that** I can add new tools without modifying the app's Kotlin source code, enabling rapid prototyping, community sharing, and AI-assisted tool creation.

### Typical Scenarios

1. **AI-generated tool**: User asks the AI "create a tool that converts Markdown to plain text". The AI writes a `.js` file and a `.json` metadata file to the tools directory using `write_file`, and the tool becomes immediately available.
2. **Community sharing**: User downloads a JS tool package (`.js` + `.json`) from a forum or GitHub, places it in the tools directory, and the tool appears in the tool list.
3. **Data processing**: User needs a tool that parses CSV data and extracts specific columns. Instead of waiting for a new app release, they add a JS tool that handles it.
4. **API integration**: User creates a JS tool that calls a specific REST API (e.g., a weather service) with custom authentication and response formatting, using the bridged `fetch()` capability.
5. **Compound operations**: User creates a JS tool that reads a file, processes its contents, and writes the result to another file -- combining multiple capabilities in a single tool invocation.

## Feature Description

### Overview

The JavaScript Tool Engine extends FEAT-004 (Tool System) by allowing tools to be defined as JavaScript files executed via an embedded QuickJS runtime. Each JS tool consists of two files: a `.js` file containing the tool logic and a `.json` file defining the tool metadata (name, description, parameter schema). JS tools are loaded from a designated directory, registered into the existing ToolRegistry alongside built-in Kotlin tools, and executed through the same ToolExecutionEngine pipeline.

This feature bridges the gap between the current "built-in tools only" model and a full plugin system, providing an immediate, lightweight extensibility mechanism.

### Architecture Principles

1. **Seamless integration**: JS tools are first-class citizens in the ToolRegistry -- the AI model, Agents, and execution engine treat them identically to built-in Kotlin tools.
2. **Convention over configuration**: Tools are discovered by scanning a directory. File naming convention (`name.js` + `name.json`) is the only requirement.
3. **Direct bridge, not orchestration**: JS scripts access host capabilities (network, file system) through bridged functions injected into the QuickJS runtime, not by calling other tools indirectly.
4. **AI as tool author**: The primary tool creation workflow is asking the AI agent to write tools for you, leveraging the existing `write_file` built-in tool.
5. **Fail-safe**: A buggy JS tool cannot crash the app. Errors are caught and returned as standard `ToolResult.error()`.

### Tool File Structure

Tools live in a designated directory on the device:

```
/sdcard/OneClaw/tools/
  weather_lookup.js        -- tool logic
  weather_lookup.json      -- tool metadata
  csv_parser.js
  csv_parser.json
  markdown_to_text.js
  markdown_to_text.json
```

Alternatively, tools can also be stored in the app's internal storage:

```
{app_internal}/tools/
```

Both directories are scanned. App-internal tools take precedence on name conflict.

### Metadata File Format (`.json`)

Each tool's metadata file follows the existing `ToolDefinition` schema:

```json
{
  "name": "weather_lookup",
  "description": "Look up current weather for a given city using the OpenWeatherMap API",
  "parameters": {
    "properties": {
      "city": {
        "type": "string",
        "description": "City name (e.g., 'Tokyo', 'New York')"
      },
      "units": {
        "type": "string",
        "description": "Temperature units",
        "enum": ["metric", "imperial"],
        "default": "metric"
      }
    },
    "required": ["city"]
  },
  "requiredPermissions": [],
  "timeoutSeconds": 15
}
```

This is the exact same structure as the Kotlin `ToolDefinition` data class, serialized as JSON. The `name` field must match the filename (e.g., `weather_lookup.json` defines name `weather_lookup`).

### JavaScript File Format (`.js`)

Each JS file exports an `execute` function that receives a parameters object and returns a result:

```javascript
// weather_lookup.js

async function execute(params) {
  const city = params.city;
  const units = params.units || "metric";
  const apiKey = params._env?.OPENWEATHER_API_KEY || "";

  const url = `https://api.openweathermap.org/data/2.5/weather?q=${encodeURIComponent(city)}&units=${units}&appid=${apiKey}`;

  const response = await fetch(url);
  if (!response.ok) {
    throw new Error(`API returned ${response.status}: ${response.statusText}`);
  }

  const data = await response.json();
  return `Weather in ${data.name}: ${data.weather[0].description}, temperature: ${data.main.temp}°${units === "metric" ? "C" : "F"}, humidity: ${data.main.humidity}%`;
}
```

Contract:
- The file must define an `execute` function (global scope).
- `execute` receives a single `params` object matching the JSON schema.
- `execute` returns a string (success result) or throws an Error (error result).
- `execute` may be `async` (returns a Promise) to use bridged async APIs like `fetch()`.

### Bridged Host APIs

The QuickJS runtime is augmented with the following host-bridged APIs:

#### Network: `fetch()`
A subset of the Web Fetch API:
```javascript
const response = await fetch(url, {
  method: "GET" | "POST" | "PUT" | "DELETE",
  headers: { "Content-Type": "application/json" },
  body: "string body"
});

response.ok        // boolean
response.status    // number
response.statusText // string
await response.text()  // string
await response.json()  // parsed object
```

Implementation: Delegates to OkHttpClient on the Kotlin side.

#### File System: `fs`
```javascript
const content = fs.readFile("/sdcard/Documents/notes.txt");        // string (UTF-8)
fs.writeFile("/sdcard/Documents/output.txt", "content");           // void
const exists = fs.exists("/sdcard/Documents/notes.txt");           // boolean
```

Implementation: Delegates to Java File I/O on the Kotlin side. Same path restrictions as `ReadFileTool` / `WriteFileTool` (blocked system paths, size limits).

#### Console: `console`
```javascript
console.log("debug info");     // logged to Android Logcat (tag: "JSTool:{tool_name}")
console.warn("warning");
console.error("error");
```

For debugging purposes only. Output is not returned to the AI model.

#### Environment: `params._env`
A read-only object containing user-configured environment variables (stored in app settings). Useful for API keys that JS tools need:
```javascript
const apiKey = params._env?.MY_API_KEY || "";
```

This avoids hardcoding API keys in JS tool files.

### Tool Discovery and Loading

1. **On app startup**: The `JsToolLoader` scans the tools directories for `.json` files.
2. **For each `.json` file**:
   a. Parse and validate the metadata.
   b. Check that a corresponding `.js` file exists.
   c. Create a `JsTool` instance that wraps the metadata and JS file path.
   d. Register the `JsTool` in the `ToolRegistry`.
3. **On conflict**: If a JS tool has the same name as a built-in Kotlin tool, the JS tool is skipped and a warning is logged.
4. **Hot reload** (optional, P2): When files change in the tools directory, re-scan and update the registry. For V1, a manual "Reload tools" action in Settings is sufficient.

### Tool Execution Flow

When the AI model calls a JS tool:

```
1. ToolExecutionEngine receives the tool call (same as any tool)
2. JsTool.execute(parameters) is invoked
3. JsTool creates a QuickJS runtime instance (or reuses a pooled one)
4. Bridge functions (fetch, fs, console) are injected into the JS context
5. The tool's .js file is loaded and evaluated
6. The execute(params) function is called with the parameters
7. If execute returns a string: ToolResult.success(result)
8. If execute throws: ToolResult.error("execution_error", error.message)
9. If execution exceeds timeout: QuickJS context is terminated, ToolResult.error("timeout", ...)
10. QuickJS context is cleaned up
```

### User Interaction Flow

#### Adding a tool via AI
```
1. User: "Create a tool that converts temperature between Celsius and Fahrenheit"
2. AI calls write_file to create /sdcard/OneClaw/tools/temperature_convert.json
3. AI calls write_file to create /sdcard/OneClaw/tools/temperature_convert.js
4. AI informs the user the tool has been created
5. User taps "Reload tools" in Settings (or restarts app)
6. The tool appears in the tool list and can be assigned to Agents
7. User (or AI) can now use the tool: "Convert 100°F to Celsius"
```

#### Adding a tool via file import
```
1. User downloads tool files from an external source
2. User places them in /sdcard/OneClaw/tools/
3. User taps "Reload tools" in Settings (or restarts app)
4. The tool appears in the tool list
```

### Settings UI Addition

A new section in Settings (FEAT-009):
- **JS Tools** section showing:
  - Count of loaded JS tools
  - "Reload tools" button to re-scan the tools directory
  - List of loaded JS tools with name and status (loaded / error)
  - Tap a tool to see its metadata details and any load errors
- **Environment Variables** section:
  - Key-value pairs that are injected as `params._env` into all JS tool executions
  - Used for API keys and configuration that JS tools need
  - Values are stored encrypted (same as API keys, using EncryptedSharedPreferences)

## Acceptance Criteria

Must pass (all required):
- [ ] QuickJS engine is embedded in the app and can execute JavaScript code
- [ ] JS tools are discovered by scanning the tools directory on startup
- [ ] `.json` metadata files are parsed and validated against ToolDefinition schema
- [ ] `.js` files are loaded and executed in the QuickJS runtime
- [ ] The `execute(params)` function contract works (receives params, returns string or throws)
- [ ] JS tools are registered in ToolRegistry alongside built-in Kotlin tools
- [ ] JS tools appear in the Agent tool selection UI identically to built-in tools
- [ ] JS tools execute through the same ToolExecutionEngine pipeline (timeout, error handling)
- [ ] Bridge: `fetch()` works for GET and POST requests
- [ ] Bridge: `fs.readFile()` reads files with same restrictions as ReadFileTool
- [ ] Bridge: `fs.writeFile()` writes files with same restrictions as WriteFileTool
- [ ] Bridge: `console.log/warn/error` output to Logcat
- [ ] Environment variables are injectable via `params._env`
- [ ] JS tool errors are caught and returned as ToolResult.error (app does not crash)
- [ ] JS tool timeout is enforced (QuickJS context is terminated)
- [ ] Name conflicts with built-in tools are handled (JS tool skipped, warning logged)
- [ ] "Reload tools" action in Settings re-scans and updates the registry
- [ ] AI can create JS tools using the existing `write_file` tool

Optional (nice to have):
- [ ] Hot reload: file system watcher auto-detects changes in tools directory
- [ ] Tool validation: "Test tool" button in Settings that runs a tool with sample params
- [ ] JS tool import via share intent (receive `.js` + `.json` files from other apps)

## UI/UX Requirements

### Settings Screen Additions

#### JS Tools Section
- Header: "JavaScript Tools"
- Subtitle: "{N} tools loaded"
- "Reload" button (icon button or text button)
- List of tools: each row shows tool name, description (truncated), and status indicator
  - Green dot: loaded successfully
  - Red dot: load error (tap to see error details)
- Tapping a tool shows a detail dialog with full metadata and the JS file path

#### Environment Variables Section
- Header: "Environment Variables"
- List of key-value pairs with add/edit/delete
- Values are masked by default (like password fields), tap to reveal
- "Add variable" button
- Used for API keys that JS tools reference via `params._env`

### Visual Design
- Follows existing Material 3 + gold/amber accent style
- JS tools are visually indistinguishable from built-in tools in the Agent config tool list (no special badge or indicator -- they are first-class)
- In Settings, the JS tools section uses a subtle code-style monospace font for tool names

## Feature Boundary

### Included
- QuickJS runtime integration
- JS tool discovery from file system (scan directory)
- JSON metadata parsing and validation
- JS `execute()` function invocation
- Host bridge: `fetch()`, `fs.readFile()`, `fs.writeFile()`, `fs.exists()`, `console.*`
- Environment variables for JS tools
- "Reload tools" action in Settings
- JS tools as first-class ToolRegistry citizens

### Not Included
- In-app JavaScript code editor
- Visual tool builder / no-code tool creation
- npm / Node.js module system (no `require()`, no `import` from npm)
- TypeScript support
- Debugger / step-through execution
- Tool versioning or update mechanism
- Tool marketplace / store
- JS-to-JS tool chaining within a single execution (a JS tool calling another JS tool)
- Sandboxed process isolation (JS runs in-process via QuickJS)

## Business Rules

### Tool Rules
1. Tool names must be unique across both built-in and JS tools
2. Built-in Kotlin tools always take precedence on name conflict
3. Tool names must match the filename (e.g., `my_tool.json` must define `"name": "my_tool"`)
4. Tool names follow snake_case convention (validated on load)
5. A tool is only loaded if both `.js` and `.json` files exist and are valid
6. Invalid tools are skipped with a warning (do not block other tools from loading)

### Execution Rules
1. JS tools run on `Dispatchers.IO`, same as Kotlin tools
2. Each JS tool execution gets a fresh (or pooled) QuickJS context
3. The timeout from the `.json` metadata is enforced
4. If `execute()` returns a non-string value, it is converted to string via `JSON.stringify()`
5. If `execute()` returns `undefined` or `null`, the result is an empty string
6. Bridge function errors (e.g., fetch network error) are thrown as JS exceptions that the tool can catch or let propagate

### Security Rules
1. `fs` bridge has the same path restrictions as built-in file tools (blocked system paths)
2. `fs` bridge enforces the same file size limits
3. Environment variable values are stored encrypted
4. JS tools cannot access app-internal storage or databases
5. JS tools cannot access Android APIs directly (only through provided bridges)
6. `fetch()` bridge has the same response size limits as HttpRequestTool

## Non-Functional Requirements

### Performance
- QuickJS engine initialization: < 50ms
- Tool directory scan and loading: < 500ms for 50 tools
- Individual JS tool execution overhead (excluding actual work): < 20ms
- QuickJS context creation: < 10ms
- Memory: QuickJS runtime adds ~2MB to app memory footprint
- Each JS context limited to 16MB heap

### Reliability
- QuickJS crash (native) is caught and does not crash the app
- Infinite loops in JS are terminated by timeout
- Memory exhaustion in JS context triggers an error, not an app crash

### Security
- No `eval()` of arbitrary code outside of tool execution context
- JS tools cannot escape the QuickJS sandbox to access JVM/Kotlin objects
- Bridge functions are the only communication channel between JS and the host

### Compatibility
- QuickJS library: use quickjs-android or similar actively maintained wrapper
- Minimum API level: same as app (API 26)
- ABI support: arm64-v8a, armeabi-v7a, x86_64 (for emulator)

## Dependencies

### Depends On
- **FEAT-004 (Tool System)**: JS tools integrate into the existing ToolRegistry and ToolExecutionEngine
- **FEAT-009 (Settings)**: UI for JS tool management and environment variables
- **QuickJS native library**: Third-party dependency for JavaScript execution

### Depended On By
- **FEAT-002 (Agent Management)**: Agents can select JS tools in their tool configuration
- **FEAT-001 (Chat Interaction)**: JS tool calls are displayed in chat like any other tool

### External Dependencies
- QuickJS Android library (e.g., `aspect-build/aspect-quickjs-android`, `niclas-niclas/niclas-niclas-niclas-niclas` or similar)

## Error Handling

### Error Scenarios

1. **Missing `.js` file**
   - Cause: `.json` metadata exists but corresponding `.js` file is missing
   - Handling: Skip tool, log warning, show error status in Settings

2. **Invalid JSON metadata**
   - Cause: `.json` file has syntax errors or missing required fields
   - Handling: Skip tool, log warning, show error status in Settings with parse error details

3. **JS syntax error**
   - Cause: `.js` file contains invalid JavaScript
   - Handling: Tool loads but fails on first execution with a clear error message

4. **Missing `execute` function**
   - Cause: `.js` file evaluates successfully but does not define `execute`
   - Handling: Return `ToolResult.error("execution_error", "JS tool does not define an execute() function")`

5. **JS runtime error**
   - Cause: Unhandled exception in `execute()` (TypeError, ReferenceError, etc.)
   - Handling: Catch, return `ToolResult.error("execution_error", error.message)`

6. **fetch() network error**
   - Cause: Network unreachable, DNS failure, timeout
   - Handling: Throws a JS exception that propagates as an execution error if uncaught

7. **fs bridge permission denied**
   - Cause: Attempting to access a blocked path
   - Handling: Throws a JS exception: "Access denied: path is restricted"

8. **Timeout**
   - Cause: JS execution exceeds the configured timeout
   - Handling: QuickJS context is interrupted/terminated, `ToolResult.error("timeout", ...)`

9. **Memory exhaustion**
   - Cause: JS code allocates too much memory (over 16MB heap limit)
   - Handling: QuickJS triggers OOM, caught as execution error

10. **Name conflict with built-in tool**
    - Cause: JS tool has the same name as a Kotlin built-in tool
    - Handling: JS tool is skipped, warning logged

## Test Points

### Functional Tests
- Verify QuickJS engine initializes and can execute basic JavaScript
- Verify tool directory scanning finds `.js` + `.json` file pairs
- Verify JSON metadata is correctly parsed into ToolDefinition
- Verify `execute(params)` is called with correct parameters
- Verify successful return value is wrapped in ToolResult.success()
- Verify thrown errors are wrapped in ToolResult.error()
- Verify `fetch()` bridge makes HTTP requests correctly (GET, POST)
- Verify `fs.readFile()` reads files correctly
- Verify `fs.writeFile()` writes files correctly
- Verify `fs.exists()` returns correct boolean
- Verify `console.log()` outputs to Logcat
- Verify `params._env` contains configured environment variables
- Verify timeout enforcement terminates JS execution
- Verify name conflict handling (built-in wins)
- Verify "Reload tools" re-scans directory and updates registry
- Verify JS tool appears in Agent tool selection
- Verify AI can create a JS tool via write_file and it loads after reload

### Edge Cases
- Tool directory does not exist (should be created automatically)
- Empty tools directory (no JS tools loaded, no error)
- `.json` file without matching `.js` file
- `.js` file without matching `.json` file
- JS file with syntax errors
- JS file without `execute` function
- `execute` returning non-string (object, number, array)
- `execute` returning null/undefined
- Infinite loop in JS (timeout must trigger)
- Very large string returned from execute
- fetch() called with invalid URL
- fs operations on non-existent files
- Multiple tools loaded, one invalid (others should still load)
- Unicode content in JS files and parameters

### Performance Tests
- QuickJS initialization time
- Tool loading time with 1, 10, 50 tools
- JS tool execution latency overhead
- Memory footprint with QuickJS loaded

### Security Tests
- Verify fs bridge blocks system paths
- Verify JS cannot access app-internal storage
- Verify JS cannot escape QuickJS sandbox
- Verify environment variables are encrypted at rest

## Data Requirements

### Tools Directory
| Item | Type | Required | Description |
|------|------|----------|-------------|
| `{name}.json` | File | Yes | Tool metadata in ToolDefinition JSON format |
| `{name}.js` | File | Yes | Tool logic with `execute(params)` function |

### Environment Variables (stored in EncryptedSharedPreferences)
| Item | Type | Required | Description |
|------|------|----------|-------------|
| Key | String | Yes | Variable name (e.g., `OPENWEATHER_API_KEY`) |
| Value | String | Yes | Variable value (encrypted at rest) |

### No new Room entities are needed
JS tool metadata is read from files, not stored in the database. Environment variables are stored in EncryptedSharedPreferences.

## Open Questions

- [ ] Which QuickJS Android library to use? Need to evaluate options for stability, maintenance, and API ergonomics.
- [ ] Should JS tool contexts be pooled for performance, or created fresh each execution for isolation?
- [ ] Should the `fetch()` bridge support streaming responses, or only buffered?
- [ ] Should there be a maximum number of JS tools that can be loaded?
- [ ] Should environment variables be per-tool or global?

## Reference

- [QuickJS JavaScript Engine](https://bellard.org/quickjs/)
- [FEAT-004 Tool System PRD](FEAT-004-tool-system.md)
- [RFC-004 Tool System](../../rfc/features/RFC-004-tool-system.md)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
