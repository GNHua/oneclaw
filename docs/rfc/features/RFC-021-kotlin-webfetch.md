# RFC-021: Kotlin Webfetch Tool

## Document Information
- **RFC ID**: RFC-021
- **Related PRD**: [FEAT-021 (Kotlin Webfetch Tool)](../../prd/features/FEAT-021-kotlin-webfetch.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-015 (JS Tool Migration)](RFC-015-js-tool-migration.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

RFC-015 introduced a JavaScript-based `webfetch` tool that fetches web pages and converts HTML to Markdown. The original design intended to use the Turndown library for DOM-based HTML-to-Markdown conversion. However, Turndown requires DOM APIs (`document.createElement`, `DOMParser`, etc.) that are unavailable in the QuickJS JavaScript runtime. The current implementation falls back to regex-based conversion, which has fundamental limitations:

- Cannot handle nested structures (lists within lists, tables within blockquotes)
- Fragile content extraction using single-match regex for `<main>`/`<article>` tags
- Fails on malformed HTML (unclosed tags, overlapping elements)
- Cannot properly handle character encoding edge cases

RFC-021 replaces the JS `webfetch` tool with a Kotlin-native implementation using Jsoup, a battle-tested Java HTML parser that provides a full DOM API. This gives us proper HTML parsing, robust content extraction, and accurate Markdown conversion without requiring DOM APIs in the JS runtime.

### Goals

1. Implement `WebfetchTool.kt` as a Kotlin built-in tool in `tool/builtin/`
2. Implement `HtmlToMarkdownConverter` utility class for DOM-based HTML-to-Markdown conversion
3. Add Jsoup dependency to the project
4. Remove JS `webfetch.js` and `webfetch.json` from `assets/js/tools/`
5. Update `ToolModule` to register the new Kotlin `WebfetchTool`
6. Add output truncation with configurable character limit

### Non-Goals

- Implementing full Mozilla Readability scoring algorithm
- JavaScript rendering for dynamic/SPA pages (deferred to RFC-022)
- Response caching
- PDF or binary content extraction
- Cookie/session management

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     Chat Layer (RFC-001)                      │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  tool call: webfetch(url, max_length?)               │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   Tool Execution Engine (RFC-004)             │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐                                  │  │
│  │  │    webfetch       │  Kotlin built-in [NEW]          │  │
│  │  │ (WebfetchTool.kt) │                                 │  │
│  │  └───────┬──────────┘                                  │  │
│  │          │                                              │  │
│  │          v                                              │  │
│  │  ┌──────────────────────────────────────────────────┐  │  │
│  │  │           WebfetchTool                            │  │  │
│  │  │  1. Validate URL                                  │  │  │
│  │  │  2. Fetch HTML via OkHttpClient                   │  │  │
│  │  │  3. Parse with Jsoup                              │  │  │
│  │  │  4. Extract main content                          │  │  │
│  │  │  5. Convert to Markdown (HtmlToMarkdownConverter) │  │  │
│  │  │  6. Truncate if needed                            │  │  │
│  │  └──────────────────────────────────────────────────┘  │  │
│  └────────────────────────────────────────────────────────┘  │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

**New:**
1. `WebfetchTool` -- Kotlin built-in tool that fetches web pages and returns Markdown
2. `HtmlToMarkdownConverter` -- Utility class for DOM-based HTML-to-Markdown conversion

**Modified:**
3. `ToolModule` -- Register `WebfetchTool` as a Kotlin built-in tool

**Removed:**
4. `assets/js/tools/webfetch.js` -- JS implementation
5. `assets/js/tools/webfetch.json` -- JS tool definition

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── WebfetchTool.kt             # NEW
│   │   │   ├── LoadSkillTool.kt            # unchanged
│   │   │   ├── CreateScheduledTaskTool.kt  # unchanged
│   │   │   └── CreateAgentTool.kt          # unchanged
│   │   └── util/
│   │       └── HtmlToMarkdownConverter.kt  # NEW
│   └── di/
│       └── ToolModule.kt                   # MODIFIED
├── assets/
│   └── js/
│       └── tools/
│           ├── webfetch.js                 # DELETED
│           ├── webfetch.json               # DELETED
│           ├── get_current_time.js          # unchanged
│           └── ...                          # other JS tools unchanged

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   └── WebfetchToolTest.kt          # NEW
        └── util/
            └── HtmlToMarkdownConverterTest.kt # NEW
```

### WebfetchTool

```kotlin
/**
 * Located in: tool/builtin/WebfetchTool.kt
 *
 * Kotlin-native webfetch tool that fetches web pages and converts
 * HTML to Markdown using Jsoup. Replaces the JS webfetch implementation.
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

        // Validate URL scheme
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
            // Limit response size to prevent OOM
            val source = responseBody.source()
            source.request(MAX_RESPONSE_SIZE.toLong())
            val buffer = source.buffer
            if (buffer.size > MAX_RESPONSE_SIZE) {
                buffer.snapshot(MAX_RESPONSE_SIZE.toLong()).utf8()
            } else {
                responseBody.string()
            }
        } ?: return ToolResult.error("Empty response body")

        // Non-HTML: return raw body (truncated)
        if (!contentType.contains("text/html") && !contentType.contains("application/xhtml")) {
            return ToolResult.success(truncateText(body, maxLength))
        }

        // HTML: parse and convert to Markdown
        val markdown = HtmlToMarkdownConverter.convert(body, response.request.url.toString())
        return ToolResult.success(truncateText(markdown, maxLength))
    }

    private fun truncateText(text: String, maxLength: Int): String {
        if (maxLength <= 0 || text.length <= maxLength) return text

        // Find the last paragraph/block boundary before the limit
        val truncateAt = text.lastIndexOf("\n\n", maxLength)
        val cutoff = if (truncateAt > maxLength / 2) truncateAt else maxLength

        return text.substring(0, cutoff) + "\n\n[Content truncated at $maxLength characters]"
    }
}
```

### HtmlToMarkdownConverter

```kotlin
/**
 * Located in: tool/util/HtmlToMarkdownConverter.kt
 *
 * Converts HTML to Markdown using Jsoup DOM traversal.
 * Handles content extraction (article/main detection),
 * noise removal, and element-by-element Markdown rendering.
 */
object HtmlToMarkdownConverter {

    // Elements to remove entirely (content and tag)
    private val NOISE_TAGS = setOf(
        "script", "style", "nav", "header", "footer", "aside",
        "noscript", "svg", "iframe", "form", "button", "input",
        "select", "textarea"
    )

    // Block elements that produce paragraph breaks
    private val BLOCK_TAGS = setOf(
        "p", "div", "section", "article", "main", "figure",
        "figcaption", "details", "summary", "address"
    )

    /**
     * Convert HTML string to Markdown.
     *
     * @param html The raw HTML string
     * @param baseUrl Optional base URL for resolving relative links
     * @return Markdown string
     */
    fun convert(html: String, baseUrl: String? = null): String {
        val doc = if (baseUrl != null) {
            Jsoup.parse(html, baseUrl)
        } else {
            Jsoup.parse(html)
        }

        // Extract title
        val title = doc.title().takeIf { it.isNotBlank() }

        // Remove noise elements
        NOISE_TAGS.forEach { tag ->
            doc.select(tag).remove()
        }

        // Find main content area
        val contentElement = findMainContent(doc)

        // Convert to Markdown
        val markdown = convertElement(contentElement, depth = 0)

        // Clean up whitespace
        val cleaned = cleanupWhitespace(markdown)

        // Prepend title if not already present in content
        return if (title != null && !cleaned.startsWith("# ") && title !in cleaned.take(200)) {
            "# $title\n\n$cleaned"
        } else {
            cleaned
        }
    }

    /**
     * Find the main content element using a priority-based strategy.
     * article > main > [role="main"] > body
     */
    private fun findMainContent(doc: Document): Element {
        // Try <article> first -- most specific content marker
        doc.selectFirst("article")?.let { return it }

        // Try <main>
        doc.selectFirst("main")?.let { return it }

        // Try role="main"
        doc.selectFirst("[role=main]")?.let { return it }

        // Fallback to <body>
        return doc.body() ?: doc
    }

    /**
     * Recursively convert a Jsoup Element to Markdown.
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
     * Convert a specific HTML tag to its Markdown equivalent.
     */
    private fun convertTag(el: Element, depth: Int): String {
        val tag = el.tagName().lowercase()

        return when (tag) {
            // Headings
            "h1" -> "\n\n# ${inlineText(el)}\n\n"
            "h2" -> "\n\n## ${inlineText(el)}\n\n"
            "h3" -> "\n\n### ${inlineText(el)}\n\n"
            "h4" -> "\n\n#### ${inlineText(el)}\n\n"
            "h5" -> "\n\n##### ${inlineText(el)}\n\n"
            "h6" -> "\n\n###### ${inlineText(el)}\n\n"

            // Paragraphs and block elements
            "p" -> "\n\n${convertElement(el, depth)}\n\n"
            "div", "section", "article", "main" -> "\n\n${convertElement(el, depth)}\n\n"

            // Links
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

            // Emphasis
            "strong", "b" -> "**${inlineText(el)}**"
            "em", "i" -> "*${inlineText(el)}*"
            "del", "s", "strike" -> "~~${inlineText(el)}~~"

            // Code
            "code" -> {
                if (el.parent()?.tagName() == "pre") {
                    // Handled by <pre> case
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

            // Lists
            "ul" -> "\n\n${convertList(el, ordered = false, indent = depth)}\n\n"
            "ol" -> "\n\n${convertList(el, ordered = true, indent = depth)}\n\n"

            // Blockquote
            "blockquote" -> {
                val content = convertElement(el, depth).trim()
                val quoted = content.lines().joinToString("\n") { "> $it" }
                "\n\n$quoted\n\n"
            }

            // Images
            "img" -> {
                val alt = el.attr("alt")
                val src = el.absUrl("src").ifEmpty { el.attr("src") }
                if (src.isNotBlank()) "![${alt}]($src)" else ""
            }

            // Horizontal rule
            "hr" -> "\n\n---\n\n"

            // Line break
            "br" -> "\n"

            // Tables
            "table" -> "\n\n${convertTable(el)}\n\n"

            // Definition lists
            "dl" -> "\n\n${convertDefinitionList(el)}\n\n"

            // Figure
            "figure" -> "\n\n${convertElement(el, depth)}\n\n"
            "figcaption" -> "\n*${inlineText(el)}*\n"

            // Other block elements
            in BLOCK_TAGS -> "\n\n${convertElement(el, depth)}\n\n"

            // Unknown/inline elements -- recurse into children
            else -> convertElement(el, depth)
        }
    }

    /**
     * Convert a <ul> or <ol> to Markdown list items with proper nesting.
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
                            // Nested list
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
     * Convert a <table> to Markdown table format.
     */
    private fun convertTable(table: Element): String {
        val rows = mutableListOf<List<String>>()

        // Collect all rows from thead, tbody, tfoot
        for (row in table.select("tr")) {
            val cells = row.select("th, td").map { inlineText(it).trim() }
            if (cells.isNotEmpty()) {
                rows.add(cells)
            }
        }

        if (rows.isEmpty()) return ""

        // Determine column count
        val colCount = rows.maxOf { it.size }

        // Pad rows to equal column count
        val paddedRows = rows.map { row ->
            row + List(colCount - row.size) { "" }
        }

        val sb = StringBuilder()

        // Header row
        sb.append("| ${paddedRows[0].joinToString(" | ")} |\n")
        // Separator
        sb.append("| ${paddedRows[0].map { "---" }.joinToString(" | ")} |\n")
        // Data rows
        for (i in 1 until paddedRows.size) {
            sb.append("| ${paddedRows[i].joinToString(" | ")} |\n")
        }

        return sb.toString().trimEnd()
    }

    /**
     * Convert a <dl> to Markdown format.
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
     * Extract inline text from an element, stripping all HTML tags.
     * Preserves inline Markdown formatting from child elements.
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
     * Clean up whitespace in the final Markdown output.
     */
    private fun cleanupWhitespace(markdown: String): String {
        return markdown
            .replace(Regex("\n{3,}"), "\n\n")  // Collapse multiple blank lines
            .replace(Regex("[ \t]+\n"), "\n")  // Trailing whitespace
            .replace(Regex("\n[ \t]+\n"), "\n\n")  // Lines with only whitespace
            .trim()
    }
}
```

### Jsoup Dependency

Add to `app/build.gradle.kts`:

```kotlin
dependencies {
    // ... existing dependencies ...
    implementation("org.jsoup:jsoup:1.18.3")
}
```

Jsoup is:
- ~400KB in size
- Apache 2.0 licensed
- Has zero transitive dependencies
- Compatible with Android API 21+
- Widely used in Android projects

### ToolModule Changes

```kotlin
// In ToolModule.kt, add WebfetchTool registration

val toolModule = module {
    // ... existing tool registrations ...

    // WebfetchTool (replaces JS webfetch)
    single { WebfetchTool(get()) }

    single { ToolRegistry() } bind ToolRegistry::class

    // In the ToolRegistry initialization, register WebfetchTool:
    single {
        val registry = get<ToolRegistry>()
        // ... existing tool registrations ...
        registry.register(get<WebfetchTool>())
        // ... rest of initialization ...
        registry
    }
}
```

### JS Tool Removal

Remove the following files from `assets/js/tools/`:
- `webfetch.js` -- The regex-based JS implementation
- `webfetch.json` -- The JS tool definition

The `JsToolLoader` will no longer find a `webfetch` JS tool, and the Kotlin `WebfetchTool` will be registered directly in `ToolModule`.

## Implementation Plan

### Phase 1: HtmlToMarkdownConverter (Core Logic)

1. Add Jsoup dependency to `build.gradle.kts`
2. Create `HtmlToMarkdownConverter.kt` in `tool/util/`
3. Create `HtmlToMarkdownConverterTest.kt` with comprehensive test cases
4. Verify conversion quality against the current regex-based implementation

### Phase 2: WebfetchTool (Tool Integration)

1. Create `WebfetchTool.kt` in `tool/builtin/`
2. Create `WebfetchToolTest.kt`
3. Update `ToolModule.kt` to register `WebfetchTool`
4. Remove `webfetch.js` and `webfetch.json` from assets

### Phase 3: Testing & Verification

1. Run Layer 1A tests (`./gradlew test`)
2. Run Layer 1B tests if emulator available
3. Manual testing with various real-world URLs
4. Compare output quality against the JS implementation

## Data Model

No data model changes. `WebfetchTool` implements the existing `Tool` interface.

## API Design

### Tool Interface

```
Tool Name: webfetch
Parameters:
  - url: string (required) -- The URL to fetch
  - max_length: integer (optional, default: 50000) -- Maximum output length

Returns on success:
  Markdown string of the page content

Returns on error:
  ToolResult.error with descriptive message
```

### HtmlToMarkdownConverter Public API

```kotlin
object HtmlToMarkdownConverter {
    fun convert(html: String, baseUrl: String? = null): String
}
```

## Migration Strategy

The migration is a direct replacement:

1. `WebfetchTool` is registered in `ToolModule` as a Kotlin built-in
2. JS `webfetch.js` and `webfetch.json` are deleted from assets
3. The `JsToolLoader` no longer loads a JS webfetch tool
4. The tool name `webfetch` and parameter `url` remain identical
5. AI models and users see no behavioral change (output format is the same: Markdown)

The only visible difference is improved Markdown quality for complex HTML pages.

## Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| Invalid URL | Malformed URL or non-HTTP scheme | `ToolResult.error("Invalid URL: ...")` |
| DNS failure | Unknown host | `ToolResult.error("DNS resolution failed: ...")` |
| Connection timeout | Server unreachable or slow | `ToolResult.error("Request timed out: ...")` |
| HTTP 4xx/5xx | Server error | `ToolResult.error("HTTP {code}: {message}")` |
| Response too large | Page exceeds 5MB | HTML truncated before parsing |
| Empty response | No body in response | `ToolResult.error("Empty response body")` |
| Parse failure | Jsoup cannot parse | Jsoup handles malformed HTML gracefully; returns best-effort output |

## Security Considerations

1. **URL scheme validation**: Only HTTP and HTTPS are accepted. `file://`, `content://`, `javascript:` schemes are rejected.
2. **Response size limit**: Responses larger than 5MB are truncated before parsing to prevent OOM.
3. **No credential forwarding**: No cookies, sessions, or authentication tokens are sent.
4. **User-Agent spoofing**: A standard mobile browser User-Agent is set to avoid bot-blocking, which is transparent and not deceptive.
5. **Jsoup safety**: Jsoup's parser is safe against malicious HTML (no script execution, no external entity resolution).
6. **Output is Markdown**: The output is plain text (Markdown), not HTML, so XSS concerns do not apply.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| Network fetch | Variable | Depends on server/network |
| Jsoup parse (500KB HTML) | ~50ms | Single-threaded DOM construction |
| Markdown conversion | ~30ms | DOM traversal, string building |
| Total (excluding network) | < 100ms | Well within 30s tool timeout |

Memory usage:
- Jsoup DOM: ~3-5x the HTML size in memory (temporary)
- Markdown output: typically smaller than source HTML
- All objects are garbage collected after the tool call returns

## Testing Strategy

### Unit Tests

**HtmlToMarkdownConverterTest.kt:**
- `testConvertSimpleHtml` -- Basic HTML with paragraphs and headings
- `testConvertLinks` -- Absolute and relative links
- `testConvertNestedLists` -- Nested unordered and ordered lists
- `testConvertTables` -- Tables with headers and data rows
- `testConvertCodeBlocks` -- Inline code and fenced code blocks
- `testConvertBlockquotes` -- Single and nested blockquotes
- `testNoiseRemoval` -- Script, style, nav elements removed
- `testContentExtraction_article` -- Prefers `<article>` content
- `testContentExtraction_main` -- Falls back to `<main>`
- `testContentExtraction_body` -- Falls back to `<body>`
- `testTitlePrepend` -- Title added when not in content
- `testEmptyHtml` -- Empty or minimal HTML
- `testMalformedHtml` -- Unclosed tags, invalid nesting
- `testWhitespaceCleanup` -- Multiple blank lines collapsed

**WebfetchToolTest.kt:**
- `testExecute_success_html` -- Successful HTML fetch and conversion
- `testExecute_success_nonHtml` -- Non-HTML content returned as-is
- `testExecute_httpError` -- HTTP error response
- `testExecute_networkError` -- Network failure
- `testExecute_invalidUrl` -- Invalid URL parameter
- `testExecute_missingUrl` -- Missing required parameter
- `testExecute_truncation` -- Output truncated at max_length
- `testExecute_customMaxLength` -- Custom max_length parameter
- `testDefinition` -- Tool definition has correct name and parameters

### Integration Tests

Manual verification with real URLs:
- Simple blog post / article page
- Documentation page with code blocks and tables
- Page with nested lists and complex structure
- Non-English page (CJK characters)
- Page with large amount of content (truncation test)

## Alternatives Considered

### 1. Keep JS webfetch with Turndown in WebView

**Approach**: Load Turndown in a WebView instead of QuickJS, using real DOM APIs.
**Rejected because**: WebView is heavyweight, requires Android context and main thread coordination, and introduces unnecessary complexity for a simple conversion task. Jsoup is purpose-built for this.

### 2. Use Mozilla Readability port

**Approach**: Port or use a Java version of Mozilla's Readability algorithm for content extraction.
**Rejected because**: Full Readability is complex (scoring, candidate selection, etc.) and overkill for V1. The simple article/main/body priority works well for most pages. Can be added later.

### 3. Use a different HTML parser (TagSoup, HtmlCleaner)

**Approach**: Use an alternative to Jsoup.
**Rejected because**: Jsoup is the de facto standard for Java/Android HTML parsing. It's actively maintained, well-documented, has zero dependencies, and is the most popular choice.

## Dependencies

### External Dependencies

| Dependency | Version | Size | License |
|------------|---------|------|---------|
| org.jsoup:jsoup | 1.18.3 | ~400KB | Apache 2.0 |

### Internal Dependencies

- `Tool` interface from `tool/` package
- `ToolResult`, `ToolDefinition`, `ToolParameters` from `tool/` package
- `OkHttpClient` from network module (already available via Koin)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
