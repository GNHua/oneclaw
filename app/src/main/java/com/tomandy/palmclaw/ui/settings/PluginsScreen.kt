package com.tomandy.palmclaw.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PluginsScreen(
    viewModel: SettingsViewModel,
    modifier: Modifier = Modifier
) {
    val plugins by viewModel.plugins.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()

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
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(plugins, key = { it.metadata.id }) { pluginState ->
                    PluginCard(
                        pluginState = pluginState,
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
    onToggle: (Boolean) -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    val metadata = pluginState.metadata
    Card(
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
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
                    onCheckedChange = onToggle
                )
            }
        }
    }
}
