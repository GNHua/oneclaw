package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgePreferences

class ConversationMapper(
    private val preferences: BridgePreferences,
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(): String {
        val storedId = preferences.getBridgeConversationId()
        if (storedId != null && conversationManager.conversationExists(storedId)) {
            return storedId
        }
        return createNewConversation()
    }

    suspend fun createNewConversation(): String {
        val newId = conversationManager.createNewConversation()
        preferences.setBridgeConversationId(newId)
        return newId
    }
}
