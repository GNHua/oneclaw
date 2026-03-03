# RFC-030：Google Workspace 工具

## 文档信息
- **RFC ID**：RFC-030
- **关联 PRD**：[FEAT-030（Google Workspace 工具）](../../prd/features/FEAT-030-google-workspace.md)
- **关联架构**：[RFC-000（整体架构）](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**：[RFC-004（工具系统）](RFC-004-tool-system.md)、[RFC-012（JS 工具引擎）](RFC-012-js-tool-engine.md)、[RFC-018（JS 工具组）](RFC-018-js-tool-group.md)
- **创建日期**：2026-03-01
- **最后更新**：2026-03-01
- **状态**：草稿
- **作者**：TBD

## 概述

### 背景

OneClaw 的工具系统支持 JS 工具组（RFC-018）——以 JSON 数组清单文件配合 JS 实现文件的形式，通过单一文件对暴露多个工具。该应用还具备成熟的桥接注入模式，可为 JS 工具提供 Kotlin 支撑的能力（FetchBridge、FsBridge、ConsoleBridge 等）。

前身项目 oneclaw-1 拥有经过验证的 Google Workspace 插件系统，涵盖 10 项 Google 服务、约 89 个工具，采用 BYOK（自带密钥）OAuth 认证方式。这些工具久经考验，覆盖 Gmail、日历、任务、联系人、云端硬盘、文档、表格、幻灯片、表单以及 Gmail 设置。

RFC-030 将整套 Google Workspace 集成迁移至 shadow-4，从 oneclaw-1 的插件架构（单一 `execute()` 入口点、`oneclaw.*` 命名空间）适配为 shadow-4 的 JS 工具组架构（具名函数分发、Web Fetch API、专用桥接）。

### 目标

1. 实现 `GoogleAuthManager` —— 从 oneclaw-1 的 `OAuthGoogleAuthManager` 移植 BYOK 回环 OAuth 流程
2. 实现 `GoogleAuthBridge` —— 新的 QuickJS 桥接，提供 `google.getAccessToken()` 和 `google.getAccountEmail()`
3. 实现 `FileTransferBridge` —— 新的 QuickJS 桥接，提供 `downloadToFile()` 和 `uploadMultipart()`（云端硬盘所需）
4. 修改 `JsExecutionEngine` 以注入两个新桥接
5. 为所有 Google Workspace 服务创建 10 个 JS 工具组资产对（JSON + JS）
6. 实现设置 UI —— `GoogleAuthScreen` 和 `GoogleAuthViewModel`，用于 Google 账户管理
7. 接入 DI、导航和注册

### 非目标

- Google 服务账户认证（服务器间通信）
- Google Workspace Admin SDK
- 多账户支持
- OAuth 权限范围精细化（选择性范围）
- Google 数据的离线缓存
- 来自 Google 服务的实时推送通知
- Google 地图、YouTube 或其他非 Workspace API

## 技术设计

### 架构概览

```
┌──────────────────────────────────────────────────────────────────┐
│                      设置层                                       │
│  GoogleAuthScreen ──> GoogleAuthViewModel                        │
│       │                      │                                    │
│       │  保存凭据            │  signIn() / signOut()              │
│       v                      v                                    │
│  GoogleAuthManager [新增 - Kotlin]                               │
│       │                                                           │
│       +── EncryptedSharedPreferences（令牌、凭据）               │
│       +── OkHttpClient（令牌交换、刷新、撤销）                   │
│       +── 回环 HTTP 服务器（OAuth 重定向捕获）                   │
│       +── 浏览器 Intent（Google 授权页面）                       │
│                                                                   │
├──────────────────────────────────────────────────────────────────┤
│                      聊天层（RFC-001）                            │
│  SendMessageUseCase                                               │
│       │                                                           │
│       │  工具调用：gmail_search(query="...")                      │
│       v                                                           │
├──────────────────────────────────────────────────────────────────┤
│                  工具执行引擎（RFC-004）                           │
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
│  │             │  JS 工具组（JSON+JS 文件对）                  │  │
│  └─────────────┼──────────────────────────────────────────────┘  │
│                │                                                  │
│                v                                                  │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │              JsExecutionEngine [已修改]                     │  │
│  │                                                             │  │
│  │  QuickJS 上下文（每次执行独立隔离）                         │  │
│  │  ┌─────────────────────────────────────────────────────┐   │  │
│  │  │  桥接（注入）：                                       │   │  │
│  │  │  - ConsoleBridge         （已有）                    │   │  │
│  │  │  - FsBridge              （已有）                    │   │  │
│  │  │  - FetchBridge           （已有）                    │   │  │
│  │  │  - TimeBridge            （已有）                    │   │  │
│  │  │  - LibraryBridge         （已有）                    │   │  │
│  │  │  - GoogleAuthBridge      [新增]                      │   │  │
│  │  │  - FileTransferBridge    [新增]                      │   │  │
│  │  ├─────────────────────────────────────────────────────┤   │  │
│  │  │  JS 包装代码：                                        │   │  │
│  │  │  - fetch()               (FetchBridge)               │   │  │
│  │  │  - lib()                 (LibraryBridge)             │   │  │
│  │  │  - google.*              (GoogleAuthBridge) [新增]   │   │  │
│  │  │  - downloadToFile()      (FileTransferBridge) [新增] │   │  │
│  │  │  - uploadMultipart()     (FileTransferBridge) [新增] │   │  │
│  │  ├─────────────────────────────────────────────────────┤   │  │
│  │  │  工具 JS 代码（如 google_gmail.js）：                 │   │  │
│  │  │  - async function gmailSearch(params) { ... }        │   │  │
│  │  │  - async function gmailGetMessage(params) { ... }    │   │  │
│  │  │  - ...（每个工具对应具名函数）                        │   │  │
│  │  └─────────────────────────────────────────────────────┘   │  │
│  │       │                                                     │  │
│  │       │  fetch() 携带 Bearer 令牌                           │  │
│  │       v                                                     │  │
│  │  Google Workspace REST API                                  │  │
│  └────────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘
```

### 核心组件

**新增：**
1. `GoogleAuthManager` —— Kotlin 类，管理 BYOK OAuth 流程、令牌存储与刷新
2. `GoogleAuthBridge` —— QuickJS 桥接，暴露 `google.getAccessToken()` 和 `google.getAccountEmail()`
3. `FileTransferBridge` —— QuickJS 桥接，暴露 `downloadToFile()` 和 `uploadMultipart()`
4. `GoogleAuthScreen` —— 用于 Google 账户配置的 Compose 界面
5. `GoogleAuthViewModel` —— Google 账户设置状态的 ViewModel
6. 10 个 JS 工具组资产对（JSON + JS），覆盖各 Google Workspace 服务

**修改：**
7. `JsExecutionEngine` —— 注入 GoogleAuthBridge 和 FileTransferBridge
8. `ToolModule` —— 注册 GoogleAuthManager，传入 JsExecutionEngine
9. `AppModule` —— 注册 GoogleAuthManager 单例
10. `SettingsScreen` —— 添加「Google 账户」导航项
11. `Routes` —— 添加 GoogleAuth 路由
12. `NavGraph` —— 接入 GoogleAuth 目标页面

## 详细设计

### 目录结构（新增及变更文件）

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── data/
│   │   └── security/
│   │       └── GoogleAuthManager.kt              # 新增
│   ├── tool/
│   │   └── js/
│   │       ├── JsExecutionEngine.kt              # 修改
│   │       └── bridge/
│   │           ├── GoogleAuthBridge.kt            # 新增
│   │           └── FileTransferBridge.kt          # 新增
│   ├── feature/
│   │   └── settings/
│   │       ├── GoogleAuthScreen.kt                # 新增
│   │       └── GoogleAuthViewModel.kt             # 新增
│   ├── di/
│   │   ├── AppModule.kt                           # 修改
│   │   └── ToolModule.kt                          # 修改
│   └── navigation/
│       ├── Routes.kt                              # 修改
│       └── NavGraph.kt                            # 修改
├── assets/
│   └── js/
│       └── tools/
│           ├── google_gmail.json                  # 新增（18 个工具）
│           ├── google_gmail.js                    # 新增
│           ├── google_gmail_settings.json         # 新增（11 个工具）
│           ├── google_gmail_settings.js           # 新增
│           ├── google_calendar.json               # 新增（11 个工具）
│           ├── google_calendar.js                 # 新增
│           ├── google_tasks.json                  # 新增（7 个工具）
│           ├── google_tasks.js                    # 新增
│           ├── google_contacts.json               # 新增（7 个工具）
│           ├── google_contacts.js                 # 新增
│           ├── google_drive.json                  # 新增（13 个工具）
│           ├── google_drive.js                    # 新增
│           ├── google_docs.json                   # 新增（6 个工具）
│           ├── google_docs.js                     # 新增
│           ├── google_sheets.json                 # 新增（7 个工具）
│           ├── google_sheets.js                   # 新增
│           ├── google_slides.json                 # 新增（6 个工具）
│           ├── google_slides.js                   # 新增
│           ├── google_forms.json                  # 新增（3 个工具）
│           └── google_forms.js                    # 新增

app/src/test/kotlin/com/oneclaw/shadow/
    ├── data/
    │   └── security/
    │       └── GoogleAuthManagerTest.kt            # 新增
    └── tool/
        └── js/
            └── bridge/
                ├── GoogleAuthBridgeTest.kt         # 新增
                └── FileTransferBridgeTest.kt       # 新增
```

### GoogleAuthManager

```kotlin
/**
 * 位置：data/security/GoogleAuthManager.kt
 *
 * 管理 Google Workspace 的 BYOK（自带密钥）OAuth 2.0 流程。
 * 从 oneclaw-1 的 OAuthGoogleAuthManager 移植。
 *
 * 流程：
 * 1. 用户提供 GCP 桌面端 OAuth Client ID + Secret
 * 2. 应用在随机端口启动回环 HTTP 服务器
 * 3. 打开浏览器进入 Google 授权页面
 * 4. 通过重定向捕获授权码
 * 5. 用授权码换取令牌
 * 6. 将令牌存储至 EncryptedSharedPreferences
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

        private const val TOKEN_EXPIRY_MARGIN_MS = 60_000L  // 到期前 60 秒刷新

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

    // --- 公开 API ---

    /**
     * 保存 OAuth 客户端凭据（Client ID + Client Secret）。
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
     * 发起 OAuth 流程：
     * 1. 在随机端口启动回环 HTTP 服务器
     * 2. 构建授权 URL 并打开浏览器
     * 3. 等待带授权码的重定向
     * 4. 用授权码换取令牌
     * 5. 获取用户信息
     * 6. 存储所有数据
     */
    suspend fun authorize(): AppResult<String> {
        val clientId = getClientId()
            ?: return AppResult.Error(Exception("Client ID not configured"))
        val clientSecret = getClientSecret()
            ?: return AppResult.Error(Exception("Client Secret not configured"))

        return try {
            // 在随机可用端口启动回环服务器
            val serverSocket = ServerSocket(0)
            val port = serverSocket.localPort
            val redirectUri = "http://127.0.0.1:$port"

            // 构建授权 URL
            val consentUrl = buildConsentUrl(clientId, redirectUri)

            // 打开浏览器
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)

            // 等待重定向（在 IO 调度器上阻塞）
            val authCode = withContext(Dispatchers.IO) {
                waitForAuthCode(serverSocket)
            }

            // 用授权码换取令牌
            val tokens = exchangeCodeForTokens(authCode, clientId, clientSecret, redirectUri)

            // 获取用户邮箱
            val email = fetchUserEmail(tokens.accessToken)

            // 存储所有数据
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
     * 获取有效的访问令牌，必要时自动刷新。
     * 通过 Mutex 保证线程安全，防止并发刷新风暴。
     */
    suspend fun getAccessToken(): String? {
        if (!isSignedIn()) return null

        return tokenMutex.withLock {
            val cachedToken = prefs.getString(KEY_ACCESS_TOKEN, null)
            val expiry = prefs.getLong(KEY_TOKEN_EXPIRY, 0)

            if (cachedToken != null && System.currentTimeMillis() < expiry - TOKEN_EXPIRY_MARGIN_MS) {
                return@withLock cachedToken
            }

            // 令牌已过期或即将过期 —— 刷新
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
                // 刷新失败时清除令牌（刷新令牌可能已被撤销）
                clearTokens()
                null
            }
        }
    }

    /**
     * 退出登录：尽力在服务端撤销令牌，然后清除本地存储。
     */
    suspend fun signOut() {
        val token = prefs.getString(KEY_ACCESS_TOKEN, null)
            ?: prefs.getString(KEY_REFRESH_TOKEN, null)

        // 尽力撤销服务端令牌
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

    // --- 私有辅助方法 ---

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
        serverSocket.soTimeout = 120_000  // 2 分钟超时
        val socket = serverSocket.accept()
        val reader = socket.getInputStream().bufferedReader()
        val requestLine = reader.readLine()
        // 解析：GET /?code=AUTH_CODE&scope=... HTTP/1.1
        val code = requestLine
            ?.substringAfter("code=", "")
            ?.substringBefore("&")
            ?.substringBefore(" ")
            ?: throw IOException("No auth code in redirect")

        if (code.isBlank()) throw IOException("Empty auth code")

        // 向浏览器发送成功响应
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
                refreshToken = refreshToken,  // 刷新令牌不会轮换
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
 * 位置：tool/js/bridge/GoogleAuthBridge.kt
 *
 * 将 Google 认证函数注入 QuickJS 上下文。
 * 为 JS 工具提供 google.getAccessToken() 和 google.getAccountEmail()。
 *
 * 遵循与 FetchBridge 相同的模式：
 * - inject() 注册底层异步函数（__googleGetAccessToken、__googleGetAccountEmail）
 * - GOOGLE_AUTH_WRAPPER_JS 提供高层 JS API（google.*）
 */
object GoogleAuthBridge {

    /**
     * 提供 google.* API 的 JS 包装代码。
     * 必须在工具代码运行前于 QuickJS 上下文中求值。
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
     * 将底层异步函数注入 QuickJS 上下文。
     *
     * @param quickJs 要注入的 QuickJS 上下文。
     * @param googleAuthManager 用于访问令牌的 GoogleAuthManager 实例。
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
 * 位置：tool/js/bridge/FileTransferBridge.kt
 *
 * 将文件传输函数注入 QuickJS 上下文。
 * 为 Google Drive 及其他需要二进制文件操作的工具提供
 * downloadToFile() 和 uploadMultipart()。
 *
 * 这些操作无法通过标准 fetch() 桥接完成，
 * 因为 fetch() 处理的是文本内容（截断上限为 100KB）。
 * 文件传输需要处理任意大小的二进制数据。
 */
object FileTransferBridge {

    /**
     * 提供 downloadToFile() 和 uploadMultipart() 的 JS 包装代码。
     * 必须在工具代码运行前于 QuickJS 上下文中求值。
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
     * 将底层异步函数注入 QuickJS 上下文。
     */
    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // __downloadToFile(url, savePath, headersJson) -> Promise<String>
        // 将 URL 内容下载至本地文件。返回 JSON：{success, path, size, error}
        quickJs.asyncFunction("__downloadToFile") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: url required")
            val savePath = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: savePath required")
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"

            performDownload(okHttpClient, url, savePath, headersJson)
        }

        // __uploadMultipart(url, partsJson, headersJson) -> Promise<String>
        // 以 multipart/related 方式上传文件。返回 JSON 响应。
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
     * 将 URL 内容下载至本地文件路径。
     * 返回 JSON：{ "success": true, "path": "/...", "size": 12345 }
     *       或：{ "success": false, "error": "..." }
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
     * 以 multipart/related 请求上传文件。
     * partsJson 格式：
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

### JsExecutionEngine 修改

```kotlin
/**
 * 位置：tool/js/JsExecutionEngine.kt
 *
 * 已修改：添加 GoogleAuthManager 以及 GoogleAuthBridge + FileTransferBridge 注入。
 *
 * 变更内容：
 * 1. 新增构造函数参数：googleAuthManager（可为 null，向后兼容）
 * 2. 在桥接注入块中调用 GoogleAuthBridge.inject()
 * 3. 在桥接注入块中调用 FileTransferBridge.inject()
 * 4. 在包装代码中加入 GoogleAuthBridge.GOOGLE_AUTH_WRAPPER_JS
 * 5. 在包装代码中加入 FileTransferBridge.FILE_TRANSFER_WRAPPER_JS
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient,
    private val libraryBridge: LibraryBridge,
    private val googleAuthManager: GoogleAuthManager? = null  // 新增（可为 null）
) {
    // ... companion object 不变 ...

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

            // 已有桥接
            ConsoleBridge.inject(this, toolName)
            FsBridge.inject(this)
            FetchBridge.inject(this, okHttpClient)
            TimeBridge.inject(this)
            libraryBridge.inject(this)

            // 新增：Google Auth 桥接
            GoogleAuthBridge.inject(this, googleAuthManager)

            // 新增：文件传输桥接
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

    // ... 其余部分不变 ...
}
```

### JS 工具组资产

每个 Google 服务对应一个 JSON 清单文件（数组格式）和一个 JS 实现文件。JSON 定义工具元数据，JS 导出具名异步函数。

#### 示例：google_gmail.json（部分）

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

> 所有 10 个服务的完整 JSON 清单遵循相同模式。每个工具条目包含 `name`、`description`、`function`、`parameters` 和 `timeoutSeconds`。

#### 示例：google_gmail.js（部分）

```javascript
/**
 * OneClaw 的 Google Gmail 工具组。
 *
 * 使用：
 * - google.getAccessToken() —— 来自 GoogleAuthBridge
 * - fetch() —— 来自 FetchBridge（Web Fetch API 风格）
 * - console.log/error() —— 来自 ConsoleBridge
 *
 * 所有函数接收 params 对象，返回结果对象或字符串。
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

// ... 其余 Gmail 函数遵循相同模式 ...

// --- 辅助函数 ---

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

#### JS API 适配对比

从 oneclaw-1 到 shadow-4 的关键适配变更：

| 方面 | oneclaw-1 | shadow-4 |
|--------|-----------|----------|
| 入口点 | 单一 `execute(toolName, args)` 配合 switch/case | 具名异步函数（如 `gmailSearch(params)`） |
| HTTP | `oneclaw.http.fetch(method, url, body, ct, headers)` 返回 JSON 字符串 `{status, body, error}` | `fetch(url, {method, body, headers})` 返回 Response 对象，含 `.ok`、`.status`、`.text()`、`.json()` |
| 认证 | `oneclaw.google.getAccessToken()` | `google.getAccessToken()` |
| 文件下载 | `oneclaw.http.downloadToFile(url, path, headers)` | `downloadToFile(url, path, headers)` |
| 文件上传 | `oneclaw.http.uploadMultipart(url, parts, headers)` | `uploadMultipart(url, parts, headers)` |
| 文件系统 | `oneclaw.fs.writeFile(path, content)` | `fs.writeFile(path, content)` |
| 日志 | `oneclaw.log.error(msg)` | `console.error(msg)` |
| 响应解析 | `var resp = JSON.parse(oneclaw.http.fetch(...)); var data = JSON.parse(resp.body);` | `var resp = await fetch(...); var data = await resp.json();` |

### GoogleAuthScreen

```kotlin
/**
 * 位置：feature/settings/GoogleAuthScreen.kt
 *
 * 用于 Google 账户配置的 Compose 界面。
 * 允许用户输入 OAuth 凭据并登录/退出。
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
            // OAuth 凭据区域
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

            // 登录 / 退出区域
            if (uiState.isSignedIn) {
                // 已登录状态
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
                // 未登录状态
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

            // 错误提示
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
 * 位置：feature/settings/GoogleAuthViewModel.kt
 *
 * Google 账户设置界面的 ViewModel。
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

### DI 接入

#### AppModule 变更

```kotlin
// 在 AppModule.kt 中 —— 添加 GoogleAuthManager 单例

val appModule = module {
    // ... 已有注册 ...

    // RFC-030：Google OAuth Manager
    single { GoogleAuthManager(androidContext(), get()) }
}
```

#### ToolModule 变更

```kotlin
// 在 ToolModule.kt 中 —— 将 GoogleAuthManager 传入 JsExecutionEngine

val toolModule = module {
    // 已修改：JsExecutionEngine 现在接受 3 个参数
    single { JsExecutionEngine(get(), get(), get()) }
    //                         ^       ^      ^
    //                  OkHttpClient  LibBridge  GoogleAuthManager

    // ... 其余部分不变 ...
}
```

### 导航变更

#### Routes.kt

```kotlin
// 在 Routes sealed class 中添加：

@Serializable
data object GoogleAuth : Route
```

#### NavGraph.kt

```kotlin
// 在 NavHost composable 块中添加：

composable<Routes.GoogleAuth> {
    GoogleAuthScreen(
        onNavigateBack = { navController.popBackStack() }
    )
}
```

### SettingsScreen 变更

```kotlin
// 在 SettingsScreen.kt 中 —— 添加 Google 账户条目

// 在「备份与同步」与「主题」条目之间添加：
SettingsItem(
    icon = Icons.Default.AccountCircle,
    title = "Google Account",
    subtitle = if (googleAuthManager.isSignedIn())
        googleAuthManager.getAccountEmail() ?: "Connected"
    else "Not connected",
    onClick = { navController.navigate(Routes.GoogleAuth) }
)
```

### 完整 JS 工具组

以下是各 JS 工具组的摘要。每个工具组遵循相同模式：服务专属的 fetch 辅助函数、每个工具对应的具名异步函数，以及辅助工具函数。

#### 服务 fetch 辅助函数模式

每个 JS 文件都有一个服务专属的 fetch 辅助函数，负责处理 Bearer 认证、Content-Type 和错误解析：

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

#### 全部 10 个工具组

| # | 文件对 | 工具数 | API 基础 URL | 主要特性 |
|---|-----------|-------|-------------|--------------|
| 1 | `google_gmail.json/js` | 18 | `gmail/v1/users/me` | MIME 编码、base64url、附件处理 |
| 2 | `google_gmail_settings.json/js` | 11 | `gmail/v1/users/me/settings` | 过滤器、自动回复、转发设置 |
| 3 | `google_calendar.json/js` | 11 | `calendar/v3` | 日期/时间处理、时区支持、重复事件 |
| 4 | `google_tasks.json/js` | 7 | `tasks/v1` | 任务列表、通过 parent 实现子任务、完成状态 |
| 5 | `google_contacts.json/js` | 7 | `people/v1` | Person 字段、etag 更新、通讯录 |
| 6 | `google_drive.json/js` | 13 | `drive/v3` + `upload/drive/v3` | 文件传输桥接用于下载/上传、导出 |
| 7 | `google_docs.json/js` | 6 | `docs/v1` | 文档结构、文本提取、批量更新 |
| 8 | `google_sheets.json/js` | 7 | `sheets/v4` | 范围表示法、值输入选项、批量更新 |
| 9 | `google_slides.json/js` | 6 | `slides/v1` | 页面元素、布局类型、文本提取 |
| 10 | `google_forms.json/js` | 3 | `forms/v1` | 只读（表单结构 + 响应）、问题类型 |

#### google_drive.js：文件传输集成

Google Drive 工具需要唯一使用 FileTransferBridge 来实现下载和上传：

```javascript
// drive_download —— 使用 FileTransferBridge 的 downloadToFile()
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

// drive_upload —— 使用 FileTransferBridge 的 uploadMultipart()
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

## 实施计划

### 阶段一：OAuth 基础设施

1. 创建 `GoogleAuthManager.kt` —— 从 oneclaw-1 移植 BYOK OAuth 流程
2. 创建 `GoogleAuthBridge.kt` —— `google.*` API 的 QuickJS 桥接
3. 创建 `FileTransferBridge.kt` —— 文件传输的 QuickJS 桥接
4. 修改 `JsExecutionEngine.kt` —— 注入新桥接
5. 更新 `AppModule.kt` 和 `ToolModule.kt` —— DI 接入
6. 为 GoogleAuthManager 和桥接编写单元测试

### 阶段二：JS 工具组

1. 创建全部 10 个 JSON 清单文件（工具定义）
2. 创建全部 10 个 JS 实现文件（从 oneclaw-1 移植）
3. 将 JS 代码从 oneclaw-1 命名空间适配为 shadow-4 桥接
4. 通过 JsToolLoader 验证所有工具组能否正确加载

### 阶段三：设置 UI

1. 创建 `GoogleAuthViewModel.kt`
2. 创建 `GoogleAuthScreen.kt`
3. 将 Routes.GoogleAuth 添加至导航
4. 在 SettingsScreen 中添加 Google 账户条目
5. 在 NavGraph 中接入导航

### 阶段四：测试与验证

1. 运行 Layer 1A 测试（`./gradlew test`）
2. 运行 Layer 1B 仪器化测试（需要模拟器）
3. 使用真实 Google 账户进行手动测试
4. 端到端测试全部 10 个服务工具组
5. 验证令牌刷新和错误处理

## 数据模型

无数据库模型变更。所有 Google OAuth 数据均存储在 EncryptedSharedPreferences 中。

### 存储键

| 键 | 类型 | 说明 |
|-----|------|-------------|
| `google_oauth_client_id` | String | 用户的 GCP OAuth Client ID |
| `google_oauth_client_secret` | String | 用户的 GCP OAuth Client Secret |
| `google_oauth_refresh_token` | String | OAuth 刷新令牌（长期有效） |
| `google_oauth_access_token` | String | OAuth 访问令牌（短期有效，约 1 小时） |
| `google_oauth_token_expiry` | Long | 令牌到期时间戳（毫秒） |
| `google_oauth_email` | String | 已登录用户的邮箱地址 |

## API 设计

### GoogleAuthBridge JS API

```
google.getAccessToken() -> Promise<string>
  返回有效的访问令牌，必要时自动刷新。
  未登录时抛出 Error。

google.getAccountEmail() -> Promise<string>
  返回已登录用户的邮箱地址。
  未登录时返回空字符串。
```

### FileTransferBridge JS API

```
downloadToFile(url, savePath, headers) -> Promise<{success, path, size, error}>
  将 URL 内容下载至本地文件。
  返回包含成功状态的结果对象。

uploadMultipart(url, parts, headers) -> Promise<{status, body, ok, error}>
  以 multipart 请求上传文件。
  parts: [{type: "json"|"file", contentType, body|path}]
  返回响应对象。
```

## 错误处理

| 错误 | 原因 | 处理方式 |
|-------|-------|----------|
| 未登录 | 未连接 Google 账户 | JS 抛出：`"Not signed in to Google. Connect your Google account in Settings."` |
| 令牌刷新失败 | 刷新令牌被外部撤销 | 清除令牌，返回认证错误 |
| OAuth 已取消 | 用户拒绝授权 | 向 UI 返回错误，不存储令牌 |
| 凭据未配置 | 缺少 Client ID 或 Secret | UI 禁用登录按钮 |
| Google API 400 | 无效请求参数 | 将 API 错误信息传递至工具结果 |
| Google API 401 | 请求中途令牌过期 | 令牌本应已刷新；重试可能有效 |
| Google API 403 | 权限不足 | 传递错误信息（如消费者账户上的"contacts_directory"） |
| Google API 404 | 资源未找到 | 将错误信息传递至工具结果 |
| Google API 429 | 超出速率限制 | 将速率限制错误传递至工具结果 |
| 网络错误 | 无网络连接 | JS fetch 抛出异常，作为工具错误捕获 |
| 回环端口冲突 | 端口已被占用 | ServerSocket(0) 自动选取随机可用端口 |
| OAuth 超时 | 用户未在 2 分钟内完成授权 | ServerSocket 超时，返回错误 |

## 安全考虑

1. **凭据存储**：OAuth 凭据（Client ID、Secret、令牌）使用 Android KeyStore 存储于 EncryptedSharedPreferences。绝不存入 Room 或写入日志。
2. **回环重定向**：OAuth 重定向使用 `http://127.0.0.1:{random_port}`，其他应用无法拦截（本地回环绑定）。
3. **令牌暴露**：访问令牌通过桥接传递给 JS 工具，但仅存在于短暂的 QuickJS 上下文中（每次执行后销毁）。令牌永远不会在 JS 中持久化。
4. **仅 HTTPS**：所有 Google API 调用均使用 HTTPS。fetch 桥接强制执行此约束。
5. **令牌撤销**：退出登录时在服务端尽力撤销令牌（best-effort），然后再清除本地存储。
6. **权限范围限制**：仅申请列出的 11 个权限范围，不包含管理员或全量写入权限。
7. **BYOK 模型**：用户控制自己的凭据，可随时通过 GCP 控制台撤销访问权限。
8. **输入校验**：所有 JS 工具参数在 API 调用前均经过校验（必填字段、类型检查）。

## 性能

| 操作 | 预期耗时 | 备注 |
|-----------|--------------|-------|
| OAuth 流程（用户交互） | 5-15 秒 | 含浏览器 + 授权 |
| 令牌刷新 | < 1 秒 | 单次 HTTPS POST |
| JS 工具组加载（从 assets） | < 100ms | JSON 解析 + JS 源码读取 |
| QuickJS 上下文创建 + 桥接注入 | < 50ms | 每次工具调用 |
| Google API 调用 | 0.5-3 秒 | 取决于 API 和负载 |
| Drive 文件下载（1MB） | 2-5 秒 | 取决于网络 |
| Drive 文件上传（1MB） | 3-8 秒 | Multipart 上传 |

### 内存

| 资源 | 峰值占用 | 备注 |
|----------|-----------|-------|
| QuickJS 上下文 | ~1-2MB | 每次执行后销毁 |
| GoogleAuthManager | ~10KB | 单例，状态极小 |
| JS 源码（最大组：gmail.js） | ~15KB | 从 assets 加载 |
| 令牌字符串 | < 1KB | 存于 EncryptedSharedPreferences |

## 测试策略

### 单元测试（Layer 1A）

**GoogleAuthManagerTest.kt：**
- `testSaveAndLoadCredentials` —— 验证凭据存储与读取
- `testIsSignedIn_withRefreshToken` —— 存在刷新令牌时返回 true
- `testIsSignedIn_withoutRefreshToken` —— 无令牌时返回 false
- `testGetAccessToken_cached` —— 令牌未过期时返回缓存令牌
- `testGetAccessToken_expired` —— 令牌过期时触发刷新
- `testSignOut_clearsTokens` —— 验证所有令牌键均被删除
- `testHasOAuthCredentials` —— Client ID 和 Secret 均存在时返回 true

**GoogleAuthBridgeTest.kt：**
- `testInject_registersAsyncFunctions` —— 验证 __googleGetAccessToken 和 __googleGetAccountEmail 已注册
- `testWrapperJs_syntaxValid` —— 验证 GOOGLE_AUTH_WRAPPER_JS 解析无误
- `testGetAccessToken_returnsToken` —— 验证桥接返回 GoogleAuthManager 的令牌
- `testGetAccessToken_whenNull_returnsEmpty` —— 验证 manager 为 null 时返回空字符串

**FileTransferBridgeTest.kt：**
- `testInject_registersAsyncFunctions` —— 验证 __downloadToFile 和 __uploadMultipart 已注册
- `testWrapperJs_syntaxValid` —— 验证 FILE_TRANSFER_WRAPPER_JS 解析无误
- `testDownload_success` —— 验证文件保存至正确路径
- `testDownload_networkError` —— 验证错误结果正确返回
- `testUpload_success` —— 验证 multipart 请求构造正确

### 集成测试（Layer 1B）

使用真实 Google 账户手动验证：
- 完整 OAuth 流程（登录、令牌刷新、退出）
- Gmail：搜索、阅读、发送、草稿操作
- 日历：列表、创建、更新、删除事件
- 任务：列表、创建、完成、删除任务
- 联系人：搜索、列表、创建联系人
- Drive：列表、上传、下载文件
- Docs：获取、创建、插入文本
- Sheets：读取、写入、追加值
- Slides：获取、添加幻灯片
- Forms：获取表单结构、列出响应

## 已考虑的替代方案

### 1. Google Sign-In SDK（GMS）

**方案**：使用 Google 官方 Sign-In SDK（GMS/Firebase）进行认证。
**拒绝原因**：需要 Google Play 服务（并非所有设备都具备），将应用与 Google SDK 生命周期绑定，且不支持赋予用户自主控制凭据的 BYOK 模型。

### 2. 将工具实现为 Kotlin 内置工具

**方案**：为每个 Google 服务创建 Kotlin Tool 实现，而非使用 JS 工具组。
**拒绝原因**：约 89 个工具会造成大量代码重复。oneclaw-1 的 JS 实现经过验证且可移植。JS 工具组（RFC-018）提供了简洁、可维护的模式。适配现有 JS 代码比用 Kotlin 重写更快、出错风险更低。

### 3. 将所有 Google 工具合并为单一 JS 文件

**方案**：将所有约 89 个工具放入一个 JS 文件，配合单一 JSON 清单。
**拒绝原因**：会超出 MAX_GROUP_SIZE 限制，导致维护困难，且即便只需要一个服务也会加载所有 Google 代码。按服务拆分（10 个工具组）更加自然且易于管理。

### 4. 使用自定义 TabNet 协议进行 OAuth

**方案**：使用自定义 URI 方案（如 `oneclawshadow://oauth/callback`）而非回环重定向。
**拒绝原因**：自定义 URI 方案需要在 AndroidManifest 中注册，安全性较低（其他应用可注册相同方案）。回环重定向是桌面端/CLI OAuth 的标准方式，符合 Google 的指导方针。

## 依赖项

### 外部依赖

无新增外部依赖。使用：
- **OkHttpClient**（已通过 networkModule 提供）
- **EncryptedSharedPreferences**（已提供，用于 API 密钥存储）
- **QuickJS**（`com.dokar.quickjs`，已通过工具系统提供）
- **Google Workspace REST API**（外部 HTTP 端点，无 SDK 依赖）

### 内部依赖

- `tool/` 包中的 `Tool` 接口、`ToolRegistry`、`ToolExecutionEngine`
- `tool/js/` 包中的 `JsExecutionEngine`、`FetchBridge`、桥接模式
- 用于基于 assets 加载工具组的 `JsToolLoader`
- `core/util/` 中的 `AppResult<T>`
- `data/security/` 中的 `EncryptedSharedPreferences` 模式
- Android `Context`（通过 Koin DI）
- `OkHttpClient`（通过 Koin networkModule）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
