package com.tomandy.oneclaw.security

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.KeyStore

/**
 * Implementation of CredentialVault using Android KeyStore and EncryptedSharedPreferences.
 * All operations are performed on Dispatchers.IO for thread safety.
 */
class CredentialVaultImpl(context: Context) : CredentialVault {

    companion object {
        private const val TAG = "CredentialVaultImpl"
        private const val PREFS_FILE_NAME = "oneclaw_credentials"
        private const val API_KEY_PREFIX = "api_key_"
        private const val MASTER_KEY_ALIAS = "_androidx_security_master_key_"
    }

    private val appContext = context.applicationContext

    private val prefs: SharedPreferences by lazy {
        try {
            createEncryptedPrefs()
        } catch (e: Exception) {
            Log.w(TAG, "EncryptedSharedPreferences corrupted, resetting", e)
            clearCorruptedPrefs()
            createEncryptedPrefs()
        }
    }

    private fun createEncryptedPrefs(): SharedPreferences {
        val masterKey = MasterKey.Builder(appContext)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        return EncryptedSharedPreferences.create(
            appContext,
            PREFS_FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private fun clearCorruptedPrefs() {
        // Delete the corrupted prefs file
        val prefsFile = File(appContext.filesDir.parent, "shared_prefs/${PREFS_FILE_NAME}.xml")
        if (prefsFile.exists()) {
            prefsFile.delete()
        }
        // Remove the master key from Android KeyStore so it can be recreated
        try {
            val keyStore = KeyStore.getInstance("AndroidKeyStore")
            keyStore.load(null)
            keyStore.deleteEntry(MASTER_KEY_ALIAS)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to remove master key from KeyStore", e)
        }
    }

    override suspend fun saveApiKey(provider: String, key: String) = withContext(Dispatchers.IO) {
        try {
            val sanitizedProvider = provider.trim()
            val sanitizedKey = key.trim()

            if (sanitizedProvider.isEmpty()) {
                throw IllegalArgumentException("Provider name cannot be empty")
            }

            if (sanitizedKey.isEmpty()) {
                throw IllegalArgumentException("API key cannot be empty")
            }

            prefs.edit()
                .putString(makeKey(sanitizedProvider), sanitizedKey)
                .apply()
        } catch (e: SecurityException) {
            throw SecurityException("Failed to save API key due to security constraints", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to save API key for provider: $provider", e)
        }
    }

    override suspend fun getApiKey(provider: String): String? = withContext(Dispatchers.IO) {
        try {
            val sanitizedProvider = provider.trim()
            if (sanitizedProvider.isEmpty()) {
                return@withContext null
            }

            val key = prefs.getString(makeKey(sanitizedProvider), null)
            key?.trim()?.takeIf { it.isNotEmpty() }
        } catch (e: SecurityException) {
            throw SecurityException("Failed to retrieve API key due to security constraints", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to retrieve API key for provider: $provider", e)
        }
    }

    override suspend fun deleteApiKey(provider: String) = withContext(Dispatchers.IO) {
        try {
            val sanitizedProvider = provider.trim()
            if (sanitizedProvider.isEmpty()) {
                return@withContext
            }

            prefs.edit()
                .remove(makeKey(sanitizedProvider))
                .apply()
        } catch (e: SecurityException) {
            throw SecurityException("Failed to delete API key due to security constraints", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to delete API key for provider: $provider", e)
        }
    }

    override suspend fun listProviders(): List<String> = withContext(Dispatchers.IO) {
        try {
            prefs.all.keys
                .filter { it.startsWith(API_KEY_PREFIX) }
                .map { it.removePrefix(API_KEY_PREFIX) }
                .filter { it.isNotEmpty() && !it.endsWith("_baseUrl") && !it.startsWith("plugin.") && !it.startsWith("google_oauth_") && !it.startsWith("antigravity_") }
                .sorted()
        } catch (e: SecurityException) {
            throw SecurityException("Failed to list providers due to security constraints", e)
        } catch (e: Exception) {
            throw RuntimeException("Failed to list providers", e)
        }
    }

    private fun makeKey(provider: String): String {
        return "$API_KEY_PREFIX$provider"
    }
}
