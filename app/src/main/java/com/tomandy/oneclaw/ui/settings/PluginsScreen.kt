package com.tomandy.oneclaw.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.google.OAuthGoogleAuthManager
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.settingsTextFieldColors
import com.tomandy.oneclaw.ui.theme.settingsTextFieldShape
import org.koin.compose.koinInject

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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    LaunchedEffect(Unit) { viewModel.refreshPlugins() }

    val plugins by viewModel.plugins.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()

    var selectedPlugin by remember { mutableStateOf<PluginUiState?>(null) }
    var showImportSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }
    var credentialPrompt by remember { mutableStateOf<String?>(null) }
    var showGoogleAccountSheet by remember { mutableStateOf(false) }

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
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

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

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawColumnScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
                    .padding(16.dp)
                    .padding(bottom = 64.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                groupedPlugins.forEach { (group, groupPlugins) ->
                    PluginGroupCard(
                        group = group,
                        plugins = groupPlugins,
                        onPluginClick = { selectedPlugin = it },
                        onToggle = { pluginState, enabled ->
                            if (enabled && !pluginState.toggleable &&
                                pluginState.toggleDisabledReason == "Requires Google sign-in"
                            ) {
                                showGoogleAccountSheet = true
                            } else if (enabled && !pluginState.toggleable &&
                                pluginState.toggleDisabledReason != null
                            ) {
                                credentialPrompt = pluginState.toggleDisabledReason
                            } else if (enabled && pluginState.needsCredentials) {
                                selectedPlugin = pluginState
                            } else {
                                viewModel.togglePlugin(pluginState.metadata.id, enabled)
                            }
                        },
                        onDelete = { showDeleteConfirm = it.metadata.id }
                    )
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

    // Google Account bottom sheet
    if (showGoogleAccountSheet) {
        val oauthAuthManager: OAuthGoogleAuthManager = koinInject()
        val googleSheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showGoogleAccountSheet = false },
            sheetState = googleSheetState
        ) {
            GoogleAccountScreen(
                oauthAuthManager = oauthAuthManager,
                onSignInChanged = { signedIn ->
                    viewModel.onGoogleSignInChanged(signedIn)
                    if (signedIn) {
                        showGoogleAccountSheet = false
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding()
            )
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
                    modifier = Modifier.fillMaxWidth(),
                    shape = settingsTextFieldShape,
                    colors = settingsTextFieldColors()
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

    // Credential/setup required dialog
    credentialPrompt?.let { reason ->
        AlertDialog(
            onDismissRequest = { credentialPrompt = null },
            title = { Text("Setup Required") },
            text = { Text(reason) },
            confirmButton = {
                TextButton(onClick = { credentialPrompt = null }) {
                    Text("OK")
                }
            }
        )
    }
}

@Composable
private fun PluginGroupCard(
    group: PluginGroup,
    plugins: List<PluginUiState>,
    onPluginClick: (PluginUiState) -> Unit,
    onToggle: (PluginUiState, Boolean) -> Unit,
    onDelete: (PluginUiState) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val enabledCount = plugins.count { it.enabled }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.primaryContainer
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { expanded = !expanded }
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = group.label,
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "$enabledCount / ${plugins.size}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = if (expanded) "Collapse" else "Expand",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.rotate(if (expanded) 180f else 0f)
                )
            }
            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    plugins.forEachIndexed { index, pluginState ->
                        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                        PluginRow(
                            pluginState = pluginState,
                            onClick = { onPluginClick(pluginState) },
                            onToggle = { enabled -> onToggle(pluginState, enabled) },
                            onDelete = if (pluginState.isUserPlugin) {
                                { onDelete(pluginState) }
                            } else null
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PluginRow(
    pluginState: PluginUiState,
    onClick: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = pluginState.metadata.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
        )
        if (onDelete != null) {
            IconButton(
                onClick = onDelete,
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete plugin",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
            }
            Spacer(modifier = Modifier.width(4.dp))
        }
        Switch(
            checked = pluginState.enabled,
            onCheckedChange = onToggle,
            modifier = Modifier.scale(0.8f)
        )
    }
}
