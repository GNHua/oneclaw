# JavaScript 工具组

## 功能信息
- **Feature ID**: FEAT-018
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **优先级**: P2 (Nice to Have)
- **负责人**: TBD
- **关联 RFC**: RFC-018 (pending)

## 用户故事

**作为** OneClaw 的开发者或高级用户，
**我希望** 在单个 JavaScript 文件中定义多个相关工具，并配合共享的 JSON 清单，
**以便** 按服务或领域（例如 Google Drive、Gmail）对工具进行分组，在组内共享辅助代码，并随着工具生态系统的扩展减少文件数量的激增。

### 典型场景

1. 开发者创建一个 Google Drive 集成。他无需编写 6 对独立文件（12 个文件），只需编写一个包含 `listFiles`、`readFile`、`writeFile`、`deleteFile`、`search`、`shareFile` 的 `google_drive.js`，以及一个定义全部 6 个工具的 `google_drive.json` 数组清单。每个工具以清晰的名称和参数 schema 单独注册到 ToolRegistry。
2. 用户从论坛下载一个"GitHub Tools"工具包——一对 `.js` + `.json` 文件，提供 `github_list_repos`、`github_create_issue`、`github_read_issue`、`github_close_issue`。将两个文件放入工具目录后，所有 4 个工具立即生效。
3. AI Agent 将 `google_drive_list_files`、`google_drive_read_file` 等视为独立工具，每个工具有其自己的描述和参数——使用方式与任何单文件工具完全相同，无法感知它们来自同一个组。
4. 高级用户编写工具组 `text_utils.js`，包含 `word_count`、`regex_extract`、`base64_encode`——三个共享通用字符串辅助函数的小工具。
5. 现有的单工具 JS 文件（例如 `weather_lookup.js` + `weather_lookup.json`）无需任何改动，继续正常运行。

## 功能描述

### 概述

FEAT-018 在 JS 工具引擎（FEAT-012）和 JS 工具迁移（FEAT-015）的基础上，新增对多工具 JavaScript 文件的支持。单个 `.js` 文件可以定义多个具名函数，每个函数对应一个独立工具。配套的 `.json` 清单为工具定义数组，每条定义指定要调用的 JS 函数。各工具以独立条目注册到 ToolRegistry——分组仅是文件层面的组织概念。

### 核心概念：工具组 = 一个 JS + 一个 JSON 数组

工具组由两个文件组成：

**`google_drive.json`**（数组格式）：
```json
[
  {
    "name": "google_drive_list_files",
    "description": "List files in a Google Drive folder",
    "function": "listFiles",
    "parameters": {
      "properties": {
        "folder_id": {
          "type": "string",
          "description": "Google Drive folder ID. Use 'root' for the root folder."
        }
      },
      "required": []
    }
  },
  {
    "name": "google_drive_read_file",
    "description": "Read the content of a file from Google Drive",
    "function": "readFile",
    "parameters": {
      "properties": {
        "file_id": {
          "type": "string",
          "description": "The Google Drive file ID to read"
        }
      },
      "required": ["file_id"]
    }
  },
  {
    "name": "google_drive_upload_file",
    "description": "Upload a local file to Google Drive",
    "function": "uploadFile",
    "parameters": {
      "properties": {
        "local_path": {
          "type": "string",
          "description": "Absolute path of the local file to upload"
        },
        "folder_id": {
          "type": "string",
          "description": "Target folder ID in Google Drive. Defaults to root.",
          "default": "root"
        }
      },
      "required": ["local_path"]
    }
  }
]
```

**`google_drive.js`**：
```javascript
// Shared helpers -- not exposed as tools
async function _getAuthHeaders(env) {
    var token = env.GOOGLE_DRIVE_TOKEN;
    if (!token) throw new Error("GOOGLE_DRIVE_TOKEN not set in environment variables");
    return { "Authorization": "Bearer " + token };
}

function _driveApiUrl(path) {
    return "https://www.googleapis.com/drive/v3" + path;
}

// Tool functions -- each corresponds to a JSON entry via "function" field
async function listFiles(params) {
    var headers = await _getAuthHeaders(params._env);
    var folderId = params.folder_id || "root";
    var url = _driveApiUrl("/files?q='" + folderId + "'+in+parents&fields=files(id,name,mimeType,size)");
    var response = await fetch(url, { headers: headers });
    return await response.json();
}

async function readFile(params) {
    var headers = await _getAuthHeaders(params._env);
    var url = _driveApiUrl("/files/" + params.file_id + "?alt=media");
    var response = await fetch(url, { headers: headers });
    return await response.text();
}

async function uploadFile(params) {
    var headers = await _getAuthHeaders(params._env);
    var content = fs.readFile(params.local_path);
    // ... upload logic
}
```

### 向后兼容性

该变更完全向后兼容。检测逻辑基于 JSON 内容：

| JSON 内容 | 行为 |
|------------|------|
| 对象 `{ "name": ... }` | 单工具模式（现有）。调用 JS 中的 `execute(params)`。 |
| 数组 `[ { "name": ... }, ... ]` | 工具组模式（新增）。每条定义指定 `"function"` 字段，调用对应具名函数。 |

现有单工具文件无需任何改动。不含 `"function"` 字段的单工具文件继续使用 `execute(params)` 约定。

### 函数分发

调用工具组中的某个工具时：

1. `JsExecutionEngine` 接收工具名称和 JS 源码
2. 包装代码调用该工具 `"function"` 字段中指定的函数，而非硬编码的 `execute()`
3. JS 函数接收与单工具模式相同的 `params` 对象（含 `_env`）

```
AI 调用工具 "google_drive_read_file"
    -> ToolRegistry 查找 -> JsTool (jsSource, functionName="readFile")
    -> JsExecutionEngine 包装器：readFile(params)
    -> QuickJS 执行 google_drive.js 中的 readFile()
```

### 命名约定

工具组内的工具名称应遵循统一的前缀约定：

```
{service}_{operation}
```

示例：
- `google_drive_list_files`、`google_drive_read_file`、`google_drive_upload_file`
- `gmail_list_messages`、`gmail_send_message`、`gmail_read_message`
- `github_list_repos`、`github_create_issue`

此约定为推荐而非强制——组内工具可以使用任何合法的工具名称。

### 设置界面中的工具组

设置界面（JS 工具部分）应将来自同一文件的工具在视觉上归为一组：

```
JavaScript Tools (8 tools loaded)

  google_drive.js (3 tools)
    google_drive_list_files   List files in a Google Drive folder
    google_drive_read_file    Read the content of a file from Google Drive
    google_drive_upload_file  Upload a local file to Google Drive

  gmail.js (2 tools)
    gmail_list_messages       List recent Gmail messages
    gmail_send_message        Send an email via Gmail

  weather_lookup.js (1 tool)
    weather_lookup            Look up current weather for a city
```

### 内置工具组

FEAT-015 中的现有内置工具可在未来迭代中可选地重组为工具组：

```
assets/js/tools/
  file_tools.json    -> [read_file, write_file]
  file_tools.js
  http_tools.json    -> [http_request, webfetch]
  http_tools.js
  time_tools.json    -> [get_current_time]
  time_tools.js
```

此重组为可选项，不属于 FEAT-018 V1 的范围。主要目标是为新工具开发（例如 Google Workspace 集成）提供工具组格式支持。

## 验收标准

必须通过（全部必需）：

- [ ] JSON 清单支持包含多个工具定义的数组格式
- [ ] 数组中的每个工具定义包含 `"function"` 字段，用于指定 JS 函数名称
- [ ] 工具组中的工具以独立条目注册到 ToolRegistry
- [ ] 执行工具组中的工具时，调用 JS 文件中正确的具名函数
- [ ] 具名 JS 函数接收与单工具 `execute()` 相同的 `params` 对象（含 `_env`）
- [ ] JS 文件中的共享辅助函数（以 `_` 为前缀）可被所有工具函数访问，但不对外暴露为工具
- [ ] 现有的单工具 JSON 格式（对象而非数组）继续正常工作，无需任何改动
- [ ] 现有使用 `execute(params)` 的单工具 JS 文件继续正常工作，无需任何改动
- [ ] 工具组中的工具可使用 `lib()`、`fetch()`、`fs`、`console`、`_time()`——所有桥接功能与单工具模式完全一致
- [ ] 工具组内的工具名称在整个 ToolRegistry 中必须唯一（规则与单工具相同）
- [ ] 若工具组数组中某条工具定义无效，仅跳过该条目；组内其他有效工具仍正常加载

可选（V1 阶段锦上添花）：

- [ ] 设置界面在视觉上将来自同一源文件的工具归为一组
- [ ] 工具加载错误信息同时指明文件名和具体失败的工具名称
- [ ] 将现有内置工具重组为工具组（file_tools、http_tools、time_tools）

## UI/UX 要求

### 设置界面：JS 工具部分

- 来自同一 JS 文件的工具在文件名下方进行视觉分组
- 每组显示：文件名、工具数量、可展开的工具列表
- 组内每个工具显示：工具名称、描述（与当前一致）
- 单工具文件以"1 个工具的工具组"形式显示（保持展示一致性）
- 错误展示：若组内某个工具加载失败，在该组下方显示带有工具名称的错误信息

### 无其他 UI 变更

- AI 模型只感知独立工具——"组"的概念不向模型暴露
- 对话中的工具调用展示与当前行为完全一致
- Agent 工具选择将工具组中的工具视为独立工具处理

## 功能边界

### 包含范围

- `.json` 清单文件的数组格式支持
- 工具定义中用于具名函数分发的 `"function"` 字段
- 检测逻辑：数组 → 工具组模式，对象 → 单工具模式
- `JsTool` 对 `functionName` 参数的支持
- `JsExecutionEngine` 对调用具名函数的支持
- `JsToolLoader` 对解析数组清单并为每个文件创建多个 `JsTool` 实例的支持
- 错误时的部分加载：跳过无效条目，加载有效条目

### 不包含范围（V1）

- 将现有内置工具重组为工具组（可选的未来工作）
- 组级权限控制（权限仍通过 `requiredPermissions` 按工具设置）
- 组级超时（超时仍按工具设置）
- 设置界面中组级的启用/禁用开关
- 组内工具依赖声明
- 根据文件名自动生成工具名称前缀
- 嵌套组或子组

## 业务规则

1. 包含 JSON 数组的 `.json` 文件被视为工具组清单；JSON 对象被视为单工具清单
2. 工具组数组中的每条定义在整个 ToolRegistry 中必须具有唯一的 `"name"`
3. 工具组数组中的每条定义必须包含 `"function"` 字段（字符串类型），且该字段须与对应 `.js` 文件中已定义的函数名匹配
4. 若工具组条目中缺少 `"function"` 字段，则跳过该工具并报错
5. 工具名称遵循与单工具相同的校验规则：`^[a-z][a-z0-9_]*$`
6. `"function"` 字段不限于 snake_case——遵循 JavaScript 命名约定（推荐使用 camelCase）
7. 工具组最多可包含 1 至 50 个工具（实际限制，防止滥用）
8. JS 文件中的辅助函数（未被任何 `"function"` 字段引用的函数）不注册为工具
9. 环境变量（`_env`）在工具组内所有工具间共享（使用同一个 `EnvironmentVariableStore`）

## 非功能性需求

### 性能

- 加载包含 N 个工具的工具组，所需时间应与加载 N 个单工具大致相当（无显著额外开销）
- JS 源码在工具组所有工具间共享（不在每个工具中重复存储）
- 工具组清单解析每组新增耗时 < 10ms

### 兼容性

- 与单工具格式完全向后兼容
- 现有单工具 `.js` 或 `.json` 文件无需任何改动
- AI 模型的工具调用接口无任何变更

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：ToolRegistry、ToolExecutionEngine
- **FEAT-012（JavaScript 工具引擎）**：JsTool、JsToolLoader、JsExecutionEngine、桥接层
- **FEAT-015（JS 工具迁移）**：内置 JS 工具、LibraryBridge、基于 asset 的工具加载

### 被依赖于

- 当前暂无其他功能依赖 FEAT-018

### 外部依赖

- 无

## 错误处理

### 错误场景

1. **工具组条目中缺少 `"function"` 字段**
   - 原因：工具组 JSON 数组中的某条定义不含 `"function"` 字段
   - 处理方式：跳过该条目并报错："Tool '[name]' in group '[filename]' missing required 'function' field"
   - 组内其他工具仍正常加载

2. **运行时未找到 JS 函数**
   - 原因：指定了 `"function": "listFiles"`，但 `.js` 文件中未定义 `listFiles`
   - 处理方式：工具执行返回 `ToolResult.error("execution_error", "Function 'listFiles' is not defined")`

3. **工具组内工具名称重复**
   - 原因：同一工具组数组中两条定义使用了相同的 `"name"`
   - 处理方式：先出现者保留，后出现者跳过并报错："Duplicate tool name '[name]' in group '[filename]'"

4. **跨工具组/文件的工具名称冲突**
   - 原因：某工具组中的工具名称与其他文件中的工具或内置工具冲突
   - 处理方式：与当前 `registerTools()` 行为一致——由 `allowOverride` 决定跳过或替换

5. **无效的 JSON 数组条目**
   - 原因：缺少 `"name"`、缺少 `"description"`，或参数 schema 无效
   - 处理方式：跳过该条目并输出描述性错误；继续加载其他条目

6. **空数组**
   - 原因：JSON 文件内容为 `[]`
   - 处理方式：记录警告"Empty tool group in '[filename]'"，不注册任何工具

## 未来改进方向

- [ ] **将内置工具重组为工具组**：将现有内置工具从 5 个独立文件迁移至 2-3 个工具组文件
- [ ] **组级启用/禁用**：在设置界面中通过开关批量启用或禁用某组内所有工具
- [ ] **自动添加工具名前缀**：提供选项，自动为工具名称添加工具组文件名前缀（例如 `google_drive.js` 中所有工具自动添加 `google_drive_` 前缀）
- [ ] **组元数据**：在清单中新增组级字段：`group_name`、`group_description`、`group_version`、`author`
- [ ] **AI 工具组感知**：将工具组描述注入系统提示，使 AI 能够按服务名称推荐工具

## 测试要点

### 功能测试

- 验证工具组 JSON（数组格式）能被正确解析，并创建多个 JsTool 实例
- 验证工具组中的每个工具调用了正确的具名函数
- 验证具名函数接收与单工具 execute() 相同的 params（含 `_env`）
- 验证 JS 文件中的共享辅助函数可被所有工具函数访问
- 验证现有单工具 JSON（对象格式）继续正常工作，无需改动
- 验证使用 `execute(params)` 的单工具 JS 文件继续正常工作，无需改动
- 验证所有桥接功能（`lib()`、`fetch()`、`fs`、`console`、`_time()`）在工具组函数内正常工作
- 验证部分加载：无效条目被跳过，有效条目正常加载
- 验证组内名称重复：先出现者保留，后出现者跳过并报错
- 验证跨文件名称重复遵循 `allowOverride` 策略
- 验证工具组中的工具名称以独立工具条目出现在 ToolRegistry 中
- 验证工具组条目必须包含 `"function"` 字段
- 验证运行时函数缺失时返回清晰的错误信息

### 边界情况

- 只含单个工具的工具组（数组长度为 1）——行为应与单工具格式完全一致
- 包含 50 个工具的工具组（最大值）
- 工具函数名为 JS 保留字（例如 `delete`）
- 工具组条目中 `"function": "execute"`（合法——仅是一个函数名）
- JS 文件中存在未被任何 JSON 条目引用的额外函数（应被忽略）
- 工具组 JSON 中多条定义引用同一函数名（同一 JS 函数，两个工具定义）
- 同目录下工具组文件与单工具文件存在名称冲突
- 混合场景：目录中同时存在工具组文件和单工具文件

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-02-28 | 0.1 | 初始版本 | - |
