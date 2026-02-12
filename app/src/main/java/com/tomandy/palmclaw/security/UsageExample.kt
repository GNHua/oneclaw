package com.tomandy.palmclaw.security

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch

/**
 * Example usage of CredentialVault in various scenarios.
 * This file demonstrates best practices for integrating the credential vault.
 */

// ============================================
// Example 1: Basic Usage in a ViewModel
// ============================================
class ExampleViewModel(
    private val credentialVault: CredentialVault
) : ViewModel() {

    fun saveOpenAIKey(apiKey: String) {
        viewModelScope.launch {
            try {
                credentialVault.saveApiKey("OpenAI", apiKey)
                // Show success message
            } catch (e: SecurityException) {
                // Handle security error
            } catch (e: IllegalArgumentException) {
                // Handle validation error
            }
        }
    }

    suspend fun getOpenAIKey(): String? {
        return try {
            credentialVault.getApiKey("OpenAI")
        } catch (e: Exception) {
            null
        }
    }
}

// ============================================
// Example 2: Using in an API Client
// ============================================
class OpenAIClient(
    private val credentialVault: CredentialVault
) {
    suspend fun makeRequest(prompt: String): String {
        val apiKey = credentialVault.getApiKey("OpenAI")
            ?: throw IllegalStateException("OpenAI API key not configured")

        // Use apiKey to make API request
        return "Response from API"
    }
}

// ============================================
// Example 3: Dependency Injection Setup
// ============================================

// Using manual DI (in Application class or similar)
class DIContainer(context: Context) {
    val credentialVault: CredentialVault by lazy {
        CredentialVaultImpl(context)
    }
}

// Using Hilt (if available)
// @Module
// @InstallIn(SingletonComponent::class)
// object SecurityModule {
//     @Provides
//     @Singleton
//     fun provideCredentialVault(
//         @ApplicationContext context: Context
//     ): CredentialVault = CredentialVaultImpl(context)
// }

// Using Koin (if available)
// val securityModule = module {
//     single<CredentialVault> { CredentialVaultImpl(androidContext()) }
// }

// ============================================
// Example 4: Managing Multiple Providers
// ============================================
class MultiProviderManager(
    private val credentialVault: CredentialVault
) {
    suspend fun configureAllProviders(
        openAIKey: String? = null,
        anthropicKey: String? = null,
        googleKey: String? = null
    ) {
        openAIKey?.let { credentialVault.saveApiKey("OpenAI", it) }
        anthropicKey?.let { credentialVault.saveApiKey("Anthropic", it) }
        googleKey?.let { credentialVault.saveApiKey("Google", it) }
    }

    suspend fun getAvailableProviders(): List<String> {
        return credentialVault.listProviders()
    }

    suspend fun isProviderConfigured(provider: String): Boolean {
        return credentialVault.getApiKey(provider) != null
    }
}

// ============================================
// Example 5: Error Handling Patterns
// ============================================
class ErrorHandlingExample(
    private val credentialVault: CredentialVault
) {
    suspend fun saveKeyWithErrorHandling(provider: String, key: String): Result<Unit> {
        return try {
            if (provider.isBlank()) {
                return Result.failure(IllegalArgumentException("Provider cannot be blank"))
            }
            if (key.isBlank()) {
                return Result.failure(IllegalArgumentException("Key cannot be blank"))
            }

            credentialVault.saveApiKey(provider, key)
            Result.success(Unit)
        } catch (e: SecurityException) {
            Result.failure(SecurityException("Unable to save key securely: ${e.message}"))
        } catch (e: Exception) {
            Result.failure(RuntimeException("Failed to save key: ${e.message}"))
        }
    }

    suspend fun getKeyOrDefault(provider: String, default: String): String {
        return try {
            credentialVault.getApiKey(provider) ?: default
        } catch (e: Exception) {
            default
        }
    }
}

// ============================================
// Example 6: Testing with Mock
// ============================================
// For testing, you can create a simple in-memory implementation:
class InMemoryCredentialVault : CredentialVault {
    private val storage = mutableMapOf<String, String>()

    override suspend fun saveApiKey(provider: String, key: String) {
        storage[provider] = key
    }

    override suspend fun getApiKey(provider: String): String? {
        return storage[provider]
    }

    override suspend fun deleteApiKey(provider: String) {
        storage.remove(provider)
    }

    override suspend fun listProviders(): List<String> {
        return storage.keys.sorted()
    }
}

// Usage in tests:
// val testVault = InMemoryCredentialVault()
// val viewModel = SettingsViewModel(testVault)

// ============================================
// Example 7: Migration from Old Storage
// ============================================
class CredentialMigration(
    private val credentialVault: CredentialVault,
    private val context: Context
) {
    suspend fun migrateFromLegacyStorage() {
        // Example: Migrate from old SharedPreferences
        val oldPrefs = context.getSharedPreferences("old_keys", Context.MODE_PRIVATE)
        val oldOpenAIKey = oldPrefs.getString("openai_key", null)

        oldOpenAIKey?.let {
            credentialVault.saveApiKey("OpenAI", it)
            // Clear old storage
            oldPrefs.edit().remove("openai_key").apply()
        }
    }
}

// ============================================
// Example 8: Key Validation
// ============================================
object APIKeyValidator {
    fun validateOpenAIKey(key: String): Boolean {
        return key.startsWith("sk-") && key.length >= 20
    }

    fun validateAnthropicKey(key: String): Boolean {
        return key.startsWith("sk-ant-") && key.length >= 20
    }

    fun validateGoogleKey(key: String): Boolean {
        return key.matches(Regex("AIza[0-9A-Za-z_-]{35}"))
    }
}

// Use before saving:
// if (APIKeyValidator.validateOpenAIKey(apiKey)) {
//     credentialVault.saveApiKey("OpenAI", apiKey)
// }
