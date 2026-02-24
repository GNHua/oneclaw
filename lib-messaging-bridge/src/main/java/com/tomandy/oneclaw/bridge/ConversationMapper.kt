package com.tomandy.oneclaw.bridge

/**
 * Resolves the OneClaw conversation ID for bridge messages.
 *
 * The bridge maintains its own active conversation ID in BridgePreferences,
 * separate from the UI's active conversation in ConversationPreferences.
 * This prevents ChatViewModel from overwriting the bridge's conversation.
 */
class ConversationMapper(
    private val preferences: BridgePreferences,
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(): String {
        val bridgeConvId = preferences.getBridgeConversationId()
        if (bridgeConvId != null && conversationManager.conversationExists(bridgeConvId)) {
            return bridgeConvId
        }
        return createNewConversation()
    }

    suspend fun createNewConversation(): String {
        val newId = conversationManager.createNewConversation()
        preferences.setBridgeConversationId(newId)
        return newId
    }
}
