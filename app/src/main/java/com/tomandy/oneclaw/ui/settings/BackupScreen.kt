package com.tomandy.oneclaw.ui.settings

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.theme.Dimens
import com.tomandy.oneclaw.backup.BackupStatus
import com.tomandy.oneclaw.backup.BackupViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun BackupScreen(
    viewModel: BackupViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val exportStatus by viewModel.exportStatus.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()
    val importManifest by viewModel.importManifest.collectAsState()

    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd", Locale.US) }
    val defaultFilename = remember { "oneclaw-backup-${dateFormat.format(Date())}.zip" }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let { viewModel.exportBackup(it) }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let { viewModel.previewImport(it) }
    }

    val isOperationRunning = exportStatus is BackupStatus.InProgress ||
        importStatus is BackupStatus.InProgress

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(Dimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing)
    ) {
        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Back up your conversations, settings, plugins, skills, and media to a single file. Restore on this or another device.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "API keys and credentials are NOT included in backups (they are tied to this device's security hardware).",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }

        // Export section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Export Backup",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Save all app data to a file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Checkbox(
                        checked = viewModel.includeMedia,
                        onCheckedChange = { viewModel.includeMedia = it },
                        enabled = !isOperationRunning
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "Include media files (images & videos)",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                Button(
                    onClick = { exportLauncher.launch(defaultFilename) },
                    enabled = !isOperationRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Export Backup")
                }

                when (val status = exportStatus) {
                    is BackupStatus.InProgress -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        if (status.total > 0) {
                            Text(
                                text = "Exporting ${status.current}/${status.total} files...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    is BackupStatus.Success -> {
                        Text(
                            text = "Export complete! ${status.manifest.stats.conversations} conversations, ${status.manifest.stats.messages} messages saved.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                    is BackupStatus.Error -> {
                        Text(
                            text = "Export failed: ${status.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        }

        // Import section
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Import Backup",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "Restore from a backup file",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Button(
                    onClick = {
                        importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
                    },
                    enabled = !isOperationRunning,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Select Backup File")
                }

                when (val status = importStatus) {
                    is BackupStatus.InProgress -> {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                        Text(
                            text = "Restoring data...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is BackupStatus.NeedsRestart -> {
                        Text(
                            text = "Import complete! The app needs to restart.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary
                        )
                        Button(
                            onClick = { restartApp(context) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = MaterialTheme.colorScheme.primary
                            )
                        ) {
                            Text("Restart Now")
                        }
                    }
                    is BackupStatus.Error -> {
                        Text(
                            text = "Import failed: ${status.message}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                    else -> {}
                }
            }
        }
    }

    // Confirmation dialog
    val manifest = importManifest
    if (manifest != null) {
        val dateTimeFormat = remember {
            SimpleDateFormat("MMM dd, yyyy 'at' h:mm a", Locale.US)
        }

        AlertDialog(
            onDismissRequest = { viewModel.cancelImport() },
            title = { Text("Restore from backup?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = "This will REPLACE all current data:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("${manifest.stats.conversations} conversations, ${manifest.stats.messages} messages")
                    Text("${manifest.stats.cronjobs} scheduled tasks")
                    Text("Exported on ${dateTimeFormat.format(Date(manifest.exportTimestamp))}")
                    Text("Media included: ${if (manifest.includesMedia) "Yes (${manifest.stats.mediaFiles} files)" else "No"}")
                    if (manifest.stats.cronjobs > 0) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Scheduled tasks will be imported as disabled.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = { viewModel.confirmImport() },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error
                    )
                ) {
                    Text("Restore")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.cancelImport() }) {
                    Text("Cancel")
                }
            }
        )
    }
}

private fun restartApp(context: Context) {
    val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
    intent?.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
    Runtime.getRuntime().exit(0)
}
