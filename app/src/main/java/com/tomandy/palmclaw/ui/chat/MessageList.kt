package com.tomandy.palmclaw.ui.chat

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
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

    LazyColumn(
        modifier = modifier.fillMaxSize(),
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
