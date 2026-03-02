# RFC-043: Google OAuth 缺陷修复

## 文档信息
- **RFC ID**: RFC-043
- **关联 PRD**: [FEAT-043 (Google OAuth 缺陷修复)](../../prd/features/FEAT-043-google-oauth-bugfix.md)
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 已完成
- **作者**: TBD

## 概述

### 背景

FEAT-039 打磨阶段完成后，Google OAuth 流程在真实设备上仍然失败。通过将 `GoogleAuthManager.kt` 与 `../oneclaw-1/OAuthGoogleAuthManager.kt` 中可正常运行的参考实现进行差异对比，发现了三个缺陷。这些缺陷相互关联：缺陷 1 阻止流程启动，缺陷 2 导致间歇性 `invalid_grant` 错误，缺陷 3 在邮箱获取失败时丢弃有效的令牌。

### 目标

1. 修复从 Application 上下文启动浏览器时 `startActivity()` 崩溃的问题
2. 在令牌交换前对 URL 编码的授权码进行解码
3. 在交换成功后立即保存令牌，使邮箱获取成为尽力而为的操作

### 非目标

- UI 变更（已在 FEAT-039 中处理）
- 新增 OAuth 作用域或更改令牌刷新逻辑
- 新增单元测试（OAuth loopback 流程在 JVM 中无法测试）

## 技术设计

### 变更文件

```
app/src/main/kotlin/com/oneclaw/shadow/data/security/
└── GoogleAuthManager.kt    # MODIFIED (3 bug fixes + 2 refactors)
```

## 详细设计

### 缺陷修复 1：缺少 FLAG_ACTIVITY_NEW_TASK

**文件**: `GoogleAuthManager.kt:141-144`

`GoogleAuthManager` 通过 Koin DI 接收 Application 范围的 `Context`。从非 Activity 上下文调用 `context.startActivity()` 时，Android 要求携带 `FLAG_ACTIVITY_NEW_TASK`，否则会抛出 `AndroidRuntimeException`。

```kotlin
// Before
withContext(Dispatchers.Main) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl))
    context.startActivity(intent)
}

// After
withContext(Dispatchers.Main) {
    val intent = Intent(Intent.ACTION_VIEW, Uri.parse(consentUrl))
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    context.startActivity(intent)
}
```

注意：RFC-039 中错误地描述为"移除了 FLAG_ACTIVITY_NEW_TASK"——实际上该 flag 从未存在过，需要的是新增它。

---

### 缺陷修复 2：对授权码缺少 URLDecoder.decode 处理

**文件**: `GoogleAuthManager.kt:335`

Google OAuth 授权码可能包含 `/` 等字符，在重定向查询字符串中会被 URL 编码为 `%2F`。`parseAuthCode()` 函数提取原始值时未进行解码，导致在令牌交换端点出现 `invalid_grant` 错误。

```kotlin
// Before
return query.split("&")
    .map { it.split("=", limit = 2) }
    .find { it[0] == "code" }
    ?.getOrNull(1)

// After
return query.split("&")
    .map { it.split("=", limit = 2) }
    .find { it[0] == "code" }
    ?.getOrNull(1)
    ?.let { java.net.URLDecoder.decode(it, "UTF-8") }
```

---

### 缺陷修复 3：缩小重试范围 + 邮箱获取改为可选

**文件**: `GoogleAuthManager.kt:160-216`

**根因分析**：

原始流程如下：
```
repeat(3) {
    tokens = exchangeCodeForTokens(code)    // 第 1 次尝试：成功
    email = fetchUserEmail(tokens)          // 抛出异常！（无 email 字段）
    saveTokens(tokens, email)               // 永远不会执行
    return success                          // 永远不会执行
}
// 第 2、3 次重试：exchangeCodeForTokens 因 invalid_grant 失败
//               （授权码已在第 1 次尝试时被消费）
return error
```

修复方案将令牌交换与用户信息获取分离：

```kotlin
// Step 1: Retry only the token exchange
var tokens: TokenResponse? = null
repeat(3) { attempt ->
    if (attempt > 0) delay(3000)
    try {
        tokens = exchangeCodeForTokens(authCode, clientId, clientSecret, redirectUri)
        return@repeat  // success, stop retrying
    } catch (e: UnknownHostException) { lastError = e }
      catch (e: SocketTimeoutException) { lastError = e }
      catch (e: Exception) { lastError = e }
}

val exchangedTokens = tokens ?: return@withContext AppResult.Error(...)

// Step 2: Save tokens immediately (before userinfo fetch)
prefs.edit()
    .putString(KEY_REFRESH_TOKEN, exchangedTokens.refreshToken)
    .putString(KEY_ACCESS_TOKEN, exchangedTokens.accessToken)
    .putLong(KEY_TOKEN_EXPIRY, System.currentTimeMillis() + exchangedTokens.expiresInMs)
    .apply()

// Step 3: Fetch email (best-effort)
val email = try {
    fetchUserEmail(exchangedTokens.accessToken)
} catch (e: Exception) {
    Log.w(TAG, "Could not fetch user email (non-fatal)", e)
    null
}
if (email != null) {
    prefs.edit().putString(KEY_EMAIL, email).apply()
}

AppResult.Success(email ?: "Authorized")
```

**关键变更**：
1. `fetchUserEmail()` 返回类型从 `String` 改为 `String?`——邮箱缺失时返回 `null` 而非抛出异常。
2. 令牌保存在邮箱获取之前完成，令牌不再丢失。
3. 重试循环仅覆盖令牌交换，不涵盖邮箱获取。
4. 若邮箱不可用，流程仍以 `"Authorized"` 作为展示值成功返回。

---

### 重构：buildConsentUrl() 改用 Uri.Builder

**文件**: `GoogleAuthManager.kt:283-293`

将手动字符串拼接替换为 Android 的 `Uri.Builder`，以实现更安全的查询参数编码：

```kotlin
// Before
return "$AUTH_URL?" +
    "client_id=${Uri.encode(clientId)}" +
    "&redirect_uri=${Uri.encode(redirectUri)}" + ...

// After
return Uri.parse(AUTH_URL).buildUpon()
    .appendQueryParameter("client_id", clientId)
    .appendQueryParameter("redirect_uri", redirectUri)
    .appendQueryParameter("response_type", "code")
    .appendQueryParameter("scope", SCOPES.joinToString(" "))
    .appendQueryParameter("access_type", "offline")
    .appendQueryParameter("prompt", "consent")
    .build()
    .toString()
```

`Uri.Builder.appendQueryParameter()` 自动处理编码，消除了手动调用 `Uri.encode()` 的需要。

---

### 重构：fetchUserEmail() 改为返回可空类型

**文件**: `GoogleAuthManager.kt:406-417`

```kotlin
// Before
private suspend fun fetchUserEmail(accessToken: String): String {
    // ...
    val responseBody = response.body?.string()
        ?: throw IOException("Empty userinfo response")
    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
    jsonObj["email"]?.jsonPrimitive?.content
        ?: throw IOException("No email in userinfo response")
}

// After
private suspend fun fetchUserEmail(accessToken: String): String? {
    // ...
    val responseBody = response.body?.string() ?: return@withContext null
    val jsonObj = json.parseToJsonElement(responseBody).jsonObject
    jsonObj["email"]?.jsonPrimitive?.content
}
```

当响应体为空或缺少 `email` 字段时，返回 `null` 而非抛出异常。

## 安全性考量

1. **令牌存储无变化**：令牌仍使用 `EncryptedSharedPreferences` 存储。
2. **FLAG_ACTIVITY_NEW_TASK**：这是从非 Activity 上下文启动 Activity 的标准 Android flag，无安全影响。
3. **URLDecoder**：对标准百分号编码字符进行解码。由于该值仅用作向 Google 令牌端点发送的 POST 表单参数，不存在注入风险。

## 性能考量

- 无性能影响。重试循环的操作范围缩小（每次重试操作更少），在失败路径上略有改善。

## 编译与测试结果

- `./gradlew compileDebugKotlin` 通过。
- 在 Pixel 6a 上手动测试：浏览器正常打开，Google 授权完成，重定向成功捕获，令牌已保存，邮箱已获取。

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | 初始版本——3 个缺陷修复 + 2 个重构 | - |
