package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.drawScrollbar
import com.tomandy.oneclaw.ui.rememberLazyListHeightCache
import com.tomandy.oneclaw.ui.theme.Dimens

@Composable
fun MemoryScreen(
    viewModel: MemoryViewModel,
    onNavigateToDetail: (relativePath: String, displayName: String) -> Unit,
    modifier: Modifier = Modifier
) {
    val files by viewModel.memoryFiles.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf<MemoryFileEntry?>(null) }

    Box(modifier = modifier.fillMaxSize()) {
        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No memories yet.\nThe agent will create memories as you chat.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
            val heightCache = rememberLazyListHeightCache()

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxSize()
                    .drawScrollbar(listState, scrollbarColor, heightCache)
                    .padding(horizontal = Dimens.ScreenPadding),
                verticalArrangement = Arrangement.spacedBy(Dimens.CardSpacing),
                contentPadding = PaddingValues(top = Dimens.ScreenPadding, bottom = Dimens.ScreenPadding)
            ) {
                items(files, key = { it.relativePath }) { entry ->
                    MemoryFileCard(
                        entry = entry,
                        onClick = {
                            onNavigateToDetail(entry.relativePath, entry.displayName)
                        },
                        onDelete = { showDeleteConfirm = entry }
                    )
                }
            }
        }
    }

    // Delete confirmation dialog
    showDeleteConfirm?.let { entry ->
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = null },
            title = { Text("Delete Memory") },
            text = { Text("Delete \"${entry.displayName}\"? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(entry.relativePath)
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
private fun MemoryFileCard(
    entry: MemoryFileEntry,
    onClick: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onClick)
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
                        text = entry.displayName,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (entry.isLongTerm) {
                        AssistChip(
                            onClick = {},
                            label = {
                                Text(
                                    text = "Curated",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            },
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
                Text(
                    text = formatFileSize(entry.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

private fun formatFileSize(bytes: Long): String {
    return when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> "${"%.1f".format(bytes / (1024.0 * 1024.0))} MB"
    }
}
