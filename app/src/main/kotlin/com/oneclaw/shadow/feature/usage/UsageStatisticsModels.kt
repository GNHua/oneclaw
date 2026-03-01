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
