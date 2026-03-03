# Google Workspace 工具

## 功能信息
- **功能 ID**: FEAT-030
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（必须实现）
- **负责人**: 待定
- **关联 RFC**: RFC-030（待定）

## 用户故事

**作为** 使用 OneClaw 的 AI agent，
**我希望** 通过经过身份验证的 API 工具访问 Google Workspace 服务（Gmail、Calendar、Tasks、Contacts、Drive、Docs、Sheets、Slides、Forms），
**以便** 我可以代表用户管理其电子邮件、日历事件、任务、联系人、文件、文档、电子表格、演示文稿和表单。

### 典型场景

1. 用户要求 agent 查看未读邮件。agent 以查询参数 `is:unread` 调用 `gmail_search`，并返回最近未读邮件的摘要。
2. 用户说"明天下午 2 点和 Alice 安排一个会议"。agent 以适当的日期、时间和参会者调用 `calendar_create_event`。
3. 用户问"本周有哪些任务到期？"agent 调用 `tasks_list_tasks` 并筛选当周到期的任务。
4. 用户说"帮我找 John Smith 的电话号码"。agent 以姓名调用 `contacts_search`，并返回匹配的联系人详情。
5. 用户要求将本地文件上传到 Google Drive。agent 以文件路径和目标文件夹调用 `drive_upload`。
6. 用户说"新建一个包含预算模板的电子表格"。agent 调用 `sheets_create`，再调用 `sheets_update_values` 填充模板内容。
7. 用户问"我的反馈表单有哪些回复？"agent 调用 `forms_list_responses` 并汇总结果。
8. 用户说"帮我给老板最新的邮件起草一封回复"。agent 调用 `gmail_search` 找到该邮件，用 `gmail_get_message` 读取内容，然后调用 `gmail_create_draft` 创建回复草稿。
9. 用户说"把 Q1 报告分享给市场团队"。agent 调用 `drive_search` 找到文件，再调用 `drive_share` 添加权限。
10. 用户说"在我的演示文稿中添加一张关于营收的幻灯片"。agent 调用 `slides_add_slide`，并使用 Google Slides API 添加内容。

## 功能描述

### 概述

FEAT-030 为 OneClaw 新增 Google Workspace 集成，跨 10 个 Google 服务提供约 89 个工具。这是从经过验证的 oneclaw-1 插件系统移植而来，并适配了 shadow-4 的架构（使用 QuickJS 执行引擎的 JS 工具组）。

该功能包含：
1. **BYOK OAuth 认证** -- 用户自带 GCP OAuth Client ID 和 Client Secret，实现安全、自主管理的身份验证
2. **10 个 Google 服务工具组** -- 每个服务以 JSON+JS 资产对的形式注册为一个 JS 工具组
3. **设置 UI** -- Google 账号配置与登录管理

### 架构概览

```
用户
    | 在设置中配置 OAuth 凭据
    | 通过浏览器 OAuth 流程登录
    v
GoogleAuthManager  [新增 - Kotlin，处理 OAuth 流程]
    |
    +-- EncryptedSharedPreferences（存储令牌）
    |
    v
AI 模型
    | 工具调用：gmail_search(query="is:unread") ...
    v
ToolExecutionEngine（无变更）
    |
    v
ToolRegistry
    |  JS 工具组：google_gmail
    |  JS 工具组：google_calendar
    |  JS 工具组：google_tasks
    |  ... （共 10 个组）
    v
JsExecutionEngine（已修改）
    |
    +-- GoogleAuthBridge [新增] -- google.getAccessToken()
    +-- FileTransferBridge [新增] -- downloadToFile(), uploadMultipart()
    +-- FetchBridge（已有）-- fetch()
    +-- FsBridge（已有）-- fs.*
    +-- ConsoleBridge（已有）-- console.*
    |
    v
QuickJS Runtime
    |  执行 google_{service}.js
    |  调用 Google Workspace REST API
    v
Google Workspace API
    |
    v
结果返回给 AI 模型
```

### 10 个 Google 服务

#### 1. Google Gmail（18 个工具）

| 工具 | 描述 |
|------|------|
| `gmail_search` | 使用 Gmail 查询语法搜索邮件 |
| `gmail_get_message` | 获取特定邮件的完整内容 |
| `gmail_send` | 发送新邮件（纯文本和 HTML） |
| `gmail_reply` | 回复现有邮件线程 |
| `gmail_delete` | 将邮件移至回收站（批量，最多 1000 封） |
| `gmail_list_labels` | 列出所有标签/文件夹 |
| `gmail_get_thread` | 获取线程中的所有邮件 |
| `gmail_modify_labels` | 为邮件添加/移除标签 |
| `gmail_batch_modify` | 批量修改多封邮件的标签 |
| `gmail_list_drafts` | 列出草稿 |
| `gmail_get_draft` | 获取特定草稿 |
| `gmail_create_draft` | 创建新草稿 |
| `gmail_send_draft` | 发送现有草稿 |
| `gmail_delete_draft` | 删除草稿 |
| `gmail_create_label` | 创建新标签 |
| `gmail_delete_label` | 删除标签 |
| `gmail_get_attachment` | 下载附件 |
| `gmail_history` | 获取邮箱变更历史 |

#### 2. Google Gmail 设置（11 个工具）

| 工具 | 描述 |
|------|------|
| `gmail_list_filters` | 列出所有 Gmail 过滤器 |
| `gmail_create_filter` | 创建过滤器（条件 + 操作） |
| `gmail_delete_filter` | 按 ID 删除过滤器 |
| `gmail_get_vacation` | 获取假期自动回复设置 |
| `gmail_set_vacation` | 设置/更新假期自动回复 |
| `gmail_list_forwarding` | 列出转发地址 |
| `gmail_add_forwarding` | 添加转发地址 |
| `gmail_get_auto_forward` | 获取自动转发设置 |
| `gmail_set_auto_forward` | 启用/禁用自动转发 |
| `gmail_list_send_as` | 列出发件人别名 |
| `gmail_list_delegates` | 列出委托人 |

#### 3. Google Calendar（11 个工具）

| 工具 | 描述 |
|------|------|
| `calendar_list_events` | 列出即将到来的事件（默认：7 天，主日历） |
| `calendar_get_event` | 按 ID 获取事件详情 |
| `calendar_create_event` | 创建新事件 |
| `calendar_update_event` | 更新现有事件 |
| `calendar_delete_event` | 删除事件 |
| `calendar_quick_add` | 通过自然语言文本快速添加事件 |
| `calendar_list_calendars` | 列出所有日历 |
| `calendar_freebusy` | 查询空闲/忙碌状态 |
| `calendar_instances` | 列出重复事件的各实例 |
| `calendar_respond` | 响应事件邀请（接受/拒绝/暂定） |
| `calendar_list_colors` | 列出可用的日历颜色 |

#### 4. Google Tasks（7 个工具）

| 工具 | 描述 |
|------|------|
| `tasks_list_tasklists` | 列出所有任务列表 |
| `tasks_list_tasks` | 列出特定列表中的任务 |
| `tasks_get_task` | 获取任务详情 |
| `tasks_create` | 创建任务（通过 parent 支持子任务） |
| `tasks_update` | 更新任务 |
| `tasks_complete` | 将任务标记为已完成 |
| `tasks_delete` | 删除任务 |

#### 5. Google Contacts（7 个工具）

| 工具 | 描述 |
|------|------|
| `contacts_search` | 按姓名/邮箱/电话搜索（最多 30 条） |
| `contacts_list` | 分页列出联系人 |
| `contacts_get` | 获取联系人完整详情 |
| `contacts_create` | 创建联系人 |
| `contacts_update` | 更新联系人（自动获取 etag） |
| `contacts_delete` | 删除联系人 |
| `contacts_directory` | 列出 Workspace 域目录 |

#### 6. Google Drive（13 个工具）

| 工具 | 描述 |
|------|------|
| `drive_list` | 列出文件（支持查询、排序、分页） |
| `drive_search` | 全文搜索文件 |
| `drive_get` | 获取文件元数据 |
| `drive_mkdir` | 创建文件夹 |
| `drive_copy` | 复制文件 |
| `drive_rename` | 重命名文件 |
| `drive_move` | 在文件夹之间移动文件 |
| `drive_delete` | 删除文件（移至回收站） |
| `drive_share` | 共享文件（设置权限） |
| `drive_permissions` | 列出文件权限 |
| `drive_download` | 将文件下载到本地存储 |
| `drive_upload` | 将文件上传到 Drive |
| `drive_export` | 将 Google Docs/Sheets/Slides 导出为 PDF/DOCX 等格式 |

#### 7. Google Docs（6 个工具）

| 工具 | 描述 |
|------|------|
| `docs_get` | 获取完整文档结构 |
| `docs_create` | 创建新的空白文档 |
| `docs_get_text` | 从文档中提取纯文本 |
| `docs_insert` | 在特定索引位置插入文本 |
| `docs_delete_range` | 删除内容范围 |
| `docs_find_replace` | 查找并替换所有匹配项 |

#### 8. Google Sheets（7 个工具）

| 工具 | 描述 |
|------|------|
| `sheets_get_values` | 读取指定范围的单元格值 |
| `sheets_update_values` | 更新单元格 |
| `sheets_append` | 在最后一行数据后追加行 |
| `sheets_clear` | 清除范围内的值（保留格式） |
| `sheets_metadata` | 获取电子表格标题和工作表属性 |
| `sheets_create` | 创建新电子表格 |
| `sheets_batch_update` | 批量结构性变更（添加/删除工作表、格式化、合并、排序） |

#### 9. Google Slides（6 个工具）

| 工具 | 描述 |
|------|------|
| `slides_get` | 获取演示文稿详情 |
| `slides_create` | 创建新演示文稿 |
| `slides_list_slides` | 列出所有幻灯片 |
| `slides_get_slide_text` | 从特定幻灯片中提取文本 |
| `slides_add_slide` | 添加幻灯片（支持布局类型） |
| `slides_delete_slide` | 删除幻灯片 |

#### 10. Google Forms（3 个工具）

| 工具 | 描述 |
|------|------|
| `forms_get` | 获取表单结构（项目、问题类型、选项） |
| `forms_list_responses` | 列出已提交的回复及摘要 |
| `forms_get_response` | 获取包含所有答案的特定回复 |

### BYOK OAuth 认证

OneClaw 采用 BYOK（自带密钥）OAuth 流程。用户提供自己的 GCP 桌面版 OAuth Client ID 和 Client Secret，从而完全掌控自己的凭据和 API 访问权限。

#### OAuth 流程

```
1. 用户打开"设置 > Google 账号"
2. 用户输入其 GCP OAuth Client ID 和 Client Secret
3. 用户点击"保存凭据"
4. 用户点击"使用 Google 登录"
5. 应用在随机端口启动回环 HTTP 服务器（127.0.0.1:{port}）
6. 应用打开浏览器，跳转至 Google OAuth 授权 URL：
   - client_id, redirect_uri=http://127.0.0.1:{port}
   - scope = 全部 11 个 Workspace 授权范围
   - access_type=offline, prompt=consent
7. 用户在浏览器中授予权限
8. 浏览器重定向至 http://127.0.0.1:{port}?code=...
9. 回环服务器捕获授权码
10. 应用用授权码换取令牌（POST https://oauth2.googleapis.com/token）
11. 应用获取用户邮箱（GET https://www.googleapis.com/oauth2/v2/userinfo）
12. 应用将 refresh token、access token、过期时间和邮箱存入 EncryptedSharedPreferences
13. 设置 UI 更新，显示已登录状态及用户邮箱
```

#### 所需 Google Workspace 授权范围

| 授权范围 | 服务 |
|----------|------|
| `https://www.googleapis.com/auth/gmail.modify` | Gmail（读取 + 发送 + 修改） |
| `https://www.googleapis.com/auth/gmail.settings.basic` | Gmail 设置 |
| `https://www.googleapis.com/auth/calendar` | Google Calendar |
| `https://www.googleapis.com/auth/tasks` | Google Tasks |
| `https://www.googleapis.com/auth/contacts` | Google Contacts |
| `https://www.googleapis.com/auth/drive` | Google Drive |
| `https://www.googleapis.com/auth/documents` | Google Docs |
| `https://www.googleapis.com/auth/spreadsheets` | Google Sheets |
| `https://www.googleapis.com/auth/presentations` | Google Slides |
| `https://www.googleapis.com/auth/forms.body.readonly` | Google Forms（结构） |
| `https://www.googleapis.com/auth/forms.responses.readonly` | Google Forms（回复） |

### 设置 UI

在设置页面新增"Google 账号"设置项，点击后导航至专用的 Google 账号配置页面。

#### Google 账号设置页面

- **Client ID 输入框** -- 用于输入用户 GCP OAuth Client ID 的文本框
- **Client Secret 输入框** -- 用于输入 Client Secret 的密码遮罩文本框
- **保存凭据按钮** -- 将凭据存入 EncryptedSharedPreferences
- **使用 Google 登录按钮** -- 启动 OAuth 流程（仅在保存凭据后可用）
- **已登录状态** -- 显示已连接的 Google 账号邮箱及"退出登录"按钮
- **状态指示器** -- 显示配置状态（未配置 / 凭据已保存 / 已登录）

### 用户交互流程

#### 初始设置流程

```
1. 用户：打开"设置 > Google 账号"
2. UI：显示空白的 Client ID 和 Client Secret 输入框
3. 用户：输入 GCP OAuth 凭据并点击"保存"
4. UI：显示"凭据已保存"确认信息，启用"登录"按钮
5. 用户：点击"使用 Google 登录"
6. 系统：打开浏览器进行 Google OAuth 授权
7. 用户：在浏览器中授予权限
8. UI：显示已登录状态及邮箱（例如："user@gmail.com"）
```

#### 使用 Google 工具流程

```
1. 用户："查看我的未读邮件"
2. AI：调用 gmail_search(query="is:unread", max_results=10)
3. JS 引擎：执行 google_gmail.js::gmailSearch()
   a. 通过 google.getAccessToken() 获取 access token
   b. 调用 Gmail API：GET /gmail/v1/users/me/messages?q=is:unread
   c. 返回格式化的邮件列表
4. AI：向用户汇总未读邮件
```

#### 令牌刷新流程（透明）

```
1. AI 调用任意 Google 工具
2. JS 中调用 google.getAccessToken()
3. GoogleAuthManager 检查令牌过期时间
4. 若已过期（60 秒余量内）：使用 refresh_token 刷新
5. 返回有效的 access token
6. 工具继续执行 API 调用
```

## 验收标准

必须通过（全部必填）：

- [ ] BYOK OAuth 流程：用户可以输入 GCP Client ID + Secret、登录并获取令牌
- [ ] access token 过期时自动刷新
- [ ] 10 个服务的全部 89 个工具以 JS 工具组形式注册到 ToolRegistry
- [ ] 每个工具组均有有效的 JSON 定义文件和 JS 实现文件
- [ ] Gmail 工具：搜索、读取、发送、回复、草稿、标签管理功能正常
- [ ] Gmail 设置工具：过滤器、假期自动回复、转发管理功能正常
- [ ] Calendar 工具：列出、创建、更新、删除事件功能正常
- [ ] Tasks 工具：列出、创建、更新、完成、删除任务功能正常
- [ ] Contacts 工具：搜索、列出、创建、更新、删除联系人功能正常
- [ ] Drive 工具：列出、搜索、上传、下载、共享文件功能正常
- [ ] Docs 工具：获取、创建、插入、删除、查找替换功能正常
- [ ] Sheets 工具：读取、写入、追加、清除值功能正常
- [ ] Slides 工具：获取、创建、添加/删除幻灯片功能正常
- [ ] Forms 工具：获取表单结构、列出和获取回复功能正常
- [ ] Google 账号设置页面支持凭据配置及登录/退出操作
- [ ] 令牌和凭据存储在 EncryptedSharedPreferences 中（不存入 Room）
- [ ] 退出登录时在服务端吊销令牌（尽力而为）并清除本地存储
- [ ] 未登录时所有 Google 工具返回清晰的错误信息
- [ ] 所有 Layer 1A 测试通过

可选（V1 中锦上添花）：

- [ ] 针对瞬态 API 错误（429、5xx）的重试逻辑
- [ ] 多项目请求的批量操作
- [ ] OAuth 授权范围粒度控制（允许用户选择授权哪些服务）

## UI/UX 要求

### 设置页面变更

- 在设置页面列表中添加"Google 账号"项
- 位置：在"备份与同步"和"主题"之间
- 图标：账号/人物图标
- 副标题：显示已连接的邮箱或"未连接"
- 点击后导航至 Google 账号配置页面

### Google 账号页面

- 顶部区域：Client ID 和 Client Secret 输入框
- 中部区域：保存凭据按钮
- 底部区域：登录/退出按钮及状态显示
- 已登录状态：醒目显示邮箱地址
- 错误状态：操作失败时显示内联错误信息

## 功能边界

### 包含

- 带回环重定向的 BYOK OAuth 认证
- 令牌管理（存储、刷新、吊销）
- 10 个 Google Workspace 服务工具组（89 个工具）
- Google 账号设置 UI（凭据配置 + 登录/退出）
- 供 JS 工具访问 OAuth 令牌的 GoogleAuthBridge
- 供 Drive 上传/下载操作使用的 FileTransferBridge
- 认证失败、API 错误和频率限制的错误处理

### 不包含（V1）

- Google 服务账号认证（仅支持 OAuth 用户流程）
- OAuth 授权范围选择（所有范围一次性请求）
- Google Workspace Admin SDK 工具
- Google Maps / YouTube / 其他 Google API 集成
- 多账号支持（每次只支持一个 Google 账号）
- Google 数据的离线缓存
- Google 服务的实时同步或推送通知
- Google Workspace Marketplace 集成

## 业务规则

1. OAuth 凭据（Client ID、Client Secret）存储在 EncryptedSharedPreferences 中
2. Refresh token、access token 和令牌过期时间存储在 EncryptedSharedPreferences 中
3. access token 在过期前 60 秒刷新，以避免竞争条件
4. 令牌刷新受互斥锁保护，防止并发刷新风暴
5. 退出登录时先在服务端吊销令牌（尽力而为），再清除本地存储
6. 所有 Google API 调用使用 Bearer token 认证
7. 用户未登录时 Google 工具返回描述性错误信息
8. 用户必须自行提供 GCP OAuth 凭据（BYOK 模式）
9. OAuth 授权期间一次性请求全部 11 个 Google Workspace 授权范围

## 非功能性要求

### 性能

- OAuth 流程完成时间：5-15 秒（含浏览器交互）
- 令牌刷新：< 1 秒（单次 HTTPS 调用）
- 单次工具 API 调用：0.5-3 秒（取决于 Google API 和数据量）
- 工具组 JS 加载：< 100 毫秒（从 assets 加载）
- QuickJS 执行开销：每次工具调用 < 50 毫秒

### 安全性

- OAuth 凭据使用 Android KeyStore 存储于 EncryptedSharedPreferences
- access token 不记录日志，不在 UI 中暴露
- 回环 OAuth 重定向（127.0.0.1）防止外部拦截
- 仅通过 HTTPS 与 Google API 通信
- 退出登录时吊销令牌（尽力而为）
- 不在 Room 数据库中持久化凭据

### 可靠性

- 自动刷新令牌，余量为 60 秒
- 互斥锁保护刷新，防止令牌竞争条件
- 所有失败场景（未登录、已过期、网络错误、API 错误）均有清晰的错误信息

### 兼容性

- 需要 Android API 24+
- 所有 Google API 操作均需要网络连接
- 支持个人 Gmail 账号和 Google Workspace 账号

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：Tool 接口、ToolRegistry、ToolExecutionEngine
- **FEAT-012（JS 工具引擎）**：JsExecutionEngine、bridge 注入系统
- **FEAT-018（JS 工具组）**：JS 工具组注册模式（JSON+JS 对）
- **FEAT-009（设置）**：设置页面导航和布局

### 被依赖于

- 目前没有其他功能依赖 FEAT-030

### 外部依赖

- **Google Workspace REST API**：Gmail、Calendar、Tasks、Contacts、Drive、Docs、Sheets、Slides、Forms
- **Google OAuth 2.0**：授权端点、令牌端点、userinfo 端点
- **OkHttpClient**：已通过 DI 注入，用于 HTTP 请求
- **EncryptedSharedPreferences**：已用于 API 密钥存储
- **QuickJS (com.dokar.quickjs)**：已作为 JS 运行时使用

## 错误处理

### 错误场景

1. **未登录**
   - 原因：用户未登录即尝试使用 Google 工具
   - 处理：返回 `{error: "Not signed in to Google. Connect your Google account in Settings."}`

2. **OAuth 凭据未配置**
   - 原因：用户未输入 Client ID/Secret
   - 处理：设置 UI 显示"未配置"状态，禁用登录按钮

3. **OAuth 授权被拒绝**
   - 原因：用户在 Google 授权页面拒绝授权
   - 处理：在设置 UI 中显示错误，不存储任何令牌

4. **令牌刷新失败**
   - 原因：refresh token 被吊销或网络错误
   - 处理：清除已存储的令牌，返回需要重新登录的认证错误

5. **Google API 错误（4xx）**
   - 原因：请求无效、权限不足、资源未找到
   - 处理：将 Google API 错误信息返回给 AI 模型

6. **Google API 频率限制（429）**
   - 原因：对 Google API 请求过于频繁
   - 处理：将频率限制错误返回给 AI 模型

7. **网络错误**
   - 原因：无网络连接
   - 处理：返回网络错误信息

8. **无效参数**
   - 原因：工具参数缺失或格式错误
   - 处理：返回参数验证错误

## 未来改进

- [ ] 多账号支持（同时支持多个 Google 账号）
- [ ] 选择性授权范围（选择启用哪些服务）
- [ ] Google 服务账号支持（服务器到服务器认证）
- [ ] 针对瞬态错误的指数退避重试逻辑
- [ ] 多项目操作的批量 API 请求
- [ ] 最近访问数据的离线缓存
- [ ] 新邮件、日历事件的推送通知
- [ ] Google Workspace Admin SDK 集成
- [ ] Google Maps、YouTube API 工具组

## 测试要点

### 功能测试

- 验证 OAuth 凭据可以保存并从 EncryptedSharedPreferences 加载
- 验证 OAuth 流程完成并存储令牌
- 验证令牌过期时 access token 刷新功能正常
- 验证退出登录时吊销令牌并清除存储
- 验证所有 10 个工具组已在 ToolRegistry 中注册
- 验证每个工具组的 JSON 定义文件可以正确解析
- 验证带 GoogleAuthBridge 的 JS 执行能提供有效令牌
- 验证 FileTransferBridge 的下载和上传操作
- 验证 Gmail 搜索返回格式化结果
- 验证 Calendar 事件创建及日期/时间处理
- 验证 Drive 文件上传和下载
- 验证 Contacts 搜索和增删改查操作
- 验证 Docs 文本提取和插入
- 验证 Sheets 读写操作
- 验证 Slides 列出和添加操作
- 验证 Forms 回复列表功能
- 验证 Google 账号设置页面渲染正常
- 验证导航至/从 Google 账号设置页面

### 边界情况

- 工具调用执行期间令牌过期
- 并发 Google 工具调用触发同时令牌刷新
- Google API 返回分页结果
- 用户从 Google 账号设置中撤销应用访问权限（外部操作）
- 超大邮件附件或 Drive 文件
- 邮件主题、文档标题中包含特殊字符
- OAuth 流程期间网络断开
- 回环服务器端口冲突
- 工具调用进行中时用户退出登录
- 具有受限 API 访问权限的 Google Workspace 账号

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
