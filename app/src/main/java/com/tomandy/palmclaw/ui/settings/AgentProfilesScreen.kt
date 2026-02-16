package com.tomandy.palmclaw.ui.settings

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.agent.profile.AgentProfileEntry

@Composable
fun AgentProfilesScreen(
    viewModel: AgentProfilesViewModel,
    onNavigateToEditor: (String?) -> Unit,
    modifier: Modifier = Modifier
) {
    val profiles by viewModel.profiles.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf<AgentProfileEntry?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (profiles.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No agent profiles yet",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(bottom = 80.dp)
            ) {
                items(profiles, key = { it.name }) { profile ->
                    AgentProfileCard(
                        profile = profile,
                        onClick = { onNavigateToEditor(profile.name) },
                        onDelete = { showDeleteConfirm = profile }
                    )
                }
            }
        }

        FloatingActionButton(
            onClick = { onNavigateToEditor(null) },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "Add profile")
        }
    }

    showDeleteConfirm?.let { profile ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Profile") },
            text = { Text("Remove \"${profile.name}\"? Conversations using this profile will revert to defaults.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteProfile(profile.name)
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
private fun AgentProfileCard(
    profile: AgentProfileEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
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
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = profile.name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = profile.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                if (profile.model != null) {
                    AssistChip(
                        onClick = {},
                        label = {
                            Text(
                                text = profile.model,
                                style = MaterialTheme.typography.labelSmall
                            )
                        },
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .height(24.dp)
                    )
                }
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete profile",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}
