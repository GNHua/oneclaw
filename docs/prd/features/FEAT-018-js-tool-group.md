# JavaScript Tool Group

## Feature Information
- **Feature ID**: FEAT-018
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Priority**: P2 (Nice to Have)
- **Owner**: TBD
- **Related RFC**: RFC-018 (pending)

## User Story

**As** a developer or power user of OneClaw,
**I want to** define multiple related tools in a single JavaScript file with a shared JSON manifest,
**so that** I can group tools by service or domain (e.g., Google Drive, Gmail), share helper code within the group, and reduce file proliferation as the tool ecosystem grows.

### Typical Scenarios

1. A developer creates a Google Drive integration. Instead of 6 separate file pairs (12 files), they write one `google_drive.js` containing `listFiles`, `readFile`, `writeFile`, `deleteFile`, `search`, `shareFile` and one `google_drive.json` array manifest defining all 6 tools. Each tool appears individually in the ToolRegistry with a clear name and parameter schema.
2. A user downloads a "GitHub Tools" package from a forum -- a single `.js` + `.json` pair that provides `github_list_repos`, `github_create_issue`, `github_read_issue`, and `github_close_issue`. They drop the two files into the tools directory and all 4 tools appear immediately.
3. The AI agent sees `google_drive_list_files`, `google_drive_read_file`, etc. as distinct tools, each with its own description and parameters -- it uses them exactly as it would any single-file tool, with no awareness that they come from a group.
4. A power user writes a utility group `text_utils.js` containing `word_count`, `regex_extract`, and `base64_encode` -- three small tools that share common string helper functions.
5. An existing single-tool JS file (e.g., `weather_lookup.js` + `weather_lookup.json`) continues to work exactly as before with no changes required.

## Feature Description

### Overview

FEAT-018 extends the JS Tool Engine (FEAT-012) and JS Tool Migration (FEAT-015) to support multi-tool JavaScript files. A single `.js` file can define multiple named functions, each corresponding to a separate tool. The accompanying `.json` manifest is an array of tool definitions, each specifying which JS function to call. Individual tools are registered into the ToolRegistry as independent entries -- the grouping is purely a file-level organizational concept.

### Core Concept: Tool Group = One JS + One JSON Array

A tool group consists of two files:

**`google_drive.json`** (array format):
```json
[
  {
    "name": "google_drive_list_files",
    "description": "List files in a Google Drive folder",
    "function": "listFiles",
    "parameters": {
      "properties": {
        "folder_id": {
          "type": "string",
          "description": "Google Drive folder ID. Use 'root' for the root folder."
        }
      },
      "required": []
    }
  },
  {
    "name": "google_drive_read_file",
    "description": "Read the content of a file from Google Drive",
    "function": "readFile",
    "parameters": {
      "properties": {
        "file_id": {
          "type": "string",
          "description": "The Google Drive file ID to read"
        }
      },
      "required": ["file_id"]
    }
  },
  {
    "name": "google_drive_upload_file",
    "description": "Upload a local file to Google Drive",
    "function": "uploadFile",
    "parameters": {
      "properties": {
        "local_path": {
          "type": "string",
          "description": "Absolute path of the local file to upload"
        },
        "folder_id": {
          "type": "string",
          "description": "Target folder ID in Google Drive. Defaults to root.",
          "default": "root"
        }
      },
      "required": ["local_path"]
    }
  }
]
```

**`google_drive.js`**:
```javascript
// Shared helpers -- not exposed as tools
async function _getAuthHeaders(env) {
    var token = env.GOOGLE_DRIVE_TOKEN;
    if (!token) throw new Error("GOOGLE_DRIVE_TOKEN not set in environment variables");
    return { "Authorization": "Bearer " + token };
}

function _driveApiUrl(path) {
    return "https://www.googleapis.com/drive/v3" + path;
}

// Tool functions -- each corresponds to a JSON entry via "function" field
async function listFiles(params) {
    var headers = await _getAuthHeaders(params._env);
    var folderId = params.folder_id || "root";
    var url = _driveApiUrl("/files?q='" + folderId + "'+in+parents&fields=files(id,name,mimeType,size)");
    var response = await fetch(url, { headers: headers });
    return await response.json();
}

async function readFile(params) {
    var headers = await _getAuthHeaders(params._env);
    var url = _driveApiUrl("/files/" + params.file_id + "?alt=media");
    var response = await fetch(url, { headers: headers });
    return await response.text();
}

async function uploadFile(params) {
    var headers = await _getAuthHeaders(params._env);
    var content = fs.readFile(params.local_path);
    // ... upload logic
}
```

### Backward Compatibility

The change is fully backward-compatible. Detection is based on the JSON content:

| JSON content | Behavior |
|-------------|----------|
| Object `{ "name": ... }` | Single-tool mode (existing). Calls `execute(params)` in JS. |
| Array `[ { "name": ... }, ... ]` | Group mode (new). Each entry specifies a `"function"` field. Calls that named function. |

Existing single-tool files require zero changes. A single-tool file without a `"function"` field continues to use the `execute(params)` convention.

### Function Dispatch

When a tool from a group is invoked:

1. `JsExecutionEngine` receives the tool name and the JS source
2. The wrapper code calls the function specified in the tool's `"function"` field instead of the hardcoded `execute()`
3. The JS function receives the same `params` object (including `_env`) as single-tool mode

```
AI calls tool "google_drive_read_file"
    -> ToolRegistry lookup -> JsTool (jsSource, functionName="readFile")
    -> JsExecutionEngine wrapper: readFile(params)
    -> QuickJS executes readFile() from google_drive.js
```

### Naming Convention

Tool names within a group should follow a consistent prefix convention:

```
{service}_{operation}
```

Examples:
- `google_drive_list_files`, `google_drive_read_file`, `google_drive_upload_file`
- `gmail_list_messages`, `gmail_send_message`, `gmail_read_message`
- `github_list_repos`, `github_create_issue`

This convention is recommended but not enforced -- tools within a group can have any valid tool name.

### Tool Group in Settings UI

The Settings UI (JS Tools section) should visually group tools from the same file:

```
JavaScript Tools (8 tools loaded)

  google_drive.js (3 tools)
    google_drive_list_files   List files in a Google Drive folder
    google_drive_read_file    Read the content of a file from Google Drive
    google_drive_upload_file  Upload a local file to Google Drive

  gmail.js (2 tools)
    gmail_list_messages       List recent Gmail messages
    gmail_send_message        Send an email via Gmail

  weather_lookup.js (1 tool)
    weather_lookup            Look up current weather for a city
```

### Built-in Tool Groups

Existing built-in tools from FEAT-015 can optionally be reorganized into groups in a future iteration:

```
assets/js/tools/
  file_tools.json    -> [read_file, write_file]
  file_tools.js
  http_tools.json    -> [http_request, webfetch]
  http_tools.js
  time_tools.json    -> [get_current_time]
  time_tools.js
```

This reorganization is optional and not part of FEAT-018 V1. The primary goal is to support the group format for new tool development (e.g., Google Workspace integrations).

## Acceptance Criteria

Must pass (all required):

- [ ] JSON manifest supports array format with multiple tool definitions
- [ ] Each tool definition in the array includes a `"function"` field specifying the JS function name
- [ ] Tools from a group are registered as individual entries in ToolRegistry
- [ ] Executing a group tool calls the correct named function in the JS file
- [ ] Named JS functions receive the same `params` object (including `_env`) as single-tool `execute()`
- [ ] Shared helper functions (prefixed with `_`) in the JS file are accessible to all tool functions but not exposed as tools
- [ ] Existing single-tool JSON format (object, not array) continues to work unchanged
- [ ] Existing single-tool JS files with `execute(params)` continue to work unchanged
- [ ] A group tool can use `lib()`, `fetch()`, `fs`, `console`, `_time()` -- all bridges work identically
- [ ] Tool names within a group must be unique across the entire ToolRegistry (same rules as single tools)
- [ ] If any tool definition in a group array is invalid, only that tool is skipped; other valid tools in the group are still loaded

Optional (nice to have for V1):

- [ ] Settings UI groups tools from the same source file visually
- [ ] Tool load error messages indicate both the file name and the specific tool name that failed
- [ ] Reorganize existing built-in tools into groups (file_tools, http_tools, time_tools)

## UI/UX Requirements

### Settings: JS Tools Section

- Tools from the same JS file are visually grouped under the filename
- Each group shows: filename, tool count, expandable list of individual tools
- Individual tools within a group show: tool name, description (same as current)
- Single-tool files appear as a group of 1 (consistent display)
- Error display: if a specific tool in a group fails to load, show the error under the group with the tool name

### No Other UI Changes

- The AI model sees individual tools -- no concept of "group" is exposed to the model
- Tool calls in chat display identically to current behavior
- Agent tool selection treats group tools as individual tools

## Feature Boundary

### Included

- Array format for `.json` manifest files
- `"function"` field in tool definitions for named function dispatch
- Detection logic: array → group mode, object → single-tool mode
- `JsTool` support for `functionName` parameter
- `JsExecutionEngine` support for calling named functions
- `JsToolLoader` support for parsing array manifests and creating multiple `JsTool` instances per file
- Partial load on error: skip invalid entries, load valid ones

### Not Included (V1)

- Reorganizing existing built-in tools into groups (optional future work)
- Group-level permissions (permissions remain per-tool via `requiredPermissions`)
- Group-level timeout (timeout remains per-tool)
- Group-level enable/disable toggle in UI
- Tool dependency declaration within a group
- Automatic tool name prefix generation from filename
- Nested groups or sub-groups

## Business Rules

1. A `.json` file containing a JSON array is treated as a group manifest; a JSON object is treated as a single-tool manifest
2. Each entry in a group array must have a unique `"name"` across the entire ToolRegistry
3. Each entry in a group array must have a `"function"` field (string) that matches a function name defined in the corresponding `.js` file
4. If `"function"` is missing from a group entry, the tool is skipped with an error
5. Tool names follow the same validation rules as single tools: `^[a-z][a-z0-9_]*$`
6. The `"function"` field is not restricted to snake_case -- it follows JavaScript naming conventions (camelCase recommended)
7. A group can contain 1 to 50 tools (practical limit to prevent abuse)
8. Helper functions in the JS file (those not referenced by any `"function"` field) are not registered as tools
9. Environment variables (`_env`) are shared across all tools in a group (same `EnvironmentVariableStore`)

## Non-Functional Requirements

### Performance

- Loading a group of N tools should take roughly the same time as loading N single tools (no significant overhead)
- JS source is shared in memory for all tools in a group (not duplicated per tool)
- Group manifest parsing adds < 10ms per group

### Compatibility

- Fully backward-compatible with single-tool format
- No changes required to existing single-tool `.js` or `.json` files
- No changes to the AI model's tool calling interface

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: ToolRegistry, ToolExecutionEngine
- **FEAT-012 (JavaScript Tool Engine)**: JsTool, JsToolLoader, JsExecutionEngine, bridges
- **FEAT-015 (JS Tool Migration)**: Built-in JS tools, LibraryBridge, asset-based tool loading

### Depended On By

- No other features currently depend on FEAT-018

### External Dependencies

- None

## Error Handling

### Error Scenarios

1. **Missing `"function"` field in group entry**
   - Cause: Group JSON array entry does not have a `"function"` field
   - Handling: Skip this entry with error: "Tool '[name]' in group '[filename]' missing required 'function' field"
   - Other tools in the group are still loaded

2. **JS function not found at runtime**
   - Cause: `"function": "listFiles"` specified but `listFiles` is not defined in the `.js` file
   - Handling: Tool execution returns `ToolResult.error("execution_error", "Function 'listFiles' is not defined")`

3. **Duplicate tool name in group**
   - Cause: Two entries in the same group array have the same `"name"`
   - Handling: First wins; second is skipped with error: "Duplicate tool name '[name]' in group '[filename]'"

4. **Duplicate tool name across groups/files**
   - Cause: A tool name in a group conflicts with a tool in another file or a built-in
   - Handling: Same as current `registerTools()` behavior -- `allowOverride` determines skip or replace

5. **Invalid JSON array entry**
   - Cause: Missing `"name"`, missing `"description"`, or invalid parameter schema
   - Handling: Skip entry with descriptive error; continue loading other entries

6. **Empty array**
   - Cause: JSON file contains `[]`
   - Handling: Log warning "Empty tool group in '[filename]'", no tools registered

## Future Improvements

- [ ] **Reorganize built-in tools into groups**: Move existing built-in tools from 5 separate files to 2-3 group files
- [ ] **Group-level enable/disable**: Toggle all tools in a group on/off from Settings
- [ ] **Auto-prefix tool names**: Option to auto-prefix tool names with the group filename (e.g., `google_drive_` prefix for all tools in `google_drive.js`)
- [ ] **Group metadata**: Add group-level fields to the manifest: `group_name`, `group_description`, `group_version`, `author`
- [ ] **AI group awareness**: Inject group descriptions into the system prompt so the AI can recommend tools by service name

## Test Points

### Functional Tests

- Verify group JSON (array format) is parsed correctly and creates multiple JsTool instances
- Verify each tool in a group calls the correct named function
- Verify named functions receive the same params (including `_env`) as single-tool execute()
- Verify shared helper functions in the JS file are accessible to all tool functions
- Verify existing single-tool JSON (object format) still works unchanged
- Verify single-tool JS files with `execute(params)` still work unchanged
- Verify all bridges (`lib()`, `fetch()`, `fs`, `console`, `_time()`) work inside group tool functions
- Verify partial load: invalid entry is skipped, valid entries are loaded
- Verify duplicate name within a group: first wins, second skipped with error
- Verify duplicate name across files follows `allowOverride` policy
- Verify tool names from groups appear as individual tools in ToolRegistry
- Verify `"function"` field is required for group entries
- Verify missing function at runtime returns clear error

### Edge Cases

- Group with a single tool (array of 1) -- should work identically to single-tool format
- Group with 50 tools (maximum)
- Tool function name that is a JS reserved word (e.g., `delete`)
- Group entry with `"function": "execute"` (valid -- it's just a function name)
- JS file with extra functions not referenced by any JSON entry (should be ignored)
- Group JSON where entries reference the same function name (same JS function, two tool definitions)
- Group tool + single tool in same directory with conflicting names
- Mixed: directory contains both group files and single-tool files

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
