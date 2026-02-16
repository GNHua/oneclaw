package com.tomandy.palmclaw.ui.chat

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.llm.NetworkConfig
import com.tomandy.palmclaw.llm.ToolCall


/**
 * A single bubble representing multiple consecutive tool call messages.
 * Tapping opens a bottom sheet with details of each tool call.
 */
@Composable
fun ToolCallGroupBubble(
    messages: List<MessageEntity>,
    toolResults: Map<String, MessageEntity>,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }

    // Parse all tool calls from all grouped messages
    val allToolCalls = remember(messages) {
        messages.flatMap { msg ->
            msg.toolCalls?.let {
                try {
                    NetworkConfig.json.decodeFromString<List<ToolCall>>(it)
                } catch (e: Exception) {
                    Log.w("ToolCallGroupBubble", "Failed to parse toolCalls: ${e.message}")
                    emptyList()
                }
            } ?: emptyList()
        }
    }

    val totalTools = allToolCalls.size
    val timestamp = messages.lastOrNull()?.timestamp ?: 0L

    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.Start
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceVariant,
            modifier = Modifier.clickable { showSheet = true }
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Build,
                    contentDescription = "Tools",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "Used $totalTools tool${if (totalTools != 1) "s" else ""}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = "View details",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }

    if (showSheet) {
        ToolCallDetailSheet(
            toolCalls = allToolCalls,
            toolResults = toolResults,
            onDismiss = { showSheet = false }
        )
    }
}
