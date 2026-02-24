package com.tomandy.oneclaw.bridge.service

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

/**
 * Reads bridge-specific credentials from encrypted storage.
 * Credentials are saved by the app module's settings UI.
 */
class BridgeCredentialProvider(context: Context) {

    private val prefs = try {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    } catch (e: Exception) {
        // Fallback to regular prefs if encryption fails (rare edge case)
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun getTelegramBotToken(): String? = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, null)
    fun saveTelegramBotToken(token: String) = prefs.edit().putString(KEY_TELEGRAM_BOT_TOKEN, token).apply()

    fun getDiscordBotToken(): String? = prefs.getString(KEY_DISCORD_BOT_TOKEN, null)
    fun saveDiscordBotToken(token: String) = prefs.edit().putString(KEY_DISCORD_BOT_TOKEN, token).apply()

    fun getWebChatAccessToken(): String? = prefs.getString(KEY_WEBCHAT_ACCESS_TOKEN, null)
    fun saveWebChatAccessToken(token: String) = prefs.edit().putString(KEY_WEBCHAT_ACCESS_TOKEN, token).apply()

    companion object {
        private const val PREFS_NAME = "bridge_credentials"
        private const val KEY_TELEGRAM_BOT_TOKEN = "telegram_bot_token"
        private const val KEY_DISCORD_BOT_TOKEN = "discord_bot_token"
        private const val KEY_WEBCHAT_ACCESS_TOKEN = "webchat_access_token"
    }
}
