package com.tomandy.oneclaw.ui.chat

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatInput(
    value: String,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onStop: () -> Unit = {},
    onPickFromGallery: () -> Unit = {},
    onTakePhoto: () -> Unit = {},
    onTakeVideo: () -> Unit = {},
    onPickDocument: () -> Unit = {},
    onMicTap: () -> Unit = {},
    onShowSkillPicker: () -> Unit = {},
    isProcessing: Boolean = false,
    isRecording: Boolean = false,
    micAvailable: Boolean = true,
    attachedImages: List<String> = emptyList(),
    attachedAudios: List<String> = emptyList(),
    attachedVideos: List<String> = emptyList(),
    attachedDocuments: List<Pair<String, String>> = emptyList(),
    onRemoveImage: (Int) -> Unit = {},
    onRemoveAudio: (Int) -> Unit = {},
    onRemoveVideo: (Int) -> Unit = {},
    onRemoveDocument: (Int) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val canSend = value.isNotBlank() || attachedImages.isNotEmpty() ||
        attachedAudios.isNotEmpty() || attachedVideos.isNotEmpty() ||
        attachedDocuments.isNotEmpty()

    var showAttachmentSheet by remember { mutableStateOf(false) }
    val keyboardController = LocalSoftwareKeyboardController.current
    val scope = rememberCoroutineScope()

    Column(modifier = modifier.fillMaxWidth()) {
        // Attached previews
        if (attachedImages.isNotEmpty() || attachedAudios.isNotEmpty() ||
            attachedVideos.isNotEmpty() || attachedDocuments.isNotEmpty()
        ) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(vertical = 4.dp)
            ) {
                itemsIndexed(attachedImages) { index, path ->
                    AttachedImagePreview(
                        filePath = path,
                        onRemove = { onRemoveImage(index) }
                    )
                }
                itemsIndexed(attachedAudios) { index, path ->
                    AttachedAudioPreview(filePath = path, onRemove = { onRemoveAudio(index) })
                }
                itemsIndexed(attachedVideos) { index, path ->
                    AttachedVideoPreview(filePath = path, onRemove = { onRemoveVideo(index) })
                }
                itemsIndexed(attachedDocuments) { index, (_, displayName) ->
                    AttachedDocumentPreview(
                        displayName = displayName,
                        onRemove = { onRemoveDocument(index) }
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
        }

        // Two-row input bar
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHighest
        ) {
            Column {
                // Row 1: Text field
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
                    textStyle = MaterialTheme.typography.bodyLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.primary),
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    ),
                    decorationBox = { innerTextField ->
                        Box {
                            if (value.isEmpty()) {
                                Text(
                                    text = "Ask OneClaw",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                        alpha = 0.6f
                                    )
                                )
                            }
                            innerTextField()
                        }
                    }
                )

                // Row 2: Action buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 4.dp, end = 4.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Attachment button
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            scope.launch {
                                delay(150)
                                showAttachmentSheet = true
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Add,
                            contentDescription = "Attach",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    // Skill picker button
                    IconButton(
                        onClick = {
                            keyboardController?.hide()
                            scope.launch {
                                delay(150)
                                onShowSkillPicker()
                            }
                        },
                        modifier = Modifier.size(40.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.FilterList,
                            contentDescription = "Skills",
                            modifier = Modifier.size(22.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Right side: mic / send / stop
                    if (isProcessing) {
                        StopButton(onClick = onStop)
                    } else if (canSend) {
                        FilledIconButton(
                            onClick = onSend,
                            modifier = Modifier.size(40.dp),
                            shape = CircleShape,
                            colors = IconButtonDefaults.filledIconButtonColors(
                                containerColor = MaterialTheme.colorScheme.primary,
                                contentColor = MaterialTheme.colorScheme.onPrimary
                            )
                        ) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.Send,
                                contentDescription = "Send message",
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    } else if (micAvailable || isRecording) {
                        IconButton(
                            onClick = onMicTap,
                            modifier = Modifier.size(40.dp)
                        ) {
                            Icon(
                                imageVector = if (isRecording) Icons.Default.Stop else Icons.Default.Mic,
                                contentDescription = if (isRecording) "Stop recording" else "Voice input",
                                modifier = Modifier.size(22.dp),
                                tint = if (isRecording) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    if (showAttachmentSheet) {
        AttachmentBottomSheet(
            onDismiss = { showAttachmentSheet = false },
            onTakePhoto = { showAttachmentSheet = false; onTakePhoto() },
            onPickFromGallery = { showAttachmentSheet = false; onPickFromGallery() },
            onPickDocument = { showAttachmentSheet = false; onPickDocument() }
        )
    }
}

@Composable
private fun AttachedAudioPreview(filePath: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.height(56.dp).width(220.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box {
            AudioPlayerBar(
                filePath = filePath,
                accentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                textColor = MaterialTheme.colorScheme.onSecondaryContainer,
                modifier = Modifier.padding(start = 4.dp, end = 8.dp)
            )

            // Remove button
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clickable(role = Role.Button, onClick = onRemove),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove audio",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(2.dp)
                )
            }
        }
    }
}

@Composable
private fun AttachedImagePreview(
    filePath: String,
    onRemove: () -> Unit
) {
    var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(filePath) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 4 }
                BitmapFactory.decodeFile(filePath, options)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    Box(modifier = Modifier.size(72.dp)) {
        bitmap?.let {
            Image(
                bitmap = it,
                contentDescription = "Attached image",
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        }

        // Remove button
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clickable(role = Role.Button, onClick = onRemove),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.error
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove image",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .size(14.dp)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun AttachedVideoPreview(filePath: String, onRemove: () -> Unit) {
    var thumbnail by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }
    var durationText by remember(filePath) { mutableStateOf("") }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                val bmp = retriever.getFrameAtTime(0)
                thumbnail = bmp?.asImageBitmap()
                val durationMs = retriever.extractMetadata(
                    MediaMetadataRetriever.METADATA_KEY_DURATION
                )?.toLongOrNull() ?: 0L
                val totalSec = durationMs / 1000
                durationText = "%d:%02d".format(totalSec / 60, totalSec % 60)
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    Box(modifier = Modifier.size(72.dp)) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = "Video thumbnail",
                modifier = Modifier
                    .size(72.dp)
                    .clip(RoundedCornerShape(8.dp)),
                contentScale = ContentScale.Crop
            )
        } ?: Surface(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(8.dp),
            color = MaterialTheme.colorScheme.surfaceVariant
        ) {}

        // Play icon overlay
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = null,
            modifier = Modifier.size(28.dp).align(Alignment.Center),
            tint = Color.White
        )

        // Duration label
        if (durationText.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(2.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                )
            }
        }

        // Remove button
        Surface(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .size(20.dp)
                .clickable(role = Role.Button, onClick = onRemove),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.error
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "Remove video",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .size(14.dp)
                    .padding(2.dp)
            )
        }
    }
}

@Composable
private fun AttachedDocumentPreview(displayName: String, onRemove: () -> Unit) {
    Surface(
        modifier = Modifier.height(56.dp).width(160.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Box {
            Row(
                modifier = Modifier.padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Description,
                    contentDescription = null,
                    modifier = Modifier.size(24.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
            }

            // Remove button
            Surface(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(20.dp)
                    .clickable(role = Role.Button, onClick = onRemove),
                shape = CircleShape,
                color = MaterialTheme.colorScheme.error
            ) {
                Icon(
                    imageVector = Icons.Default.Close,
                    contentDescription = "Remove document",
                    tint = MaterialTheme.colorScheme.onError,
                    modifier = Modifier
                        .size(14.dp)
                        .padding(2.dp)
                )
            }
        }
    }
}

@Composable
private fun StopButton(onClick: () -> Unit) {
    val color = MaterialTheme.colorScheme.error

    Box(
        modifier = Modifier
            .size(40.dp)
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        // Spinning ring
        CircularProgressIndicator(
            modifier = Modifier.size(28.dp),
            color = color.copy(alpha = 0.5f),
            strokeWidth = 2.dp
        )
        // Filled square (media-player stop)
        Canvas(modifier = Modifier.size(12.dp)) {
            drawRoundRect(
                color = color,
                cornerRadius = CornerRadius(2.dp.toPx()),
                topLeft = Offset.Zero,
                size = Size(size.width, size.height)
            )
        }
    }
}
