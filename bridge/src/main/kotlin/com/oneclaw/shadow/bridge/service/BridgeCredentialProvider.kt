package com.oneclaw.shadow.bridge.service

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys

class BridgeCredentialProvider(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    private fun createPrefs(context: Context): SharedPreferences {
        return try {
            val masterKeyAlias = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            EncryptedSharedPreferences.create(
                "oneclaw_bridge_credentials",
                masterKeyAlias,
                context,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences unavailable, falling back to plain prefs: ${e.message}")
            context.getSharedPreferences("oneclaw_bridge_credentials_plain", Context.MODE_PRIVATE)
        }
    }

    // Telegram
    fun getTelegramBotToken(): String? = prefs.getString(KEY_TELEGRAM_TOKEN, null)
    fun saveTelegramBotToken(token: String) = prefs.edit().putString(KEY_TELEGRAM_TOKEN, token).apply()

    // Discord
    fun getDiscordBotToken(): String? = prefs.getString(KEY_DISCORD_TOKEN, null)
    fun saveDiscordBotToken(token: String) = prefs.edit().putString(KEY_DISCORD_TOKEN, token).apply()

    // Slack
    fun getSlackBotToken(): String? = prefs.getString(KEY_SLACK_BOT_TOKEN, null)
    fun saveSlackBotToken(token: String) = prefs.edit().putString(KEY_SLACK_BOT_TOKEN, token).apply()
    fun getSlackAppToken(): String? = prefs.getString(KEY_SLACK_APP_TOKEN, null)
    fun saveSlackAppToken(token: String) = prefs.edit().putString(KEY_SLACK_APP_TOKEN, token).apply()

    // Matrix
    fun getMatrixAccessToken(): String? = prefs.getString(KEY_MATRIX_TOKEN, null)
    fun saveMatrixAccessToken(token: String) = prefs.edit().putString(KEY_MATRIX_TOKEN, token).apply()

    // LINE
    fun getLineChannelAccessToken(): String? = prefs.getString(KEY_LINE_ACCESS_TOKEN, null)
    fun saveLineChannelAccessToken(token: String) = prefs.edit().putString(KEY_LINE_ACCESS_TOKEN, token).apply()
    fun getLineChannelSecret(): String? = prefs.getString(KEY_LINE_CHANNEL_SECRET, null)
    fun saveLineChannelSecret(secret: String) = prefs.edit().putString(KEY_LINE_CHANNEL_SECRET, secret).apply()

    // WebChat
    fun getWebChatAccessToken(): String? = prefs.getString(KEY_WEBCHAT_TOKEN, null)
    fun saveWebChatAccessToken(token: String) = prefs.edit().putString(KEY_WEBCHAT_TOKEN, token).apply()

    companion object {
        private const val TAG = "BridgeCredentialProvider"
        private const val KEY_TELEGRAM_TOKEN = "telegram_bot_token"
        private const val KEY_DISCORD_TOKEN = "discord_bot_token"
        private const val KEY_SLACK_BOT_TOKEN = "slack_bot_token"
        private const val KEY_SLACK_APP_TOKEN = "slack_app_token"
        private const val KEY_MATRIX_TOKEN = "matrix_access_token"
        private const val KEY_LINE_ACCESS_TOKEN = "line_channel_access_token"
        private const val KEY_LINE_CHANNEL_SECRET = "line_channel_secret"
        private const val KEY_WEBCHAT_TOKEN = "webchat_access_token"
    }
}
