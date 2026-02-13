package com.tomandy.palmclaw.security

import com.tomandy.palmclaw.engine.CredentialVault as EngineCredentialVault

/**
 * Adapter to bridge the app's CredentialVault interface with the engine's CredentialVault interface.
 *
 * This allows the app's credential vault implementation to be used by engine plugins.
 */
class CredentialVaultAdapter(
    private val appVault: com.tomandy.palmclaw.security.CredentialVault
) : EngineCredentialVault {

    override suspend fun getApiKey(key: String): String? {
        return appVault.getApiKey(key)
    }

    override suspend fun saveApiKey(key: String, value: String) {
        appVault.saveApiKey(key, value)
    }

    override suspend fun deleteApiKey(key: String) {
        appVault.deleteApiKey(key)
    }
}
