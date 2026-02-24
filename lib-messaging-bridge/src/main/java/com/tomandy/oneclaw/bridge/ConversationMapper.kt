package com.tomandy.oneclaw.bridge

import java.util.UUID

class ConversationMapper(
    private val preferences: BridgePreferences,
    private val conversationManager: BridgeConversationManager
) {
    suspend fun resolveConversationId(
        channelType: ChannelType,
        externalChatId: String,
        senderName: String?
    ): String {
        val key = "${channelType.name}:$externalChatId"
        val existing = preferences.getMappedConversationId(key)
        if (existing != null && conversationManager.conversationExists(existing)) {
            return existing
        }

        val conversationId = UUID.randomUUID().toString()
        val title = when (channelType) {
            ChannelType.TELEGRAM -> "Telegram: ${senderName ?: externalChatId}"
            ChannelType.DISCORD -> "Discord: ${senderName ?: externalChatId}"
            ChannelType.WEBCHAT -> "WebChat: ${senderName ?: "Session"}"
        }
        conversationManager.createConversation(conversationId, title)
        preferences.setMappedConversationId(key, conversationId)
        return conversationId
    }
}
