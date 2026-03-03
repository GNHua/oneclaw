# RFC-006: Token Usage Tracking

## Document Information
- **RFC ID**: RFC-006
- **Related PRD**: [FEAT-006 (Token Usage Tracking)](../../prd/features/FEAT-006-token-tracking.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Depends On**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **Depended On By**: None
- **Created**: 2026-02-28
- **Last Updated**: 2026-02-28
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw users provide their own API keys, which means they bear the direct cost of every API call. Currently, there is no visibility into how many tokens each message, session, or model consumes. Users have no way to understand their usage patterns or manage their API spending without leaving the app and checking provider dashboards.

The data layer already supports token tracking. `MessageEntity` has `token_count_input` and `token_count_output` columns, and the `Message` domain model exposes `tokenCountInput` and `tokenCountOutput` fields. These values are populated from `StreamEvent.Usage` events emitted during streaming. What is missing is the UI to surface this data to users at three levels: per-message, per-session, and globally.

### Goals

1. Display per-message input/output token counts next to the model ID label on each AI response bubble.
2. Display per-session cumulative token counts in the session drawer, right-aligned below the preview text.
3. Provide a dedicated Usage Statistics screen accessible from Settings, showing token breakdown by model with time period filtering (Today / This Week / This Month / All Time).
4. Format all token numbers with comma separators and use K/M abbreviations where appropriate.

### Non-Goals

- Cost or price estimation based on token counts.
- Budget alerts or spending limits.
- Per-agent usage breakdown.
- Data export (CSV, etc.).
- Historical trend charts or bar charts.
- Token prediction for unsent messages.

## Technical Design

### Architecture Overview

```
Three display surfaces, all reading from the same messages table:

1. Per-Message (ChatScreen)
   ChatMessageItem.tokenCountInput/Output -> AiMessageBubble label

2. Per-Session (SessionDrawer)
   MessageDao.getTotalTokensForSession() -> SessionListItem.totalTokens -> SessionDrawerContent label

3. Global (UsageStatisticsScreen)
   MessageDao.getUsageStatsByModel() -> UsageStatisticsViewModel -> UsageStatisticsScreen table
```

No schema changes are needed. All data already exists in the `messages` table.

### Change 1: Per-Message Token Display

#### 1a. Add token fields to `ChatMessageItem`

`ChatMessageItem` currently has no token fields. Add them:

```kotlin
data class ChatMessageItem(
    val id: String,
    val type: MessageType,
    val content: String,
    val thinkingContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolDurationMs: Long? = null,
    val modelId: String? = null,
    val tokenCountInput: Int? = null,   // NEW
    val tokenCountOutput: Int? = null,  // NEW
    val isRetryable: Boolean = false,
    val timestamp: Long = 0
)
```

#### 1b. Update `toChatMessageItem()` mapper

The existing mapper in `ChatViewModel.kt` does not map token fields. Add them:

```kotlin
fun Message.toChatMessageItem(): ChatMessageItem = ChatMessageItem(
    id = id, type = type, content = content, thinkingContent = thinkingContent,
    toolCallId = toolCallId, toolName = toolName, toolInput = toolInput, toolOutput = toolOutput,
    toolStatus = toolStatus, toolDurationMs = toolDurationMs, modelId = modelId,
    tokenCountInput = tokenCountInput,     // NEW
    tokenCountOutput = tokenCountOutput,   // NEW
    isRetryable = type == MessageType.ERROR, timestamp = createdAt
)
```

#### 1c. Add token parameters to `AiMessageBubble`

Add `tokenCountInput: Int?` and `tokenCountOutput: Int?` parameters. Display them next to the existing `modelId` label in the action row:

```kotlin
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun AiMessageBubble(
    content: String,
    thinkingContent: String?,
    modelId: String?,
    tokenCountInput: Int?,    // NEW
    tokenCountOutput: Int?,   // NEW
    isLastAiMessage: Boolean,
    onCopy: () -> Unit,
    onRegenerate: () -> Unit,
    isStreaming: Boolean = false
) {
    // ... existing Column/Surface/Markdown/StreamingCursor code unchanged ...

    if (!isStreaming && isLastAiMessage && content.isNotEmpty()) {
        Row(
            modifier = Modifier.padding(start = 8.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            IconButton(onClick = onCopy, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.ContentCopy, contentDescription = "Copy",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onRegenerate, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.Refresh, contentDescription = "Regenerate",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (modelId != null) {
                Text(
                    text = modelId,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = 8.dp)
                )
            }
            // NEW: token count label
            if (tokenCountInput != null && tokenCountOutput != null) {
                Text(
                    text = "${formatWithCommas(tokenCountInput)} in / ${formatWithCommas(tokenCountOutput)} out",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .align(Alignment.CenterVertically)
                        .padding(start = if (modelId != null) 4.dp else 8.dp)
                )
            }
        }
    }
}
```

The `|` separator between model ID and token counts is implicit via spacing. If the model ID label is present, the token label follows with a small gap.

#### 1d. Update `AiMessageBubble` call site

Wherever `AiMessageBubble` is called in `ChatScreen.kt`, pass the new token fields from `ChatMessageItem`:

```kotlin
AiMessageBubble(
    content = message.content,
    thinkingContent = message.thinkingContent,
    modelId = message.modelId,
    tokenCountInput = message.tokenCountInput,
    tokenCountOutput = message.tokenCountOutput,
    isLastAiMessage = isLastAiMessage,
    onCopy = { /* ... */ },
    onRegenerate = { /* ... */ },
    isStreaming = false
)
```

### Change 2: Session Drawer Token Summary

#### 2a. Add aggregate query to `MessageDao`

```kotlin
@Query(
    """
    SELECT COALESCE(SUM(token_count_input), 0) + COALESCE(SUM(token_count_output), 0)
    FROM messages
    WHERE session_id = :sessionId AND token_count_input IS NOT NULL
    """
)
suspend fun getTotalTokensForSession(sessionId: String): Long
```

This returns the combined input+output token total for a session. Messages where `token_count_input IS NULL` (provider did not return usage) are excluded from the sum.

#### 2b. Add `totalTokens` field to `SessionListItem`

```kotlin
data class SessionListItem(
    val id: String,
    val title: String,
    val agentName: String,
    val lastMessagePreview: String?,
    val relativeTime: String,
    val isActive: Boolean,
    val isSelected: Boolean,
    val totalTokens: Long = 0  // NEW
)
```

#### 2c. Load token totals in `SessionListViewModel`

In the `loadSessions()` method, query total tokens for each session and include in the `SessionListItem`:

```kotlin
private fun loadSessions() {
    viewModelScope.launch {
        sessionRepository.getAllSessions().collect { sessions ->
            val selected = _uiState.value.selectedSessionIds
            val items = sessions.map { session ->
                val totalTokens = messageDao.getTotalTokensForSession(session.id)
                SessionListItem(
                    id = session.id,
                    title = session.title,
                    agentName = agentNameCache[session.currentAgentId] ?: "Agent",
                    lastMessagePreview = session.lastMessagePreview,
                    relativeTime = formatRelativeTime(session.updatedAt),
                    isActive = session.isActive,
                    isSelected = session.id in selected,
                    totalTokens = totalTokens
                )
            }
            _uiState.update { it.copy(sessions = items, isLoading = false) }
        }
    }
}
```

The `SessionListViewModel` constructor needs a new `messageDao` dependency. See Implementation Step 5 for the DI change.

#### 2d. Display token total in `SessionListItemRow`

In the `trailingContent` of `SessionListItemRow`, add a token label below the existing relative time and agent badge:

```kotlin
trailingContent = {
    Column(horizontalAlignment = Alignment.End) {
        Text(
            text = item.relativeTime,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(4.dp))
        Surface(
            shape = MaterialTheme.shapes.small,
            color = MaterialTheme.colorScheme.secondaryContainer
        ) {
            Text(
                text = item.agentName,
                style = MaterialTheme.typography.labelSmall,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        // NEW: token total
        if (item.totalTokens > 0) {
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = "${abbreviateNumber(item.totalTokens)} tokens",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
},
```

### Change 3: Usage Statistics Screen

#### 3a. Data class for model usage stats

New file: `feature/usage/UsageStatisticsModels.kt`

```kotlin
package com.oneclaw.shadow.feature.usage

data class ModelUsageStats(
    val modelId: String,
    val inputTokens: Long,
    val outputTokens: Long,
    val messageCount: Int
) {
    val totalTokens: Long get() = inputTokens + outputTokens
}

enum class TimePeriod {
    TODAY,
    THIS_WEEK,
    THIS_MONTH,
    ALL_TIME
}
```

#### 3b. Add aggregate query to `MessageDao`

```kotlin
data class ModelUsageRow(
    @ColumnInfo(name = "model_id") val modelId: String,
    @ColumnInfo(name = "input_tokens") val inputTokens: Long,
    @ColumnInfo(name = "output_tokens") val outputTokens: Long,
    @ColumnInfo(name = "message_count") val messageCount: Int
)

@Query(
    """
    SELECT model_id,
           COALESCE(SUM(token_count_input), 0) AS input_tokens,
           COALESCE(SUM(token_count_output), 0) AS output_tokens,
           COUNT(*) AS message_count
    FROM messages
    WHERE type = 'AI_RESPONSE'
      AND token_count_input IS NOT NULL
      AND created_at >= :since
    GROUP BY model_id
    ORDER BY (COALESCE(SUM(token_count_input), 0) + COALESCE(SUM(token_count_output), 0)) DESC
    """
)
suspend fun getUsageStatsByModel(since: Long): List<ModelUsageRow>
```

The `since` parameter is the epoch millis threshold. For "All Time", pass `0L`. For "Today", pass midnight local time. For "This Week", pass Monday 00:00 local time. For "This Month", pass 1st of the month 00:00.

The `ModelUsageRow` data class must be defined in the DAO file or in a separate file that Room can access for its column mapping.

#### 3c. New Route

Add to `Routes.kt`:

```kotlin
sealed class Route(val path: String) {
    // ... existing routes ...
    data object UsageStatistics : Route("usage")  // NEW
}
```

#### 3d. `UsageStatisticsViewModel`

New file: `feature/usage/UsageStatisticsViewModel.kt`

```kotlin
package com.oneclaw.shadow.feature.usage

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oneclaw.shadow.data.local.dao.MessageDao
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

class UsageStatisticsViewModel(
    private val messageDao: MessageDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(UsageStatisticsUiState())
    val uiState: StateFlow<UsageStatisticsUiState> = _uiState.asStateFlow()

    init {
        loadStats(TimePeriod.ALL_TIME)
    }

    fun selectTimePeriod(period: TimePeriod) {
        _uiState.update { it.copy(selectedPeriod = period) }
        loadStats(period)
    }

    private fun loadStats(period: TimePeriod) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            val since = computeSinceTimestamp(period)
            val rows = messageDao.getUsageStatsByModel(since)
            val stats = rows.map { row ->
                ModelUsageStats(
                    modelId = row.modelId,
                    inputTokens = row.inputTokens,
                    outputTokens = row.outputTokens,
                    messageCount = row.messageCount
                )
            }
            _uiState.update {
                it.copy(
                    modelStats = stats,
                    isLoading = false
                )
            }
        }
    }

    internal fun computeSinceTimestamp(period: TimePeriod): Long {
        val cal = Calendar.getInstance()
        return when (period) {
            TimePeriod.ALL_TIME -> 0L
            TimePeriod.TODAY -> {
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            TimePeriod.THIS_WEEK -> {
                cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
            TimePeriod.THIS_MONTH -> {
                cal.set(Calendar.DAY_OF_MONTH, 1)
                cal.set(Calendar.HOUR_OF_DAY, 0)
                cal.set(Calendar.MINUTE, 0)
                cal.set(Calendar.SECOND, 0)
                cal.set(Calendar.MILLISECOND, 0)
                cal.timeInMillis
            }
        }
    }
}

data class UsageStatisticsUiState(
    val selectedPeriod: TimePeriod = TimePeriod.ALL_TIME,
    val modelStats: List<ModelUsageStats> = emptyList(),
    val isLoading: Boolean = true
) {
    val totalInputTokens: Long get() = modelStats.sumOf { it.inputTokens }
    val totalOutputTokens: Long get() = modelStats.sumOf { it.outputTokens }
    val totalTokens: Long get() = modelStats.sumOf { it.totalTokens }
    val totalMessageCount: Int get() = modelStats.sumOf { it.messageCount }
}
```

#### 3e. `UsageStatisticsScreen`

New file: `feature/usage/UsageStatisticsScreen.kt`

```kotlin
package com.oneclaw.shadow.feature.usage

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UsageStatisticsScreen(
    onNavigateBack: () -> Unit,
    viewModel: UsageStatisticsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Usage Statistics") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Time period chips
            TimePeriodChipRow(
                selectedPeriod = uiState.selectedPeriod,
                onPeriodSelected = { viewModel.selectTimePeriod(it) },
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )

            if (uiState.modelStats.isEmpty() && !uiState.isLoading) {
                Text(
                    text = "No usage data for this period.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            } else {
                // Header row
                UsageHeaderRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // Model rows
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.modelStats) { stats ->
                        UsageModelRow(
                            stats = stats,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                // Totals row
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
                UsageTotalsRow(
                    totalInput = uiState.totalInputTokens,
                    totalOutput = uiState.totalOutputTokens,
                    totalTokens = uiState.totalTokens,
                    totalMessages = uiState.totalMessageCount,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
        }
    }
}

@Composable
private fun TimePeriodChipRow(
    selectedPeriod: TimePeriod,
    onPeriodSelected: (TimePeriod) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        TimePeriod.entries.forEach { period ->
            FilterChip(
                selected = period == selectedPeriod,
                onClick = { onPeriodSelected(period) },
                label = {
                    Text(
                        when (period) {
                            TimePeriod.TODAY -> "Today"
                            TimePeriod.THIS_WEEK -> "This Week"
                            TimePeriod.THIS_MONTH -> "This Month"
                            TimePeriod.ALL_TIME -> "All Time"
                        }
                    )
                }
            )
        }
    }
}

@Composable
private fun UsageHeaderRow(modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Model",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = "Input",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Output",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Total",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = "Msgs",
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun UsageModelRow(stats: ModelUsageStats, modifier: Modifier = Modifier) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = stats.modelId,
            style = MaterialTheme.typography.bodySmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = formatWithCommas(stats.inputTokens),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(stats.outputTokens),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(stats.totalTokens),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(stats.messageCount.toLong()),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.weight(0.6f)
        )
    }
}

@Composable
private fun UsageTotalsRow(
    totalInput: Long,
    totalOutput: Long,
    totalTokens: Long,
    totalMessages: Int,
    modifier: Modifier = Modifier
) {
    Row(modifier = modifier.fillMaxWidth()) {
        Text(
            text = "Total",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(2f)
        )
        Text(
            text = formatWithCommas(totalInput),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(totalOutput),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(totalTokens),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(1f)
        )
        Text(
            text = formatWithCommas(totalMessages.toLong()),
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.weight(0.6f)
        )
    }
}
```

#### 3f. Add "Usage Statistics" entry to `SettingsScreen`

Add a new callback parameter and a new settings item:

```kotlin
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onManageProviders: () -> Unit,
    onManageAgents: () -> Unit = {},
    onUsageStatistics: () -> Unit = {}  // NEW
) {
    Scaffold(
        topBar = { /* ... unchanged ... */ }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SettingsItem(
                title = "Manage Agents",
                subtitle = "Create and configure AI agents",
                onClick = onManageAgents
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            SettingsItem(
                title = "Manage Providers",
                subtitle = "Add API keys, configure models",
                onClick = onManageProviders
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
            // NEW
            SettingsItem(
                title = "Usage Statistics",
                subtitle = "View token usage by model and time period",
                onClick = onUsageStatistics
            )
            HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
        }
    }
}
```

#### 3g. Register in NavGraph

In `NavGraph.kt`, add the composable for the new route:

```kotlin
composable(Route.UsageStatistics.path) {
    UsageStatisticsScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

Update the `SettingsScreen` call site to pass navigation:

```kotlin
composable(Route.Settings.path) {
    SettingsScreen(
        onNavigateBack = { navController.popBackStack() },
        onManageProviders = { navController.navigate(Route.ProviderList.path) },
        onManageAgents = { navController.navigate(Route.AgentList.path) },
        onUsageStatistics = { navController.navigate(Route.UsageStatistics.path) }
    )
}
```

#### 3h. Register ViewModel in Koin DI

In `FeatureModule.kt`:

```kotlin
val featureModule = module {
    // ... existing registrations ...

    // RFC-006: Usage Statistics
    viewModelOf(::UsageStatisticsViewModel)
}
```

No use case factory is needed -- the ViewModel queries `MessageDao` directly.

### Utility Functions

Two formatting functions are used across multiple composables. Place them in a shared utility file.

New file: `core/util/NumberFormat.kt`

```kotlin
package com.oneclaw.shadow.core.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formats a number with comma separators: 1234567 -> "1,234,567"
 */
fun formatWithCommas(value: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(value)
}

fun formatWithCommas(value: Int): String {
    return NumberFormat.getNumberInstance(Locale.US).format(value)
}

/**
 * Abbreviates large numbers: 1234 -> "1.2K", 1234567 -> "1.2M"
 * Numbers below 1000 are returned as-is with comma formatting.
 */
fun abbreviateNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> formatWithCommas(value)
    }
}
```

- `formatWithCommas` is used in per-message labels and the Usage Statistics table.
- `abbreviateNumber` is used in the session drawer token summary.

## Implementation Steps

### Step 1: Add utility formatting functions
- File: `app/src/main/kotlin/com/oneclaw/shadow/core/util/NumberFormat.kt` (new)
- Add `formatWithCommas(Long)`, `formatWithCommas(Int)`, and `abbreviateNumber(Long)` functions

### Step 2: Add token fields to `ChatMessageItem` and update mapper
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatUiState.kt`
  - Add `tokenCountInput: Int? = null` and `tokenCountOutput: Int? = null` to `ChatMessageItem`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatViewModel.kt`
  - Update `Message.toChatMessageItem()` to map `tokenCountInput` and `tokenCountOutput`

### Step 3: Display token counts in `AiMessageBubble`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatScreen.kt`
  - Add `tokenCountInput: Int?` and `tokenCountOutput: Int?` parameters to `AiMessageBubble`
  - Add token label `Text` composable next to the model ID label in the action row
  - Update all call sites of `AiMessageBubble` to pass the new parameters

### Step 4: Add `MessageDao.getTotalTokensForSession()` query
- File: `app/src/main/kotlin/com/oneclaw/shadow/data/local/dao/MessageDao.kt`
  - Add `getTotalTokensForSession(sessionId: String): Long` suspend function with aggregate SQL

### Step 5: Add token summary to session drawer
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionUiState.kt`
  - Add `totalTokens: Long = 0` to `SessionListItem`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionListViewModel.kt`
  - Add `MessageDao` constructor parameter
  - In `loadSessions()`, query `getTotalTokensForSession()` for each session and map into `SessionListItem.totalTokens`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionDrawerContent.kt`
  - In `SessionListItemRow`, add token label below the agent badge in `trailingContent`
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - Update `SessionListViewModel` constructor call to include `MessageDao` (Koin will resolve `get()` automatically with `viewModelOf`)

### Step 6: Add `MessageDao.getUsageStatsByModel()` query
- File: `app/src/main/kotlin/com/oneclaw/shadow/data/local/dao/MessageDao.kt`
  - Add `ModelUsageRow` data class
  - Add `getUsageStatsByModel(since: Long): List<ModelUsageRow>` suspend function

### Step 7: Create Usage Statistics data models
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsModels.kt` (new)
  - Add `ModelUsageStats` data class and `TimePeriod` enum

### Step 8: Create `UsageStatisticsViewModel`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsViewModel.kt` (new)
  - ViewModel with `MessageDao` dependency, `loadStats()` method, time period selection, `computeSinceTimestamp()`
  - `UsageStatisticsUiState` data class with computed total properties

### Step 9: Create `UsageStatisticsScreen`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsScreen.kt` (new)
  - Scaffold with TopAppBar, chip row, model rows, totals row
  - Uses `koinViewModel()` injection

### Step 10: Add route, navigation, settings entry, and DI registration
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/Routes.kt`
  - Add `data object UsageStatistics : Route("usage")`
- File: `app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt`
  - Add `onUsageStatistics` callback parameter
  - Add "Usage Statistics" settings item
- File: `app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - Add `composable(Route.UsageStatistics.path)` block
  - Pass `onUsageStatistics` to `SettingsScreen`
- File: `app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - Add `viewModelOf(::UsageStatisticsViewModel)`

## Test Strategy

### Layer 1A -- Unit Tests

**`NumberFormatTest`** (`app/src/test/kotlin/.../core/util/`):
- Test: `formatWithCommas(1234567L)` returns `"1,234,567"`
- Test: `formatWithCommas(0L)` returns `"0"`
- Test: `formatWithCommas(999)` returns `"999"`
- Test: `abbreviateNumber(1234L)` returns `"1.2K"`
- Test: `abbreviateNumber(1_234_567L)` returns `"1.2M"`
- Test: `abbreviateNumber(500L)` returns `"500"`

**`UsageStatisticsViewModelTest`** (`app/src/test/kotlin/.../feature/usage/`):
- Test: `computeSinceTimestamp(ALL_TIME)` returns `0L`
- Test: `computeSinceTimestamp(TODAY)` returns midnight of current day
- Test: `computeSinceTimestamp(THIS_WEEK)` returns Monday 00:00 of current week
- Test: `computeSinceTimestamp(THIS_MONTH)` returns 1st of current month 00:00
- Test: `selectTimePeriod(TODAY)` updates `selectedPeriod` and triggers reload
- Test: initial load calls `getUsageStatsByModel(0L)` for ALL_TIME
- Test: empty query result produces empty `modelStats` and zero totals
- Test: multiple model rows are correctly mapped to `ModelUsageStats` list
- Test: totals are correctly computed from model stats

**`SessionListViewModelTokenTest`** (`app/src/test/kotlin/.../feature/session/`):
- Test: `loadSessions()` queries `getTotalTokensForSession()` for each session and maps to `SessionListItem.totalTokens`
- Test: session with zero tokens shows `totalTokens = 0`

**`ChatMessageItemMappingTest`** (`app/src/test/kotlin/.../feature/chat/`):
- Test: `Message.toChatMessageItem()` maps `tokenCountInput` and `tokenCountOutput` correctly
- Test: `toChatMessageItem()` with null token fields produces null in `ChatMessageItem`

### Layer 1C -- Screenshot Tests

- `AiMessageBubble` with token counts visible: `modelId = "claude-sonnet-4"`, `tokenCountInput = 1234`, `tokenCountOutput = 567` -- verify label shows "1,234 in / 567 out"
- `AiMessageBubble` with null token counts: verify no token label rendered
- `SessionListItemRow` with `totalTokens = 12345L` -- verify "12.3K tokens" label
- `SessionListItemRow` with `totalTokens = 0L` -- verify no token label
- `UsageStatisticsScreen` with sample data: verify chip row, model rows, totals row

### Layer 2 -- adb Visual Verification

**Flow 6-1: Per-message token display**
1. Configure a provider with a model that returns token usage
2. Start a chat, send a message
3. Observe below the AI response: model ID and token counts visible (e.g., "1,234 in / 567 out")
4. Verify the label uses small typography and muted color

**Flow 6-2: Session drawer token summary**
1. Have a conversation with several AI responses
2. Open the session drawer
3. Verify the current session shows a token total (e.g., "12.3K tokens") right-aligned below the agent badge
4. Verify other sessions also show their token totals

**Flow 6-3: Usage Statistics screen**
1. Go to Settings
2. Tap "Usage Statistics"
3. Verify the screen opens with "All Time" chip selected
4. Verify model rows display with input/output/total/message count columns
5. Verify totals row at the bottom
6. Tap "Today" chip -- verify table updates
7. Tap "This Month" chip -- verify table updates

## Data Flow

### Per-message token display

```
MessageEntity (DB)
  -> Message (domain model, has tokenCountInput/tokenCountOutput)
  -> Message.toChatMessageItem() maps to ChatMessageItem.tokenCountInput/Output
  -> ChatScreen renders ChatMessageItem list
  -> AiMessageBubble receives tokenCountInput/Output params
  -> if non-null: Text("1,234 in / 567 out") displayed next to model ID
```

### Session drawer token summary

```
SessionListViewModel.loadSessions()
  -> for each session: messageDao.getTotalTokensForSession(sessionId)
  -> SQL: SUM(token_count_input) + SUM(token_count_output) WHERE session_id = ?
  -> result mapped to SessionListItem.totalTokens
  -> SessionDrawerContent renders SessionListItemRow
  -> if totalTokens > 0: Text("12.3K tokens") in trailingContent
```

### Usage Statistics screen

```
UsageStatisticsViewModel.loadStats(period)
  -> computeSinceTimestamp(period) -> epoch millis
  -> messageDao.getUsageStatsByModel(since)
  -> SQL: GROUP BY model_id, SUM input/output, COUNT messages, filtered by created_at >= since
  -> List<ModelUsageRow> mapped to List<ModelUsageStats>
  -> UsageStatisticsUiState.modelStats updated
  -> computed totals: totalInputTokens, totalOutputTokens, totalTokens, totalMessageCount
  -> UsageStatisticsScreen renders chip row + model table + totals row
```

## Change History

| Date | Version | Change | Author |
|------|---------|--------|--------|
| 2026-02-28 | 0.1 | Initial draft | TBD |
