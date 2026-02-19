package com.tomandy.oneclaw.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.drawScrollbar
import com.tomandy.oneclaw.ui.rememberLazyListHeightCache
import com.tomandy.oneclaw.ui.theme.Dimens

private enum class PluginGroup(val label: String) {
    USER("User Plugins"),
    GOOGLE_WORKSPACE("Google Workspace"),
    DEVICE_MEDIA("Device & Media"),
    COMMUNICATION("Communication"),
    PRODUCTIVITY("Productivity"),
    LIFESTYLE("Lifestyle"),
    UTILITIES("Utilities"),
    SYSTEM("System")
}

private val DEVICE_MEDIA_IDS = setOf(
    "device_control", "camera", "voice_memo", "media_control", "notifications"
)
private val COMMUNICATION_IDS = setOf("sms-phone")
private val PRODUCTIVITY_IDS = setOf("notion")
private val LIFESTYLE_IDS = setOf("smart-home")
private val UTILITIES_IDS = setOf(
    "web", "web-fetch", "location", "search", "qrcode", "pdf-tools", "time", "image-gen"
)
private val SYSTEM_IDS = setOf(
    "workspace", "memory", "scheduler", "config", "delegate_agent", "activate_tools",
    "plugin_management"
)

private fun classifyPlugin(plugin: PluginUiState): PluginGroup {
    if (plugin.isUserPlugin) return PluginGroup.USER
    val id = plugin.metadata.id
    return when {
        id.startsWith("google-") -> PluginGroup.GOOGLE_WORKSPACE
        id in DEVICE_MEDIA_IDS -> PluginGroup.DEVICE_MEDIA
        id in COMMUNICATION_IDS -> PluginGroup.COMMUNICATION
        id in PRODUCTIVITY_IDS -> PluginGroup.PRODUCTIVITY
        id in LIFESTYLE_IDS -> PluginGroup.LIFESTYLE
        id in SYSTEM_IDS -> PluginGroup.SYSTEM
        id in UTILITIES_IDS -> PluginGroup.UTILITIES
        else -> PluginGroup.UTILITIES
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun PluginsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val plugins by viewModel.plugins.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()

    var selectedPlugin by remember { mutableStateOf<PluginUiState?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    val zipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        uri?.let { viewModel.importPluginFromZip(it) }
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (plugins.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No plugins available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            val heightCache = rememberLazyListHeightCache()

            val groupedPlugins by remember(plugins) {
                derivedStateOf {
                    plugins
                        .groupBy { classifyPlugin(it) }
                        .toSortedMap(compareBy { it.ordinal })
                        .mapValues { (_, list) ->
                            list.sortedBy { it.metadata.name.lowercase() }
                        }
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawScrollbar(listState, scrollbarColor, heightCache)
                    .padding(horizontal = Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
                contentPadding = PaddingValues(top = Dimens.ScreenPadding, bottom = 80.dp)
            ) {
                groupedPlugins.forEach { (group, groupPlugins) ->
                    stickyHeader(key = "header_${group.name}") {
                        SectionHeader(title = group.label)
                    }
                    items(groupPlugins, key = { it.metadata.id }) { pluginState ->
                        PluginCard(
                            pluginState = pluginState,
                            onClick = { selectedPlugin = pluginState },
                            onToggle = { enabled ->
                                viewModel.togglePlugin(pluginState.metadata.id, enabled)
                            },
                            onDelete = if (pluginState.isUserPlugin) {
                                { showDeleteConfirm = pluginState.metadata.id }
                            } else null
                        )
                    }
                }
            }
        }

        // Import status snackbar area
        when (val status = importStatus) {
            is ImportStatus.Importing -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            is ImportStatus.Success -> {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text("Installed: ${status.pluginName}")
                }
            }
            is ImportStatus.Error -> {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp),
                    action = {
                        TextButton(onClick = { viewModel.resetImportStatus() }) {
                            Text("Dismiss")
                        }
                    }
                ) {
                    Text(status.message)
                }
            }
            ImportStatus.Idle -> {}
        }

        FloatingActionButton(
            onClick = { showImportSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add plugin")
        }
    }

    // Plugin detail sheet
    selectedPlugin?.let { plugin ->
        PluginDetailSheet(
            pluginState = plugin,
            viewModel = viewModel,
            onDismiss = { selectedPlugin = null }
        )
    }

    // Import bottom sheet
    if (showImportSheet) {
        ModalBottomSheet(
            onDismissRequest = { showImportSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add Plugin",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedButton(
                    onClick = {
                        showImportSheet = false
                        zipPicker.launch(arrayOf("application/zip"))
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import from file (.zip)")
                }
                OutlinedButton(
                    onClick = {
                        showImportSheet = false
                        showUrlDialog = true
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Import from URL")
                }
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }

    // URL import dialog
    if (showUrlDialog) {
        var url by remember { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { showUrlDialog = false },
            title = { Text("Import from URL") },
            text = {
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Plugin ZIP URL") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showUrlDialog = false
                        if (url.isNotBlank()) {
                            viewModel.importPluginFromUrl(url.trim())
                        }
                    },
                    enabled = url.isNotBlank()
                ) {
                    Text("Download")
                }
            },
            dismissButton = {
                TextButton(onClick = { showUrlDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { pluginId ->
        val pluginName = plugins.find { it.metadata.id == pluginId }?.metadata?.name ?: pluginId
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Plugin") },
            text = { Text("Remove \"$pluginName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deletePlugin(pluginId)
                        showDeleteConfirm = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun PluginCard(
    pluginState: PluginUiState,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val metadata = pluginState.metadata
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                WrapRow(
                    horizontalSpacing = 8.dp,
                    verticalSpacing = 4.dp
                ) {
                    Text(
                        text = metadata.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (pluginState.isUserPlugin) "User" else "Built-in",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                    metadata.tags.forEach { tag ->
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = tag.replaceFirstChar { it.uppercaseChar() },
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp),
                            colors = AssistChipDefaults.assistChipColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                                labelColor = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        )
                    }
                }
                Text(
                    text = metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = "v${metadata.version} · ${metadata.tools.size} tool${if (metadata.tools.size != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (!pluginState.toggleable && pluginState.toggleDisabledReason != null) {
                    Text(
                        text = pluginState.toggleDisabledReason,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete plugin",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = pluginState.enabled,
                    onCheckedChange = onToggle,
                    enabled = pluginState.toggleable
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background)
    ) {
        HorizontalDivider(
            color = MaterialTheme.colorScheme.outlineVariant,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(top = 4.dp, bottom = 2.dp)
        )
    }
}

@Composable
private fun WrapRow(
    modifier: Modifier = Modifier,
    horizontalSpacing: Dp = 0.dp,
    verticalSpacing: Dp = 0.dp,
    content: @Composable () -> Unit
) {
    Layout(content = content, modifier = modifier) { measurables, constraints ->
        val hSpacingPx = horizontalSpacing.roundToPx()
        val vSpacingPx = verticalSpacing.roundToPx()
        val placeables = measurables.map { it.measure(constraints.copy(minWidth = 0)) }
        var x = 0
        var y = 0
        var rowHeight = 0
        val positions = placeables.map { placeable ->
            if (x > 0 && x + hSpacingPx + placeable.width > constraints.maxWidth) {
                x = 0
                y += rowHeight + vSpacingPx
                rowHeight = 0
            }
            if (x > 0) x += hSpacingPx
            val pos = IntOffset(x, y)
            x += placeable.width
            rowHeight = maxOf(rowHeight, placeable.height)
            pos
        }
        val totalHeight = if (placeables.isEmpty()) 0 else y + rowHeight
        layout(constraints.maxWidth, totalHeight) {
            placeables.forEachIndexed { i, placeable ->
                placeable.placeRelative(positions[i].x, positions[i].y)
            }
        }
    }
}
