# RFC-017: Tool Management

## Document Information
- **RFC ID**: RFC-017
- **Related PRD**: FEAT-017 (Tool Management)
- **Dependencies**: RFC-004 (Tool System), RFC-009 (Settings), RFC-012 (JS Tool Engine), RFC-014 (Agent Skill), RFC-015 (JS Tool Migration), RFC-018 (JS Tool Group)
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background
OneClaw currently has no centralized screen to view, inspect, or control tools. Tools are only visible through per-agent configuration (FEAT-002), and there is no way to globally disable a tool or inspect its full definition (parameters, permissions, timeout). As the tool ecosystem grows -- with built-in Kotlin tools, built-in JS tools (RFC-015), user JS extensions (RFC-012), and tool groups (RFC-018) -- a management UI becomes essential.

### Goals
1. Add a "Manage Tools" entry in Settings that opens a Tool Management screen
2. Display all registered tools organized in three sections: Built-in (flat), Tool Groups (collapsible), Standalone JS (flat)
3. Provide a global enable/disable toggle per tool, persisted in SharedPreferences
4. Provide group-level enable/disable with parent-child toggle relationship
5. Show a Tool Detail view with full tool metadata (name, description, parameters, permissions, timeout, source, group)
6. Integrate the global disable check into `ToolExecutionEngine` to block disabled tools
7. Show globally disabled tools as grayed-out and non-selectable in Agent configuration (FEAT-002)

### Non-Goals
- Tool creation, editing, or deletion from this screen (JS tools managed via file system per FEAT-012; skills via FEAT-014)
- Tool execution history or logs
- Tool testing or dry-run capability
- Tool search/filter (deferred)
- Notifications when a tool is disabled while in active conversation
- Bulk import/export of tool enable/disable configuration

## Technical Design

### Architecture Overview

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

**Files modified**: 7
- `ToolRegistry.kt` -- add source info tracking and group query methods
- `ToolExecutionEngine.kt` -- add global enable check
- `ToolModule.kt` -- register tools with source info, wire new dependencies
- `SettingsScreen.kt` -- add "Manage Tools" entry
- `AgentDetailViewModel.kt` -- add globally disabled flag to tool items
- `AgentDetailScreen.kt` -- show visual indicator for globally disabled tools
- `AgentUiState.kt` -- add `isGloballyDisabled` field to `ToolOptionItem`

**Files added**: 5
- `ToolEnabledStateStore.kt` -- SharedPreferences store for enable/disable state
- `ToolSourceInfo.kt` -- data model for tool origin metadata
- `ToolManagementViewModel.kt` -- ViewModel for tool management screen
- `ToolManagementScreen.kt` -- Composable for tool list and detail views
- `Route.kt` update (or new route entry) -- navigation route for tool management

### Core Components

#### 1. ToolSourceInfo (Domain Model)

Tracks the origin and group membership of each tool. This is metadata about how a tool was loaded, stored alongside the tool in the registry.

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

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/core/model/ToolSourceInfo.kt`

**Classification rules**:
- `LoadSkillTool` (Kotlin): `ToolSourceType.BUILTIN`, groupName=null
- Tools loaded from `assets/js/tools/` (built-in JS): `ToolSourceType.BUILTIN`, groupName=null
- Tools loaded from array manifest on device storage: `ToolSourceType.TOOL_GROUP`, groupName derived from manifest filename
- Single-file JS tools from device storage: `ToolSourceType.JS_EXTENSION`, groupName=null

**Group name derivation**: Manifest filename without extension, underscores replaced by spaces, each word capitalized. For example, `google_drive.json` becomes "Google Drive".

#### 2. ToolRegistry Enhancements

Add source info tracking and group query methods to the existing `ToolRegistry`.

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

**Backward compatibility**: The existing `register(tool: Tool)` single-parameter call remains supported. The `sourceInfo` parameter defaults to `null`, which maps to `ToolSourceType.BUILTIN`. All existing code continues to work without modification.

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolRegistry.kt`

#### 3. ToolEnabledStateStore

Persists the global enable/disable state for individual tools and tool groups in SharedPreferences.

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

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolEnabledStateStore.kt`

**Design decisions**:
- Uses plain `SharedPreferences` (not `EncryptedSharedPreferences`) since enable/disable state is not sensitive data.
- Default is `true` (enabled) for both tools and groups, ensuring backward compatibility.
- The `isToolEffectivelyEnabled` method encapsulates the two-level check (individual + group) so callers don't need to implement this logic.

#### 4. ToolExecutionEngine Integration

Add a global enable check to the existing tool execution pipeline.

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

**Error code**: `tool_globally_disabled` -- a new distinct error type so the model can recognize the tool is disabled (not missing or unauthorized).

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/tool/engine/ToolExecutionEngine.kt`

#### 5. ToolManagementViewModel

Manages the state for the Tool Management screen.

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

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/feature/tool/ToolManagementViewModel.kt`

#### 6. ToolManagementScreen (UI)

The Tool Management screen has two views: a tool list and a tool detail view, managed as screen state within the ViewModel (not separate navigation destinations).

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

**Location**: `app/src/main/kotlin/com/oneclaw/shadow/feature/tool/ToolManagementScreen.kt`

#### 7. ToolDetailView

A separate composable for the tool detail screen, shown when a tool is tapped.

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

**Location**: Same file as `ToolManagementScreen.kt` (or split into `ToolDetailView.kt` if preferred).

#### 8. SettingsScreen Integration

Add `onManageTools` parameter and a new "Tools" section.

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

#### 9. AgentDetailViewModel Integration

Modify `loadAvailableTools()` to include global disable state.

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

**ToolOptionItem enhancement** (in `AgentUiState.kt`):

```kotlin
data class ToolOptionItem(
    val name: String,
    val description: String,
    val isSelected: Boolean,
    val isGloballyDisabled: Boolean = false  // NEW
)
```

#### 10. AgentDetailScreen Integration

Show visual indicators for globally disabled tools.

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

#### 11. Navigation Route

Add a route for the Tool Management screen.

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

#### 12. Koin DI Module

Register new components in the appropriate modules.

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

### ToolModule Registration with Source Info

Update tool registration calls in `ToolModule.kt` to include source info:

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

**Note**: This assumes `JsToolLoader` returns structured results distinguishing single tools from groups. The exact API depends on RFC-018 implementation, but the concept is: during loading, we know whether a tool came from a single-file manifest or an array manifest, and we pass this info as `ToolSourceInfo`.

### Data Flow

#### Global Disable Flow

```
1. User opens Settings → taps "Manage Tools"
2. ToolManagementScreen renders, ToolManagementViewModel loads tools from ToolRegistry + state from ToolEnabledStateStore
3. User taps toggle for "http_request" → toggleToolEnabled("http_request")
4. ViewModel calls enabledStateStore.setToolEnabled("http_request", false)
5. SharedPreferences persists: tool_enabled_http_request = false
6. ViewModel refreshes UI state → toggle shows OFF, snackbar "http_request disabled"
7. Later, AI model requests tool call: http_request(url="...")
8. ToolExecutionEngine.executeTool("http_request", ...) checks:
   - Tool exists? Yes
   - Agent has tool? Yes
   - Tool globally enabled? → enabledStateStore.isToolEffectivelyEnabled("http_request", null) → false
   - Returns ToolResult.error("tool_globally_disabled", "Tool 'http_request' is globally disabled and not available.")
9. Model receives error, informs user or tries alternative
```

#### Group Disable Flow

```
1. User opens Tool Management, taps group toggle for "Google Drive" → OFF
2. ViewModel calls enabledStateStore.setGroupEnabled("Google Drive", false)
3. SharedPreferences persists: tool_group_enabled_Google Drive = false
4. UI refreshes: group toggle OFF, all child tool toggles grayed out
5. Later, AI requests gdrive_list(...)
6. ToolExecutionEngine checks:
   - Tool exists? Yes
   - Agent has tool? Yes
   - Tool effectively enabled? → isToolEffectivelyEnabled("gdrive_list", "Google Drive")
     - isToolEnabled("gdrive_list") → true (individual not changed)
     - isGroupEnabled("Google Drive") → false (group disabled)
     - Result: false
   - Returns ToolResult.error("tool_globally_disabled", ...)
```

#### Auto-disable Group Flow

```
1. Group "Google Drive" has 3 tools, all individually enabled, group enabled
2. User disables gdrive_list → 2 of 3 still enabled → group stays ON
3. User disables gdrive_read → 1 of 3 still enabled → group stays ON
4. User disables gdrive_upload → 0 of 3 enabled → toggleToolEnabled detects all disabled
5. ViewModel auto-calls enabledStateStore.setGroupEnabled("Google Drive", false)
6. UI refreshes: group toggle auto-turns OFF
```

## Implementation Steps

### Phase 1: Data Layer
1. [ ] Create `ToolSourceInfo` data model (`core/model/ToolSourceInfo.kt`)
2. [ ] Create `ToolEnabledStateStore` (`tool/engine/ToolEnabledStateStore.kt`)
3. [ ] Enhance `ToolRegistry` with source info tracking (`register` overload, `getToolSourceInfo`, `getAllToolSourceInfo`, `getToolGroups`)
4. [ ] Update `ToolModule.kt` to register tools with `ToolSourceInfo`
5. [ ] Write unit tests for `ToolEnabledStateStore` and enhanced `ToolRegistry`

### Phase 2: Execution Engine Integration
6. [ ] Add `ToolEnabledStateStore` dependency to `ToolExecutionEngine`
7. [ ] Insert global enable check in `executeTool()` between availability check and parameter validation
8. [ ] Update `ToolModule.kt` to wire `ToolEnabledStateStore` into `ToolExecutionEngine`
9. [ ] Write unit tests for the new execution engine check

### Phase 3: Tool Management UI
10. [ ] Create `ToolManagementViewModel` and UI state classes
11. [ ] Create `ToolManagementScreen` composable (tool list with three sections)
12. [ ] Create `ToolDetailView` composable
13. [ ] Create `ToolGroupHeader` and `ToolListItem` composables
14. [ ] Add `Route.ManageTools` to navigation
15. [ ] Wire navigation in `NavHost`

### Phase 4: Settings Integration
16. [ ] Add `onManageTools` parameter to `SettingsScreen`
17. [ ] Add "Tools" section with "Manage Tools" entry to Settings layout
18. [ ] Wire `onManageTools` in the NavHost where `SettingsScreen` is called

### Phase 5: Agent Configuration Integration
19. [ ] Add `isGloballyDisabled` field to `ToolOptionItem` in `AgentUiState.kt`
20. [ ] Update `AgentDetailViewModel.loadAvailableTools()` to populate `isGloballyDisabled`
21. [ ] Update `AgentDetailScreen` tool list to show grayed-out state and "Globally disabled" label
22. [ ] Disable checkbox interaction for globally disabled tools

### Phase 6: Testing
23. [ ] Layer 1A: JVM unit tests for ToolEnabledStateStore, ToolRegistry enhancements, ToolExecutionEngine global check, ToolManagementViewModel
24. [ ] Layer 1B: Instrumented tests for SharedPreferences persistence (if applicable)
25. [ ] Layer 1C: Roborazzi screenshot tests for ToolManagementScreen, ToolDetailView, updated AgentDetailScreen
26. [ ] Layer 2: adb visual verification flows
27. [ ] Write test report

## Testing Strategy

### Unit Tests (Layer 1A)

**ToolEnabledStateStore**:
- `isToolEnabled` returns `true` by default (no prior set)
- `setToolEnabled(false)` then `isToolEnabled` returns `false`
- `isGroupEnabled` returns `true` by default
- `setGroupEnabled(false)` then `isGroupEnabled` returns `false`
- `isToolEffectivelyEnabled` returns `false` when tool disabled
- `isToolEffectivelyEnabled` returns `false` when group disabled (tool enabled)
- `isToolEffectivelyEnabled` returns `true` when both tool and group enabled
- `isToolEffectivelyEnabled` ignores group when `groupName` is null

**ToolRegistry enhancements**:
- `register(tool, sourceInfo)` stores source info
- `getToolSourceInfo` returns correct info
- `getToolGroups` returns correct grouping
- `getToolGroups` excludes non-group tools
- Backward compat: `register(tool)` without sourceInfo defaults to BUILTIN

**ToolExecutionEngine**:
- Tool globally disabled: returns `tool_globally_disabled` error
- Tool in disabled group: returns `tool_globally_disabled` error
- Tool enabled: proceeds to next checks as before
- All existing tests remain passing

**ToolManagementViewModel**:
- `loadTools` categorizes tools into correct sections
- `toggleToolEnabled` flips state and reloads
- `toggleGroupEnabled` flips group state
- Auto-disable group when all children are individually disabled
- `selectTool` populates `selectedTool` with correct detail
- `clearSelectedTool` sets `selectedTool` to null

### Screenshot Tests (Layer 1C)

- ToolManagementScreen: built-in tools listed, groups collapsed
- ToolManagementScreen: group expanded with child tools
- ToolManagementScreen: group disabled, child tools grayed out
- ToolManagementScreen: empty state
- ToolDetailView: built-in tool with parameters
- ToolDetailView: group tool showing group name
- AgentDetailScreen: globally disabled tool grayed out with label

### Visual Verification (Layer 2)

| Flow | Steps | Expected |
|------|-------|----------|
| TC-017-01: Navigate to Tool Management | Settings > Manage Tools | Screen shows three sections with all tools |
| TC-017-02: Disable a tool | Toggle off `http_request` | Snackbar confirms, toggle reflects OFF state |
| TC-017-03: Re-enable a tool | Toggle on `http_request` | Snackbar confirms, toggle reflects ON state |
| TC-017-04: View tool detail | Tap a tool name | Detail view shows name, description, params, permissions, timeout, source |
| TC-017-05: Persistence | Disable a tool, restart app, check | Tool still disabled after restart |
| TC-017-06: Global disable blocks execution | Disable tool, ask AI to use it | AI reports tool not available |
| TC-017-07: Agent config shows disabled | Disable tool, open Agent config | Tool grayed out with "Globally disabled" label |
| TC-017-08: Group expand/collapse | Tap chevron on group | Group expands/collapses |
| TC-017-09: Group toggle OFF | Toggle group OFF | All child tools grayed, non-interactive |
| TC-017-10: Group re-enable | Toggle group ON | Children restore to previous states |
| TC-017-11: Auto-disable group | Disable all children individually | Group toggle auto-turns OFF |

## Error Handling

| Error | Source | Handling |
|-------|--------|---------|
| SharedPreferences write failure | `ToolEnabledStateStore.setToolEnabled` | Toggle reverts, snackbar: "Failed to save tool state. Please try again." |
| Empty ToolRegistry | `ToolManagementViewModel.loadTools` | Show empty state: "No tools available." |
| Tool unregistered while detail open | `ToolManagementViewModel.selectTool` | Show "This tool is no longer available", navigate back |
| Model calls disabled tool | `ToolExecutionEngine.executeTool` | Return `ToolResult.error("tool_globally_disabled", ...)` |
| Group manifest removed | JS tool reload | Group disappears on next screen visit. Detail view navigates back if viewing a removed tool |

## Performance Considerations

- **Tool Management screen load**: Reads from in-memory `ToolRegistry` + local `SharedPreferences`. Expected < 200ms for 50+ tools.
- **Toggle persistence**: `SharedPreferences.apply()` is asynchronous, completing in < 50ms.
- **ToolExecutionEngine check**: One `SharedPreferences` read per tool call. SharedPreferences values are cached in memory by Android after first read, so effectively zero-cost.
- **No network calls**: All Tool Management operations are local.

## Security Considerations

- Enable/disable state stored in plain `SharedPreferences` (app-private, `MODE_PRIVATE`). Not sensitive data.
- No new permissions required.
- Global disable cannot be bypassed by the AI model -- the check is enforced at the `ToolExecutionEngine` level.

## Dependencies

### Depends On
- **RFC-004 (Tool System)**: `ToolRegistry`, `ToolDefinition`, `Tool` interface, `ToolExecutionEngine`
- **RFC-009 (Settings)**: `SettingsScreen` entry point
- **RFC-012 (JS Tool Engine)**: JS tools appear in the tool list
- **RFC-014 (Agent Skill)**: `load_skill` tool in the tool list
- **RFC-015 (JS Tool Migration)**: Built-in JS tools classification
- **RFC-018 (JS Tool Group)**: Array manifest format, group loading, group membership

### Depended On By
- **FEAT-002 (Agent Management)**: Agent config respects global disable state

## Risks and Mitigation

| Risk | Impact | Probability | Mitigation |
|------|--------|-------------|------------|
| Group info not available from JsToolLoader | Medium | Medium | RFC-018 must expose group metadata during loading; coordinate implementation order |
| SharedPreferences corruption | Low | Very Low | Default to enabled (fail-open); no data loss |
| Large number of tools (100+) | Low | Low | LazyColumn handles efficiently; SharedPreferences is fast for flat key-value |

## Alternative Solutions

### Alternative A: Store enable/disable in Room
- **Approach**: Create a `tool_config` Room table with (tool_name, enabled) columns
- **Pros**: Consistent with other app data storage; supports complex queries
- **Cons**: Overkill for simple boolean flags; adds migration; tools are not Room entities
- **Why not chosen**: SharedPreferences is simpler and faster for flat key-value boolean storage

### Alternative B: Add enabled field to ToolDefinition
- **Approach**: Add `var enabled: Boolean` to `ToolDefinition` data class
- **Pros**: Single source of truth; no separate store
- **Cons**: `ToolDefinition` is an immutable data class; making it mutable breaks the value-object pattern; state must still persist somewhere
- **Why not chosen**: Violates immutability of domain model; persistence still needed

## Future Enhancements

- Tool search and filter bar
- Bulk enable/disable all tools
- Tool categories beyond source type (File, Network, System, Text)
- Tool usage statistics (call count, last used)
- Tool health indicators for JS tools with load errors
- Per-agent disable override display in tool detail
- Export/import tool enable/disable configuration

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | Initial version | - |
