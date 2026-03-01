package com.oneclaw.shadow.feature.tool

import androidx.lifecycle.ViewModel
import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
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
                isGroupEnabled = enabledStateStore.isGroupEnabled(groupName),
                // Preserve expanded state if already loaded
                isExpanded = _uiState.value.toolGroups
                    .find { it.groupName == groupName }?.isExpanded ?: false
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
        if (sourceInfo.type == ToolSourceType.TOOL_GROUP && sourceInfo.groupName != null) {
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
