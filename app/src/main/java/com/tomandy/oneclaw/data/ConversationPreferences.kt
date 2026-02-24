package com.tomandy.oneclaw.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class ConversationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_prefs", Context.MODE_PRIVATE)

    private val _activeConversationIdFlow = MutableStateFlow(
        prefs.getString("active_conversation_id", null)
    )
    val activeConversationIdFlow: StateFlow<String?> = _activeConversationIdFlow.asStateFlow()

    fun getActiveConversationId(): String? = prefs.getString("active_conversation_id", null)

    fun setActiveConversationId(id: String) {
        prefs.edit().putString("active_conversation_id", id).apply()
        _activeConversationIdFlow.value = id
    }

    fun clearActiveConversationId() {
        prefs.edit().remove("active_conversation_id").apply()
        _activeConversationIdFlow.value = null
    }
}
