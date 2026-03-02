package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeConversationManager

class ConversationMapper(
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(): String {
        val activeId = conversationManager.getActiveConversationId()
        if (activeId != null && conversationManager.conversationExists(activeId)) {
            return activeId
        }
        return createNewConversation()
    }

    suspend fun createNewConversation(): String {
        return conversationManager.createNewConversation()
    }
}
