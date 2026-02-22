package com.tomandy.oneclaw.ui.chat

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.llm.ToolCall

@Composable
fun ToolCallsSection(
    toolCalls: List<ToolCall>,
    toolResults: Map<String, MessageEntity> = emptyMap(),
    modifier: Modifier = Modifier
) {
    var showDialog by remember { mutableStateOf(false) }
    val indicatorColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.7f)

    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { showDialog = true }
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.Build,
                contentDescription = "Tools",
                tint = indicatorColor,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "Used ${toolCalls.size} tool${if (toolCalls.size > 1) "s" else ""}",
                style = MaterialTheme.typography.labelMedium,
                color = indicatorColor
            )
        }

        Icon(
            imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = "View details",
            tint = indicatorColor,
            modifier = Modifier.size(20.dp)
        )
    }

    if (showDialog) {
        ToolCallDetailDialog(
            toolCalls = toolCalls,
            toolResults = toolResults,
            onDismiss = { showDialog = false }
        )
    }
}
