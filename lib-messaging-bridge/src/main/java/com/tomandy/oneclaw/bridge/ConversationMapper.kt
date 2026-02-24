package com.tomandy.oneclaw.bridge

/**
 * Resolves the OneClaw conversation ID for bridge messages.
 * Uses the active conversation (same one visible in the native UI).
 * Creates a new conversation only if no active one exists.
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
