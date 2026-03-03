# Built-in Tool Reference

OneClaw provides 39+ built-in tools that the AI can call during conversations. Tools are organized by category.

## Web and Content

### `webfetch`

Fetch a web page and return its content as Markdown. Best for static pages.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `url` | string | yes | The URL to fetch |
| `max_length` | integer | no | Maximum output length in characters (default: 50000) |

### `browser`

Render a web page in a WebView, then take a screenshot or extract content. Use when content requires JavaScript rendering.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `url` | string | yes | The URL to load |
| `mode` | string | yes | `screenshot` or `extract` |
| `width` | integer | no | Viewport width in pixels (default: 412) |
| `height` | integer | no | Viewport height in pixels (default: 915) |
| `wait_seconds` | number | no | Seconds to wait after page load (default: 2) |
| `full_page` | boolean | no | Capture full scrollable page (default: false) |
| `max_length` | integer | no | Maximum output length for extract mode (default: 50000) |
| `javascript` | string | no | Custom JavaScript to execute in extract mode |

## PDF Processing

### `pdf_extract_text`

Extract text content from a PDF file. For scanned PDFs with no text layer, use `pdf_render_page` instead.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Path to the PDF file |
| `pages` | string | no | Page range (e.g., "1-5", "3", "1,3,5-7"). Omit for all pages |
| `max_chars` | integer | no | Maximum characters to return (default: 50000) |

### `pdf_info`

Get metadata and info about a PDF file (page count, file size, title, author).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Path to the PDF file |

### `pdf_render_page`

Render a PDF page to a PNG image. Useful for pages with complex layouts, charts, or images.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Path to the PDF file |
| `page` | integer | yes | Page number (1-based) |
| `dpi` | integer | no | Render resolution (default: 150, range: 72-300) |

## Code Execution

### `exec`

Execute a shell command on the device and return its output.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `command` | string | yes | The shell command to execute |
| `timeout_seconds` | integer | no | Maximum execution time (default: 30, max: 120) |
| `working_directory` | string | no | Working directory (default: app data directory) |
| `max_length` | integer | no | Maximum output length (default: 50000) |

### `js_eval`

Execute JavaScript code in a sandboxed QuickJS environment. If the code defines a `main()` function, it will be called. Otherwise the last expression value is returned.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `code` | string | yes | JavaScript source code to execute |
| `timeout_seconds` | integer | no | Maximum execution time (default: 30, max: 120) |

## Memory

### `save_memory`

Save information to persistent long-term memory (MEMORY.md). This content is injected into the system prompt of all future conversations.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `content` | string | yes | Text to save (max 5000 characters) |
| `category` | string | no | Section: `profile`, `preferences`, `interests`, `workflow`, `projects`, `notes` (default: `notes`) |

### `update_memory`

Update or delete an existing entry in long-term memory. To delete, set `new_text` to an empty string.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `old_text` | string | yes | Exact text to find in MEMORY.md |
| `new_text` | string | yes | Replacement text (empty to delete) |

### `search_history`

Search past conversations, memory, and daily logs.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `query` | string | yes | Search keywords or phrase |
| `scope` | string | no | `all` (default), `memory`, `daily_log`, `sessions` |
| `date_from` | string | no | Start date (YYYY-MM-DD) |
| `date_to` | string | no | End date (YYYY-MM-DD) |
| `max_results` | integer | no | Maximum results (default: 10, max: 50) |

## Agent Management

### `create_agent`

Create a new custom AI agent.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Agent display name (max 100 chars) |
| `description` | string | no | Short description |
| `system_prompt` | string | yes | System prompt defining behavior (max 50000 chars) |

### `update_agent`

Update an existing agent. Only provided fields are changed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `agent_id` | string | yes | ID of the agent |
| `name` | string | no | New name |
| `description` | string | no | New description |
| `system_prompt` | string | no | New system prompt |
| `preferred_provider_id` | string | no | Preferred provider (empty to clear) |
| `preferred_model_id` | string | no | Preferred model (empty to clear) |

### `delete_agent`

Delete a custom agent. Built-in agents cannot be deleted.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `agent_id` | string | yes | ID of the agent |

### `list_agents`

List all configured AI agents with their details. No parameters.

## Scheduled Tasks

### `schedule_task`

Create a scheduled task that runs an AI agent at a specified time.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Task name |
| `prompt` | string | yes | Prompt message for the agent |
| `schedule_type` | string | yes | `one_time`, `daily`, or `weekly` |
| `hour` | integer | yes | Hour (0-23) |
| `minute` | integer | yes | Minute (0-59) |
| `day_of_week` | string | no | Day name for weekly (e.g., `monday`) |
| `date` | string | no | Date for one-time (YYYY-MM-DD) |

### `update_scheduled_task`

Update an existing scheduled task. Only provided fields are changed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `task_id` | string | yes | Task ID |
| `name` | string | no | New name |
| `prompt` | string | no | New prompt |
| `schedule_type` | string | no | New schedule type |
| `hour` | integer | no | New hour |
| `minute` | integer | no | New minute |
| `day_of_week` | string | no | New day of week |
| `date` | string | no | New date |
| `enabled` | boolean | no | Enable/disable |

### `delete_scheduled_task`

Permanently delete a scheduled task and cancel its alarm.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `task_id` | string | yes | Task ID |

### `list_scheduled_tasks`

List all scheduled tasks with status and next trigger time. No parameters.

### `run_scheduled_task`

Trigger a scheduled task to run immediately.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `task_id` | string | yes | Task ID |

## Provider and Model Configuration

### `create_provider`

Create a new AI provider. The user must set the API key in Settings afterward.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Display name |
| `type` | string | yes | `OPENAI`, `ANTHROPIC`, or `GEMINI` |
| `api_base_url` | string | yes | API base URL |

### `update_provider`

Update a provider's configuration. Only provided fields are changed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |
| `name` | string | no | New name |
| `api_base_url` | string | no | New API base URL |
| `is_active` | boolean | no | Active status |

### `delete_provider`

Delete a provider and all its models.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |

### `list_providers`

List all configured providers with details. No parameters.

### `add_model`

Add a model manually to a provider.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |
| `model_id` | string | yes | Model identifier (e.g., `gpt-4-turbo`) |
| `display_name` | string | no | Human-readable name |

### `delete_model`

Delete a manually-added model. Only MANUAL models can be deleted.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |
| `model_id` | string | yes | Model ID |

### `list_models`

List all models for a provider.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |

### `fetch_models`

Fetch and refresh the model list from the provider's API.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |

### `set_default_model`

Set the global default model.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `provider_id` | string | yes | Provider ID |
| `model_id` | string | yes | Model ID |

## App Configuration

### `get_config`

Read an app configuration setting.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | yes | Configuration key (e.g., `theme_mode`) |

### `set_config`

Set an app configuration value. Changes take effect immediately.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `key` | string | yes | Configuration key |
| `value` | string | yes | Value to set |

### `manage_env_var`

Manage JavaScript tool environment variables (stored in encrypted preferences).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `action` | string | yes | `list`, `set`, or `delete` |
| `key` | string | conditional | Variable name (required for `set`/`delete`) |
| `value` | string | conditional | Variable value (required for `set`) |

## Tool State Management

### `list_tool_states`

List all registered tools with their enabled/disabled status. No parameters.

### `set_tool_enabled`

Enable or disable a specific tool or tool group.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Tool or group name |
| `enabled` | boolean | yes | Enable or disable |
| `type` | string | no | `tool` or `group` (default: `tool`) |

## JavaScript Tool CRUD

### `create_js_tool`

Create a new JavaScript tool. The tool is saved to the device and persists across restarts.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Tool name (lowercase, 2-50 chars, start with letter) |
| `description` | string | yes | What the tool does |
| `parameters_schema` | string | yes | JSON string defining parameters |
| `js_code` | string | yes | JavaScript source with `execute(params)` function |
| `required_permissions` | string | no | Comma-separated Android permissions |
| `timeout_seconds` | integer | no | Timeout (default: 30) |

**Available JS APIs:**
- `fetch(url, options)` -- HTTP requests via OkHttp
- `fs.readFile(path)` / `fs.writeFile(path, content)` / `fs.appendFile(path, content)` / `fs.exists(path)` -- File system access (app storage only)
- `console.log()` / `console.warn()` / `console.error()` -- Logging
- `_time(timezone, format)` -- Date/time formatting
- `lib(name)` -- Load reusable libraries

### `update_js_tool`

Update an existing user-created JavaScript tool. Only specified fields are changed.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Tool name |
| `description` | string | no | New description |
| `parameters_schema` | string | no | New parameters schema |
| `js_code` | string | no | New JavaScript source |
| `required_permissions` | string | no | New permissions |
| `timeout_seconds` | integer | no | New timeout |

### `delete_js_tool`

Delete a user-created JavaScript tool.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Tool name |

### `list_user_tools`

List all user-created JavaScript tools. No parameters.

## Skills and Tool Groups

### `load_skill`

Load the full prompt instructions for a skill. Used when a user requests a skill or when the AI recognizes a matching task.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `name` | string | yes | Skill name (e.g., `create-tool`) |

### `load_tool_group`

Load all tools in a tool group. A group must be loaded before its tools can be used.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `group_name` | string | yes | Tool group name |

## Git Versioning

### `git_log`

List commit history for the app's file repository. Optionally filter to a specific file or directory.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | no | Relative file path to filter commits by (e.g., `memory/MEMORY.md`) |
| `max_count` | integer | no | Maximum number of commits to return (default: 20) |

### `git_show`

Show the full diff and metadata for a specific commit.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `sha` | string | yes | Full or abbreviated commit SHA |

### `git_diff`

Show the diff between two commits. If `to_sha` is omitted, compares against the current working tree.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `from_sha` | string | yes | Base commit SHA |
| `to_sha` | string | no | Target commit SHA (omit to diff against HEAD) |

### `git_restore`

Restore a specific file to its state at a given commit. Automatically commits the restoration.

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `path` | string | yes | Relative file path to restore |
| `sha` | string | yes | Commit SHA to restore from |

### `git_bundle`

Export the entire git repository as a single bundle file for offline backup (e.g., upload to Google Drive).

| Parameter | Type | Required | Description |
|-----------|------|----------|-------------|
| `output_path` | string | yes | Relative path for the bundle file (e.g., `exports/oneclaw.bundle`) |
