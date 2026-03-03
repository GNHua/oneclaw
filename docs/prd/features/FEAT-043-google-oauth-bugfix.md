# Google OAuth Bugfix

## Feature Information
- **Feature ID**: FEAT-043
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: RFC-043

## User Story

**As** a OneClaw user,
**I want** the "Authorize with Google" flow to complete without crashing or silently failing,
**so that** I can use Google Workspace tools (Gmail, Calendar, Drive, etc.) reliably.

### Typical Scenarios

1. User taps "Authorize with Google", browser never opens, app crashes with `android.util.AndroidRuntimeException: Calling startActivity() from outside of an Activity context`.
2. User completes Google consent in browser, but token exchange fails because the auth code contains URL-encoded characters (e.g., `%2F`) that are not decoded before being sent to Google's token endpoint.
3. User completes consent, token exchange succeeds on attempt 1, but `fetchUserEmail()` throws because the response lacks an `email` field. Tokens are discarded. Retry attempts 2-3 fail with `invalid_grant` because Google auth codes are single-use.

## Feature Description

### Overview

FEAT-043 fixes three bugs in `GoogleAuthManager.kt` that were identified by comparing the current implementation against the working reference in `../oneclaw-1/OAuthGoogleAuthManager.kt`. All three bugs prevent the Google OAuth flow from completing successfully on real devices.

### Bug 1: Missing FLAG_ACTIVITY_NEW_TASK

**Symptom**: App crashes immediately when tapping "Authorize with Google".

**Root Cause**: `GoogleAuthManager` is constructed with an Application-scoped `Context`. Calling `context.startActivity()` from a non-Activity context without `FLAG_ACTIVITY_NEW_TASK` throws `AndroidRuntimeException`.

**Fix**: Add `.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)` to the browser intent.

### Bug 2: Missing URLDecoder.decode on Auth Code

**Symptom**: Token exchange may fail with `invalid_grant` for auth codes containing special characters.

**Root Cause**: `parseAuthCode()` extracts the raw query parameter value without URL-decoding. Google auth codes can contain `/` (encoded as `%2F`) and `+` characters. Passing the encoded value to the token endpoint causes a mismatch.

**Fix**: Apply `java.net.URLDecoder.decode(code, "UTF-8")` after extracting the `code` parameter.

### Bug 3: Retry Loop Too Broad + Email Required

**Symptom**: OAuth appears to fail after 3 retries, even though the first token exchange actually succeeded. User sees a generic error message.

**Root Cause**: The original retry loop wrapped both `exchangeCodeForTokens()` and `fetchUserEmail()` together. When the token exchange succeeded but `fetchUserEmail()` threw (e.g., missing email field, or `email` scope not granted), the tokens were discarded. Retries 2-3 then failed with `invalid_grant` because Google auth codes are single-use -- the code was already consumed on attempt 1.

**Fix**:
1. Retry only the token exchange, not the userinfo fetch.
2. Save tokens to `EncryptedSharedPreferences` immediately after successful exchange.
3. Make `fetchUserEmail()` return `String?` (nullable) and wrap the call in try-catch. Email is best-effort, not required.
4. If email is `null`, return `AppResult.Success("Authorized")` instead of failing.

### Additional Improvements (in same changeset)

- **`buildConsentUrl()`**: Refactored from manual string concatenation to `Uri.Builder` for safer URL construction.
- **`fetchUserEmail()`**: Return type changed from `String` to `String?`; no longer throws on missing data.

## Acceptance Criteria

- [x] Browser opens without crash when tapping "Authorize with Google"
- [x] Auth codes with URL-encoded characters (e.g., `%2F`) are decoded before token exchange
- [x] Tokens are saved immediately after successful exchange, before userinfo fetch
- [x] OAuth flow succeeds even if email is unavailable (returns "Authorized")
- [x] `./gradlew compileDebugKotlin` passes
- [x] Tested on Pixel 6a: browser opens, consent completes, tokens saved, no crash

## Feature Boundary

### Included
- Three bug fixes in `GoogleAuthManager.kt`
- Refactored `buildConsentUrl()` to use `Uri.Builder`
- Changed `fetchUserEmail()` return type from `String` to `String?`

### Not Included
- UI changes (GoogleAuthScreen was already fixed in FEAT-039)
- New OAuth scopes
- Token refresh logic changes
- New tests (existing behavior tested manually on device)

## Files Changed

| File | Change Type |
|------|------------|
| `app/src/main/kotlin/com/oneclaw/shadow/data/security/GoogleAuthManager.kt` | Modified |

## Dependencies

### Depends On
- FEAT-030 (Google Workspace): GoogleAuthManager initial implementation
- FEAT-039 (Bug Fix & Polish): GoogleAuthScreen UI, network security config

### Depended On By
- None

## Error Handling

Unchanged from FEAT-039. The three standard error paths remain:
1. **UnknownHostException**: "Network unavailable. Check your internet connection and try again."
2. **SocketTimeoutException**: "Connection timed out. Check your internet connection and try again."
3. **All retries exhausted**: Shows the last error message.

New behavior: if `fetchUserEmail()` fails, a warning is logged but auth succeeds with `"Authorized"` as the display value.

## Test Points

- Manual verification on Pixel 6a with valid GCP Desktop OAuth credentials.
- `./gradlew compileDebugKotlin` passes.
- No new unit tests (loopback server + real OAuth flow not suitable for JVM tests).

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial version -- 3 bugs fixed | - |
