package com.tomandy.oneclaw.data

import android.content.Context

class ConversationPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("conversation_prefs", Context.MODE_PRIVATE)

    fun getActiveConversationId(): String? = prefs.getString("active_conversation_id", null)

    fun setActiveConversationId(id: String) {
        prefs.edit().putString("active_conversation_id", id).apply()
    }

    fun clearActiveConversationId() {
        prefs.edit().remove("active_conversation_id").apply()
    }
}
