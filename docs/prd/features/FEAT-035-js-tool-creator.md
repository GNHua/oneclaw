# JS Tool Creator

## Feature Information
- **Feature ID**: FEAT-035
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-035

## User Story

**As** a user of OneClaw,
**I want** to describe a custom tool in natural language in the chat screen and have the AI generate, register, and manage it for me,
**so that** I can extend the agent's capabilities with new JavaScript tools without manually writing manifest and source files.

### Typical Scenarios

1. A user needs a tool to parse CSV data. They invoke `/create-tool` or simply describe their need in chat. The AI asks clarifying questions, generates the JSON manifest and JS code, shows it for review, and after the user confirms, creates and registers the tool immediately.
2. A user realizes their custom tool needs an extra parameter. They ask the AI to update it, the AI generates the updated code and calls `update_js_tool` to apply the changes.
3. A user wants to see what custom tools they have. The AI calls `list_user_tools` and presents a summary.
4. A user no longer needs a custom tool. They ask the AI to remove it, the AI calls `delete_js_tool` to unregister and delete the tool files.
5. A user asks the AI to "make a tool that fetches weather from an API." The AI uses the `/create-tool` skill to guide the conversation, designs the tool with appropriate parameters (city, units), generates the JS code using the `fetch()` bridge, and registers it.

## Feature Description

### Overview

FEAT-035 adds the ability to create, list, update, and delete custom JavaScript tools directly from the chat screen. The feature consists of two parts:

1. **A built-in skill** (`create-tool`) that guides the AI through the tool creation workflow, including gathering requirements, designing parameters, generating code, and confirming with the user before saving.
2. **Four built-in Kotlin tools** that perform the actual CRUD operations on user JS tools:
   - `create_js_tool` -- Create a new JS tool (write files + register in ToolRegistry)
   - `list_user_tools` -- List all user-created JS tools
   - `update_js_tool` -- Update an existing user JS tool
   - `delete_js_tool` -- Delete a user JS tool

Created tools are persisted to the file system and survive app restarts. They are functionally identical to manually-created user JS tools and have access to the same bridge APIs (`fetch`, `fs`, `console`, `_time`, `lib`).

### Architecture Overview

```
User: "I need a tool that parses CSV"
    |
    v
AI loads /create-tool skill
    |
    v
AI designs tool, shows code for review
    |
    v (user confirms)
AI calls create_js_tool(name, description, parameters_schema, js_code)
    |
    v
CreateJsToolTool
    |-- Validates inputs (name format, JSON schema, JS syntax)
    |-- Writes tool-name.json (manifest) to {filesDir}/tools/
    |-- Writes tool-name.js (source) to {filesDir}/tools/
    |-- Creates JsTool instance
    |-- Registers in ToolRegistry
    |
    v
Tool is immediately available for use in the current session
(and auto-loaded on next app restart by JsToolLoader)
```

### Tool Definitions

#### create_js_tool

| Field | Value |
|-------|-------|
| Name | `create_js_tool` |
| Description | Create a new JavaScript tool and register it for use |
| Parameters | `name` (string, required): Tool name (lowercase letters, numbers, underscores; 2-50 chars) |
| | `description` (string, required): What the tool does (shown to AI for tool selection) |
| | `parameters_schema` (string, required): JSON string defining the parameters schema |
| | `js_code` (string, required): JavaScript source code with an `execute(params)` function |
| | `required_permissions` (string, optional): Comma-separated Android permission names |
| | `timeout_seconds` (integer, optional, default: 30): Execution timeout |
| Required Permissions | None (the tool itself needs no permissions; the created tool may declare its own) |
| Timeout | 10 seconds |
| Returns | Success message with tool name and registration status |

#### list_user_tools

| Field | Value |
|-------|-------|
| Name | `list_user_tools` |
| Description | List all user-created JavaScript tools |
| Parameters | (none) |
| Required Permissions | None |
| Timeout | 5 seconds |
| Returns | Formatted list of user tools with name, description, and source file path |

#### update_js_tool

| Field | Value |
|-------|-------|
| Name | `update_js_tool` |
| Description | Update an existing user-created JavaScript tool |
| Parameters | `name` (string, required): Name of the tool to update |
| | `description` (string, optional): New description |
| | `parameters_schema` (string, optional): New parameters schema JSON |
| | `js_code` (string, optional): New JavaScript source code |
| | `required_permissions` (string, optional): New comma-separated permissions |
| | `timeout_seconds` (integer, optional): New timeout |
| Required Permissions | None |
| Timeout | 10 seconds |
| Returns | Success message confirming the update |

#### delete_js_tool

| Field | Value |
|-------|-------|
| Name | `delete_js_tool` |
| Description | Delete a user-created JavaScript tool |
| Parameters | `name` (string, required): Name of the tool to delete |
| Required Permissions | None |
| Timeout | 5 seconds |
| Returns | Success message confirming deletion |

### Skill Definition

#### create-tool

| Field | Value |
|-------|-------|
| Name | `create-tool` |
| Display Name | Create Tool |
| Description | Guide the user through creating a custom JavaScript tool |
| Tools Required | `create_js_tool` |
| Parameters | `idea` (string, optional): Brief description of what the tool should do |

The skill prompt instructs the AI to:
1. Clarify the user's requirements (what the tool does, inputs, outputs)
2. Design the tool's parameters and behavior
3. Generate the JSON manifest and JS code
4. Present the generated code to the user for review
5. Only call `create_js_tool` after the user confirms
6. Test the newly created tool if possible

### User Interaction Flow

```
1. User: "/create-tool" or "I need a tool to fetch stock prices"
2. AI loads the create-tool skill
3. AI asks clarifying questions:
   "What stock API would you like to use? What parameters should the tool accept?"
4. User provides details
5. AI generates tool code and presents it:
   "Here's the tool I've designed:
    Name: fetch_stock_price
    Parameters: symbol (required), market (optional)
    [shows JS code]
    Should I create this tool?"
6. User: "Yes"
7. AI calls create_js_tool(name="fetch_stock_price", ...)
8. Tool responds: "Tool 'fetch_stock_price' created and registered successfully."
9. AI: "The tool is ready. Try asking me to fetch a stock price!"
```

## Acceptance Criteria

Must pass (all required):

- [ ] TEST-035-01: `create_js_tool` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-035-02: `list_user_tools` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-035-03: `update_js_tool` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-035-04: `delete_js_tool` is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] TEST-035-05: `create_js_tool` validates tool name format (lowercase, letters/numbers/underscores, 2-50 chars)
- [ ] TEST-035-06: `create_js_tool` validates parameters_schema is valid JSON with correct structure
- [ ] TEST-035-07: `create_js_tool` writes .json manifest and .js source to the user tools directory
- [ ] TEST-035-08: `create_js_tool` registers the new tool in ToolRegistry immediately (available without restart)
- [ ] TEST-035-09: Created tools persist across app restarts (loaded by JsToolLoader on next launch)
- [ ] TEST-035-10: `create_js_tool` rejects duplicate tool names (tools already in registry)
- [ ] TEST-035-11: `list_user_tools` returns all user-created tools with name and description
- [ ] TEST-035-12: `update_js_tool` updates only the specified fields, preserving others
- [ ] TEST-035-13: `update_js_tool` re-registers the tool with updated definition
- [ ] TEST-035-14: `update_js_tool` rejects updates to built-in tools
- [ ] TEST-035-15: `delete_js_tool` removes the tool from ToolRegistry and deletes the files
- [ ] TEST-035-16: `delete_js_tool` rejects deletion of built-in tools
- [ ] TEST-035-17: The `create-tool` skill is loaded and available in the skill registry
- [ ] TEST-035-18: All Layer 1A tests pass

Optional (nice to have):

- [ ] `create_js_tool` performs basic JS syntax validation before saving
- [ ] `create_js_tool` runs a dry-run execution to verify the tool works before registering
- [ ] Support creating tool groups (multiple tools from one JS file, RFC-018 format)

## UI/UX Requirements

This feature has no new UI. The tools and skill integrate into the existing system:
- Tool names appear in the tool management screen (FEAT-017)
- Tool call results are displayed in the chat view (FEAT-001)
- The `/create-tool` skill appears in the skill list for user invocation
- Created tools appear alongside other user tools in the tool management screen

## Feature Boundary

### Included

- Four Kotlin built-in tools: `CreateJsToolTool`, `ListUserToolsTool`, `UpdateJsToolTool`, `DeleteJsToolTool`
- One built-in skill: `create-tool` (SKILL.md in assets)
- Writing .json manifest and .js source files to user tools directory
- Immediate in-memory registration in ToolRegistry
- Persistence across app restarts via existing JsToolLoader
- Tool name validation and duplicate detection
- Protection against modifying/deleting built-in tools

### Not Included (V1)

- Visual code editor for JS tools
- Tool testing/debugging UI
- Tool sharing or export/import between devices
- Tool group creation (multiple tools per JS file)
- Tool versioning or rollback
- Tool marketplace or community tools
- Automatic tool discovery from user behavior

## Business Rules

1. Tool names must match `^[a-z][a-z0-9_]{0,48}[a-z0-9]$` (lowercase, 2-50 chars, start with letter)
2. Tool names must not conflict with existing registered tools (built-in or user)
3. Only user-created tools can be updated or deleted (built-in tools are protected)
4. The `parameters_schema` must be valid JSON with a `properties` object
5. The `js_code` must define an `execute(params)` function (or an async variant)
6. Created tools are saved to the app's internal tools directory
7. Created tools have access to the same bridge APIs as manually-created JS tools
8. The skill prompt must instruct the AI to show code for user review before creating

## Non-Functional Requirements

### Performance

- `create_js_tool`: < 500ms (file write + registry operation)
- `list_user_tools`: < 100ms (in-memory registry query)
- `update_js_tool`: < 500ms (file write + re-registration)
- `delete_js_tool`: < 200ms (file delete + unregistration)

### Security

- Tool names are validated to prevent path traversal (no `/`, `..`, etc.)
- Created JS code runs in the same QuickJS sandbox as other JS tools (memory/timeout limits)
- Bridge APIs (fs, fetch) have their own security restrictions (restricted paths, response size limits)
- The AI is instructed by the skill to show code for user review before saving
- Built-in tools cannot be overwritten, updated, or deleted through these tools

### Compatibility

- Uses existing JS tool infrastructure (JsTool, JsExecutionEngine, QuickJS)
- Created tools are compatible with existing tool management features (FEAT-017)
- Compatible with tool enable/disable toggles (ToolEnabledStateStore)

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-014 (Agent Skills)**: Skill system for the create-tool skill
- **RFC-004 (Tool System)**: JsTool, JsExecutionEngine, JsToolLoader infrastructure

### Depended On By

- No other features currently depend on FEAT-035

### External Dependencies

- No new external dependencies. Uses existing QuickJS engine and JS tool infrastructure.

## Error Handling

### Error Scenarios

1. **Invalid tool name**
   - Cause: Name doesn't match the required format
   - Handling: Return `ToolResult.error("validation_error", "Invalid tool name: ...")`

2. **Duplicate tool name**
   - Cause: A tool with the same name already exists in the registry
   - Handling: Return `ToolResult.error("duplicate_name", "Tool 'X' already exists")`

3. **Invalid parameters schema**
   - Cause: The parameters_schema string is not valid JSON or missing required structure
   - Handling: Return `ToolResult.error("validation_error", "Invalid parameters schema: ...")`

4. **Tool not found (update/delete)**
   - Cause: The specified tool name doesn't exist
   - Handling: Return `ToolResult.error("not_found", "Tool 'X' not found")`

5. **Protected tool (update/delete)**
   - Cause: Attempting to modify or delete a built-in tool
   - Handling: Return `ToolResult.error("protected_tool", "Cannot modify built-in tool 'X'")`

6. **File write failure**
   - Cause: Disk full or I/O error when saving tool files
   - Handling: Return `ToolResult.error("io_error", "Failed to save tool files: ...")`

## Test Points

### Functional Tests

- Verify `create_js_tool` creates .json and .js files in the correct directory
- Verify `create_js_tool` registers the tool in ToolRegistry with correct definition
- Verify created tool can be executed via ToolExecutionEngine
- Verify created tool has access to bridge APIs (fetch, fs, console, _time)
- Verify `create_js_tool` rejects invalid names, duplicate names, invalid schema
- Verify `list_user_tools` returns only user tools, not built-in ones
- Verify `update_js_tool` updates specified fields and preserves others
- Verify `update_js_tool` re-registers the tool (old definition replaced)
- Verify `delete_js_tool` removes tool from registry and deletes files
- Verify `delete_js_tool` cannot delete built-in tools
- Verify `create-tool` skill loads correctly and provides creation guidance

### Edge Cases

- Create tool with minimal parameters (only required fields)
- Create tool with all optional fields specified
- Create tool with async execute function
- Create tool with permissions that require user approval
- Update tool with only one field changed
- Delete the last user tool (registry should still work)
- Create tool with name that's 2 characters (minimum)
- Create tool with name that's 50 characters (maximum)
- Create tool with empty JS code (should fail validation)
- Create tool with JS code that has syntax errors
- Create two tools with similar names (e.g., "my_tool" and "my_tool2")
- List tools when no user tools exist

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
