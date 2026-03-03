# RFC-030: Google Workspace Tools

## Document Information
- **RFC ID**: RFC-030
- **Related PRD**: [FEAT-030 (Google Workspace Tools)](../../prd/features/FEAT-030-google-workspace.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-012 (JS Tool Engine)](RFC-012-js-tool-engine.md), [RFC-018 (JS Tool Group)](RFC-018-js-tool-group.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

OneClaw's tool system supports JS tool groups (RFC-018) -- JSON array manifests paired with JS implementation files that expose multiple tools under a single file pair. The app also has an established bridge injection pattern for providing Kotlin-backed capabilities to JS tools (FetchBridge, FsBridge, ConsoleBridge, etc.).

The predecessor project oneclaw-1 has a proven Google Workspace plugin system with ~89 tools across 10 Google services, using BYOK (Bring Your Own Key) OAuth authentication. These tools have been battle-tested and cover Gmail, Calendar, Tasks, Contacts, Drive, Docs, Sheets, Slides, Forms, and Gmail Settings.

RFC-030 ports this entire Google Workspace integration to shadow-4, adapting from oneclaw-1's plugin architecture (single `execute()` entry point, `oneclaw.*` namespace) to shadow-4's JS tool group architecture (named function dispatch, Web Fetch API, dedicated bridges).

### Goals

1. Implement `GoogleAuthManager` -- port the BYOK loopback OAuth flow from oneclaw-1's `OAuthGoogleAuthManager`
2. Implement `GoogleAuthBridge` -- new QuickJS bridge for `google.getAccessToken()` and `google.getAccountEmail()`
3. Implement `FileTransferBridge` -- new QuickJS bridge for `downloadToFile()` and `uploadMultipart()` (needed by Drive)
4. Modify `JsExecutionEngine` to inject the two new bridges
5. Create 10 JS tool group asset pairs (JSON + JS) for all Google Workspace services
6. Implement Settings UI -- `GoogleAuthScreen` and `GoogleAuthViewModel` for Google Account management
7. Wire DI, navigation, and registration

### Non-Goals

- Google service account authentication (server-to-server)
- Google Workspace Admin SDK
- Multi-account support
- OAuth scope granularity (selective scopes)
- Offline caching of Google data
- Real-time push notifications from Google services
- Google Maps, YouTube, or other non-Workspace APIs

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                      Settings Layer                               │
│  GoogleAuthScreen ──> GoogleAuthViewModel                        │
│       │                      │                                    │
│       │  Save credentials    │  signIn() / signOut()             │
│       v                      v                                    │
│  GoogleAuthManager [NEW - Kotlin]                                │
│       │                                                           │
│       +── EncryptedSharedPreferences (tokens, credentials)       │
│       +── OkHttpClient (token exchange, refresh, revoke)         │
│       +── Loopback HTTP server (OAuth redirect capture)          │
│       +── Browser intent (Google consent screen)                 │
│                                                                   │
├──────────────────────────────────────────────────────────────────┤
│                      Chat Layer (RFC-001)                          │
│  SendMessageUseCase                                               │
│       │                                                           │
│       │  tool call: gmail_search(query="...")                     │
│       v                                                           │
├──────────────────────────────────────────────────────────────────┤
│                  Tool Execution Engine (RFC-004)                   │
│  executeTool(name, params, availableToolIds)                      │
│       │                                                           │
│       v                                                           │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                             │  │
│  │  ┌─────────────────────┐  ┌──────────────────────────┐    │  │
│  │  │ google_gmail (18)    │  │ google_gmail_settings(11)│    │  │
│  │  │ google_calendar (11) │  │ google_tasks (7)         │    │  │
│  │  │ google_contacts (7)  │  │ google_drive (13)        │    │  │
│  │  │ google_docs (6)      │  │ google_sheets (7)        │    │  │
│  │  │ google_slides (6)    │  │ google_forms (3)         │    │  │
│  │  └──────────┬──────────┘  └──────────┬───────────────┘    │  │
│  │             │  JS Tool Groups (JSON+JS pairs)              │  │
│  └─────────────┼──────────────────────────────────────────────┘  │
│                │                                                  │
│                v                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │              JsExecutionEngine [MODIFIED]                   │  │
│  │                                                             │  │
│  │  QuickJS Context (per execution, isolated)                 │  │
│  │  ┌─────────────────────────────────────────────────────┐   │  │
│  │  │  Bridges (injected):                                 │   │  │
│  │  │  - ConsoleBridge         (existing)                  │   │  │
│  │  │  - FsBridge              (existing)                  │   │  │
│  │  │  - FetchBridge           (existing)                  │   │  │
│  │  │  - TimeBridge            (existing)                  │   │  │
│  │  │  - LibraryBridge         (existing)                  │   │  │
│  │  │  - GoogleAuthBridge      [NEW]                       │   │  │
│  │  │  - FileTransferBridge    [NEW]                       │   │  │
│  │  ├─────────────────────────────────────────────────────┤   │  │
│  │  │  JS Wrapper Code:                                    │   │  │
│  │  │  - fetch()               (FetchBridge)               │   │  │
│  │  │  - lib()                 (LibraryBridge)             │   │  │
│  │  │  - google.*              (GoogleAuthBridge) [NEW]    │   │  │
│  │  │  - downloadToFile()      (FileTransferBridge) [NEW] │   │  │
│  │  │  - uploadMultipart()     (FileTransferBridge) [NEW] │   │  │
│  │  ├─────────────────────────────────────────────────────┤   │  │
│  │  │  Tool JS Code (e.g., google_gmail.js):               │   │  │
│  │  │  - async function gmailSearch(params) { ... }        │   │  │
│  │  │  - async function gmailGetMessage(params) { ... }    │   │  │
│  │  │  - ... (named functions per tool)                    │   │  │
│  │  └─────────────────────────────────────────────────────┘   │  │
│  │       │                                                     │  │
│  │       │  fetch() with Bearer token                         │  │
│  │       v                                                     │  │
│  │  Google Workspace REST APIs                                │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### Core Components

**New:**
1. `GoogleAuthManager` -- Kotlin class managing BYOK OAuth flow, token storage and refresh
2. `GoogleAuthBridge` -- QuickJS bridge exposing `google.getAccessToken()` and `google.getAccountEmail()`
3. `FileTransferBridge` -- QuickJS bridge exposing `downloadToFile()` and `uploadMultipart()`
4. `GoogleAuthScreen` -- Compose screen for Google Account configuration
5. `GoogleAuthViewModel` -- ViewModel for Google Account settings state
6. 10 JS tool group asset pairs (JSON + JS) for Google Workspace services

**Modified:**
7. `JsExecutionEngine` -- Inject GoogleAuthBridge and FileTransferBridge
8. `ToolModule` -- Register GoogleAuthManager, pass to JsExecutionEngine
9. `AppModule` -- Register GoogleAuthManager singleton
10. `SettingsScreen` -- Add "Google Account" navigation item
11. `Routes` -- Add GoogleAuth route
12. `NavGraph` -- Wire GoogleAuth destination

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── data/
│   │   └── security/
│   │       └── GoogleAuthManager.kt              # NEW
│   ├── tool/
│   │   └── js/
│   │       ├── JsExecutionEngine.kt              # MODIFIED
│   │       └── bridge/
│   │           ├── GoogleAuthBridge.kt            # NEW
│   │           └── FileTransferBridge.kt          # NEW
│   ├── feature/
│   │   └── settings/
│   │       ├── GoogleAuthScreen.kt                # NEW
│   │       └── GoogleAuthViewModel.kt             # NEW
│   ├── di/
│   │   ├── AppModule.kt                           # MODIFIED
│   │   └── ToolModule.kt                          # MODIFIED
│   └── navigation/
│       ├── Routes.kt                              # MODIFIED
│       └── NavGraph.kt                            # MODIFIED
├── assets/
│   └── js/
│       └── tools/
│           ├── google_gmail.json                  # NEW (18 tools)
│           ├── google_gmail.js                    # NEW
│           ├── google_gmail_settings.json         # NEW (11 tools)
│           ├── google_gmail_settings.js           # NEW
│           ├── google_calendar.json               # NEW (11 tools)
│           ├── google_calendar.js                 # NEW
│           ├── google_tasks.json                  # NEW (7 tools)
│           ├── google_tasks.js                    # NEW
│           ├── google_contacts.json               # NEW (7 tools)
│           ├── google_contacts.js                 # NEW
│           ├── google_drive.json                  # NEW (13 tools)
│           ├── google_drive.js                    # NEW
│           ├── google_docs.json                   # NEW (6 tools)
│           ├── google_docs.js                     # NEW
│           ├── google_sheets.json                 # NEW (7 tools)
│           ├── google_sheets.js                   # NEW
│           ├── google_slides.json                 # NEW (6 tools)
│           ├── google_slides.js                   # NEW
│           ├── google_forms.json                  # NEW (3 tools)
│           └── google_forms.js                    # NEW

app/src/test/kotlin/com/oneclaw/shadow/
    ├── data/
    │   └── security/
    │       └── GoogleAuthManagerTest.kt            # NEW
    └── tool/
        └── js/
            └── bridge/
                ├── GoogleAuthBridgeTest.kt         # NEW
                └── FileTransferBridgeTest.kt       # NEW
```

### GoogleAuthManager

```kotlin
/**
 * Located in: data/security/GoogleAuthManager.kt
 *
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
    }

    private val prefs: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            PREFS_NAME,
            MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC),
            context,
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
            ?: return AppResult.Error(Exception("Client ID not configured"))
        val clientSecret = getClientSecret()
            ?: return AppResult.Error(Exception("Client Secret not configured"))

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
            AppResult.Error(e)
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

    private fun buildConsentUrl(clientId: String, redirectUri: String): String {
        val scopeString = SCOPES.joinToString(" ")
        return "$AUTH_URL?" +
            "client_id=${Uri.encode(clientId)}" +
            "&redirect_uri=${Uri.encode(redirectUri)}" +
            "&response_type=code" +
            "&scope=${Uri.encode(scopeString)}" +
            "&access_type=offline" +
            "&prompt=consent"
    }

    private fun waitForAuthCode(serverSocket: ServerSocket): String {
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

    private suspend fun refreshAccessToken(
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

    private fun clearTokens() {
        prefs.edit()
            .remove(KEY_REFRESH_TOKEN)
            .remove(KEY_ACCESS_TOKEN)
            .remove(KEY_TOKEN_EXPIRY)
            .remove(KEY_EMAIL)
            .apply()
    }

    private data class TokenResponse(
        val accessToken: String,
        val refreshToken: String,
        val expiresInMs: Long
    )
}
```

### GoogleAuthBridge

```kotlin
/**
 * Located in: tool/js/bridge/GoogleAuthBridge.kt
 *
 * Injects Google authentication functions into the QuickJS context.
 * Provides google.getAccessToken() and google.getAccountEmail() to JS tools.
 *
 * Follows the same pattern as FetchBridge:
 * - inject() registers low-level async functions (__googleGetAccessToken, __googleGetAccountEmail)
 * - GOOGLE_AUTH_WRAPPER_JS provides the high-level JS API (google.*)
 */
object GoogleAuthBridge {

    /**
     * JS wrapper code that provides the google.* API.
     * Must be evaluated in the QuickJS context before tool code runs.
     */
    val GOOGLE_AUTH_WRAPPER_JS = """
        const google = {
            async getAccessToken() {
                const token = await __googleGetAccessToken();
                if (!token) {
                    throw new Error(
                        "Not signed in to Google. Connect your Google account in Settings."
                    );
                }
                return token;
            },
            async getAccountEmail() {
                return await __googleGetAccountEmail();
            }
        };
    """.trimIndent()

    /**
     * Inject the low-level async functions into the QuickJS context.
     *
     * @param quickJs The QuickJS context to inject into.
     * @param googleAuthManager The GoogleAuthManager instance for token access.
     */
    fun inject(quickJs: QuickJs, googleAuthManager: GoogleAuthManager?) {
        quickJs.asyncFunction("__googleGetAccessToken") { _: Array<Any?> ->
            googleAuthManager?.getAccessToken() ?: ""
        }

        quickJs.asyncFunction("__googleGetAccountEmail") { _: Array<Any?> ->
            googleAuthManager?.getAccountEmail() ?: ""
        }
    }
}
```

### FileTransferBridge

```kotlin
/**
 * Located in: tool/js/bridge/FileTransferBridge.kt
 *
 * Injects file transfer functions into the QuickJS context.
 * Provides downloadToFile() and uploadMultipart() for Google Drive
 * and other tools that need binary file operations.
 *
 * These operations cannot be done through the standard fetch() bridge
 * because fetch() operates on text content (truncated at 100KB).
 * File transfer needs to handle binary data of arbitrary size.
 */
object FileTransferBridge {

    /**
     * JS wrapper code that provides downloadToFile() and uploadMultipart().
     * Must be evaluated in the QuickJS context before tool code runs.
     */
    val FILE_TRANSFER_WRAPPER_JS = """
        async function downloadToFile(url, savePath, headers) {
            const headersJson = headers ? JSON.stringify(headers) : "{}";
            const resultJson = await __downloadToFile(url, savePath, headersJson);
            return JSON.parse(resultJson);
        }

        async function uploadMultipart(url, parts, headers) {
            const partsJson = JSON.stringify(parts);
            const headersJson = headers ? JSON.stringify(headers) : "{}";
            const resultJson = await __uploadMultipart(url, partsJson, headersJson);
            return JSON.parse(resultJson);
        }
    """.trimIndent()

    /**
     * Inject the low-level async functions into the QuickJS context.
     */
    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // __downloadToFile(url, savePath, headersJson) -> Promise<String>
        // Downloads a URL to a local file. Returns JSON: {success, path, size, error}
        quickJs.asyncFunction("__downloadToFile") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: url required")
            val savePath = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: savePath required")
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"

            performDownload(okHttpClient, url, savePath, headersJson)
        }

        // __uploadMultipart(url, partsJson, headersJson) -> Promise<String>
        // Uploads a file as multipart/related. Returns JSON response.
        quickJs.asyncFunction("__uploadMultipart") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("uploadMultipart: url required")
            val partsJson = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("uploadMultipart: parts required")
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"

            performUpload(okHttpClient, url, partsJson, headersJson)
        }
    }

    /**
     * Download a URL to a local file path.
     * Returns JSON: { "success": true, "path": "/...", "size": 12345 }
     *          or:  { "success": false, "error": "..." }
     */
    private suspend fun performDownload(
        okHttpClient: OkHttpClient,
        url: String,
        savePath: String,
        headersJson: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val headers = Json.parseToJsonElement(headersJson).jsonObject
                val requestBuilder = Request.Builder().url(url)
                headers.entries.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value.jsonPrimitive.content)
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    return@withContext buildJsonObject {
                        put("success", false)
                        put("error", "HTTP ${response.code}: ${response.message}")
                    }.toString()
                }

                val file = File(savePath)
                file.parentFile?.mkdirs()
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                buildJsonObject {
                    put("success", true)
                    put("path", file.absolutePath)
                    put("size", file.length())
                }.toString()
            } catch (e: Exception) {
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Download failed")
                }.toString()
            }
        }
    }

    /**
     * Upload a file as multipart/related request.
     * partsJson format:
     * [
     *   { "type": "json", "contentType": "application/json", "body": "{...}" },
     *   { "type": "file", "contentType": "application/octet-stream", "path": "/path/to/file" }
     * ]
     */
    private suspend fun performUpload(
        okHttpClient: OkHttpClient,
        url: String,
        partsJson: String,
        headersJson: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val parts = Json.parseToJsonElement(partsJson).jsonArray
                val headers = Json.parseToJsonElement(headersJson).jsonObject

                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)

                for (part in parts) {
                    val partObj = part.jsonObject
                    val contentType = partObj["contentType"]?.jsonPrimitive?.content
                        ?: "application/octet-stream"
                    val mediaType = contentType.toMediaTypeOrNull()

                    when (partObj["type"]?.jsonPrimitive?.content) {
                        "json" -> {
                            val body = partObj["body"]?.jsonPrimitive?.content ?: "{}"
                            multipartBuilder.addPart(body.toRequestBody(mediaType))
                        }
                        "file" -> {
                            val filePath = partObj["path"]?.jsonPrimitive?.content
                                ?: throw IllegalArgumentException("File part missing 'path'")
                            val file = File(filePath)
                            multipartBuilder.addPart(file.asRequestBody(mediaType))
                        }
                    }
                }

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(multipartBuilder.build())

                headers.entries.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value.jsonPrimitive.content)
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""

                buildJsonObject {
                    put("status", response.code)
                    put("body", responseBody)
                    put("ok", response.isSuccessful)
                }.toString()
            } catch (e: Exception) {
                buildJsonObject {
                    put("status", 0)
                    put("body", "")
                    put("ok", false)
                    put("error", e.message ?: "Upload failed")
                }.toString()
            }
        }
    }
}
```

### JsExecutionEngine Modifications

```kotlin
/**
 * Located in: tool/js/JsExecutionEngine.kt
 *
 * MODIFIED: Add GoogleAuthManager and GoogleAuthBridge + FileTransferBridge injection.
 *
 * Changes:
 * 1. New constructor parameter: googleAuthManager (nullable for backward compat)
 * 2. GoogleAuthBridge.inject() in bridge injection block
 * 3. FileTransferBridge.inject() in bridge injection block
 * 4. GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS in wrapper code
 * 5. FileTransferBridge.FILE_TRANSFER_WRAPPER_JS in wrapper code
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient,
    private val libraryBridge: LibraryBridge,
    private val googleAuthManager: GoogleAuthManager? = null  // NEW (nullable)
) {
    // ... companion object unchanged ...

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        jsSource: String?,
        toolName: String,
        functionName: String?,
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            // Existing bridges
            ConsoleBridge.inject(this, toolName)
            FsBridge.inject(this)
            FetchBridge.inject(this, okHttpClient)
            TimeBridge.inject(this)
            libraryBridge.inject(this)

            // NEW: Google Auth bridge
            GoogleAuthBridge.inject(this, googleAuthManager)

            // NEW: File Transfer bridge
            FileTransferBridge.inject(this, okHttpClient)

            val jsCode = jsSource ?: File(jsFilePath).readText()
            val paramsJson = anyToJsonElement(paramsWithEnv).toString()
            val entryFunction = functionName ?: "execute"

            val wrapperCode = """
                ${FetchBridge.FETCH_WRAPPER_JS}
                ${libraryBridge.LIB_WRAPPER_JS}
                ${GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS}
                ${FileTransferBridge.FILE_TRANSFER_WRAPPER_JS}

                $jsCode

                const __params__ = JSON.parse(${quoteJsString(paramsJson)});
                const __result__ = await $entryFunction(__params__);
                if (__result__ === null || __result__ === undefined) {
                    "";
                } else if (typeof __result__ === "string") {
                    __result__;
                } else {
                    JSON.stringify(__result__);
                }
            """.trimIndent()

            evaluate<String>(wrapperCode)
        }

        return ToolResult.success(result ?: "")
    }

    // ... rest unchanged ...
}
```

### JS Tool Group Assets

Each Google service has a JSON manifest (array format) and a JS implementation file. The JSON defines tool metadata; the JS exports named async functions.

#### Example: google_gmail.json (Partial)

```json
[
  {
    "name": "gmail_search",
    "description": "Search Gmail messages using Gmail query syntax (e.g., 'from:user@example.com', 'is:unread', 'subject:hello'). Returns message list with id, snippet, from, subject, date.",
    "function": "gmailSearch",
    "parameters": {
      "properties": {
        "query": {
          "type": "string",
          "description": "Gmail search query (same syntax as Gmail search bar)"
        },
        "max_results": {
          "type": "integer",
          "description": "Maximum number of results to return (default: 20, max: 100)"
        }
      },
      "required": ["query"]
    },
    "timeoutSeconds": 30
  },
  {
    "name": "gmail_get_message",
    "description": "Get the full content of a specific Gmail message by ID. Returns subject, from, to, cc, date, body text, labels, and attachment info.",
    "function": "gmailGetMessage",
    "parameters": {
      "properties": {
        "message_id": {
          "type": "string",
          "description": "The Gmail message ID"
        }
      },
      "required": ["message_id"]
    },
    "timeoutSeconds": 30
  },
  {
    "name": "gmail_send",
    "description": "Send a new email. Supports plain text and HTML body. Returns the sent message ID and thread ID.",
    "function": "gmailSend",
    "parameters": {
      "properties": {
        "to": {
          "type": "string",
          "description": "Recipient email address(es), comma-separated for multiple"
        },
        "subject": {
          "type": "string",
          "description": "Email subject"
        },
        "body": {
          "type": "string",
          "description": "Email body (plain text or HTML)"
        },
        "cc": {
          "type": "string",
          "description": "CC recipients, comma-separated (optional)"
        },
        "bcc": {
          "type": "string",
          "description": "BCC recipients, comma-separated (optional)"
        },
        "html": {
          "type": "boolean",
          "description": "If true, body is treated as HTML. Default: false"
        }
      },
      "required": ["to", "subject", "body"]
    },
    "timeoutSeconds": 30
  }
]
```

> Full JSON manifests for all 10 services follow the same pattern. Each tool entry has `name`, `description`, `function`, `parameters`, and `timeoutSeconds`.

#### Example: google_gmail.js (Partial)

```javascript
/**
 * Google Gmail tool group for OneClaw.
 *
 * Uses:
 * - google.getAccessToken() -- from GoogleAuthBridge
 * - fetch() -- from FetchBridge (Web Fetch API style)
 * - console.log/error() -- from ConsoleBridge
 *
 * All functions receive a params object and return a result object or string.
 */

var GMAIL_API = "https://www.googleapis.com/gmail/v1/users/me";

async function gmailFetch(method, path, body) {
    var token = await google.getAccessToken();
    var options = {
        method: method,
        headers: {
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json"
        }
    };
    if (body) {
        options.body = JSON.stringify(body);
    }
    var resp = await fetch(GMAIL_API + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("Gmail API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}

async function gmailSearch(params) {
    var query = params.query;
    var maxResults = params.max_results || 20;
    var path = "/messages?q=" + encodeURIComponent(query) +
        "&maxResults=" + maxResults;

    var data = await gmailFetch("GET", path);
    if (!data.messages || data.messages.length === 0) {
        return { messages: [], total: 0 };
    }

    var results = [];
    for (var i = 0; i < data.messages.length; i++) {
        var msg = await gmailFetch("GET", "/messages/" + data.messages[i].id +
            "?format=metadata&metadataHeaders=Subject&metadataHeaders=From&metadataHeaders=Date");
        var headers = msg.payload.headers;
        results.push({
            id: msg.id,
            threadId: msg.threadId,
            snippet: msg.snippet,
            subject: findHeader(headers, "Subject"),
            from: findHeader(headers, "From"),
            date: findHeader(headers, "Date"),
            labelIds: msg.labelIds || []
        });
    }
    return { messages: results, total: data.resultSizeEstimate || results.length };
}

async function gmailGetMessage(params) {
    var msg = await gmailFetch("GET", "/messages/" + params.message_id + "?format=full");
    var headers = msg.payload.headers;
    return {
        id: msg.id,
        threadId: msg.threadId,
        subject: findHeader(headers, "Subject"),
        from: findHeader(headers, "From"),
        to: findHeader(headers, "To"),
        cc: findHeader(headers, "Cc"),
        date: findHeader(headers, "Date"),
        body: extractBody(msg.payload),
        labelIds: msg.labelIds || [],
        attachments: extractAttachments(msg.payload)
    };
}

async function gmailSend(params) {
    var mime = buildMimeMessage(params);
    var encoded = btoa(unescape(encodeURIComponent(mime)))
        .replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
    var data = await gmailFetch("POST", "/messages/send", { raw: encoded });
    return { id: data.id, threadId: data.threadId };
}

// ... remaining Gmail functions follow the same pattern ...

// --- Helpers ---

function findHeader(headers, name) {
    for (var i = 0; i < headers.length; i++) {
        if (headers[i].name.toLowerCase() === name.toLowerCase()) {
            return headers[i].value;
        }
    }
    return "";
}

function extractBody(payload) {
    if (payload.body && payload.body.data) {
        return decodeBase64Url(payload.body.data);
    }
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.mimeType === "text/plain" && part.body && part.body.data) {
                return decodeBase64Url(part.body.data);
            }
        }
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.mimeType === "text/html" && part.body && part.body.data) {
                return decodeBase64Url(part.body.data);
            }
        }
    }
    return "";
}

function extractAttachments(payload) {
    var attachments = [];
    if (payload.parts) {
        for (var i = 0; i < payload.parts.length; i++) {
            var part = payload.parts[i];
            if (part.filename && part.filename.length > 0) {
                attachments.push({
                    filename: part.filename,
                    mimeType: part.mimeType,
                    size: part.body.size,
                    attachmentId: part.body.attachmentId
                });
            }
        }
    }
    return attachments;
}

function decodeBase64Url(data) {
    var str = data.replace(/-/g, "+").replace(/_/g, "/");
    return decodeURIComponent(escape(atob(str)));
}

function buildMimeMessage(params) {
    var lines = [];
    lines.push("To: " + params.to);
    if (params.cc) lines.push("Cc: " + params.cc);
    if (params.bcc) lines.push("Bcc: " + params.bcc);
    lines.push("Subject: " + params.subject);
    if (params.html) {
        lines.push("Content-Type: text/html; charset=UTF-8");
    } else {
        lines.push("Content-Type: text/plain; charset=UTF-8");
    }
    lines.push("");
    lines.push(params.body);
    return lines.join("\r\n");
}
```

#### JS API Adaptation Summary

The key adaptation from oneclaw-1 to shadow-4:

| Aspect | oneclaw-1 | shadow-4 |
|--------|-----------|----------|
| Entry point | Single `execute(toolName, args)` with switch/case | Named async functions (e.g., `gmailSearch(params)`) |
| HTTP | `oneclaw.http.fetch(method, url, body, ct, headers)` returns `{status, body, error}` as JSON string | `fetch(url, {method, body, headers})` returns Response object with `.ok`, `.status`, `.text()`, `.json()` |
| Auth | `oneclaw.google.getAccessToken()` | `google.getAccessToken()` |
| File download | `oneclaw.http.downloadToFile(url, path, headers)` | `downloadToFile(url, path, headers)` |
| File upload | `oneclaw.http.uploadMultipart(url, parts, headers)` | `uploadMultipart(url, parts, headers)` |
| File system | `oneclaw.fs.writeFile(path, content)` | `fs.writeFile(path, content)` |
| Logging | `oneclaw.log.error(msg)` | `console.error(msg)` |
| Response parsing | `var resp = JSON.parse(oneclaw.http.fetch(...)); var data = JSON.parse(resp.body);` | `var resp = await fetch(...); var data = await resp.json();` |

### GoogleAuthScreen

```kotlin
/**
 * Located in: feature/settings/GoogleAuthScreen.kt
 *
 * Compose screen for Google Account configuration.
 * Allows users to enter OAuth credentials and sign in/out.
 */
@Composable
fun GoogleAuthScreen(
    viewModel: GoogleAuthViewModel = koinViewModel(),
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Google Account") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // OAuth Credentials Section
            Text("OAuth Credentials", style = MaterialTheme.typography.titleMedium)
            Text(
                "Enter your GCP Desktop OAuth Client ID and Secret.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            OutlinedTextField(
                value = uiState.clientId,
                onValueChange = viewModel::onClientIdChanged,
                label = { Text("Client ID") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true
            )

            OutlinedTextField(
                value = uiState.clientSecret,
                onValueChange = viewModel::onClientSecretChanged,
                label = { Text("Client Secret") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = PasswordVisualTransformation()
            )

            Button(
                onClick = viewModel::saveCredentials,
                modifier = Modifier.fillMaxWidth(),
                enabled = uiState.clientId.isNotBlank() && uiState.clientSecret.isNotBlank()
            ) {
                Text("Save Credentials")
            }

            HorizontalDivider()

            // Sign In / Sign Out Section
            if (uiState.isSignedIn) {
                // Signed-in state
                Text("Connected", style = MaterialTheme.typography.titleMedium)
                Text(
                    uiState.accountEmail ?: "",
                    style = MaterialTheme.typography.bodyLarge
                )
                OutlinedButton(
                    onClick = viewModel::signOut,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading
                ) {
                    Text("Sign Out")
                }
            } else {
                // Not signed in
                Button(
                    onClick = viewModel::signIn,
                    modifier = Modifier.fillMaxWidth(),
                    enabled = uiState.hasCredentials && !uiState.isLoading
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("Sign In with Google")
                    }
                }
            }

            // Error display
            uiState.error?.let { error ->
                Text(
                    error,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}
```

### GoogleAuthViewModel

```kotlin
/**
 * Located in: feature/settings/GoogleAuthViewModel.kt
 *
 * ViewModel for the Google Account settings screen.
 */
class GoogleAuthViewModel(
    private val googleAuthManager: GoogleAuthManager
) : ViewModel() {

    data class UiState(
        val clientId: String = "",
        val clientSecret: String = "",
        val isSignedIn: Boolean = false,
        val accountEmail: String? = null,
        val hasCredentials: Boolean = false,
        val isLoading: Boolean = false,
        val error: String? = null
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadState()
    }

    private fun loadState() {
        _uiState.update {
            it.copy(
                clientId = googleAuthManager.getClientId() ?: "",
                clientSecret = googleAuthManager.getClientSecret() ?: "",
                isSignedIn = googleAuthManager.isSignedIn(),
                accountEmail = googleAuthManager.getAccountEmail(),
                hasCredentials = googleAuthManager.hasOAuthCredentials()
            )
        }
    }

    fun onClientIdChanged(value: String) {
        _uiState.update { it.copy(clientId = value) }
    }

    fun onClientSecretChanged(value: String) {
        _uiState.update { it.copy(clientSecret = value) }
    }

    fun saveCredentials() {
        googleAuthManager.saveOAuthCredentials(
            _uiState.value.clientId,
            _uiState.value.clientSecret
        )
        _uiState.update { it.copy(hasCredentials = true, error = null) }
    }

    fun signIn() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            when (val result = googleAuthManager.authorize()) {
                is AppResult.Success -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            isSignedIn = true,
                            accountEmail = result.data
                        )
                    }
                }
                is AppResult.Error -> {
                    _uiState.update {
                        it.copy(
                            isLoading = false,
                            error = result.exception.message ?: "Sign-in failed"
                        )
                    }
                }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            googleAuthManager.signOut()
            _uiState.update {
                it.copy(
                    isLoading = false,
                    isSignedIn = false,
                    accountEmail = null
                )
            }
        }
    }
}
```

### DI Wiring

#### AppModule Changes

```kotlin
// In AppModule.kt -- add GoogleAuthManager singleton

val appModule = module {
    // ... existing registrations ...

    // RFC-030: Google OAuth Manager
    single { GoogleAuthManager(androidContext(), get()) }
}
```

#### ToolModule Changes

```kotlin
// In ToolModule.kt -- pass GoogleAuthManager to JsExecutionEngine

val toolModule = module {
    // MODIFIED: JsExecutionEngine now takes 3 parameters
    single { JsExecutionEngine(get(), get(), get()) }
    //                         ^       ^      ^
    //                  OkHttpClient  LibBridge  GoogleAuthManager

    // ... rest unchanged ...
}
```

### Navigation Changes

#### Routes.kt

```kotlin
// Add to Routes sealed class:

@Serializable
data object GoogleAuth : Route
```

#### NavGraph.kt

```kotlin
// Add to NavHost composable block:

composable<Routes.GoogleAuth> {
    GoogleAuthScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

### SettingsScreen Changes

```kotlin
// In SettingsScreen.kt -- add Google Account item

// Add between "Backup & Sync" and "Theme" items:
SettingsItem(
    icon = Icons.Default.AccountCircle,
    title = "Google Account",
    subtitle = if (googleAuthManager.isSignedIn())
        googleAuthManager.getAccountEmail() ?: "Connected"
    else "Not connected",
    onClick = { navController.navigate(Routes.GoogleAuth) }
)
```

### Complete JS Tool Groups

Below is a summary of each JS tool group. Each group follows the same pattern: a service-specific fetch helper, named async functions for each tool, and helper utilities.

#### Service Fetch Helper Pattern

Each JS file has a service-specific fetch helper that handles Bearer auth, content type, and error parsing:

```javascript
var API_BASE = "https://www.googleapis.com/...";

async function serviceFetch(method, path, body) {
    var token = await google.getAccessToken();
    var options = {
        method: method,
        headers: {
            "Authorization": "Bearer " + token,
            "Content-Type": "application/json"
        }
    };
    if (body) {
        options.body = JSON.stringify(body);
    }
    var resp = await fetch(API_BASE + path, options);
    if (!resp.ok) {
        var errorText = await resp.text();
        throw new Error("API error (" + resp.status + "): " + errorText);
    }
    return await resp.json();
}
```

#### All 10 Tool Groups

| # | File Pair | Tools | API Base URL | Key Features |
|---|-----------|-------|-------------|--------------|
| 1 | `google_gmail.json/js` | 18 | `gmail/v1/users/me` | MIME encoding, base64url, attachment handling |
| 2 | `google_gmail_settings.json/js` | 11 | `gmail/v1/users/me/settings` | Filters, vacation responder, forwarding |
| 3 | `google_calendar.json/js` | 11 | `calendar/v3` | Date/time handling, timezone support, recurring events |
| 4 | `google_tasks.json/js` | 7 | `tasks/v1` | Task lists, subtasks via parent, completion |
| 5 | `google_contacts.json/js` | 7 | `people/v1` | Person fields, etag for updates, directory |
| 6 | `google_drive.json/js` | 13 | `drive/v3` + `upload/drive/v3` | File transfer bridge for download/upload, export |
| 7 | `google_docs.json/js` | 6 | `docs/v1` | Document structure, text extraction, batch update |
| 8 | `google_sheets.json/js` | 7 | `sheets/v4` | Range notation, value input options, batch update |
| 9 | `google_slides.json/js` | 6 | `slides/v1` | Page elements, layout types, text extraction |
| 10 | `google_forms.json/js` | 3 | `forms/v1` | Read-only (body + responses), question types |

#### google_drive.js: File Transfer Integration

Google Drive tools uniquely require the FileTransferBridge for download and upload:

```javascript
// drive_download -- uses downloadToFile() from FileTransferBridge
async function driveDownload(params) {
    var token = await google.getAccessToken();
    var fileId = params.file_id;
    var savePath = params.save_path;

    var result = await downloadToFile(
        DRIVE_API + "/files/" + fileId + "?alt=media",
        savePath,
        { "Authorization": "Bearer " + token }
    );
    if (!result.success) {
        throw new Error("Download failed: " + result.error);
    }
    return { path: result.path, size: result.size };
}

// drive_upload -- uses uploadMultipart() from FileTransferBridge
async function driveUpload(params) {
    var token = await google.getAccessToken();
    var metadata = {
        name: params.name,
        mimeType: params.mime_type || "application/octet-stream"
    };
    if (params.parent_id) {
        metadata.parents = [params.parent_id];
    }

    var result = await uploadMultipart(
        UPLOAD_API + "/files?uploadType=multipart&fields=" + DETAIL_FIELDS,
        [
            { type: "json", contentType: "application/json", body: JSON.stringify(metadata) },
            { type: "file", contentType: metadata.mimeType, path: params.file_path }
        ],
        { "Authorization": "Bearer " + token }
    );
    if (!result.ok) {
        throw new Error("Upload failed: " + (result.error || result.body));
    }
    return JSON.parse(result.body);
}
```

## Implementation Plan

### Phase 1: OAuth Infrastructure

1. Create `GoogleAuthManager.kt` -- port BYOK OAuth flow from oneclaw-1
2. Create `GoogleAuthBridge.kt` -- QuickJS bridge for `google.*` API
3. Create `FileTransferBridge.kt` -- QuickJS bridge for file transfer
4. Modify `JsExecutionEngine.kt` -- inject new bridges
5. Update `AppModule.kt` and `ToolModule.kt` -- DI wiring
6. Write unit tests for GoogleAuthManager and bridges

### Phase 2: JS Tool Groups

1. Create all 10 JSON manifest files (tool definitions)
2. Create all 10 JS implementation files (ported from oneclaw-1)
3. Adapt JS code from oneclaw-1 namespace to shadow-4 bridges
4. Verify all tool groups load correctly via JsToolLoader

### Phase 3: Settings UI

1. Create `GoogleAuthViewModel.kt`
2. Create `GoogleAuthScreen.kt`
3. Add Routes.GoogleAuth to navigation
4. Add Google Account item to SettingsScreen
5. Wire navigation in NavGraph

### Phase 4: Testing & Verification

1. Run Layer 1A tests (`./gradlew test`)
2. Run Layer 1B instrumented tests (requires emulator)
3. Manual testing with real Google account
4. Test all 10 service tool groups end-to-end
5. Verify token refresh and error handling

## Data Model

No database model changes. All Google OAuth data is stored in EncryptedSharedPreferences.

### Storage Keys

| Key | Type | Description |
|-----|------|-------------|
| `google_oauth_client_id` | String | User's GCP OAuth Client ID |
| `google_oauth_client_secret` | String | User's GCP OAuth Client Secret |
| `google_oauth_refresh_token` | String | OAuth refresh token (long-lived) |
| `google_oauth_access_token` | String | OAuth access token (short-lived, ~1 hour) |
| `google_oauth_token_expiry` | Long | Token expiry timestamp (milliseconds) |
| `google_oauth_email` | String | Signed-in user's email address |

## API Design

### GoogleAuthBridge JS API

```
google.getAccessToken() -> Promise<string>
  Returns a valid access token, refreshing if necessary.
  Throws Error if not signed in.

google.getAccountEmail() -> Promise<string>
  Returns the signed-in user's email address.
  Returns empty string if not signed in.
```

### FileTransferBridge JS API

```
downloadToFile(url, savePath, headers) -> Promise<{success, path, size, error}>
  Downloads a URL to a local file.
  Returns result object with success status.

uploadMultipart(url, parts, headers) -> Promise<{status, body, ok, error}>
  Uploads a file as multipart request.
  parts: [{type: "json"|"file", contentType, body|path}]
  Returns response object.
```

## Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| Not signed in | No Google account connected | JS throws: `"Not signed in to Google. Connect your Google account in Settings."` |
| Token refresh failed | Refresh token revoked externally | Clear tokens, return auth error |
| OAuth cancelled | User denied consent | Return error to UI, no tokens stored |
| Credential not configured | Missing Client ID or Secret | UI disables sign-in button |
| Google API 400 | Invalid request parameters | Pass error message from API to tool result |
| Google API 401 | Token expired mid-request | Token should have been refreshed; retry may help |
| Google API 403 | Insufficient permissions | Pass error message (e.g., "contacts_directory" on consumer accounts) |
| Google API 404 | Resource not found | Pass error message to tool result |
| Google API 429 | Rate limit exceeded | Pass rate limit error to tool result |
| Network error | No connectivity | JS fetch throws, caught as tool error |
| Loopback port conflict | Port already in use | ServerSocket(0) picks random available port |
| OAuth timeout | User doesn't complete consent in 2 minutes | ServerSocket timeout, return error |

## Security Considerations

1. **Credential storage**: OAuth credentials (Client ID, Secret, tokens) are stored in EncryptedSharedPreferences using Android KeyStore. Never stored in Room or logged.
2. **Loopback redirect**: OAuth redirect uses `http://127.0.0.1:{random_port}`, which cannot be intercepted by other apps (localhost binding).
3. **Token exposure**: Access tokens are passed to JS tools via the bridge but only exist in the ephemeral QuickJS context (destroyed after each execution). They are never persisted in JS.
4. **HTTPS only**: All Google API calls use HTTPS. The fetch bridge enforces this.
5. **Token revocation**: Sign-out revokes the token server-side (best-effort) before clearing local storage.
6. **Scope limitation**: Only the 11 scopes listed are requested. No admin or write-all scopes.
7. **BYOK model**: Users control their own credentials and can revoke access at any time via their GCP console.
8. **Input validation**: All JS tool parameters are validated before API calls (required fields, type checks).

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| OAuth flow (user interaction) | 5-15s | Includes browser + consent |
| Token refresh | < 1s | Single HTTPS POST |
| JS tool group load (from assets) | < 100ms | JSON parse + JS source read |
| QuickJS context creation + bridge injection | < 50ms | Per tool call |
| Google API call | 0.5-3s | Depends on API + payload |
| Drive file download (1MB) | 2-5s | Depends on network |
| Drive file upload (1MB) | 3-8s | Multipart upload |

### Memory

| Resource | Peak Usage | Notes |
|----------|-----------|-------|
| QuickJS context | ~1-2MB | Per execution, destroyed after |
| GoogleAuthManager | ~10KB | Singleton, minimal state |
| JS source (largest group: gmail.js) | ~15KB | Loaded from assets |
| Token strings | < 1KB | In EncryptedSharedPreferences |

## Testing Strategy

### Unit Tests (Layer 1A)

**GoogleAuthManagerTest.kt:**
- `testSaveAndLoadCredentials` -- Verify credential storage and retrieval
- `testIsSignedIn_withRefreshToken` -- Returns true when refresh token exists
- `testIsSignedIn_withoutRefreshToken` -- Returns false when no tokens
- `testGetAccessToken_cached` -- Returns cached token when not expired
- `testGetAccessToken_expired` -- Triggers refresh when token expired
- `testSignOut_clearsTokens` -- Verify all token keys removed
- `testHasOAuthCredentials` -- True when both Client ID and Secret present

**GoogleAuthBridgeTest.kt:**
- `testInject_registersAsyncFunctions` -- Verify __googleGetAccessToken and __googleGetAccountEmail registered
- `testWrapperJs_syntaxValid` -- Verify GOOGLE_AUTH_WRAPPER_JS parses without errors
- `testGetAccessToken_returnsToken` -- Verify bridge returns GoogleAuthManager token
- `testGetAccessToken_whenNull_returnsEmpty` -- Verify null manager returns empty string

**FileTransferBridgeTest.kt:**
- `testInject_registersAsyncFunctions` -- Verify __downloadToFile and __uploadMultipart registered
- `testWrapperJs_syntaxValid` -- Verify FILE_TRANSFER_WRAPPER_JS parses without errors
- `testDownload_success` -- Verify file is saved to correct path
- `testDownload_networkError` -- Verify error result returned
- `testUpload_success` -- Verify multipart request constructed correctly

### Integration Tests (Layer 1B)

Manual verification with real Google account:
- Full OAuth flow (sign-in, token refresh, sign-out)
- Gmail: search, read, send, draft operations
- Calendar: list, create, update, delete events
- Tasks: list, create, complete, delete tasks
- Contacts: search, list, create contacts
- Drive: list, upload, download files
- Docs: get, create, insert text
- Sheets: read, write, append values
- Slides: get, add slides
- Forms: get form structure, list responses

## Alternatives Considered

### 1. Google Sign-In SDK (GMS)

**Approach**: Use Google's official Sign-In SDK (GMS/Firebase) for authentication.
**Rejected because**: Requires Google Play Services (not available on all devices), ties the app to Google's SDK lifecycle, and doesn't support the BYOK model that gives users control over their own credentials.

### 2. Implement tools as Kotlin built-in tools

**Approach**: Create Kotlin Tool implementations for each Google service instead of JS tool groups.
**Rejected because**: ~89 tools would create massive code duplication. The oneclaw-1 JS implementations are proven and portable. JS tool groups (RFC-018) provide a clean, maintainable pattern. Adapting existing JS code is faster and less error-prone than rewriting in Kotlin.

### 3. Single monolithic JS file for all Google tools

**Approach**: Put all ~89 tools in one JS file with a single JSON manifest.
**Rejected because**: Would exceed the MAX_GROUP_SIZE limit, make maintenance difficult, and load all Google code even when only one service is needed. Splitting by service (10 groups) is natural and manageable.

### 4. Custom TabNet protocol for OAuth

**Approach**: Use a custom URI scheme (e.g., `oneclawshadow://oauth/callback`) instead of loopback redirect.
**Rejected because**: Custom URI schemes require AndroidManifest registration and are less secure (other apps can register the same scheme). Loopback redirect is the standard approach for desktop/CLI OAuth and is supported by Google's guidelines.

## Dependencies

### External Dependencies

No new external dependencies. Uses:
- **OkHttpClient** (already available via networkModule)
- **EncryptedSharedPreferences** (already available, used for API key storage)
- **QuickJS** (`com.dokar.quickjs`, already available via tool system)
- **Google Workspace REST APIs** (external HTTP endpoints, no SDK dependency)

### Internal Dependencies

- `Tool` interface, `ToolRegistry`, `ToolExecutionEngine` from `tool/` package
- `JsExecutionEngine`, `FetchBridge`, bridge pattern from `tool/js/` package
- `JsToolLoader` for asset-based tool group loading
- `AppResult<T>` from `core/util/`
- `EncryptedSharedPreferences` pattern from `data/security/`
- Android `Context` (via Koin DI)
- `OkHttpClient` (via Koin networkModule)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
