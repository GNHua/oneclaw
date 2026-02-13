package com.tomandy.palmclaw.ui.history

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.ui.chat.MessageBubble
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConversationHistoryScreen(
    viewModel: ConversationHistoryViewModel,
    currentConversationId: StateFlow<String>,
    onConversationSelected: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val pagingItems = viewModel.conversations.collectAsLazyPagingItems()
    val activeId by currentConversationId.collectAsState()
    val previewConversation by viewModel.previewConversation.collectAsState()
    val previewMessages by viewModel.previewMessages.collectAsState()

    val scope = rememberCoroutineScope()

    // Preview bottom sheet
    if (previewConversation != null) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

        ModalBottomSheet(
            onDismissRequest = { viewModel.clearPreview() },
            sheetState = sheetState
        ) {
            ConversationPreviewSheet(
                conversation = previewConversation!!,
                messages = previewMessages,
                onLoad = {
                    onConversationSelected(previewConversation!!.id)
                    viewModel.clearPreview()
                },
                onDismiss = {
                    scope.launch {
                        sheetState.hide()
                        viewModel.clearPreview()
                    }
                }
            )
        }
    }

    if (pagingItems.itemCount == 0) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Outlined.Info,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "No conversations yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    } else {
        val listState = rememberLazyListState()
        val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp)
                .drawScrollbar(listState, scrollbarColor),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item { Spacer(modifier = Modifier.height(8.dp)) }
            items(
                count = pagingItems.itemCount,
                key = { index -> pagingItems[index]?.id ?: index }
            ) { index ->
                val conv = pagingItems[index] ?: return@items
                val isActive = conv.id == activeId
                val dismissState = rememberSwipeToDismissBoxState()

                LaunchedEffect(dismissState.currentValue) {
                    if (dismissState.currentValue == SwipeToDismissBoxValue.EndToStart && !isActive) {
                        viewModel.deleteConversation(conv.id)
                    } else if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) {
                        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
                    }
                }

                SwipeToDismissBox(
                    state = dismissState,
                    enableDismissFromStartToEnd = false,
                    enableDismissFromEndToStart = !isActive,
                    backgroundContent = {
                        val color by animateColorAsState(
                            targetValue = if (dismissState.targetValue == SwipeToDismissBoxValue.EndToStart) {
                                MaterialTheme.colorScheme.errorContainer
                            } else {
                                MaterialTheme.colorScheme.surface
                            },
                            label = "swipe-bg"
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(color)
                                .padding(end = 24.dp),
                            contentAlignment = Alignment.CenterEnd
                        ) {
                            Icon(
                                Icons.Default.Delete,
                                contentDescription = "Delete",
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }
                ) {
                    ConversationListItem(
                        conversation = conv,
                        isActive = isActive,
                        onClick = { viewModel.loadPreview(conv.id) }
                    )
                }
            }
            item { Spacer(modifier = Modifier.height(8.dp)) }
        }
    }
}

@Composable
private fun ConversationListItem(
    conversation: ConversationEntity,
    isActive: Boolean,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (isActive) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.surface
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isActive) 2.dp else 1.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = conversation.title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = formatTimestamp(conversation.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (conversation.lastMessagePreview.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = conversation.lastMessagePreview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "${conversation.messageCount} messages",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ConversationPreviewSheet(
    conversation: ConversationEntity,
    messages: List<MessageEntity>,
    onLoad: () -> Unit,
    onDismiss: () -> Unit
) {
    // Build tool results map (same as MessageList)
    val toolResultsMap = remember(messages) {
        messages
            .filter { it.role == "tool" && it.toolCallId != null }
            .associateBy { it.toolCallId!! }
    }
    val displayedMessages = remember(messages) {
        messages.filter { it.role != "tool" }
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .padding(bottom = 32.dp)
    ) {
        // Title
        Text(
            text = conversation.title,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${conversation.messageCount} messages",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()

        // Message preview -- use Column + verticalScroll to avoid nested LazyColumn conflict
        if (displayedMessages.isEmpty()) {
            Text(
                text = "No messages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(vertical = 24.dp)
            )
        } else {
            val scrollState = rememberScrollState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .drawColumnScrollbar(scrollState, scrollbarColor)
                    .verticalScroll(scrollState)
            ) {
                displayedMessages.forEach { message ->
                    MessageBubble(
                        message = message,
                        toolResults = toolResultsMap
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onDismiss) {
                Text("Close")
            }
            Spacer(modifier = Modifier.width(12.dp))
            Button(onClick = onLoad) {
                Text("Load Conversation")
            }
        }
    }
}

private fun Modifier.drawScrollbar(
    state: androidx.compose.foundation.lazy.LazyListState,
    color: Color,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    val layoutInfo = state.layoutInfo
    val totalItems = layoutInfo.totalItemsCount
    if (totalItems == 0 || layoutInfo.visibleItemsInfo.size >= totalItems) return@drawWithContent

    val viewportHeight = size.height
    val scrollbarHeight = (layoutInfo.visibleItemsInfo.size.toFloat() / totalItems * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
    val scrollRange = viewportHeight - scrollbarHeight
    val scrollOffset = state.firstVisibleItemIndex.toFloat() / (totalItems - layoutInfo.visibleItemsInfo.size).coerceAtLeast(1)
    val scrollbarY = scrollOffset * scrollRange

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), scrollbarY),
        size = Size(width.toPx(), scrollbarHeight),
        cornerRadius = CornerRadius(width.toPx() / 2)
    )
}

private fun Modifier.drawColumnScrollbar(
    state: androidx.compose.foundation.ScrollState,
    color: Color,
    width: Dp = 4.dp
): Modifier = drawWithContent {
    drawContent()
    if (state.maxValue <= 0) return@drawWithContent

    val viewportHeight = size.height
    val totalHeight = (state.maxValue + viewportHeight).coerceAtLeast(1f)
    val scrollbarHeight = (viewportHeight / totalHeight * viewportHeight)
        .coerceAtLeast(24.dp.toPx())
    val scrollRange = viewportHeight - scrollbarHeight
    val scrollFraction = state.value.toFloat() / state.maxValue.coerceAtLeast(1)
    val scrollbarY = scrollFraction * scrollRange

    drawRoundRect(
        color = color,
        topLeft = Offset(size.width - width.toPx(), scrollbarY),
        size = Size(width.toPx(), scrollbarHeight),
        cornerRadius = CornerRadius(width.toPx() / 2)
    )
}

private fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp
    val minutes = diff / 60_000
    val hours = diff / 3_600_000
    val days = diff / 86_400_000

    return when {
        minutes < 1 -> "Just now"
        minutes < 60 -> "${minutes}m ago"
        hours < 24 -> "${hours}h ago"
        days < 7 -> "${days}d ago"
        else -> SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
    }
}
