package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.TokenEstimator
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage

data class CompactResult(
    val didCompact: Boolean,
    val fallbackToTruncation: Boolean = false
)

class AutoCompactUseCase(
    private val sessionRepository: SessionRepository,
    private val messageRepository: MessageRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory
) {
    companion object {
        const val COMPACT_THRESHOLD_RATIO = 0.85
        const val PROTECTED_WINDOW_RATIO = 0.25
        const val SUMMARY_MAX_TOKENS = 2048
        const val MAX_RETRIES = 1
    }

    suspend fun compactIfNeeded(
        sessionId: String,
        model: AiModel,
        provider: Provider
    ): CompactResult {
        // Step 1: Check model.contextWindowSize -- if null, return (no-op)
        val contextWindowSize = model.contextWindowSize ?: return CompactResult(didCompact = false)

        // Step 2: Get all messages for the session
        val allMessages = messageRepository.getMessagesSnapshot(sessionId)

        // Step 3: Estimate total tokens via TokenEstimator
        val totalTokens = TokenEstimator.estimateTotalTokens(allMessages)

        // Step 4: If totalTokens <= threshold, return (no-op)
        val threshold = (contextWindowSize * COMPACT_THRESHOLD_RATIO).toInt()
        if (totalTokens <= threshold) return CompactResult(didCompact = false)

        // Step 5: Split messages into (olderMessages, protectedMessages)
        val protectedBudget = (contextWindowSize * PROTECTED_WINDOW_RATIO).toInt()
        val (olderMessages, _) = splitMessages(allMessages, protectedBudget)

        // Step 6: If olderMessages is empty, return (no-op)
        if (olderMessages.isEmpty()) return CompactResult(didCompact = false)

        // Step 7: Get existing session summary
        val session = sessionRepository.getSessionById(sessionId) ?: return CompactResult(didCompact = false)

        // Step 8: Build summarization prompt
        val prompt = buildSummarizationPrompt(olderMessages, session.compactedSummary)

        // Step 9: Get API key and adapter
        val apiKey = apiKeyStorage.getApiKey(provider.id) ?: return CompactResult(didCompact = false)
        val adapter = adapterFactory.getAdapter(provider.type)

        // Step 10: Call adapter.generateSimpleCompletion with retry
        val summaryResult = runWithRetry(MAX_RETRIES) {
            adapter.generateSimpleCompletion(
                apiBaseUrl = provider.apiBaseUrl,
                apiKey = apiKey,
                modelId = model.id,
                prompt = prompt,
                maxTokens = SUMMARY_MAX_TOKENS
            )
        }

        // Step 11: Handle result
        return when (summaryResult) {
            is AppResult.Success -> {
                val summary = summaryResult.data
                if (summary.isBlank()) {
                    CompactResult(didCompact = false)
                } else {
                    // Determine boundary timestamp: the createdAt of the first protected message
                    val boundaryTimestamp = allMessages
                        .drop(olderMessages.size)
                        .firstOrNull()
                        ?.createdAt
                    sessionRepository.updateCompactedSummary(sessionId, summary, boundaryTimestamp)
                    CompactResult(didCompact = true)
                }
            }
            is AppResult.Error -> CompactResult(didCompact = false)
        }
    }

    /**
     * Split messages into (olderMessages, protectedMessages).
     * Walk backwards from newest, accumulating tokens until we reach protectedBudget.
     * Everything before the split point = olderMessages.
     */
    fun splitMessages(
        messages: List<Message>,
        protectedBudget: Int
    ): Pair<List<Message>, List<Message>> {
        var accumulated = 0
        var splitIndex = messages.size

        for (i in messages.indices.reversed()) {
            val tokens = TokenEstimator.estimateMessageTokens(messages[i])
            if (accumulated + tokens > protectedBudget) {
                splitIndex = i + 1
                break
            }
            accumulated += tokens
            splitIndex = i
        }

        val olderMessages = messages.subList(0, splitIndex)
        val protectedMessages = messages.subList(splitIndex, messages.size)
        return Pair(olderMessages, protectedMessages)
    }

    private fun buildSummarizationPrompt(
        olderMessages: List<Message>,
        existingSummary: String?
    ): String {
        val sb = StringBuilder()
        sb.appendLine(
            """You are summarizing a conversation for context continuity. Create a concise but
comprehensive summary that preserves:
- Key topics discussed
- Important decisions or conclusions
- Any pending questions or tasks
- Tool calls made and their results (briefly)"""
        )

        if (!existingSummary.isNullOrBlank()) {
            sb.appendLine()
            sb.appendLine("Previous conversation summary:")
            sb.appendLine(existingSummary)
            sb.appendLine()
            sb.appendLine("Additional conversation to incorporate:")
        }

        sb.appendLine()
        for (msg in olderMessages) {
            val roleLabel = when (msg.type) {
                MessageType.USER -> "User"
                MessageType.AI_RESPONSE -> "Assistant"
                MessageType.TOOL_CALL -> "Tool Call (${msg.toolName})"
                MessageType.TOOL_RESULT -> "Tool Result (${msg.toolName})"
                MessageType.SYSTEM -> "System"
                MessageType.ERROR -> continue
            }
            val content = when (msg.type) {
                MessageType.TOOL_CALL -> msg.toolInput ?: msg.content
                MessageType.TOOL_RESULT -> msg.toolOutput ?: msg.content
                else -> msg.content
            }
            if (content.isNotBlank()) {
                sb.appendLine("$roleLabel: $content")
            }
        }

        sb.appendLine()
        sb.append("Provide a summary in 200-500 words. Be factual and concise.")
        return sb.toString()
    }

    private suspend fun runWithRetry(
        maxRetries: Int,
        block: suspend () -> AppResult<String>
    ): AppResult<String> {
        var lastResult: AppResult<String> = AppResult.Error(message = "Not attempted")
        repeat(maxRetries + 1) {
            val result = block()
            if (result is AppResult.Success && result.data.isNotBlank()) {
                return result
            }
            lastResult = result
        }
        return lastResult
    }
}
