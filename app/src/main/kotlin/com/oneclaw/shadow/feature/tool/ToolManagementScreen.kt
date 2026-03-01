package com.oneclaw.shadow.feature.tool

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.oneclaw.shadow.core.model.ToolParameter
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDetailView(
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
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                Text(
                    text = tool.name,
                    style = MaterialTheme.typography.headlineSmall,
                    fontFamily = FontFamily.Monospace
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = tool.description,
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Metadata
            item {
                Spacer(modifier = Modifier.height(8.dp))
                DetailRow("Source", tool.sourceType.displayLabel())
                DetailRow("Group", tool.groupName ?: "None")
                DetailRow("Timeout", "${tool.timeoutSeconds} seconds")
                DetailRow(
                    "Permissions",
                    tool.requiredPermissions.ifEmpty { listOf("None") }.joinToString(", ")
                )
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
                    Text(
                        "Enabled",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(1f)
                    )
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
                Text(
                    "PARAMETERS",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            if (tool.parametersSchema.properties.isEmpty()) {
                item {
                    Text(
                        "No parameters",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
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
