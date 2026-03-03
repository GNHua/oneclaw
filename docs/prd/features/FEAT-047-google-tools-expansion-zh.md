# Google Workspace 工具扩展

## 功能信息
- **功能 ID**: FEAT-047
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **优先级**: P1 (Should Have)
- **负责人**: TBD
- **相关 RFC**: [RFC-047 (Google Tools Expansion)](../../rfc/features/RFC-047-google-tools-expansion.md)
- **相关功能**: [FEAT-040 (Tool Group Routing)](FEAT-040-tool-group-routing.md)

## 用户故事

**作为** OneClaw 的用户，
**我希望** 拥有一套与其他 AI Agent 平台相当的完整 Google Workspace 工具集，
**以便于** 我可以执行批量邮件管理、日历 RSVP 回复、文档编辑、文件复制等高级操作，而无需切换到浏览器。

### 典型场景
1. 用户说"把这 15 封促销邮件全部标记为已读并移到 Promotions 标签" -- AI 使用 `gmail_batch_modify` 在一次调用中对所有邮件应用标签变更。
2. 用户说"接受明天的团队会议邀请" -- AI 使用 `calendar_respond` 以"接受"状态进行 RSVP 回复。
3. 用户说"在我的季度报告文档中将 'Q3' 全部替换为 'Q4'" -- AI 使用 `docs_find_replace` 执行全局文本替换。
4. 用户说"复制这个电子表格并放到 Archive 文件夹中" -- AI 使用 `drive_copy` 并指定目标父文件夹。
5. 用户说"我的 Gmail 有哪些转发地址？" -- AI 使用 `gmail_settings_list_forwarding_addresses`（已有工具）查询，或通过 `gmail_settings_add_forwarding` 添加新地址。

## 功能描述

### 概述
本功能将 Google Workspace 工具覆盖范围从现有工具集扩展为新增 20 个工具以及 1 个增强工具，分布于 8 个现有工具组。重点涵盖批量操作（Gmail 批量修改、批量移入垃圾桶）、日历活动回复、文档编辑操作以及文件管理工具。

### 新工具汇总

| 工具组 | 工具 | 数量 |
|-------|-------|-------|
| Google Gmail | `gmail_modify_labels`, `gmail_delete_label`, `gmail_get_draft`, `gmail_delete_draft`, `gmail_history`, `gmail_batch_modify` + 增强版 `gmail_trash` | 6 个新增 + 1 个增强 |
| Google Gmail Settings | `gmail_settings_add_forwarding`, `gmail_settings_set_auto_forward`, `gmail_settings_list_delegates` | 3 个新增 |
| Google Calendar | `calendar_respond`, `calendar_list_colors`, `calendar_instances` | 3 个新增 |
| Google Docs | `docs_delete_range`, `docs_find_replace` | 2 个新增 |
| Google Drive | `drive_copy`, `drive_permissions` | 2 个新增 |
| Google Sheets | `sheets_metadata`, `sheets_create` | 2 个新增 |
| Google Slides | `slides_list_slides` | 1 个新增 |
| Google Tasks | `tasks_get_task` | 1 个新增 |

### 超出范围
- **Google Places**（3 个工具：`places_search`, `places_details`, `places_nearby`）-- 推迟至 FEAT-048，因为其所需的 API key 桥接机制在当前架构中尚不存在。

## 成功标准
1. 所有 20 个新工具和 1 个增强工具均可通过工具组路由系统正确加载并执行。
2. 现有工具继续正常运行，无回归问题。
3. 无需修改任何 Kotlin 代码 -- 所有变更均位于 JS/JSON 资产文件中。
4. 构建和现有测试无需修改即可通过。

## 风险与缓解措施
| 风险 | 可能性 | 影响 | 缓解措施 |
|------|-----------|--------|------------|
| OAuth scope 不足以支持新操作 | 低 | 中 | 所有所需 scope 已在 GoogleAuthManager.SCOPES 中声明 |
| 批量操作触发 API 速率限制 | 低 | 低 | Gmail API 批量接口拥有宽裕的配额 |
| 204 No Content 响应导致 gmailFetch 出错 | 中 | 中 | 新增的删除工具使用直接 fetch 并显式处理 204 响应 |
