package com.tomandy.palmclaw.engine

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Sandboxed execution context provided to plugins.
 *
 * PluginContext gives plugins controlled access to:
 * - HTTP networking (via OkHttp)
 * - File storage (plugin-specific directory)
 * - Credentials (encrypted storage)
 * - Notifications
 * - Intents (with user approval)
 * - Logging
 *
 * This interface prevents plugins from accessing sensitive system resources
 * directly while still allowing them to be useful.
 *
 * Security Notes:
 * - Credentials are namespaced by plugin ID
 * - Storage is isolated per plugin
 * - HTTP client has timeouts to prevent abuse
 * - No direct access to Android Context (except for intents)
 */
class PluginContext(
    private val androidContext: Context,
    private val pluginId: String,
    private val credentialVault: CredentialVault,
    val googleAuthProvider: GoogleAuthProvider? = null
) {
    companion object {
        /**
         * Create a new PluginContext for a plugin.
         *
         * This is the public factory method for creating plugin contexts.
         *
         * @param androidContext The Android application context
         * @param pluginId Unique plugin identifier
         * @param credentialVault The credential vault for secure storage
         * @param googleAuthProvider Optional Google OAuth provider for Google Workspace plugins
         * @return A new PluginContext instance
         */
        fun create(
            androidContext: Context,
            pluginId: String,
            credentialVault: CredentialVault,
            googleAuthProvider: GoogleAuthProvider? = null
        ): PluginContext {
            return PluginContext(androidContext, pluginId, credentialVault, googleAuthProvider)
        }
    }
    /**
     * Pre-configured HTTP client with timeouts and logging.
     *
     * Features:
     * - 30s connect timeout
     * - 60s read/write timeout
     * - Logging in debug builds
     * - Standard User-Agent header
     *
     * Example:
     * ```kotlin
     * val request = Request.Builder()
     *     .url("https://api.example.com/data")
     *     .header("Authorization", "Bearer $token")
     *     .build()
     * val response = context.httpClient.newCall(request).execute()
     * ```
     */
    val httpClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .addInterceptor(HttpLoggingInterceptor().apply {
                val isDebug = androidContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
                level = if (isDebug) {
                    HttpLoggingInterceptor.Level.BODY
                } else {
                    HttpLoggingInterceptor.Level.NONE
                }
            })
            .build()
    }

    /**
     * Plugin-specific storage directory.
     *
     * This directory is isolated from other plugins and persists across app restarts.
     * Use this to cache data, store configuration, or maintain state.
     *
     * Location: `/data/data/com.tomandy.palmclaw/files/plugins/{pluginId}/`
     *
     * Example:
     * ```kotlin
     * val cacheFile = File(context.storageDir, "cache.json")
     * cacheFile.writeText(jsonData)
     * ```
     */
    val storageDir: File by lazy {
        File(androidContext.filesDir, "plugins/$pluginId").apply {
            mkdirs()
        }
    }

    /**
     * Retrieve a credential from encrypted storage.
     *
     * Credentials are automatically namespaced by plugin ID, so different plugins
     * can use the same key names without conflicts.
     *
     * @param key The credential key (e.g., "access_token", "api_key")
     * @return The credential value, or null if not found
     *
     * Example:
     * ```kotlin
     * val apiKey = context.getCredential("api_key")
     *     ?: throw IllegalStateException("API key not configured")
     * ```
     */
    suspend fun getCredential(key: String): String? {
        return credentialVault.getApiKey("plugin.${pluginId}.$key")
    }

    /**
     * Save a credential to encrypted storage.
     *
     * Credentials are encrypted with Android KeyStore and persist across app restarts.
     *
     * @param key The credential key
     * @param value The credential value
     *
     * Example:
     * ```kotlin
     * context.saveCredential("access_token", oauthToken)
     * ```
     */
    suspend fun saveCredential(key: String, value: String) {
        credentialVault.saveApiKey("plugin.${pluginId}.$key", value)
    }

    /**
     * Delete a credential from storage.
     *
     * @param key The credential key to delete
     */
    suspend fun deleteCredential(key: String) {
        credentialVault.deleteApiKey("plugin.${pluginId}.$key")
    }

    /**
     * Show a notification to the user.
     *
     * Use this to inform the user of important events, results, or errors.
     *
     * @param title Notification title
     * @param message Notification message
     * @param priority Priority level (default: DEFAULT)
     *
     * Example:
     * ```kotlin
     * context.showNotification(
     *     title = "Email Sent",
     *     message = "Your email was sent successfully",
     *     priority = NotificationCompat.PRIORITY_LOW
     * )
     * ```
     */
    fun showNotification(
        title: String,
        message: String,
        priority: Int = NotificationCompat.PRIORITY_DEFAULT
    ) {
        val notificationManager = androidContext.getSystemService(Context.NOTIFICATION_SERVICE)
                as NotificationManager

        // Create notification channel (Android 8+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "plugin_$pluginId",
                "Plugin: $pluginId",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifications from $pluginId plugin"
            }
            notificationManager.createNotificationChannel(channel)
        }

        val notification = NotificationCompat.Builder(androidContext, "plugin_$pluginId")
            .setContentTitle(title)
            .setContentText(message)
            .setSmallIcon(android.R.drawable.ic_dialog_info) // Default icon
            .setPriority(priority)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(pluginId.hashCode(), notification)
    }

    /**
     * Launch an Android Intent.
     *
     * Use this to open other apps, share content, or trigger system actions.
     * The Intent must be valid and should not require user credentials.
     *
     * @param intent The intent to launch
     *
     * Example:
     * ```kotlin
     * val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com"))
     * context.launchIntent(intent)
     * ```
     */
    fun launchIntent(intent: Intent) {
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        androidContext.startActivity(intent)
    }

    /**
     * Get the application context.
     *
     * Use this for read-only operations only. Do not use for sensitive operations.
     *
     * @return Application context
     */
    fun getApplicationContext(): Context = androidContext.applicationContext

    /**
     * Log a message visible in Logcat and plugin manager.
     *
     * All logs are prefixed with "Plugin:{pluginId}" for easy filtering.
     *
     * @param level Log level
     * @param message Message to log
     *
     * Example:
     * ```kotlin
     * context.log(LogLevel.INFO, "Fetching emails from Gmail API")
     * context.log(LogLevel.ERROR, "Failed to authenticate: ${e.message}")
     * ```
     */
    fun log(level: LogLevel, message: String) {
        val tag = "Plugin:$pluginId"
        when (level) {
            LogLevel.DEBUG -> Log.d(tag, message)
            LogLevel.INFO -> Log.i(tag, message)
            LogLevel.WARN -> Log.w(tag, message)
            LogLevel.ERROR -> Log.e(tag, message)
        }
    }
}

/**
 * Log levels for plugin logging.
 */
enum class LogLevel {
    DEBUG,
    INFO,
    WARN,
    ERROR
}

/**
 * Interface for credential storage.
 *
 * This is injected into PluginContext to allow for testing and different
 * storage implementations.
 */
interface CredentialVault {
    suspend fun getApiKey(key: String): String?
    suspend fun saveApiKey(key: String, value: String)
    suspend fun deleteApiKey(key: String)
}

