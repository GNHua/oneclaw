# RFC-043: Google OAuth Bugfix

## Document Information
- **RFC ID**: RFC-043
- **Related PRD**: [FEAT-043 (Google OAuth Bugfix)](../../prd/features/FEAT-043-google-oauth-bugfix.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Author**: TBD

## Overview

### Background

After the FEAT-039 polish pass, the Google OAuth flow still failed on real devices. Three bugs were identified by diff-comparing `GoogleAuthManager.kt` against the working reference implementation in `../oneclaw-1/OAuthGoogleAuthManager.kt`. The bugs interact: Bug 1 prevents the flow from starting, Bug 2 causes intermittent `invalid_grant` errors, and Bug 3 discards valid tokens when email fetch fails.

### Goals

1. Fix the `startActivity()` crash when launching the browser from Application context
2. Decode URL-encoded auth codes before token exchange
3. Save tokens immediately after exchange, making email fetch best-effort

### Non-Goals

- UI changes (already handled in FEAT-039)
- New OAuth scopes or token refresh logic changes
- New unit tests (OAuth loopback flow not testable in JVM)

## Technical Design

### Changed File

```
app/src/main/kotlin/com/oneclaw/shadow/data/security/
└── GoogleAuthManager.kt    # MODIFIED (3 bug fixes + 2 refactors)
```

## Detailed Design

### Bug Fix 1: Missing FLAG_ACTIVITY_NEW_TASK

**File**: `GoogleAuthManager.kt:141-144`

`GoogleAuthManager` receives an Application-scoped `Context` via Koin DI. When calling `context.startActivity()` from a non-Activity context, Android requires `FLAG_ACTIVITY_NEW_TASK` or it throws `AndroidRuntimeException`.

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

Note: RFC-039 incorrectly stated "Removed FLAG_ACTIVITY_NEW_TASK" -- in reality the flag was never present and needed to be added.

---

### Bug Fix 2: Missing URLDecoder.decode on Auth Code

**File**: `GoogleAuthManager.kt:335`

Google OAuth auth codes can contain characters like `/` that are URL-encoded as `%2F` in the redirect query string. The `parseAuthCode()` function extracted the raw value without decoding, causing `invalid_grant` on the token exchange endpoint.

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

### Bug Fix 3: Retry Scope Narrowed + Email Made Optional

**File**: `GoogleAuthManager.kt:160-216`

**Root Cause Analysis**:

The original flow was:
```
repeat(3) {
    tokens = exchangeCodeForTokens(code)    // attempt 1: succeeds
    email = fetchUserEmail(tokens)          // throws! (no email field)
    saveTokens(tokens, email)               // never reached
    return success                          // never reached
}
// retries 2-3: exchangeCodeForTokens fails with invalid_grant
//              (code was already consumed on attempt 1)
return error
```

The fix separates the exchange from the userinfo fetch:

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

**Key changes**:
1. `fetchUserEmail()` return type changed from `String` to `String?` -- returns `null` instead of throwing when email is missing.
2. Token save happens before email fetch, so tokens are never lost.
3. Retry loop only covers the exchange, not the email fetch.
4. If email is unavailable, the flow still succeeds with `"Authorized"` as the display value.

---

### Refactor: buildConsentUrl() Uses Uri.Builder

**File**: `GoogleAuthManager.kt:283-293`

Replaced manual string concatenation with Android's `Uri.Builder` for safer query parameter encoding:

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

`Uri.Builder.appendQueryParameter()` handles encoding automatically, eliminating manual `Uri.encode()` calls.

---

### Refactor: fetchUserEmail() Returns Nullable

**File**: `GoogleAuthManager.kt:406-417`

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

Returns `null` instead of throwing when the response body is empty or lacks an `email` field.

## Security Considerations

1. **No change to token storage**: Tokens continue to use `EncryptedSharedPreferences`.
2. **FLAG_ACTIVITY_NEW_TASK**: Standard Android flag for launching activities from non-Activity contexts. No security implications.
3. **URLDecoder**: Decodes standard percent-encoded characters. No injection risk since the value is only used as a POST form parameter to Google's token endpoint.

## Performance Considerations

- No performance impact. The retry loop behavior is narrowed (fewer operations per retry), which is a slight improvement on the failure path.

## Compilation & Test Results

- `./gradlew compileDebugKotlin` passes.
- Manual test on Pixel 6a: browser opens, Google consent completes, redirect captured, tokens saved, email fetched.

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial version -- 3 bug fixes + 2 refactors | - |
