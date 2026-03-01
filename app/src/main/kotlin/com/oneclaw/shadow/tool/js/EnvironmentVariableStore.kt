package com.oneclaw.shadow.tool.js

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Stores encrypted key-value pairs for JS tool environment variables.
 * Uses EncryptedSharedPreferences, same mechanism as API key storage.
 */
class EnvironmentVariableStore(context: Context) {

    companion object {
        private const val PREFS_NAME = "js_tool_env_vars"
    }

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        PREFS_NAME,
        MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build(),
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    fun getAll(): Map<String, String> {
        return prefs.all.mapValues { it.value.toString() }
    }

    fun get(key: String): String? {
        return prefs.getString(key, null)
    }

    fun set(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }

    fun delete(key: String) {
        prefs.edit().remove(key).apply()
    }

    fun getKeys(): List<String> {
        return prefs.all.keys.toList().sorted()
    }
}
