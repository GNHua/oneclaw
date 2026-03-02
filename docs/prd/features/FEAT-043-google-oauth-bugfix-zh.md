# Google OAuth 缺陷修复

## 功能信息
- **功能 ID**: FEAT-043
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 已完成
- **优先级**: P0（必须）
- **负责人**: TBD
- **关联 RFC**: RFC-043

## 用户故事

**作为** OneClawShadow 的用户，
**我希望** "使用 Google 授权"流程能够顺利完成，而不崩溃或静默失败，
**以便** 我能可靠地使用 Google Workspace 工具（Gmail、Calendar、Drive 等）。

### 典型场景

1. 用户点击"使用 Google 授权"，浏览器未打开，应用崩溃并报错 `android.util.AndroidRuntimeException: Calling startActivity() from outside of an Activity context`。
2. 用户在浏览器中完成 Google 授权同意，但令牌交换失败，原因是 auth code 包含 URL 编码字符（如 `%2F`），在发送至 Google 令牌端点前未被解码。
3. 用户完成授权同意，第 1 次令牌交换成功，但 `fetchUserEmail()` 抛出异常，原因是响应中缺少 `email` 字段。令牌被丢弃。第 2-3 次重试以 `invalid_grant` 失败，因为 Google auth code 只能使用一次。

## 功能描述

### 概述

FEAT-043 修复了 `GoogleAuthManager.kt` 中的三个缺陷，这些缺陷通过对比当前实现与 `../oneclaw-1/OAuthGoogleAuthManager.kt` 中可正常工作的参考实现后发现。三个缺陷均会导致 Google OAuth 流程在真实设备上无法成功完成。

### 缺陷 1：缺少 FLAG_ACTIVITY_NEW_TASK

**症状**：点击"使用 Google 授权"时，应用立即崩溃。

**根本原因**：`GoogleAuthManager` 以 Application 作用域的 `Context` 构造。在非 Activity 上下文中调用 `context.startActivity()` 时，若未设置 `FLAG_ACTIVITY_NEW_TASK`，将抛出 `AndroidRuntimeException`。

**修复**：在浏览器 Intent 中添加 `.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)`。

### 缺陷 2：auth code 未经 URLDecoder.decode 处理

**症状**：对于包含特殊字符的 auth code，令牌交换可能以 `invalid_grant` 失败。

**根本原因**：`parseAuthCode()` 直接提取原始查询参数值，未进行 URL 解码。Google auth code 可能包含 `/`（编码为 `%2F`）和 `+` 字符。将编码后的值传递给令牌端点会导致不匹配。

**修复**：在提取 `code` 参数后，应用 `java.net.URLDecoder.decode(code, "UTF-8")`。

### 缺陷 3：重试循环范围过宽 + email 字段为必填

**症状**：即使第 1 次令牌交换实际成功，OAuth 仍在 3 次重试后显示失败。用户看到通用错误信息。

**根本原因**：原重试循环将 `exchangeCodeForTokens()` 和 `fetchUserEmail()` 包裹在一起。当令牌交换成功但 `fetchUserEmail()` 抛出异常时（如缺少 email 字段，或未授予 `email` 权限范围），令牌被丢弃。第 2-3 次重试随即以 `invalid_grant` 失败，因为 Google auth code 只能使用一次——code 在第 1 次尝试时已被消耗。

**修复**：
1. 仅对令牌交换进行重试，而非 userinfo 获取。
2. 成功交换后立即将令牌保存至 `EncryptedSharedPreferences`。
3. 将 `fetchUserEmail()` 返回类型改为 `String?`（可空），并在调用处包裹 try-catch。Email 为尽力获取，非必填项。
4. 若 email 为 `null`，返回 `AppResult.Success("Authorized")` 而非失败。

### 附加改进（同一变更集中）

- **`buildConsentUrl()`**：从手动字符串拼接重构为 `Uri.Builder`，以实现更安全的 URL 构造。
- **`fetchUserEmail()`**：返回类型从 `String` 改为 `String?`；在数据缺失时不再抛出异常。

## 验收标准

- [x] 点击"使用 Google 授权"时浏览器正常打开，无崩溃
- [x] 包含 URL 编码字符（如 `%2F`）的 auth code 在令牌交换前已被解码
- [x] 成功交换后、获取 userinfo 之前，令牌已立即保存
- [x] 即使 email 不可用，OAuth 流程仍成功完成（返回 "Authorized"）
- [x] `./gradlew compileDebugKotlin` 通过
- [x] 已在 Pixel 6a 上测试：浏览器打开、授权同意完成、令牌已保存、无崩溃

## 功能边界

### 包含范围
- `GoogleAuthManager.kt` 中的三个缺陷修复
- `buildConsentUrl()` 重构为使用 `Uri.Builder`
- `fetchUserEmail()` 返回类型从 `String` 改为 `String?`

### 不包含范围
- UI 变更（GoogleAuthScreen 已在 FEAT-039 中修复）
- 新增 OAuth 权限范围
- 令牌刷新逻辑变更
- 新增测试（现有行为已在设备上手动测试）

## 变更文件

| 文件 | 变更类型 |
|------|----------|
| `app/src/main/kotlin/com/oneclaw/shadow/data/security/GoogleAuthManager.kt` | 修改 |

## 依赖关系

### 依赖于
- FEAT-030（Google Workspace）：GoogleAuthManager 初始实现
- FEAT-039（缺陷修复与打磨）：GoogleAuthScreen UI、网络安全配置

### 被依赖于
- 无

## 错误处理

与 FEAT-039 保持不变。三条标准错误路径如下：
1. **UnknownHostException**："Network unavailable. Check your internet connection and try again."
2. **SocketTimeoutException**："Connection timed out. Check your internet connection and try again."
3. **所有重试耗尽**：显示最后一条错误信息。

新行为：若 `fetchUserEmail()` 失败，将记录警告日志，但授权仍以 `"Authorized"` 作为显示值成功完成。

## 测试要点

- 使用有效 GCP Desktop OAuth 凭据在 Pixel 6a 上进行手动验证。
- `./gradlew compileDebugKotlin` 通过。
- 无新增单元测试（回环服务器 + 真实 OAuth 流程不适合 JVM 测试）。

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 1.0 | 初始版本——修复 3 个缺陷 | - |
