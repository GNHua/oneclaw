package com.tomandy.palmclaw.engine

/**
 * Provider for Google OAuth2 access tokens.
 *
 * This interface sits in plugin-runtime so it has no Android framework
 * dependencies beyond what plugin-runtime already uses. The implementation
 * lives in the app module (GoogleAuthManager).
 *
 * JS plugins access this through the palmclaw.google host binding.
 * Multiple plugins (Gmail, Calendar, etc.) share the same token since this
 * is injected at the PluginContext level, not namespaced per-plugin.
 */
interface GoogleAuthProvider {
    /**
     * Get a valid access token, refreshing if expired.
     * Returns null if the user is not signed in.
     */
    suspend fun getAccessToken(): String?

    /**
     * Check if the user has a valid Google OAuth session.
     */
    suspend fun isSignedIn(): Boolean

    /**
     * Get the connected account email, or null if not signed in.
     */
    suspend fun getAccountEmail(): String?
}
