package com.tomandy.oneclaw.security

/**
 * Interface for secure credential storage and retrieval.
 * Implementations should use encrypted storage mechanisms.
 */
interface CredentialVault : com.tomandy.oneclaw.engine.CredentialVault {
    /**
     * Saves an API key for a specific provider.
     * @param provider The provider identifier (e.g., "OpenAI", "Anthropic")
     * @param key The API key to store securely
     */
    override suspend fun saveApiKey(provider: String, key: String)

    /**
     * Retrieves an API key for a specific provider.
     * @param provider The provider identifier
     * @return The API key if found, null otherwise
     */
    override suspend fun getApiKey(provider: String): String?

    /**
     * Deletes the API key for a specific provider.
     * @param provider The provider identifier
     */
    override suspend fun deleteApiKey(provider: String)

    /**
     * Lists all providers that have stored API keys.
     * @return List of provider identifiers
     */
    suspend fun listProviders(): List<String>
}
