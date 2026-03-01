package com.oneclaw.shadow.bridge

interface BridgeConversationManager {
    fun getActiveConversationId(): String?
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
