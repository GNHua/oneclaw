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
