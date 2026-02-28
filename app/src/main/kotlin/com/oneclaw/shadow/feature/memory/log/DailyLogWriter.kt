package com.oneclaw.shadow.feature.memory.log

import android.util.Log
import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.local.dao.MemoryIndexDao
import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingEngine
import com.oneclaw.shadow.feature.memory.embedding.EmbeddingSerializer
import com.oneclaw.shadow.feature.memory.longterm.LongTermMemoryManager
import com.oneclaw.shadow.feature.memory.storage.MemoryFileStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.UUID

/**
 * Handles daily log extraction and writing.
 * Extracts new messages since lastLoggedMessageId, calls the AI model for
 * summarization, and writes the result to daily log + MEMORY.md.
 */
class DailyLogWriter(
    private val messageRepository: MessageRepository,
    private val sessionRepository: SessionRepository,
    private val agentRepository: AgentRepository,
    private val providerRepository: ProviderRepository,
    private val apiKeyStorage: ApiKeyStorage,
    private val adapterFactory: ModelApiAdapterFactory,
    private val memoryFileStorage: MemoryFileStorage,
    private val longTermMemoryManager: LongTermMemoryManager,
    private val memoryIndexDao: MemoryIndexDao,
    private val embeddingEngine: EmbeddingEngine
) {
    companion object {
        private const val TAG = "DailyLogWriter"

        private val SUMMARIZATION_SYSTEM_PROMPT = """
            You are a memory extraction assistant. Your job is to summarize conversations
            and extract important long-term facts. Be concise and factual.
            Format your response exactly as requested with the two sections:
            "## Daily Summary" and "## Long-term Facts".
        """.trimIndent()
    }

    /**
     * Extract and summarize unprocessed messages from a session into the daily log.
     *
     * Flow:
     * 1. Load the session and its lastLoggedMessageId
     * 2. Fetch messages after lastLoggedMessageId
     * 3. If no new messages, return early
     * 4. Build a summarization prompt with the new messages
     * 5. Call the AI model (non-streaming) for summarization
     * 6. Parse the response into: daily_summary + long_term_facts
     * 7. Append daily_summary to today's daily log file
     * 8. Append long_term_facts to MEMORY.md (if any)
     * 9. Index the new chunks (embed + store in Room)
     * 10. Update the session's lastLoggedMessageId
     */
    suspend fun writeDailyLog(sessionId: String): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val session = sessionRepository.getSessionById(sessionId)
                ?: return@withContext Result.failure(Exception("Session not found: $sessionId"))

            // Fetch all messages in the session
            val allMessages = messageRepository.getMessagesSnapshot(sessionId)

            // Determine new messages since last log
            val newMessages = if (session.lastLoggedMessageId != null) {
                val lastIndex = allMessages.indexOfFirst { it.id == session.lastLoggedMessageId }
                if (lastIndex < 0) allMessages else allMessages.drop(lastIndex + 1)
            } else {
                allMessages
            }

            if (newMessages.isEmpty()) {
                return@withContext Result.success(Unit)
            }

            // Filter to meaningful messages (USER and AI_RESPONSE only)
            val meaningfulMessages = newMessages.filter {
                it.type == MessageType.USER || it.type == MessageType.AI_RESPONSE
            }

            if (meaningfulMessages.isEmpty()) {
                // Update pointer even if no meaningful messages
                sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)
                return@withContext Result.success(Unit)
            }

            // Resolve model for summarization
            val agent = agentRepository.getAgentById(session.currentAgentId)
                ?: return@withContext Result.failure(Exception("Agent not found"))
            val modelProviderPair = resolveModel(agent)
                ?: return@withContext Result.failure(Exception("No model available for summarization"))
            val (model, provider) = modelProviderPair
            val apiKey = apiKeyStorage.getApiKey(provider.id)
                ?: return@withContext Result.failure(Exception("No API key for provider ${provider.id}"))

            // Build summarization prompt
            val conversationText = meaningfulMessages.joinToString("\n") { msg ->
                val role = if (msg.type == MessageType.USER) "User" else "Assistant"
                "$role: ${msg.content}"
            }
            val prompt = buildSummarizationPrompt(conversationText)

            // Call model for summarization
            val adapter = adapterFactory.getAdapter(provider.type)
            val summaryResult = adapter.generateSimpleCompletion(
                apiBaseUrl = provider.apiBaseUrl,
                apiKey = apiKey,
                modelId = model.id,
                prompt = "$SUMMARIZATION_SYSTEM_PROMPT\n\n$prompt",
                maxTokens = 1000
            )

            val responseText = when (summaryResult) {
                is AppResult.Success -> summaryResult.data
                is AppResult.Error -> {
                    Log.w(TAG, "Summarization API call failed for session $sessionId: ${summaryResult.message}")
                    // Still update the pointer so we don't retry the same messages
                    sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)
                    return@withContext Result.failure(
                        summaryResult.exception ?: RuntimeException(summaryResult.message)
                    )
                }
            }

            // Parse response
            val (dailySummary, longTermFacts) = parseSummarizationResponse(responseText)

            // Write daily log
            val today = LocalDate.now().toString() // "2026-02-28"
            if (dailySummary.isNotBlank()) {
                memoryFileStorage.appendToDailyLog(today, dailySummary)
                indexChunks(dailySummary, "daily_log", today)
            }

            // Promote long-term facts
            if (longTermFacts.isNotBlank()) {
                longTermMemoryManager.appendMemory(longTermFacts)
                indexChunks(longTermFacts, "long_term", null)
            }

            // Update lastLoggedMessageId
            sessionRepository.updateLastLoggedMessageId(sessionId, newMessages.last().id)

            Log.d(TAG, "Daily log written for session $sessionId (${meaningfulMessages.size} messages processed)")
            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write daily log for session $sessionId", e)
            Result.failure(e)
        }
    }

    private suspend fun indexChunks(text: String, sourceType: String, sourceDate: String?) {
        // Split text into paragraphs as chunks
        val chunks = text.split(Regex("\n{2,}")).filter { it.isNotBlank() }
        val now = System.currentTimeMillis()

        val entities = chunks.map { chunkText ->
            val embedding = embeddingEngine.embed(chunkText)
            MemoryIndexEntity(
                id = UUID.randomUUID().toString(),
                sourceType = sourceType,
                sourceDate = sourceDate,
                chunkText = chunkText.trim(),
                embedding = embedding?.let { EmbeddingSerializer.toByteArray(it) },
                createdAt = now,
                updatedAt = now
            )
        }

        if (entities.isNotEmpty()) {
            memoryIndexDao.insertAll(entities)
        }
    }

    private fun buildSummarizationPrompt(conversationText: String): String {
        return """
            |Summarize the following conversation. Provide two sections:
            |
            |## Daily Summary
            |A concise summary of key topics discussed, decisions made, tasks completed,
            |and any notable information. Use bullet points.
            |
            |## Long-term Facts
            |Extract any stable facts, user preferences, or important knowledge that should
            |be remembered permanently. If none, write "None".
            |
            |---
            |Conversation:
            |$conversationText
        """.trimMargin()
    }

    internal fun parseSummarizationResponse(
        response: String
    ): Pair<String, String> {
        val dailySummaryRegex = Regex(
            "## Daily Summary\\s*\\n([\\s\\S]*?)(?=## Long-term Facts|$)",
            RegexOption.IGNORE_CASE
        )
        val longTermRegex = Regex(
            "## Long-term Facts\\s*\\n([\\s\\S]*?)$",
            RegexOption.IGNORE_CASE
        )

        val dailySummary = dailySummaryRegex.find(response)?.groupValues?.get(1)?.trim() ?: response.trim()
        val longTermRaw = longTermRegex.find(response)?.groupValues?.get(1)?.trim() ?: ""
        val longTermFacts = if (longTermRaw.equals("None", ignoreCase = true) ||
            longTermRaw.equals("None.", ignoreCase = true) ||
            longTermRaw.isBlank()
        ) "" else longTermRaw

        return dailySummary to longTermFacts
    }

    private suspend fun resolveModel(agent: com.oneclaw.shadow.core.model.Agent): Pair<AiModel, Provider>? {
        // Try agent's preferred model
        if (agent.preferredModelId != null && agent.preferredProviderId != null) {
            val provider = providerRepository.getProviderById(agent.preferredProviderId)
            if (provider != null && provider.isActive) {
                val models = providerRepository.getModelsForProvider(provider.id)
                val model = models.find { it.id == agent.preferredModelId }
                if (model != null) return model to provider
            }
        }
        // Fall back to global default
        val defaultModel = providerRepository.getGlobalDefaultModel().first() ?: return null
        val provider = providerRepository.getProviderById(defaultModel.providerId) ?: return null
        if (!provider.isActive) return null
        return defaultModel to provider
    }
}
