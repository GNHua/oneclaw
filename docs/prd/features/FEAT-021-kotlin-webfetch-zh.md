# Kotlin Webfetch 工具

## 功能信息
- **功能 ID**: FEAT-021
- **创建日期**: 2026-03-01
- **最后更新**: 2026-03-01
- **状态**: 草稿
- **优先级**: P1（应有）
- **负责人**: 待定
- **关联 RFC**: RFC-021（待定）

## 用户故事

**作为** 使用 OneClaw 的 AI 智能体，
**我希望** 拥有一个可靠的 webfetch 工具，能够使用真正的 HTML 解析器将网页准确转换为 Markdown，
**以便** 我可以提取和摘要网页内容，而不受基于正则表达式的 HTML 转 Markdown 转换方式的限制。

### 典型场景

1. 智能体使用 URL 调用 `webfetch`。工具获取 HTML，使用 Jsoup（真正的 HTML 解析器）解析，提取主要内容区域，将其转换为 Markdown，并返回干净、可读的文本。
2. 智能体获取一个文档页面。Jsoup 基于 DOM 的解析能够正确处理嵌套元素、格式不规范的 HTML 以及基于正则表达式的转换会产生错误结果的复杂表格结构。
3. 智能体获取一个大型页面（例如 API 参考文档）。工具自动将输出截断至可配置的字符限制，防止上下文窗口溢出。
4. 智能体获取非 HTML 资源（JSON、纯文本）。工具直接返回原始响应体，不尝试进行 Markdown 转换。

## 功能描述

### 概述

FEAT-021 将当前基于 JavaScript 的 `webfetch` 工具（实现于 `assets/js/tools/webfetch.js`）替换为使用 Jsoup 进行 HTML 解析和 Markdown 转换的 Kotlin 原生实现。当前的 JS 实现使用基于正则表达式的 HTML 转 Markdown 转换方式，该方式较为脆弱，无法可靠地处理嵌套结构、格式不规范的 HTML 或复杂的页面布局。

新的 Kotlin 实现提供：
- **基于 DOM 的 HTML 解析**（通过 Jsoup）-- 能够正确处理现实世界中的 HTML
- **内容提取** -- 类 Readability 风格的主内容检测（article/main/body 回退策略）
- **正确的 Markdown 转换** -- 通过 DOM 遍历处理嵌套列表、表格、代码块和行内格式
- **输出大小控制** -- 可配置的字符限制与干净的截断
- **相同接口** -- 相同的工具名称、相同的参数，透明替换

### 架构概述

```
AI 模型
    | 工具调用：webfetch(url="...")
    v
ToolExecutionEngine  （Kotlin，不变）
    |
    v
ToolRegistry
    |
    v
WebfetchTool  [新增 - Kotlin 内置工具]
    |
    +-- OkHttpClient（获取 HTML）
    |
    +-- Jsoup（解析 HTML）
    |
    +-- HtmlToMarkdownConverter [新增 - 工具类]
            |
            +-- 内容提取（article/main 检测）
            +-- DOM 转 Markdown 遍历
            +-- 输出截断
```

### 为何用 Kotlin 替换 JS？

当前的 JS `webfetch`（`assets/js/tools/webfetch.js`）使用基于正则表达式的 HTML 转 Markdown 转换方式。该方式存在根本性的局限：

1. **无 DOM 感知能力**：正则表达式无法解析嵌套 HTML 结构。嵌套列表、表格中的表格以及重叠标签会产生错误输出。
2. **脆弱的内容提取**：JS 实现使用单一正则表达式查找 `<main>` 或 `<article>` 标签，在页面包含多个此类元素或内容区域深度嵌套时会失败。
3. **QuickJS 中无 Turndown**：原始 FEAT-015 设计计划使用 Turndown（一个真正的基于 DOM 的转换器），但 Turndown 依赖 DOM API（`document.createElement` 等），而 QuickJS 并不提供这些 API。正则表达式回退方案是一种权宜之计。
4. **Jsoup 久经考验**：Jsoup 是标准的 Java/Kotlin HTML 解析器，能够优雅地处理格式不规范的 HTML，并提供完整的 DOM API 用于精确的内容提取。

### 内容提取策略

工具采用受 Readability 启发的方式来查找主要内容：

1. **优先顺序**：`<article>` > `<main>` > `<div role="main">` > `<body>`
2. **噪音去除**：在转换前删除 `<script>`、`<style>`、`<nav>`、`<header>`、`<footer>`、`<aside>`、`<noscript>`、`<svg>`、`<iframe>`、`<form>` 元素
3. **标题提取**：提取 `<title>` 并以 `# 标题` 的形式添加到内容开头（如果内容中尚未包含）

### Markdown 转换

基于 DOM 的遍历将 HTML 元素转换为 Markdown：

| HTML 元素 | Markdown 输出 |
|---|---|
| `<h1>` - `<h6>` | `#` - `######` |
| `<p>` | 段落，末尾加双换行 |
| `<a href="...">` | `[文本](url)` |
| `<strong>`、`<b>` | `**文本**` |
| `<em>`、`<i>` | `*文本*` |
| `<code>` | `` `文本` `` |
| `<pre><code>` | 围栏式代码块 |
| `<ul>/<li>` | `- 列表项`（支持嵌套） |
| `<ol>/<li>` | `1. 列表项`（支持嵌套） |
| `<blockquote>` | `> 文本` |
| `<img>` | `![alt](src)` |
| `<hr>` | `---` |
| `<br>` | 换行 |
| `<table>` | 带表头分隔符的 Markdown 表格 |

### 输出大小控制

大型网页可能产生超过 AI 模型上下文窗口的 Markdown 内容。工具支持输出截断：

- 默认限制：50,000 个字符（可配置）
- 截断在段落/块边界处发生（不在句子中间）
- 截断后的输出以 `\n\n[Content truncated at {limit} characters]` 结尾
- AI 模型可通过 `max_length` 参数请求更高或更低的限制

### 工具定义

| 字段 | 值 |
|-------|-------|
| 名称 | `webfetch` |
| 描述 | 获取网页并以 Markdown 格式返回其内容 |
| 参数 | `url`（字符串，必填）：要获取的 URL |
| | `max_length`（整数，可选）：最大输出长度（字符数）。默认值：50000 |
| 所需权限 | `INTERNET` |
| 超时时间 | 30 秒 |
| 返回值 | 页面内容的 Markdown 字符串，或错误对象 |

### 用户交互流程

```
1. 用户："Jsoup 首页写了什么？"
2. AI 调用 webfetch(url="https://jsoup.org")
3. WebfetchTool：
   a. 通过 OkHttpClient 获取 HTML
   b. 使用 Jsoup 解析
   c. 提取主要内容区域
   d. 通过 HtmlToMarkdownConverter 转换为 Markdown
   e. 必要时进行截断
4. AI 接收干净的 Markdown，为用户进行摘要
5. 聊天界面显示 webfetch 工具调用结果
```

## 验收标准

必须通过（全部必填）：

- [ ] `webfetch` 工具作为 Kotlin 内置工具注册到 `ToolRegistry`
- [ ] JS 文件 `webfetch.js` 和 `webfetch.json` 已从 `assets/js/tools/` 中删除
- [ ] 工具名称（`webfetch`）和必填参数（`url`）保持不变
- [ ] HTML 页面通过 Jsoup 解析，并经由 DOM 遍历转换为 Markdown
- [ ] 内容提取能正确识别 `<article>` 或 `<main>` 内容区域
- [ ] 噪音元素（`<script>`、`<style>`、`<nav>` 等）在转换前被去除
- [ ] 非 HTML 响应（JSON、纯文本等）返回原始响应体
- [ ] HTTP 错误返回包含状态码和消息的错误对象
- [ ] 输出在可配置的字符限制处被截断（默认 50,000）
- [ ] 截断在块边界处发生，不在单词中间
- [ ] 嵌套 HTML 结构（列表、表格）被正确转换
- [ ] 所有 Layer 1A 测试通过

可选（V1 版本中的优化项）：

- [ ] 从 `Content-Type` 头检测语言/字符集
- [ ] 提取 `<meta>` 描述作为摘要行
- [ ] 支持 `selector` 参数以提取特定 CSS 选择器

## UI/UX 需求

本功能没有新的 UI。替换是透明的：
- 工具列表中显示相同的工具名称
- 接受相同的参数
- 聊天中的工具调用展示保持不变

## 功能边界

### 包含内容

- 使用 Jsoup 的 Kotlin `WebfetchTool` 实现
- 基于 DOM 遍历的 `HtmlToMarkdownConverter` 工具类
- 在 `build.gradle.kts` 中添加 Jsoup 依赖
- 从 assets 中删除 JS 文件 `webfetch.js` 和 `webfetch.json`
- 更新 `ToolModule` 中的注册
- 支持可配置限制的输出截断

### 不包含内容（V1 版本）

- JavaScript 渲染（单页应用/动态页面）-- 延迟至 FEAT-022
- 响应缓存
- PDF 或二进制内容提取
- Cookie 或身份验证支持
- 可读性评分（完整的 Mozilla Readability 算法）
- 代理配置

## 业务规则

1. `webfetch` 仅接受 HTTP 和 HTTPS URL
2. `webfetch` 跟随重定向（最多 5 次跳转，与 OkHttpClient 默认值一致）
3. `webfetch` 不跟随重定向至 `file://` 或 `content://` URI
4. 若未指定 `max_length`，输出截断默认为 50,000 个字符
5. 非 HTML 内容类型以原始文本返回，不进行 Markdown 转换
6. `User-Agent` 请求头设置为移动浏览器标识，以避免被反爬虫机制拦截

## 非功能性需求

### 性能

- HTML 解析 + Markdown 转换：对于典型页面（< 500KB HTML）应小于 200ms
- 内存：Jsoup DOM 仅在转换期间保留在内存中，之后会被垃圾回收
- 工具调用之间无持久状态

### 兼容性

- 与 JS 前身保持相同的工具名称和参数 schema
- 使用 `webfetch` 的 AI 模型不会感知到行为变化（两种实现的输出均为 Markdown）

### 安全性

- URL 验证：仅允许 HTTP/HTTPS scheme
- 不跟随重定向至非 HTTP scheme
- Jsoup 解析在设计上可防御 XSS（输出为 Markdown，而非 HTML）
- OkHttpClient 超时机制防止在响应缓慢的服务器上挂起

## 依赖关系

### 依赖于

- **FEAT-004（工具系统）**：工具接口、注册表、执行引擎
- **FEAT-015（JS 工具迁移）**：建立了当前正在被替换的 JS webfetch 实现

### 被依赖于

- 目前没有其他功能依赖 FEAT-021

### 外部依赖

- **Jsoup**（约 400KB）：Java/Kotlin HTML 解析器库。Apache 2.0 许可证。广泛使用，维护良好。

## 错误处理

### 错误场景

1. **无效 URL**
   - 原因：URL 格式不正确或使用了非 HTTP scheme
   - 处理方式：返回 `ToolResult.error("Invalid URL: <message>")`

2. **网络错误**
   - 原因：DNS 解析失败、连接超时、服务器不可达
   - 处理方式：返回 `ToolResult.error("Network error: <message>")`

3. **非 200 HTTP 响应**
   - 原因：404、500 等
   - 处理方式：返回包含状态码的错误；如有响应体则一并包含

4. **HTML 解析失败**
   - 原因：内容严重格式不规范
   - 处理方式：Jsoup 能优雅地处理格式不规范的 HTML；若转换结果为空则回退到原始文本

5. **响应体过大**
   - 原因：页面超出合理大小（例如 >5MB）
   - 处理方式：在解析前截断 HTML，防止内存溢出（OOM）

## 未来改进

- [ ] **完整 Readability 算法**：实现类似 Mozilla Readability 的内容评分，以提升主内容检测效果
- [ ] **响应缓存**：以短 TTL 缓存已获取的页面，避免重复请求
- [ ] **CSS 选择器提取**：允许指定 CSS 选择器以提取特定页面片段
- [ ] **元数据提取**：以结构化字段返回页面元数据（标题、描述、作者、发布日期）
- [ ] **多页爬取**：跟随分页链接获取多页内容

## 测试要点

### 功能测试

- 验证 `webfetch` 对标准 HTML 页面返回 Markdown
- 验证内容提取优先选择 `<article>` 而非 `<body>`
- 验证在没有 `<article>` 时内容提取优先选择 `<main>`
- 验证噪音元素（`<script>`、`<nav>` 等）被去除
- 验证标题被转换为 `#` 语法
- 验证链接被转换为 `[文本](url)` 语法
- 验证嵌套列表被正确缩进
- 验证表格被转换为 Markdown 表格语法
- 验证代码块使用三重反引号围栏
- 验证非 HTML 内容类型返回原始响应体
- 验证 HTTP 错误返回错误对象
- 验证在可配置限制处进行输出截断
- 验证截断在块边界处发生
- 验证 `max_length` 参数被正确遵守

### 边界情况

- 无 `<body>` 内容的页面（空 HTML）
- 非常大的页面（>1MB HTML）
- 深度嵌套元素的页面（>10 层）
- 包含格式不规范/未闭合标签的页面
- 包含混合编码的页面
- 返回重定向链的 URL
- 返回二进制内容（图片、PDF）的 URL
- 仅包含 `<table>` 内容的页面（无散文）
- `max_length` 设置为 0 或负值

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | 初始版本 | - |
