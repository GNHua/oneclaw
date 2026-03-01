package com.oneclaw.shadow.core.util

import com.oneclaw.shadow.core.model.Message

object TokenEstimator {
    const val CHARS_PER_TOKEN = 4

    fun estimateTotalTokens(messages: List<Message>): Int =
        messages.sumOf { estimateMessageTokens(it) }

    fun estimateMessageTokens(msg: Message): Int {
        val contentTokens = estimateFromText(msg.content)
        val thinkingTokens = msg.thinkingContent?.let { estimateFromText(it) } ?: 0
        val toolInputTokens = msg.toolInput?.let { estimateFromText(it) } ?: 0
        val toolOutputTokens = msg.toolOutput?.let { estimateFromText(it) } ?: 0
        return contentTokens + thinkingTokens + toolInputTokens + toolOutputTokens
    }

    fun estimateFromText(text: String): Int {
        if (text.isEmpty()) return 0
        return (text.length / CHARS_PER_TOKEN).coerceAtLeast(1)
    }
}
