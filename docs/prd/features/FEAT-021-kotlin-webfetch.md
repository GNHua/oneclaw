# Kotlin Webfetch Tool

## Feature Information
- **Feature ID**: FEAT-021
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Priority**: P1 (Should Have)
- **Owner**: TBD
- **Related RFC**: RFC-021 (pending)

## User Story

**As** an AI agent using OneClawShadow,
**I want** a reliable webfetch tool that accurately converts HTML pages to Markdown using a proper HTML parser,
**so that** I can extract and summarize web content without the limitations of regex-based HTML-to-Markdown conversion.

### Typical Scenarios

1. The agent calls `webfetch` with a URL. The tool fetches the HTML, parses it with Jsoup (a real HTML parser), extracts the main content area, converts it to Markdown, and returns clean, readable text.
2. The agent fetches a documentation page. Jsoup's DOM-based parsing correctly handles nested elements, malformed HTML, and complex table structures that regex-based conversion would mangle.
3. The agent fetches a large page (e.g., an API reference). The tool automatically truncates the output to a configurable character limit, preventing context window overflow.
4. The agent fetches a non-HTML resource (JSON, plain text). The tool returns the raw body without attempting Markdown conversion.

## Feature Description

### Overview

FEAT-021 replaces the current JavaScript-based `webfetch` tool (implemented in `assets/js/tools/webfetch.js`) with a Kotlin-native implementation using Jsoup for HTML parsing and Markdown conversion. The current JS implementation uses regex-based HTML-to-Markdown conversion, which is fragile and cannot handle nested structures, malformed HTML, or complex layouts reliably.

The new Kotlin implementation provides:
- **DOM-based HTML parsing** via Jsoup -- correctly handles real-world HTML
- **Content extraction** -- Readability-style main content detection (article/main/body fallback)
- **Proper Markdown conversion** -- Handles nested lists, tables, code blocks, and inline formatting via DOM traversal
- **Output size control** -- Configurable character limit with clean truncation
- **Same interface** -- Same tool name, same parameters, transparent replacement

### Architecture Overview

```
AI Model
    | tool call: webfetch(url="...")
    v
ToolExecutionEngine  (Kotlin, unchanged)
    |
    v
ToolRegistry
    |
    v
WebfetchTool  [NEW - Kotlin built-in tool]
    |
    +-- OkHttpClient (fetch HTML)
    |
    +-- Jsoup (parse HTML)
    |
    +-- HtmlToMarkdownConverter [NEW - utility class]
            |
            +-- Content extraction (article/main detection)
            +-- DOM-to-Markdown traversal
            +-- Output truncation
```

### Why Replace JS with Kotlin?

The current JS `webfetch` (`assets/js/tools/webfetch.js`) uses regex-based HTML-to-Markdown conversion. This approach has fundamental limitations:

1. **No DOM awareness**: Regex cannot parse nested HTML structures. Nested lists, tables within tables, and overlapping tags produce incorrect output.
2. **Fragile content extraction**: The JS implementation uses a single regex to find `<main>` or `<article>` tags, which fails on pages with multiple such elements or deeply nested content areas.
3. **No Turndown in QuickJS**: The original FEAT-015 design planned to use Turndown (a proper DOM-based converter), but Turndown requires DOM APIs (`document.createElement`, etc.) that QuickJS does not provide. The regex fallback was a workaround.
4. **Jsoup is battle-tested**: Jsoup is the standard Java/Kotlin HTML parser, handles malformed HTML gracefully, and provides a full DOM API for precise content extraction.

### Content Extraction Strategy

The tool uses a Readability-inspired approach to find the main content:

1. **Priority order**: `<article>` > `<main>` > `<div role="main">` > `<body>`
2. **Noise removal**: Strip `<script>`, `<style>`, `<nav>`, `<header>`, `<footer>`, `<aside>`, `<noscript>`, `<svg>`, `<iframe>`, `<form>` elements before conversion
3. **Title extraction**: Extract `<title>` and prepend as `# Title` if not already present in content

### Markdown Conversion

DOM-based traversal converts HTML elements to Markdown:

| HTML Element | Markdown Output |
|---|---|
| `<h1>` - `<h6>` | `#` - `######` |
| `<p>` | Paragraph with double newline |
| `<a href="...">` | `[text](url)` |
| `<strong>`, `<b>` | `**text**` |
| `<em>`, `<i>` | `*text*` |
| `<code>` | `` `text` `` |
| `<pre><code>` | Fenced code block |
| `<ul>/<li>` | `- item` (nested supported) |
| `<ol>/<li>` | `1. item` (nested supported) |
| `<blockquote>` | `> text` |
| `<img>` | `![alt](src)` |
| `<hr>` | `---` |
| `<br>` | Newline |
| `<table>` | Markdown table with header separator |

### Output Size Control

Large web pages can produce Markdown that exceeds the AI model's context window. The tool supports output truncation:

- Default limit: 50,000 characters (configurable)
- Truncation happens at a paragraph/block boundary (not mid-sentence)
- Truncated output ends with `\n\n[Content truncated at {limit} characters]`
- The AI model can request a higher or lower limit via the `max_length` parameter

### Tool Definition

| Field | Value |
|-------|-------|
| Name | `webfetch` |
| Description | Fetch a web page and return its content as Markdown |
| Parameters | `url` (string, required): The URL to fetch |
| | `max_length` (integer, optional): Maximum output length in characters. Default: 50000 |
| Required Permissions | `INTERNET` |
| Timeout | 30 seconds |
| Returns | Markdown string of the page content, or error object |

### User Interaction Flow

```
1. User: "What does the Jsoup homepage say?"
2. AI calls webfetch(url="https://jsoup.org")
3. WebfetchTool:
   a. Fetches HTML via OkHttpClient
   b. Parses with Jsoup
   c. Extracts main content area
   d. Converts to Markdown via HtmlToMarkdownConverter
   e. Truncates if needed
4. AI receives clean Markdown, summarizes for the user
5. Chat shows the webfetch tool call result
```

## Acceptance Criteria

Must pass (all required):

- [ ] `webfetch` tool is registered as a Kotlin built-in tool in `ToolRegistry`
- [ ] The JS `webfetch.js` and `webfetch.json` are removed from `assets/js/tools/`
- [ ] Same tool name (`webfetch`) and same required parameter (`url`)
- [ ] HTML pages are parsed with Jsoup and converted to Markdown via DOM traversal
- [ ] Content extraction correctly identifies `<article>` or `<main>` content areas
- [ ] Noise elements (`<script>`, `<style>`, `<nav>`, etc.) are stripped before conversion
- [ ] Non-HTML responses (JSON, plain text, etc.) return the raw body
- [ ] HTTP errors return an error object with status code and message
- [ ] Output is truncated at a configurable character limit (default 50,000)
- [ ] Truncation occurs at a block boundary, not mid-word
- [ ] Nested HTML structures (lists, tables) are correctly converted
- [ ] All Layer 1A tests pass

Optional (nice to have for V1):

- [ ] Language/charset detection from `Content-Type` header
- [ ] `<meta>` description extraction as a summary line
- [ ] Support for `selector` parameter to extract specific CSS selectors

## UI/UX Requirements

This feature has no new UI. The replacement is transparent:
- Same tool name appears in tool lists
- Same parameters accepted
- Tool call display in chat is unchanged

## Feature Boundary

### Included

- Kotlin `WebfetchTool` implementation using Jsoup
- `HtmlToMarkdownConverter` utility class with DOM-based traversal
- Jsoup dependency addition to `build.gradle.kts`
- Removal of JS `webfetch.js` and `webfetch.json` from assets
- Update to `ToolModule` registration
- Output truncation with configurable limit

### Not Included (V1)

- JavaScript rendering (SPA/dynamic pages) -- deferred to FEAT-022
- Response caching
- PDF or binary content extraction
- Cookie or authentication support
- Readability scoring (full Mozilla Readability algorithm)
- Proxy configuration

## Business Rules

1. `webfetch` only accepts HTTP and HTTPS URLs
2. `webfetch` follows redirects (up to 5 hops, consistent with OkHttpClient defaults)
3. `webfetch` does not follow redirects to `file://` or `content://` URIs
4. Output truncation defaults to 50,000 characters if `max_length` is not specified
5. Non-HTML content types are returned as raw text without Markdown conversion
6. The `User-Agent` header is set to identify as a mobile browser to avoid bot-blocking

## Non-Functional Requirements

### Performance

- HTML parsing + Markdown conversion: < 200ms for typical pages (< 500KB HTML)
- Memory: Jsoup DOM stays in memory only during conversion, then is garbage collected
- No persistent state between tool calls

### Compatibility

- Same tool name and parameter schema as the JS predecessor
- AI models using `webfetch` see no behavioral change (output is Markdown in both cases)

### Security

- URL validation: only HTTP/HTTPS schemes allowed
- No redirect following to non-HTTP schemes
- Jsoup parsing is safe against XSS by design (output is Markdown, not HTML)
- OkHttpClient timeout prevents hanging on slow servers

## Dependencies

### Depends On

- **FEAT-004 (Tool System)**: Tool interface, registry, execution engine
- **FEAT-015 (JS Tool Migration)**: Established the current JS webfetch implementation being replaced

### Depended On By

- No other features currently depend on FEAT-021

### External Dependencies

- **Jsoup** (~400KB): Java/Kotlin HTML parser library. Apache 2.0 license. Widely used, well-maintained.

## Error Handling

### Error Scenarios

1. **Invalid URL**
   - Cause: Malformed URL or non-HTTP scheme
   - Handling: Return `ToolResult.error("Invalid URL: <message>")`

2. **Network error**
   - Cause: DNS failure, connection timeout, server unreachable
   - Handling: Return `ToolResult.error("Network error: <message>")`

3. **Non-200 HTTP response**
   - Cause: 404, 500, etc.
   - Handling: Return error with status code; include response body if available

4. **HTML parsing failure**
   - Cause: Severely malformed content
   - Handling: Jsoup handles malformed HTML gracefully; fallback to raw text if conversion produces empty output

5. **Response too large**
   - Cause: Page exceeds reasonable size (e.g., >5MB)
   - Handling: Truncate HTML before parsing to prevent OOM

## Future Improvements

- [ ] **Full Readability algorithm**: Implement content scoring similar to Mozilla Readability for better main content detection
- [ ] **Response caching**: Cache fetched pages for a short TTL to avoid redundant requests
- [ ] **CSS selector extraction**: Allow specifying a CSS selector to extract specific page sections
- [ ] **Metadata extraction**: Return page metadata (title, description, author, publish date) as structured fields
- [ ] **Multi-page crawling**: Follow pagination links to fetch multi-page content

## Test Points

### Functional Tests

- Verify `webfetch` returns Markdown for a standard HTML page
- Verify content extraction prefers `<article>` over `<body>`
- Verify content extraction prefers `<main>` when no `<article>` exists
- Verify noise elements (`<script>`, `<nav>`, etc.) are stripped
- Verify headings are converted to `#` syntax
- Verify links are converted to `[text](url)` syntax
- Verify nested lists are properly indented
- Verify tables are converted to Markdown table syntax
- Verify code blocks are fenced with triple backticks
- Verify non-HTML content types return raw body
- Verify HTTP errors return error objects
- Verify output truncation at configurable limit
- Verify truncation occurs at block boundary
- Verify `max_length` parameter is respected

### Edge Cases

- Page with no `<body>` content (empty HTML)
- Very large page (>1MB HTML)
- Page with deeply nested elements (>10 levels)
- Page with malformed/unclosed tags
- Page with mixed encodings
- URL returning a redirect chain
- URL returning binary content (image, PDF)
- Page with only `<table>` content (no prose)
- `max_length` set to 0 or negative value

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
