# RFC-006: Token 用量追踪

## 文档信息
- **RFC ID**: RFC-006
- **关联 PRD**: [FEAT-006 (Token Usage Tracking)](../../prd/features/FEAT-006-token-tracking.md)
- **关联架构**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **依赖**: [RFC-001 (Chat Interaction)](RFC-001-chat-interaction.md)
- **被依赖**: 无
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClaw 的用户使用自己的 API key，这意味着每次 API 调用的费用由用户直接承担。目前应用中没有任何界面展示每条消息、每个会话或每个模型的 token 消耗情况。用户若想了解自己的用量模式或管理 API 开支，只能离开应用去查看各 provider 的后台面板。

数据层已经支持 token 追踪。`MessageEntity` 有 `token_count_input` 和 `token_count_output` 列，域模型 `Message` 也暴露了 `tokenCountInput` 和 `tokenCountOutput` 字段。这些值在 streaming 过程中从 `StreamEvent.Usage` 事件写入。目前缺少的是将这些数据在三个层面呈现给用户的 UI：单条消息、单个会话、全局统计。

### 目标

1. 在每条 AI 回复气泡上，紧邻 model ID 标签显示该消息的 input/output token 数。
2. 在会话抽屉中，每个会话的预览文本右侧显示该会话的累计 token 总量。
3. 提供从 Settings 进入的专用"Usage Statistics"页面，按模型和时间段（Today / This Week / This Month / All Time）展示 token 用量明细。
4. 所有 token 数字使用逗号分隔格式，较大数字使用 K/M 缩写。

### 非目标

- 基于 token 数量的费用或价格估算。
- 预算提醒或消费限额。
- 按 agent 分类的用量统计。
- 数据导出（CSV 等）。
- 历史趋势图表或柱状图。
- 未发送消息的 token 预估。

## 技术设计

### 架构概览

```
三个展示层面，均从同一张 messages 表读取数据：

1. 单条消息 (ChatScreen)
   ChatMessageItem.tokenCountInput/Output -> AiMessageBubble 标签

2. 单个会话 (SessionDrawer)
   MessageDao.getTotalTokensForSession() -> SessionListItem.totalTokens -> SessionDrawerContent 标签

3. 全局统计 (UsageStatisticsScreen)
   MessageDao.getUsageStatsByModel() -> UsageStatisticsViewModel -> UsageStatisticsScreen 表格
```

无需修改数据库 schema。所有数据已存在于 `messages` 表中。

### 变更 1：单条消息 Token 展示

#### 1a. 为 `ChatMessageItem` 添加 token 字段

`ChatMessageItem` 当前没有 token 字段，需要添加：

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

#### 1b. 更新 `toChatMessageItem()` 映射器

`ChatViewModel.kt` 中现有的映射器未映射 token 字段，需要补充：

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

#### 1c. 为 `AiMessageBubble` 添加 token 参数

添加 `tokenCountInput: Int?` 和 `tokenCountOutput: Int?` 参数，在操作行中现有的 `modelId` 标签旁显示：

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
    // ... 现有的 Column/Surface/Markdown/StreamingCursor 代码不变 ...

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
            // NEW: token 数量标签
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

model ID 与 token 数量之间的 `|` 分隔符通过间距隐式实现。当 model ID 标签存在时，token 标签紧随其后并保持小间距。

#### 1d. 更新 `AiMessageBubble` 调用处

在 `ChatScreen.kt` 中所有调用 `AiMessageBubble` 的地方，传入来自 `ChatMessageItem` 的新 token 字段：

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

### 变更 2：会话抽屉 Token 汇总

#### 2a. 为 `MessageDao` 添加聚合查询

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

返回某会话的 input+output token 合计。`token_count_input IS NULL` 的消息（provider 未返回用量数据）不参与求和。

#### 2b. 为 `SessionListItem` 添加 `totalTokens` 字段

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

#### 2c. 在 `SessionListViewModel` 中加载 token 总量

在 `loadSessions()` 方法中，为每个会话查询 token 总量并写入 `SessionListItem`：

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

`SessionListViewModel` 的构造函数需要新增 `messageDao` 依赖。详见实现步骤 5 中的 DI 变更。

#### 2d. 在 `SessionListItemRow` 中显示 token 总量

在 `SessionListItemRow` 的 `trailingContent` 中，在现有的时间和 agent 标签下方添加 token 标签：

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
        // NEW: token 总量
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

### 变更 3：Usage Statistics 页面

#### 3a. 模型用量统计数据类

新文件：`feature/usage/UsageStatisticsModels.kt`

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

#### 3b. 为 `MessageDao` 添加按模型聚合查询

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

`since` 参数为 epoch 毫秒阈值。"All Time" 传 `0L`，"Today" 传当天午夜，"This Week" 传本周一 00:00，"This Month" 传本月 1 日 00:00。

`ModelUsageRow` 数据类需定义在 DAO 文件中或 Room 可访问的独立文件中，以便进行列映射。

#### 3c. 新增 Route

在 `Routes.kt` 中添加：

```kotlin
sealed class Route(val path: String) {
    // ... 现有 route ...
    data object UsageStatistics : Route("usage")  // NEW
}
```

#### 3d. `UsageStatisticsViewModel`

新文件：`feature/usage/UsageStatisticsViewModel.kt`

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

新文件：`feature/usage/UsageStatisticsScreen.kt`

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
            // 时间段选择 chip
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
                // 表头行
                UsageHeaderRow(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))

                // 模型行
                LazyColumn(modifier = Modifier.weight(1f)) {
                    items(uiState.modelStats) { stats ->
                        UsageModelRow(
                            stats = stats,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                        )
                    }
                }

                // 合计行
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

#### 3f. 在 `SettingsScreen` 中添加"Usage Statistics"入口

添加新的回调参数和设置项：

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
        topBar = { /* ... 不变 ... */ }
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

#### 3g. 在 NavGraph 中注册

在 `NavGraph.kt` 中为新路由添加 composable：

```kotlin
composable(Route.UsageStatistics.path) {
    UsageStatisticsScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

更新 `SettingsScreen` 的调用处以传递导航回调：

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

#### 3h. 在 Koin DI 中注册 ViewModel

在 `FeatureModule.kt` 中：

```kotlin
val featureModule = module {
    // ... 现有注册 ...

    // RFC-006: Usage Statistics
    viewModelOf(::UsageStatisticsViewModel)
}
```

不需要 use case factory -- ViewModel 直接查询 `MessageDao`。

### 工具函数

两个格式化函数在多个 composable 中使用，放在共享工具文件中。

新文件：`core/util/NumberFormat.kt`

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

- `formatWithCommas` 用于单条消息标签和 Usage Statistics 表格。
- `abbreviateNumber` 用于会话抽屉的 token 汇总。

## 实现步骤

### 步骤 1：添加工具格式化函数
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/core/util/NumberFormat.kt`（新建）
- 添加 `formatWithCommas(Long)`、`formatWithCommas(Int)` 和 `abbreviateNumber(Long)` 函数

### 步骤 2：为 `ChatMessageItem` 添加 token 字段并更新映射器
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatUiState.kt`
  - 向 `ChatMessageItem` 添加 `tokenCountInput: Int? = null` 和 `tokenCountOutput: Int? = null`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatViewModel.kt`
  - 更新 `Message.toChatMessageItem()` 以映射 `tokenCountInput` 和 `tokenCountOutput`

### 步骤 3：在 `AiMessageBubble` 中显示 token 数量
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/chat/ChatScreen.kt`
  - 为 `AiMessageBubble` 添加 `tokenCountInput: Int?` 和 `tokenCountOutput: Int?` 参数
  - 在操作行中 model ID 标签旁添加 token 标签 `Text` composable
  - 更新所有 `AiMessageBubble` 的调用处传入新参数

### 步骤 4：添加 `MessageDao.getTotalTokensForSession()` 查询
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/data/local/dao/MessageDao.kt`
  - 添加 `getTotalTokensForSession(sessionId: String): Long` suspend 函数及聚合 SQL

### 步骤 5：在会话抽屉中添加 token 汇总
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionUiState.kt`
  - 向 `SessionListItem` 添加 `totalTokens: Long = 0`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionListViewModel.kt`
  - 添加 `MessageDao` 构造函数参数
  - 在 `loadSessions()` 中为每个会话查询 `getTotalTokensForSession()` 并映射到 `SessionListItem.totalTokens`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/session/SessionDrawerContent.kt`
  - 在 `SessionListItemRow` 的 `trailingContent` 中，agent 标签下方添加 token 标签
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - 更新 `SessionListViewModel` 构造调用以包含 `MessageDao`（Koin 通过 `viewModelOf` 自动解析 `get()`）

### 步骤 6：添加 `MessageDao.getUsageStatsByModel()` 查询
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/data/local/dao/MessageDao.kt`
  - 添加 `ModelUsageRow` 数据类
  - 添加 `getUsageStatsByModel(since: Long): List<ModelUsageRow>` suspend 函数

### 步骤 7：创建 Usage Statistics 数据模型
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsModels.kt`（新建）
  - 添加 `ModelUsageStats` 数据类和 `TimePeriod` 枚举

### 步骤 8：创建 `UsageStatisticsViewModel`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsViewModel.kt`（新建）
  - ViewModel 依赖 `MessageDao`，包含 `loadStats()` 方法、时间段选择、`computeSinceTimestamp()`
  - `UsageStatisticsUiState` 数据类及 computed total 属性

### 步骤 9：创建 `UsageStatisticsScreen`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/usage/UsageStatisticsScreen.kt`（新建）
  - Scaffold 含 TopAppBar、chip 行、模型行、合计行
  - 使用 `koinViewModel()` 注入

### 步骤 10：添加 route、导航、设置入口和 DI 注册
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/Routes.kt`
  - 添加 `data object UsageStatistics : Route("usage")`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/feature/provider/SettingsScreen.kt`
  - 添加 `onUsageStatistics` 回调参数
  - 添加"Usage Statistics"设置项
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/navigation/NavGraph.kt`
  - 添加 `composable(Route.UsageStatistics.path)` 代码块
  - 向 `SettingsScreen` 传递 `onUsageStatistics`
- 文件：`app/src/main/kotlin/com/oneclaw/shadow/di/FeatureModule.kt`
  - 添加 `viewModelOf(::UsageStatisticsViewModel)`

## 测试策略

### Layer 1A -- 单元测试

**`NumberFormatTest`** (`app/src/test/kotlin/.../core/util/`):
- 测试：`formatWithCommas(1234567L)` 返回 `"1,234,567"`
- 测试：`formatWithCommas(0L)` 返回 `"0"`
- 测试：`formatWithCommas(999)` 返回 `"999"`
- 测试：`abbreviateNumber(1234L)` 返回 `"1.2K"`
- 测试：`abbreviateNumber(1_234_567L)` 返回 `"1.2M"`
- 测试：`abbreviateNumber(500L)` 返回 `"500"`

**`UsageStatisticsViewModelTest`** (`app/src/test/kotlin/.../feature/usage/`):
- 测试：`computeSinceTimestamp(ALL_TIME)` 返回 `0L`
- 测试：`computeSinceTimestamp(TODAY)` 返回当天午夜时间戳
- 测试：`computeSinceTimestamp(THIS_WEEK)` 返回本周一 00:00 时间戳
- 测试：`computeSinceTimestamp(THIS_MONTH)` 返回本月 1 日 00:00 时间戳
- 测试：`selectTimePeriod(TODAY)` 更新 `selectedPeriod` 并触发重新加载
- 测试：初始加载调用 `getUsageStatsByModel(0L)` 对应 ALL_TIME
- 测试：空查询结果产生空的 `modelStats` 和零合计
- 测试：多个模型行正确映射为 `ModelUsageStats` 列表
- 测试：合计值从模型统计中正确计算

**`SessionListViewModelTokenTest`** (`app/src/test/kotlin/.../feature/session/`):
- 测试：`loadSessions()` 为每个会话查询 `getTotalTokensForSession()` 并映射到 `SessionListItem.totalTokens`
- 测试：token 为零的会话显示 `totalTokens = 0`

**`ChatMessageItemMappingTest`** (`app/src/test/kotlin/.../feature/chat/`):
- 测试：`Message.toChatMessageItem()` 正确映射 `tokenCountInput` 和 `tokenCountOutput`
- 测试：token 字段为 null 的 `toChatMessageItem()` 在 `ChatMessageItem` 中也产生 null

### Layer 1C -- 截图测试

- `AiMessageBubble` 显示 token 数量：`modelId = "claude-sonnet-4"`、`tokenCountInput = 1234`、`tokenCountOutput = 567` -- 验证标签显示 "1,234 in / 567 out"
- `AiMessageBubble` token 为 null：验证不渲染 token 标签
- `SessionListItemRow` 中 `totalTokens = 12345L` -- 验证显示 "12.3K tokens" 标签
- `SessionListItemRow` 中 `totalTokens = 0L` -- 验证不显示 token 标签
- `UsageStatisticsScreen` 含样例数据：验证 chip 行、模型行、合计行

### Layer 2 -- adb 可视化验证

**Flow 6-1: 单条消息 token 显示**
1. 配置一个返回 token 用量的 provider 和模型
2. 开始聊天，发送一条消息
3. 观察 AI 回复下方：model ID 和 token 数量可见（如 "1,234 in / 567 out"）
4. 验证标签使用小号字体和低对比度颜色

**Flow 6-2: 会话抽屉 token 汇总**
1. 进行包含多条 AI 回复的对话
2. 打开会话抽屉
3. 验证当前会话在 agent 标签下方显示 token 总量（如 "12.3K tokens"），右对齐
4. 验证其他会话也显示各自的 token 总量

**Flow 6-3: Usage Statistics 页面**
1. 进入 Settings
2. 点击"Usage Statistics"
3. 验证页面打开，默认选中"All Time" chip
4. 验证模型行显示 input/output/total/message count 列
5. 验证底部有合计行
6. 点击"Today" chip -- 验证表格更新
7. 点击"This Month" chip -- 验证表格更新

## 数据流

### 单条消息 token 显示

```
MessageEntity (DB)
  -> Message (域模型, 含 tokenCountInput/tokenCountOutput)
  -> Message.toChatMessageItem() 映射到 ChatMessageItem.tokenCountInput/Output
  -> ChatScreen 渲染 ChatMessageItem 列表
  -> AiMessageBubble 接收 tokenCountInput/Output 参数
  -> 若非 null: 显示 Text("1,234 in / 567 out") 在 model ID 旁
```

### 会话抽屉 token 汇总

```
SessionListViewModel.loadSessions()
  -> 对每个会话: messageDao.getTotalTokensForSession(sessionId)
  -> SQL: SUM(token_count_input) + SUM(token_count_output) WHERE session_id = ?
  -> 结果映射到 SessionListItem.totalTokens
  -> SessionDrawerContent 渲染 SessionListItemRow
  -> 若 totalTokens > 0: 在 trailingContent 中显示 Text("12.3K tokens")
```

### Usage Statistics 页面

```
UsageStatisticsViewModel.loadStats(period)
  -> computeSinceTimestamp(period) -> epoch 毫秒
  -> messageDao.getUsageStatsByModel(since)
  -> SQL: GROUP BY model_id, SUM input/output, COUNT messages, 按 created_at >= since 过滤
  -> List<ModelUsageRow> 映射为 List<ModelUsageStats>
  -> UsageStatisticsUiState.modelStats 更新
  -> computed totals: totalInputTokens, totalOutputTokens, totalTokens, totalMessageCount
  -> UsageStatisticsScreen 渲染 chip 行 + 模型表格 + 合计行
```

## 变更历史

| 日期 | 版本 | 变更内容 | 作者 |
|------|------|----------|------|
| 2026-02-28 | 0.1 | 初始草稿 | TBD |
