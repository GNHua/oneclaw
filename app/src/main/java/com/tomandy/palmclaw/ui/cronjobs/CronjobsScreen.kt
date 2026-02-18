package com.tomandy.palmclaw.ui.cronjobs

import androidx.compose.animation.AnimatedVisibility
import com.tomandy.palmclaw.scheduler.util.formatCronExpression
import com.tomandy.palmclaw.scheduler.util.formatIntervalMinutes
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import com.tomandy.palmclaw.ui.HandleDismissBottomSheet
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomandy.palmclaw.scheduler.data.CronjobEntity
import com.tomandy.palmclaw.ui.drawScrollbar
import com.tomandy.palmclaw.ui.rememberLazyListHeightCache
import com.tomandy.palmclaw.scheduler.data.ExecutionLog
import com.tomandy.palmclaw.scheduler.data.ExecutionStatus
import com.tomandy.palmclaw.scheduler.data.ScheduleType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CronjobsScreen(
    viewModel: CronjobsViewModel,
    modifier: Modifier = Modifier
) {
    val cronjobs by viewModel.cronjobs.collectAsState()
    val selectedCronjobId by viewModel.selectedCronjobId.collectAsState()
    val executionLogs by viewModel.executionLogs.collectAsState()
    val deleteConfirmation by viewModel.deleteConfirmation.collectAsState()
    val error by viewModel.error.collectAsState()
    val showHistory by viewModel.showHistory.collectAsState()
    val historyCronjobs by viewModel.historyCronjobs.collectAsState()
    val historyCanLoadMore by viewModel.historyCanLoadMore.collectAsState()
    val historyLoading by viewModel.historyLoading.collectAsState()

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // Delete confirmation dialog
    if (deleteConfirmation != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissDelete() },
            title = { Text("Delete scheduled task?") },
            text = { Text("This will cancel the task and remove all execution history.") },
            confirmButton = {
                TextButton(onClick = { viewModel.confirmDelete() }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissDelete() }) {
                    Text("Cancel")
                }
            }
        )
    }

    // History bottom sheet
    if (showHistory) {
        HandleDismissBottomSheet(
            onDismissRequest = { viewModel.closeHistory() },
            header = {
                Text(
                    text = "History",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        ) {
            HistorySheetContent(
                historyCronjobs = historyCronjobs,
                canLoadMore = historyCanLoadMore,
                isLoading = historyLoading,
                onLoadMore = { viewModel.loadMoreHistory() },
                onToggleEnabled = { viewModel.toggleEnabled(it) },
                onDelete = { viewModel.requestDelete(it.id) }
            )
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize()
        ) {
            if (cronjobs.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(32.dp)
                    ) {
                        Text(
                            text = "No scheduled tasks",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "Tasks scheduled through the agent will appear here",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            } else {
                val listState = rememberLazyListState()
                val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
                val heightCache = rememberLazyListHeightCache()

                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .drawScrollbar(listState, scrollbarColor, heightCache)
                        .padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                    items(cronjobs, key = { it.id }) { cronjob ->
                        CronjobCard(
                            cronjob = cronjob,
                            isExpanded = selectedCronjobId == cronjob.id,
                            executionLogs = if (selectedCronjobId == cronjob.id) executionLogs else emptyList(),
                            onToggleEnabled = { viewModel.toggleEnabled(cronjob) },
                            onToggleLogs = { viewModel.toggleExecutionLogs(cronjob.id) },
                            onDelete = { viewModel.requestDelete(cronjob.id) },
                            modifier = Modifier.animateItem()
                        )
                    }
                    item { Spacer(modifier = Modifier.height(4.dp)) }
                }
            }

            // History button - always visible at the bottom
            OutlinedButton(
                onClick = { viewModel.openHistory() },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Text("History")
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier.align(Alignment.BottomCenter)
        )
    }
}

@Composable
private fun HistorySheetContent(
    historyCronjobs: List<CronjobEntity>,
    canLoadMore: Boolean,
    isLoading: Boolean,
    onLoadMore: () -> Unit,
    onToggleEnabled: (CronjobEntity) -> Unit,
    onDelete: (CronjobEntity) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 16.dp)
    ) {
        if (historyCronjobs.isEmpty() && !isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "No completed tasks",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            val listState = rememberLazyListState()
            val scrollbarColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
            val heightCache = rememberLazyListHeightCache()

            // Detect scroll near end to trigger loading more
            val shouldLoadMore by remember {
                derivedStateOf {
                    val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()
                        ?: return@derivedStateOf false
                    lastVisibleItem.index >= listState.layoutInfo.totalItemsCount - 3
                }
            }

            LaunchedEffect(shouldLoadMore) {
                if (shouldLoadMore && canLoadMore && !isLoading) {
                    onLoadMore()
                }
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .drawScrollbar(listState, scrollbarColor, heightCache)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(historyCronjobs, key = { it.id }) { cronjob ->
                    HistoryCard(
                        cronjob = cronjob,
                        onToggleEnabled = { onToggleEnabled(cronjob) },
                        onDelete = { onDelete(cronjob) },
                        modifier = Modifier.animateItem()
                    )
                }
                if (isLoading) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(modifier = Modifier.size(24.dp))
                        }
                    }
                }
                item { Spacer(modifier = Modifier.height(4.dp)) }
            }
        }
    }
}

@Composable
private fun HistoryCard(
    cronjob: CronjobEntity,
    onToggleEnabled: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Row 1: Title + enabled switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cronjob.title.ifBlank { cronjob.instruction },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val executeAt = cronjob.executeAt
                val isExpiredOneTime = cronjob.scheduleType == ScheduleType.ONE_TIME
                    && executeAt != null
                    && executeAt < System.currentTimeMillis()

                Switch(
                    checked = cronjob.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    enabled = !isExpiredOneTime
                )
            }

            // Show instruction as detail when title is present
            if (cronjob.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = cronjob.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Completed badge for disabled one-time tasks
            if (cronjob.scheduleType == ScheduleType.ONE_TIME && cronjob.executionCount > 0) {
                Surface(
                    color = Color(0xFF4CAF50).copy(alpha = 0.15f),
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(
                        text = "Completed",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            }

            // Row 2: Schedule description + execution count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSchedule(cronjob),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatRunCount(cronjob.executionCount, cronjob.maxExecutions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Row 3: Last executed + delete
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cronjob.lastExecutedAt?.let { "Last: ${formatTimestamp(it)}" } ?: "Never executed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete task",
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
private fun CronjobCard(
    cronjob: CronjobEntity,
    isExpanded: Boolean,
    executionLogs: List<ExecutionLog>,
    onToggleEnabled: () -> Unit,
    onToggleLogs: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Row 1: Title + enabled switch
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cronjob.title.ifBlank { cronjob.instruction },
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                Spacer(modifier = Modifier.width(8.dp))
                val executeAt = cronjob.executeAt
                val isExpiredOneTime = cronjob.scheduleType == ScheduleType.ONE_TIME
                    && executeAt != null
                    && executeAt < System.currentTimeMillis()

                Switch(
                    checked = cronjob.enabled,
                    onCheckedChange = { onToggleEnabled() },
                    enabled = !isExpiredOneTime
                )
            }

            // Show instruction as detail when title is present
            if (cronjob.title.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = cronjob.instruction,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Row 2: Schedule description + execution count
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = formatSchedule(cronjob),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatRunCount(cronjob.executionCount, cronjob.maxExecutions),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Row 3: Last executed + actions
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = cronjob.lastExecutedAt?.let { "Last: ${formatTimestamp(it)}" } ?: "Never executed",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    IconButton(onClick = onToggleLogs) {
                        Icon(
                            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Hide logs" else "Show logs",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            imageVector = Icons.Default.Delete,
                            contentDescription = "Delete task",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Expandable execution logs
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                    if (executionLogs.isEmpty()) {
                        Text(
                            text = "No execution history",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    } else {
                        executionLogs.take(10).forEach { log ->
                            ExecutionLogItem(log = log)
                            Spacer(modifier = Modifier.height(6.dp))
                        }
                        if (executionLogs.size > 10) {
                            Text(
                                text = "and ${executionLogs.size - 10} more...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExecutionLogItem(
    log: ExecutionLog,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = when (log.status) {
        ExecutionStatus.SUCCESS -> Icons.Default.Check to Color(0xFF4CAF50)
        ExecutionStatus.FAILED -> Icons.Default.Close to MaterialTheme.colorScheme.error
        ExecutionStatus.CANCELLED -> Icons.Default.Close to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = MaterialTheme.shapes.small
    ) {
        Row(
            modifier = Modifier.padding(8.dp),
            verticalAlignment = Alignment.Top
        ) {
            Icon(
                imageVector = icon,
                contentDescription = log.status.name,
                tint = tint,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = log.status.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = tint
                    )
                    Text(
                        text = formatTimestamp(log.startedAt),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                val summary = when {
                    log.status == ExecutionStatus.FAILED && log.errorMessage != null -> log.errorMessage
                    log.resultSummary != null -> log.resultSummary
                    else -> null
                }
                if (summary != null) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

private fun formatSchedule(cronjob: CronjobEntity): String {
    return when (cronjob.scheduleType) {
        ScheduleType.ONE_TIME -> {
            cronjob.executeAt?.let { "Once at ${formatFullTimestamp(it)}" } ?: "One-time"
        }
        ScheduleType.RECURRING -> {
            when {
                cronjob.cronExpression != null -> formatCronExpression(cronjob.cronExpression!!)
                cronjob.intervalMinutes != null -> formatIntervalMinutes(cronjob.intervalMinutes!!)
                else -> "Recurring"
            }
        }
        ScheduleType.CONDITIONAL -> "Conditional"
    }
}

private fun formatRunCount(executionCount: Int, maxExecutions: Int?): String {
    val suffix = if (executionCount >= 2) "s" else ""
    return if (maxExecutions != null) {
        "$executionCount / $maxExecutions run$suffix"
    } else {
        "$executionCount run$suffix"
    }
}

private val timeFormat = SimpleDateFormat("MMM d, h:mm a", Locale.getDefault())
private val fullFormat = SimpleDateFormat("MMM d yyyy, h:mm a", Locale.getDefault())

private fun formatTimestamp(millis: Long): String {
    return timeFormat.format(Date(millis))
}

private fun formatFullTimestamp(millis: Long): String {
    return fullFormat.format(Date(millis))
}

