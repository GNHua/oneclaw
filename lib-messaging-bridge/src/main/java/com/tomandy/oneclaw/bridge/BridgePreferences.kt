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

    // -- Conversation mapping --

    fun getMappedConversationId(externalKey: String): String? =
        prefs.getString("conv_map:$externalKey", null)

    fun setMappedConversationId(externalKey: String, conversationId: String) =
        prefs.edit().putString("conv_map:$externalKey", conversationId).apply()

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
        private const val KEY_WEBCHAT_ENABLED = "webchat_enabled"
        private const val KEY_WEBCHAT_PORT = "webchat_port"
    }
}
