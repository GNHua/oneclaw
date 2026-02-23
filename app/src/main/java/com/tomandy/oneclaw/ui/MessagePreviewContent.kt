package com.tomandy.oneclaw.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.ui.chat.MessageBubble
import com.tomandy.oneclaw.ui.chat.ToolCallGroupBubble

@Composable
fun MessagePreviewContent(
    messages: List<MessageEntity>,
    isLoading: Boolean = false,
    modifier: Modifier = Modifier
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        }
        messages.isEmpty() -> {
            Text(
                text = "No messages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
            )
        }
        else -> {
            val toolResultsMap = remember(messages) {
                messages
                    .filter { it.role == "tool" && it.toolCallId != null }
                    .associateBy { it.toolCallId!! }
            }
            val displayItems = remember(messages) {
                val filtered = messages.filter { it.role != "tool" }
                buildList<Pair<List<MessageEntity>, Boolean>> {
                    var i = 0
                    while (i < filtered.size) {
                        val msg = filtered[i]
                        if (msg.role == "assistant" && !msg.toolCalls.isNullOrEmpty()) {
                            val group = mutableListOf(msg)
                            while (i + 1 < filtered.size) {
                                val next = filtered[i + 1]
                                if (next.role == "assistant" && !next.toolCalls.isNullOrEmpty()) {
                                    i++
                                    group.add(next)
                                } else break
                            }
                            add(group to true)
                        } else {
                            add(listOf(msg) to false)
                        }
                        i++
                    }
                }
            }

            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

            Column(
                modifier = modifier
                    .fillMaxWidth()
                    .drawColumnScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
            ) {
                displayItems.forEach { (msgs, isToolGroup) ->
                    if (isToolGroup) {
                        ToolCallGroupBubble(
                            messages = msgs,
                            toolResults = toolResultsMap
                        )
                    } else {
                        val message = msgs.first()
                        if (message.role == "meta" && message.toolName == "stopped") {
                            Text(
                                text = "[stopped]",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 4.dp)
                            )
                        } else if (message.role == "meta" && message.toolName == "summary") {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 16.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                                Text(
                                    text = "Earlier messages summarized",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                    modifier = Modifier.padding(horizontal = 12.dp)
                                )
                                HorizontalDivider(
                                    modifier = Modifier.weight(1f),
                                    color = MaterialTheme.colorScheme.outlineVariant
                                )
                            }
                        } else {
                            MessageBubble(
                                message = message,
                                toolResults = toolResultsMap
                            )
                        }
                    }
                }
            }
        }
    }
}
