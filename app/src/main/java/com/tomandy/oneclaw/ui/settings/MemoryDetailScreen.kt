package com.tomandy.oneclaw.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import com.tomandy.oneclaw.ui.chat.ChatMarkdown
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.theme.Dimens

@Composable
fun MemoryDetailScreen(
    viewModel: MemoryViewModel,
    relativePath: String,
    showRaw: Boolean,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    val content by viewModel.fileContent.collectAsState()
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(relativePath) {
        viewModel.readFile(relativePath)
    }

    val scrollState = rememberScrollState()
    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    if (content.isEmpty()) {
        Text(
            text = "Empty file",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.padding(Dimens.ScreenPadding)
        )
    } else if (showRaw) {
        Text(
            text = content,
            style = MaterialTheme.typography.bodyMedium.copy(
                fontFamily = FontFamily.Monospace,
                fontSize = 14.sp,
                lineHeight = 20.sp
            ),
            color = MaterialTheme.colorScheme.onSurface,
            modifier = modifier
                .fillMaxSize()
                .drawColumnScrollbar(scrollState, scrollbarColor)
                .verticalScroll(scrollState)
                .padding(Dimens.ScreenPadding)
        )
    } else {
        ChatMarkdown(
            text = content,
            modifier = modifier
                .fillMaxSize()
                .drawColumnScrollbar(scrollState, scrollbarColor)
                .verticalScroll(scrollState)
                .padding(Dimens.ScreenPadding)
        )
    }

    if (showDeleteConfirm) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            title = { Text("Delete Memory") },
            text = { Text("Delete this memory file? This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteFile(relativePath)
                        showDeleteConfirm = false
                        onDelete()
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
