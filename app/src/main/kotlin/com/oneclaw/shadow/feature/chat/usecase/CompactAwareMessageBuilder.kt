package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.data.remote.adapter.ApiMessage

object CompactAwareMessageBuilder {

    fun build(
        session: Session,
        allMessages: List<Message>,
        originalSystemPrompt: String?
    ): Pair<String?, List<ApiMessage>> {
        val summary = session.compactedSummary
        val boundary = session.compactBoundaryTimestamp

        if (summary == null || boundary == null) {
            return Pair(originalSystemPrompt, allMessages.toApiMessages())
        }

        val recentMessages = allMessages.filter { it.createdAt >= boundary }
        val apiMessages = recentMessages.toApiMessages()

        val summaryPrefix = "Previous conversation summary:\n$summary\n\n---\n\n"
        val enhancedPrompt = if (originalSystemPrompt != null) {
            summaryPrefix + originalSystemPrompt
        } else {
            summaryPrefix + "Continue the conversation based on the summary above."
        }

        return Pair(enhancedPrompt, apiMessages)
    }
}
