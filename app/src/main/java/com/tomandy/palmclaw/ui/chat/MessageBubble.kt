package com.tomandy.palmclaw.ui.chat

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.widget.Toast
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import android.util.Log
import androidx.core.content.FileProvider
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.NetworkConfig
import com.tomandy.palmclaw.llm.ToolCall
import com.tomandy.palmclaw.util.formatTimestamp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageBubble(
    message: MessageEntity,
    toolResults: Map<String, MessageEntity> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"
    val context = LocalContext.current

    val toolCalls: List<ToolCall>? = remember(message.toolCalls) {
        message.toolCalls?.let {
            try {
                NetworkConfig.json.decodeFromString<List<ToolCall>>(it)
            } catch (e: Exception) {
                Log.w("MessageBubble", "Failed to parse toolCalls: ${e.message}")
                null
            }
        }
    }

    val imagePaths: List<String> = remember(message.imagePaths) {
        message.imagePaths?.let {
            try {
                NetworkConfig.json.decodeFromString<List<String>>(it)
            } catch (e: Exception) {
                Log.w("MessageBubble", "Failed to parse imagePaths: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
    }

    val audioPaths: List<String> = remember(message.audioPaths) {
        message.audioPaths?.let {
            try {
                NetworkConfig.json.decodeFromString<List<String>>(it)
            } catch (e: Exception) {
                Log.w("MessageBubble", "Failed to parse audioPaths: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
    }

    val videoPaths: List<String> = remember(message.videoPaths) {
        message.videoPaths?.let {
            try {
                NetworkConfig.json.decodeFromString<List<String>>(it)
            } catch (e: Exception) {
                Log.w("MessageBubble", "Failed to parse videoPaths: ${e.message}")
                emptyList()
            }
        } ?: emptyList()
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = if (isUser) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            },
            modifier = Modifier
                .widthIn(max = 280.dp)
                .combinedClickable(
                    onClick = {},
                    onLongClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText("message", message.content))
                        Toast.makeText(context, "Copied", Toast.LENGTH_SHORT).show()
                    }
                )
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                // Show attached images
                if (imagePaths.isNotEmpty()) {
                    imagePaths.forEach { path ->
                        MessageImageThumbnail(filePath = path)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Show attached audio
                if (audioPaths.isNotEmpty()) {
                    audioPaths.forEach { path ->
                        AudioPlayerBar(
                            filePath = path,
                            accentColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            textColor = if (isUser) {
                                MaterialTheme.colorScheme.onPrimaryContainer
                            } else {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                // Show attached videos
                if (videoPaths.isNotEmpty()) {
                    videoPaths.forEach { path ->
                        MessageVideoThumbnail(filePath = path)
                        Spacer(modifier = Modifier.height(4.dp))
                    }
                }

                if (!toolCalls.isNullOrEmpty()) {
                    // Tool-call-only bubble
                    ToolCallsSection(
                        toolCalls = toolCalls,
                        toolResults = toolResults,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (message.content.isNotBlank()) {
                    // Regular text bubble
                    ChatMarkdown(
                        text = message.content,
                        textColor = if (isUser) {
                            MaterialTheme.colorScheme.onPrimaryContainer
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                }

                Spacer(modifier = Modifier.height(4.dp))

                // Timestamp
                Text(
                    text = formatTimestamp(message.timestamp),
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isUser) {
                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    }
                )
            }
        }
    }
}

@Composable
private fun MessageImageThumbnail(filePath: String) {
    var bitmap by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }

    LaunchedEffect(filePath) {
        bitmap = withContext(Dispatchers.IO) {
            try {
                val options = BitmapFactory.Options().apply { inSampleSize = 2 }
                BitmapFactory.decodeFile(filePath, options)?.asImageBitmap()
            } catch (_: Exception) {
                null
            }
        }
    }

    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Image attachment",
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
        )
    }
}

@Composable
private fun MessageVideoThumbnail(filePath: String) {
    val context = LocalContext.current
    var thumbnail by remember(filePath) { mutableStateOf<ImageBitmap?>(null) }
    var durationText by remember(filePath) { mutableStateOf("") }

    LaunchedEffect(filePath) {
        withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                val bmp = retriever.getFrameAtTime(0)
                thumbnail = bmp?.asImageBitmap()
                val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                val totalSec = durationMs / 1000
                durationText = "%d:%02d".format(totalSec / 60, totalSec % 60)
                retriever.release()
            } catch (_: Exception) {}
        }
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .clickable {
                try {
                    val file = java.io.File(filePath)
                    val uri = FileProvider.getUriForFile(
                        context,
                        "${context.packageName}.fileprovider",
                        file
                    )
                    val intent = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, "video/mp4")
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    context.startActivity(intent)
                } catch (_: Exception) {}
            }
    ) {
        thumbnail?.let {
            Image(
                bitmap = it,
                contentDescription = "Video attachment",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
            )
        }

        // Play icon overlay
        Icon(
            imageVector = Icons.Default.PlayArrow,
            contentDescription = "Play video",
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.Center),
            tint = Color.White
        )

        // Duration label
        if (durationText.isNotEmpty()) {
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(4.dp),
                shape = RoundedCornerShape(4.dp),
                color = Color.Black.copy(alpha = 0.6f)
            ) {
                Text(
                    text = durationText,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                )
            }
        }
    }
}
