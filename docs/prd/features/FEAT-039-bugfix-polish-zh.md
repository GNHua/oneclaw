# 缺陷修复与界面优化

## 功能信息
- **Feature ID**: FEAT-039
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Completed
- **Priority**: P0 (Must Have)
- **Owner**: TBD
- **Related RFC**: RFC-039

## 用户故事

**作为** OneClaw 用户，
**我希望** 应用稳定、精致，并且不存在我在测试期间反馈的问题，
**从而** 能够顺畅地使用 Google OAuth、管理 Agent、浏览文件、搜索历史记录、发送附件以及管理定时任务。

### 典型场景

1. 用户尝试通过 Google OAuth 登录，但流程静默失败或崩溃。
2. 用户切换到新对话，期望当日日志在未来搜索时已被刷写。
3. 用户通过 `search_history` 工具搜索历史对话，但在结果中看到了当前消息。
4. 用户打开文件浏览器，看到的是空目录而非自己的文件。
5. 用户想调整内置 Agent 的 temperature/max iterations，但相关控件缺失或被禁用。
6. 用户想向聊天消息附加文件，但没有附件按钮。
7. 用户点击聊天中的工具调用，希望查看其参数。
8. 用户期望工具管理界面在工具注册/注销时自动刷新。
9. 用户发现 30 多个内置工具没有分组，难以浏览。
10. 用户无法判断定时任务列表中的行是否可点击。

## 功能描述

### 概述

FEAT-039 是一次全面的缺陷修复与界面优化，解决了用户测试中反馈的 15 个问题。变更涉及 7 个功能领域：Google OAuth、聊天、Agent 详情、工具管理、Bridge 设置、文件浏览器、搜索历史以及定时任务。

### 问题清单

| # | 领域 | 摘要 | 类型 |
|---|------|---------|------|
| 1 | Chat | 切换到"新对话"时当日日志未刷写 | Bug |
| 2 | Bridge | Wake Lock 开关缺少说明文字 | UX |
| 3 | Google Auth | OAuth 流程失败（6 个子问题） | Bug |
| 4 | Google Auth | 认证界面缺少说明和状态显示 | UX |
| 5 | Agent | 内置 Agent 无法在运行时切换 Web Search | Bug |
| 6 | Agent | 缺少 temperature 和 max iterations 的界面控件 | Missing Feature |
| 7 | Chat | 无附件按钮/文件选择器集成 | Missing Feature |
| 8 | Chat | 工具调用卡片不显示参数 | UX |
| 9 | Tool Mgmt | 工具列表在注册表变更时不自动刷新 | Bug |
| 10 | Tool Mgmt | 内置工具以平铺列表展示，无分类 | UX |
| 11 | Bridge | 频道区块之间缺少视觉分隔 | UX |
| 12 | File Browser | 文件浏览器显示空目录 | Bug |
| 13 | Search History | `search_history` 将当前消息包含在结果中 | Bug |
| 14 | Schedule | 任务行不可点击，无编辑引导 | UX |
| 15 | Schedule | 编辑任务时跳过了精确闹钟权限申请 | Bug |

## 验收标准

必须通过（全部必需）：

### 缺陷修复
- [ ] 从活跃会话切换到"新对话"（null sessionId）时，当日日志已刷写
- [ ] 在真实设备上使用有效的 GCP 凭据，Google OAuth 能够成功完成
- [ ] OAuth 回环服务器显式绑定到 127.0.0.1
- [ ] OAuth 令牌交换最多重试 3 次，间隔 3 秒
- [ ] OAuth 流程在 Activity 重建后仍可继续（NonCancellable）
- [ ] 浏览器意图启动时不带 FLAG_ACTIVITY_NEW_TASK
- [ ] 针对 UnknownHostException 和 SocketTimeoutException 显示具体错误信息
- [ ] 文件浏览器显示所有内部文件（memory/、daily_logs/ 等），而非空的 `user_files/` 目录
- [ ] 当 `dateTo` 为 null 时，`search_history` 工具排除最近 5 秒内的消息
- [ ] 内置 Agent 可在运行时切换 Web Search、temperature 和 max iterations
- [ ] 保存内置 Agent 时保留其 name、description、systemPrompt 及其他不可变字段
- [ ] 新建和编辑定时任务时均弹出精确闹钟权限对话框
- [ ] 工具管理界面在工具注册或注销时自动刷新
- [ ] 所有 Layer 1A 测试通过（`./gradlew test`）

### 界面/体验改进
- [ ] Wake Lock 开关下方显示说明文字
- [ ] Google Auth 界面显示连接状态卡片（Connected/Not connected）
- [ ] Google Auth 界面显示"未验证应用"警告卡片
- [ ] Google Auth 界面显示带编号的 9 步 GCP 配置说明
- [ ] Google Auth 界面列出所有必需的 Google API
- [ ] Google Auth 界面包含可点击的 console.cloud.google.com 链接
- [ ] Google Auth 界面错误信息以 errorContainer 颜色的卡片展示
- [ ] Google Auth 界面支持编辑已有凭据
- [ ] Agent 详情界面新增 Temperature 滑块（0.0–2.0）和 Max Iterations 文本输入框（1–100）
- [ ] 聊天输入框在技能按钮旁新增附件按钮
- [ ] AttachmentPickerSheet 和 AttachmentPreviewRow 已接入 ChatScreen
- [ ] 有待发送附件时（即使文本为空），发送按钮处于可用状态
- [ ] 工具调用卡片可展开，以等宽字体显示工具输入参数
- [ ] 工具输入内容超过 20 行时截断并显示省略号
- [ ] 内置工具按分类分组（Calendar、Config、Provider/Model、Agent、Scheduling、Files & Web、PDF、JS Tools、Other）
- [ ] 分类分组可折叠，并带有展开/收起图标
- [ ] Bridge 设置频道区块使用圆角 Surface 卡片包裹
- [ ] 区块之间的 HorizontalDivider 替换为 Spacer
- [ ] 定时任务列表行可点击（导航到编辑界面）
- [ ] 定时任务列表行显示 ChevronRight 图标作为编辑引导

## 功能边界

### 包含内容
- 8 个功能性问题的缺陷修复
- 7 个领域的界面/体验改进
- 测试修复（SearchHistoryUseCaseTest、ScheduledTaskEditViewModelTest）
- 构建配置修复（单元测试的 isReturnDefaultValues）
- OAuth 明文流量的网络安全配置

### 不包含内容
- 上述列表之外的新功能
- 数据库 Schema 变更
- 新增外部依赖
- 附件选择器的 ActivityResultLauncher 接线（占位回调）
- Layer 2 手动验证

## 变更文件

| 文件 | 变更类型 |
|------|------------|
| `app/build.gradle.kts` | Modified (isReturnDefaultValues) |
| `AndroidManifest.xml` | Modified (networkSecurityConfig) |
| `res/xml/network_security_config.xml` | New |
| `GoogleAuthManager.kt` | Modified (6 sub-fixes) |
| `GoogleAuthScreen.kt` | Rewritten |
| `GoogleAuthViewModel.kt` | Modified (editingCredentials, dirty) |
| `UserFileStorage.kt` | Modified (rootDir) |
| `AgentDetailScreen.kt` | Modified (runtime settings, behavior section) |
| `AgentDetailViewModel.kt` | Modified (built-in agent save logic) |
| `AgentUiState.kt` | Modified (hasRuntimeChanges) |
| `CreateAgentUseCase.kt` | Modified (new parameters) |
| `ChatScreen.kt` | Modified (attachment, tool card expand) |
| `ChatViewModel.kt` | Modified (flush condition) |
| `BridgeSettingsScreen.kt` | Modified (cards, wake lock text) |
| `ToolRegistry.kt` | Modified (version StateFlow) |
| `ToolManagementScreen.kt` | Modified (category grouping) |
| `ToolManagementViewModel.kt` | Modified (categorize, auto-refresh) |
| `ScheduledTaskListScreen.kt` | Modified (clickable, chevron) |
| `ScheduledTaskEditViewModel.kt` | Modified (alarm check) |
| `SearchHistoryUseCase.kt` | Modified (5s buffer, debug logs) |
| `ScheduledTaskEditViewModelTest.kt` | Modified (updated assertion) |
| `SearchHistoryUseCaseTest.kt` | Modified (any() matcher) |

## 依赖关系

### 依赖于
- FEAT-001 (Chat): ChatViewModel、ChatScreen
- FEAT-002 (Agent): AgentDetailScreen、AgentDetailViewModel
- FEAT-004 (Tool System): ToolRegistry
- FEAT-017 (Tool Management): ToolManagementScreen
- FEAT-019 (Scheduled Tasks): ScheduledTaskEditViewModel、ScheduledTaskListScreen
- FEAT-024 (Messaging Bridge): BridgeSettingsScreen
- FEAT-025 (File Browsing): UserFileStorage
- FEAT-026 (File Attachments): AttachmentPickerSheet、AttachmentPreviewRow
- FEAT-030 (Google Workspace): GoogleAuthManager、GoogleAuthScreen
- FEAT-032 (Search History): SearchHistoryUseCase

### 被依赖于
- 无

## 错误处理

### Google OAuth 错误
1. **UnknownHostException**："网络不可用，请检查网络连接后重试。"
2. **SocketTimeoutException**："连接超时，请检查网络连接后重试。"
3. **未收到授权码**："No authorization code received (timed out or cancelled)"
4. **令牌交换重试 3 次后仍失败**：显示最后一次的错误信息

### 搜索历史
1. **结果包含当前消息**：5 秒缓冲区自动排除最近的消息

## 测试要点

### 修改的单元测试
- `SearchHistoryUseCaseTest`：将 `coVerify` 断言从 `Long.MAX_VALUE` 改为 `any()`，以适应 5 秒缓冲区逻辑
- `ScheduledTaskEditViewModelTest`：更新为期望编辑任务时弹出闹钟对话框（此前断言为不弹出）

### 构建配置
- `app/build.gradle.kts`：在 `testOptions.unitTests` 中添加 `isReturnDefaultValues = true`，防止 `android.util.Log` 在 JVM 测试中抛出异常

## 第二轮：额外问题（16-25）

### 问题清单（第二轮）

| # | 区域 | 摘要 | 类型 |
|---|------|------|------|
| 16 | 桥接 | 频道区应为可折叠的单行标题 | UX |
| 17 | 桥接 | 每个频道添加设置指南 | UX |
| 18 | 桥接 | Wake Lock 警告文本需要视觉强调 | UX |
| 19 | Google Auth | SetupInstructions 因 LinkAnnotation API 不稳定而崩溃 | Bug |
| 20 | Google Auth | 无法删除已存储的 OAuth 凭据 | 功能缺失 |
| 21 | Agent | 最大迭代次数应为滑块而非文本框 | UX |
| 22 | 聊天 | 附件选择器回调未接线（空操作） | Bug |
| 23 | 聊天 | 附件选择器深色模式导致状态栏图标颜色异常 | Bug |
| 24 | 工具管理 | 分类名称应使用完整 Google 产品名 | UX |
| 25 | 工具管理 | 长工具名导致徽章布局被挤压 | Bug |

### 验收标准（第二轮）

- [ ] 桥接频道显示为可折叠的单行标题（频道名 + 开关）
- [ ] 每个频道有可折叠的设置指南和编号步骤
- [ ] Wake Lock 文本带有警告图标和错误颜色
- [ ] Google OAuth 屏幕不会在 SetupInstructions 处崩溃
- [ ] 已登录和有凭据状态下可见"删除凭据"按钮
- [ ] Agent 最大迭代次数使用滑块（1-200，200 = 无限制）
- [ ] 附件选择器（照片、视频、相机、文件）启动真实系统选择器
- [ ] 附件选择器底部面板遮罩不影响深色模式状态栏
- [ ] 工具分类使用 Google 产品名称（Gmail、Google Calendar 等）
- [ ] 长工具名不会挤压徽章布局

### 变更文件（第二轮）

| 文件 | 变更类型 |
|------|---------|
| `BridgeSettingsScreen.kt` | 修改（可折叠频道、设置指南、警告图标） |
| `GoogleAuthScreen.kt` | 修改（LinkAnnotation 崩溃修复、删除按钮） |
| `GoogleAuthViewModel.kt` | 修改（deleteCredentials） |
| `GoogleAuthManager.kt` | 修改（clearAllCredentials） |
| `AgentDetailScreen.kt` | 修改（滑块替换文本框） |
| `AgentDetailViewModel.kt` | 修改（移除验证） |
| `AgentUiState.kt` | 修改（移除 maxIterationsError） |
| `SendMessageUseCase.kt` | 修改（null = Int.MAX_VALUE） |
| `ChatScreen.kt` | 修改（接线 ActivityResultLauncher） |
| `AttachmentPickerSheet.kt` | 修改（遮罩颜色） |
| `ToolManagementViewModel.kt` | 修改（Google 产品名称） |
| `ToolManagementScreen.kt` | 修改（徽章布局修复） |
| `file_paths.xml` | 修改（camera_photos 缓存路径） |

## 变更历史

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 1.0 | Initial version (15 issues fixed) | - |
| 2026-03-01 | 2.0 | Round 2 (10 additional issues: 16-25) | - |
