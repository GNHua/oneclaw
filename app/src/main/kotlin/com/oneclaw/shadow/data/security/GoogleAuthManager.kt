package com.oneclaw.shadow.data.security

import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.oneclaw.shadow.core.util.AppResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.ServerSocket

/**
 * Manages BYOK (Bring Your Own Key) OAuth 2.0 flow for Google Workspace.
 * Ported from oneclaw-1's OAuthGoogleAuthManager.
 *
 * Flow:
 * 1. User provides GCP Desktop OAuth Client ID + Secret
 * 2. App starts loopback HTTP server on random port
 * 3. Opens browser for Google consent
 * 4. Captures auth code via redirect
 * 5. Exchanges code for tokens
 * 6. Stores tokens in EncryptedSharedPreferences
 */
class GoogleAuthManager(
    private val context: Context,
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "GoogleAuthManager"
        private const val PREFS_NAME = "google_oauth_prefs"
        private const val KEY_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_TOKEN_EXPIRY = "google_oauth_token_expiry"
        private const val KEY_EMAIL = "google_oauth_email"

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L  // refresh 60s before expiry

        private const val AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_URL = "https://oauth2.googleapis.com/token"
        private const val REVOKE_URL = "https://oauth2.googleapis.com/revoke"
        private const val USERINFO_URL = "https://www.googleapis.com/oauth2/v2/userinfo"

        val SCOPES = listOf(
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
    }

    private val prefs: SharedPreferences by lazy {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }

    private val tokenMutex = Mutex()

    // --- Public API ---

    /**
     * Save OAuth client credentials (Client ID + Client Secret).
     */
    fun saveOAuthCredentials(clientId: String, clientSecret: String) {
        prefs.edit()
            .putString(KEY_CLIENT_ID, clientId.trim())
            .putString(KEY_CLIENT_SECRET, clientSecret.trim())
            .apply()
    }

    fun getClientId(): String? = prefs.getString(KEY_CLIENT_ID, null)
    fun getClientSecret(): String? = prefs.getString(KEY_CLIENT_SECRET, null)
    fun hasOAuthCredentials(): Boolean = !getClientId().isNullOrBlank() && !getClientSecret().isNullOrBlank()

    fun isSignedIn(): Boolean = !prefs.getString(KEY_REFRESH_TOKEN, null).isNullOrBlank()
    fun getAccountEmail(): String? = prefs.getString(KEY_EMAIL, null)

    /**
     * Initiate OAuth flow:
     * 1. Start loopback HTTP server on random port
     * 2. Build consent URL and open browser
     * 3. Wait for redirect with auth code
     * 4. Exchange code for tokens
     * 5. Fetch user info
     * 6. Store everything
     */
    suspend fun authorize(): AppResult<String> {
        val clientId = getClientId()
            ?: return AppResult.Error(exception = Exception("Client ID not configured"), message = "Client ID not configured")
        val clientSecret = getClientSecret()
            ?: return AppResult.Error(exception = Exception("Client Secret not configured"), message = "Client Secret not configured")

        return try {
            // Start loopback server on a random available port
            val serverSocket = ServerSocket(0)
            val port = serverSocket.localPort
            val redirectUri = "http://127.0.0.1:$port"

            // Build consent URL
            val consentUrl = buildConsentUrl(clientId, redirectUri)

            // Open browser
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // Wait for redirect (blocking on IO dispatcher)
            val authCode = withContext(Dispatchers.IO) {
                waitForAuthCode(serverSocket)
            }

            // Exchange code for tokens
            val tokens = exchangeCodeForTokens(authCode, clientId, clientSecret, redirectUri)

            // Fetch user email
            val email = fetchUserEmail(tokens.accessToken)

            // Store everything
            prefs.edit()
                .putString(KEY_REFRESH_TOKEN, tokens.refreshToken)
                .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + tokens.expiresInMs)
                .putString(KEY_EMAIL, email)
                .apply()

            AppResult.Success(email)
        } catch (e: Exception) {
            Log.e(TAG, "Authorization failed", e)
            AppResult.Error(exception = e, message = e.message ?: "Authorization failed")
        }
    }

    /**
     * Get a valid access token, refreshing if necessary.
     * Thread-safe via Mutex to prevent concurrent refresh storms.
     */
    suspend fun getAccessToken(): String? {
        if (!isSignedIn()) return null

        return tokenMutex.withLock {
            val cachedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
            val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)

            if (cachedToken != null && System.currentTimeMillis() < expiry - TOKEN_EXPIRY_MARGIN_MS) {
                return@withLock cachedToken
            }

            // Token expired or about to expire -- refresh
            val refreshToken = prefs.getString(KEY_REFRESH_TOKEN, null) ?: return@withLock null
            val clientId = getClientId() ?: return@withLock null
            val clientSecret = getClientSecret() ?: return@withLock null

            try {
                val tokens = refreshAccessToken(refreshToken, clientId, clientSecret)
                prefs.edit()
                    .putString(KEY_ACCESS_TOKEN, tokens.accessToken)
                    .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + tokens.expiresInMs)
                    .apply()
                tokens.accessToken
            } catch (e: Exception) {
                Log.e(TAG, "Token refresh failed", e)
                // Clear tokens on refresh failure (refresh token may be revoked)
                clearTokens()
                null
            }
        }
    }

    /**
     * Sign out: revoke token server-side (best-effort), then clear local storage.
     */
    suspend fun signOut() {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
            ?: prefs.getString(KEY_REFRESH_TOKEN, null)

        // Best-effort server-side revocation
        if (token != null) {
            try {
                withContext(Dispatchers.IO) {
                    val request = Request.Builder()
                        .url("$REVOKE_URL?token=$token")
                        .post("".toRequestBody(null))
                        .build()
                    okHttpClient.newCall(request).execute().close()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Token revocation failed (best-effort)", e)
            }
        }

        clearTokens()
    }

    // --- Private helpers ---

    internal fun buildConsentUrl(clientId: String, redirectUri: String): String {
        val scopeString = SCOPES.joinToString(" ")
        return "$AUTH_URL?" +
            "client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&scope=${Uri.encode(scopeString)}" +
            "&access_type=offline" +
            "&prompt=consent"
    }

    internal fun waitForAuthCode(serverSocket: ServerSocket): String {
        serverSocket.soTimeout = 120_000  // 2-minute timeout
        val socket = serverSocket.accept()
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine()
        // Parse: GET /?code=AUTH_CODE&scope=... HTTP/1.1
        val code = requestLine
            ?.substringAfter("code=", "")
            ?.substringBefore("&")
            ?.substringBefore(" ")
            ?: throw IOException("No auth code in redirect")

        if (code.isBlank()) throw IOException("Empty auth code")

        // Send success response to browser
        val response = "HTTP/1.1 200 OK\r\nContent-Type: text/html\r\n\r\n" +
            "<html><body><h2>Authorization successful</h2>" +
            "<p>You can close this tab and return to the app.</p></body></html>"
        socket.getOutputStream().write(response.toByteArray())
        socket.close()
        serverSocket.close()

        return code
    }

    private suspend fun exchangeCodeForTokens(
        code: String,
        clientId: String,
        clientSecret: String,
        redirectUri: String
    ): TokenResponse {
        return withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("code", code)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("redirect_uri", redirectUri)
                .add("grant_type", "authorization_code")
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IOException("Empty token response")

            val json = Json.parseToJsonElement(responseBody).jsonObject
            TokenResponse(
                accessToken = json["access_token"]!!.jsonPrimitive.content,
                refreshToken = json["refresh_token"]?.jsonPrimitive?.content ?: "",
                expiresInMs = (json["expires_in"]!!.jsonPrimitive.long) * 1000
            )
        }
    }

    internal suspend fun refreshAccessToken(
        refreshToken: String,
        clientId: String,
        clientSecret: String
    ): TokenResponse {
        return withContext(Dispatchers.IO) {
            val body = FormBody.Builder()
                .add("refresh_token", refreshToken)
                .add("client_id", clientId)
                .add("client_secret", clientSecret)
                .add("grant_type", "refresh_token")
                .build()

            val request = Request.Builder()
                .url(TOKEN_URL)
                .post(body)
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IOException("Empty refresh response")

            val json = Json.parseToJsonElement(responseBody).jsonObject
            TokenResponse(
                accessToken = json["access_token"]!!.jsonPrimitive.content,
                refreshToken = refreshToken,  // refresh token is not rotated
                expiresInMs = (json["expires_in"]!!.jsonPrimitive.long) * 1000
            )
        }
    }

    private suspend fun fetchUserEmail(accessToken: String): String {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(USERINFO_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IOException("Empty userinfo response")

            val json = Json.parseToJsonElement(responseBody).jsonObject
            json["email"]?.jsonPrimitive?.content
                ?: throw IOException("No email in userinfo response")
        }
    }

    internal fun clearTokens() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_EMAIL)
            .apply()
    }

    data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresInMs: Long
    )
}
