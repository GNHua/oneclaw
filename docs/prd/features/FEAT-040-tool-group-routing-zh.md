# 工具组路由（动态工具加载）

## 功能信息
- **功能 ID**: FEAT-040
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: Draft
- **优先级**: P1（应该实现）
- **负责人**: TBD
- **关联 RFC**: RFC-040（待定）

## 用户故事

**作为** OneClawShadow 的用户，
**我希望** AI Agent 只加载每个任务实际需要的工具，
**以便** 对话消耗更少的 token、响应更快，并且不会被无关的工具 schema 所干扰。

### 典型场景

1. 用户说"查看我的邮件" -- Agent 调用 `load_tool_group("google_gmail")`，返回 Gmail 工具列表。Agent 随后调用 `gmail_search` 查找最新邮件。
2. 用户说"创建一张包含季度数据的表格" -- Agent 调用 `load_tool_group("google_sheets")` 加载表格工具，随后使用 `sheets_create` 和 `sheets_update_values`。
3. 用户说"现在几点了？" -- Agent 直接调用 `get_current_time`（核心工具，始终可用），无需加载任何工具组。
4. 用户说"明天安排一个任务" -- Agent 调用 `load_tool_group("scheduled_tasks")` 加载任务管理工具，随后使用 `schedule_task`。
5. 用户说"加载 PDF 工具" -- Agent 调用 `load_tool_group("pdf")`，返回 PDF 工具列表：`pdf_info`、`pdf_extract_text`、`pdf_render_page`。
6. 用户说"添加一个新的 OpenAI provider" -- Agent 调用 `load_tool_group("config")` 加载配置工具，随后使用 `create_provider`。
7. 用户说"帮我写一个 JS 工具" -- Agent 调用 `load_tool_group("js_tool_management")` 加载 JS 工具 CRUD 工具，随后使用 `create_js_tool`。

## 功能描述

### 概述

OneClawShadow 目前在每次消息轮次中将所有已注册的工具 schema 发送给 LLM。随着 37 个以上的 Kotlin 工具、60 个以上的 JS Google Workspace 工具，以及潜在的大量用户自定义 JS 工具，这将浪费大量 token（每个工具 schema 消耗约 200-500 个 token）。工具 schema 的总负载每轮可能超过 20,000 个 token。

Skill 系统（FEAT-014）已经展示了一种懒加载模式：系统提示词中只出现 skill 的名称和描述，完整的 skill 内容通过 `load_skill` 按需加载。本功能将相同的模式应用于工具：按领域将工具分组，在系统提示词中列出各组的摘要，通过 `load_tool_group` 元工具按需加载完整的工具 schema。

### 工作原理

```
之前（当前方案）：
  系统提示词：...
  工具：[get_current_time, read_file, write_file, http_request,
          gmail_search, gmail_read, gmail_send, gmail_draft, ...(60+ 个),
          list_providers, create_provider, ...(17 个),
          pdf_info, pdf_extract_text, pdf_render_page,
          schedule_task, list_scheduled_tasks, ...,
          create_js_tool, list_user_tools, ...]
  Token 消耗：仅工具 schema 就达 ~20,000+ 个 token

之后（使用工具组路由）：
  系统提示词：
    ...
    ## Available Tool Groups
    Use `load_tool_group` to load tools from a group before using them.
    - config: Manage providers, models, agents, app settings, environment variables, and tool states
    - pdf: Extract text, get info, and render pages from PDF files
    - scheduled_tasks: Create, list, run, update, and delete scheduled tasks
    - js_tool_management: Create, list, update, and delete user JavaScript tools
    - google_gmail: Email: search, read, send, draft, label, manage messages
    - google_drive: File storage: list, search, upload, download, manage Drive files
    ...
  工具：[get_current_time, read_file, write_file, http_request,
          load_skill, load_tool_group, save_memory, search_history,
          exec, js_eval, webfetch, browser, create_agent]
  Token 消耗：核心工具 schema 约 ~3,000 个 token + 工具组列表约 ~200 个 token
```

### 工具分类

#### 核心工具（始终可用，不分组）

以下工具在每次轮次中均会发送给 LLM：

| 工具 | 用途 |
|------|---------|
| `load_skill` | 元工具：加载 skill 内容 |
| `load_tool_group` | 元工具：加载工具组 |
| `save_memory` | 保存信息到记忆 |
| `search_history` | 搜索对话历史 |
| `exec` | 执行 shell 命令 |
| `js_eval` | 执行 JavaScript 代码 |
| `webfetch` | 获取网页内容 |
| `browser` | 浏览器自动化 |
| `create_agent` | 创建子 Agent |
| `read_file` | 读取文件（JS 内置） |
| `write_file` | 写入文件（JS 内置） |
| `get_current_time` | 获取当前时间（JS 内置） |
| `http_request` | HTTP 请求（JS 内置） |

#### 分组 Kotlin 工具

| 组名 | 工具 | 数量 |
|-------|-------|-------|
| `config` | list_providers, create_provider, update_provider, delete_provider, list_models, fetch_models, set_default_model, add_model, delete_model, list_agents, update_agent, delete_agent, get_config, set_config, manage_env_var, list_tool_states, set_tool_enabled | 17 |
| `pdf` | pdf_info, pdf_extract_text, pdf_render_page | 3 |
| `scheduled_tasks` | schedule_task, list_scheduled_tasks, run_scheduled_task, update_scheduled_task, delete_scheduled_task | 5 |
| `js_tool_management` | create_js_tool, list_user_tools, update_js_tool, delete_js_tool | 4 |

#### 分组 JS 工具（来自 assets）

每个 Google Workspace JSON 清单文件各自成为一个工具组：

| 组名 | 源文件 | 工具数（约） |
|-------|-----------|---------------|
| `google_gmail` | google_gmail.json | 8 |
| `google_gmail_settings` | google_gmail_settings.json | 4 |
| `google_drive` | google_drive.json | 8 |
| `google_calendar` | google_calendar.json | 7 |
| `google_contacts` | google_contacts.json | 5 |
| `google_docs` | google_docs.json | 6 |
| `google_sheets` | google_sheets.json | 8 |
| `google_slides` | google_slides.json | 6 |
| `google_forms` | google_forms.json | 5 |
| `google_tasks` | google_tasks.json | 5 |

### 用户交互流程

```
1. 用户："查看我最新的邮件"
2. 系统提示词包含：
     ## Available Tool Groups
     - google_gmail: Email management: search, read, send, draft, label, archive, and manage Gmail messages
     ...
3. Agent 调用 load_tool_group(group_name="google_gmail")
4. 工具返回："已从工具组 'google_gmail' 加载 8 个工具：
     - gmail_search: Search Gmail messages
     - gmail_read: Read a Gmail message
     - gmail_send: Send a Gmail message
     ..."
5. Gmail 工具现在已被加入当前对话后续轮次的活跃工具列表
6. Agent 调用 gmail_search(query="is:unread newer_than:1d")
7. Agent 将结果返回给用户
```

## 验收标准

### 核心行为

#### TEST-040-01：核心工具始终可用
- **前提** 新对话开始
- **当** Agent 收到第一条消息
- **则** 只有核心工具（load_skill、load_tool_group、save_memory、search_history、exec、js_eval、webfetch、browser、create_agent 以及单文件 JS 内置工具）被发送给 LLM

#### TEST-040-02：系统提示词中的工具组列表
- **前提** 已注册工具组
- **当** 系统提示词被组装
- **则** 追加"Available Tool Groups"章节，列出每个工具组的名称和描述

#### TEST-040-03：加载工具组成功
- **前提** 工具组名称有效
- **当** Agent 调用 `load_tool_group(group_name="google_gmail")`
- **则** "google_gmail"组中的所有工具在后续轮次中变为可用，工具返回已加载工具的名称和描述列表

#### TEST-040-04：加载工具组名称无效
- **前提** 工具组名称不存在
- **当** Agent 调用 `load_tool_group(group_name="nonexistent")`
- **则** 工具返回错误，并列出所有可用的工具组名称

#### TEST-040-05：已加载工具在多轮次间持久存在
- **前提** 某工具组已被加载
- **当** Agent 在同一对话中发送后续消息
- **则** 已加载的工具保持在活跃工具列表中

#### TEST-040-06：可同时加载多个工具组
- **前提** 两个不同的工具组（"google_gmail"和"google_drive"）
- **当** Agent 加载两个工具组
- **则** 两个工具组的工具同时可用

#### TEST-040-07：重复加载是幂等操作
- **前提** 某工具组已经被加载
- **当** Agent 再次以相同的工具组名称调用 `load_tool_group`
- **则** 工具返回成功，且不会重复工具定义

### Token 效率

#### TEST-040-08：初始 Token 数量减少
- **前提** 已注册 100 个以上工具（核心 + 分组）
- **当** 新对话开始
- **则** 只有约 13 个核心工具 schema 被发送给 LLM（而非全部 100 个以上）

#### TEST-040-09：加载后工具组 Schema 被发送
- **前提** Agent 加载"config"组（17 个工具）
- **当** 下一条消息被发送给 LLM
- **则** 工具列表包含全部 13 个核心工具 + 17 个 config 工具 = 30 个工具

### JS 工具组集成

#### TEST-040-10：带 _meta 的 JS 组清单
- **前提** JS 组清单 JSON 文件包含 `_meta` 条目
- **当** 清单被加载
- **则** `_meta` 条目用于提供工具组的 display_name 和 description，且该条目不被注册为工具

#### TEST-040-11：不带 _meta 的 JS 组清单
- **前提** JS 组清单 JSON 文件不包含 `_meta` 条目
- **当** 清单被加载
- **则** display_name 从文件名自动生成，description 从工具名称自动生成

### 工具执行

#### TEST-040-12：未加载工具组时阻止执行分组工具
- **前提** "pdf"工具组尚未被加载
- **当** Agent 尝试调用 `pdf_info`
- **则** 工具调用失败，因为 `pdf_info` 不在可用工具名称列表中

#### TEST-040-13：加载工具组后可执行分组工具
- **前提** "pdf"工具组已被加载
- **当** Agent 调用 `pdf_info`
- **则** 工具正常执行并返回结果

## UI/UX 要求

本功能无新增 UI。工具组路由对用户完全透明 -- 在现有聊天界面和工具调用展示区内运行。用户可见的唯一变化是：Agent 在使用特定领域工具之前可能会调用 `load_tool_group`，该调用以普通工具调用的形式显示在聊天中。

## 功能边界

### 包含范围

- `ToolGroupDefinition` 数据类，用于存储工具组元数据
- `load_tool_group` 元工具，遵循 `load_skill` 的同款模式
- `SendMessageUseCase` 中的动态工具列表管理
- 工具组列表的系统提示词注入
- Kotlin 工具在 `ToolModule` 中的工具组注册
- JS 组清单中的 `_meta` 条目支持
- `JsToolLoader` 中的工具组元数据暴露

### 不包含（V1）

- 自动工具组检测（Agent 必须显式调用 `load_tool_group`）
- 按 Agent 配置工具组（所有 Agent 均可见所有工具组）
- 跨会话的工具组加载持久化（工具组每次对话重置）
- 工具组启用/禁用（已由现有的 FEAT-017 工具管理覆盖）
- 用户创建/删除工具组（工具组由注册定义）
- 流式工具组加载（工具组同步加载）

## 业务规则

1. 核心工具始终可用，与工具组加载状态无关
2. 分组工具只有在通过 `load_tool_group` 显式加载其所属工具组后才可用
3. 工具组加载的作用域为当前对话（新对话开始时重置）
4. 加载已经加载过的工具组是无操作（幂等）
5. `load_tool_group` 工具本身始终是核心工具（从不分组）
6. JS 组清单可选择性地包含 `_meta` 条目，用于提供人类可读的工具组元数据
7. 若不存在 `_meta` 条目，display_name 和 description 将被自动生成
8. 通过 `ToolSourceInfo(type = TOOL_GROUP, groupName = ...)` 注册的工具组将被自动排除在核心工具定义之外
9. 在 `ToolSourceInfo` 中设置了 `groupName` 的 Kotlin 工具，无论 `ToolSourceType` 为何，均被视为分组工具

## 非功能性要求

### 性能

- `load_tool_group` 执行时间：< 10ms（内存中的注册表查找）
- 系统提示词工具组列表组装时间：< 5ms
- 工具组加载无需任何网络调用

### Token 节省

- 未加载工具组时，预计每轮减少 15,000-20,000 个 token
- 每个工具组列表条目消耗约 20-30 个 token（名称 + 单行描述）
- 完整工具组列表（14 个工具组）消耗约 300-400 个 token，而发送全部工具 schema 需要 20,000 个以上 token

### 内存

- 内存开销极小：`ToolGroupDefinition` 实例（每个约 100 字节）
- 工具定义仍保留在 `ToolRegistry` 中 -- 工具组路由只控制哪些工具被发送给 LLM

### 兼容性

- 向后兼容：现有工具继续正常工作
- 无需数据库变更
- 无需新权限

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**: Tool 接口、ToolRegistry、ToolExecutionEngine、ToolSourceInfo
- **FEAT-014（Agent Skill）**: LoadSkillTool 模式、SkillRegistry 模式
- **FEAT-018（JS 工具组）**: JS 组清单格式、JsToolLoader
- **FEAT-036（配置工具）**: 待分组的配置工具

### 被依赖于

- 目前无

### 外部依赖

- 无（所有操作均使用现有内部组件）

## 错误处理

### 错误场景

1. **工具组未找到**
   - 原因：Agent 以不存在的工具组名称调用 `load_tool_group`
   - 处理：返回 `ToolResult.error("not_found", "Tool group 'xyz' not found. Available groups: config, pdf, scheduled_tasks, ...")`，并附带完整的可用工具组列表

2. **缺少参数**
   - 原因：Agent 调用 `load_tool_group` 时未提供 `group_name`
   - 处理：返回 `ToolResult.error("missing_parameter", "Required parameter 'group_name' is missing.")`

3. **工具组为空**
   - 原因：某已注册工具组没有工具（所有工具未注册或已禁用）
   - 处理：返回 `ToolResult.error("empty_group", "Tool group 'xyz' has no available tools.")`

## 测试要点

### 功能测试

- 验证核心工具始终包含在工具定义中
- 验证分组工具被排除在初始工具定义之外
- 验证 `load_tool_group` 返回正确的工具列表
- 验证已加载的工具变为可执行状态
- 验证可同时加载多个工具组
- 验证重复加载是幂等的
- 验证无效工具组名称的错误响应包含可用工具组列表
- 验证系统提示词中包含工具组列表
- 验证 JS `_meta` 条目被正确解析
- 验证 JS `_meta` 条目不被注册为工具

### 边界情况

- 同时加载所有工具组
- 加载只包含单个工具的工具组
- 加载工具组后通过 `set_tool_enabled` 禁用其中某个工具
- 只含 `_meta` 条目（无实际工具）的 JS 清单
- `_meta` 条目的 description 中包含特殊字符的 JS 清单
- 并行工具执行中并发调用 `load_tool_group`
- Kotlin 和 JS 工具组之间的工具组名称冲突
- 超长工具组描述的截断处理

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
