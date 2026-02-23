package com.tomandy.oneclaw.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Image
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AttachmentBottomSheet(
    onDismiss: () -> Unit,
    onTakePhoto: () -> Unit,
    onPickFromGallery: () -> Unit,
    onPickDocument: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState()

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState
    ) {
        ListItem(
            headlineContent = { Text("Camera") },
            leadingContent = { Icon(Icons.Default.CameraAlt, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onTakePhoto)
        )
        ListItem(
            headlineContent = { Text("Gallery") },
            leadingContent = { Icon(Icons.Default.Image, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onPickFromGallery)
        )
        ListItem(
            headlineContent = { Text("File") },
            leadingContent = { Icon(Icons.Default.Description, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onPickDocument)
        )
        Spacer(modifier = Modifier.height(16.dp))
    }
}
