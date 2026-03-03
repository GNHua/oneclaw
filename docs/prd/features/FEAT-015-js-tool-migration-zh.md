# JavaScript 工具迁移与库系统

## 功能信息
- **Feature ID**: FEAT-015
- **创建日期**: 2026-02-28
- **最后更新**: 2026-02-28
- **状态**: Draft
- **优先级**: P1 (Should Have)
- **负责人**: TBD
- **关联 RFC**: RFC-015（待定）

## 用户故事

**作为** OneClaw 的开发者或高级用户，
**我希望** 内置工具以 JavaScript 实现（由原生桥接支撑），并且第三方 JavaScript 库对所有 JS 工具可用，
**以便** 工具生态系统可以轻松通过社区 JS 库进行扩展，`webfetch` 等新工具可以使用 Turndown 等库进行富内容处理，无需重新编译应用。

### 典型场景

1. Agent 调用 `webfetch` 并传入一个 URL。工具通过原生 HTTP 桥接获取 HTML，将其传递给内置的 Turndown 库，返回干净的 Markdown，供模型直接读取。
2. 开发者希望添加一个解析 RSS Feed 的新工具。他们找到一个纯 JS 的 RSS 解析库，将其放入共享库目录，然后编写一个导入该库的 JS 工具。无需修改任何 Kotlin 代码。
3. 现有的 `http_request` 内置工具现在是一个随应用附带的 JS 文件。用户可以将其作为参考，编写自己的基于 HTTP 的工具。
4. Agent 照常使用 `read_file`——此次迁移对 AI 模型和用户完全透明。

## 功能描述

### 概述

FEAT-015 具有两个紧密耦合的目标：

1. **JS 库捆绑系统**：允许将共享 JavaScript 库（如 Turndown）作为应用资产捆绑，并使其对在 QuickJS 运行时中运行的所有 JS 工具可用。
2. **内置工具 JS 迁移**：将现有的 Kotlin 内置工具（`get_current_time`、`read_file`、`write_file`、`http_request`）重新实现为以应用资产形式附带的 JavaScript 工具，并由 FEAT-012 中引入的相同原生桥接提供支撑。新增一个使用 Turndown 的 `webfetch` 内置 JS 工具。

完成迁移后，应用不再包含任何 Kotlin 工具实现。所有工具均为 JS 文件——Kotlin 层仅提供访问 Android 原生能力的桥接。

### 架构概览

```
AI Model
    ↓ tool call
ToolExecutionEngine  (Kotlin，不变)
    ↓
ToolRegistry  (Kotlin，不变)
    ├── Built-in JS Tools  [新增 - 以 assets/js/tools/ 形式附带]
    │     get_current_time.js
    │     read_file.js
    │     write_file.js
    │     http_request.js
    │     webfetch.js          ← 新工具
    └── User JS Tools  (FEAT-012 - 来自 /sdcard/OneClaw/tools/)

QuickJS Runtime  (FEAT-012 桥接)
    ├── Native Bridges: _time(), _readFile(), _writeFile(), _httpRequest()
    └── Shared Library Loader: lib('turndown'), lib('...')
           ↑
    assets/js/lib/
           turndown.min.js
           （未来可扩展更多库）
```

### 共享 JS 库系统

#### 库的存储位置

共享库作为应用资产捆绑：

```
assets/js/lib/
    turndown.min.js
```

用户自定义库（供高级用户使用）也可放置于：

```
{app_internal}/js/lib/
```

当应用内置库与用户自定义库发生名称冲突时，内置库优先。

#### 库加载 API

在任意 JS 工具（内置或用户自定义）中，通过注入到 QuickJS 全局作用域的 `lib()` 函数加载库：

```javascript
const TurndownService = lib('turndown');
const td = new TurndownService();
const markdown = td.turndown(htmlString);
```

`lib()` 函数的行为：
1. 在 `assets/js/lib/` 中查找库名（然后查找 `{app_internal}/js/lib/`）
2. 在沙盒作用域中对库脚本求值
3. 返回库导出的值（最后赋值的 `module.exports`、`exports` 或最后求值的表达式）
4. 缓存结果——在同一运行时会话中，后续的 `lib('turndown')` 调用将返回已缓存的实例

#### 库兼容性要求

库必须满足以下条件：
- **自包含**：不依赖 Node.js 内置模块（`require('fs')`、`require('path')` 等）
- **QuickJS 兼容**：标准 ES5/ES6+ JavaScript；不使用浏览器专有 API
- **纯逻辑库**：只处理数据的库，不依赖 DOM 或 Node.js 原生 API 的库

Turndown 满足以上全部条件。

### 内置工具 JS 迁移

以下 Kotlin 工具将被删除，并替换为等效的 JS 实现：

| 工具 | 已删除的 Kotlin 类 | 新增 JS 资产 |
|------|------------------------|----------------|
| `get_current_time` | `GetCurrentTimeTool.kt` | `assets/js/tools/get_current_time.js` |
| `read_file` | `ReadFileTool.kt` | `assets/js/tools/read_file.js` |
| `write_file` | `WriteFileTool.kt` | `assets/js/tools/write_file.js` |
| `http_request` | `HttpRequestTool.kt` | `assets/js/tools/http_request.js` |

每个 JS 工具均通过 FEAT-012 中已建立的原生桥接调用底层能力：

```javascript
// get_current_time.js
function execute(params) {
    return { time: _time() };
}
```

```javascript
// http_request.js
function execute(params) {
    const response = _httpRequest(params.method, params.url, params.headers, params.body);
    return response;
}
```

工具定义（名称、描述、参数 schema）从硬编码的 Kotlin `ToolDefinition` 对象迁移至与用户 JS 工具（FEAT-012）相同的 `.json` 元数据格式，存储于 `assets/js/tools/` 中对应 `.js` 文件旁边。

### 新工具：`webfetch`

`webfetch` 是一个新的内置 JS 工具，用于获取指定 URL 的页面内容并以干净的 Markdown 格式返回。

#### 工具定义

| 字段 | 值 |
|-------|-------|
| 名称 | `webfetch` |
| 描述 | 获取一个网页并以 Markdown 格式返回其内容 |
| 参数 | `url`（string，必填）：要获取的 URL |
| 所需权限 | `INTERNET`（`http_request` 已申请） |
| 超时 | 30 秒 |
| 返回值 | 页面内容的 Markdown 字符串 |

#### 实现

```javascript
// webfetch.js
const TurndownService = lib('turndown');

function execute(params) {
    const response = _httpRequest('GET', params.url, {}, null);
    if (response.error) {
        return { error: response.error };
    }
    const contentType = (response.headers['content-type'] || '').toLowerCase();
    if (!contentType.includes('text/html')) {
        return { content: response.body };
    }
    const td = new TurndownService({ headingStyle: 'atx', codeBlockStyle: 'fenced' });
    // 在转换前去除 nav、header、footer、script、style 标签
    const cleanedHtml = response.body
        .replace(/<(script|style|nav|header|footer)[^>]*>[\s\S]*?<\/\1>/gi, '');
    const markdown = td.turndown(cleanedHtml);
    return { content: markdown };
}
```

#### 与 `http_request` 的关系

`webfetch` 与 `http_request` 是职责不同的独立工具：

| | `http_request` | `webfetch` |
|--|----------------|------------|
| 用途 | 通用 HTTP 请求 | 供人类阅读的网页内容 |
| 响应 | 原始 HTTP 响应（body、status、headers） | Markdown 字符串 |
| 适用场景 | API、JSON 接口、自定义请求头 | 文档、文章、网页 |
| Turndown | 否 | 是 |

### 内置 JS 工具加载

内置 JS 工具（来自 `assets/js/tools/`）在应用启动时与 FEAT-012 用户 JS 工具一起加载：

1. 启动时，`JsToolLoader` 扫描 `assets/js/tools/` 中的 `*.json` + `*.js` 文件对
2. 每对文件被注册到 `ToolRegistry` 中，作为 `JsTool`（与 FEAT-012 用户工具使用同一类）
3. 之后加载应用内部用户工具和外部用户工具（FEAT-012 行为不变）
4. 发生名称冲突时：用户工具覆盖内置工具（高级用户可替换任意内置工具）

### 原生桥接要求

FEAT-012 引入了 QuickJS 桥接。FEAT-015 要求以下桥接可用（部分可能已在 FEAT-012 中存在）：

| 桥接函数 | 描述 | 是否已在 FEAT-012 中？ |
|----------------|-------------|----------------------|
| `_time()` | 返回 ISO 8601 格式的当前时间字符串 | 可能已有 |
| `_readFile(path)` | 以字符串形式读取文件内容 | 可能已有 |
| `_writeFile(path, content)` | 将字符串写入文件 | 可能已有 |
| `_httpRequest(method, url, headers, body)` | 发起 HTTP 请求，返回 `{status, body, headers, error}` | 可能已有 |

如果 FEAT-012 中缺少任意桥接，必须在 FEAT-015 实现时补充。

### 用户交互流程

#### 在对话中使用 `webfetch`

```
1. 用户："总结 https://example.com/article 的内容"
2. AI 调用 webfetch(url="https://example.com/article")
3. 工具获取 HTML，运行 Turndown，返回 Markdown
4. AI 总结 Markdown 内容并回复用户
5. 聊天界面显示 webfetch 工具调用（URL → Markdown）
```

#### 用户 JS 工具使用共享库

```
1. 用户请求 AI："创建一个将文本转换为标题大小写的工具"
2. AI 生成：
   - {tools}/title_case.js（使用 lib('some-case-library') 或纯 JS 实现）
   - {tools}/title_case.json
3. 工具立即在注册表中可用
4. 用户在下一条消息中调用该工具
```

## 验收标准

必须通过（全部必须满足）：

- [ ] 现有的四个 Kotlin 内置工具（`get_current_time`、`read_file`、`write_file`、`http_request`）已从 Kotlin 中删除，并替换为等效的 JS 实现
- [ ] 迁移后的工具行为与其 Kotlin 前身完全一致（工具名称、参数 schema、返回格式均相同）
- [ ] 内置 JS 工具在应用启动时从 `assets/js/tools/` 加载
- [ ] `lib()` 函数在所有 JS 工具的 QuickJS 全局作用域中可用
- [ ] `lib('turndown')` 能正确加载内置的 Turndown 库，并返回可用的 `TurndownService` 构造函数
- [ ] `webfetch` 工具可用，并能为给定 URL 返回干净的 Markdown
- [ ] `webfetch` 能正确处理非 HTML 响应（直接返回原始 body，不经过 Turndown 处理）
- [ ] `webfetch` 能优雅处理 HTTP 错误（返回错误信息）
- [ ] 用户自定义 JS 工具（FEAT-012）也可以调用 `lib()` 访问共享库
- [ ] 与内置工具同名的用户工具能覆盖内置工具
- [ ] 迁移后，所有内置工具的 Layer 1A 现有测试仍能通过

可选（V1 的锦上添花）：

- [ ] 用户可以将自定义库添加到 `{app_internal}/js/lib/`，并通过 `lib()` 使用
- [ ] 库加载失败时，错误信息能明确指出失败的库名称
- [ ] 内置工具的 JS 源文件可从设置或文件管理器中查看（作为参考）

## UI/UX 要求

本功能没有新增 UI。此次迁移对用户完全透明：
- 工具名称和行为完全不变
- `webfetch` 像其他工具一样出现在工具列表中
- 聊天界面中的工具调用展示保持不变

## 功能边界

### 包含范围

- JS 库捆绑系统（`lib()` API、`assets/js/lib/` 目录）
- 内置 Turndown 库（`turndown.min.js`）
- 将 `get_current_time`、`read_file`、`write_file`、`http_request` 从 Kotlin 迁移至 JS
- 新增 `webfetch` 内置 JS 工具
- 从 `assets/js/tools/` 加载内置 JS 工具
- 发生名称冲突时用户工具覆盖内置工具

### 不包含范围（V1）

- 库版本管理或更新机制
- 库市场或发现功能
- 库的 Tree-shaking 或打包优化
- 需要 DOM 的库（jsdom 等）
- `webfetch` 中的 HTML 截断 / token 限制感知（推迟至未来功能）
- `webfetch` 缓存（推迟）
- `webfetch` 中 PDF 或非 HTML 内容的提取（返回原始 body）

## 业务规则

1. 内置 JS 工具为只读——用户不能通过应用内 UI 编辑或删除它们
2. 与内置工具同名的用户工具将静默覆盖内置工具
3. `lib()` 仅通过名称解析库（不通过路径）——不允许任意文件访问
4. 通过 `lib()` 加载的库被沙盒限制在同一 QuickJS 实例中——不能直接访问 Android API
5. `webfetch` 仅接受 HTTP 和 HTTPS URL
6. `webfetch` 遵循重定向（最多 5 跳，与 `http_request` 行为一致）
7. `webfetch` 在 Turndown 转换前会剥离 `<script>`、`<style>`、`<nav>`、`<header>`、`<footer>` 标签

## 非功能性要求

### 性能

- `lib()` 首次加载（冷启动）：Turndown 加载时间 < 100ms
- `lib()` 后续调用（已缓存）：< 1ms
- `webfetch` 总耗时（不含网络）：Turndown 处理时间 < 200ms
- 加载内置 JS 工具导致的应用启动时间增加：< 100ms

### 兼容性

- 所有迁移后的工具必须完全向后兼容：工具名称、参数 schema、返回值结构均相同

### 安全性

- `lib()` 不能加载 `assets/js/lib/` 和 `{app_internal}/js/lib/` 以外的文件
- 库在与其他 JS 工具相同的 QuickJS 沙盒中运行——没有提升的访问权限
- `webfetch` 不跟随跨域重定向到 `file://` 或 `content://` URI

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：被替换的工具接口、注册表和执行引擎
- **FEAT-012（JavaScript 工具引擎）**：QuickJS 运行时、原生桥接、JS 工具加载基础设施

### 被依赖于

- 目前没有其他功能依赖 FEAT-015

### 外部依赖

- **Turndown**（约 20KB 压缩版，无运行时依赖）：以 `assets/js/lib/turndown.min.js` 形式捆绑

## 错误处理

### 错误场景

1. **库未找到**
   - 原因：对不存在的库调用了 `lib('unknown-lib')`
   - 处理方式：在 QuickJS 内部抛出 `Error("Library 'unknown-lib' not found")`——由 `ToolExecutionEngine` 捕获并以 `ToolResult.error()` 形式返回

2. **库解析/求值错误**
   - 原因：库文件已损坏
   - 处理方式：抛出描述性错误；工具返回错误结果

3. **`webfetch` 网络错误**
   - 原因：URL 不可达、超时、DNS 解析失败
   - 处理方式：返回 `{ error: "Network error: <message>" }`

4. **`webfetch` 非 200 响应**
   - 原因：404、500 等状态码
   - 处理方式：返回 `{ error: "HTTP <status>: <status text>", content: response.body }`

5. **Turndown 转换失败**
   - 原因：Turndown 无法处理的格式错误 HTML
   - 处理方式：回退为返回原始去标签文本；记录警告日志

6. **内置工具 JS 文件在资产中缺失**
   - 原因：构建配置错误
   - 处理方式：启动时记录错误日志；继续运行但不注册受影响的工具；在启动诊断日志中显示

## 未来改进

- [ ] **`webfetch` 中的 HTML 截断**：将 Markdown 输出限制在可配置的 token 预算内，避免超出模型上下文
- [ ] **`webfetch` 缓存**：为获取的页面设置短暂的 TTL 缓存（如 5 分钟），避免重复网络请求
- [ ] **更多内置库**：例如 Markdown 解析器、CSV 解析器、日期/时间工具库
- [ ] **库清单**：`lib-manifest.json` 文件，列出内置库的名称、版本和描述，在设置中展示
- [ ] **用户库上传**：提供 UI 以便用户无需使用文件管理器即可添加自定义库

## 测试要点

### 功能测试

- 验证 `get_current_time` JS 工具返回正确的 ISO 8601 时间
- 验证 `read_file` JS 工具能正确读取文件内容
- 验证 `write_file` JS 工具能正确写入和覆写文件内容
- 验证 `http_request` JS 工具能发起 GET/POST 请求并返回 status、body、headers
- 验证所有四个迁移后的工具与其 Kotlin 前身具有完全相同的参数 schema
- 验证 `lib('turndown')` 返回可用的构造函数
- 验证 `lib('nonexistent')` 返回清晰的错误信息
- 验证 `webfetch` 对 HTML 页面返回 Markdown
- 验证 `webfetch` 在转换前剥离 `<script>` 和 `<nav>` 标签
- 验证 `webfetch` 对非 HTML 内容类型返回原始 body
- 验证 `webfetch` 对不可达 URL 返回错误
- 验证用户 JS 工具可以调用 `lib()` 使用共享库
- 验证与内置工具同名的用户工具能覆盖内置工具

### 边界情况

- `webfetch` 获取无 body 内容的页面（空 HTML）
- `webfetch` 获取非常大的 HTML 页面（>1MB）
- 同一会话中多个工具并发调用 `lib()`
- 带自定义请求头和 POST body 的 `http_request`（与迁移前行为相同）
- 内置工具目录存在，但某个工具缺少 `.json` 或 `.js` 文件
- `webfetch` 重定向链超过 5 跳

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-02-28 | 0.1 | 初始版本 | - |
