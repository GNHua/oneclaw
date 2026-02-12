package com.tomandy.palmclaw.security

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Unit tests for CredentialVault implementation.
 * These tests verify secure storage, retrieval, and deletion of API keys.
 */
@RunWith(AndroidJUnit4::class)
class CredentialVaultTest {

    private lateinit var context: Context
    private lateinit var credentialVault: CredentialVault

    @Before
    fun setup() {
        context = ApplicationProvider.getApplicationContext()
        credentialVault = CredentialVaultImpl(context)
    }

    @After
    fun cleanup() {
        // Clean up all stored credentials after each test
        runTest {
            val providers = credentialVault.listProviders()
            providers.forEach { provider ->
                credentialVault.deleteApiKey(provider)
            }
        }
    }

    @Test
    fun testSaveAndRetrieveApiKey() = runTest {
        val provider = "OpenAI"
        val apiKey = "sk-test-1234567890abcdef"

        credentialVault.saveApiKey(provider, apiKey)
        val retrieved = credentialVault.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun testRetrieveNonExistentApiKey() = runTest {
        val retrieved = credentialVault.getApiKey("NonExistentProvider")
        assertNull(retrieved)
    }

    @Test
    fun testDeleteApiKey() = runTest {
        val provider = "Anthropic"
        val apiKey = "sk-ant-test-key"

        credentialVault.saveApiKey(provider, apiKey)
        assertNotNull(credentialVault.getApiKey(provider))

        credentialVault.deleteApiKey(provider)
        val retrieved = credentialVault.getApiKey(provider)

        assertNull(retrieved)
    }

    @Test
    fun testListProviders() = runTest {
        val providers = listOf("OpenAI", "Anthropic", "Google")
        providers.forEach { provider ->
            credentialVault.saveApiKey(provider, "test-key-$provider")
        }

        val listedProviders = credentialVault.listProviders()

        assertEquals(providers.size, listedProviders.size)
        assertTrue(listedProviders.containsAll(providers))
    }

    @Test
    fun testListProvidersEmpty() = runTest {
        val providers = credentialVault.listProviders()
        assertTrue(providers.isEmpty())
    }

    @Test
    fun testMultipleProvidersIndependence() = runTest {
        val provider1 = "OpenAI"
        val key1 = "sk-openai-key"
        val provider2 = "Anthropic"
        val key2 = "sk-ant-key"

        credentialVault.saveApiKey(provider1, key1)
        credentialVault.saveApiKey(provider2, key2)

        assertEquals(key1, credentialVault.getApiKey(provider1))
        assertEquals(key2, credentialVault.getApiKey(provider2))

        credentialVault.deleteApiKey(provider1)
        assertNull(credentialVault.getApiKey(provider1))
        assertEquals(key2, credentialVault.getApiKey(provider2))
    }

    @Test
    fun testUpdateExistingApiKey() = runTest {
        val provider = "OpenAI"
        val oldKey = "sk-old-key"
        val newKey = "sk-new-key"

        credentialVault.saveApiKey(provider, oldKey)
        assertEquals(oldKey, credentialVault.getApiKey(provider))

        credentialVault.saveApiKey(provider, newKey)
        assertEquals(newKey, credentialVault.getApiKey(provider))
    }

    @Test
    fun testEncryption() = runTest {
        val provider = "OpenAI"
        val apiKey = "sk-plaintext-key-for-encryption-test"

        credentialVault.saveApiKey(provider, apiKey)

        // Directly access encrypted SharedPreferences to verify encryption
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        val prefs = EncryptedSharedPreferences.create(
            context,
            "palmclaw_credentials",
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )

        // The stored value should be accessible via EncryptedSharedPreferences
        val storedValue = prefs.getString("api_key_$provider", null)

        // Verify the value can be read (meaning encryption/decryption works)
        assertNotNull(storedValue)
        assertEquals(apiKey, storedValue)

        // Note: We can't easily test that the raw encrypted value is different
        // without accessing the underlying encrypted file directly, which is
        // implementation-specific. The fact that we can store and retrieve
        // the value correctly through EncryptedSharedPreferences validates
        // that encryption is working.
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSaveWithEmptyProvider() = runTest {
        credentialVault.saveApiKey("", "test-key")
    }

    @Test(expected = IllegalArgumentException::class)
    fun testSaveWithEmptyKey() = runTest {
        credentialVault.saveApiKey("OpenAI", "")
    }

    @Test
    fun testProviderNameTrimming() = runTest {
        val provider = "  OpenAI  "
        val apiKey = "sk-test-key"

        credentialVault.saveApiKey(provider, apiKey)

        // Should be retrievable with trimmed provider name
        val retrieved = credentialVault.getApiKey("OpenAI")
        assertEquals(apiKey, retrieved)

        // Should also be in the providers list as trimmed
        val providers = credentialVault.listProviders()
        assertTrue(providers.contains("OpenAI"))
        assertFalse(providers.contains(provider))
    }

    @Test
    fun testApiKeyTrimming() = runTest {
        val provider = "OpenAI"
        val apiKey = "  sk-test-key  "

        credentialVault.saveApiKey(provider, apiKey)
        val retrieved = credentialVault.getApiKey(provider)

        assertEquals(apiKey.trim(), retrieved)
    }

    @Test
    fun testDeleteNonExistentProvider() = runTest {
        // Should not throw exception
        credentialVault.deleteApiKey("NonExistent")

        val providers = credentialVault.listProviders()
        assertTrue(providers.isEmpty())
    }

    @Test
    fun testProvidersListSorted() = runTest {
        val providers = listOf("Zebra", "Apple", "Microsoft", "Google")
        providers.forEach { provider ->
            credentialVault.saveApiKey(provider, "test-key")
        }

        val listedProviders = credentialVault.listProviders()
        val sortedProviders = providers.sorted()

        assertEquals(sortedProviders, listedProviders)
    }

    @Test
    fun testPersistenceAcrossInstances() = runTest {
        val provider = "OpenAI"
        val apiKey = "sk-persistence-test"

        // Save with first instance
        credentialVault.saveApiKey(provider, apiKey)

        // Create new instance
        val newVault = CredentialVaultImpl(context)
        val retrieved = newVault.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun testSpecialCharactersInProviderName() = runTest {
        val provider = "OpenAI-GPT-4"
        val apiKey = "sk-test-key"

        credentialVault.saveApiKey(provider, apiKey)
        val retrieved = credentialVault.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }

    @Test
    fun testLongApiKey() = runTest {
        val provider = "OpenAI"
        val apiKey = "sk-" + "a".repeat(500)

        credentialVault.saveApiKey(provider, apiKey)
        val retrieved = credentialVault.getApiKey(provider)

        assertEquals(apiKey, retrieved)
    }
}
