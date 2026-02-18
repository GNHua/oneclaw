package com.tomandy.oneclaw.ui.history

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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.compose.collectAsLazyPagingItems
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import com.tomandy.oneclaw.ui.HandleDismissBottomSheet
import com.tomandy.oneclaw.ui.chat.MessageBubble
import com.tomandy.oneclaw.ui.chat.ToolCallGroupBubble
import com.tomandy.oneclaw.ui.drawColumnScrollbar
import com.tomandy.oneclaw.ui.drawScrollbar
import com.tomandy.oneclaw.ui.rememberLazyListHeightCache
import kotlinx.coroutines.flow.StateFlow
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
    var pendingDeleteId by remember { mutableStateOf<String?>(null) }

    // Delete confirmation dialog
    if (pendingDeleteId != null) {
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("Delete conversation?") },
            text = { Text("This conversation and all its messages will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteConversation(pendingDeleteId!!)
                    pendingDeleteId = null
                }) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Preview bottom sheet
    if (previewConversation != null) {
        HandleDismissBottomSheet(
            onDismissRequest = { viewModel.clearPreview() },
            header = {
                Text(
                    text = previewConversation!!.title,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "${previewConversation!!.messageCount} messages",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
            }
        ) {
            ConversationPreviewSheet(
                messages = previewMessages,
                onLoad = {
                    onConversationSelected(previewConversation!!.id)
                    viewModel.clearPreview()
                },
                onDismiss = { viewModel.clearPreview() }
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
        val heightCache = rememberLazyListHeightCache()

        LazyColumn(
            state = listState,
            modifier = modifier
                .fillMaxSize()
                .drawScrollbar(listState, scrollbarColor, heightCache)
                .padding(horizontal = 16.dp),
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
                        pendingDeleteId = conv.id
                        dismissState.snapTo(SwipeToDismissBoxValue.Settled)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 32.dp)
    ) {
        // Message preview -- use Column + verticalScroll to avoid nested LazyColumn conflict
        if (displayItems.isEmpty()) {
            Text(
                text = "No messages",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 24.dp)
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
                    .padding(horizontal = 16.dp)
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

        Spacer(modifier = Modifier.height(16.dp))

        // Action buttons
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
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
