package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.Dimens
import com.tomandy.oneclaw.ui.theme.settingsTextFieldColors
import com.tomandy.oneclaw.ui.theme.settingsTextFieldShape
import com.tomandy.oneclaw.skill.SkillEntry
import com.tomandy.oneclaw.skill.SkillSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    viewModel: SkillsViewModel,
    onNavigateToEditor: (String?) -> Unit,
    onNavigateToChat: () -> Unit,
    modifier: Modifier = Modifier
) {
    val skills by viewModel.skills.collectAsState()
    val enabledMap by viewModel.enabledMap.collectAsState()
    val importStatus by viewModel.importStatus.collectAsState()

    var showAddSheet by remember { mutableStateOf(false) }
    var showUrlDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        viewModel.refresh()
    }

    Box(modifier = modifier.fillMaxSize()) {
        if (skills.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No skills available",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .drawColumnScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
                    .padding(horizontal = Dimens.ScreenPadding)
                    .padding(top = Dimens.ScreenPadding, bottom = 80.dp),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing)
            ) {
                skills.forEach { skill ->
                    SkillCard(
                        skill = skill,
                        enabled = enabledMap[skill.metadata.name] ?: true,
                        onToggle = { enabled ->
                            viewModel.toggleSkill(skill.metadata.name, enabled)
                        },
                        onClick = { onNavigateToEditor(skill.metadata.name) },
                        onDelete = if (skill.source == SkillSource.USER) {
                            { showDeleteConfirm = skill.metadata.name }
                        } else null
                    )
                }
            }
        }

        // Import status feedback
        when (val status = importStatus) {
            is SkillImportStatus.Importing -> {
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.TopCenter)
                )
            }
            is SkillImportStatus.Success -> {
                Snackbar(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(16.dp)
                ) {
                    Text("Imported: ${status.skillName}")
                }
            }
            is SkillImportStatus.Error -> {
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
            SkillImportStatus.Idle -> {}
        }

        FloatingActionButton(
            onClick = { showAddSheet = true },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add skill")
        }
    }

    // Add skill bottom sheet
    if (showAddSheet) {
        ModalBottomSheet(
            onDismissRequest = { showAddSheet = false }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Add Skill",
                    style = MaterialTheme.typography.titleLarge
                )
                OutlinedButton(
                    onClick = {
                        showAddSheet = false
                        viewModel.startAgentCreateFlow()
                        onNavigateToChat()
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create with Agent")
                }
                OutlinedButton(
                    onClick = {
                        showAddSheet = false
                        onNavigateToEditor(null)
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Create Manually")
                }
                OutlinedButton(
                    onClick = {
                        showAddSheet = false
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
                    label = { Text("SKILL.md URL") },
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
                            viewModel.importFromUrl(url.trim())
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
    showDeleteConfirm?.let { skillName ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Skill") },
            text = { Text("Remove \"$skillName\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteSkill(skillName)
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
private fun SkillCard(
    skill: SkillEntry,
    enabled: Boolean,
    onToggle: (Boolean) -> Unit,
    onClick: () -> Unit,
    onDelete: (() -> Unit)?,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = skill.metadata.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = if (skill.source == SkillSource.BUNDLED) "Built-in" else "User",
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier.height(24.dp)
                    )
                }
                Text(
                    text = skill.metadata.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = skill.metadata.command,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (onDelete != null) {
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Default.Delete,
                            contentDescription = "Delete skill",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
                Switch(
                    checked = enabled,
                    onCheckedChange = onToggle
                )
            }
        }
    }
}
