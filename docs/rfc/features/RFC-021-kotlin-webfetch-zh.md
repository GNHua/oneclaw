# RFC-021：Kotlin Webfetch 工具

## 文档信息
- **RFC ID**：RFC-021
- **关联 PRD**：[FEAT-021（Kotlin Webfetch 工具）](../../prd/features/FEAT-021-kotlin-webfetch.md)
- **关联架构**：[RFC-000（整体架构）](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**：[RFC-004（工具系统）](RFC-004-tool-system.md)、[RFC-015（JS 工具迁移）](RFC-015-js-tool-migration.md)
- **创建时间**：2026-03-01
- **最后更新**：2026-03-01
- **状态**：草稿
- **作者**：待定

## 概述

### 背景

RFC-015 引入了一个基于 JavaScript 的 `webfetch` 工具，用于抓取网页并将 HTML 转换为 Markdown。原始设计打算使用 Turndown 库进行基于 DOM 的 HTML 到 Markdown 转换。然而，Turndown 需要 DOM API（`document.createElement`、`DOMParser` 等），而这些 API 在 QuickJS JavaScript 运行时中不可用。当前实现退而使用基于正则表达式的转换，存在根本性的局限：

- 无法处理嵌套结构（列表中的列表、引用块中的表格）
- 使用单次匹配正则表达式提取 `<main>`/`<article>` 标签内容，十分脆弱
- 对格式不规范的 HTML（未闭合标签、元素重叠）处理失败
- 无法正确处理字符编码的边缘情况

RFC-021 将 JS `webfetch` 工具替换为使用 Jsoup 的 Kotlin 原生实现。Jsoup 是一个经过实战检验的 Java HTML 解析器，提供完整的 DOM API。这让我们无需在 JS 运行时中使用 DOM API，即可实现正确的 HTML 解析、健壮的内容提取和准确的 Markdown 转换。

### 目标

1. 在 `tool/builtin/` 中实现 `WebfetchTool.kt`，作为 Kotlin 内置工具
2. 实现 `HtmlToMarkdownConverter` 工具类，用于基于 DOM 的 HTML 到 Markdown 转换
3. 向项目添加 Jsoup 依赖
4. 从 `assets/js/tools/` 中删除 JS 版本的 `webfetch.js` 和 `webfetch.json`
5. 更新 `ToolModule`，注册新的 Kotlin `WebfetchTool`
6. 添加输出截断功能，支持可配置的字符限制

### 非目标

- 实现完整的 Mozilla Readability 评分算法
- 对动态页面/单页应用（SPA）进行 JavaScript 渲染（延至 RFC-022）
- 响应缓存
- PDF 或二进制内容提取
- Cookie/会话管理

## 技术设计

### 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                     对话层（RFC-001）                          │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  工具调用：webfetch(url, max_length?)                 │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   工具执行引擎（RFC-004）                       │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐                                  │  │
│  │  │    webfetch       │  Kotlin 内置工具【新增】          │  │
│  │  │ (WebfetchTool.kt) │                                 │  │
│  │  └───────┬──────────┘                                  │  │
│  │          │                                              │  │
│  │          v                                              │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │           WebfetchTool                            │  │  │
│  │  │  1. 验证 URL                                      │  │  │
│  │  │  2. 通过 OkHttpClient 抓取 HTML                   │  │  │
│  │  │  3. 使用 Jsoup 解析                               │  │  │
│  │  │  4. 提取主要内容                                   │  │  │
│  │  │  5. 转换为 Markdown（HtmlToMarkdownConverter）     │  │  │
│  │  │  6. 必要时进行截断                                 │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### 核心组件

**新增：**
1. `WebfetchTool` -- Kotlin 内置工具，抓取网页并返回 Markdown
2. `HtmlToMarkdownConverter` -- 基于 DOM 的 HTML 到 Markdown 转换工具类

**修改：**
3. `ToolModule` -- 将 `WebfetchTool` 注册为 Kotlin 内置工具

**删除：**
4. `assets/js/tools/webfetch.js` -- JS 实现
5. `assets/js/tools/webfetch.json` -- JS 工具定义

## 详细设计

### 目录结构（新增及变更文件）

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── WebfetchTool.kt             # 新增
│   │   │   ├── LoadSkillTool.kt            # 不变
│   │   │   ├── CreateScheduledTaskTool.kt  # 不变
│   │   │   └── CreateAgentTool.kt          # 不变
│   │   └── util/
│   │       └── HtmlToMarkdownConverter.kt  # 新增
│   └── di/
│       └── ToolModule.kt                   # 修改
├── assets/
│   └── js/
│       └── tools/
│           ├── webfetch.js                 # 删除
│           ├── webfetch.json               # 删除
│           ├── get_current_time.js          # 不变
│           └── ...                          # 其他 JS 工具不变

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   └── WebfetchToolTest.kt          # 新增
        └── util/
            └── HtmlToMarkdownConverterTest.kt # 新增
```

### WebfetchTool

```kotlin
/**
 * 位置：tool/builtin/WebfetchTool.kt
 *
 * Kotlin 原生 webfetch 工具，使用 Jsoup 抓取网页并将
 * HTML 转换为 Markdown。替代 JS 版 webfetch 实现。
 */
class WebfetchTool(
    private val okHttpClient: OkHttpClient
) : Tool {

    companion object {
        private const val TAG = "WebfetchTool"
        private const val DEFAULT_MAX_LENGTH = 50_000
        private const val MAX_RESPONSE_SIZE = 5 * 1024 * 1024  // 5MB
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
    }

    override val definition = ToolDefinition(
        name = "webfetch",
        description = "Fetch a web page and return its content as Markdown",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "The URL to fetch"
                ),
                "max_length" to ToolParameter(
                    type = "integer",
                    description = "Maximum output length in characters. Default: 50000"
                )
            ),
            required = listOf("url")
        )
    )

    override suspend fun execute(
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        val url = params["url"]?.toString()
            ?: return ToolResult.error("Parameter 'url' is required")

        val maxLength = (params["max_length"] as? Number)?.toInt()
            ?: DEFAULT_MAX_LENGTH

        // 验证 URL scheme
        val parsedUrl = try {
            java.net.URL(url)
        } catch (e: Exception) {
            return ToolResult.error("Invalid URL: ${e.message}")
        }

        if (parsedUrl.protocol !in listOf("http", "https")) {
            return ToolResult.error("Only HTTP and HTTPS URLs are supported")
        }

        return try {
            val response = fetchUrl(url)
            processResponse(response, maxLength)
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("Request timed out: ${e.message}")
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("DNS resolution failed: ${e.message}")
        } catch (e: java.io.IOException) {
            ToolResult.error("Network error: ${e.message}")
        } catch (e: Exception) {
            Log.e(TAG, "Unexpected error fetching $url", e)
            ToolResult.error("Error: ${e.message}")
        }
    }

    private suspend fun fetchUrl(url: String): okhttp3.Response {
        val request = okhttp3.Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8")
            .header("Accept-Language", "en-US,en;q=0.5")
            .build()

        return withContext(Dispatchers.IO) {
            okHttpClient.newCall(request).execute()
        }
    }

    private fun processResponse(
        response: okhttp3.Response,
        maxLength: Int
    ): ToolResult {
        if (!response.isSuccessful) {
            val body = response.body?.string()?.take(1000) ?: ""
            return ToolResult.error(
                "HTTP ${response.code}: ${response.message}\n$body"
            )
        }

        val contentType = response.header("Content-Type")?.lowercase() ?: ""
        val body = response.body?.let { responseBody ->
            // 限制响应大小以防止内存溢出
            val source = responseBody.source()
            source.request(MAX_RESPONSE_SIZE.toLong())
            val buffer = source.buffer
            if (buffer.size > MAX_RESPONSE_SIZE) {
                buffer.snapshot(MAX_RESPONSE_SIZE.toLong()).utf8()
            } else {
                responseBody.string()
            }
        } ?: return ToolResult.error("Empty response body")

        // 非 HTML：直接返回原始内容（截断）
        if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
            return ToolResult.success(truncateText(body, maxLength))
        }

        // HTML：解析并转换为 Markdown
        val markdown = HtmlToMarkdownConverter.convert(body, response.request.url.toString())
        return ToolResult.success(truncateText(markdown, maxLength))
    }

    private fun truncateText(text: String, maxLength: Int): String {
        if (maxLength <= 0 || text.length <= maxLength) return text

        // 在限制之前找到最近的段落/块边界
        val truncateAt = text.lastIndexOf("\n\n", maxLength)
        val cutoff = if (truncateAt > maxLength / 2) truncateAt else maxLength

        return text.substring(0, cutoff) + "\n\n[Content truncated at $maxLength characters]"
    }
}
```

### HtmlToMarkdownConverter

```kotlin
/**
 * 位置：tool/util/HtmlToMarkdownConverter.kt
 *
 * 使用 Jsoup DOM 遍历将 HTML 转换为 Markdown。
 * 处理内容提取（article/main 检测）、
 * 噪声元素移除，以及逐元素的 Markdown 渲染。
 */
object HtmlToMarkdownConverter {

    // 需要完全移除（包括标签和内容）的元素
    private val NOISE_TAGS = setOf(
        "script", "style", "nav", "header", "footer", "aside",
        "noscript", "svg", "iframe", "form", "button", "input",
        "select", "textarea"
    )

    // 产生段落换行的块级元素
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "figure",
        "figcaption", "details", "summary", "address"
    )

    /**
     * 将 HTML 字符串转换为 Markdown。
     *
     * @param html 原始 HTML 字符串
     * @param baseUrl 可选的基础 URL，用于解析相对链接
     * @return Markdown 字符串
     */
    fun convert(html: String, baseUrl: String? = null): String {
        val doc = if (baseUrl != null) {
            Jsoup.parse(html, baseUrl)
        } else {
            Jsoup.parse(html)
        }

        // 提取标题
        val title = doc.title().takeIf { it.isNotBlank() }

        // 移除噪声元素
        NOISE_TAGS.forEach { tag ->
            doc.select(tag).remove()
        }

        // 查找主要内容区域
        val contentElement = findMainContent(doc)

        // 转换为 Markdown
        val markdown = convertElement(contentElement, depth = 0)

        // 清理空白字符
        val cleaned = cleanupWhitespace(markdown)

        // 如果标题不在内容中则追加到开头
        return if (title != null && !cleaned.startsWith("# ") && title !in cleaned.take(200)) {
            "# $title\n\n$cleaned"
        } else {
            cleaned
        }
    }

    /**
     * 使用基于优先级的策略查找主要内容元素。
     * article > main > [role="main"] > body
     */
    private fun findMainContent(doc: Document): Element {
        // 首先尝试 <article> -- 最具体的内容标记
        doc.selectFirst("article")?.let { return it }

        // 尝试 <main>
        doc.selectFirst("main")?.let { return it }

        // 尝试 role="main"
        doc.selectFirst("[role=main]")?.let { return it }

        // 回退到 <body>
        return doc.body() ?: doc
    }

    /**
     * 递归地将 Jsoup Element 转换为 Markdown。
     */
    private fun convertElement(element: Element, depth: Int): String {
        val sb = StringBuilder()

        for (node in element.childNodes()) {
            when (node) {
                is TextNode -> {
                    val text = node.text()
                    if (text.isNotBlank()) {
                        sb.append(text)
                    } else if (text.isNotEmpty() && sb.isNotEmpty() && !sb.endsWith(" ")) {
                        sb.append(" ")
                    }
                }
                is Element -> {
                    sb.append(convertTag(node, depth))
                }
            }
        }

        return sb.toString()
    }

    /**
     * 将特定 HTML 标签转换为对应的 Markdown 格式。
     */
    private fun convertTag(el: Element, depth: Int): String {
        val tag = el.tagName().lowercase()

        return when (tag) {
            // 标题
            "h1" -> "\n\n# ${inlineText(el)}\n\n"
            "h2" -> "\n\n## ${inlineText(el)}\n\n"
            "h3" -> "\n\n### ${inlineText(el)}\n\n"
            "h4" -> "\n\n#### ${inlineText(el)}\n\n"
            "h5" -> "\n\n##### ${inlineText(el)}\n\n"
            "h6" -> "\n\n###### ${inlineText(el)}\n\n"

            // 段落和块级元素
            "p" -> "\n\n${convertElement(el, depth)}\n\n"
            "div", "section", "article", "main" -> "\n\n${convertElement(el, depth)}\n\n"

            // 链接
            "a" -> {
                val href = el.absUrl("href").ifEmpty { el.attr("href") }
                val text = inlineText(el)
                if (text.isNotBlank() && href.isNotBlank()) {
                    "[$text]($href)"
                } else if (text.isNotBlank()) {
                    text
                } else {
                    ""
                }
            }

            // 强调
            "strong", "b" -> "**${inlineText(el)}**"
            "em", "i" -> "*${inlineText(el)}*"
            "del", "s", "strike" -> "~~${inlineText(el)}~~"

            // 代码
            "code" -> {
                if (el.parent()?.tagName() == "pre") {
                    // 由 <pre> 的 case 处理
                    el.wholeText()
                } else {
                    "`${el.text()}`"
                }
            }
            "pre" -> {
                val codeEl = el.selectFirst("code")
                val code = codeEl?.wholeText() ?: el.wholeText()
                val lang = codeEl?.className()
                    ?.replace("language-", "")
                    ?.replace("lang-", "")
                    ?.takeIf { it.isNotBlank() && !it.contains(" ") }
                    ?: ""
                "\n\n```$lang\n${code.trimEnd()}\n```\n\n"
            }

            // 列表
            "ul" -> "\n\n${convertList(el, ordered = false, indent = depth)}\n\n"
            "ol" -> "\n\n${convertList(el, ordered = true, indent = depth)}\n\n"

            // 引用块
            "blockquote" -> {
                val content = convertElement(el, depth).trim()
                val quoted = content.lines().joinToString("\n") { "> $it" }
                "\n\n$quoted\n\n"
            }

            // 图片
            "img" -> {
                val alt = el.attr("alt")
                val src = el.absUrl("src").ifEmpty { el.attr("src") }
                if (src.isNotBlank()) "![${alt}]($src)" else ""
            }

            // 水平分割线
            "hr" -> "\n\n---\n\n"

            // 换行
            "br" -> "\n"

            // 表格
            "table" -> "\n\n${convertTable(el)}\n\n"

            // 定义列表
            "dl" -> "\n\n${convertDefinitionList(el)}\n\n"

            // 图表
            "figure" -> "\n\n${convertElement(el, depth)}\n\n"
            "figcaption" -> "\n*${inlineText(el)}*\n"

            // 其他块级元素
            in BLOCK_TAGS -> "\n\n${convertElement(el, depth)}\n\n"

            // 未知/行内元素 -- 递归处理子节点
            else -> convertElement(el, depth)
        }
    }

    /**
     * 将 <ul> 或 <ol> 转换为带正确缩进的 Markdown 列表项。
     */
    private fun convertList(
        listEl: Element,
        ordered: Boolean,
        indent: Int
    ): String {
        val sb = StringBuilder()
        val prefix = "  ".repeat(indent)
        var index = 1

        for (li in listEl.children()) {
            if (li.tagName().lowercase() != "li") continue

            val bullet = if (ordered) "${index}. " else "- "
            val content = StringBuilder()

            for (child in li.childNodes()) {
                when (child) {
                    is TextNode -> {
                        val text = child.text().trim()
                        if (text.isNotBlank()) content.append(text)
                    }
                    is Element -> {
                        if (child.tagName().lowercase() in listOf("ul", "ol")) {
                            // 嵌套列表
                            content.append("\n")
                            content.append(convertList(
                                child,
                                ordered = child.tagName().lowercase() == "ol",
                                indent = indent + 1
                            ))
                        } else {
                            content.append(inlineText(child))
                        }
                    }
                }
            }

            sb.append("$prefix$bullet${content.toString().trim()}\n")
            index++
        }

        return sb.toString().trimEnd()
    }

    /**
     * 将 <table> 转换为 Markdown 表格格式。
     */
    private fun convertTable(table: Element): String {
        val rows = mutableListOf<List<String>>()

        // 收集 thead、tbody、tfoot 中的所有行
        for (row in table.select("tr")) {
            val cells = row.select("th, td").map { inlineText(it).trim() }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (rows.isEmpty()) return ""

        // 确定列数
        val colCount = rows.maxOf { it.size }

        // 将各行填充至相同列数
        val paddedRows = rows.map { row ->
            row + List(colCount - row.size) { "" }
        }

        val sb = StringBuilder()

        // 表头行
        sb.append("| ${paddedRows[0].joinToString(" | ")} |\n")
        // 分隔行
        sb.append("| ${paddedRows[0].map { "---" }.joinToString(" | ")} |\n")
        // 数据行
        for (i in 1 until paddedRows.size) {
            sb.append("| ${paddedRows[i].joinToString(" | ")} |\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * 将 <dl> 转换为 Markdown 格式。
     */
    private fun convertDefinitionList(dl: Element): String {
        val sb = StringBuilder()
        for (child in dl.children()) {
            when (child.tagName().lowercase()) {
                "dt" -> sb.append("**${inlineText(child)}**\n")
                "dd" -> sb.append(": ${inlineText(child)}\n\n")
            }
        }
        return sb.toString().trimEnd()
    }

    /**
     * 从元素中提取行内文本，去除所有 HTML 标签。
     * 保留子元素中的行内 Markdown 格式。
     */
    private fun inlineText(el: Element): String {
        val sb = StringBuilder()
        for (node in el.childNodes()) {
            when (node) {
                is TextNode -> sb.append(node.text())
                is Element -> {
                    when (node.tagName().lowercase()) {
                        "strong", "b" -> sb.append("**${inlineText(node)}**")
                        "em", "i" -> sb.append("*${inlineText(node)}*")
                        "code" -> sb.append("`${node.text()}`")
                        "a" -> {
                            val href = node.absUrl("href").ifEmpty { node.attr("href") }
                            val text = inlineText(node)
                            if (text.isNotBlank() && href.isNotBlank()) {
                                sb.append("[$text]($href)")
                            } else {
                                sb.append(text)
                            }
                        }
                        "br" -> sb.append("\n")
                        "img" -> {
                            val alt = node.attr("alt")
                            val src = node.absUrl("src").ifEmpty { node.attr("src") }
                            if (src.isNotBlank()) sb.append("![${alt}]($src)")
                        }
                        else -> sb.append(inlineText(node))
                    }
                }
            }
        }
        return sb.toString()
    }

    /**
     * 清理最终 Markdown 输出中的空白字符。
     */
    private fun cleanupWhitespace(markdown: String): String {
        return markdown
            .replace(Regex("\n{3,}"), "\n\n")  // 合并多个空行
            .replace(Regex("[ \t]+\n"), "\n")  // 行尾空白
            .replace(Regex("\n[ \t]+\n"), "\n\n")  // 仅含空白字符的行
            .trim()
    }
}
```

### Jsoup 依赖

在 `app/build.gradle.kts` 中添加：

```kotlin
dependencies {
    // ... 现有依赖 ...
    implementation("org.jsoup:jsoup:1.18.3")
}
```

Jsoup 的特点：
- 体积约 400KB
- Apache 2.0 许可证
- 零传递依赖
- 兼容 Android API 21+
- 在 Android 项目中广泛使用

### ToolModule 变更

```kotlin
// 在 ToolModule.kt 中添加 WebfetchTool 注册

val toolModule = module {
    // ... 现有工具注册 ...

    // WebfetchTool（替代 JS 版 webfetch）
    single { WebfetchTool(get()) }

    single { ToolRegistry() } bind ToolRegistry::class

    // 在 ToolRegistry 初始化时注册 WebfetchTool：
    single {
        val registry = get<ToolRegistry>()
        // ... 现有工具注册 ...
        registry.register(get<WebfetchTool>())
        // ... 其余初始化逻辑 ...
        registry
    }
}
```

### JS 工具删除

从 `assets/js/tools/` 中删除以下文件：
- `webfetch.js` -- 基于正则表达式的 JS 实现
- `webfetch.json` -- JS 工具定义

`JsToolLoader` 将不再找到 JS 版 `webfetch` 工具，Kotlin `WebfetchTool` 将直接在 `ToolModule` 中注册。

## 实施计划

### 第一阶段：HtmlToMarkdownConverter（核心逻辑）

1. 在 `build.gradle.kts` 中添加 Jsoup 依赖
2. 在 `tool/util/` 中创建 `HtmlToMarkdownConverter.kt`
3. 创建 `HtmlToMarkdownConverterTest.kt`，包含全面的测试用例
4. 对比当前基于正则表达式的实现，验证转换质量

### 第二阶段：WebfetchTool（工具集成）

1. 在 `tool/builtin/` 中创建 `WebfetchTool.kt`
2. 创建 `WebfetchToolTest.kt`
3. 更新 `ToolModule.kt`，注册 `WebfetchTool`
4. 从 assets 中删除 `webfetch.js` 和 `webfetch.json`

### 第三阶段：测试与验证

1. 运行 Layer 1A 测试（`./gradlew test`）
2. 如有模拟器则运行 Layer 1B 测试
3. 使用各种真实 URL 进行手动测试
4. 对比 JS 实现，评估输出质量

## 数据模型

无数据模型变更。`WebfetchTool` 实现现有的 `Tool` 接口。

## API 设计

### 工具接口

```
工具名称：webfetch
参数：
  - url：string（必填）-- 要抓取的 URL
  - max_length：integer（可选，默认值：50000）-- 最大输出长度

成功时返回：
  页面内容的 Markdown 字符串

错误时返回：
  带描述性信息的 ToolResult.error
```

### HtmlToMarkdownConverter 公共 API

```kotlin
object HtmlToMarkdownConverter {
    fun convert(html: String, baseUrl: String? = null): String
}
```

## 迁移策略

本次迁移为直接替换：

1. `WebfetchTool` 作为 Kotlin 内置工具注册到 `ToolModule`
2. JS 版 `webfetch.js` 和 `webfetch.json` 从 assets 中删除
3. `JsToolLoader` 不再加载 JS 版 webfetch 工具
4. 工具名称 `webfetch` 和参数 `url` 保持不变
5. AI 模型和用户不会感知到行为变化（输出格式相同：Markdown）

唯一可见的差异是复杂 HTML 页面的 Markdown 质量得到提升。

## 错误处理

| 错误 | 原因 | 处理方式 |
|------|------|----------|
| 无效 URL | URL 格式错误或非 HTTP scheme | `ToolResult.error("Invalid URL: ...")` |
| DNS 解析失败 | 未知主机 | `ToolResult.error("DNS resolution failed: ...")` |
| 连接超时 | 服务器不可达或响应缓慢 | `ToolResult.error("Request timed out: ...")` |
| HTTP 4xx/5xx | 服务器错误 | `ToolResult.error("HTTP {code}: {message}")` |
| 响应体过大 | 页面超过 5MB | 解析前对 HTML 进行截断 |
| 响应体为空 | 响应中无正文 | `ToolResult.error("Empty response body")` |
| 解析失败 | Jsoup 无法解析 | Jsoup 会优雅地处理格式不规范的 HTML；返回尽力而为的输出 |

## 安全考量

1. **URL scheme 验证**：仅接受 HTTP 和 HTTPS。拒绝 `file://`、`content://`、`javascript:` scheme。
2. **响应大小限制**：超过 5MB 的响应在解析前截断，以防止内存溢出。
3. **不转发凭证**：不发送任何 Cookie、会话或认证令牌。
4. **User-Agent 伪装**：设置标准移动浏览器 User-Agent 以避免被反爬虫拦截，此行为透明且不具欺骗性。
5. **Jsoup 安全性**：Jsoup 的解析器对恶意 HTML 是安全的（不执行脚本，不解析外部实体）。
6. **输出为 Markdown**：输出是纯文本（Markdown），而非 HTML，因此不存在 XSS 风险。

## 性能

| 操作 | 预期耗时 | 备注 |
|------|---------|------|
| 网络抓取 | 不定 | 取决于服务器和网络 |
| Jsoup 解析（500KB HTML） | ~50ms | 单线程 DOM 构建 |
| Markdown 转换 | ~30ms | DOM 遍历，字符串拼接 |
| 总计（不含网络） | < 100ms | 远低于 30 秒的工具超时限制 |

内存使用：
- Jsoup DOM：约为 HTML 大小的 3-5 倍（临时）
- Markdown 输出：通常小于源 HTML
- 工具调用返回后，所有对象均被垃圾回收

## 测试策略

### 单元测试

**HtmlToMarkdownConverterTest.kt：**
- `testConvertSimpleHtml` -- 含段落和标题的基础 HTML
- `testConvertLinks` -- 绝对链接和相对链接
- `testConvertNestedLists` -- 嵌套无序列表和有序列表
- `testConvertTables` -- 含表头和数据行的表格
- `testConvertCodeBlocks` -- 行内代码和围栏代码块
- `testConvertBlockquotes` -- 单层和嵌套引用块
- `testNoiseRemoval` -- 移除 script、style、nav 等元素
- `testContentExtraction_article` -- 优先使用 `<article>` 内容
- `testContentExtraction_main` -- 回退到 `<main>`
- `testContentExtraction_body` -- 回退到 `<body>`
- `testTitlePrepend` -- 标题不在内容中时追加到开头
- `testEmptyHtml` -- 空或极简 HTML
- `testMalformedHtml` -- 未闭合标签、无效嵌套
- `testWhitespaceCleanup` -- 多个空行合并

**WebfetchToolTest.kt：**
- `testExecute_success_html` -- 成功抓取 HTML 并转换
- `testExecute_success_nonHtml` -- 非 HTML 内容原样返回
- `testExecute_httpError` -- HTTP 错误响应
- `testExecute_networkError` -- 网络故障
- `testExecute_invalidUrl` -- 无效 URL 参数
- `testExecute_missingUrl` -- 缺少必填参数
- `testExecute_truncation` -- 输出在 max_length 处截断
- `testExecute_customMaxLength` -- 自定义 max_length 参数
- `testDefinition` -- 工具定义包含正确的名称和参数

### 集成测试

使用真实 URL 进行手动验证：
- 简单博客文章/资讯页面
- 含代码块和表格的文档页面
- 含嵌套列表和复杂结构的页面
- 非英文页面（CJK 字符）
- 含大量内容的页面（截断测试）

## 备选方案

### 1. 保留 JS webfetch，在 WebView 中使用 Turndown

**方案**：在 WebView 中加载 Turndown，而非在 QuickJS 中，以使用真实的 DOM API。
**被拒绝的原因**：WebView 较为重量级，需要 Android context 和主线程协调，对于简单的转换任务引入了不必要的复杂性。Jsoup 是专为此场景设计的。

### 2. 使用 Mozilla Readability 移植版

**方案**：移植或使用 Mozilla Readability 算法的 Java 版本进行内容提取。
**被拒绝的原因**：完整的 Readability 算法较为复杂（评分、候选项筛选等），对于 V1 版本过于繁重。简单的 article/main/body 优先级策略对大多数页面已足够适用，后续可按需添加。

### 3. 使用其他 HTML 解析器（TagSoup、HtmlCleaner）

**方案**：使用 Jsoup 的替代品。
**被拒绝的原因**：Jsoup 是 Java/Android HTML 解析的事实标准，持续维护、文档完善、零依赖，是最受欢迎的选择。

## 依赖

### 外部依赖

| 依赖 | 版本 | 大小 | 许可证 |
|------|------|------|--------|
| org.jsoup:jsoup | 1.18.3 | ~400KB | Apache 2.0 |

### 内部依赖

- `tool/` 包中的 `Tool` 接口
- `tool/` 包中的 `ToolResult`、`ToolDefinition`、`ToolParameters`
- 网络模块中的 `OkHttpClient`（已通过 Koin 提供）

## 变更历史

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|---------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
