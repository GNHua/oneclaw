package com.tomandy.oneclaw.bridge

/**
 * Interface for creating conversations and inserting user messages.
 * The app module implements this via ConversationDao + MessageDao.
 */
interface BridgeConversationManager {
    suspend fun createNewConversation(): String
    suspend fun createConversation(conversationId: String, title: String)
    suspend fun conversationExists(conversationId: String): Boolean
    suspend fun insertUserMessage(
        conversationId: String,
        content: String,
        imagePaths: List<String> = emptyList()
    )
    suspend fun updateConversationTimestamp(conversationId: String)
}
