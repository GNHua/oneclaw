package com.tomandy.oneclaw.ui.cronjobs

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tomandy.oneclaw.ui.HandleDismissBottomSheet
import com.tomandy.oneclaw.ui.MessagePreviewContent
import com.tomandy.oneclaw.scheduler.data.CronjobEntity
import com.tomandy.oneclaw.scheduler.data.ExecutionLog
import com.tomandy.oneclaw.scheduler.data.ExecutionStatus
import com.tomandy.oneclaw.scheduler.data.ScheduleType
import com.tomandy.oneclaw.scheduler.util.formatCronExpression
import com.tomandy.oneclaw.scheduler.util.formatIntervalMinutes
import com.tomandy.oneclaw.ui.theme.SuccessGreen
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun CronjobDetailScreen(
    viewModel: CronjobsViewModel,
    cronjobId: String,
    modifier: Modifier = Modifier
) {
    val cronjobFlow = remember(cronjobId) { viewModel.getCronjobById(cronjobId) }
    val cronjob by cronjobFlow.collectAsState()

    val logs by viewModel.detailLogs.collectAsState()
    val canLoadMore by viewModel.detailLogsCanLoadMore.collectAsState()
    val logsLoading by viewModel.detailLogsLoading.collectAsState()

    val showConversation by viewModel.showConversation.collectAsState()
    val conversationMessages by viewModel.conversationMessages.collectAsState()
    val conversationLoading by viewModel.conversationLoading.collectAsState()

    LaunchedEffect(cronjobId) {
        viewModel.loadExecutionLogs(cronjobId)
    }

    val task = cronjob
    if (task == null) {
        return
    }

    val listState = rememberLazyListState()

    // Trigger load more when scrolling near the bottom
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleItem = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            canLoadMore && !logsLoading && lastVisibleItem >= totalItems - 3
        }
    }

    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore) {
            viewModel.loadMoreExecutionLogs()
        }
    }

    LazyColumn(
        state = listState,
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(0.dp)
    ) {
        item { Spacer(modifier = Modifier.height(16.dp)) }

        // Title
        item {
            if (task.title.isNotBlank()) {
                Text(
                    text = task.title,
                    style = MaterialTheme.typography.headlineSmall
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }

        // Instruction
        item {
            DetailSection("Instruction") {
                Text(
                    text = task.instruction,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Schedule
        item {
            DetailSection("Schedule") {
                Text(
                    text = formatScheduleDetail(task),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Status
        item {
            DetailSection("Status") {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    StatusChip(
                        label = if (task.enabled) "Enabled" else "Disabled",
                        color = if (task.enabled) SuccessGreen else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (task.scheduleType == ScheduleType.ONE_TIME && task.executionCount > 0) {
                        StatusChip(label = "Completed", color = SuccessGreen)
                    }
                }
            }
        }

        // Execution stats
        item {
            DetailSection("Executions") {
                DetailRow("Count", formatRunCount(task.executionCount, task.maxExecutions))
                if (task.maxExecutions != null) {
                    DetailRow("Max executions", task.maxExecutions.toString())
                }
                DetailRow(
                    "Last executed",
                    task.lastExecutedAt?.let { formatDetailTimestamp(it) } ?: "Never"
                )
                DetailRow("Created", formatDetailTimestamp(task.createdAt))
            }
        }

        // Constraints
        item {
            val constraints = parseConstraints(task.constraints)
            if (constraints.first || constraints.second) {
                DetailSection("Constraints") {
                    if (constraints.first) {
                        DetailRow("Network", "Required")
                    }
                    if (constraints.second) {
                        DetailRow("Charging", "Required")
                    }
                }
            }
        }

        // Execution logs
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "Execution History",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(vertical = 8.dp)
            )
        }

        if (logs.isEmpty() && !logsLoading) {
            item {
                Text(
                    text = "No execution history",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp)
                )
            }
        } else {
            items(logs, key = { it.id }) { log ->
                DetailExecutionLogItem(
                    log = log,
                    onClick = {
                        viewModel.openConversation(log.conversationId)
                    }
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }

        if (logsLoading) {
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

        item { Spacer(modifier = Modifier.height(16.dp)) }
    }

    // Conversation viewer bottom sheet
    if (showConversation) {
        HandleDismissBottomSheet(
            onDismissRequest = { viewModel.closeConversation() },
            header = {
                Text(
                    text = "Execution Conversation",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider()
            }
        ) {
            MessagePreviewContent(
                messages = conversationMessages,
                isLoading = conversationLoading,
                modifier = Modifier.weight(1f, fill = false)
            )
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
private fun DetailSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column(modifier = Modifier.padding(vertical = 8.dp)) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        content()
    }
}

@Composable
private fun DetailRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

@Composable
private fun StatusChip(label: String, color: androidx.compose.ui.graphics.Color) {
    Surface(
        color = color.copy(alpha = 0.15f),
        shape = MaterialTheme.shapes.small
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

@Composable
private fun DetailExecutionLogItem(
    log: ExecutionLog,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, tint) = when (log.status) {
        ExecutionStatus.SUCCESS -> Icons.Default.Check to SuccessGreen
        ExecutionStatus.FAILED -> Icons.Default.Close to MaterialTheme.colorScheme.error
        ExecutionStatus.CANCELLED -> Icons.Default.Close to MaterialTheme.colorScheme.onSurfaceVariant
    }

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(enabled = log.conversationId != null, onClick = onClick),
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
                        text = formatDetailTimestamp(log.startedAt),
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
                        maxLines = 5,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                val completed = log.completedAt
                if (completed != null) {
                    val durationMs = completed - log.startedAt
                    val durationSec = durationMs / 1000
                    Text(
                        text = "Duration: ${durationSec}s",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                if (log.conversationId != null) {
                    Text(
                        text = "Tap to view conversation",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
            }
        }
    }
}

private fun formatScheduleDetail(cronjob: CronjobEntity): String {
    return when (cronjob.scheduleType) {
        ScheduleType.ONE_TIME -> {
            cronjob.executeAt?.let { "One-time at ${formatDetailTimestamp(it)}" } ?: "One-time"
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

private val detailFormat = SimpleDateFormat("MMM d yyyy, h:mm a", Locale.getDefault())

private fun formatDetailTimestamp(millis: Long): String {
    return detailFormat.format(Date(millis))
}

private fun parseConstraints(constraintsJson: String): Pair<Boolean, Boolean> {
    return try {
        val obj = Json.parseToJsonElement(constraintsJson).jsonObject
        val network = obj["requiresNetwork"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        val charging = obj["requiresCharging"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
        network to charging
    } catch (_: Exception) {
        false to false
    }
}
