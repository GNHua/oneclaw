package com.tomandy.palmclaw.ui.chat

import android.media.MediaMetadataRetriever
import android.media.MediaPlayer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PauseCircle
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@Composable
fun AudioPlayerBar(
    filePath: String,
    accentColor: Color = MaterialTheme.colorScheme.primary,
    textColor: Color = MaterialTheme.colorScheme.onSurface,
    modifier: Modifier = Modifier
) {
    var isPlaying by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var currentMs by remember { mutableLongStateOf(0L) }
    var isSeeking by remember { mutableStateOf(false) }

    val mediaPlayer = remember { MediaPlayer() }

    // Load duration and prepare player
    LaunchedEffect(filePath) {
        durationMs = withContext(Dispatchers.IO) {
            try {
                val retriever = MediaMetadataRetriever()
                retriever.setDataSource(filePath)
                val dur = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                    ?.toLongOrNull() ?: 0L
                retriever.release()
                dur
            } catch (_: Exception) {
                0L
            }
        }
        try {
            mediaPlayer.reset()
            mediaPlayer.setDataSource(filePath)
            mediaPlayer.prepare()
        } catch (_: Exception) {
            // File may not exist
        }
    }

    // Poll current position while playing
    LaunchedEffect(isPlaying) {
        while (isPlaying && isActive) {
            if (!isSeeking) {
                try {
                    val pos = mediaPlayer.currentPosition.toLong()
                    currentMs = pos
                    progress = if (durationMs > 0) pos.toFloat() / durationMs else 0f
                } catch (_: Exception) {}
            }
            delay(100)
        }
    }

    // Handle playback completion
    DisposableEffect(filePath) {
        mediaPlayer.setOnCompletionListener {
            isPlaying = false
            progress = 0f
            currentMs = 0L
        }
        onDispose {
            try {
                mediaPlayer.release()
            } catch (_: Exception) {}
        }
    }

    Row(
        modifier = modifier.height(40.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Play/Pause button
        IconButton(
            onClick = {
                if (isPlaying) {
                    mediaPlayer.pause()
                    isPlaying = false
                } else {
                    mediaPlayer.start()
                    isPlaying = true
                }
            },
            modifier = Modifier.size(36.dp)
        ) {
            Icon(
                imageVector = if (isPlaying) Icons.Default.PauseCircle else Icons.Default.PlayCircle,
                contentDescription = if (isPlaying) "Pause" else "Play",
                modifier = Modifier.size(32.dp),
                tint = accentColor
            )
        }

        Spacer(modifier = Modifier.width(4.dp))

        // Seek bar
        Slider(
            value = progress,
            onValueChange = { value ->
                isSeeking = true
                progress = value
                currentMs = (value * durationMs).toLong()
            },
            onValueChangeFinished = {
                mediaPlayer.seekTo(currentMs.toInt())
                isSeeking = false
            },
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(
                thumbColor = accentColor,
                activeTrackColor = accentColor,
                inactiveTrackColor = accentColor.copy(alpha = 0.3f)
            )
        )

        Spacer(modifier = Modifier.width(4.dp))

        // Duration / current time
        Text(
            text = formatDuration(if (isPlaying || currentMs > 0) currentMs else durationMs),
            style = MaterialTheme.typography.labelSmall,
            color = textColor
        )
    }
}

private fun formatDuration(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}
