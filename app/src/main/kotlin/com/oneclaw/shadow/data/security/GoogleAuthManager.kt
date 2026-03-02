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
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
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
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.net.UnknownHostException

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
        private const val PREFS_NAME = "oneclaw_google_oauth_prefs"
        private const val KEY_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_TOKEN_EXPIRY = "google_oauth_token_expiry"
        private const val KEY_EMAIL = "google_oauth_email"

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L  // refresh 60s before expiry
        private const val AUTH_TIMEOUT_MS = 120_000

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
    private val json = Json { ignoreUnknownKeys = true }

    // --- Public API ---

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
     * 1. Start loopback HTTP server on random port bound to 127.0.0.1
     * 2. Build consent URL and open browser
     * 3. Wait for redirect with auth code
     * 4. Exchange code for tokens (with retry)
     * 5. Fetch user info
     * 6. Store everything
     */
    suspend fun authorize(): AppResult<String> {
        val clientId = getClientId()
            ?: return AppResult.Error(exception = Exception("Client ID not configured"), message = "Client ID not configured")
        val clientSecret = getClientSecret()
            ?: return AppResult.Error(exception = Exception("Client Secret not configured"), message = "Client Secret not configured")

        // Bind to 127.0.0.1 explicitly (matches oneclaw-1)
        val serverSocket = withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        val port = serverSocket.localPort
        val redirectUri = "http://127.0.0.1:$port"

        try {
            val consentUrl = buildConsentUrl(clientId, redirectUri)

            // Launch browser -- FLAG_ACTIVITY_NEW_TASK required when starting
            // from Application context (not an Activity)
            withContext(Dispatchers.Main) {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            }

            // Use NonCancellable so activity recreation doesn't abort the flow
            return withContext(NonCancellable) {
                // Wait for redirect (blocking on IO dispatcher)
                val authCode = withContext(Dispatchers.IO) {
                    waitForAuthCode(serverSocket)
                }

                if (authCode == null) {
                    return@withContext AppResult.Error(
                        exception = Exception("No authorization code received"),
                        message = "No authorization code received (timed out or cancelled)"
                    )
                }

                // Retry token exchange -- the first attempt may fail because
                // the app is still backgrounded and Android restricts network.
                // Only retry the exchange itself; once tokens are obtained, save
                // them immediately so they are not lost if userinfo fetch fails.
                var lastError: Exception? = null
                var tokens: TokenResponse? = null
                repeat(3) { attempt ->
                    if (attempt > 0) {
                        Log.d(TAG, "Token exchange retry $attempt after: ${lastError?.message}")
                        delay(3000)
                    }
                    try {
                        tokens = exchangeCodeForTokens(authCode, clientId, clientSecret, redirectUri)
                        return@repeat // success, stop retrying
                    } catch (e: UnknownHostException) {
                        lastError = e
                        Log.e(TAG, "Token exchange failed (DNS): ${e.message}")
                    } catch (e: SocketTimeoutException) {
                        lastError = e
                        Log.e(TAG, "Token exchange failed (timeout): ${e.message}")
                    } catch (e: Exception) {
                        lastError = e
                        Log.e(TAG, "Token exchange failed", e)
                    }
                }

                val exchangedTokens = tokens
                    ?: return@withContext AppResult.Error(
                        exception = lastError ?: Exception("Authorization failed"),
                        message = when (lastError) {
                            is UnknownHostException -> "Network unavailable. Check your internet connection and try again."
                            is SocketTimeoutException -> "Connection timed out. Check your internet connection and try again."
                            else -> lastError?.message ?: "Authorization failed"
                        }
                    )

                // Save tokens immediately (before userinfo fetch)
                prefs.edit()
                    .putString(KEY_REFRESH_TOKEN, exchangedTokens.refreshToken)
                    .putString(KEY_ACCESS_TOKEN, exchangedTokens.accessToken)
                    .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + exchangedTokens.expiresInMs)
                    .apply()

                // Fetch email (best-effort, not required for auth to succeed)
                val email = try {
                    fetchUserEmail(exchangedTokens.accessToken)
                } catch (e: Exception) {
                    Log.w(TAG, "Could not fetch user email (non-fatal)", e)
                    null
                }
                if (email != null) {
                    prefs.edit().putString(KEY_EMAIL, email).apply()
                }

                Log.i(TAG, "Authorization successful for ${email ?: "(email unknown)"}")
                AppResult.Success(email ?: "Authorized")
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { serverSocket.close() }
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
        return Uri.parse(AUTH_URL).buildUpon()
            .appendQueryParameter("client_id", clientId)
            .appendQueryParameter("redirect_uri", redirectUri)
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("scope", SCOPES.joinToString(" "))
            .appendQueryParameter("access_type", "offline")
            .appendQueryParameter("prompt", "consent")
            .build()
            .toString()
    }

    internal fun waitForAuthCode(serverSocket: ServerSocket): String? {
        serverSocket.soTimeout = AUTH_TIMEOUT_MS
        return try {
            val socket = serverSocket.accept()
            try {
                val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
                val requestLine = reader.readLine() ?: ""
                val code = parseAuthCode(requestLine)

                val html = if (code != null) {
                    "<html><body><h2>Authorization complete</h2>" +
                        "<p>You can close this tab and return to the app.</p></body></html>"
                } else {
                    "<html><body><h2>Authorization failed</h2>" +
                        "<p>Please close this tab and try again.</p></body></html>"
                }
                val response = "HTTP/1.1 200 OK\r\n" +
                    "Content-Type: text/html; charset=utf-8\r\n" +
                    "Connection: close\r\n\r\n$html"
                socket.getOutputStream().write(response.toByteArray())
                socket.getOutputStream().flush()
                code
            } finally {
                socket.close()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Loopback server error", e)
            null
        }
    }

    private fun parseAuthCode(requestLine: String): String? {
        val pathAndQuery = requestLine.split(" ").getOrNull(1) ?: return null
        val queryStart = pathAndQuery.indexOf('?')
        if (queryStart < 0) return null
        val query = pathAndQuery.substring(queryStart + 1)
        return query.split("&")
            .map { it.split("=", limit = 2) }
            .find { it[0] == "code" }
            ?.getOrNull(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
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

            if (!response.isSuccessful) {
                throw IOException("Token exchange failed (${response.code}): $responseBody")
            }

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            TokenResponse(
                accessToken = jsonObj["access_token"]!!.jsonPrimitive.content,
                refreshToken = jsonObj["refresh_token"]?.jsonPrimitive?.content ?: "",
                expiresInMs = (jsonObj["expires_in"]!!.jsonPrimitive.long) * 1000
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

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            TokenResponse(
                accessToken = jsonObj["access_token"]!!.jsonPrimitive.content,
                refreshToken = refreshToken,
                expiresInMs = (jsonObj["expires_in"]!!.jsonPrimitive.long) * 1000
            )
        }
    }

    private suspend fun fetchUserEmail(accessToken: String): String? {
        return withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(USERINFO_URL)
                .addHeader("Authorization", "Bearer $accessToken")
                .build()

            val response = okHttpClient.newCall(request).execute()
            val responseBody = response.body?.string() ?: return@withContext null

            val jsonObj = json.parseToJsonElement(responseBody).jsonObject
            jsonObj["email"]?.jsonPrimitive?.content
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

    fun clearAllCredentials() {
        prefs.edit()
            .remove(KEY_CLIENT_ID)
            .remove(KEY_CLIENT_SECRET)
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
