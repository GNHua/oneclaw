# 内置工具参考

OneClaw 提供 39 个以上的内置工具，AI 可在对话过程中调用。工具按类别组织。

## 网络与内容

### `webfetch`

抓取网页并以 Markdown 格式返回其内容。适用于静态页面。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `url` | string | 是 | 要抓取的 URL |
| `max_length` | integer | 否 | 输出最大字符数（默认：50000） |

### `browser`

在 WebView 中渲染网页，然后截图或提取内容。当内容需要 JavaScript 渲染时使用。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `url` | string | 是 | 要加载的 URL |
| `mode` | string | 是 | `screenshot` 或 `extract` |
| `width` | integer | 否 | 视口宽度（像素，默认：412） |
| `height` | integer | 否 | 视口高度（像素，默认：915） |
| `wait_seconds` | number | 否 | 页面加载后等待的秒数（默认：2） |
| `full_page` | boolean | 否 | 捕获完整可滚动页面（默认：false） |
| `max_length` | integer | 否 | extract 模式的最大输出长度（默认：50000） |
| `javascript` | string | 否 | 在 extract 模式中执行的自定义 JavaScript |

## PDF 处理

### `pdf_extract_text`

从 PDF 文件中提取文本内容。对于没有文本层的扫描版 PDF，请改用 `pdf_render_page`。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `path` | string | 是 | PDF 文件路径 |
| `pages` | string | 否 | 页码范围（例如 "1-5"、"3"、"1,3,5-7"）。省略则处理全部页面 |
| `max_chars` | integer | 否 | 最多返回的字符数（默认：50000） |

### `pdf_info`

获取 PDF 文件的元数据和信息（页数、文件大小、标题、作者）。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `path` | string | 是 | PDF 文件路径 |

### `pdf_render_page`

将 PDF 页面渲染为 PNG 图像。适用于布局复杂、包含图表或图片的页面。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `path` | string | 是 | PDF 文件路径 |
| `page` | integer | 是 | 页码（从 1 开始） |
| `dpi` | integer | 否 | 渲染分辨率（默认：150，范围：72-300） |

## 代码执行

### `exec`

在设备上执行 shell 命令并返回其输出。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `command` | string | 是 | 要执行的 shell 命令 |
| `timeout_seconds` | integer | 否 | 最长执行时间（默认：30，最大：120） |
| `working_directory` | string | 否 | 工作目录（默认：应用数据目录） |
| `max_length` | integer | 否 | 最大输出长度（默认：50000） |

### `js_eval`

在沙箱化的 QuickJS 环境中执行 JavaScript 代码。如果代码定义了 `main()` 函数，该函数将被调用；否则返回最后一个表达式的值。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `code` | string | 是 | 要执行的 JavaScript 源代码 |
| `timeout_seconds` | integer | 否 | 最长执行时间（默认：30，最大：120） |

## 记忆

### `save_memory`

将信息保存到持久化长期记忆（MEMORY.md）。此内容会被注入所有后续对话的系统提示词中。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `content` | string | 是 | 要保存的文本（最多 5000 字符） |
| `category` | string | 否 | 分类：`profile`、`preferences`、`interests`、`workflow`、`projects`、`notes`（默认：`notes`） |

### `update_memory`

更新或删除长期记忆中的现有条目。若要删除，请将 `new_text` 设置为空字符串。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `old_text` | string | 是 | 要在 MEMORY.md 中查找的精确文本 |
| `new_text` | string | 是 | 替换文本（留空则删除） |

### `search_history`

搜索历史对话、记忆和每日日志。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `query` | string | 是 | 搜索关键词或短语 |
| `scope` | string | 否 | `all`（默认）、`memory`、`daily_log`、`sessions` |
| `date_from` | string | 否 | 开始日期（YYYY-MM-DD） |
| `date_to` | string | 否 | 结束日期（YYYY-MM-DD） |
| `max_results` | integer | 否 | 最大结果数（默认：10，最大：50） |

## Agent 管理

### `create_agent`

创建新的自定义 AI Agent。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | Agent 显示名称（最多 100 字符） |
| `description` | string | 否 | 简短描述 |
| `system_prompt` | string | 是 | 定义行为的系统提示词（最多 50000 字符） |

### `update_agent`

更新现有 Agent。仅更改已提供的字段。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `agent_id` | string | 是 | Agent 的 ID |
| `name` | string | 否 | 新名称 |
| `description` | string | 否 | 新描述 |
| `system_prompt` | string | 否 | 新系统提示词 |
| `preferred_provider_id` | string | 否 | 首选提供商（留空则清除） |
| `preferred_model_id` | string | 否 | 首选模型（留空则清除） |

### `delete_agent`

删除自定义 Agent。内置 Agent 无法删除。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `agent_id` | string | 是 | Agent 的 ID |

### `list_agents`

列出所有已配置的 AI Agent 及其详细信息。无参数。

## 计划任务

### `schedule_task`

创建一个计划任务，在指定时间运行 AI Agent。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 任务名称 |
| `prompt` | string | 是 | 发送给 Agent 的提示词消息 |
| `schedule_type` | string | 是 | `one_time`、`daily` 或 `weekly` |
| `hour` | integer | 是 | 小时（0-23） |
| `minute` | integer | 是 | 分钟（0-59） |
| `day_of_week` | string | 否 | 每周任务的星期几（例如 `monday`） |
| `date` | string | 否 | 一次性任务的日期（YYYY-MM-DD） |

### `update_scheduled_task`

更新现有计划任务。仅更改已提供的字段。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `task_id` | string | 是 | 任务 ID |
| `name` | string | 否 | 新名称 |
| `prompt` | string | 否 | 新提示词 |
| `schedule_type` | string | 否 | 新计划类型 |
| `hour` | integer | 否 | 新小时 |
| `minute` | integer | 否 | 新分钟 |
| `day_of_week` | string | 否 | 新星期几 |
| `date` | string | 否 | 新日期 |
| `enabled` | boolean | 否 | 启用/禁用 |

### `delete_scheduled_task`

永久删除计划任务并取消其闹钟。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `task_id` | string | 是 | 任务 ID |

### `list_scheduled_tasks`

列出所有计划任务及其状态和下次触发时间。无参数。

### `run_scheduled_task`

立即触发计划任务执行。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `task_id` | string | 是 | 任务 ID |

## 提供商与模型配置

### `create_provider`

创建新的 AI 提供商。用户需在设置中手动填写 API 密钥。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 显示名称 |
| `type` | string | 是 | `OPENAI`、`ANTHROPIC` 或 `GEMINI` |
| `api_base_url` | string | 是 | API 基础 URL |

### `update_provider`

更新提供商配置。仅更改已提供的字段。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |
| `name` | string | 否 | 新名称 |
| `api_base_url` | string | 否 | 新 API 基础 URL |
| `is_active` | boolean | 否 | 激活状态 |

### `delete_provider`

删除提供商及其所有模型。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |

### `list_providers`

列出所有已配置的提供商及详细信息。无参数。

### `add_model`

手动向提供商添加模型。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |
| `model_id` | string | 是 | 模型标识符（例如 `gpt-4-turbo`） |
| `display_name` | string | 否 | 可读名称 |

### `delete_model`

删除手动添加的模型。仅可删除 MANUAL 类型的模型。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |
| `model_id` | string | 是 | 模型 ID |

### `list_models`

列出提供商的所有模型。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |

### `fetch_models`

从提供商的 API 获取并刷新模型列表。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |

### `set_default_model`

设置全局默认模型。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `provider_id` | string | 是 | 提供商 ID |
| `model_id` | string | 是 | 模型 ID |

## 应用配置

### `get_config`

读取应用配置项。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `key` | string | 是 | 配置键（例如 `theme_mode`） |

### `set_config`

设置应用配置值。更改立即生效。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `key` | string | 是 | 配置键 |
| `value` | string | 是 | 要设置的值 |

### `manage_env_var`

管理 JavaScript 工具的环境变量（存储于加密偏好设置中）。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `action` | string | 是 | `list`、`set` 或 `delete` |
| `key` | string | 条件必填 | 变量名（`set`/`delete` 时必填） |
| `value` | string | 条件必填 | 变量值（`set` 时必填） |

## 工具状态管理

### `list_tool_states`

列出所有已注册工具及其启用/禁用状态。无参数。

### `set_tool_enabled`

启用或禁用特定工具或工具组。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 工具或工具组名称 |
| `enabled` | boolean | 是 | 启用或禁用 |
| `type` | string | 否 | `tool` 或 `group`（默认：`tool`） |

## JavaScript 工具 CRUD

### `create_js_tool`

创建新的 JavaScript 工具。工具保存到设备，重启后依然存在。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 工具名称（小写，2-50 字符，以字母开头） |
| `description` | string | 是 | 工具功能描述 |
| `parameters_schema` | string | 是 | 定义参数的 JSON 字符串 |
| `js_code` | string | 是 | 包含 `execute(params)` 函数的 JavaScript 源代码 |
| `required_permissions` | string | 否 | 逗号分隔的 Android 权限列表 |
| `timeout_seconds` | integer | 否 | 超时时间（默认：30） |

**可用的 JS API：**
- `fetch(url, options)` -- 通过 OkHttp 发起 HTTP 请求
- `fs.readFile(path)` / `fs.writeFile(path, content)` / `fs.appendFile(path, content)` / `fs.exists(path)` -- 文件系统访问（仅限应用存储）
- `console.log()` / `console.warn()` / `console.error()` -- 日志输出
- `_time(timezone, format)` -- 日期/时间格式化
- `lib(name)` -- 加载可复用库

### `update_js_tool`

更新现有的用户创建 JavaScript 工具。仅更改已指定的字段。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 工具名称 |
| `description` | string | 否 | 新描述 |
| `parameters_schema` | string | 否 | 新参数 schema |
| `js_code` | string | 否 | 新 JavaScript 源代码 |
| `required_permissions` | string | 否 | 新权限列表 |
| `timeout_seconds` | integer | 否 | 新超时时间 |

### `delete_js_tool`

删除用户创建的 JavaScript 工具。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 工具名称 |

### `list_user_tools`

列出所有用户创建的 JavaScript 工具。无参数。

## 技能与工具组

### `load_skill`

加载技能的完整提示词说明。当用户请求某项技能或 AI 识别到匹配任务时使用。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `name` | string | 是 | 技能名称（例如 `create-tool`） |

### `load_tool_group`

加载工具组中的所有工具。工具组必须先加载，其中的工具才能使用。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `group_name` | string | 是 | 工具组名称 |

## Git 版本管理

### `git_log`

列出应用文件仓库的提交历史。可选择性地过滤到特定文件或目录。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `path` | string | 否 | 用于过滤提交的相对文件路径（例如 `memory/MEMORY.md`） |
| `max_count` | integer | 否 | 最多返回的提交数量（默认：20） |

### `git_show`

显示特定提交的完整 diff 及元数据。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `sha` | string | 是 | 完整或缩写的提交 SHA |

### `git_diff`

显示两个提交之间的 diff。若省略 `to_sha`，则与当前工作区进行比较。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `from_sha` | string | 是 | 基准提交 SHA |
| `to_sha` | string | 否 | 目标提交 SHA（省略则与 HEAD 比较） |

### `git_restore`

将指定文件恢复到某次提交时的状态。恢复操作会自动创建一个新提交。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `path` | string | 是 | 要恢复的相对文件路径 |
| `sha` | string | 是 | 要从中恢复的提交 SHA |

### `git_bundle`

将整个 git 仓库导出为单个 bundle 文件，用于离线备份（例如上传到 Google Drive）。

| 参数 | 类型 | 必填 | 说明 |
|-----------|------|----------|-------------|
| `output_path` | string | 是 | bundle 文件的相对路径（例如 `exports/oneclaw.bundle`） |
