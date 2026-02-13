package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import android.util.Log
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.NetworkConfig
import com.tomandy.palmclaw.llm.ToolCall
import com.tomandy.palmclaw.util.formatTimestamp

/**
 * Message bubble component for displaying individual chat messages.
 *
 * Displays messages with different styling based on role:
 * - User messages: aligned right, primary color
 * - Assistant messages: aligned left, surface variant color
 *
 * Features:
 * - Markdown rendering for rich text content
 * - Timestamp display with human-readable format
 * - Maximum width constraint for better readability
 * - Rounded corners for modern appearance
 * - Material3 theming support
 */
@Composable
fun MessageBubble(
    message: MessageEntity,
    toolResults: Map<String, MessageEntity> = emptyMap(),
    modifier: Modifier = Modifier
) {
    val isUser = message.role == "user"

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
            modifier = Modifier.widthIn(max = 280.dp)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                if (!toolCalls.isNullOrEmpty()) {
                    // Tool-call-only bubble
                    ToolCallsSection(
                        toolCalls = toolCalls,
                        toolResults = toolResults,
                        modifier = Modifier.fillMaxWidth()
                    )
                } else {
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
