# Tool System

## Feature Information
- **Feature ID**: FEAT-004
- **Created**: 2026-02-26
- **Last Updated**: 2026-02-26
- **Status**: Draft
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: [RFC-004 (Tool System)](../../rfc/features/RFC-004-tool-system.md)

## User Story

**As** a user of OneClawShadow,
**I want** the AI agent to be able to call tools to perform actions (read files, make HTTP requests, check the time, etc.),
**so that** the AI can do more than just chat -- it can take real actions on my behalf.

### Typical Scenarios
1. User asks "What time is it?" -- the AI calls the `get_current_time` tool and responds with the current time.
2. User asks "Read my notes file" -- the AI calls `read_file` with the file path, the tool reads the file from local storage, and the AI summarizes the contents.
3. User asks "Fetch the latest Bitcoin price" -- the AI calls `http_request` to query a public API and returns the result.
4. User asks the AI to perform a multi-step task that requires chaining multiple tool calls.
5. A tool requires Android storage permission -- the system permission dialog appears, user grants it, and the tool proceeds.

## Feature Description

### Overview
The Tool System is the extensible framework that allows AI models to perform actions beyond text generation. It defines a standard interface for registering, invoking, and returning results from tools. The system handles the complete tool call loop: model requests a tool call -> tool executes -> result returns to model -> model continues. The architectural priority is a clean, well-defined, extensible interface -- making it easy to add new tools over time. V1 ships with a small set of built-in starter tools.

### Architecture Principles

1. **Interface first**: The tool registration and invocation protocol is the most important design decision. It must be clean, typed, and versioned.
2. **Provider-agnostic tool definitions**: Tools are defined once using a unified format (based on JSON Schema, aligned with OpenAI function calling format). The provider adapter layer handles format conversion for different API providers (OpenAI, Anthropic, Gemini).
3. **Extensibility over completeness**: Better to ship few tools with a great interface than many tools with a rigid system.
4. **Automatic execution by default**: Tools run without user confirmation, except when Android system permissions are required.

### Tool Definition Format

Each tool is defined with the following structure, based on JSON Schema (aligned with OpenAI function calling format):

```json
{
  "name": "read_file",
  "description": "Read the contents of a file from local storage",
  "parameters": {
    "type": "object",
    "properties": {
      "path": {
        "type": "string",
        "description": "The absolute file path to read"
      }
    },
    "required": ["path"]
  }
}
```

This format is the unified internal representation. When sending tool definitions to different providers, the provider adapter layer converts to the provider's specific format:
- **OpenAI**: Used as-is (this is the native format)
- **Anthropic**: Minor structural adjustments to match Anthropic's tool use format
- **Gemini**: Converted to Gemini's function declaration format
- **Custom**: Depends on the protocol compatibility type selected by the user

### Tool Registration Interface

Each tool must implement:

| Component | Description |
|-----------|-------------|
| Name | Unique identifier (e.g., `read_file`). Follows snake_case convention. |
| Description | Human-readable description of what the tool does (one sentence). Used in AI prompt and shown to users in Agent config. |
| Parameter Schema | JSON Schema defining the input parameters the tool accepts. |
| Required Permissions | List of Android permissions this tool needs (if any). E.g., `READ_EXTERNAL_STORAGE`, `ACCESS_FINE_LOCATION`. |
| Timeout | Maximum execution time in seconds. Default: 30 seconds. Can be overridden per tool. |
| Execute Function | The actual implementation that receives parameters and returns a result. |

### Tool Execution Engine

The execution engine manages the complete tool call lifecycle:

#### Execution Flow
```
1. Model response includes a tool call request (tool name + parameters)
2. Engine validates the tool name exists and is available to the current Agent
3. Engine validates parameters against the tool's schema
4. Engine checks if the tool requires Android permissions
   - If permissions needed and not granted: trigger Android permission request dialog
   - If permissions denied by user: return permission error to model
5. Engine executes the tool on a background thread (not the main/UI thread)
6. Engine applies timeout -- if execution exceeds the tool's timeout, abort and return timeout error
7. Engine captures the result (success or error)
8. Engine returns the result to the model as a tool result message
9. Model processes the result and continues (may make more tool calls or produce final response)
```

#### Execution Environment
- Tools execute in the app process on a background thread (not the main/UI thread)
- UI remains responsive during tool execution
- If a tool crashes (unhandled exception), the error is caught and returned to the model as a tool error result -- the app does not crash

#### Timeout Handling
- Each tool has a configurable timeout (default: 30 seconds)
- Recommended defaults by tool type:
  - Simple queries (get time, etc.): 5 seconds
  - Local file operations: 10 seconds
  - HTTP/network requests: 30 seconds
- When timeout is reached:
  - Execution is aborted
  - A timeout error is returned to the model
  - The model can decide how to handle it (retry, inform user, etc.)

#### Multi-Tool and Chained Calls
- A single model response may request multiple tool calls (parallel or sequential, depending on the model)
- The engine executes them and returns all results to the model
- The model may then make additional tool calls based on results (chaining)
- This loop continues until the model produces a final text response with no tool calls
- There is no hard limit on the number of tool call rounds, but the token/cost tracking (FEAT-006) provides visibility

### Android Permission Handling

Some tools require Android system permissions. The tool system handles this transparently:

1. Each tool declares its required permissions at registration time
2. Before executing a tool, the engine checks if required permissions are granted
3. If not granted:
   - The standard Android permission request dialog is shown to the user
   - If user grants: tool execution proceeds
   - If user denies: a permission-denied error is returned to the model
4. Permission grants are remembered by the Android system (standard behavior)
5. If a permission was previously denied with "Don't ask again", the engine returns an error suggesting the user enable the permission in system settings

### Built-in Tools (V1)

V1 ships with the following starter tools:

#### 1. `get_current_time`
- **Description**: Get the current date and time
- **Parameters**: 
  - `timezone` (string, optional): Timezone identifier (e.g., "America/New_York"). Defaults to device timezone.
  - `format` (string, optional): Output format (e.g., "ISO8601", "human_readable"). Defaults to ISO8601.
- **Required Permissions**: None
- **Timeout**: 5 seconds
- **Returns**: Current date and time string

#### 2. `read_file`
- **Description**: Read the contents of a file from local storage
- **Parameters**:
  - `path` (string, required): The absolute file path to read
  - `encoding` (string, optional): File encoding. Defaults to "UTF-8".
- **Required Permissions**: `READ_EXTERNAL_STORAGE` (or `READ_MEDIA_*` on Android 13+)
- **Timeout**: 10 seconds
- **Returns**: File contents as a string. For binary files, returns an error suggesting a different approach.

#### 3. `write_file`
- **Description**: Write contents to a file on local storage
- **Parameters**:
  - `path` (string, required): The absolute file path to write
  - `content` (string, required): The content to write
  - `mode` (string, optional): Write mode -- "overwrite" (default) or "append"
- **Required Permissions**: `WRITE_EXTERNAL_STORAGE` (or appropriate permission on Android 13+)
- **Timeout**: 10 seconds
- **Returns**: Confirmation with file path and bytes written

#### 4. `http_request`
- **Description**: Make an HTTP request to a URL
- **Parameters**:
  - `url` (string, required): The URL to request
  - `method` (string, optional): HTTP method -- "GET" (default), "POST", "PUT", "DELETE"
  - `headers` (object, optional): Key-value pairs of HTTP headers
  - `body` (string, optional): Request body (for POST/PUT)
- **Required Permissions**: `INTERNET` (granted by default on Android)
- **Timeout**: 30 seconds
- **Returns**: Response status code, headers, and body (truncated if very large)

### Tool Result Format

Tool results returned to the model follow a consistent format:

**Success:**
```json
{
  "status": "success",
  "result": "The actual result data here"
}
```

**Error:**
```json
{
  "status": "error",
  "error_type": "timeout | permission_denied | execution_error | validation_error",
  "message": "Human-readable error description"
}
```

This format is consistent regardless of which tool is called. The provider adapter layer converts this to the appropriate tool result format for each provider.

### Tool Visibility in Agent Configuration

When configuring an Agent's tool set (FEAT-002), the user sees:
- Tool name and description for each available tool
- Checkboxes to enable/disable each tool for the Agent
- Example display:
  ```
  [x] get_current_time - Get the current date and time
  [x] read_file - Read the contents of a file from local storage
  [x] write_file - Write contents to a file on local storage
  [ ] http_request - Make an HTTP request to a URL
  ```
- Tools that require special permissions show a permission icon/indicator
- No parameter details are shown at this level (too verbose for selection UI)

## Acceptance Criteria

Must pass (all required):
- [ ] Tool definition format based on JSON Schema (aligned with OpenAI function calling format)
- [ ] Tool definitions are provider-agnostic; provider adapter handles format conversion
- [ ] Tools execute on a background thread, not blocking the UI
- [ ] Tool execution has configurable per-tool timeouts
- [ ] Timeout errors are returned to the model as tool error results
- [ ] Tool crashes are caught and returned as error results (app does not crash)
- [ ] Android permissions are checked before tool execution
- [ ] Standard Android permission dialog is shown when permissions are needed
- [ ] Permission denial is returned to the model as a permission error
- [ ] Multi-tool calls in a single model response are supported
- [ ] Chained tool calls (model makes additional calls based on results) work correctly
- [ ] Built-in tool: `get_current_time` works correctly
- [ ] Built-in tool: `read_file` reads local files correctly
- [ ] Built-in tool: `write_file` writes local files correctly
- [ ] Built-in tool: `http_request` makes HTTP requests correctly
- [ ] Tool results follow a consistent success/error format
- [ ] Tools are selectable per-Agent in Agent configuration
- [ ] Agent config shows tool name and description for selection

Optional (nice to have for V1):
- [ ] Tool execution duration is tracked and displayed
- [ ] Tool execution history/log viewable by user

## UI/UX Requirements

### Tool Display in Chat (handled by FEAT-001)
Tool call display in the chat is defined in FEAT-001 (compact and detailed modes). The Tool System provides the data; FEAT-001 handles the rendering.

### Tool Selection in Agent Config (handled by FEAT-002)
Tool selection UI in Agent configuration is defined in FEAT-002. The Tool System provides the list of available tools with their names and descriptions.

### Permission Dialogs
- Standard Android permission request dialogs (system-provided, not custom)
- If a permission is permanently denied, show a message guiding the user to system settings

## Feature Boundary

### Included
- Tool definition format and registration interface
- Tool execution engine (background thread, timeout, error handling)
- Provider-agnostic tool definitions with provider adapter conversion
- Android permission handling for tools
- Tool result format (success/error)
- Built-in tools: `get_current_time`, `read_file`, `write_file`, `http_request`
- Multi-tool and chained tool call support
- Tool visibility in Agent configuration (name + description)

### Not Included (V1)
- User-defined custom tools (users cannot create new tools, only select from built-in ones)
- Tool marketplace or plugin system
- Sandboxed tool execution (separate process isolation)
- Tool execution approval/confirmation flow (all tools auto-execute except permission dialogs)
- Tool-specific UI (e.g., a file picker for `read_file`)
- Streaming tool results (results are returned as a complete response)
- Tool versioning

## Business Rules

### Tool Rules
1. Every tool must have a unique name (enforced at registration)
2. Tool names follow snake_case convention
3. Tools execute automatically without user confirmation
4. The only user-facing interruption is Android system permission dialogs
5. A tool that fails returns an error to the model -- the model decides how to proceed
6. The engine never silently drops a tool call; every call gets a result (success or error)

### Execution Rules
1. Tools always execute on a background thread
2. If a tool exceeds its timeout, execution is aborted and a timeout error is returned
3. If a tool throws an unhandled exception, it is caught and an execution error is returned
4. Multiple tool calls in a single response are executed (potentially in parallel if the model requests it)
5. There is no hard limit on tool call chain depth, but cost tracking provides visibility

### Permission Rules
1. Tools declare required permissions at registration
2. Permissions are checked before every execution (not just the first time)
3. If a permission is not granted, the standard Android dialog is shown
4. If denied, a clear error is returned to the model
5. The tool system never bypasses Android's permission model

## Non-Functional Requirements

### Performance
- Tool registration (at app startup) completes in < 100ms for all built-in tools
- Tool execution overhead (excluding actual work) < 10ms
- Tool result serialization < 10ms
- UI remains responsive (60fps) during tool execution

### Reliability
- Tool crashes never crash the app
- Network tool failures (timeout, DNS, etc.) return clear errors
- File tool failures (permission, not found, etc.) return clear errors

### Security
- File tools only access user-accessible storage (not app-internal files or system files)
- HTTP tool respects HTTPS; no restriction on HTTP for user-intended requests
- Tool results may contain sensitive data -- they are stored in the session history with the same security as other messages

## Dependencies

### Depends On
- Android system APIs (permissions, file system, network)

### Depended On By
- **FEAT-001 (Chat Interaction)**: Displays tool call process and results in chat
- **FEAT-002 (Agent Management)**: Agents configure which tools are available
- **FEAT-003 (Model/Provider Management)**: Provider adapter converts tool definitions to provider-specific format

## Error Handling

### Error Scenarios

1. **Tool not found**
   - Cause: Model requests a tool that doesn't exist or isn't available to the current Agent
   - Handling: Return error to model: "Tool [name] is not available"
   - The model can inform the user or try a different approach

2. **Parameter validation failure**
   - Cause: Model provides invalid parameters (wrong type, missing required field)
   - Handling: Return validation error to model with details
   - The model can correct and retry

3. **Permission denied**
   - Cause: User denies an Android permission required by the tool
   - Handling: Return permission error to model: "Permission [X] was denied by the user"
   - The model can inform the user

4. **Execution timeout**
   - Cause: Tool takes longer than its configured timeout
   - Handling: Abort execution, return timeout error to model
   - The model can retry or inform the user

5. **Tool crash (unhandled exception)**
   - Cause: Bug in tool implementation
   - Handling: Catch exception, return execution error to model with error message
   - App continues running normally

6. **File not found**
   - Cause: `read_file` called with non-existent path
   - Handling: Return error to model: "File not found: [path]"

7. **Network error in HTTP tool**
   - Cause: DNS failure, connection timeout, server error
   - Handling: Return error to model with HTTP status code or network error details

## Future Improvements

- [ ] **Sandboxed execution**: Run tools in a separate process for isolation (tool crash cannot affect app)
- [ ] **User-defined custom tools**: Allow users to create their own tools (e.g., through a scripting interface or plugin system)
- [ ] **Tool marketplace / plugins**: Community-contributed tools that can be installed
- [ ] **Tool-specific UI**: Rich UI for certain tools (e.g., file picker for `read_file`, visual display for image tools)
- [ ] **Streaming tool results**: Return partial results as a tool executes (useful for long-running tools)
- [ ] **Tool execution approval mode**: Optional setting to require user confirmation before executing certain tools
- [ ] **More built-in tools**: Location, contacts, calendar, clipboard, notifications, device info, etc.
- [ ] **Tool versioning**: Version the tool interface for backward compatibility

## Test Points

### Functional Tests
- Verify `get_current_time` returns correct time in default and specified timezones
- Verify `read_file` reads file contents correctly
- Verify `read_file` returns error for non-existent file
- Verify `write_file` writes content correctly (overwrite and append modes)
- Verify `http_request` makes GET/POST requests correctly
- Verify `http_request` handles various HTTP status codes
- Verify tool timeout aborts execution and returns error
- Verify tool crash is caught and returns error (app stays alive)
- Verify Android permission dialog appears when needed
- Verify permission denial returns error to model
- Verify multi-tool calls execute correctly
- Verify chained tool calls work (result of tool A triggers tool B)
- Verify tool definitions convert correctly for each provider (OpenAI, Anthropic, Gemini)
- Verify unavailable tool returns "not available" error
- Verify invalid parameters return validation error

### Performance Tests
- Tool registration time at app startup
- Tool execution overhead measurement
- UI responsiveness during tool execution (frame rate)
- Concurrent tool execution performance

### Security Tests
- Verify file tools cannot access app-internal storage
- Verify tool results are stored with same security as messages
- Verify tools respect Android permission model

### Edge Cases
- Tool called with empty parameters
- Tool called with extremely large parameters
- HTTP request to unreachable host
- HTTP request returning very large response body
- File read on a very large file
- File write to a read-only path
- Multiple tool calls requested simultaneously
- Tool call chain with 10+ rounds
- Permission requested while app is in background

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-26 | 0.1 | Initial version | - |
| 2026-02-27 | 0.2 | Added RFC-004 reference | - |
