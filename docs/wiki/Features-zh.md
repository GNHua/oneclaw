# 功能概览

OneClaw 共有 51 个功能，每个功能均附有 PRD 和 RFC 文档。以下按类别列出所有功能。

## 核心功能 (P0)

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-001 | 对话交互 | 实时流式对话，支持工具调用展示与 Markdown 渲染 | [PRD](../prd/features/FEAT-001-chat.md) | [RFC](../rfc/features/RFC-001-chat-interaction.md) |
| FEAT-002 | Agent 管理 | 创建并配置 AI 人格，支持自定义系统提示词和工具集 | [PRD](../prd/features/FEAT-002-agent.md) | [RFC](../rfc/features/RFC-002-agent-management.md) |
| FEAT-003 | 提供商管理 | 配置 AI 提供商（OpenAI、Anthropic、Gemini），管理 API 密钥和模型 | [PRD](../prd/features/FEAT-003-provider.md) | [RFC](../rfc/features/RFC-003-provider-management.md) |
| FEAT-004 | 工具系统 | 可扩展的工具框架，供 AI 与设备及服务进行交互 | [PRD](../prd/features/FEAT-004-tool-system.md) | [RFC](../rfc/features/RFC-004-tool-system.md) |
| FEAT-005 | 会话管理 | 多对话会话管理，支持重命名、删除和自动命名 | [PRD](../prd/features/FEAT-005-session.md) | [RFC](../rfc/features/RFC-005-session-management.md) |

## 数据与基础设施

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-006 | Token 用量追踪 | 按消息、会话和模型追踪并展示 Token 使用量 | [PRD](../prd/features/FEAT-006-token-tracking.md) | [RFC](../rfc/features/RFC-006-token-tracking.md) |
| FEAT-007 | 数据同步 | Google Drive 备份及本地 JSON 导入/导出 | [PRD](../prd/features/FEAT-007-data-sync.md) | [RFC](../rfc/features/RFC-007-data-sync.md) |
| FEAT-008 | 通知 | 后台任务完成与失败的通知推送 | [PRD](../prd/features/FEAT-008-notifications.md) | [RFC](../rfc/features/RFC-008-notifications.md) |
| FEAT-009 | 设置 | 应用配置：主题、提供商、Agent、数据备份 | [PRD](../prd/features/FEAT-009-settings.md) | [RFC](../rfc/features/RFC-009-settings.md) |

## 对话增强

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-010 | 并发输入 | 在 AI 仍在流式输出时发送多条消息 | [PRD](../prd/features/FEAT-010-concurrent-input.md) | [RFC](../rfc/features/RFC-010-concurrent-user-input.md) |
| FEAT-011 | 自动压缩 | 在接近上下文限制时自动压缩消息历史 | [PRD](../prd/features/FEAT-011-auto-compact.md) | [RFC](../rfc/features/RFC-011-auto-compact.md) |
| FEAT-016 | 对话输入栏重设计 | 改进的对话输入栏，支持附件和技能调用 | [PRD](../prd/features/FEAT-016-chat-input-redesign.md) | [RFC](../rfc/features/RFC-016-chat-input-redesign.md) |
| FEAT-026 | 文件附件 | 在对话消息中附加图片和文件 | [PRD](../prd/features/FEAT-026-file-attachments.md) | [RFC](../rfc/features/RFC-026-file-attachments.md) |

## 工具系统

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-012 | JS 工具引擎 | 通过 QuickJS 沙箱运行时执行 JavaScript 工具 | [PRD](../prd/features/FEAT-012-js-tool-engine.md) | [RFC](../rfc/features/RFC-012-js-tool-engine.md) |
| FEAT-015 | JS 工具迁移 | 将内置工具从 JS 迁移至 Kotlin 以提升性能 | [PRD](../prd/features/FEAT-015-js-tool-migration.md) | [RFC](../rfc/features/RFC-015-js-tool-migration.md) |
| FEAT-017 | 工具管理 | 用于启用/禁用工具和查看工具状态的界面 | [PRD](../prd/features/FEAT-017-tool-management.md) | [RFC](../rfc/features/RFC-017-tool-management.md) |
| FEAT-018 | JS 工具分组 | 对 JavaScript 工具进行分组以实现有序加载 | [PRD](../prd/features/FEAT-018-js-tool-group.md) | [RFC](../rfc/features/RFC-018-js-tool-group.md) |
| FEAT-034 | JS 求值工具 | 在对话中执行 JavaScript 以完成计算任务 | [PRD](../prd/features/FEAT-034-js-eval-tool.md) | [RFC](../rfc/features/RFC-034-js-eval-tool.md) |
| FEAT-035 | JS 工具创建器 | AI 辅助创建自定义 JavaScript 工具 | [PRD](../prd/features/FEAT-035-js-tool-creator.md) | [RFC](../rfc/features/RFC-035-js-tool-creator.md) |
| FEAT-036 | 配置工具 | 通过对话管理提供商、模型、Agent 和设置的工具 | [PRD](../prd/features/FEAT-036-config-tools.md) | [RFC](../rfc/features/RFC-036-config-tools.md) |
| FEAT-040 | 工具分组路由 | 自动将工具分组路由至指定 Agent | [PRD](../prd/features/FEAT-040-tool-group-routing.md) | [RFC](../rfc/features/RFC-040-tool-group-routing.md) |
| FEAT-044 | 工具分组持久化 | 跨会话持久化工具分组的启用状态 | [PRD](../prd/features/FEAT-044-tool-group-persistence.md) | [RFC](../rfc/features/RFC-044-tool-group-persistence.md) |

## Agent 与技能系统

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-014 | Agent 技能 | 可通过斜杠命令调用的可复用提示词模板 | [PRD](../prd/features/FEAT-014-agent-skill.md) | [RFC](../rfc/features/RFC-014-agent-skill.md) |
| FEAT-020 | Agent 增强 | Agent 参数配置、首选模型和克隆功能 | [PRD](../prd/features/FEAT-020-agent-enhancement.md) | [RFC](../rfc/features/RFC-020-agent-enhancement.md) |
| FEAT-038 | Agent 参数 | 按 Agent 配置温度和模型覆盖 | [PRD](../prd/features/FEAT-038-agent-parameters.md) | [RFC](../rfc/features/RFC-038-agent-parameters.md) |

## 记忆与搜索

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-013 | 记忆系统 | 持久化长期记忆，支持每日日志和系统提示词注入 | [PRD](../prd/features/FEAT-013-memory.md) | [RFC](../rfc/features/RFC-013-memory-system.md) |
| FEAT-023 | 记忆增强 | 基于 BM25 与向量嵌入的混合搜索，含时间衰减 | [PRD](../prd/features/FEAT-023-memory-enhancement.md) | [RFC](../rfc/features/RFC-023-memory-enhancement.md) |
| FEAT-032 | 历史搜索 | 跨会话、记忆和每日日志的统一搜索 | [PRD](../prd/features/FEAT-032-search-history.md) | [RFC](../rfc/features/RFC-032-search-history.md) |
| FEAT-049 | 记忆质量 | 记忆质量评分与自动清理 | [PRD](../prd/features/FEAT-049-memory-quality.md) | [RFC](../rfc/features/RFC-049-memory-quality.md) |
| FEAT-050 | Git 版本控制 | 对应用存储中所有文本文件进行基于 Git 的版本管理，支持自动提交 | [PRD](../prd/features/FEAT-050-git-versioning.md) | [RFC](../rfc/features/RFC-050-git-versioning.md) |
| FEAT-051 | Git 历史界面 | 应用内 Git 提交历史浏览器，支持筛选和差异对比视图 | [PRD](../prd/features/FEAT-051-git-history-ui.md) | [RFC](../rfc/features/RFC-051-git-history-ui.md) |

## 网络与内容

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-021 | 网页抓取工具 | 抓取网页并转换为 Markdown | [PRD](../prd/features/FEAT-021-kotlin-webfetch.md) | [RFC](../rfc/features/RFC-021-kotlin-webfetch.md) |
| FEAT-022 | WebView 浏览器 | 完整浏览器渲染，支持截图和内容提取 | [PRD](../prd/features/FEAT-022-webview-browser.md) | [RFC](../rfc/features/RFC-022-webview-browser.md) |
| FEAT-025 | 文件浏览 | 浏览和预览应用存储中的文件 | [PRD](../prd/features/FEAT-025-file-browsing.md) | [RFC](../rfc/features/RFC-025-file-browsing.md) |
| FEAT-029 | Shell 执行工具 | 在设备上执行 Shell 命令 | [PRD](../prd/features/FEAT-029-exec-tool.md) | [RFC](../rfc/features/RFC-029-exec-tool.md) |
| FEAT-031 | 网络搜索 | 提供商原生网络搜索（grounding） | [PRD](../prd/features/FEAT-031-provider-web-search.md) | [RFC](../rfc/features/RFC-031-provider-web-search.md) |
| FEAT-033 | PDF 工具 | 从 PDF 文件中提取文本、元数据并渲染页面 | [PRD](../prd/features/FEAT-033-pdf-tools.md) | [RFC](../rfc/features/RFC-033-pdf-tools.md) |

## 定时任务

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-019 | 定时任务 | 基于时间的任务调度，支持每日和每周重复 | [PRD](../prd/features/FEAT-019-scheduled-tasks.md) | [RFC](../rfc/features/RFC-019-scheduled-tasks.md) |
| FEAT-027 | 定时任务工具 | 通过对话创建和管理定时任务 | [PRD](../prd/features/FEAT-027-scheduled-task-tools.md) | [RFC](../rfc/features/RFC-027-scheduled-task-tools.md) |
| FEAT-028 | 定时任务详情 | 包含执行历史的详细视图 | [PRD](../prd/features/FEAT-028-scheduled-task-detail.md) | [RFC](../rfc/features/RFC-028-scheduled-task-detail.md) |
| FEAT-037 | 精确闹钟权限 | 处理 Android 12+ 精确闹钟权限要求 | [PRD](../prd/features/FEAT-037-exact-alarm-permission.md) | [RFC](../rfc/features/RFC-037-exact-alarm-permission.md) |

## Google 集成

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-030 | Google Workspace | Google Drive 文件访问工具 | [PRD](../prd/features/FEAT-030-google-workspace.md) | [RFC](../rfc/features/RFC-030-google-workspace.md) |
| FEAT-043 | Google OAuth 问题修复 | 修复 Google OAuth 流程的稳定性问题 | [PRD](../prd/features/FEAT-043-google-oauth-bugfix.md) | [RFC](../rfc/features/RFC-043-google-oauth-bugfix.md) |
| FEAT-047 | Google 工具扩展 | 额外的 Google Drive 工具（创建、更新、分享） | [PRD](../prd/features/FEAT-047-google-tools-expansion.md) | [RFC](../rfc/features/RFC-047-google-tools-expansion.md) |

## 消息桥接

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-024 | 消息桥接 | 以前台服务形式运行的多渠道消息桥接 | [PRD](../prd/features/FEAT-024-messaging-bridge.md) | [RFC](../rfc/features/RFC-024-messaging-bridge.md) |
| FEAT-041 | 桥接改进 | 提升桥接可靠性和用户体验 | [PRD](../prd/features/FEAT-041-bridge-improvements.md) | [RFC](../rfc/features/RFC-041-bridge-improvements.md) |
| FEAT-042 | Telegram 图片传输 | Telegram 渠道的图片发送与接收 | [PRD](../prd/features/FEAT-042-telegram-image-delivery.md) | [RFC](../rfc/features/RFC-042-telegram-image-delivery.md) |
| FEAT-045 | 桥接会话同步 | 将桥接会话与主应用界面同步 | [PRD](../prd/features/FEAT-045-bridge-session-sync.md) | [RFC](../rfc/features/RFC-045-bridge-session-sync.md) |
| FEAT-046 | 桥接响应修复 | 修复响应传递的可靠性问题 | [PRD](../prd/features/FEAT-046-bridge-response-delivery-fix.md) | [RFC](../rfc/features/RFC-046-bridge-response-delivery-fix.md) |
| FEAT-048 | 桥接渠道功能对齐 | 所有消息渠道的功能对等 | [PRD](../prd/features/FEAT-048-bridge-channel-parity.md) | [RFC](../rfc/features/RFC-048-bridge-channel-parity.md) |

## 维护

| ID | 功能 | 描述 | PRD | RFC |
|----|---------|-------------|-----|-----|
| FEAT-039 | 问题修复与界面优化 | 跨功能的 Bug 修复与 UI 细节优化 | [PRD](../prd/features/FEAT-039-bugfix-polish.md) | [RFC](../rfc/features/RFC-039-bugfix-polish.md) |
