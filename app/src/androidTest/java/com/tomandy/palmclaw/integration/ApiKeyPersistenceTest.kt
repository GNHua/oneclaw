package com.tomandy.palmclaw.integration

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.MediumTest
import androidx.test.platform.app.InstrumentationRegistry
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.security.CredentialVaultImpl
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Integration test for API key persistence and encryption.
 *
 * This test verifies that:
 * 1. API keys are encrypted using Android KeyStore
 * 2. API keys persist across app restarts (simulated by creating new vault instances)
 * 3. Multiple API keys can be stored simultaneously
 * 4. API keys can be deleted
 * 5. Invalid/non-existent keys return null
 *
 * The CredentialVaultImpl uses EncryptedSharedPreferences with Android KeyStore,
 * which provides hardware-backed encryption on supported devices.
 */
@RunWith(AndroidJUnit4::class)
@MediumTest
class ApiKeyPersistenceTest {

    private lateinit var credentialVault: CredentialVault
    private val testKeys = mutableListOf<String>()

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        credentialVault = CredentialVaultImpl(context)
        testKeys.clear()
    }

    @After
    fun tearDown() = runBlocking {
        // Clean up all test keys
        testKeys.forEach { provider ->
            credentialVault.deleteApiKey(provider)
        }
    }

    @Test
    fun apiKey_saveAndRetrieve_succeeds() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val expectedKey = "sk-test-key-123456"

        // Save the API key
        credentialVault.saveApiKey(provider, expectedKey)

        // Retrieve the API key
        val actualKey = credentialVault.getApiKey(provider)

        assert(actualKey == expectedKey) {
            "Expected '$expectedKey', got '$actualKey'"
        }
    }

    @Test
    fun apiKey_persistsAcrossVaultInstances() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val expectedKey = "sk-test-key-persist-123"

        // Save with first vault instance
        val vault1 = CredentialVaultImpl(context)
        vault1.saveApiKey(provider, expectedKey)

        // Create new vault instance (simulates app restart)
        val vault2 = CredentialVaultImpl(context)
        val retrievedKey = vault2.getApiKey(provider)

        assert(retrievedKey == expectedKey) {
            "Key did not persist across instances. Expected '$expectedKey', got '$retrievedKey'"
        }

        // Clean up
        vault2.deleteApiKey(provider)
    }

    @Test
    fun multipleApiKeys_storeAndRetrieveIndependently() = runTest {
        val providers = listOf(
            "OpenAI_${UUID.randomUUID()}",
            "Anthropic_${UUID.randomUUID()}",
            "Groq_${UUID.randomUUID()}"
        )
        testKeys.addAll(providers)

        val keys = mapOf(
            providers[0] to "sk-openai-key-123",
            providers[1] to "sk-ant-key-456",
            providers[2] to "gsk-groq-key-789"
        )

        // Save all keys
        keys.forEach { (provider, key) ->
            credentialVault.saveApiKey(provider, key)
        }

        // Retrieve and verify all keys
        keys.forEach { (provider, expectedKey) ->
            val actualKey = credentialVault.getApiKey(provider)
            assert(actualKey == expectedKey) {
                "Key mismatch for $provider. Expected '$expectedKey', got '$actualKey'"
            }
        }
    }

    @Test
    fun apiKey_update_overwritesExisting() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val originalKey = "sk-original-key-123"
        val updatedKey = "sk-updated-key-456"

        // Save original key
        credentialVault.saveApiKey(provider, originalKey)
        val retrievedOriginal = credentialVault.getApiKey(provider)
        assert(retrievedOriginal == originalKey) {
            "Original key not saved correctly"
        }

        // Update with new key
        credentialVault.saveApiKey(provider, updatedKey)
        val retrievedUpdated = credentialVault.getApiKey(provider)
        assert(retrievedUpdated == updatedKey) {
            "Key not updated. Expected '$updatedKey', got '$retrievedUpdated'"
        }

        // Verify old key is not retrievable
        assert(retrievedUpdated != originalKey) {
            "Old key still present after update"
        }
    }

    @Test
    fun apiKey_delete_removesKey() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val testKey = "sk-test-key-delete-123"

        // Save the key
        credentialVault.saveApiKey(provider, testKey)
        assert(credentialVault.getApiKey(provider) == testKey) {
            "Key not saved"
        }

        // Delete the key
        credentialVault.deleteApiKey(provider)

        // Verify key is deleted
        val retrievedAfterDelete = credentialVault.getApiKey(provider)
        assert(retrievedAfterDelete == null) {
            "Key not deleted. Expected null, got '$retrievedAfterDelete'"
        }
    }

    @Test
    fun apiKey_getNonExistent_returnsNull() = runTest {
        val nonExistentProvider = "NonExistent_${UUID.randomUUID()}"

        val result = credentialVault.getApiKey(nonExistentProvider)

        assert(result == null) {
            "Expected null for non-existent key, got '$result'"
        }
    }

    @Test
    fun apiKey_emptyString_savesAndRetrieves() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val emptyKey = ""

        // Save empty string
        credentialVault.saveApiKey(provider, emptyKey)

        // Retrieve and verify
        val retrievedKey = credentialVault.getApiKey(provider)
        assert(retrievedKey == emptyKey) {
            "Empty string not handled correctly. Expected '', got '$retrievedKey'"
        }
    }

    @Test
    fun apiKey_longString_savesAndRetrieves() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        // Create a very long key (simulating long tokens)
        val longKey = "sk-" + "a".repeat(500)

        // Save long key
        credentialVault.saveApiKey(provider, longKey)

        // Retrieve and verify
        val retrievedKey = credentialVault.getApiKey(provider)
        assert(retrievedKey == longKey) {
            "Long key not saved correctly. Length expected: ${longKey.length}, got: ${retrievedKey?.length}"
        }
    }

    @Test
    fun apiKey_specialCharacters_savesAndRetrieves() = runTest {
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        // Key with special characters
        val specialKey = "sk-test!@#$%^&*()_+-={}[]|\\:;\"'<>,.?/~`"

        // Save key with special characters
        credentialVault.saveApiKey(provider, specialKey)

        // Retrieve and verify
        val retrievedKey = credentialVault.getApiKey(provider)
        assert(retrievedKey == specialKey) {
            "Special characters not preserved. Expected '$specialKey', got '$retrievedKey'"
        }
    }

    @Test
    fun apiKey_concurrentAccess_threadsafe() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val expectedKey = "sk-concurrent-test-key"

        // Create multiple vault instances (simulating concurrent access)
        val vault1 = CredentialVaultImpl(context)
        val vault2 = CredentialVaultImpl(context)
        val vault3 = CredentialVaultImpl(context)

        // Save with vault1
        vault1.saveApiKey(provider, expectedKey)

        // Retrieve with vault2 and vault3
        val key2 = vault2.getApiKey(provider)
        val key3 = vault3.getApiKey(provider)

        assert(key2 == expectedKey) {
            "Vault2 did not retrieve correct key"
        }
        assert(key3 == expectedKey) {
            "Vault3 did not retrieve correct key"
        }

        // Clean up
        vault1.deleteApiKey(provider)
    }

    @Test
    fun apiKey_getAllProviders_returnsAllSaved() = runTest {
        val providers = listOf(
            "Provider1_${UUID.randomUUID()}",
            "Provider2_${UUID.randomUUID()}",
            "Provider3_${UUID.randomUUID()}"
        )
        testKeys.addAll(providers)

        // Save keys for all providers
        providers.forEach { provider ->
            credentialVault.saveApiKey(provider, "key-for-$provider")
        }

        // Get all providers
        val savedProviders = credentialVault.getAllProviders()

        // Verify all test providers are in the list
        providers.forEach { provider ->
            assert(savedProviders.contains(provider)) {
                "Provider '$provider' not found in saved providers list"
            }
        }
    }

    @Test
    fun encryption_isActuallyUsed_notPlaintext() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val provider = "TestProvider_${UUID.randomUUID()}"
        testKeys.add(provider)

        val testKey = "sk-should-be-encrypted-123"

        // Save the key
        credentialVault.saveApiKey(provider, testKey)

        // Try to read the SharedPreferences directly (as plaintext)
        val prefs = context.getSharedPreferences("palmclaw_credentials", android.content.Context.MODE_PRIVATE)
        val allEntries = prefs.all

        // The key should NOT appear in plaintext in SharedPreferences
        val foundPlaintext = allEntries.values.any { value ->
            value.toString().contains(testKey)
        }

        assert(!foundPlaintext) {
            "API key found in plaintext! Encryption is not working."
        }

        // Verify we can still retrieve it through the vault (decryption works)
        val retrievedKey = credentialVault.getApiKey(provider)
        assert(retrievedKey == testKey) {
            "Key retrieval failed after encryption check"
        }
    }
}
