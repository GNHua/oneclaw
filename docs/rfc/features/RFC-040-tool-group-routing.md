# RFC-040: Tool Group Routing (Dynamic Tool Loading)

## Document Information
- **RFC ID**: RFC-040
- **Related PRD**: [FEAT-040 (Tool Group Routing)](../../prd/features/FEAT-040-tool-group-routing.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-014 (Agent Skill)](RFC-014-agent-skill.md), [RFC-018 (JS Tool Group)](RFC-018-js-tool-group.md), [RFC-036 (Config Tools)](RFC-036-config-tools.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

`SendMessageUseCase` currently calls `toolRegistry.getAllToolDefinitions()` and sends ALL tool schemas to the LLM every turn. With 37+ Kotlin tools, 60+ JS Google Workspace tools, and user-created JS tools, the total tool schema payload can exceed 20,000 tokens per turn. This is wasteful because most conversations only use a small subset of available tools.

The Skill system (RFC-014) already demonstrates a lazy-loading pattern: only skill names and descriptions appear in the system prompt, and full skill content is loaded on demand via `load_skill`. This RFC applies the same pattern to tools: group them by domain, list group summaries in the system prompt, and load full tool schemas on demand via a `load_tool_group` meta-tool.

### Goals

1. Add `ToolGroupDefinition` model for group metadata
2. Extend `ToolRegistry` with group registration and core/group query methods
3. Add `LoadToolGroupTool` meta-tool mirroring `LoadSkillTool` pattern
4. Modify `SendMessageUseCase` for dynamic tool list management
5. Register tool groups in `ToolModule` for both Kotlin and JS tools
6. Add `_meta` entry support to JS group manifests in `JsToolLoader`
7. Update Google Workspace JS manifests with `_meta` entries

### Non-Goals

- Automatic tool group inference (LLM must explicitly call `load_tool_group`)
- Per-agent tool group configuration
- Cross-session group persistence
- UI for managing tool groups

## Technical Design

### Architecture Overview

```
+---------------------------------------------------------------------+
|                     Chat Layer (RFC-001)                              |
|  SendMessageUseCase                                                  |
|       |                                                              |
|       |  1. Build system prompt with tool group listing              |
|       |  2. Send only core tool schemas to LLM                      |
|       |  3. On load_tool_group call, expand active tool list         |
|       |  4. Send expanded tool list on next turn                    |
|       v                                                              |
+---------------------------------------------------------------------+
|                  Tool Execution Engine (RFC-004)                      |
|       |                                                              |
|       v                                                              |
|  +------------------------------------------------------------------+|
|  |                     ToolRegistry                                  ||
|  |                                                                   ||
|  |  groupDefinitions: Map<String, ToolGroupDefinition>               ||
|  |                                                                   ||
|  |  getCoreToolDefinitions() --> tools where groupName == null       ||
|  |  getGroupToolDefinitions(name) --> tools in specific group        ||
|  |  getAllGroupDefinitions() --> all ToolGroupDefinition metadata     ||
|  |                                                                   ||
|  |  Core tools (always loaded):                                      ||
|  |    load_skill, load_tool_group, save_memory, search_history,      ||
|  |    exec, js_eval, webfetch, browser, create_agent,                ||
|  |    read_file, write_file, get_current_time, http_request          ||
|  |                                                                   ||
|  |  Grouped tools (loaded on demand):                                ||
|  |    config (17), pdf (3), scheduled_tasks (5),                     ||
|  |    js_tool_management (4), google_gmail (8), ...                  ||
|  +------------------------------------------------------------------+|
+---------------------------------------------------------------------+
```

### Core Components

**New:**
1. `ToolGroupDefinition` -- data class for group metadata (name, displayName, description)
2. `LoadToolGroupTool` -- meta-tool to load tools from a group on demand

**Modified:**
3. `ToolRegistry` -- add group registration and core/group query methods
4. `SendMessageUseCase` -- dynamic tool list, group prompt injection
5. `ToolModule` -- register groups, assign groupName to Kotlin tools
6. `JsToolLoader` -- parse `_meta` entries, expose group metadata in load result

**Updated (data only):**
7. `google_gmail.json` -- add `_meta` entry
8. `google_gmail_settings.json` -- add `_meta` entry
9. `google_drive.json` -- add `_meta` entry
10. `google_calendar.json` -- add `_meta` entry
11. `google_contacts.json` -- add `_meta` entry
12. `google_docs.json` -- add `_meta` entry
13. `google_sheets.json` -- add `_meta` entry
14. `google_slides.json` -- add `_meta` entry
15. `google_forms.json` -- add `_meta` entry
16. `google_tasks.json` -- add `_meta` entry

**Reused (unchanged):**
17. `Tool` interface -- existing tool contract
18. `ToolDefinition` -- existing tool schema
19. `ToolSourceInfo` / `ToolSourceType` -- existing source tracking (BUILTIN, TOOL_GROUP, JS_EXTENSION)
20. `ToolExecutionEngine` -- existing execution engine
21. `SkillRegistry` / `LoadSkillTool` -- pattern reference

### Data Flow

```
Conversation start:
  ToolRegistry
    |-- getCoreToolDefinitions() --> [load_skill, load_tool_group, exec, ...]
    |-- getAllGroupDefinitions() --> [config, pdf, google_gmail, ...]
    v
  SendMessageUseCase
    |-- activeToolDefs = coreToolDefs (mutable)
    |-- systemPrompt += buildSystemPromptWithToolGroups()
    |-- send to LLM with activeToolDefs
    v
  LLM responds with tool call: load_tool_group(group_name="google_gmail")
    v
  ToolExecutionEngine.executeToolsParallel()
    |-- LoadToolGroupTool.execute("google_gmail")
    |-- returns list of tool names + descriptions
    v
  SendMessageUseCase detects load_tool_group success
    |-- loadedGroupNames.add("google_gmail")
    |-- activeToolDefs += toolRegistry.getGroupToolDefinitions("google_gmail")
    |-- next LLM call includes Gmail tools in tool list
    v
  LLM responds with tool call: gmail_search(query="is:unread")
    |-- executed normally via ToolExecutionEngine
```

## Detailed Design

### Step 1: New Model -- `ToolGroupDefinition`

**File:** `app/src/main/kotlin/com/oneclaw/shadow/core/model/ToolGroupDefinition.kt` (new)

```kotlin
package com.oneclaw.shadow.core.model

/**
 * Metadata for a tool group. Used to display group information
 * in the system prompt and validate load_tool_group calls.
 */
data class ToolGroupDefinition(
    /** Unique group identifier, e.g. "google_gmail" */
    val name: String,
    /** Human-readable name, e.g. "Google Gmail" */
    val displayName: String,
    /** One-line description of group capabilities */
    val description: String
)
```

### Step 2: Extend `ToolRegistry`

**File:** `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolRegistry.kt`

Add new storage and methods:

```kotlin
// New field
private val groupDefinitions = mutableMapOf<String, ToolGroupDefinition>()

// Register group metadata
fun registerGroup(group: ToolGroupDefinition) {
    groupDefinitions[group.name] = group
}

// Get a single group definition
fun getGroupDefinition(name: String): ToolGroupDefinition? {
    return groupDefinitions[name]
}

// Get all registered group definitions
fun getAllGroupDefinitions(): List<ToolGroupDefinition> {
    return groupDefinitions.values.toList()
}

// Get tool definitions that are NOT in any group (core tools)
fun getCoreToolDefinitions(): List<ToolDefinition> {
    return tools.entries
        .filter { (name, _) -> sourceInfoMap[name]?.groupName == null }
        .map { (_, tool) -> tool.definition }
}

// Get tool definitions belonging to a specific group
fun getGroupToolDefinitions(groupName: String): List<ToolDefinition> {
    return tools.entries
        .filter { (name, _) -> sourceInfoMap[name]?.groupName == groupName }
        .map { (_, tool) -> tool.definition }
}
```

Also modify the existing `getToolGroups()` method to include ALL tools with `groupName` set, not just `TOOL_GROUP` type:

```kotlin
// Current implementation (line 69-77):
fun getToolGroups(): Map<String, List<String>> {
    val groups = mutableMapOf<String, MutableList<String>>()
    for ((toolName, info) in sourceInfoMap) {
        if (info.type == ToolSourceType.TOOL_GROUP && info.groupName != null) {
            groups.getOrPut(info.groupName) { mutableListOf() }.add(toolName)
        }
    }
    return groups
}

// New implementation:
fun getToolGroups(): Map<String, List<String>> {
    val groups = mutableMapOf<String, MutableList<String>>()
    for ((toolName, info) in sourceInfoMap) {
        if (info.groupName != null) {  // Remove TOOL_GROUP type filter
            groups.getOrPut(info.groupName) { mutableListOf() }.add(toolName)
        }
    }
    return groups
}
```

This change is needed because Kotlin tools registered with `ToolSourceInfo(type = BUILTIN, groupName = "config")` should also appear in group queries.

### Step 3: New Tool -- `LoadToolGroupTool`

**File:** `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/LoadToolGroupTool.kt` (new)

Mirrors the `LoadSkillTool` pattern:

```kotlin
package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.Tool
import com.oneclaw.shadow.tool.engine.ToolRegistry

class LoadToolGroupTool(
    private val toolRegistry: ToolRegistry
) : Tool {

    override val definition = ToolDefinition(
        name = "load_tool_group",
        description = "Load all tools in a tool group to make them available for use. " +
            "You MUST load a tool group before you can use any tools in it. " +
            "After loading, the tools will be available for the rest of this conversation.",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "group_name" to ToolParameter(
                    type = "string",
                    description = "The name of the tool group to load"
                )
            ),
            required = listOf("group_name")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 5
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val groupName = parameters["group_name"] as? String
            ?: return ToolResult.error(
                "missing_parameter",
                "Required parameter 'group_name' is missing."
            )

        val groupDef = toolRegistry.getGroupDefinition(groupName)
            ?: run {
                val available = toolRegistry.getAllGroupDefinitions()
                    .joinToString(", ") { it.name }
                return ToolResult.error(
                    "not_found",
                    "Tool group '$groupName' not found. Available groups: $available"
                )
            }

        val toolDefs = toolRegistry.getGroupToolDefinitions(groupName)
        if (toolDefs.isEmpty()) {
            return ToolResult.error(
                "empty_group",
                "Tool group '$groupName' has no available tools."
            )
        }

        val toolList = toolDefs.joinToString("\n") { def ->
            "- ${def.name}: ${def.description}"
        }

        return ToolResult.success(
            "Loaded ${toolDefs.size} tools from group '${groupDef.displayName}':\n$toolList"
        )
    }
}
```

### Step 4: Modify `SendMessageUseCase` -- Dynamic Tool List

**File:** `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/SendMessageUseCase.kt`

#### 4a: Replace static tool loading with dynamic

Current code (lines 134-137):
```kotlin
val agentToolDefs: List<ToolDefinition>? = toolRegistry
    .getAllToolDefinitions()
    .takeIf { it.isNotEmpty() }
```

Replace with:
```kotlin
// Track loaded groups for this conversation
val loadedGroupNames = mutableSetOf<String>()
// Start with core tools only
val activeToolDefs = toolRegistry.getCoreToolDefinitions().toMutableList()
```

#### 4b: Detect `load_tool_group` results and expand tool list

In the tool execution section, after `executeToolsParallel` returns, add group expansion logic:

```kotlin
// After tool execution (around line 310+):
for (tr in toolResponses) {
    if (tr.toolName == "load_tool_group" &&
        tr.result.status == ToolResultStatus.SUCCESS) {
        val groupName = extractGroupName(toolRequests, tr.toolCallId)
        if (groupName != null && loadedGroupNames.add(groupName)) {
            val groupDefs = toolRegistry.getGroupToolDefinitions(groupName)
            activeToolDefs.addAll(groupDefs)
        }
    }
}
```

Helper method:
```kotlin
private fun extractGroupName(
    toolRequests: List<ToolRequest>,
    toolCallId: String
): String? {
    val request = toolRequests.find { it.id == toolCallId } ?: return null
    return request.parameters["group_name"] as? String
}
```

#### 4c: Use active tool list for execution

Current code (line 310):
```kotlin
availableToolNames = toolRegistry.getAllToolNames()
```

Replace with:
```kotlin
availableToolNames = activeToolDefs.map { it.name }
```

Pass `activeToolDefs` (instead of the previous `agentToolDefs`) to the adapter on each loop iteration, so the LLM sees the expanded tool list after a group is loaded.

#### 4d: Add system prompt injection for tool groups

New method, similar to the existing `buildSystemPromptWithSkills`:

```kotlin
private fun buildSystemPromptWithToolGroups(basePrompt: String): String {
    val groups = toolRegistry.getAllGroupDefinitions()
    if (groups.isEmpty()) return basePrompt

    val registryPrompt = buildString {
        appendLine("## Available Tool Groups")
        appendLine()
        appendLine("Use `load_tool_group` to load tools from a group before using them.")
        appendLine()
        groups.forEach { group ->
            appendLine("- ${group.name}: ${group.description}")
        }
    }.trimEnd()

    return if (basePrompt.isBlank()) registryPrompt
    else "$basePrompt\n\n---\n\n$registryPrompt"
}
```

Call this in the prompt assembly chain, after `buildSystemPromptWithSkills` and before compaction:

```kotlin
// Existing:
var systemPrompt = buildSystemPromptWithSkills(baseSystemPrompt)
// New (add after):
systemPrompt = buildSystemPromptWithToolGroups(systemPrompt)
```

### Step 5: Modify `ToolModule` -- Register Groups

**File:** `app/src/main/kotlin/com/oneclaw/shadow/di/ToolModule.kt`

#### 5a: Register `LoadToolGroupTool` as core tool

```kotlin
single { LoadToolGroupTool(get()) }

// In ToolRegistry setup:
register(get<LoadToolGroupTool>(), ToolSourceInfo.BUILTIN)
```

#### 5b: Assign `groupName` to Kotlin tools

Change registration calls for grouped tools from:
```kotlin
register(tool, ToolSourceInfo.BUILTIN)
```
to:
```kotlin
register(tool, ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config"))
```

Full group assignments:

| Group Name | Tools |
|---|---|
| `config` | ListProvidersTool, CreateProviderTool, UpdateProviderTool, DeleteProviderTool, ListModelsTool, FetchModelsTool, SetDefaultModelTool, AddModelTool, DeleteModelTool, ListAgentsTool, UpdateAgentTool, DeleteAgentTool, GetConfigTool, SetConfigTool, ManageEnvVarTool, ListToolStatesTool, SetToolEnabledTool |
| `pdf` | PdfInfoTool, PdfExtractTextTool, PdfRenderPageTool |
| `scheduled_tasks` | ScheduleTaskTool, ListScheduledTasksTool, RunScheduledTaskTool, UpdateScheduledTaskTool, DeleteScheduledTaskTool |
| `js_tool_management` | CreateJsToolTool, ListUserToolsTool, UpdateJsToolTool, DeleteJsToolTool |

#### 5c: Register group metadata

```kotlin
toolRegistry.registerGroup(ToolGroupDefinition(
    name = "config",
    displayName = "Configuration",
    description = "Manage providers, models, agents, app settings, environment variables, and tool states"
))
toolRegistry.registerGroup(ToolGroupDefinition(
    name = "pdf",
    displayName = "PDF Tools",
    description = "Extract text, get info, and render pages from PDF files"
))
toolRegistry.registerGroup(ToolGroupDefinition(
    name = "scheduled_tasks",
    displayName = "Scheduled Tasks",
    description = "Create, list, run, update, and delete scheduled tasks"
))
toolRegistry.registerGroup(ToolGroupDefinition(
    name = "js_tool_management",
    displayName = "JS Tool Management",
    description = "Create, list, update, and delete user JavaScript tools"
))
```

JS group metadata is registered automatically from `_meta` entries (see Step 6).

#### 5d: JS built-in tool registration with groupName

For JS tool groups (detected by JSON array format), change registration:

```kotlin
// Current:
for (tool in builtinResult.loadedTools) {
    register(tool, ToolSourceInfo(type = ToolSourceType.BUILTIN))
}

// New:
for (tool in builtinResult.loadedTools) {
    val groupName = builtinResult.groupNames[tool.definition.name]
    val sourceInfo = if (groupName != null) {
        ToolSourceInfo(type = ToolSourceType.TOOL_GROUP, groupName = groupName)
    } else {
        ToolSourceInfo(type = ToolSourceType.BUILTIN)
    }
    register(tool, sourceInfo)
}

// Register JS group definitions
for (groupDef in builtinResult.groupDefinitions) {
    toolRegistry.registerGroup(groupDef)
}
```

This requires `JsToolLoadResult` to expose `groupNames` and `groupDefinitions` (see Step 6).

#### 5e: Core tools (no groupName, always loaded)

The following tools remain registered as `ToolSourceInfo.BUILTIN` (no `groupName`):

- `load_skill` (meta-tool)
- `load_tool_group` (meta-tool)
- `save_memory` (memory)
- `search_history` (history)
- `exec` (execution)
- `js_eval` (execution)
- `webfetch` (web)
- `browser` (web)
- `create_agent` (agent management)
- `read_file` (JS built-in, single-file)
- `write_file` (JS built-in, single-file)
- `get_current_time` (JS built-in, single-file)
- `http_request` (JS built-in, single-file)

### Step 6: Modify `JsToolLoader` -- Expose Group Metadata

**File:** `app/src/main/kotlin/com/oneclaw/shadow/tool/js/JsToolLoader.kt`

#### 6a: Add `_meta` support to group manifest parsing

In `parseGroupManifest()`, detect an optional first entry with `"_meta": true`:

```json
[
  {
    "_meta": true,
    "display_name": "Google Gmail",
    "description": "Email management: search, read, send, draft, label, archive, and manage Gmail messages"
  },
  { "name": "gmail_search", "description": "Search Gmail messages", "function": "gmailSearch", ... },
  ...
]
```

Implementation:

```kotlin
internal fun parseGroupManifest(
    array: JsonArray,
    baseName: String,
    fileName: String
): Pair<List<Pair<ToolDefinition, String?>>, ToolGroupDefinition?> {
    val entries = array.toList()

    // Check for _meta entry
    var metaDef: ToolGroupDefinition? = null
    val toolEntries = if (entries.isNotEmpty() &&
        entries[0] is JsonObject &&
        (entries[0] as JsonObject)["_meta"]?.jsonPrimitive?.booleanOrNull == true
    ) {
        val meta = entries[0] as JsonObject
        metaDef = ToolGroupDefinition(
            name = baseName,
            displayName = meta["display_name"]?.jsonPrimitive?.content
                ?: baseName.replace("_", " ")
                    .replaceFirstChar { it.uppercase() },
            description = meta["description"]?.jsonPrimitive?.content
                ?: "Tools from $baseName group"
        )
        entries.drop(1) // Skip _meta entry for tool parsing
    } else {
        // Auto-generate group definition from filename
        metaDef = ToolGroupDefinition(
            name = baseName,
            displayName = baseName.replace("_", " ")
                .split(" ")
                .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } },
            description = "Tools from $baseName group"
        )
        entries
    }

    // Parse remaining entries as tools (existing logic)
    val tools = toolEntries.mapNotNull { entry ->
        // ... existing tool parsing logic ...
    }

    return Pair(tools, metaDef)
}
```

#### 6b: Expose group info in load result

Add fields to the JS tool load result (or modify existing return type):

```kotlin
data class JsToolLoadResult(
    val loadedTools: List<JsTool>,
    val errors: List<String>,
    /** tool name -> group name mapping */
    val groupNames: Map<String, String>,
    /** extracted group metadata from _meta entries or auto-generated */
    val groupDefinitions: List<ToolGroupDefinition>
)
```

In `loadBuiltinTools()`, collect group info:

```kotlin
fun loadBuiltinTools(): JsToolLoadResult {
    val allTools = mutableListOf<JsTool>()
    val allErrors = mutableListOf<String>()
    val groupNames = mutableMapOf<String, String>()
    val groupDefs = mutableListOf<ToolGroupDefinition>()

    for (fileName in assetManager.list("js/tools") ?: emptyArray()) {
        val baseName = fileName.removeSuffix(".json")
        val jsonContent = loadAsset("js/tools/$fileName")
        val (parsedTools, groupDef) = parseJsonManifest(jsonContent, baseName, fileName)

        if (parsedTools.size > 1 || parsedTools.any { it.second != null }) {
            // This is a group manifest
            for ((toolDef, _) in parsedTools) {
                groupNames[toolDef.name] = baseName
            }
            if (groupDef != null) {
                groupDefs.add(groupDef)
            }
        }

        // ... create JsTool instances (existing logic) ...
    }

    return JsToolLoadResult(allTools, allErrors, groupNames, groupDefs)
}
```

### Step 7: Update JS Manifests with `_meta`

**Files:** All `app/src/main/assets/js/tools/google_*.json`

Add `_meta` entry as the first element of each group manifest array. Examples:

**google_gmail.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Gmail",
    "description": "Email management: search, read, send, draft, label, archive, and manage Gmail messages"
  },
  { "name": "gmail_search", ... },
  ...
]
```

**google_drive.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Drive",
    "description": "File storage: list, search, upload, download, share, and manage Google Drive files and folders"
  },
  { "name": "drive_list_files", ... },
  ...
]
```

**google_calendar.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Calendar",
    "description": "Calendar management: list, create, update, delete events, and manage Google Calendar"
  },
  { "name": "calendar_list_events", ... },
  ...
]
```

**google_contacts.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Contacts",
    "description": "Contact management: search, list, create, update, and delete Google Contacts"
  },
  ...
]
```

**google_docs.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Docs",
    "description": "Document editing: create, read, update, and format Google Docs documents"
  },
  ...
]
```

**google_sheets.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Sheets",
    "description": "Spreadsheet management: create, read, update cells, format, and manage Google Sheets"
  },
  ...
]
```

**google_slides.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Slides",
    "description": "Presentation management: create, update, add slides, and manage Google Slides presentations"
  },
  ...
]
```

**google_forms.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Forms",
    "description": "Form management: create forms, add questions, get responses, and manage Google Forms"
  },
  ...
]
```

**google_tasks.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Tasks",
    "description": "Task management: list, create, update, complete, and delete Google Tasks"
  },
  ...
]
```

**google_gmail_settings.json:**
```json
[
  {
    "_meta": true,
    "display_name": "Google Gmail Settings",
    "description": "Gmail settings: manage filters, labels, forwarding, signatures, and vacation responders"
  },
  ...
]
```

## Files Modified (Summary)

| # | File | Change Type | Description |
|---|------|-------------|-------------|
| 1 | `core/model/ToolGroupDefinition.kt` | **New** | Data class for group metadata |
| 2 | `tool/engine/ToolRegistry.kt` | Modified | Add group registration, core/group queries, update `getToolGroups()` |
| 3 | `tool/builtin/LoadToolGroupTool.kt` | **New** | Meta-tool to load tool groups on demand |
| 4 | `feature/chat/usecase/SendMessageUseCase.kt` | Modified | Dynamic tool list, group prompt injection, group expansion on load |
| 5 | `di/ToolModule.kt` | Modified | Group registration, LoadToolGroupTool, groupName on Kotlin tools |
| 6 | `tool/js/JsToolLoader.kt` | Modified | `_meta` parsing, expose groupNames/groupDefinitions in result |
| 7 | `assets/js/tools/google_gmail.json` | Modified | Add `_meta` entry |
| 8 | `assets/js/tools/google_gmail_settings.json` | Modified | Add `_meta` entry |
| 9 | `assets/js/tools/google_drive.json` | Modified | Add `_meta` entry |
| 10 | `assets/js/tools/google_calendar.json` | Modified | Add `_meta` entry |
| 11 | `assets/js/tools/google_contacts.json` | Modified | Add `_meta` entry |
| 12 | `assets/js/tools/google_docs.json` | Modified | Add `_meta` entry |
| 13 | `assets/js/tools/google_sheets.json` | Modified | Add `_meta` entry |
| 14 | `assets/js/tools/google_slides.json` | Modified | Add `_meta` entry |
| 15 | `assets/js/tools/google_forms.json` | Modified | Add `_meta` entry |
| 16 | `assets/js/tools/google_tasks.json` | Modified | Add `_meta` entry |

## API Design

### `load_tool_group` Tool Schema

```json
{
  "name": "load_tool_group",
  "description": "Load all tools in a tool group to make them available for use. You MUST load a tool group before you can use any tools in it. After loading, the tools will be available for the rest of this conversation.",
  "parameters": {
    "type": "object",
    "properties": {
      "group_name": {
        "type": "string",
        "description": "The name of the tool group to load"
      }
    },
    "required": ["group_name"]
  }
}
```

### Success Response Format

```
Loaded 8 tools from group 'Google Gmail':
- gmail_search: Search Gmail messages by query
- gmail_read: Read a specific Gmail message by ID
- gmail_send: Send a new Gmail message
- gmail_draft_create: Create a Gmail draft
- gmail_draft_send: Send an existing Gmail draft
- gmail_label_add: Add labels to Gmail messages
- gmail_label_remove: Remove labels from Gmail messages
- gmail_archive: Archive Gmail messages
```

### Error Response Format

```
Tool group 'nonexistent' not found. Available groups: config, pdf, scheduled_tasks, js_tool_management, google_gmail, google_gmail_settings, google_drive, google_calendar, google_contacts, google_docs, google_sheets, google_slides, google_forms, google_tasks
```

## System Prompt Format

The tool group listing is injected into the system prompt after skills and before compaction:

```markdown
## Available Tool Groups

Use `load_tool_group` to load tools from a group before using them.

- config: Manage providers, models, agents, app settings, environment variables, and tool states
- pdf: Extract text, get info, and render pages from PDF files
- scheduled_tasks: Create, list, run, update, and delete scheduled tasks
- js_tool_management: Create, list, update, and delete user JavaScript tools
- google_gmail: Email management: search, read, send, draft, label, archive, and manage Gmail messages
- google_gmail_settings: Gmail settings: manage filters, labels, forwarding, signatures, and vacation responders
- google_drive: File storage: list, search, upload, download, share, and manage Google Drive files and folders
- google_calendar: Calendar management: list, create, update, delete events, and manage Google Calendar
- google_contacts: Contact management: search, list, create, update, and delete Google Contacts
- google_docs: Document editing: create, read, update, and format Google Docs documents
- google_sheets: Spreadsheet management: create, read, update cells, format, and manage Google Sheets
- google_slides: Presentation management: create, update, add slides, and manage Google Slides presentations
- google_forms: Form management: create forms, add questions, get responses, and manage Google Forms
- google_tasks: Task management: list, create, update, complete, and delete Google Tasks
```

## Testing Strategy

### Unit Tests

#### ToolRegistry Tests

```kotlin
class ToolRegistryGroupTest {
    @Test
    fun `getCoreToolDefinitions excludes grouped tools`() {
        val registry = ToolRegistry()
        registry.register(coreTool, ToolSourceInfo.BUILTIN)
        registry.register(groupedTool, ToolSourceInfo(type = BUILTIN, groupName = "config"))

        val core = registry.getCoreToolDefinitions()
        assertEquals(1, core.size)
        assertEquals("core_tool", core[0].name)
    }

    @Test
    fun `getGroupToolDefinitions returns correct tools`() {
        val registry = ToolRegistry()
        registry.register(tool1, ToolSourceInfo(type = BUILTIN, groupName = "config"))
        registry.register(tool2, ToolSourceInfo(type = BUILTIN, groupName = "config"))
        registry.register(tool3, ToolSourceInfo(type = BUILTIN, groupName = "pdf"))

        val configTools = registry.getGroupToolDefinitions("config")
        assertEquals(2, configTools.size)

        val pdfTools = registry.getGroupToolDefinitions("pdf")
        assertEquals(1, pdfTools.size)
    }

    @Test
    fun `getGroupToolDefinitions returns empty for unknown group`() {
        val registry = ToolRegistry()
        val tools = registry.getGroupToolDefinitions("nonexistent")
        assertTrue(tools.isEmpty())
    }

    @Test
    fun `registerGroup and getAllGroupDefinitions`() {
        val registry = ToolRegistry()
        val group = ToolGroupDefinition("config", "Configuration", "Config tools")
        registry.registerGroup(group)

        val all = registry.getAllGroupDefinitions()
        assertEquals(1, all.size)
        assertEquals("config", all[0].name)
    }

    @Test
    fun `getToolGroups includes tools with groupName regardless of type`() {
        val registry = ToolRegistry()
        registry.register(tool1, ToolSourceInfo(type = BUILTIN, groupName = "config"))
        registry.register(tool2, ToolSourceInfo(type = TOOL_GROUP, groupName = "google_gmail"))

        val groups = registry.getToolGroups()
        assertEquals(2, groups.size)
        assertTrue(groups.containsKey("config"))
        assertTrue(groups.containsKey("google_gmail"))
    }
}
```

#### LoadToolGroupTool Tests

```kotlin
class LoadToolGroupToolTest {
    @Test
    fun `execute returns tool list for valid group`() = runTest {
        val registry = createRegistryWithGroup("pdf", listOf(pdfInfoTool, pdfExtractTool))
        val tool = LoadToolGroupTool(registry)

        val result = tool.execute(mapOf("group_name" to "pdf"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("pdf_info"))
        assertTrue(result.result!!.contains("pdf_extract_text"))
        assertTrue(result.result!!.contains("Loaded 2 tools"))
    }

    @Test
    fun `execute returns error for unknown group`() = runTest {
        val registry = createRegistryWithGroup("pdf", listOf(pdfInfoTool))
        val tool = LoadToolGroupTool(registry)

        val result = tool.execute(mapOf("group_name" to "nonexistent"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("pdf"))
    }

    @Test
    fun `execute returns error for missing parameter`() = runTest {
        val registry = ToolRegistry()
        val tool = LoadToolGroupTool(registry)

        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("missing_parameter", result.errorType)
    }
}
```

#### JsToolLoader `_meta` Tests

```kotlin
class JsToolLoaderMetaTest {
    @Test
    fun `parseGroupManifest extracts _meta entry`() {
        val json = """
        [
          {"_meta": true, "display_name": "Google Gmail", "description": "Email tools"},
          {"name": "gmail_search", "description": "Search", "function": "search", ...}
        ]
        """.trimIndent()

        val (tools, groupDef) = loader.parseGroupManifest(
            Json.parseToJsonElement(json).jsonArray, "google_gmail", "google_gmail.json"
        )

        assertEquals(1, tools.size) // _meta not included as tool
        assertNotNull(groupDef)
        assertEquals("Google Gmail", groupDef!!.displayName)
        assertEquals("Email tools", groupDef.description)
    }

    @Test
    fun `parseGroupManifest auto-generates metadata without _meta`() {
        val json = """
        [
          {"name": "tool1", "description": "Tool 1", "function": "fn1", ...},
          {"name": "tool2", "description": "Tool 2", "function": "fn2", ...}
        ]
        """.trimIndent()

        val (tools, groupDef) = loader.parseGroupManifest(
            Json.parseToJsonElement(json).jsonArray, "my_tools", "my_tools.json"
        )

        assertEquals(2, tools.size)
        assertNotNull(groupDef)
        assertEquals("My Tools", groupDef!!.displayName)
    }

    @Test
    fun `_meta entry is not registered as a tool`() {
        val json = """
        [
          {"_meta": true, "display_name": "Test Group", "description": "Test"},
          {"name": "test_tool", "description": "Test", "function": "test", ...}
        ]
        """.trimIndent()

        val (tools, _) = loader.parseGroupManifest(
            Json.parseToJsonElement(json).jsonArray, "test", "test.json"
        )

        assertEquals(1, tools.size)
        assertEquals("test_tool", tools[0].first.name)
    }
}
```

### Integration Tests

#### SendMessageUseCase Tool Group Integration

```kotlin
class SendMessageUseCaseToolGroupTest {
    @Test
    fun `initial tool list contains only core tools`() {
        // Verify that when a conversation starts, only core tools
        // (no groupName) are sent to the LLM adapter
    }

    @Test
    fun `load_tool_group expands active tool list`() {
        // Simulate: LLM calls load_tool_group("pdf")
        // Verify: next adapter call includes PDF tools
    }

    @Test
    fun `system prompt includes tool group listing`() {
        // Verify: system prompt contains "## Available Tool Groups"
        // with all registered group names and descriptions
    }

    @Test
    fun `grouped tool blocked before load`() {
        // Verify: calling pdf_info before load_tool_group("pdf")
        // fails because it's not in availableToolNames
    }
}
```

### Manual Verification

1. Send "check my email" -- verify agent calls `load_tool_group("google_gmail")` first, then uses Gmail tools
2. Send "what time is it?" -- verify agent uses `get_current_time` directly without loading any group
3. Send "create a spreadsheet" -- verify agent calls `load_tool_group("google_sheets")` first
4. Start a new conversation after loading groups -- verify groups are not pre-loaded (reset per conversation)

## Migration Notes

### Backward Compatibility

- All existing tools continue to work
- The only behavior change is that grouped tools require `load_tool_group` before use
- If a user has agents with system prompts that reference grouped tools directly, the agent will see them in the "Available Tool Groups" section and learn to load them first

### Rollback

- Remove `groupName` from `ToolSourceInfo` registrations in `ToolModule`
- Remove `LoadToolGroupTool` registration
- Revert `SendMessageUseCase` to use `getAllToolDefinitions()`
- No database changes needed, no data to migrate

## Performance Impact

### Token Savings Estimate

| Scenario | Before | After | Savings |
|----------|--------|-------|---------|
| Simple query (no tools needed) | ~20,000 tokens | ~3,400 tokens | ~16,600 tokens (83%) |
| Email task (Gmail only) | ~20,000 tokens | ~5,000 tokens | ~15,000 tokens (75%) |
| Config + Email task | ~20,000 tokens | ~10,400 tokens | ~9,600 tokens (48%) |
| All groups loaded | ~20,000 tokens | ~20,400 tokens | -400 tokens (group listing overhead) |

### Latency Impact

- `load_tool_group` execution: < 10ms (in-memory lookup)
- Additional LLM turn for group loading: ~1-3 seconds (one-time cost per group per conversation)
- Net positive: fewer tokens per turn means faster LLM response times

## Security Considerations

- No new permissions required
- Tool group routing does not bypass existing tool permission checks
- `LoadToolGroupTool` has no `requiredPermissions` (loading tool definitions is safe)
- Tool execution still goes through `ToolExecutionEngine` with full permission checking

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
