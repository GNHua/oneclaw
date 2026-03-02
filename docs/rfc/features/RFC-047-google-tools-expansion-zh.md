# RFC-047：Google Workspace 工具扩展

## 文档信息
- **RFC ID**: RFC-047
- **关联 PRD**: [FEAT-047 (Google Tools Expansion)](../../prd/features/FEAT-047-google-tools-expansion.md)
- **关联 RFC**: [RFC-040 (Tool Group Routing)](RFC-040-tool-group-routing.md)
- **创建时间**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **作者**: TBD

## 概述

### 背景

OneClawShadow 目前支持 8 个工具组的 Google Workspace 工具（Gmail、Gmail Settings、Calendar、Docs、Drive、Sheets、Slides、Tasks）。参考实现（oneclaw-1）中另有 24 个工具尚未在 shadow 中实现。本 RFC 新增其中 20 个工具，并增强 1 个已有工具，以实现接近功能对等。

三个 Google Places 工具不在本 RFC 范围内，因为它们需要 shadow 中尚不存在的 API key 桥接机制（推迟至 FEAT-048）。

### 目标

1. 在 8 个现有工具组中新增 20 个工具
2. 为 `gmail_trash` 增加批量支持（单个 ID 或 ID 数组）
3. 新增 `resolveLabels()` 辅助函数，用于将标签名称转换为 ID 以支持批量操作
4. 所有变更仅涉及 JS/JSON 资源文件——零 Kotlin 修改

### 非目标

- Google Places 工具（需要 API key 桥接）
- 新增工具组
- Kotlin 代码变更
- 新增 OAuth scope（所有必需的 scope 已存在）

## 技术设计

### 架构

所有 20 个新工具遵循 RFC-040 中建立的现有模式：
- **JSON manifest**（`.json`）：工具名称、描述、函数引用、参数 schema、超时时间
- **JavaScript 实现**（`.js`）：使用该组 `*Fetch()` 辅助函数的异步函数，通过 `google.getAccessToken()` 获取 OAuth token

无需任何新基础设施。每个新工具是在现有 JS 文件中新增的一个函数，并在 JSON manifest 中添加对应条目。

### Gmail 增强（6 个新增 + 1 个增强）

#### 增强：`gmail_trash` -- 批量支持

现有 `gmail_trash` 接受单个 `message_id` 字符串。增强版本接受以下两种形式：
- `message_id`（string）-- 单条消息，使用 `POST /messages/{id}/trash`（向后兼容）
- `message_ids`（array）-- 多条消息，使用 `POST /messages/batchModify`，参数为 `addLabelIds: ["TRASH"], removeLabelIds: ["INBOX"]`

`ensureArray()` 辅助函数用于规范化输入。

#### 辅助函数：`resolveLabels(labels)`

将标签名称或 ID 数组转换为 Gmail 标签 ID：
1. 系统标签（INBOX、SENT、TRASH 等）原样返回
2. 匹配 `Label_*` 模式的标签视为 ID
3. 其他所有字符串通过 `GET /labels` 按名称查找（带惰性缓存）

供 `gmailModifyLabels` 和 `gmailBatchModify` 使用。

#### 新增工具

| 工具 | API 调用 | 备注 |
|------|----------|-------|
| `gmail_modify_labels` | `POST /messages/{id}/modify` 或 `POST /threads/{id}/modify` | 同时支持消息和会话目标 |
| `gmail_delete_label` | `DELETE /labels/{id}` | 处理 204 No Content |
| `gmail_get_draft` | `GET /drafts/{id}?format=full` | 提取邮件头和正文 |
| `gmail_delete_draft` | `DELETE /drafts/{id}` | 处理 204 No Content |
| `gmail_history` | `GET /history?startHistoryId=...` | 返回变更记录 |
| `gmail_batch_modify` | `POST /messages/batchModify` | 使用 `resolveLabels` 进行名称到 ID 的转换 |

### Gmail Settings（3 个新增）

| 工具 | API 调用 |
|------|----------|
| `gmail_settings_add_forwarding` | `POST /settings/forwardingAddresses` |
| `gmail_settings_set_auto_forward` | `PUT /settings/autoForwarding` |
| `gmail_settings_list_delegates` | `GET /settings/delegates` |

### Calendar（3 个新增）

| 工具 | API 调用 | 备注 |
|------|----------|-------|
| `calendar_respond` | 获取事件，修改与会者，PUT 回写 | 使用 `google.getAccountEmail()` 查找/添加用户与会者 |
| `calendar_list_colors` | `GET /colors` | 返回事件和日历的颜色定义 |
| `calendar_instances` | `GET /calendars/{id}/events/{id}/instances` | 列出循环事件的各个实例 |

### Docs（2 个新增）

| 工具 | API 调用 |
|------|----------|
| `docs_delete_range` | `POST /documents/{id}:batchUpdate`，使用 `deleteContentRange` |
| `docs_find_replace` | `POST /documents/{id}:batchUpdate`，使用 `replaceAllText` |

### Drive（2 个新增）

| 工具 | API 调用 |
|------|----------|
| `drive_copy` | `POST /files/{id}/copy` |
| `drive_permissions` | `GET /files/{id}/permissions` |

### Sheets（2 个新增）

| 工具 | API 调用 |
|------|----------|
| `sheets_metadata` | `GET /spreadsheets/{id}?fields=...` |
| `sheets_create` | `POST /spreadsheets` |

### Slides（1 个新增）

| 工具 | API 调用 |
|------|----------|
| `slides_list_slides` | `GET /presentations/{id}`，提取包含 objectId、index、layout 的 slides 数组 |

### Tasks（1 个新增）

| 工具 | API 调用 |
|------|----------|
| `tasks_get_task` | `GET /lists/{listId}/tasks/{taskId}` |

## 文件变更

| 文件 | 操作 |
|------|--------|
| `app/src/main/assets/js/tools/google_gmail.json` | 修改：增强 `gmail_trash`，添加 6 个工具条目 |
| `app/src/main/assets/js/tools/google_gmail.js` | 修改：添加 `ensureArray`、`resolveLabels`，增强 `gmailTrash`，添加 6 个函数 |
| `app/src/main/assets/js/tools/google_gmail_settings.json` | 修改：添加 3 个工具条目 |
| `app/src/main/assets/js/tools/google_gmail_settings.js` | 修改：添加 3 个函数 |
| `app/src/main/assets/js/tools/google_calendar.json` | 修改：添加 3 个工具条目 |
| `app/src/main/assets/js/tools/google_calendar.js` | 修改：添加 3 个函数 |
| `app/src/main/assets/js/tools/google_docs.json` | 修改：添加 2 个工具条目 |
| `app/src/main/assets/js/tools/google_docs.js` | 修改：添加 2 个函数 |
| `app/src/main/assets/js/tools/google_drive.json` | 修改：添加 2 个工具条目 |
| `app/src/main/assets/js/tools/google_drive.js` | 修改：添加 2 个函数 |
| `app/src/main/assets/js/tools/google_sheets.json` | 修改：添加 2 个工具条目 |
| `app/src/main/assets/js/tools/google_sheets.js` | 修改：添加 2 个函数 |
| `app/src/main/assets/js/tools/google_slides.json` | 修改：添加 1 个工具条目 |
| `app/src/main/assets/js/tools/google_slides.js` | 修改：添加 1 个函数 |
| `app/src/main/assets/js/tools/google_tasks.json` | 修改：添加 1 个工具条目 |
| `app/src/main/assets/js/tools/google_tasks.js` | 修改：添加 1 个函数 |

**零 Kotlin 变更。** 所有新工具使用现有的 OAuth、fetch 和 auth bridge 基础设施。

## 测试

### 自动化测试
- `./gradlew assembleDebug` -- 构建成功，包含所有修改后的资源文件
- `./gradlew test` -- 所有现有 JVM 测试通过（纯 JS 变更无需新增测试）

### 手动验证
对每个工具组，通过 `load_tool_group` 加载后调用代表性工具：
1. Gmail：对 3 条消息执行 `gmail_batch_modify`，测试 `gmail_history`、`gmail_get_draft`
2. Gmail Settings：测试 `gmail_settings_list_delegates`
3. Calendar：使用 `calendar_respond` 接受一个事件，测试 `calendar_list_colors`
4. Docs：在测试文档上执行 `docs_find_replace`
5. Drive：使用 `drive_copy` 复制文件，再对其执行 `drive_permissions`
6. Sheets：使用 `sheets_create` 创建新电子表格，测试 `sheets_metadata`
7. Slides：测试 `slides_list_slides`
8. Tasks：测试 `tasks_get_task`

## 回滚方案

所有变更均在资源文件中。回滚 git commit 即可恢复之前的工具集。无需回滚数据库迁移或 Kotlin 变更。
