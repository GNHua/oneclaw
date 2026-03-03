# 模型/Provider 管理

## 功能信息
- **功能ID**: FEAT-003
- **创建日期**: 2026-02-26
- **最后更新**: 2026-02-26
- **状态**: 草稿
- **优先级**: P0（必须有）
- **负责人**: TBD
- **关联RFC**: [RFC-003 (Provider 管理)](../../rfc/features/RFC-003-provider-management-zh.md)
- **关联设计**: [UI 设计规范](../../design/ui-design-spec-zh.md)

## 用户故事

**作为** OneClaw 的用户，
**我想要** 配置自己的 AI 模型 provider 和 API key，
**以便** 我可以使用我有权限访问的 AI 模型，而不依赖任何第三方后端。

### 典型场景
1. 用户首次打开应用，将 OpenAI API key 添加到预配置的 OpenAI provider 中。
2. 用户添加 Anthropic provider 并输入 API key，应用自动获取可用模型列表。
3. 用户将 Claude Sonnet 设为全局默认模型。
4. 用户添加自定义 API endpoint（用于自托管模型），手动输入模型名称。
5. 用户测试 provider 连接，确认 API key 有效后再开始对话。
6. 用户因安全原因轮换 API key 后更新密钥。

## 功能描述

### 概述
模型/Provider 管理允许用户配置他们要使用的 AI 模型 provider。由于 OneClaw 没有后端，用户需要自带 API key。应用为主流 provider（OpenAI、Anthropic、Google Gemini）预置了配置模板，同时支持完全自定义的 API endpoint。模型列表尽可能通过 provider API 动态获取，预设常见模型作为可靠的兜底方案。用户设置一个全局默认模型，当 Agent 未指定首选模型时使用。

### 核心概念

**Provider**：托管 AI 模型的 API 服务。由名称、API endpoint 基础 URL、API key 和 API 协议类型定义。例如：OpenAI、Anthropic、Google Gemini 或自定义 endpoint。"类型"字段表示 API 协议格式（OpenAI、Anthropic 或 Gemini），而非服务身份。自定义 endpoint 选择其兼容的协议（最常见的是 OpenAI）。`isPreConfigured` 字段区分内置模板和用户创建的 provider。

**Model**：通过 provider 提供的特定 AI 模型。例如：gpt-4o、claude-sonnet-4-20250514、gemini-2.0-flash。一个 provider 可以有多个模型。

**全局默认**：当 Agent 未设置首选模型时使用的模型/provider 组合。

### Provider 数据模型

| 字段 | 必填 | 说明 |
|------|------|------|
| ID | 是 | 唯一标识符，自动生成 |
| 名称 | 是 | 显示名称（例如"OpenAI"、"我的自定义服务器"） |
| 类型 | 是 | API 协议格式：`openai`、`anthropic`、`gemini`（无单独的 `custom` 类型；自定义 endpoint 选择兼容的协议） |
| API 基础 URL | 是 | API 请求的基础 URL |
| API Key | 是 | 用户的 API key，加密存储 |
| 是否预配置 | 是 | 是否为预配置的 provider 模板 |
| 可用模型 | 是 | 可用模型列表（动态获取或手动添加） |
| 是否启用 | 是 | 该 provider 当前是否启用 |
| 创建时间 | 是 | 时间戳 |
| 更新时间 | 是 | 时间戳 |

### Model 数据模型

| 字段 | 必填 | 说明 |
|------|------|------|
| ID | 是 | 模型标识符（例如"gpt-4o"、"claude-sonnet-4-20250514"） |
| 显示名称 | 否 | 人类友好的名称（例如"GPT-4o"、"Claude Sonnet 4"） |
| Provider ID | 是 | 该模型所属的 provider |
| 是否默认 | 否 | 是否为全局默认模型 |
| 来源 | 是 | 模型添加方式：`dynamic`（从 API 获取）、`preset`（预配置兜底）、`manual`（用户手动添加） |

### 预配置 Provider

应用为主流 provider 预置了模板。用户只需输入 API key：

| Provider | 类型 | API 基础 URL | 是否有 List Models API | 预设兜底模型 |
|----------|------|-------------|----------------------|-------------|
| OpenAI | `openai` | `https://api.openai.com/v1` | 是 | gpt-4o, gpt-4o-mini, o1, o3-mini |
| Anthropic | `anthropic` | `https://api.anthropic.com/v1` | 是 | claude-sonnet-4-20250514, claude-haiku-4-20250414 |
| Google Gemini | `gemini` | `https://generativelanguage.googleapis.com/v1beta` | 是 | gemini-2.0-flash, gemini-2.5-pro |

注意：预设兜底模型在动态获取失败时使用。由于动态获取是主要机制，预设列表不需要频繁更新。

### 模型列表策略

每个 provider 的可用模型按以下优先级填充：

1. **动态获取（首选）**：当 provider 配置了 API key 后，应用尝试调用 provider 的 list-models API 获取当前可用模型列表。执行时机：
   - 首次配置 provider 时
   - 用户手动触发刷新时
   - 后台定期执行（例如每天一次）

2. **预设兜底**：如果动态获取失败（网络错误、API 不支持等），应用回退到已知 provider 类型的预配置常见模型列表。

3. **手动输入**：对于自定义 endpoint（类型为 `custom`），或当用户想使用不在列表中的特定模型时，可以手动输入模型 ID。对于自定义 endpoint，这是唯一选项，因为我们无法获知其可用模型。

### 自定义 Endpoint 支持

用户可以添加完全自定义的 API endpoint：
- 必须指定 API 基础 URL
- 必须指定认证方式（API key）
- 必须手动添加模型名称（自定义 endpoint 无法动态获取）
- API 必须兼容支持的 provider 协议之一（OpenAI 兼容协议最常见）
- 用户选择 provider 类型（OPENAI、ANTHROPIC 或 GEMINI），基于自定义 endpoint 兼容的 API 协议

### API Key 安全

- API key 使用 Android Keystore 加密存储
- 在 UI 中，API key 默认遮掩显示（仅显示最后 4 位字符，例如 `sk-...abc1234`）
- "眼睛"图标切换按钮允许用户显示/隐藏完整密钥
- 编辑时，完整密钥在输入框中可见
- API key 绝不发送到配置的 provider endpoint 以外的任何服务器
- API key 只有在用户明确选择时才包含在 Google Drive 同步中（默认不同步）

### 连接测试

用户可以测试 provider 连接以验证：
- API endpoint 可达
- API key 有效
- 至少有一个模型可访问

测试结果显示为：
- 成功："连接成功。发现 X 个可用模型。"
- 认证失败："认证失败。请检查您的 API key。"
- 网络失败："无法连接到服务器。请检查 URL 和网络连接。"
- 其他错误：显示 API 返回的错误信息

### 全局默认模型

- 一个模型/provider 组合被指定为全局默认
- 当 Agent 未指定首选模型时使用（参见 FEAT-002 模型解析优先级）
- 用户必须在开始第一次对话前设置全局默认
- 如果全局默认 provider 变为无效（API key 被移除、provider 被删除），应用提示用户设置新的默认值

### Provider 管理操作

#### 查看 Provider 列表
- 展示所有 provider（预配置优先，然后是自定义）
- 每项显示：provider 名称、类型标记、可用模型数量、启用/停用状态
- 启用的 provider 与停用的在视觉上有区分

#### 添加 Provider（从模板）
- 用户选择预配置的 provider 模板
- 用户输入 API key
- 应用测试连接并获取模型
- Provider 保存并激活

#### 添加自定义 Provider
- 用户点击"添加自定义 Provider"
- 填写：名称、API 基础 URL、API key、协议类型
- 手动添加模型名称
- 测试连接
- 保存

#### 编辑 Provider
- 用户可修改：名称（仅自定义）、API key、启用/停用状态
- 预配置 provider：不可更改基础 URL 或类型
- 自定义 provider：可更改所有字段

#### 删除 Provider
- 确认对话框
- 如果该 provider 是全局默认，用户必须先设置新的默认值
- 如果有 Agent 将该 provider 作为首选 provider，这些 Agent 回退到全局默认值

#### 刷新模型
- 手动刷新：用户点击刷新，从 API 重新获取模型列表
- 获取过程中显示加载状态
- 成功时更新可用模型列表
- 失败时显示错误，保留现有列表

### 用户交互流程

#### 首次设置
```
1. 用户首次打开应用
2. 应用显示欢迎/设置页面："设置您的 AI Provider 即可开始使用"
3. 用户看到预配置的 provider 选项（OpenAI、Anthropic、Gemini）和"自定义"
4a. 用户选择一个 provider（例如 OpenAI）
    5. 用户输入 API key
    6. 用户点击"测试连接"
    7. 应用验证密钥并获取可用模型
    8. 用户从列表中选择默认模型
    9. 设置完成，用户进入聊天页面
4b. 用户点击"稍后设置"
    5. 用户直接进入聊天页面
    6. 用户可以浏览历史、管理 Agent 等，但无法发送消息
    7. 用户尝试发送消息时，内联错误提示配置 provider
    8. 欢迎页面不再显示；用户通过设置配置 provider
```

注意：欢迎/设置页面仅在首次启动应用时显示。它不是强制阻断——用户可以跳过。跳过后，后续启动应用直接进入聊天页面。

#### 添加第二个 Provider
```
1. 用户导航到 Provider 管理
2. 用户点击"添加 Provider"
3. 用户选择 provider 类型或"自定义"
4. 用户输入 API key（如果是自定义还需输入 URL）
5. 用户测试连接
6. 用户保存
7. 新模型现在可在 Agent 配置和全局默认选择中使用
```

## 验收标准

必须通过（所有必需项）：
- [ ] 应用预配置了 OpenAI、Anthropic 和 Google Gemini 的模板
- [ ] 用户可以通过选择模板并输入 API key 来添加 provider
- [ ] 用户可以添加完全自定义的 provider 及自定义 endpoint URL
- [ ] 可用时从 provider API 动态获取模型列表
- [ ] 动态获取失败时显示预设兜底模型
- [ ] 用户可以为自定义 provider 手动添加模型名称
- [ ] API key 加密存储（Android Keystore）
- [ ] API key 在 UI 中默认遮掩显示，可切换显示
- [ ] 用户可以测试 provider 连接并看到清晰的成功/失败结果
- [ ] 用户可以设置全局默认模型/provider
- [ ] 首次启动时通过可跳过的欢迎页面引导（不强制）用户配置 provider
- [ ] 用户在没有活跃 provider 时尝试发消息，显示内联错误提示配置
- [ ] 用户可以编辑 provider API key
- [ ] 用户可以删除 provider（需确认）
- [ ] 删除全局默认 provider 时提示用户设置新的默认值
- [ ] 用户可以启用/停用 provider 而不删除
- [ ] 用户可以手动触发模型列表刷新
- [ ] 自定义 endpoint 需要用户选择兼容的 API 协议类型（OPENAI、ANTHROPIC 或 GEMINI）

可选（V1 中的加分项）：
- [ ] 自动定期模型列表刷新（例如每天）
- [ ] Provider 健康指示器（最后成功连接时间）
- [ ] Google Drive 备份中 API key 同步的可选开关

## UI/UX 要求

### Provider 列表界面
- 所有 provider 的列表
- 每项：provider 名称、类型图标/标记、模型数量、启用状态切换
- 未配置 API key 的预配置 provider 显示"设置"提示
- "添加 Provider"按钮
- 点击查看/编辑详情

### Provider 详情/编辑界面
- Provider 名称（自定义可编辑，预配置只读）
- API 基础 URL（自定义可编辑，预配置只读）
- API Key 字段：
  - 默认遮掩显示（例如 `sk-...abc1234`）
  - 眼睛图标切换可见性
  - 点击时可编辑的文字输入框
- 协议类型（仅自定义 provider）
- 启用/停用切换
- "测试连接"按钮及结果展示
- 模型列表区域：
  - 动态获取的模型标注"动态"标签
  - 预设兜底模型标注"预设"标签
  - 手动添加的模型标注"手动"标签及删除选项
  - "添加模型"选项（手动输入）
  - "刷新模型"按钮
- 默认模型选择器（单选按钮或模型旁的星标图标）
- "删除 Provider"按钮（需确认）

### 首次设置界面（欢迎页面）
- 仅在首次启动应用时显示，可跳过
- 简洁、聚焦的布局
- Provider 模板卡片（OpenAI、Anthropic、Gemini、自定义）
- API key 输入框及测试按钮
- 连接成功后的模型选择
- "开始使用"按钮
- 底部"稍后设置"文字按钮（不配置直接进入聊天）

### 交互反馈
- 连接测试：加载旋转 -> 成功/失败结果
- 模型刷新：加载旋转 -> 更新列表或错误
- 保存：确认 toast
- 删除：确认对话框 -> 成功 toast

## 功能边界

### 包含的功能
- 预配置 provider 模板（OpenAI、Anthropic、Gemini）
- 自定义 provider endpoint 支持
- API key 管理（添加、编辑、遮掩显示、切换可见性）
- API key 存储在 Android Keystore 中（EncryptedSharedPreferences，不在数据库中）
- 动态模型列表获取
- 预设兜底模型
- 手动模型输入（用于自定义 endpoint）
- 连接测试
- 全局默认模型选择
- Provider 启用/停用
- Provider 删除及依赖处理
- 首次设置流程
- 模型列表刷新

### 不包含的功能（V1）
- OAuth 认证（仅支持 API key 认证）
- Provider 使用分析（哪个 provider 使用最多）
- 每模型费用配置（在 FEAT-006 中处理）
- 自动 provider 故障转移（某个 provider 宕机时自动切换到另一个）
- API key 轮换提醒
- 每个 provider 多个 API key

## 业务规则

### Provider 规则
1. 发送消息需要至少一个启用的、有有效 API key 的 provider，但没有时应用仍可使用（浏览历史、管理 Agent 等）。引导用户配置 provider 但不强制。
2. 预配置 provider 模板不可删除，只能停用（可移除 API key）
3. 自定义 provider 可以完全删除
4. 没有 API key 的 provider 显示为"未配置"且不可使用
5. Provider 名称必须非空

### 模型规则
1. 初始设置后必须始终有且仅有一个全局默认模型
2. 动态模型在以下时机刷新：首次设置、手动刷新、可选的定期计划
3. 如果动态获取返回空列表，使用预设兜底列表替代
4. 手动添加的模型在动态模型刷新时保留
5. 用户可以移除手动添加的模型，但不能移除动态或预设模型

### 安全规则
1. API key 存储在由 Android Keystore 支持的 EncryptedSharedPreferences 中（不在 Room 数据库中）
2. API key 仅发送到其关联的 provider endpoint，绝不发送到其他地方
3. API key 同步到 Google Drive 是可选的，默认关闭
4. 应用绝不记录 API key 到日志（包括 debug 构建）

## 非功能性需求

### 性能
- Provider 列表加载时间 < 100ms
- 连接测试完成时间 < 10 秒（超时）
- 模型列表获取完成时间 < 10 秒（超时）
- API key 加密/解密对用户透明（无可感知延迟）

### 安全
- API key 使用 Android Keystore 加密存储
- 任何地方都不存在明文 API key 存储（数据库、shared preferences、日志）
- 所有 provider API 通信强制使用 HTTPS

### 可靠性
- 动态模型获取失败时优雅回退到预设模型
- 无网络时显示上次成功获取的缓存模型列表
- Provider 配置更改立即生效（不需要重启）

## 依赖关系

### 依赖的功能
- 无（这是基础模块）

### 被依赖的功能
- **FEAT-001（对话交互）**：需要 provider/模型来发送请求
- **FEAT-002（Agent 管理）**：Agent 可引用首选模型/provider
- **FEAT-006（Token/费用追踪）**：需要知道使用的是哪个模型来估算费用
- **FEAT-007（数据存储与同步）**：Provider 配置（默认不包括 API key）包含在同步中

## 错误处理

### 错误场景

1. **API key 无效**
   - 检测时机：连接测试或首次聊天请求
   - 展示："API key 无效。请检查您在 provider 设置中的密钥。"
   - 操作：链接到 provider 设置

2. **模型获取时网络错误**
   - 展示："无法获取模型列表。使用预设模型。"
   - 操作：显示预设模型，提供重试按钮

3. **自定义 endpoint 不可达**
   - 展示："无法连接到 [URL]。请验证 URL 和网络连接。"
   - 操作：用户检查 URL 和网络

4. **未配置任何 provider（用户尝试发送消息）**
   - 展示：聊天中的内联错误："未配置 Provider。请到设置中添加。"
   - 操作：链接到 设置 > 管理 Provider

5. **全局默认 provider 被删除或停用**
   - 展示："您的默认模型不再可用。请选择新的默认值。"
   - 操作：导航到 provider 设置

6. **API key 格式验证（如适用）**
   - 展示：API key 字段的内联验证（例如"OpenAI 密钥以 'sk-' 开头"）
   - 操作：用户修正密钥

## 未来改进

- [ ] **自动 provider 故障转移**：主 provider 失败时自动切换到备用 provider
- [ ] **API key 轮换提醒**：提醒用户定期轮换 API key
- [ ] **每 provider 多个 API key**：支持跨多个密钥的负载均衡
- [ ] **OAuth 认证**：除 API key 外支持基于 OAuth 的 provider 认证
- [ ] **Provider 使用分析**：追踪最常使用的 provider/模型
- [ ] **每模型费用配置**：用户自定义的价格覆盖（关联 FEAT-006）

## 测试要点

### 功能测试
- 验证预配置 provider（OpenAI、Anthropic、Gemini）可用
- 添加带 API key 的 provider 并验证动态获取模型
- 验证动态获取失败时显示预设兜底模型
- 添加自定义 provider 并手动输入模型
- 使用有效 API key 测试连接并验证成功
- 使用无效 API key 测试连接并验证错误
- 使用不可达 endpoint 测试连接并验证错误
- 设置全局默认模型并验证持久化
- 编辑 API key 并验证更改生效
- 删除 provider 并验证已移除
- 删除全局默认 provider 并验证应用提示设置新默认值
- 启用/停用 provider 并验证状态变更
- 验证 API key 在 UI 中默认遮掩显示
- 验证 API key 眼睛图标切换功能
- 验证未配置 provider 时的首次设置流程
- 手动刷新模型列表并验证更新

### 安全测试
- 验证 API key 在本地存储中加密（数据库中非明文）
- 验证 API key 不出现在应用日志中
- 验证 API key 仅发送到配置的 provider endpoint

### 边界情况
- Provider 的 API key 过期/被撤销
- Provider API 返回意外格式的模型列表
- 模型获取过程中网络断开
- 用户输入带有前后空格的 API key
- 自定义 endpoint 返回非标准响应格式
- 两个 provider 同名

## 变更历史

| 日期 | 版本 | 变更内容 | 变更人 |
|------|------|---------|--------|
| 2026-02-26 | 0.1 | 初始版本 | - |
| 2026-02-27 | 0.2 | 添加 UI 设计规范引用；Provider 管理从设置页面进入（非独立页面） | - |
| 2026-02-27 | 0.3 | 添加 RFC-003 引用；type 字段现表示 API 协议格式（移除单独的 `custom` 类型）；更新自定义 endpoint 部分 | - |
