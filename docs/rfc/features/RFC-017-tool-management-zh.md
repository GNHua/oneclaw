# RFC-017: 工具管理

## 文档信息
- **RFC ID**: RFC-017
- **关联 PRD**: FEAT-017 (工具管理)
- **依赖项**: RFC-004 (工具系统), RFC-009 (设置), RFC-012 (JS 工具引擎), RFC-014 (Agent 技能), RFC-015 (JS 工具迁移), RFC-018 (JS 工具组)
- **创建时间**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: 草稿
- **作者**: TBD

## 概述

### 背景
OneClaw 目前没有集中的页面来查看、检查或控制工具。工具只能通过单个 Agent 的配置（FEAT-002）查看，也无法全局禁用某个工具，或查看工具的完整定义（参数、权限、超时时间）。随着工具生态系统的扩展——包括内置 Kotlin 工具、内置 JS 工具（RFC-015）、用户 JS 扩展（RFC-012）和工具组（RFC-018）——管理 UI 变得不可或缺。

### 目标
1. 在设置页面中添加"管理工具"入口，点击后跳转至工具管理页面
2. 将所有已注册的工具按三个分区展示：内置工具（平铺）、工具组（可折叠）、独立 JS 工具（平铺）
3. 为每个工具提供全局启用/禁用开关，状态持久化至 SharedPreferences
4. 提供工具组级别的启用/禁用，并与子工具保持父子联动关系
5. 展示工具详情视图，包含完整的工具元数据（名称、描述、参数、权限、超时时间、来源、所属组）
6. 将全局禁用检查集成至 `ToolExecutionEngine`，阻止已禁用工具的执行
7. 在 Agent 配置（FEAT-002）中将全局禁用的工具显示为灰色且不可选中

### 非目标
- 在此页面中创建、编辑或删除工具（JS 工具通过文件系统管理，参见 FEAT-012；技能通过 FEAT-014 管理）
- 工具执行历史或日志
- 工具测试或试运行能力
- 工具搜索/筛选（延后实现）
- 工具在活跃对话中被禁用时的通知
- 工具启用/禁用配置的批量导入/导出

## 技术方案

### 整体设计

```
┌─────────────────────────────────────────────────────────────────────┐
│                             UI Layer                                │
│                                                                     │
│  SettingsScreen          ToolManagementScreen    AgentDetailScreen  │
│  (new "Manage Tools"     (list + detail)        (grayed-out tools) │
│   entry)                       │                       │           │
│                          ToolManagementViewModel  AgentDetailVM     │
│                                │                       │           │
└────────────────────────────────┼───────────────────────┼───────────┘
                                 │                       │
┌────────────────────────────────┼───────────────────────┼───────────┐
│                          Domain / Data Layer            │           │
│                                │                       │           │
│  ToolEnabledStateStore ────────┤                       │           │
│  (SharedPreferences)           │                       │           │
│                                │                       │           │
│  ToolRegistry ─────────────────┤───────────────────────┘           │
│  (+ ToolSourceInfo tracking)   │                                   │
│                                │                                   │
│  ToolExecutionEngine ──────────┘                                   │
│  (new global enable check)                                         │
└─────────────────────────────────────────────────────────────────────┘
```

**修改的文件**: 7 个
- `ToolRegistry.kt` -- 添加来源信息追踪和工具组查询方法
- `ToolExecutionEngine.kt` -- 添加全局启用检查
- `ToolModule.kt` -- 注册工具时附带来源信息，关联新依赖
- `SettingsScreen.kt` -- 添加"管理工具"入口
- `AgentDetailViewModel.kt` -- 为工具列表项添加全局禁用标志
- `AgentDetailScreen.kt` -- 为全局禁用的工具展示视觉提示
- `AgentUiState.kt` -- 在 `ToolOptionItem` 中添加 `isGloballyDisabled` 字段

**新增的文件**: 5 个
- `ToolEnabledStateStore.kt` -- 用于存储启用/禁用状态的 SharedPreferences 存储层
- `ToolSourceInfo.kt` -- 工具来源元数据的数据模型
- `ToolManagementViewModel.kt` -- 工具管理页面的 ViewModel
- `ToolManagementScreen.kt` -- 工具列表与详情视图的 Composable
- `Route.kt` 更新（或新增路由项）-- 工具管理页面的导航路由

### 核心组件

#### 1. ToolSourceInfo（领域模型）

追踪每个工具的来源和所属工具组。这是关于工具加载方式的元数据，与工具一起存储在注册表中。

```kotlin
package com.oneclaw.shadow.core.model

/**
 * Source type classification for tools in the management UI.
 */
enum class ToolSourceType {
    /** Kotlin-implemented tool or JS tool loaded from app assets. */
    BUILTIN,
    /** JS tool loaded from an array manifest file (RFC-018). */
    TOOL_GROUP,
    /** Single-file JS extension tool from device storage, not part of any group. */
    JS_EXTENSION
}

/**
 * Metadata describing the origin of a registered tool.
 */
data class ToolSourceInfo(
    val type: ToolSourceType,
    val groupName: String? = null,
    val filePath: String? = null
)
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/core/model/ToolSourceInfo.kt`

**分类规则**：
- `LoadSkillTool`（Kotlin）：`ToolSourceType.BUILTIN`，groupName=null
- 从 `assets/js/tools/` 加载的工具（内置 JS）：`ToolSourceType.BUILTIN`，groupName=null
- 从设备存储中的数组清单文件加载的工具：`ToolSourceType.TOOL_GROUP`，groupName 从清单文件名中获取
- 从设备存储中加载的单文件 JS 工具：`ToolSourceType.JS_EXTENSION`，groupName=null

**工具组名称推导规则**：取清单文件名（去除扩展名），将下划线替换为空格，每个单词首字母大写。例如，`google_drive.json` 变为 "Google Drive"。

#### 2. ToolRegistry 增强

在现有 `ToolRegistry` 中添加来源信息追踪和工具组查询方法。

```kotlin
class ToolRegistry {
    private val tools = mutableMapOf<String, Tool>()
    private val sourceInfoMap = mutableMapOf<String, ToolSourceInfo>()

    /**
     * Register a tool with optional source metadata.
     * If sourceInfo is null, defaults to BUILTIN.
     */
    fun register(tool: Tool, sourceInfo: ToolSourceInfo? = null) {
        val name = tool.definition.name
        require(!tools.containsKey(name)) {
            "Tool with name '$name' is already registered"
        }
        tools[name] = tool
        sourceInfoMap[name] = sourceInfo ?: ToolSourceInfo(type = ToolSourceType.BUILTIN)
    }

    /** Get the source info for a tool. */
    fun getToolSourceInfo(name: String): ToolSourceInfo? = sourceInfoMap[name]

    /** Get all source info entries. */
    fun getAllToolSourceInfo(): Map<String, ToolSourceInfo> = sourceInfoMap.toMap()

    /**
     * Get tool names grouped by their group name.
     * Only returns tools with ToolSourceType.TOOL_GROUP.
     */
    fun getToolGroups(): Map<String, List<String>> {
        return sourceInfoMap.entries
            .filter { it.value.type == ToolSourceType.TOOL_GROUP && it.value.groupName != null }
            .groupBy({ it.value.groupName!! }, { it.key })
    }

    // ... existing methods unchanged: getTool, getAllToolDefinitions,
    //     getToolDefinitionsByNames, hasTool, getAllToolNames, unregister, etc.
}
```

**向后兼容性**：现有的单参数 `register(tool: Tool)` 调用方式仍然受支持。`sourceInfo` 参数默认为 `null`，对应 `ToolSourceType.BUILTIN`。所有现有代码无需修改即可继续运行。

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolRegistry.kt`

#### 3. ToolEnabledStateStore

将单个工具和工具组的全局启用/禁用状态持久化至 SharedPreferences。

```kotlin
package com.oneclaw.shadow.tool.engine

import android.content.Context
import android.content.SharedPreferences

class ToolEnabledStateStore(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(
        "tool_enabled_state", Context.MODE_PRIVATE
    )

    /**
     * Check if a tool is globally enabled.
     * Default: true (all tools enabled unless explicitly disabled).
     */
    fun isToolEnabled(toolName: String): Boolean =
        prefs.getBoolean("tool_enabled_$toolName", true)

    /**
     * Set the global enabled state for a tool.
     */
    fun setToolEnabled(toolName: String, enabled: Boolean) {
        prefs.edit().putBoolean("tool_enabled_$toolName", enabled).apply()
    }

    /**
     * Check if a tool group is globally enabled.
     * Default: true (all groups enabled unless explicitly disabled).
     */
    fun isGroupEnabled(groupName: String): Boolean =
        prefs.getBoolean("tool_group_enabled_$groupName", true)

    /**
     * Set the global enabled state for a tool group.
     */
    fun setGroupEnabled(groupName: String, enabled: Boolean) {
        prefs.edit().putBoolean("tool_group_enabled_$groupName", enabled).apply()
    }

    /**
     * Check if a tool is effectively enabled, considering both
     * individual and group state.
     *
     * A tool is effectively enabled only if:
     * 1. Its individual toggle is ON, AND
     * 2. If it belongs to a group, the group toggle is also ON.
     */
    fun isToolEffectivelyEnabled(toolName: String, groupName: String?): Boolean {
        if (!isToolEnabled(toolName)) return false
        if (groupName != null && !isGroupEnabled(groupName)) return false
        return true
    }
}
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolEnabledStateStore.kt`

**设计决策**：
- 使用普通 `SharedPreferences`（而非 `EncryptedSharedPreferences`），因为启用/禁用状态不是敏感数据。
- 默认值为 `true`（启用），确保向后兼容性。
- `isToolEffectivelyEnabled` 方法封装了两层检查（单个工具 + 工具组），调用方无需自行实现此逻辑。

#### 4. ToolExecutionEngine 集成

在现有工具执行流程中添加全局启用检查。

```kotlin
class ToolExecutionEngine(
    private val toolRegistry: ToolRegistry,
    private val permissionChecker: PermissionChecker,
    private val enabledStateStore: ToolEnabledStateStore  // NEW dependency
) {

    suspend fun executeTool(
        toolName: String,
        parameters: Map<String, Any?>,
        agentToolIds: List<String>
    ): ToolResult {
        // Step 1: Look up tool in registry
        val tool = toolRegistry.getTool(toolName)
            ?: return ToolResult.error("tool_not_found", "Tool '$toolName' not found")

        // Step 2: Check availability (per-agent)
        if (agentToolIds.isNotEmpty() && toolName !in agentToolIds) {
            return ToolResult.error(
                "tool_not_available",
                "Tool '$toolName' is not available for this agent"
            )
        }

        // Step 3: NEW -- Check global enable state
        val sourceInfo = toolRegistry.getToolSourceInfo(toolName)
        if (!enabledStateStore.isToolEffectivelyEnabled(toolName, sourceInfo?.groupName)) {
            return ToolResult.error(
                "tool_globally_disabled",
                "Tool '$toolName' is globally disabled and not available."
            )
        }

        // Step 4: Validate parameters (existing)
        // Step 5: Check permissions (existing)
        // Step 6: Execute with timeout (existing)
        // ... rest unchanged
    }
}
```

**错误码**：`tool_globally_disabled` -- 一种新的独立错误类型，使模型能够识别工具已被禁用（而非找不到或未授权）。

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolExecutionEngine.kt`

#### 5. ToolManagementViewModel

管理工具管理页面的状态。

```kotlin
package com.oneclaw.shadow.feature.tool

import androidx.lifecycle.ViewModel
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolSourceInfo
import com.oneclaw.shadow.core.model.ToolSourceType
import com.oneclaw.shadow.tool.engine.ToolEnabledStateStore
import com.oneclaw.shadow.tool.engine.ToolRegistry
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class ToolManagementUiState(
    val builtInTools: List<ToolUiItem> = emptyList(),
    val toolGroups: List<ToolGroupUiItem> = emptyList(),
    val standaloneTools: List<ToolUiItem> = emptyList(),
    val selectedTool: ToolDetailUiItem? = null,
    val snackbarMessage: String? = null
)

data class ToolUiItem(
    val name: String,
    val description: String,
    val sourceType: ToolSourceType,
    val isEnabled: Boolean,
    val groupName: String? = null
)

data class ToolGroupUiItem(
    val groupName: String,
    val tools: List<ToolUiItem>,
    val isGroupEnabled: Boolean,
    val isExpanded: Boolean = false
)

data class ToolDetailUiItem(
    val name: String,
    val description: String,
    val parametersSchema: ToolParametersSchema,
    val requiredPermissions: List<String>,
    val timeoutSeconds: Int,
    val sourceType: ToolSourceType,
    val groupName: String?,
    val filePath: String?,
    val isEnabled: Boolean
)

class ToolManagementViewModel(
    private val toolRegistry: ToolRegistry,
    private val enabledStateStore: ToolEnabledStateStore
) : ViewModel() {

    private val _uiState = MutableStateFlow(ToolManagementUiState())
    val uiState: StateFlow<ToolManagementUiState> = _uiState.asStateFlow()

    init {
        loadTools()
    }

    fun loadTools() {
        val allDefs = toolRegistry.getAllToolDefinitions()
        val allSourceInfo = toolRegistry.getAllToolSourceInfo()
        val groups = toolRegistry.getToolGroups()

        val builtIn = mutableListOf<ToolUiItem>()
        val standalone = mutableListOf<ToolUiItem>()

        allDefs.forEach { def ->
            val source = allSourceInfo[def.name]
                ?: ToolSourceInfo(type = ToolSourceType.BUILTIN)
            when (source.type) {
                ToolSourceType.BUILTIN -> builtIn.add(def.toUiItem(source))
                ToolSourceType.JS_EXTENSION -> standalone.add(def.toUiItem(source))
                ToolSourceType.TOOL_GROUP -> { /* handled in group section */ }
            }
        }

        val toolGroupItems = groups.map { (groupName, toolNames) ->
            val groupTools = toolNames.mapNotNull { name ->
                val def = allDefs.find { it.name == name } ?: return@mapNotNull null
                val source = allSourceInfo[name]
                    ?: ToolSourceInfo(ToolSourceType.TOOL_GROUP, groupName)
                def.toUiItem(source)
            }.sortedBy { it.name }

            ToolGroupUiItem(
                groupName = groupName,
                tools = groupTools,
                isGroupEnabled = enabledStateStore.isGroupEnabled(groupName)
            )
        }.sortedBy { it.groupName }

        _uiState.update {
            it.copy(
                builtInTools = builtIn.sortedBy { t -> t.name },
                toolGroups = toolGroupItems,
                standaloneTools = standalone.sortedBy { t -> t.name }
            )
        }
    }

    fun toggleToolEnabled(toolName: String) {
        val currentState = enabledStateStore.isToolEnabled(toolName)
        val newState = !currentState
        enabledStateStore.setToolEnabled(toolName, newState)

        val label = if (newState) "enabled" else "disabled"

        // Check if all tools in a group are now disabled -> auto-disable group
        val sourceInfo = toolRegistry.getToolSourceInfo(toolName)
        if (sourceInfo?.type == ToolSourceType.TOOL_GROUP && sourceInfo.groupName != null) {
            val groupTools = toolRegistry.getToolGroups()[sourceInfo.groupName] ?: emptyList()
            val allDisabled = groupTools.all { !enabledStateStore.isToolEnabled(it) }
            if (allDisabled) {
                enabledStateStore.setGroupEnabled(sourceInfo.groupName, false)
            }
        }

        loadTools()
        _uiState.update { it.copy(snackbarMessage = "$toolName $label") }
    }

    fun toggleGroupEnabled(groupName: String) {
        val currentState = enabledStateStore.isGroupEnabled(groupName)
        val newState = !currentState
        enabledStateStore.setGroupEnabled(groupName, newState)

        val label = if (newState) "enabled" else "disabled"
        loadTools()
        _uiState.update { it.copy(snackbarMessage = "$groupName group $label") }
    }

    fun toggleGroupExpanded(groupName: String) {
        _uiState.update { state ->
            state.copy(
                toolGroups = state.toolGroups.map { group ->
                    if (group.groupName == groupName) {
                        group.copy(isExpanded = !group.isExpanded)
                    } else group
                }
            )
        }
    }

    fun selectTool(toolName: String) {
        val def = toolRegistry.getAllToolDefinitions().find { it.name == toolName }
            ?: return
        val source = toolRegistry.getToolSourceInfo(toolName)
            ?: ToolSourceInfo(ToolSourceType.BUILTIN)

        _uiState.update {
            it.copy(
                selectedTool = ToolDetailUiItem(
                    name = def.name,
                    description = def.description,
                    parametersSchema = def.parametersSchema,
                    requiredPermissions = def.requiredPermissions,
                    timeoutSeconds = def.timeoutSeconds,
                    sourceType = source.type,
                    groupName = source.groupName,
                    filePath = source.filePath,
                    isEnabled = enabledStateStore.isToolEffectivelyEnabled(
                        def.name, source.groupName
                    )
                )
            )
        }
    }

    fun clearSelectedTool() {
        _uiState.update { it.copy(selectedTool = null) }
    }

    fun clearSnackbar() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    private fun ToolDefinition.toUiItem(source: ToolSourceInfo): ToolUiItem {
        return ToolUiItem(
            name = this.name,
            description = this.description,
            sourceType = source.type,
            isEnabled = enabledStateStore.isToolEffectivelyEnabled(
                this.name, source.groupName
            ),
            groupName = source.groupName
        )
    }
}
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/feature/tool/ToolManagementViewModel.kt`

#### 6. ToolManagementScreen（UI）

工具管理页面包含两个视图：工具列表和工具详情，通过 ViewModel 的页面状态管理（而非独立的导航目标）。

```kotlin
package com.oneclaw.shadow.feature.tool

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclaw.shadow.core.model.ToolSourceType
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolManagementScreen(
    onNavigateBack: () -> Unit,
    viewModel: ToolManagementViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Handle snackbar messages
    uiState.snackbarMessage?.let { message ->
        LaunchedEffect(message) {
            snackbarHostState.showSnackbar(message, duration = SnackbarDuration.Short)
            viewModel.clearSnackbar()
        }
    }

    // If a tool is selected, show detail view
    if (uiState.selectedTool != null) {
        ToolDetailView(
            tool = uiState.selectedTool!!,
            onBack = { viewModel.clearSelectedTool() },
            onToggleEnabled = { viewModel.toggleToolEnabled(it) }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Tools") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = 16.dp)
        ) {
            // Built-in section
            if (uiState.builtInTools.isNotEmpty()) {
                item {
                    SectionHeader("BUILT-IN")
                }
                items(uiState.builtInTools, key = { it.name }) { tool ->
                    ToolListItem(
                        tool = tool,
                        onToggle = { viewModel.toggleToolEnabled(tool.name) },
                        onClick = { viewModel.selectTool(tool.name) }
                    )
                }
            }

            // Tool Groups section
            if (uiState.toolGroups.isNotEmpty()) {
                item {
                    SectionHeader("TOOL GROUPS")
                }
                uiState.toolGroups.forEach { group ->
                    item(key = "group_${group.groupName}") {
                        ToolGroupHeader(
                            group = group,
                            onToggleGroup = {
                                viewModel.toggleGroupEnabled(group.groupName)
                            },
                            onToggleExpand = {
                                viewModel.toggleGroupExpanded(group.groupName)
                            }
                        )
                    }
                    if (group.isExpanded) {
                        items(
                            group.tools,
                            key = { "group_tool_${it.name}" }
                        ) { tool ->
                            val isInteractive = group.isGroupEnabled
                            ToolListItem(
                                tool = tool,
                                onToggle = {
                                    if (isInteractive) {
                                        viewModel.toggleToolEnabled(tool.name)
                                    }
                                },
                                onClick = { viewModel.selectTool(tool.name) },
                                isGroupChild = true,
                                isGroupDisabled = !group.isGroupEnabled
                            )
                        }
                    }
                }
            }

            // Standalone section
            if (uiState.standaloneTools.isNotEmpty()) {
                item {
                    SectionHeader("STANDALONE")
                }
                items(uiState.standaloneTools, key = { it.name }) { tool ->
                    ToolListItem(
                        tool = tool,
                        onToggle = { viewModel.toggleToolEnabled(tool.name) },
                        onClick = { viewModel.selectTool(tool.name) }
                    )
                }
            }

            // Empty state
            if (uiState.builtInTools.isEmpty() &&
                uiState.toolGroups.isEmpty() &&
                uiState.standaloneTools.isEmpty()
            ) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "No tools available.",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ToolListItem(
    tool: ToolUiItem,
    onToggle: () -> Unit,
    onClick: () -> Unit,
    isGroupChild: Boolean = false,
    isGroupDisabled: Boolean = false
) {
    val itemAlpha = if (isGroupDisabled) 0.38f else 1f
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .alpha(itemAlpha)
            .padding(
                start = if (isGroupChild) 32.dp else 16.dp,
                end = 16.dp,
                top = 8.dp,
                bottom = 8.dp
            ),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.bodyLarge,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.width(8.dp))
                SourceBadge(tool.sourceType)
            }
            Text(
                text = tool.description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
        Switch(
            checked = tool.isEnabled,
            onCheckedChange = { onToggle() },
            enabled = !isGroupDisabled
        )
    }
}

@Composable
private fun SourceBadge(sourceType: ToolSourceType) {
    val label = when (sourceType) {
        ToolSourceType.BUILTIN -> "Built-in"
        ToolSourceType.TOOL_GROUP -> "Tool Group"
        ToolSourceType.JS_EXTENSION -> "JS Extension"
    }
    Surface(
        shape = MaterialTheme.shapes.extraSmall,
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.padding(horizontal = 4.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun ToolGroupHeader(
    group: ToolGroupUiItem,
    onToggleGroup: () -> Unit,
    onToggleExpand: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggleExpand)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (group.isExpanded) Icons.Default.ExpandLess
                else Icons.Default.ExpandMore,
            contentDescription = if (group.isExpanded) "Collapse" else "Expand",
            modifier = Modifier.size(24.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = group.groupName,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "${group.tools.size} tools",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 8.dp)
        )
        Switch(
            checked = group.isGroupEnabled,
            onCheckedChange = { onToggleGroup() }
        )
    }
}
```

**位置**: `app/src/main/kotlin/com/oneclaw/shadow/feature/tool/ToolManagementScreen.kt`

#### 7. ToolDetailView

工具详情页面的独立 Composable，在用户点击某个工具时展示。

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ToolDetailView(
    tool: ToolDetailUiItem,
    onBack: () -> Unit,
    onToggleEnabled: (String) -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tool Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Header
            item {
                Text(text = tool.name, style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = tool.description, style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            // Metadata
            item {
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("Source", tool.sourceType.displayLabel())
                DetailRow("Group", tool.groupName ?: "None")
                DetailRow("Timeout", "${tool.timeoutSeconds} seconds")
                DetailRow("Permissions",
                    tool.requiredPermissions.ifEmpty { listOf("None") }.joinToString(", "))
                if (tool.filePath != null) {
                    DetailRow("File", tool.filePath)
                }
            }

            // Enable toggle
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Enabled", style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f))
                    Switch(
                        checked = tool.isEnabled,
                        onCheckedChange = { onToggleEnabled(tool.name) }
                    )
                }
            }

            // Parameters section
            item {
                HorizontalDivider()
                Spacer(modifier = Modifier.height(8.dp))
                Text("PARAMETERS", style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary)
            }

            if (tool.parametersSchema.properties.isEmpty()) {
                item {
                    Text("No parameters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                items(tool.parametersSchema.properties.entries.toList()) { (name, param) ->
                    val isRequired = name in tool.parametersSchema.required
                    ParameterItem(name, param, isRequired)
                }
            }
        }
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(modifier = Modifier.padding(vertical = 2.dp)) {
        Text(
            text = "$label: ",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun ParameterItem(
    name: String,
    param: ToolParameter,
    isRequired: Boolean
) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Row {
            Text(
                text = name,
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace
            )
            Text(
                text = " (${param.type})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (isRequired) " *required" else " optional",
                style = MaterialTheme.typography.labelSmall,
                color = if (isRequired) MaterialTheme.colorScheme.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Text(
            text = param.description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (param.enum != null) {
            Text(
                text = "Values: ${param.enum.joinToString(", ")}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (param.default != null) {
            Text(
                text = "Default: ${param.default}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

private fun ToolSourceType.displayLabel(): String = when (this) {
    ToolSourceType.BUILTIN -> "Built-in"
    ToolSourceType.TOOL_GROUP -> "Tool Group"
    ToolSourceType.JS_EXTENSION -> "JS Extension"
}
```

**位置**: 与 `ToolManagementScreen.kt` 同一文件（或根据需要拆分为 `ToolDetailView.kt`）。

#### 8. SettingsScreen 集成

添加 `onManageTools` 参数和新的"工具"分区。

```kotlin
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onManageProviders: () -> Unit,
    onManageAgents: () -> Unit = {},
    onManageTools: () -> Unit = {},      // NEW
    onUsageStatistics: () -> Unit = {},
    onDataBackup: () -> Unit = {},
    onMemory: () -> Unit = {},
    onSkills: () -> Unit = {},
    themeManager: ThemeManager = koinInject()
) {
    // ... existing code ...

    // After "Agents" section, before "Usage" section:
    SectionHeader("Tools")
    SettingsItem(
        title = "Manage Tools",
        subtitle = "View and enable/disable tools",
        onClick = onManageTools
    )
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

    // ... existing Usage, Memory, Skills, Data & Backup sections ...
}
```

#### 9. AgentDetailViewModel 集成

修改 `loadAvailableTools()` 以包含全局禁用状态。

```kotlin
// In AgentDetailViewModel.kt

fun loadAvailableTools() {
    val tools = toolRegistry.getAllToolDefinitions().map { toolDef ->
        val sourceInfo = toolRegistry.getToolSourceInfo(toolDef.name)
        val isGloballyDisabled = !enabledStateStore.isToolEffectivelyEnabled(
            toolDef.name, sourceInfo?.groupName
        )
        ToolOptionItem(
            name = toolDef.name,
            description = toolDef.description,
            isSelected = toolDef.name in selectedToolIds,
            isGloballyDisabled = isGloballyDisabled
        )
    }
    _uiState.update { it.copy(availableTools = tools) }
}
```

**ToolOptionItem 增强**（位于 `AgentUiState.kt`）：

```kotlin
data class ToolOptionItem(
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isGloballyDisabled: Boolean = false  // NEW
)
```

#### 10. AgentDetailScreen 集成

为全局禁用的工具展示视觉提示。

```kotlin
// In AgentDetailScreen.kt, tool list section:

items(uiState.availableTools, key = { it.name }) { tool ->
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .alpha(if (tool.isGloballyDisabled) 0.38f else 1f)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = tool.name in uiState.selectedToolIds,
            onCheckedChange = {
                if (!tool.isGloballyDisabled) viewModel.toggleTool(tool.name)
            },
            enabled = !uiState.isBuiltIn && !tool.isGloballyDisabled
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = tool.name, style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace)
            Text(text = tool.description, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (tool.isGloballyDisabled) {
                Text(
                    text = "Globally disabled",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
```

#### 11. 导航路由

为工具管理页面添加路由。

```kotlin
// In Route.kt (sealed class)
data object ManageTools : Route
```

```kotlin
// In NavHost setup:
composable<Route.ManageTools> {
    ToolManagementScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

```kotlin
// In Settings navigation:
SettingsScreen(
    onManageTools = { navController.navigate(Route.ManageTools) },
    // ... existing params
)
```

#### 12. Koin DI 模块

在对应模块中注册新组件。

```kotlin
// In ToolModule.kt:
single { ToolEnabledStateStore(androidContext()) }

// ToolExecutionEngine now takes ToolEnabledStateStore:
single { ToolExecutionEngine(get(), get(), get()) }

// In FeatureModule.kt:
viewModel { ToolManagementViewModel(get(), get()) }

// AgentDetailViewModel now takes ToolEnabledStateStore:
viewModel { AgentDetailViewModel(get(), get(), get(), get()) }
```

### ToolModule 注册时附带来源信息

更新 `ToolModule.kt` 中的工具注册调用，使其包含来源信息：

```kotlin
val toolModule = module {
    // ... existing JS engine, library bridge, etc. ...

    single {
        ToolRegistry().apply {
            // LoadSkillTool: Kotlin built-in
            register(
                LoadSkillTool(get()),
                ToolSourceInfo(type = ToolSourceType.BUILTIN)
            )

            // Built-in JS tools from assets
            val builtInTools = jsToolLoader.loadBuiltInTools()
            builtInTools.forEach { tool ->
                register(
                    tool,
                    ToolSourceInfo(
                        type = ToolSourceType.BUILTIN,
                        filePath = (tool as? JsTool)?.jsFilePath
                    )
                )
            }

            // User JS tools from device storage
            val userToolResults = jsToolLoader.loadUserTools()
            userToolResults.forEach { result ->
                when (result) {
                    is JsToolLoadResult.SingleTool -> {
                        register(
                            result.tool,
                            ToolSourceInfo(
                                type = ToolSourceType.JS_EXTENSION,
                                filePath = result.tool.jsFilePath
                            )
                        )
                    }
                    is JsToolLoadResult.ToolGroup -> {
                        result.tools.forEach { tool ->
                            register(
                                tool,
                                ToolSourceInfo(
                                    type = ToolSourceType.TOOL_GROUP,
                                    groupName = result.groupName,
                                    filePath = tool.jsFilePath
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    single { ToolEnabledStateStore(androidContext()) }
    single { ToolExecutionEngine(get(), get(), get()) }
}
```

**说明**：此处假设 `JsToolLoader` 返回的结构化结果能区分单个工具与工具组。具体 API 取决于 RFC-018 的实现，但核心思路是：加载时即可知晓工具来自单文件清单还是数组清单，并将此信息作为 `ToolSourceInfo` 传递。

### 数据流

#### 全局禁用流程

```
1. 用户打开设置 → 点击"管理工具"
2. ToolManagementScreen 渲染，ToolManagementViewModel 从 ToolRegistry 加载工具，从 ToolEnabledStateStore 读取状态
3. 用户点击 "http_request" 的开关 → toggleToolEnabled("http_request")
4. ViewModel 调用 enabledStateStore.setToolEnabled("http_request", false)
5. SharedPreferences 持久化：tool_enabled_http_request = false
6. ViewModel 刷新 UI 状态 → 开关显示为关闭，Snackbar 提示 "http_request disabled"
7. 稍后，AI 模型请求工具调用：http_request(url="...")
8. ToolExecutionEngine.executeTool("http_request", ...) 检查：
   - 工具存在？是
   - Agent 有该工具？是
   - 工具全局启用？→ enabledStateStore.isToolEffectivelyEnabled("http_request", null) → false
   - 返回 ToolResult.error("tool_globally_disabled", "Tool 'http_request' is globally disabled and not available.")
9. 模型收到错误，告知用户或尝试其他方案
```

#### 工具组禁用流程

```
1. 用户打开工具管理，点击 "Google Drive" 组的开关 → 关闭
2. ViewModel 调用 enabledStateStore.setGroupEnabled("Google Drive", false)
3. SharedPreferences 持久化：tool_group_enabled_Google Drive = false
4. UI 刷新：组开关关闭，所有子工具开关变灰
5. 稍后，AI 请求 gdrive_list(...)
6. ToolExecutionEngine 检查：
   - 工具存在？是
   - Agent 有该工具？是
   - 工具有效启用？→ isToolEffectivelyEnabled("gdrive_list", "Google Drive")
     - isToolEnabled("gdrive_list") → true（个别未更改）
     - isGroupEnabled("Google Drive") → false（组已禁用）
     - 结果：false
   - 返回 ToolResult.error("tool_globally_disabled", ...)
```

#### 自动禁用工具组流程

```
1. 工具组 "Google Drive" 有 3 个工具，均单独启用，组也启用
2. 用户禁用 gdrive_list → 仍有 2 个启用 → 组保持开启
3. 用户禁用 gdrive_read → 仍有 1 个启用 → 组保持开启
4. 用户禁用 gdrive_upload → 0 个启用 → toggleToolEnabled 检测到全部禁用
5. ViewModel 自动调用 enabledStateStore.setGroupEnabled("Google Drive", false)
6. UI 刷新：组开关自动关闭
```

## 实现步骤

### 阶段一：数据层
1. [ ] 创建 `ToolSourceInfo` 数据模型（`core/model/ToolSourceInfo.kt`）
2. [ ] 创建 `ToolEnabledStateStore`（`tool/engine/ToolEnabledStateStore.kt`）
3. [ ] 增强 `ToolRegistry`，添加来源信息追踪（`register` 重载、`getToolSourceInfo`、`getAllToolSourceInfo`、`getToolGroups`）
4. [ ] 更新 `ToolModule.kt`，注册工具时附带 `ToolSourceInfo`
5. [ ] 为 `ToolEnabledStateStore` 和增强后的 `ToolRegistry` 编写单元测试

### 阶段二：执行引擎集成
6. [ ] 为 `ToolExecutionEngine` 添加 `ToolEnabledStateStore` 依赖
7. [ ] 在 `executeTool()` 中，于可用性检查与参数校验之间插入全局启用检查
8. [ ] 更新 `ToolModule.kt`，将 `ToolEnabledStateStore` 连接至 `ToolExecutionEngine`
9. [ ] 为执行引擎新增检查编写单元测试

### 阶段三：工具管理 UI
10. [ ] 创建 `ToolManagementViewModel` 及 UI 状态类
11. [ ] 创建 `ToolManagementScreen` Composable（含三个分区的工具列表）
12. [ ] 创建 `ToolDetailView` Composable
13. [ ] 创建 `ToolGroupHeader` 和 `ToolListItem` Composable
14. [ ] 在导航中添加 `Route.ManageTools`
15. [ ] 在 `NavHost` 中接入导航

### 阶段四：设置页面集成
16. [ ] 为 `SettingsScreen` 添加 `onManageTools` 参数
17. [ ] 在设置布局中添加"工具"分区和"管理工具"入口
18. [ ] 在调用 `SettingsScreen` 的 NavHost 中接入 `onManageTools`

### 阶段五：Agent 配置集成
19. [ ] 在 `AgentUiState.kt` 的 `ToolOptionItem` 中添加 `isGloballyDisabled` 字段
20. [ ] 更新 `AgentDetailViewModel.loadAvailableTools()`，填充 `isGloballyDisabled`
21. [ ] 更新 `AgentDetailScreen` 工具列表，展示灰色状态和"Globally disabled"标签
22. [ ] 对全局禁用的工具禁用复选框交互

### 阶段六：测试
23. [ ] 第 1A 层：为 ToolEnabledStateStore、ToolRegistry 增强、ToolExecutionEngine 全局检查、ToolManagementViewModel 编写 JVM 单元测试
24. [ ] 第 1B 层：SharedPreferences 持久化的仪器测试（如适用）
25. [ ] 第 1C 层：为 ToolManagementScreen、ToolDetailView 及更新后的 AgentDetailScreen 编写 Roborazzi 截图测试
26. [ ] 第 2 层：adb 视觉验证流程
27. [ ] 编写测试报告

## 测试策略

### 单元测试（第 1A 层）

**ToolEnabledStateStore**：
- `isToolEnabled` 默认返回 `true`（未设置过）
- `setToolEnabled(false)` 后 `isToolEnabled` 返回 `false`
- `isGroupEnabled` 默认返回 `true`
- `setGroupEnabled(false)` 后 `isGroupEnabled` 返回 `false`
- 工具禁用时 `isToolEffectivelyEnabled` 返回 `false`
- 组禁用时（工具启用）`isToolEffectivelyEnabled` 返回 `false`
- 工具和组均启用时 `isToolEffectivelyEnabled` 返回 `true`
- `groupName` 为 null 时 `isToolEffectivelyEnabled` 忽略组状态

**ToolRegistry 增强**：
- `register(tool, sourceInfo)` 存储来源信息
- `getToolSourceInfo` 返回正确信息
- `getToolGroups` 返回正确分组
- `getToolGroups` 排除非工具组的工具
- 向后兼容：不带 sourceInfo 的 `register(tool)` 默认为 BUILTIN

**ToolExecutionEngine**：
- 工具全局禁用：返回 `tool_globally_disabled` 错误
- 工具所在组被禁用：返回 `tool_globally_disabled` 错误
- 工具启用：按原有流程继续执行后续检查
- 所有现有测试保持通过

**ToolManagementViewModel**：
- `loadTools` 将工具正确分类至各分区
- `toggleToolEnabled` 翻转状态并重新加载
- `toggleGroupEnabled` 翻转组状态
- 所有子工具均单独禁用时自动禁用工具组
- `selectTool` 以正确详情填充 `selectedTool`
- `clearSelectedTool` 将 `selectedTool` 置为 null

### 截图测试（第 1C 层）

- ToolManagementScreen：列出内置工具，工具组折叠状态
- ToolManagementScreen：工具组展开，显示子工具
- ToolManagementScreen：组禁用，子工具灰显
- ToolManagementScreen：空状态
- ToolDetailView：带参数的内置工具
- ToolDetailView：显示组名的工具组工具
- AgentDetailScreen：全局禁用的工具灰显并带标签

### 视觉验证（第 2 层）

| 流程 | 步骤 | 预期结果 |
|------|-------|----------|
| TC-017-01：导航至工具管理 | 设置 > 管理工具 | 页面显示三个分区及所有工具 |
| TC-017-02：禁用工具 | 关闭 `http_request` | Snackbar 确认，开关显示为关闭 |
| TC-017-03：重新启用工具 | 打开 `http_request` | Snackbar 确认，开关显示为开启 |
| TC-017-04：查看工具详情 | 点击工具名称 | 详情视图显示名称、描述、参数、权限、超时时间、来源 |
| TC-017-05：持久化 | 禁用工具，重启应用，检查状态 | 重启后工具仍为禁用 |
| TC-017-06：全局禁用阻断执行 | 禁用工具，要求 AI 使用该工具 | AI 报告工具不可用 |
| TC-017-07：Agent 配置显示禁用状态 | 禁用工具，打开 Agent 配置 | 工具灰显并显示"Globally disabled"标签 |
| TC-017-08：工具组展开/折叠 | 点击组上的折叠图标 | 工具组展开/折叠 |
| TC-017-09：工具组关闭 | 关闭工具组开关 | 所有子工具变灰且不可交互 |
| TC-017-10：工具组重新启用 | 打开工具组开关 | 子工具恢复至之前状态 |
| TC-017-11：自动禁用工具组 | 逐一禁用所有子工具 | 工具组开关自动关闭 |

## 错误处理

| 错误 | 来源 | 处理方式 |
|-------|--------|---------|
| SharedPreferences 写入失败 | `ToolEnabledStateStore.setToolEnabled` | 开关恢复，Snackbar 提示："Failed to save tool state. Please try again." |
| ToolRegistry 为空 | `ToolManagementViewModel.loadTools` | 显示空状态："No tools available." |
| 工具在详情页面打开时被注销 | `ToolManagementViewModel.selectTool` | 提示"This tool is no longer available"，返回上一页 |
| 模型调用已禁用的工具 | `ToolExecutionEngine.executeTool` | 返回 `ToolResult.error("tool_globally_disabled", ...)` |
| 工具组清单被删除 | JS 工具重新加载 | 下次访问页面时该组消失。若详情页面正在查看已删除的工具，则导航返回 |

## 性能考虑

- **工具管理页面加载**：从内存中的 `ToolRegistry` 读取 + 本地 `SharedPreferences` 读取。50 个以上工具预计耗时 < 200ms。
- **开关持久化**：`SharedPreferences.apply()` 异步执行，完成时间 < 50ms。
- **ToolExecutionEngine 检查**：每次工具调用进行一次 `SharedPreferences` 读取。Android 在首次读取后会将 SharedPreferences 值缓存至内存，因此实际开销近乎为零。
- **无网络请求**：所有工具管理操作均在本地完成。

## 安全性考虑

- 启用/禁用状态存储于普通 `SharedPreferences`（应用私有，`MODE_PRIVATE`），不属于敏感数据。
- 无需申请新权限。
- AI 模型无法绕过全局禁用——该检查在 `ToolExecutionEngine` 层强制执行。

## 依赖关系

### 依赖于
- **RFC-004（工具系统）**：`ToolRegistry`、`ToolDefinition`、`Tool` 接口、`ToolExecutionEngine`
- **RFC-009（设置）**：`SettingsScreen` 入口点
- **RFC-012（JS 工具引擎）**：JS 工具出现在工具列表中
- **RFC-014（Agent 技能）**：`load_skill` 工具出现在工具列表中
- **RFC-015（JS 工具迁移）**：内置 JS 工具分类
- **RFC-018（JS 工具组）**：数组清单格式、工具组加载、工具组成员关系

### 被依赖于
- **FEAT-002（Agent 管理）**：Agent 配置遵循全局禁用状态

## 风险和缓解

| 风险 | 影响 | 概率 | 缓解措施 |
|------|--------|-------------|------------|
| JsToolLoader 无法提供工具组信息 | 中 | 中 | RFC-018 实现时必须在加载期间暴露工具组元数据；协调实现顺序 |
| SharedPreferences 数据损坏 | 低 | 极低 | 默认为启用状态（fail-open）；无数据丢失 |
| 工具数量过多（100 个以上） | 低 | 低 | LazyColumn 高效处理；SharedPreferences 对扁平键值对读取速度快 |

## 替代方案

### 方案 A：将启用/禁用状态存储至 Room
- **方案**：创建一个包含 (tool_name, enabled) 列的 `tool_config` Room 表
- **优点**：与应用其他数据存储方式一致；支持复杂查询
- **缺点**：对简单布尔标志过度设计；增加迁移复杂度；工具不是 Room 实体
- **未选用原因**：SharedPreferences 对扁平键值布尔存储更简单、更快速

### 方案 B：在 ToolDefinition 中添加 enabled 字段
- **方案**：在 `ToolDefinition` 数据类中添加 `var enabled: Boolean`
- **优点**：单一数据来源；无需单独的存储层
- **缺点**：`ToolDefinition` 是不可变数据类；使其可变会破坏值对象模式；状态仍需持久化
- **未选用原因**：违反领域模型的不可变性；仍需持久化机制

## 未来扩展

- 工具搜索和筛选栏
- 批量启用/禁用所有工具
- 超越来源类型的工具分类（文件、网络、系统、文本）
- 工具使用统计（调用次数、最后使用时间）
- JS 工具加载错误的健康状态指示
- 工具详情中展示单个 Agent 的禁用覆盖状态
- 导出/导入工具启用/禁用配置

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | 初始版本 | - |
