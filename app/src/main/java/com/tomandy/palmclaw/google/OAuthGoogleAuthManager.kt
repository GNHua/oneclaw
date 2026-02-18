package com.tomandy.palmclaw.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tomandy.palmclaw.engine.GoogleAuthProvider
import com.tomandy.palmclaw.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.ResponseTypeValues
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * Google OAuth implementation using AppAuth-Android (BYOK).
 *
 * Users provide their own GCP OAuth Client ID and Secret (Web application type).
 * Authorization happens via Chrome Custom Tab, and refresh tokens are stored
 * in CredentialVault for persistent sessions.
 */
class OAuthGoogleAuthManager(
    private val context: Context,
    private val credentialVault: CredentialVault
) : GoogleAuthProvider {

    companion object {
        private const val TAG = "OAuthGoogleAuth"

        private const val KEY_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_TOKEN_EXPIRY = "google_oauth_token_expiry"
        private const val KEY_EMAIL = "google_oauth_email"

        private const val REDIRECT_URI = "com.tomandy.palmclaw:/oauth2callback"

        private val AUTH_CONFIG = AuthorizationServiceConfiguration(
            Uri.parse("https://accounts.google.com/o/oauth2/v2/auth"),
            Uri.parse("https://oauth2.googleapis.com/token")
        )

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/gmail.modify",
            "https://www.googleapis.com/auth/gmail.settings.basic",
            "https://www.googleapis.com/auth/calendar",
            "https://www.googleapis.com/auth/tasks",
            "https://www.googleapis.com/auth/contacts",
            "https://www.googleapis.com/auth/drive",
            "https://www.googleapis.com/auth/documents",
            "https://www.googleapis.com/auth/spreadsheets",
            "https://www.googleapis.com/auth/presentations",
            "https://www.googleapis.com/auth/forms.body.readonly",
            "https://www.googleapis.com/auth/forms.responses.readonly"
        )

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
    }

    private val httpClient = OkHttpClient()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Save OAuth client credentials (Client ID + Secret) from settings UI.
     */
    suspend fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        credentialVault.saveApiKey(KEY_CLIENT_ID, clientId.trim())
        credentialVault.saveApiKey(KEY_CLIENT_SECRET, clientSecret.trim())
    }

    /**
     * Check if OAuth client credentials have been configured.
     */
    suspend fun hasOAuthCredentials(): Boolean {
        return !credentialVault.getApiKey(KEY_CLIENT_ID).isNullOrBlank() &&
            !credentialVault.getApiKey(KEY_CLIENT_SECRET).isNullOrBlank()
    }

    /**
     * Build an authorization intent that launches a Chrome Custom Tab.
     */
    suspend fun buildAuthorizationIntent(): Intent {
        val clientId = credentialVault.getApiKey(KEY_CLIENT_ID)
            ?: throw IllegalStateException("Client ID not configured")

        val request = AuthorizationRequest.Builder(
            AUTH_CONFIG,
            clientId,
            ResponseTypeValues.CODE,
            Uri.parse(REDIRECT_URI)
        )
            .setScopes(SCOPES)
            .setAdditionalParameters(
                mapOf("access_type" to "offline", "prompt" to "consent")
            )
            .build()

        val authService = AuthorizationService(context)
        return authService.getAuthorizationRequestIntent(request)
    }

    /**
     * Handle the authorization response by exchanging the code for tokens.
     * Returns true on success, false on failure.
     */
    suspend fun handleAuthorizationResponse(intent: Intent): Boolean {
        val resp = AuthorizationResponse.fromIntent(intent) ?: run {
            val errorMsg = net.openid.appauth.AuthorizationException.fromIntent(intent)
            Log.e(TAG, "Authorization failed: $errorMsg")
            return false
        }

        val code = resp.authorizationCode ?: run {
            Log.e(TAG, "No authorization code in response")
            return false
        }

        return withContext(Dispatchers.IO) {
            try {
                val clientId = credentialVault.getApiKey(KEY_CLIENT_ID)!!
                val clientSecret = credentialVault.getApiKey(KEY_CLIENT_SECRET)!!

                // Exchange authorization code for tokens
                val tokenBody = FormBody.Builder()
                    .add("code", code)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("redirect_uri", REDIRECT_URI)
                    .add("grant_type", "authorization_code")
                    .build()

                val tokenRequest = Request.Builder()
                    .url("https://oauth2.googleapis.com/token")
                    .post(tokenBody)
                    .build()

                val tokenResponse = httpClient.newCall(tokenRequest).execute()
                val tokenJson = json.parseToJsonElement(
                    tokenResponse.body?.string() ?: throw Exception("Empty token response")
                ).jsonObject

                val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
                    ?: throw Exception("No access_token in response")
                val refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content
                    ?: throw Exception("No refresh_token in response")
                val expiresIn = tokenJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull()
                    ?: 3600L

                // Fetch user email
                val userinfoRequest = Request.Builder()
                    .url("https://www.googleapis.com/oauth2/v2/userinfo")
                    .addHeader("Authorization", "Bearer $accessToken")
                    .build()
                val userinfoResponse = httpClient.newCall(userinfoRequest).execute()
                val userinfoJson = json.parseToJsonElement(
                    userinfoResponse.body?.string() ?: "{}"
                ).jsonObject
                val email = userinfoJson["email"]?.jsonPrimitive?.content

                // Persist tokens
                credentialVault.saveApiKey(KEY_REFRESH_TOKEN, refreshToken)
                credentialVault.saveApiKey(KEY_ACCESS_TOKEN, accessToken)
                val expiryMs = System.currentTimeMillis() + (expiresIn * 1000)
                credentialVault.saveApiKey(KEY_TOKEN_EXPIRY, expiryMs.toString())
                if (email != null) {
                    credentialVault.saveApiKey(KEY_EMAIL, email)
                }

                Log.i(TAG, "Authorization successful for $email")
                true
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                false
            }
        }
    }

    override suspend fun getAccessToken(): String? {
        val refreshToken = credentialVault.getApiKey(KEY_REFRESH_TOKEN) ?: return null

        // Check cached token
        val cachedToken = credentialVault.getApiKey(KEY_ACCESS_TOKEN)
        val expiryStr = credentialVault.getApiKey(KEY_TOKEN_EXPIRY)
        val expiry = expiryStr?.toLongOrNull() ?: 0L

        if (cachedToken != null && System.currentTimeMillis() < expiry - TOKEN_EXPIRY_MARGIN_MS) {
            return cachedToken
        }

        // Refresh with mutex to prevent concurrent refresh storms
        return refreshMutex.withLock {
            // Double-check after acquiring lock
            val recheckedToken = credentialVault.getApiKey(KEY_ACCESS_TOKEN)
            val recheckedExpiry = credentialVault.getApiKey(KEY_TOKEN_EXPIRY)?.toLongOrNull() ?: 0L
            if (recheckedToken != null &&
                System.currentTimeMillis() < recheckedExpiry - TOKEN_EXPIRY_MARGIN_MS
            ) {
                return@withLock recheckedToken
            }

            withContext(Dispatchers.IO) {
                try {
                    val clientId = credentialVault.getApiKey(KEY_CLIENT_ID)!!
                    val clientSecret = credentialVault.getApiKey(KEY_CLIENT_SECRET)!!

                    val body = FormBody.Builder()
                        .add("client_id", clientId)
                        .add("client_secret", clientSecret)
                        .add("refresh_token", refreshToken)
                        .add("grant_type", "refresh_token")
                        .build()

                    val request = Request.Builder()
                        .url("https://oauth2.googleapis.com/token")
                        .post(body)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseJson = json.parseToJsonElement(
                        response.body?.string() ?: throw Exception("Empty refresh response")
                    ).jsonObject

                    val newToken = responseJson["access_token"]?.jsonPrimitive?.content
                        ?: throw Exception("No access_token in refresh response")
                    val expiresIn =
                        responseJson["expires_in"]?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

                    credentialVault.saveApiKey(KEY_ACCESS_TOKEN, newToken)
                    val newExpiry = System.currentTimeMillis() + (expiresIn * 1000)
                    credentialVault.saveApiKey(KEY_TOKEN_EXPIRY, newExpiry.toString())

                    Log.d(TAG, "Token refreshed successfully")
                    newToken
                } catch (e: Exception) {
                    Log.e(TAG, "Token refresh failed", e)
                    null
                }
            }
        }
    }

    override suspend fun isSignedIn(): Boolean {
        return !credentialVault.getApiKey(KEY_REFRESH_TOKEN).isNullOrBlank()
    }

    override suspend fun getAccountEmail(): String? {
        return credentialVault.getApiKey(KEY_EMAIL)
    }

    /**
     * Sign out: clear all stored credentials and revoke token server-side (best-effort).
     */
    suspend fun signOut() {
        val token = credentialVault.getApiKey(KEY_ACCESS_TOKEN)

        // Clear local state
        credentialVault.deleteApiKey(KEY_REFRESH_TOKEN)
        credentialVault.deleteApiKey(KEY_ACCESS_TOKEN)
        credentialVault.deleteApiKey(KEY_TOKEN_EXPIRY)
        credentialVault.deleteApiKey(KEY_EMAIL)

        // Best-effort server-side revocation
        if (token != null) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url("https://oauth2.googleapis.com/revoke?token=$token")
                        .post(FormBody.Builder().build())
                        .build()
                    httpClient.newCall(request).execute()
                } catch (e: Exception) {
                    Log.w(TAG, "Token revocation failed (best-effort)", e)
                }
            }
        }
    }
}
