package com.tomandy.oneclaw.bridge

/**
 * Resolves the OneClaw conversation ID for bridge messages.
 * Uses the same active conversation as the UI (shared via ConversationPreferences).
 */
class ConversationMapper(
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(): String {
        val activeId = conversationManager.getActiveConversationId()
        if (activeId != null && conversationManager.conversationExists(activeId)) {
            return activeId
        }
        return conversationManager.createNewConversation()
    }

    suspend fun createNewConversation(): String {
        return conversationManager.createNewConversation()
    }
}
