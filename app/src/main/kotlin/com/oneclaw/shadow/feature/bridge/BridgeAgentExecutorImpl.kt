package com.oneclaw.shadow.feature.bridge

import android.util.Log
import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.core.model.AttachmentType
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.data.local.AttachmentFileManager
import com.oneclaw.shadow.feature.chat.ChatEvent
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import kotlinx.coroutines.CancellationException
import java.io.File
import java.util.UUID

class BridgeAgentExecutorImpl(
    private val sendMessageUseCase: SendMessageUseCase,
    private val agentRepository: AgentRepository,
    private val sessionRepository: SessionRepository,
    private val generateTitleUseCase: GenerateTitleUseCase
) : BridgeAgentExecutor {

    override suspend fun executeMessage(
        conversationId: String,
        userMessage: String,
        imagePaths: List<String>
    ): BridgeMessage? {
        // Check before executing: is this the first message in the session?
        val isFirstMessage = (sessionRepository.getSessionById(conversationId)?.messageCount ?: 0) == 0

        // Phase 1 title: immediate truncated title from the user message
        if (isFirstMessage) {
            val truncatedTitle = generateTitleUseCase.generateTruncatedTitle(userMessage)
            sessionRepository.updateTitle(conversationId, truncatedTitle)
        }

        val agentId = resolveAgentId()
        val pendingAttachments = imagePaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            AttachmentFileManager.PendingAttachment(
                id = UUID.randomUUID().toString(),
                type = AttachmentType.IMAGE,
                fileName = file.name,
                mimeType = mimeTypeFromExtension(file.extension),
                fileSize = file.length(),
                filePath = path,
                thumbnailPath = null,
                width = null,
                height = null,
                durationMs = null
            )
        }
        var lastResponseContent: String? = null
        var lastResponseTimestamp: Long = 0L
        var lastModelId: String? = null
        var lastProviderId: String? = null

        try {
            sendMessageUseCase.execute(
                sessionId = conversationId,
                userText = userMessage,
                agentId = agentId,
                pendingAttachments = pendingAttachments
            ).collect { event ->
                when (event) {
                    is ChatEvent.ResponseComplete -> {
                        lastResponseContent = event.message.content
                        lastResponseTimestamp = event.message.createdAt
                        lastModelId = event.message.modelId
                        lastProviderId = event.message.providerId
                    }
                    else -> { /* other events not needed by bridge */ }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Agent execution failed; return null so caller can fall back
            return null
        }

        val content = lastResponseContent

        // Phase 2 title: AI-generated title after first response (non-fatal)
        if (isFirstMessage && content != null && lastModelId != null && lastProviderId != null) {
            try {
                generateTitleUseCase.generateAiTitle(
                    sessionId = conversationId,
                    firstUserMessage = userMessage,
                    firstAiResponse = content,
                    currentModelId = lastModelId!!,
                    currentProviderId = lastProviderId!!
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "AI title generation failed (non-fatal)", e)
            }
        }

        return if (content != null && content.isNotBlank()) {
            BridgeMessage(content = content, timestamp = lastResponseTimestamp)
        } else {
            null
        }
    }

    companion object {
        private const val TAG = "BridgeAgentExecutor"
    }

    private fun mimeTypeFromExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png"         -> "image/png"
        "gif"         -> "image/gif"
        "webp"        -> "image/webp"
        "bmp"         -> "image/bmp"
        else          -> "image/jpeg"
    }

    private suspend fun resolveAgentId(): String {
        val builtIn = agentRepository.getBuiltInAgents()
        return builtIn.firstOrNull()?.id
            ?: agentRepository.getAllAgents()
                .let { flow ->
                    var result: String? = null
                    flow.collect { agents -> result = agents.firstOrNull()?.id }
                    result
                }
            ?: "default"
    }
}
