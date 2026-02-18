package com.tomandy.oneclaw.ui.chat

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.llm.ToolCall
import com.tomandy.oneclaw.ui.HandleDismissBottomSheet

@Composable
fun ToolCallDetailSheet(
    toolCalls: List<ToolCall>,
    toolResults: Map<String, MessageEntity>,
    onDismiss: () -> Unit
) {
    HandleDismissBottomSheet(
        onDismissRequest = onDismiss,
        header = {
            Text(
                text = "Tool Calls",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 24.dp, vertical = 4.dp)
            )
        }
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(toolCalls, key = { it.id }) { toolCall ->
                ToolCallDetailItem(
                    toolCall = toolCall,
                    toolResult = toolResults[toolCall.id]
                )
            }
        }
    }
}

@Composable
private fun ToolCallDetailItem(
    toolCall: ToolCall,
    toolResult: MessageEntity?
) {
    var isResultExpanded by remember { mutableStateOf(false) }
    var isArgsExpanded by remember { mutableStateOf(false) }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // Tool name + status
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = toolCall.function.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
                toolResult?.let {
                    val isError = it.content.startsWith("Error:")
                    Icon(
                        imageVector = if (isError) Icons.Default.Close else Icons.Default.Check,
                        contentDescription = if (isError) "Error" else "Success",
                        tint = if (isError) MaterialTheme.colorScheme.error else Color(0xFF4CAF50),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            // Arguments (collapsed by default if long)
            val args = toolCall.function.arguments
            if (args.isNotBlank() && args != "{}") {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isArgsExpanded = !isArgsExpanded }
                ) {
                    if (isArgsExpanded || args.length <= 200) {
                        Text(
                            text = args,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = args,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            // Result (collapsed by default)
            toolResult?.let { result ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { isResultExpanded = !isResultExpanded }
                ) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Result",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "(${formatSize(result.content.length)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isResultExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(16.dp)
                                .rotate(if (isResultExpanded) 180f else 0f)
                        )
                    }

                    AnimatedVisibility(
                        visible = isResultExpanded,
                        enter = expandVertically(),
                        exit = shrinkVertically()
                    ) {
                        Text(
                            text = result.content,
                            style = MaterialTheme.typography.bodySmall.copy(
                                fontFamily = FontFamily.Monospace
                            ),
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private fun formatSize(chars: Int): String = when {
    chars < 1024 -> "$chars chars"
    chars < 1024 * 1024 -> "${chars / 1024} KB"
    else -> "${chars / (1024 * 1024)} MB"
}
