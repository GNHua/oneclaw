package com.tomandy.oneclaw.ui.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.drawScrollbar
import com.tomandy.oneclaw.ui.rememberLazyListHeightCache
import com.tomandy.oneclaw.data.entity.MessageEntity
import kotlinx.coroutines.launch

/**
 * Scrollable message list component for displaying chat conversations.
 *
 * Features:
 * - Auto-scroll to bottom when new messages arrive
 * - Animated scrolling for smooth transitions
 * - Message IDs as keys for proper recomposition
 * - Content padding for better UX
 * - Optimized for large lists with LazyColumn
 *
 * @param messages List of messages to display
 * @param modifier Modifier for styling
 * @param listState LazyListState for scroll control
 */
@Composable
fun MessageList(
    messages: List<MessageEntity>,
    isProcessing: Boolean = false,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState()
) {
    val coroutineScope = rememberCoroutineScope()

    // Build lookup map for tool results
    val toolResultsMap = remember(messages) {
        messages
            .filter { it.role == "tool" && it.toolCallId != null }
            .associateBy { it.toolCallId!! }
    }

    // Filter out tool-role messages and group consecutive tool-call-only assistant messages
    val displayItems = remember(messages) {
        val filtered = messages.filter { it.role != "tool" }
        buildList<DisplayItem> {
            var i = 0
            while (i < filtered.size) {
                val msg = filtered[i]
                if (msg.isToolCallOnly()) {
                    // Collect consecutive tool-call-only assistant messages
                    val group = mutableListOf(msg)
                    while (i + 1 < filtered.size && filtered[i + 1].isToolCallOnly()) {
                        i++
                        group.add(filtered[i])
                    }
                    add(DisplayItem.ToolGroup(group))
                } else {
                    add(DisplayItem.Single(msg))
                }
                i++
            }
        }
    }

    // Total item count including typing indicator
    val totalItems = displayItems.size + if (isProcessing) 1 else 0

    // Auto-scroll to bottom when new messages arrive or typing starts
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
    val heightCache = rememberLazyListHeightCache()

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .drawScrollbar(listState, scrollbarColor, heightCache),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = displayItems,
            key = { it.key }
        ) { item ->
            when (item) {
                is DisplayItem.ToolGroup -> {
                    ToolCallGroupBubble(
                        messages = item.messages,
                        toolResults = toolResultsMap
                    )
                }
                is DisplayItem.Single -> {
                    val message = item.message
                    if (message.role == "meta" && message.toolName == "stopped") {
                        Text(
                            text = "[stopped]",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(
                                alpha = 0.6f
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                        )
                    } else if (message.role == "meta" && message.toolName == "summary") {
                        SummaryDivider()
                        if (message.content.isNotBlank()) {
                            MessageBubble(
                                message = message.copy(role = "assistant"),
                                toolResults = toolResultsMap
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

        if (isProcessing) {
            item(key = "typing_indicator") {
                TypingIndicator()
            }
        }
    }
}

@Composable
private fun SummaryDivider() {
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
}

private fun MessageEntity.isToolCallOnly(): Boolean =
    role == "assistant" && !toolCalls.isNullOrEmpty()

private sealed class DisplayItem {
    abstract val key: String

    data class Single(val message: MessageEntity) : DisplayItem() {
        override val key: String get() = message.id
    }

    data class ToolGroup(val messages: List<MessageEntity>) : DisplayItem() {
        override val key: String get() = "group_${messages.first().id}"
    }
}
