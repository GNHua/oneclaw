package com.tomandy.oneclaw.bridge

import android.content.Context
import android.content.SharedPreferences

class BridgePreferences(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("messaging_bridge", Context.MODE_PRIVATE)

    // -- Master toggle --

    fun isBridgeEnabled(): Boolean = prefs.getBoolean(KEY_BRIDGE_ENABLED, false)
    fun setBridgeEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_BRIDGE_ENABLED, enabled).apply()

    // -- Telegram --

    fun isTelegramEnabled(): Boolean = prefs.getBoolean(KEY_TELEGRAM_ENABLED, false)
    fun setTelegramEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_TELEGRAM_ENABLED, enabled).apply()

    fun getAllowedTelegramUserIds(): Set<String> =
        prefs.getStringSet(KEY_TELEGRAM_ALLOWED_USERS, emptySet()) ?: emptySet()

    fun setAllowedTelegramUserIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_TELEGRAM_ALLOWED_USERS, ids).apply()

    // -- Discord --

    fun isDiscordEnabled(): Boolean = prefs.getBoolean(KEY_DISCORD_ENABLED, false)
    fun setDiscordEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_DISCORD_ENABLED, enabled).apply()

    fun getAllowedDiscordUserIds(): Set<String> =
        prefs.getStringSet(KEY_DISCORD_ALLOWED_USERS, emptySet()) ?: emptySet()

    fun setAllowedDiscordUserIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_DISCORD_ALLOWED_USERS, ids).apply()

    // -- WebChat --

    fun isWebChatEnabled(): Boolean = prefs.getBoolean(KEY_WEBCHAT_ENABLED, false)
    fun setWebChatEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_WEBCHAT_ENABLED, enabled).apply()

    fun getWebChatPort(): Int = prefs.getInt(KEY_WEBCHAT_PORT, 8080)
    fun setWebChatPort(port: Int) = prefs.edit().putInt(KEY_WEBCHAT_PORT, port).apply()

    // -- Slack --

    fun isSlackEnabled(): Boolean = prefs.getBoolean(KEY_SLACK_ENABLED, false)
    fun setSlackEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_SLACK_ENABLED, enabled).apply()

    fun getAllowedSlackUserIds(): Set<String> =
        prefs.getStringSet(KEY_SLACK_ALLOWED_USERS, emptySet()) ?: emptySet()

    fun setAllowedSlackUserIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_SLACK_ALLOWED_USERS, ids).apply()

    // -- Matrix --

    fun isMatrixEnabled(): Boolean = prefs.getBoolean(KEY_MATRIX_ENABLED, false)
    fun setMatrixEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_MATRIX_ENABLED, enabled).apply()

    fun getMatrixHomeserver(): String = prefs.getString(KEY_MATRIX_HOMESERVER, "") ?: ""
    fun setMatrixHomeserver(url: String) = prefs.edit().putString(KEY_MATRIX_HOMESERVER, url).apply()

    fun getAllowedMatrixUserIds(): Set<String> =
        prefs.getStringSet(KEY_MATRIX_ALLOWED_USERS, emptySet()) ?: emptySet()

    fun setAllowedMatrixUserIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_MATRIX_ALLOWED_USERS, ids).apply()

    // -- LINE --

    fun isLineEnabled(): Boolean = prefs.getBoolean(KEY_LINE_ENABLED, false)
    fun setLineEnabled(enabled: Boolean) = prefs.edit().putBoolean(KEY_LINE_ENABLED, enabled).apply()

    fun getAllowedLineUserIds(): Set<String> =
        prefs.getStringSet(KEY_LINE_ALLOWED_USERS, emptySet()) ?: emptySet()

    fun setAllowedLineUserIds(ids: Set<String>) =
        prefs.edit().putStringSet(KEY_LINE_ALLOWED_USERS, ids).apply()

    // -- Conversation mapping --

    fun getMappedConversationId(externalKey: String): String? =
        prefs.getString("conv_map:$externalKey", null)

    fun setMappedConversationId(externalKey: String, conversationId: String) =
        prefs.edit().putString("conv_map:$externalKey", conversationId).apply()

    // -- Bridge active conversation (separate from the UI's active conversation) --

    fun getBridgeConversationId(): String? =
        prefs.getString(KEY_BRIDGE_CONVERSATION_ID, null)

    fun setBridgeConversationId(conversationId: String) =
        prefs.edit().putString(KEY_BRIDGE_CONVERSATION_ID, conversationId).apply()

    fun clearBridgeConversationId() =
        prefs.edit().remove(KEY_BRIDGE_CONVERSATION_ID).apply()

    // -- Last chat ID per channel (for broadcast) --

    fun getLastChatId(channelType: ChannelType): String? =
        prefs.getString("last_chat_id:${channelType.name}", null)

    fun setLastChatId(channelType: ChannelType, chatId: String) =
        prefs.edit().putString("last_chat_id:${channelType.name}", chatId).apply()

    // -- Telegram polling offset --

    fun getTelegramUpdateOffset(): Long = prefs.getLong(KEY_TELEGRAM_OFFSET, 0)
    fun setTelegramUpdateOffset(offset: Long) = prefs.edit().putLong(KEY_TELEGRAM_OFFSET, offset).apply()

    companion object {
        private const val KEY_BRIDGE_ENABLED = "bridge_enabled"
        private const val KEY_TELEGRAM_ENABLED = "telegram_enabled"
        private const val KEY_TELEGRAM_ALLOWED_USERS = "telegram_allowed_users"
        private const val KEY_TELEGRAM_OFFSET = "telegram_update_offset"
        private const val KEY_DISCORD_ENABLED = "discord_enabled"
        private const val KEY_DISCORD_ALLOWED_USERS = "discord_allowed_users"
        private const val KEY_BRIDGE_CONVERSATION_ID = "bridge_conversation_id"
        private const val KEY_WEBCHAT_ENABLED = "webchat_enabled"
        private const val KEY_WEBCHAT_PORT = "webchat_port"
        private const val KEY_SLACK_ENABLED = "slack_enabled"
        private const val KEY_SLACK_ALLOWED_USERS = "slack_allowed_users"
        private const val KEY_MATRIX_ENABLED = "matrix_enabled"
        private const val KEY_MATRIX_HOMESERVER = "matrix_homeserver"
        private const val KEY_MATRIX_ALLOWED_USERS = "matrix_allowed_users"
        private const val KEY_LINE_ENABLED = "line_enabled"
        private const val KEY_LINE_ALLOWED_USERS = "line_allowed_users"
    }
}
