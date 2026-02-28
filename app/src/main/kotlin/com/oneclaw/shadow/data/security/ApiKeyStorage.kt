package com.oneclaw.shadow.data.security

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oneclaw.shadow.BuildConfig

class ApiKeyStorage(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs: SharedPreferences = EncryptedSharedPreferences.create(
        context,
        "oneclaw_api_keys",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    // In DEBUG builds, a plain SharedPreferences file is used as a fallback so that
    // instrumented tests can inject API keys without requiring EncryptedSharedPreferences
    // cross-process access.
    private val debugPrefs: SharedPreferences? = if (BuildConfig.DEBUG) {
        context.getSharedPreferences("oneclaw_api_keys_debug", Context.MODE_PRIVATE)
    } else {
        null
    }

    fun getApiKey(providerId: String): String? {
        // In DEBUG builds, prefer the plain prefs fallback written by SetupDataInjector
        val debugKey = debugPrefs?.getString("api_key_$providerId", null)
        if (debugKey != null) return debugKey
        return prefs.getString("api_key_$providerId", null)
    }

    fun setApiKey(providerId: String, apiKey: String) {
        prefs.edit().putString("api_key_$providerId", apiKey).apply()
    }

    fun deleteApiKey(providerId: String) {
        prefs.edit().remove("api_key_$providerId").apply()
        debugPrefs?.edit()?.remove("api_key_$providerId")?.apply()
    }

    fun hasApiKey(providerId: String): Boolean {
        if (debugPrefs?.contains("api_key_$providerId") == true) return true
        return prefs.contains("api_key_$providerId")
    }
}
