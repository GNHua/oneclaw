package com.tomandy.palmclaw.ui.chat

import android.graphics.BitmapFactory
import android.util.Base64
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import dev.jeziellago.compose.markdowntext.MarkdownText
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun ChatMarkdown(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = MaterialTheme.colorScheme.onSurface
) {
    val blocks = remember(text) { parseMarkdownWithImages(text) }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        for (block in blocks) {
            when (block) {
                is MarkdownBlock.Markdown -> {
                    MarkdownText(
                        markdown = block.content,
                        color = textColor,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                is MarkdownBlock.InlineImage -> {
                    InlineBase64Image(base64 = block.base64, mimeType = block.mimeType)
                }
            }
        }
    }
}

private sealed interface MarkdownBlock {
    data class Markdown(val content: String) : MarkdownBlock
    data class InlineImage(val mimeType: String?, val base64: String) : MarkdownBlock
}

/**
 * Parses markdown text and extracts inline base64 images.
 * Returns a list of blocks alternating between markdown content and images.
 */
private fun parseMarkdownWithImages(text: String): List<MarkdownBlock> {
    if (text.isEmpty()) return emptyList()

    val regex = Regex("data:image/([a-zA-Z0-9+.-]+);base64,([A-Za-z0-9+/=\\n\\r]+)")
    val blocks = mutableListOf<MarkdownBlock>()
    var lastIndex = 0

    regex.findAll(text).forEach { match ->
        // Add markdown content before the image
        if (match.range.first > lastIndex) {
            val markdownContent = text.substring(lastIndex, match.range.first).trim()
            if (markdownContent.isNotEmpty()) {
                blocks.add(MarkdownBlock.Markdown(markdownContent))
            }
        }

        // Add the image
        val mimeType = "image/" + (match.groupValues.getOrNull(1)?.trim()?.ifEmpty { "png" } ?: "png")
        val base64 = match.groupValues.getOrNull(2)?.replace("\n", "")?.replace("\r", "")?.trim().orEmpty()
        if (base64.isNotEmpty()) {
            blocks.add(MarkdownBlock.InlineImage(mimeType = mimeType, base64 = base64))
        }

        lastIndex = match.range.last + 1
    }

    // Add remaining markdown content after the last image
    if (lastIndex < text.length) {
        val markdownContent = text.substring(lastIndex).trim()
        if (markdownContent.isNotEmpty()) {
            blocks.add(MarkdownBlock.Markdown(markdownContent))
        }
    }

    // If no images were found, return the whole text as markdown
    if (blocks.isEmpty() && text.isNotBlank()) {
        blocks.add(MarkdownBlock.Markdown(text))
    }

    return blocks
}

@Composable
private fun InlineBase64Image(base64: String, mimeType: String?) {
    var image by remember(base64) { mutableStateOf<androidx.compose.ui.graphics.ImageBitmap?>(null) }
    var failed by remember(base64) { mutableStateOf(false) }

    LaunchedEffect(base64) {
        failed = false
        image =
            withContext(Dispatchers.Default) {
                try {
                    val bytes = Base64.decode(base64, Base64.DEFAULT)
                    val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return@withContext null
                    bitmap.asImageBitmap()
                } catch (_: Throwable) {
                    null
                }
            }
        if (image == null) failed = true
    }

    if (image != null) {
        Image(
            bitmap = image!!,
            contentDescription = mimeType ?: "image",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxWidth(),
        )
    } else if (failed) {
        Text(
            text = "Image unavailable",
            modifier = Modifier.padding(vertical = 2.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
