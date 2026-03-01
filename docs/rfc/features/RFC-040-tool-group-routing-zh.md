# RFC-040: 工具组路由（动态工具加载）

## 文档信息
- **RFC ID**: RFC-040
- **相关 PRD**: [FEAT-040 (工具组路由)](../../prd/features/FEAT-040-tool-group-routing.md)
- **相关架构**: [RFC-000 (整体架构)](../architecture/RFC-000-overall-architecture.md)
- **相关 RFC**: [RFC-004 (工具系统)](RFC-004-tool-system.md), [RFC-014 (Agent Skill)](RFC-014-agent-skill.md), [RFC-018 (JS 工具组)](RFC-018-js-tool-group.md), [RFC-036 (配置工具)](RFC-036-config-tools.md)
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

`SendMessageUseCase` 目前调用 `toolRegistry.getAllToolDefinitions()`，每轮对话都会将所有工具 schema 发送给 LLM。随着 37+ 个 Kotlin 工具、60+ 个 JS Google Workspace 工具以及用户自定义 JS 工具的累积，每轮的工具 schema 总 token 消耗可能超过 20,000。这是一种浪费，因为大多数对话只会用到可用工具的一小部分。

Skill 系统（RFC-014）已经展示了一种懒加载模式：只有 skill 名称和描述出现在系统提示词中，完整 skill 内容通过 `load_skill` 按需加载。本 RFC 将同样的模式应用于工具：按领域将工具分组，在系统提示词中列出组摘要，通过 `load_tool_group` 元工具按需加载完整的工具 schema。

### 目标

1. 新增 `ToolGroupDefinition` 模型，用于存储组元数据
2. 扩展 `ToolRegistry`，添加组注册及核心/分组查询方法
3. 新增 `LoadToolGroupTool` 元工具，镜像 `LoadSkillTool` 模式
4. 修改 `SendMessageUseCase`，实现动态工具列表管理
5. 在 `ToolModule` 中为 Kotlin 和 JS 工具注册工具组
6. 在 `JsToolLoader` 中添加对 JS 组 manifest 中 `_meta` 条目的支持
7. 为 Google Workspace JS manifest 添加 `_meta` 条目

### 非目标

- 自动工具组推断（LLM 必须显式调用 `load_tool_group`）
- 按 agent 配置工具组
- 跨会话的组持久化
- 工具组管理 UI

## 技术设计

### 架构概览

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

### 核心组件

**新增：**
1. `ToolGroupDefinition` -- 存储组元数据的 data class（name、displayName、description）
2. `LoadToolGroupTool` -- 按需加载工具组的元工具

**修改：**
3. `ToolRegistry` -- 添加组注册及核心/分组查询方法
4. `SendMessageUseCase` -- 动态工具列表、组提示词注入
5. `ToolModule` -- 注册工具组，为 Kotlin 工具分配 groupName
6. `JsToolLoader` -- 解析 `_meta` 条目，在加载结果中暴露组元数据

**仅更新数据：**
7. `google_gmail.json` -- 添加 `_meta` 条目
8. `google_gmail_settings.json` -- 添加 `_meta` 条目
9. `google_drive.json` -- 添加 `_meta` 条目
10. `google_calendar.json` -- 添加 `_meta` 条目
11. `google_contacts.json` -- 添加 `_meta` 条目
12. `google_docs.json` -- 添加 `_meta` 条目
13. `google_sheets.json` -- 添加 `_meta` 条目
14. `google_slides.json` -- 添加 `_meta` 条目
15. `google_forms.json` -- 添加 `_meta` 条目
16. `google_tasks.json` -- 添加 `_meta` 条目

**复用（不变）：**
17. `Tool` 接口 -- 现有工具契约
18. `ToolDefinition` -- 现有工具 schema
19. `ToolSourceInfo` / `ToolSourceType` -- 现有来源追踪（BUILTIN、TOOL_GROUP、JS_EXTENSION）
20. `ToolExecutionEngine` -- 现有执行引擎
21. `SkillRegistry` / `LoadSkillTool` -- 模式参考

### 数据流

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

## 详细设计

### 步骤 1：新增模型 `ToolGroupDefinition`

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/core/model/ToolGroupDefinition.kt`（新增）

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

### 步骤 2：扩展 `ToolRegistry`

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolRegistry.kt`

新增存储字段和方法：

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

同时修改现有的 `getToolGroups()` 方法，使其包含所有设置了 `groupName` 的工具，而不仅限于 `TOOL_GROUP` 类型：

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

此修改是必要的，因为以 `ToolSourceInfo(type = BUILTIN, groupName = "config")` 注册的 Kotlin 工具也应出现在组查询结果中。

### 步骤 3：新增工具 `LoadToolGroupTool`

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/tool/builtin/LoadToolGroupTool.kt`（新增）

镜像 `LoadSkillTool` 模式：

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

### 步骤 4：修改 `SendMessageUseCase` -- 动态工具列表

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/usecase/SendMessageUseCase.kt`

#### 4a：将静态工具加载替换为动态加载

当前代码（第 134-137 行）：
```kotlin
val agentToolDefs: List<ToolDefinition>? = toolRegistry
    .getAllToolDefinitions()
    .takeIf { it.isNotEmpty() }
```

替换为：
```kotlin
// Track loaded groups for this conversation
val loadedGroupNames = mutableSetOf<String>()
// Start with core tools only
val activeToolDefs = toolRegistry.getCoreToolDefinitions().toMutableList()
```

#### 4b：检测 `load_tool_group` 结果并扩展工具列表

在工具执行部分，`executeToolsParallel` 返回后，添加组扩展逻辑：

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

辅助方法：
```kotlin
private fun extractGroupName(
    toolRequests: List<ToolRequest>,
    toolCallId: String
): String? {
    val request = toolRequests.find { it.id == toolCallId } ?: return null
    return request.parameters["group_name"] as? String
}
```

#### 4c：使用活跃工具列表执行

当前代码（第 310 行）：
```kotlin
availableToolNames = toolRegistry.getAllToolNames()
```

替换为：
```kotlin
availableToolNames = activeToolDefs.map { it.name }
```

在每次循环迭代中将 `activeToolDefs`（而非之前的 `agentToolDefs`）传递给适配器，使 LLM 在加载组后能看到扩展后的工具列表。

#### 4d：添加工具组系统提示词注入

新增方法，类似现有的 `buildSystemPromptWithSkills`：

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

在提示词组装链中，于 `buildSystemPromptWithSkills` 之后、压缩之前调用此方法：

```kotlin
// Existing:
var systemPrompt = buildSystemPromptWithSkills(baseSystemPrompt)
// New (add after):
systemPrompt = buildSystemPromptWithToolGroups(systemPrompt)
```

### 步骤 5：修改 `ToolModule` -- 注册工具组

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/di/ToolModule.kt`

#### 5a：将 `LoadToolGroupTool` 注册为核心工具

```kotlin
single { LoadToolGroupTool(get()) }

// In ToolRegistry setup:
register(get<LoadToolGroupTool>(), ToolSourceInfo.BUILTIN)
```

#### 5b：为 Kotlin 工具分配 `groupName`

将分组工具的注册调用从：
```kotlin
register(tool, ToolSourceInfo.BUILTIN)
```
改为：
```kotlin
register(tool, ToolSourceInfo(type = ToolSourceType.BUILTIN, groupName = "config"))
```

完整的组分配如下：

| 组名 | 工具 |
|---|---|
| `config` | ListProvidersTool, CreateProviderTool, UpdateProviderTool, DeleteProviderTool, ListModelsTool, FetchModelsTool, SetDefaultModelTool, AddModelTool, DeleteModelTool, ListAgentsTool, UpdateAgentTool, DeleteAgentTool, GetConfigTool, SetConfigTool, ManageEnvVarTool, ListToolStatesTool, SetToolEnabledTool |
| `pdf` | PdfInfoTool, PdfExtractTextTool, PdfRenderPageTool |
| `scheduled_tasks` | ScheduleTaskTool, ListScheduledTasksTool, RunScheduledTaskTool, UpdateScheduledTaskTool, DeleteScheduledTaskTool |
| `js_tool_management` | CreateJsToolTool, ListUserToolsTool, UpdateJsToolTool, DeleteJsToolTool |

#### 5c：注册组元数据

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

JS 组元数据通过 `_meta` 条目自动注册（见步骤 6）。

#### 5d：JS 内置工具注册时附带 groupName

对于 JS 工具组（通过 JSON 数组格式识别），更改注册方式：

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

此处需要 `JsToolLoadResult` 暴露 `groupNames` 和 `groupDefinitions`（见步骤 6）。

#### 5e：核心工具（无 groupName，始终加载）

以下工具保持以 `ToolSourceInfo.BUILTIN` 注册（无 `groupName`）：

- `load_skill`（元工具）
- `load_tool_group`（元工具）
- `save_memory`（记忆）
- `search_history`（历史）
- `exec`（执行）
- `js_eval`（执行）
- `webfetch`（Web）
- `browser`（Web）
- `create_agent`（agent 管理）
- `read_file`（JS 内置，单文件）
- `write_file`（JS 内置，单文件）
- `get_current_time`（JS 内置，单文件）
- `http_request`（JS 内置，单文件）

### 步骤 6：修改 `JsToolLoader` -- 暴露组元数据

**文件：** `app/src/main/kotlin/com/oneclaw/shadow/tool/js/JsToolLoader.kt`

#### 6a：为组 manifest 解析添加 `_meta` 支持

在 `parseGroupManifest()` 中，检测可选的第一个条目是否包含 `"_meta": true`：

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

实现如下：

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

#### 6b：在加载结果中暴露组信息

向 JS 工具加载结果添加字段（或修改现有返回类型）：

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

在 `loadBuiltinTools()` 中收集组信息：

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

### 步骤 7：更新 JS Manifest，添加 `_meta`

**文件：** 所有 `app/src/main/assets/js/tools/google_*.json`

在每个组 manifest 数组的第一个元素处添加 `_meta` 条目。示例如下：

**google_gmail.json：**
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

**google_drive.json：**
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

**google_calendar.json：**
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

**google_contacts.json：**
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

**google_docs.json：**
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

**google_sheets.json：**
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

**google_slides.json：**
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

**google_forms.json：**
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

**google_tasks.json：**
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

**google_gmail_settings.json：**
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

## 修改文件汇总

| # | 文件 | 变更类型 | 说明 |
|---|------|-------------|-------------|
| 1 | `core/model/ToolGroupDefinition.kt` | **新增** | 组元数据 data class |
| 2 | `tool/engine/ToolRegistry.kt` | 修改 | 添加组注册、核心/分组查询，更新 `getToolGroups()` |
| 3 | `tool/builtin/LoadToolGroupTool.kt` | **新增** | 按需加载工具组的元工具 |
| 4 | `feature/chat/usecase/SendMessageUseCase.kt` | 修改 | 动态工具列表、组提示词注入、加载时组扩展 |
| 5 | `di/ToolModule.kt` | 修改 | 组注册、LoadToolGroupTool、Kotlin 工具附带 groupName |
| 6 | `tool/js/JsToolLoader.kt` | 修改 | 解析 `_meta`，在结果中暴露 groupNames/groupDefinitions |
| 7 | `assets/js/tools/google_gmail.json` | 修改 | 添加 `_meta` 条目 |
| 8 | `assets/js/tools/google_gmail_settings.json` | 修改 | 添加 `_meta` 条目 |
| 9 | `assets/js/tools/google_drive.json` | 修改 | 添加 `_meta` 条目 |
| 10 | `assets/js/tools/google_calendar.json` | 修改 | 添加 `_meta` 条目 |
| 11 | `assets/js/tools/google_contacts.json` | 修改 | 添加 `_meta` 条目 |
| 12 | `assets/js/tools/google_docs.json` | 修改 | 添加 `_meta` 条目 |
| 13 | `assets/js/tools/google_sheets.json` | 修改 | 添加 `_meta` 条目 |
| 14 | `assets/js/tools/google_slides.json` | 修改 | 添加 `_meta` 条目 |
| 15 | `assets/js/tools/google_forms.json` | 修改 | 添加 `_meta` 条目 |
| 16 | `assets/js/tools/google_tasks.json` | 修改 | 添加 `_meta` 条目 |

## API 设计

### `load_tool_group` 工具 Schema

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

### 成功响应格式

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

### 错误响应格式

```
Tool group 'nonexistent' not found. Available groups: config, pdf, scheduled_tasks, js_tool_management, google_gmail, google_gmail_settings, google_drive, google_calendar, google_contacts, google_docs, google_sheets, google_slides, google_forms, google_tasks
```

## 系统提示词格式

工具组列表在 skill 注入之后、压缩之前注入系统提示词：

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

## 测试策略

### 单元测试

#### ToolRegistry 测试

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

#### LoadToolGroupTool 测试

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

#### JsToolLoader `_meta` 测试

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

### 集成测试

#### SendMessageUseCase 工具组集成

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

### 手动验证

1. 发送"check my email"-- 验证 agent 先调用 `load_tool_group("google_gmail")`，再使用 Gmail 工具
2. 发送"what time is it?"-- 验证 agent 直接使用 `get_current_time`，无需加载任何组
3. 发送"create a spreadsheet"-- 验证 agent 先调用 `load_tool_group("google_sheets")`
4. 加载组后开启新会话 -- 验证组未被预加载（每次会话重置）

## 迁移说明

### 向后兼容性

- 所有现有工具继续正常工作
- 唯一的行为变更是分组工具在使用前需要先调用 `load_tool_group`
- 如果用户的 agent 系统提示词中直接引用了分组工具，agent 将在"Available Tool Groups"部分看到它们，并学会先加载对应组

### 回滚

- 从 `ToolModule` 的 `ToolSourceInfo` 注册中移除 `groupName`
- 移除 `LoadToolGroupTool` 注册
- 将 `SendMessageUseCase` 恢复为使用 `getAllToolDefinitions()`
- 无数据库变更，无需数据迁移

## 性能影响

### Token 节省估算

| 场景 | 变更前 | 变更后 | 节省量 |
|----------|--------|-------|---------|
| 简单查询（无需工具） | ~20,000 tokens | ~3,400 tokens | ~16,600 tokens (83%) |
| 邮件任务（仅 Gmail） | ~20,000 tokens | ~5,000 tokens | ~15,000 tokens (75%) |
| 配置 + 邮件任务 | ~20,000 tokens | ~10,400 tokens | ~9,600 tokens (48%) |
| 加载全部组 | ~20,000 tokens | ~20,400 tokens | -400 tokens（组列表开销） |

### 延迟影响

- `load_tool_group` 执行时间：< 10ms（内存查询）
- 加载组所需的额外 LLM 轮次：约 1-3 秒（每组每次会话一次性开销）
- 净收益：每轮 token 减少意味着 LLM 响应更快

## 安全考量

- 无需新增权限
- 工具组路由不绕过现有工具权限检查
- `LoadToolGroupTool` 无 `requiredPermissions`（加载工具定义是安全操作）
- 工具执行仍经过 `ToolExecutionEngine` 的完整权限检查

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
