package com.tomandy.oneclaw.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import com.tomandy.oneclaw.engine.GoogleAuthProvider
import com.tomandy.oneclaw.security.CredentialVault
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder

/**
 * Google OAuth implementation using loopback redirect (BYOK).
 *
 * Users provide their own GCP OAuth Client ID and Secret (Desktop app type).
 * Authorization opens the browser, and a loopback HTTP server on the device
 * captures the redirect. Refresh tokens are stored in CredentialVault.
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

        private const val AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token"

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
        private const val AUTH_TIMEOUT_MS = 120_000
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
     * Run the full authorization flow:
     * 1. Start a loopback HTTP server on a random port
     * 2. Open browser to Google's consent screen
     * 3. Capture the redirect with the auth code
     * 4. Exchange the code for tokens
     *
     * @param launchBrowser called on main thread to open the auth URL in a browser
     * @return null on success, or an error message string on failure
     */
    suspend fun authorize(launchBrowser: (Intent) -> Unit): String? {
        val clientId = credentialVault.getApiKey(KEY_CLIENT_ID)
            ?: return "Client ID not configured"
        val clientSecret = credentialVault.getApiKey(KEY_CLIENT_SECRET)
            ?: return "Client Secret not configured"

        val server = withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port"

        try {
            val authUrl = Uri.parse(AUTH_ENDPOINT).buildUpon()
                .appendQueryParameter("client_id", clientId)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPES.joinToString(" "))
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .build()
                .toString()

            withContext(Dispatchers.Main) {
                launchBrowser(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }

            // Wait for the browser redirect -- use NonCancellable so that
            // activity recreation doesn't abort the flow mid-exchange.
            return withContext(NonCancellable) {
                val code = withContext(Dispatchers.IO) {
                    server.soTimeout = AUTH_TIMEOUT_MS
                    try {
                        val socket = server.accept()
                        try {
                            val reader =
                                BufferedReader(InputStreamReader(socket.getInputStream()))
                            val requestLine = reader.readLine() ?: ""
                            val authCode = parseAuthCode(requestLine)

                            val html = if (authCode != null) {
                                "<html><body><h2>Authorization complete</h2>" +
                                    "<p>You can close this tab and return to OneClaw.</p></body></html>"
                            } else {
                                "<html><body><h2>Authorization failed</h2>" +
                                    "<p>Please close this tab and try again.</p></body></html>"
                            }
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Connection: close\r\n\r\n$html"
                            socket.getOutputStream().write(response.toByteArray())
                            socket.getOutputStream().flush()
                            authCode
                        } finally {
                            socket.close()
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Loopback server error", e)
                        null
                    }
                }

                if (code == null) {
                    Log.e(TAG, "No authorization code received")
                    return@withContext "No authorization code received (timed out or cancelled)"
                }

                // Retry token exchange -- the first attempt may fail because
                // the app is still backgrounded and Android restricts network.
                var lastError: String? = null
                repeat(3) { attempt ->
                    if (attempt > 0) {
                        Log.d(TAG, "Token exchange retry $attempt after: $lastError")
                        delay(3000)
                    }
                    val error = exchangeCodeForTokens(code, redirectUri, clientId, clientSecret)
                    if (error == null) return@withContext null // success
                    lastError = error
                }
                lastError
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { server.close() }
        }
    }

    private fun parseAuthCode(requestLine: String): String? {
        // "GET /?code=CODE&scope=... HTTP/1.1"
        val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: return null
        val queryStart = pathAndQuery.indexOf('?')
        if (queryStart < 0) return null
        val query = pathAndQuery.substring(queryStart + 1)
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .find { it[0] == "code" }
            ?.getOrNull(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }

    /**
     * @return null on success, or an error message on failure.
     */
    private suspend fun exchangeCodeForTokens(
        code: String,
        redirectUri: String,
        clientId: String,
        clientSecret: String
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val tokenBody = FormBody.Builder()
                    .add("code", code)
                    .add("client_id", clientId)
                    .add("client_secret", clientSecret)
                    .add("redirect_uri", redirectUri)
                    .add("grant_type", "authorization_code")
                    .build()

                val tokenRequest = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(tokenBody)
                    .build()

                val tokenResponse = httpClient.newCall(tokenRequest).execute()
                val responseBody = tokenResponse.body?.string()
                    ?: return@withContext "Empty token response"

                if (!tokenResponse.isSuccessful) {
                    Log.e(TAG, "Token exchange HTTP ${tokenResponse.code}: $responseBody")
                    return@withContext "Token exchange failed (${tokenResponse.code}): $responseBody"
                }

                val tokenJson = json.parseToJsonElement(responseBody).jsonObject

                val accessToken = tokenJson["access_token"]?.jsonPrimitive?.content
                    ?: return@withContext "No access_token in response: $responseBody"
                val refreshToken = tokenJson["refresh_token"]?.jsonPrimitive?.content
                    ?: return@withContext "No refresh_token in response: $responseBody"
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
                null // success
            } catch (e: Exception) {
                Log.e(TAG, "Token exchange failed", e)
                "Token exchange failed: ${e.message}"
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
                        .url(TOKEN_ENDPOINT)
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
     * Sign out: clear all stored tokens and revoke server-side (best-effort).
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
