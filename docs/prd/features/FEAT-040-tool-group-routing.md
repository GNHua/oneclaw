# Tool Group Routing (Dynamic Tool Loading)

## Feature Information
- **Feature ID**: FEAT-040
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-040 (pending)

## User Story

**As** a user of OneClaw,
**I want** the AI agent to only load the tools it actually needs for each task,
**so that** conversations use fewer tokens, respond faster, and are not cluttered with irrelevant tool schemas.

### Typical Scenarios

1. User says "Check my email" -- the agent calls `load_tool_group("google_gmail")`, which returns the list of Gmail tools. The agent then uses `gmail_search` to find recent emails.
2. User says "Create a spreadsheet with quarterly data" -- the agent calls `load_tool_group("google_sheets")` to load sheet tools, then uses `sheets_create` and `sheets_update_values`.
3. User says "What time is it?" -- the agent calls `get_current_time` directly (core tool, always available). No tool group loading needed.
4. User says "Schedule a task for tomorrow" -- the agent calls `load_tool_group("scheduled_tasks")` to load task management tools, then uses `schedule_task`.
5. User says "Load the PDF tools" -- the agent calls `load_tool_group("pdf")`, which returns the list of PDF tools: `pdf_info`, `pdf_extract_text`, `pdf_render_page`.
6. User says "Add a new OpenAI provider" -- the agent calls `load_tool_group("config")` to load configuration tools, then uses `create_provider`.
7. User says "Help me write a JS tool" -- the agent calls `load_tool_group("js_tool_management")` to load JS tool CRUD tools, then uses `create_js_tool`.

## Feature Description

### Overview

OneClaw currently sends ALL registered tool schemas to the LLM on every message turn. With 37+ Kotlin tools, 60+ JS Google Workspace tools, and potentially many user-created JS tools, this wastes significant tokens (each tool schema consumes ~200-500 tokens). The total tool schema payload can exceed 20,000 tokens per turn.

The Skill system (FEAT-014) already demonstrates a lazy-loading pattern: only skill names and descriptions appear in the system prompt, and the full skill content is loaded on demand via `load_skill`. This feature applies the same pattern to tools: group them by domain, list group summaries in the system prompt, and load full tool schemas on demand via a `load_tool_group` meta-tool.

### How It Works

```
Before (current):
  System prompt: ...
  Tools: [get_current_time, read_file, write_file, http_request,
          gmail_search, gmail_read, gmail_send, gmail_draft, ...(60+ more),
          list_providers, create_provider, ...(17 more),
          pdf_info, pdf_extract_text, pdf_render_page,
          schedule_task, list_scheduled_tasks, ...,
          create_js_tool, list_user_tools, ...]
  Token cost: ~20,000+ tokens for tool schemas alone

After (with tool group routing):
  System prompt:
    ...
    ## Available Tool Groups
    Use `load_tool_group` to load tools from a group before using them.
    - config: Manage providers, models, agents, app settings, environment variables, and tool states
    - pdf: Extract text, get info, and render pages from PDF files
    - scheduled_tasks: Create, list, run, update, and delete scheduled tasks
    - js_tool_management: Create, list, update, and delete user JavaScript tools
    - google_gmail: Email: search, read, send, draft, label, manage messages
    - google_drive: File storage: list, search, upload, download, manage Drive files
    ...
  Tools: [get_current_time, read_file, write_file, http_request,
          load_skill, load_tool_group, save_memory, search_history,
          exec, js_eval, webfetch, browser, create_agent]
  Token cost: ~3,000 tokens for core tool schemas + ~200 tokens for group listing
```

### Tool Classification

#### Core Tools (always available, never grouped)

These tools are sent to the LLM on every turn:

| Tool | Purpose |
|------|---------|
| `load_skill` | Meta-tool: load skill content |
| `load_tool_group` | Meta-tool: load tool group |
| `save_memory` | Save information to memory |
| `search_history` | Search conversation history |
| `exec` | Execute shell commands |
| `js_eval` | Evaluate JavaScript code |
| `webfetch` | Fetch web content |
| `browser` | Browser automation |
| `create_agent` | Create sub-agents |
| `read_file` | Read files (JS built-in) |
| `write_file` | Write files (JS built-in) |
| `get_current_time` | Get current time (JS built-in) |
| `http_request` | HTTP requests (JS built-in) |

#### Grouped Kotlin Tools

| Group | Tools | Count |
|-------|-------|-------|
| `config` | list_providers, create_provider, update_provider, delete_provider, list_models, fetch_models, set_default_model, add_model, delete_model, list_agents, update_agent, delete_agent, get_config, set_config, manage_env_var, list_tool_states, set_tool_enabled | 17 |
| `pdf` | pdf_info, pdf_extract_text, pdf_render_page | 3 |
| `scheduled_tasks` | schedule_task, list_scheduled_tasks, run_scheduled_task, update_scheduled_task, delete_scheduled_task | 5 |
| `js_tool_management` | create_js_tool, list_user_tools, update_js_tool, delete_js_tool | 4 |

#### Grouped JS Tools (from assets)

Each Google Workspace JSON manifest becomes its own group:

| Group | Source File | Approx. Tools |
|-------|-----------|---------------|
| `google_gmail` | google_gmail.json | 8 |
| `google_gmail_settings` | google_gmail_settings.json | 4 |
| `google_drive` | google_drive.json | 8 |
| `google_calendar` | google_calendar.json | 7 |
| `google_contacts` | google_contacts.json | 5 |
| `google_docs` | google_docs.json | 6 |
| `google_sheets` | google_sheets.json | 8 |
| `google_slides` | google_slides.json | 6 |
| `google_forms` | google_forms.json | 5 |
| `google_tasks` | google_tasks.json | 5 |

### User Interaction Flow

```
1. User: "Check my latest emails"
2. System prompt includes:
     ## Available Tool Groups
     - google_gmail: Email management: search, read, send, draft, label, archive, and manage Gmail messages
     ...
3. Agent calls load_tool_group(group_name="google_gmail")
4. Tool returns: "Loaded 8 tools from group 'google_gmail':
     - gmail_search: Search Gmail messages
     - gmail_read: Read a Gmail message
     - gmail_send: Send a Gmail message
     ..."
5. Gmail tools are now included in the active tool list for subsequent turns
6. Agent calls gmail_search(query="is:unread newer_than:1d")
7. Agent returns results to user
```

## Acceptance Criteria

### Core Behavior

#### TEST-040-01: Core Tools Always Available
- **Given** a new conversation starts
- **When** the agent receives the first message
- **Then** only core tools (load_skill, load_tool_group, save_memory, search_history, exec, js_eval, webfetch, browser, create_agent, and single-file JS built-ins) are sent to the LLM

#### TEST-040-02: Tool Group Listing in System Prompt
- **Given** tool groups are registered
- **When** the system prompt is assembled
- **Then** an "Available Tool Groups" section is appended listing each group's name and description

#### TEST-040-03: Load Tool Group Success
- **Given** a valid tool group name
- **When** the agent calls `load_tool_group(group_name="google_gmail")`
- **Then** all tools in the "google_gmail" group become available for subsequent turns and the tool returns a list of loaded tool names and descriptions

#### TEST-040-04: Load Tool Group Invalid Name
- **Given** a non-existent group name
- **When** the agent calls `load_tool_group(group_name="nonexistent")`
- **Then** the tool returns an error listing all available group names

#### TEST-040-05: Loaded Tools Persist Across Turns
- **Given** a tool group has been loaded
- **When** the agent sends subsequent messages in the same conversation
- **Then** the loaded tools remain in the active tool list

#### TEST-040-06: Multiple Groups Can Be Loaded
- **Given** two different groups ("google_gmail" and "google_drive")
- **When** the agent loads both groups
- **Then** tools from both groups are available simultaneously

#### TEST-040-07: Duplicate Load Is Idempotent
- **Given** a group has already been loaded
- **When** the agent calls `load_tool_group` with the same group name again
- **Then** the tool returns success without duplicating tool definitions

### Token Efficiency

#### TEST-040-08: Reduced Initial Token Count
- **Given** 100+ tools are registered (core + grouped)
- **When** a new conversation starts
- **Then** only ~13 core tool schemas are sent to the LLM (not all 100+)

#### TEST-040-09: Group Tool Schemas Sent After Load
- **Given** the agent loads the "config" group (17 tools)
- **When** the next message is sent to the LLM
- **Then** the tool list includes all 13 core tools + 17 config tools = 30 tools

### JS Tool Group Integration

#### TEST-040-10: JS Group Manifests with _meta
- **Given** a JS group manifest JSON file with a `_meta` entry
- **When** the manifest is loaded
- **Then** the `_meta` entry is used for group display_name and description, and is not registered as a tool

#### TEST-040-11: JS Group Manifests without _meta
- **Given** a JS group manifest JSON file without a `_meta` entry
- **When** the manifest is loaded
- **Then** display_name is auto-generated from filename and description is auto-generated from tool names

### Tool Execution

#### TEST-040-12: Grouped Tool Execution Blocked Before Load
- **Given** the "pdf" group has NOT been loaded
- **When** the agent attempts to call `pdf_info`
- **Then** the tool call fails because `pdf_info` is not in the available tool names list

#### TEST-040-13: Grouped Tool Execution After Load
- **Given** the "pdf" group HAS been loaded
- **When** the agent calls `pdf_info`
- **Then** the tool executes normally and returns results

## UI/UX Requirements

This feature has no new UI. The tool group routing is entirely transparent to the user -- it operates within the existing chat interface and tool call display. The only user-visible change is that the agent may call `load_tool_group` before using domain-specific tools, which appears as a normal tool call in the chat.

## Feature Boundary

### Included

- `ToolGroupDefinition` data class for group metadata
- `load_tool_group` meta-tool mirroring `load_skill` pattern
- Dynamic tool list management in `SendMessageUseCase`
- System prompt injection for tool group listing
- Group registration in `ToolModule` for Kotlin tools
- `_meta` entry support in JS group manifests
- Group metadata exposure from `JsToolLoader`

### Not Included (V1)

- Automatic tool group detection (agent must explicitly call `load_tool_group`)
- Per-agent tool group configuration (all agents see all groups)
- Persistent group loading across sessions (groups reset per conversation)
- Tool group enable/disable (covered by existing FEAT-017 tool management)
- Tool group creation/deletion by users (groups are defined by registration)
- Streaming tool group loading (groups load synchronously)

## Business Rules

1. Core tools are always available regardless of group loading state
2. Grouped tools are only available after their group is explicitly loaded via `load_tool_group`
3. Tool group loading is scoped to the current conversation (resets on new conversation)
4. Loading a group that is already loaded is a no-op (idempotent)
5. The `load_tool_group` tool itself is always a core tool (never grouped)
6. JS group manifests may optionally include a `_meta` entry for human-readable group metadata
7. If a `_meta` entry is absent, display_name and description are auto-generated
8. Tool groups registered via `ToolSourceInfo(type = TOOL_GROUP, groupName = ...)` are automatically excluded from core tool definitions
9. Kotlin tools registered with `groupName` set in their `ToolSourceInfo` are treated as grouped regardless of `ToolSourceType`

## Non-Functional Requirements

### Performance

- `load_tool_group` execution: < 10ms (in-memory registry lookup)
- System prompt group listing assembly: < 5ms
- No network calls required for group loading

### Token Savings

- Estimated 15,000-20,000 token reduction per turn when groups are not loaded
- Each group listing entry costs ~20-30 tokens (name + one-line description)
- Full group listing (14 groups) costs ~300-400 tokens vs ~20,000+ for all tool schemas

### Memory

- Minimal memory overhead: `ToolGroupDefinition` instances (~100 bytes each)
- Tool definitions remain in `ToolRegistry` regardless -- group routing only controls which are sent to LLM

### Compatibility

- Backward compatible: existing tools continue to work
- No database changes required
- No new permissions required

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, ToolRegistry, ToolExecutionEngine, ToolSourceInfo
- **FEAT-014 (Agent Skill)**: LoadSkillTool pattern, SkillRegistry pattern
- **FEAT-018 (JS Tool Group)**: JS group manifest format, JsToolLoader
- **FEAT-036 (Config Tools)**: Config tools to be grouped

### Depended On By

- None currently

### External Dependencies

- None (all operations use existing internal components)

## Error Handling

### Error Scenarios

1. **Group not found**
   - Cause: Agent calls `load_tool_group` with non-existent group name
   - Handling: Return `ToolResult.error("not_found", "Tool group 'xyz' not found. Available groups: config, pdf, scheduled_tasks, ...")` with full list of available groups

2. **Missing parameter**
   - Cause: Agent calls `load_tool_group` without `group_name`
   - Handling: Return `ToolResult.error("missing_parameter", "Required parameter 'group_name' is missing.")`

3. **Empty group**
   - Cause: A registered group has no tools (all tools unregistered or disabled)
   - Handling: Return `ToolResult.error("empty_group", "Tool group 'xyz' has no available tools.")`

## Test Points

### Functional Tests

- Verify core tools are always included in tool definitions
- Verify grouped tools are excluded from initial tool definitions
- Verify `load_tool_group` returns correct tool list
- Verify loaded tools become available for execution
- Verify multiple groups can be loaded simultaneously
- Verify duplicate load is idempotent
- Verify error response for invalid group name includes available groups
- Verify system prompt includes tool group listing
- Verify JS `_meta` entries are parsed correctly
- Verify JS `_meta` entries are not registered as tools

### Edge Cases

- Load all groups simultaneously
- Load group with a single tool
- Load group then disable a tool within it via `set_tool_enabled`
- JS manifest with `_meta` entry only (no actual tools)
- JS manifest with `_meta` entry containing special characters in description
- Concurrent `load_tool_group` calls in parallel tool execution
- Group name collision between Kotlin and JS groups
- Very long group description truncation

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
