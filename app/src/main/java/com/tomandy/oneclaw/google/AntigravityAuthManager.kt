package com.tomandy.oneclaw.google

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
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
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * OAuth2 auth manager for Google Antigravity (Cloud Code Assist).
 *
 * Uses the Gemini CLI OAuth app credentials with PKCE to authenticate
 * via loopback redirect. After authentication, discovers the user's
 * Google Cloud project ID for quota tracking.
 */
class AntigravityAuthManager(
    private val context: Context,
    private val credentialVault: CredentialVault
) {

    companion object {
        private const val TAG = "AntigravityAuth"

        private val CLIENT_ID = String(
            Base64.getDecoder().decode(
                "MTA3MTAwNjA2MDU5MS10bWhzc2luMmgyMWxjcm" +
                    "UyMzV2dG9sb2poNGc0MDNlcC5hcHBzLmdvb2ds" +
                    "ZXVzZXJjb250ZW50LmNvbQ=="
            )
        )
        private val CLIENT_SECRET = String(
            Base64.getDecoder().decode(
                "R09DU1BYLUs1OEZXUjQ4NkxkTEoxbUxCOHNYQzR6NnFEQWY="
            )
        )

        private const val AUTH_ENDPOINT =
            "https://accounts.google.com/o/oauth2/v2/auth"
        private const val TOKEN_ENDPOINT =
            "https://oauth2.googleapis.com/token"
        private const val PROJECT_DISCOVERY_URL =
            "https://cloudcode-pa.googleapis.com/v1internal:loadCodeAssist"
        private const val FALLBACK_PROJECT_ID = "rising-fact-p41fc"

        private val SCOPES = listOf(
            "https://www.googleapis.com/auth/cloud-platform",
            "https://www.googleapis.com/auth/userinfo.email",
            "https://www.googleapis.com/auth/userinfo.profile",
            "https://www.googleapis.com/auth/cclog",
            "https://www.googleapis.com/auth/experimentsandconfigs"
        )

        private const val KEY_ACCESS_TOKEN = "antigravity_access_token"
        private const val KEY_REFRESH_TOKEN = "antigravity_refresh_token"
        private const val KEY_TOKEN_EXPIRY = "antigravity_token_expiry"
        private const val KEY_PROJECT_ID = "antigravity_project_id"
        private const val KEY_EMAIL = "antigravity_email"

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L
        private const val AUTH_TIMEOUT_MS = 120_000
    }

    private val httpClient = OkHttpClient()
    private val refreshMutex = Mutex()
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Run the full authorization flow:
     * 1. Generate PKCE verifier and challenge
     * 2. Start a loopback HTTP server
     * 3. Open browser to Google's consent screen
     * 4. Capture the redirect with the auth code
     * 5. Exchange the code for tokens (with PKCE verifier)
     * 6. Fetch user email and discover project ID
     *
     * @param launchBrowser called on main thread to open the auth URL
     * @return null on success, or an error message on failure
     */
    suspend fun authorize(launchBrowser: (Intent) -> Unit): String? {
        val verifier = generateCodeVerifier()
        val challenge = generateCodeChallenge(verifier)

        val server = withContext(Dispatchers.IO) {
            ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        }
        val port = server.localPort
        val redirectUri = "http://127.0.0.1:$port"

        try {
            val authUrl = Uri.parse(AUTH_ENDPOINT).buildUpon()
                .appendQueryParameter("client_id", CLIENT_ID)
                .appendQueryParameter("redirect_uri", redirectUri)
                .appendQueryParameter("response_type", "code")
                .appendQueryParameter("scope", SCOPES.joinToString(" "))
                .appendQueryParameter("access_type", "offline")
                .appendQueryParameter("prompt", "consent")
                .appendQueryParameter("code_challenge", challenge)
                .appendQueryParameter("code_challenge_method", "S256")
                .build()
                .toString()

            withContext(Dispatchers.Main) {
                launchBrowser(Intent(Intent.ACTION_VIEW, Uri.parse(authUrl)))
            }

            return withContext(NonCancellable) {
                val code = withContext(Dispatchers.IO) {
                    server.soTimeout = AUTH_TIMEOUT_MS
                    try {
                        val socket = server.accept()
                        try {
                            val reader = BufferedReader(
                                InputStreamReader(socket.getInputStream())
                            )
                            val requestLine = reader.readLine() ?: ""
                            val authCode = parseAuthCode(requestLine)

                            val html = if (authCode != null) {
                                "<html><body><h2>Authorization complete</h2>" +
                                    "<p>You can close this tab and " +
                                    "return to OneClaw.</p></body></html>"
                            } else {
                                "<html><body><h2>Authorization failed</h2>" +
                                    "<p>Please close this tab and " +
                                    "try again.</p></body></html>"
                            }
                            val response = "HTTP/1.1 200 OK\r\n" +
                                "Content-Type: text/html; charset=utf-8\r\n" +
                                "Connection: close\r\n\r\n$html"
                            socket.getOutputStream()
                                .write(response.toByteArray())
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
                    return@withContext "No authorization code received " +
                        "(timed out or cancelled)"
                }

                var lastError: String? = null
                repeat(3) { attempt ->
                    if (attempt > 0) {
                        Log.d(
                            TAG,
                            "Token exchange retry $attempt after: $lastError"
                        )
                        delay(3000)
                    }
                    val error = exchangeCodeForTokens(
                        code, redirectUri, verifier
                    )
                    if (error == null) return@withContext null
                    lastError = error
                }
                lastError
            }
        } finally {
            withContext(Dispatchers.IO + NonCancellable) { server.close() }
        }
    }

    private fun generateCodeVerifier(): String {
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private fun generateCodeChallenge(verifier: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(verifier.toByteArray())
        return Base64.getUrlEncoder().withoutPadding()
            .encodeToString(digest)
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
            ?.let { URLDecoder.decode(it, "UTF-8") }
    }

    private suspend fun exchangeCodeForTokens(
        code: String,
        redirectUri: String,
        verifier: String
    ): String? {
        return withContext(Dispatchers.IO) {
            try {
                val tokenBody = FormBody.Builder()
                    .add("code", code)
                    .add("client_id", CLIENT_ID)
                    .add("client_secret", CLIENT_SECRET)
                    .add("redirect_uri", redirectUri)
                    .add("grant_type", "authorization_code")
                    .add("code_verifier", verifier)
                    .build()

                val tokenRequest = Request.Builder()
                    .url(TOKEN_ENDPOINT)
                    .post(tokenBody)
                    .build()

                val tokenResponse = httpClient.newCall(tokenRequest).execute()
                val responseBody = tokenResponse.body?.string()
                    ?: return@withContext "Empty token response"

                if (!tokenResponse.isSuccessful) {
                    Log.e(
                        TAG,
                        "Token exchange HTTP ${tokenResponse.code}: " +
                            responseBody
                    )
                    return@withContext "Token exchange failed " +
                        "(${tokenResponse.code}): $responseBody"
                }

                val tokenJson = json.parseToJsonElement(responseBody).jsonObject
                val accessToken = tokenJson["access_token"]
                    ?.jsonPrimitive?.content
                    ?: return@withContext "No access_token in response"
                val refreshToken = tokenJson["refresh_token"]
                    ?.jsonPrimitive?.content
                    ?: return@withContext "No refresh_token in response"
                val expiresIn = tokenJson["expires_in"]
                    ?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

                // Fetch user email
                val email = fetchUserEmail(accessToken)

                // Discover project ID
                val projectId = fetchProjectId(accessToken)

                // Persist tokens
                credentialVault.saveApiKey(KEY_REFRESH_TOKEN, refreshToken)
                credentialVault.saveApiKey(KEY_ACCESS_TOKEN, accessToken)
                val expiryMs =
                    System.currentTimeMillis() + (expiresIn * 1000)
                credentialVault.saveApiKey(
                    KEY_TOKEN_EXPIRY, expiryMs.toString()
                )
                credentialVault.saveApiKey(KEY_PROJECT_ID, projectId)
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

    private fun fetchUserEmail(accessToken: String): String? {
        return try {
            val request = Request.Builder()
                .url("https://www.googleapis.com/oauth2/v2/userinfo")
                .addHeader("Authorization", "Bearer $accessToken")
                .build()
            val response = httpClient.newCall(request).execute()
            val body = response.body?.string() ?: return null
            val userInfo = json.parseToJsonElement(body).jsonObject
            userInfo["email"]?.jsonPrimitive?.content
        } catch (e: Exception) {
            Log.w(TAG, "Failed to fetch user email", e)
            null
        }
    }

    private fun fetchProjectId(accessToken: String): String {
        try {
            val requestBody = """
                {
                    "metadata": {
                        "ideType": "IDE_UNSPECIFIED",
                        "platform": "PLATFORM_UNSPECIFIED",
                        "pluginType": "GEMINI"
                    }
                }
            """.trimIndent()

            val request = Request.Builder()
                .url(PROJECT_DISCOVERY_URL)
                .post(
                    requestBody.toRequestBody(
                        "application/json".toMediaType()
                    )
                )
                .addHeader("Authorization", "Bearer $accessToken")
                .addHeader(
                    "X-Goog-Api-Client",
                    "google-cloud-sdk vscode_cloudshelleditor/0.1"
                )
                .addHeader(
                    "Client-Metadata",
                    """{"ideType":"IDE_UNSPECIFIED",""" +
                        """"platform":"PLATFORM_UNSPECIFIED",""" +
                        """"pluginType":"GEMINI"}"""
                )
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.w(
                    TAG,
                    "Project discovery failed: ${response.code}"
                )
                return FALLBACK_PROJECT_ID
            }

            val body = response.body?.string() ?: return FALLBACK_PROJECT_ID
            val data = json.parseToJsonElement(body).jsonObject
            val project = data["cloudaicompanionProject"]

            return when {
                project is kotlinx.serialization.json.JsonPrimitive &&
                    project.isString ->
                    project.content

                project is kotlinx.serialization.json.JsonObject ->
                    project["id"]?.jsonPrimitive?.content
                        ?: FALLBACK_PROJECT_ID

                else -> FALLBACK_PROJECT_ID
            }
        } catch (e: Exception) {
            Log.w(TAG, "Project discovery failed", e)
            return FALLBACK_PROJECT_ID
        }
    }

    /**
     * Get a valid access token, refreshing if expired.
     * Returns null if the user is not signed in.
     */
    suspend fun getAccessToken(): String? {
        val refreshToken = credentialVault.getApiKey(KEY_REFRESH_TOKEN)
            ?: return null

        val cachedToken = credentialVault.getApiKey(KEY_ACCESS_TOKEN)
        val expiryStr = credentialVault.getApiKey(KEY_TOKEN_EXPIRY)
        val expiry = expiryStr?.toLongOrNull() ?: 0L

        if (cachedToken != null &&
            System.currentTimeMillis() < expiry - TOKEN_EXPIRY_MARGIN_MS
        ) {
            return cachedToken
        }

        return refreshMutex.withLock {
            // Double-check after acquiring lock
            val recheckedToken = credentialVault.getApiKey(KEY_ACCESS_TOKEN)
            val recheckedExpiry = credentialVault.getApiKey(KEY_TOKEN_EXPIRY)
                ?.toLongOrNull() ?: 0L
            if (recheckedToken != null &&
                System.currentTimeMillis() <
                recheckedExpiry - TOKEN_EXPIRY_MARGIN_MS
            ) {
                return@withLock recheckedToken
            }

            withContext(Dispatchers.IO) {
                try {
                    val body = FormBody.Builder()
                        .add("client_id", CLIENT_ID)
                        .add("client_secret", CLIENT_SECRET)
                        .add("refresh_token", refreshToken)
                        .add("grant_type", "refresh_token")
                        .build()

                    val request = Request.Builder()
                        .url(TOKEN_ENDPOINT)
                        .post(body)
                        .build()

                    val response = httpClient.newCall(request).execute()
                    val responseJson = json.parseToJsonElement(
                        response.body?.string()
                            ?: throw Exception("Empty refresh response")
                    ).jsonObject

                    val newToken = responseJson["access_token"]
                        ?.jsonPrimitive?.content
                        ?: throw Exception(
                            "No access_token in refresh response"
                        )
                    val expiresIn = responseJson["expires_in"]
                        ?.jsonPrimitive?.content?.toLongOrNull() ?: 3600L

                    credentialVault.saveApiKey(KEY_ACCESS_TOKEN, newToken)
                    val newExpiry =
                        System.currentTimeMillis() + (expiresIn * 1000)
                    credentialVault.saveApiKey(
                        KEY_TOKEN_EXPIRY, newExpiry.toString()
                    )

                    Log.d(TAG, "Token refreshed successfully")
                    newToken
                } catch (e: Exception) {
                    Log.e(TAG, "Token refresh failed", e)
                    null
                }
            }
        }
    }

    /**
     * Get the stored project ID.
     */
    suspend fun getProjectId(): String? {
        return credentialVault.getApiKey(KEY_PROJECT_ID)
    }

    /**
     * Check if the user has an active Antigravity session.
     */
    suspend fun isSignedIn(): Boolean {
        return !credentialVault.getApiKey(KEY_REFRESH_TOKEN).isNullOrBlank()
    }

    /**
     * Get the connected account email.
     */
    suspend fun getAccountEmail(): String? {
        return credentialVault.getApiKey(KEY_EMAIL)
    }

    /**
     * Sign out: clear all stored tokens.
     */
    suspend fun signOut() {
        val token = credentialVault.getApiKey(KEY_ACCESS_TOKEN)

        credentialVault.deleteApiKey(KEY_REFRESH_TOKEN)
        credentialVault.deleteApiKey(KEY_ACCESS_TOKEN)
        credentialVault.deleteApiKey(KEY_TOKEN_EXPIRY)
        credentialVault.deleteApiKey(KEY_PROJECT_ID)
        credentialVault.deleteApiKey(KEY_EMAIL)

        // Best-effort server-side revocation
        if (token != null) {
            withContext(Dispatchers.IO) {
                try {
                    val request = Request.Builder()
                        .url(
                            "https://oauth2.googleapis.com/revoke?token=$token"
                        )
                        .post(FormBody.Builder().build())
                        .build()
                    httpClient.newCall(request).execute()
                } catch (e: Exception) {
                    Log.w(
                        TAG,
                        "Token revocation failed (best-effort)",
                        e
                    )
                }
            }
        }
    }
}
