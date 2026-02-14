package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.data.entity.MessageEntity
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

    // Filter out tool-role messages — they're shown inline within the assistant's ToolCallsSection
    val displayedMessages = remember(messages) {
        messages.filter { it.role != "tool" }
    }

    // Total item count including typing indicator
    val totalItems = displayedMessages.size + if (isProcessing) 1 else 0

    // Auto-scroll to bottom when new messages arrive or typing starts
    LaunchedEffect(totalItems) {
        if (totalItems > 0) {
            coroutineScope.launch {
                listState.animateScrollToItem(totalItems - 1)
            }
        }
    }

    val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .drawScrollbar(listState, scrollbarColor),
        state = listState,
        contentPadding = PaddingValues(vertical = 8.dp)
    ) {
        items(
            items = displayedMessages,
            key = { it.id }
        ) { message ->
            MessageBubble(
                message = message,
                toolResults = toolResultsMap
            )
        }

        if (isProcessing) {
            item(key = "typing_indicator") {
                TypingIndicator()
            }
        }
    }
}

private fun Modifier.drawScrollbar(
    state: LazyListState,
    color: Color,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0 || layoutInfo.visibleItemsInfo.size >= totalItems) return@drawWithContent

    val viewportHeight = size.height
    val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull() ?: return@drawWithContent
    val avgItemHeight = layoutInfo.visibleItemsInfo.sumOf { it.size }.toFloat() / layoutInfo.visibleItemsInfo.size
    val estimatedTotalHeight = avgItemHeight * totalItems
    val scrollbarHeight = (viewportHeight / estimatedTotalHeight * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
        .coerceAtMost(viewportHeight)
    val scrollRange = viewportHeight - scrollbarHeight
    val scrolledPx = firstVisible.index * avgItemHeight - firstVisible.offset
    val maxScrollPx = (estimatedTotalHeight - viewportHeight).coerceAtLeast(1f)
    val scrollbarY = (scrolledPx / maxScrollPx * scrollRange).coerceIn(0f, scrollRange)

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), scrollbarY),
        size = Size(width.toPx(), scrollbarHeight),
        cornerRadius = CornerRadius(width.toPx() / 2)
    )
}
