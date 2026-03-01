# RFC-022: WebView Browser Tool

## Document Information
- **RFC ID**: RFC-022
- **Related PRD**: [FEAT-022 (WebView Browser Tool)](../../prd/features/FEAT-022-webview-browser.md)
- **Related Architecture**: [RFC-000 (Overall Architecture)](../architecture/RFC-000-overall-architecture.md)
- **Related RFC**: [RFC-004 (Tool System)](RFC-004-tool-system.md), [RFC-021 (Kotlin Webfetch)](RFC-021-kotlin-webfetch.md)
- **Created**: 2026-03-01
- **Last Updated**: 2026-03-01
- **Status**: Draft
- **Author**: TBD

## Overview

### Background

FEAT-021 provides a `webfetch` tool that fetches static HTML and converts it to Markdown using Jsoup. This works well for traditional server-rendered pages, but modern web applications increasingly rely on client-side JavaScript for content rendering. Single-page applications (SPAs) built with React, Vue, Angular, and similar frameworks return minimal HTML that is populated by JavaScript after the initial page load. For these pages, `webfetch` returns mostly empty content.

Additionally, AI agents sometimes need visual information about web pages -- layout verification, visual inspection, and screenshot-based analysis -- which requires actual page rendering.

RFC-022 introduces a `browser` tool that uses Android's built-in WebView (Chromium-based browser engine) to render pages with full JavaScript support. The tool provides two modes: screenshot capture and DOM content extraction.

### Goals

1. Implement `BrowserTool.kt` as a Kotlin built-in tool with `screenshot` and `extract` modes
2. Implement `WebViewManager` for off-screen WebView lifecycle management
3. Implement screenshot capture via Canvas/Bitmap
4. Implement content extraction via `evaluateJavascript()` with built-in extraction script
5. Bundle Turndown for use in WebView context (leveraging existing `assets/js/lib/turndown.min.js`)
6. Proper memory and lifecycle management for WebView instances

### Non-Goals

- Interactive browsing (clicking, form filling, multi-step navigation)
- Cookie/session persistence across tool calls
- Headless Chrome or external browser integration
- PDF rendering or download
- Browser DevTools or network inspection
- Accessibility tree extraction

## Technical Design

### Architecture Overview

```
┌──────────────────────────────────────────────────────────────┐
│                     Chat Layer (RFC-001)                      │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  tool call: browser(url, mode, ...)                  │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   Tool Execution Engine (RFC-004)             │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐                                  │  │
│  │  │     browser       │  Kotlin built-in [NEW]          │  │
│  │  │ (BrowserTool.kt)  │                                 │  │
│  │  └───────┬──────────┘                                  │  │
│  │          │                                              │  │
│  └──────────┼──────────────────────────────────────────────┘  │
│             │                                                  │
│             v                                                  │
│  ┌──────────────────────────────────────────────────────────┐ │
│  │                    BrowserTool                             │ │
│  │  ┌─────────────────────────────────────────────────────┐ │ │
│  │  │               WebViewManager                         │ │ │
│  │  │                                                     │ │ │
│  │  │  ┌─────────────┐  ┌──────────────┐                 │ │ │
│  │  │  │   WebView    │  │   Lifecycle   │                │ │ │
│  │  │  │ (off-screen) │  │  Management   │                │ │ │
│  │  │  │              │  │              │                 │ │ │
│  │  │  │ - loadUrl()  │  │ - create     │                 │ │ │
│  │  │  │ - evaluate   │  │ - reuse      │                 │ │ │
│  │  │  │   Javascript │  │ - cleanup    │                 │ │ │
│  │  │  │ - draw()     │  │ - destroy    │                 │ │ │
│  │  │  └──────┬───────┘  └──────────────┘                 │ │ │
│  │  │         │                                            │ │ │
│  │  │    ┌────┴────────────────┐                          │ │ │
│  │  │    │                     │                          │ │ │
│  │  │    v                     v                          │ │ │
│  │  │  Screenshot           Extract                      │ │ │
│  │  │  ┌───────────┐      ┌───────────────────┐         │ │ │
│  │  │  │ Canvas    │      │ evaluateJavascript │         │ │ │
│  │  │  │ -> Bitmap │      │ -> Turndown/text   │         │ │ │
│  │  │  │ -> PNG    │      │ -> Markdown        │         │ │ │
│  │  │  │ -> File   │      │ -> String          │         │ │ │
│  │  │  └───────────┘      └───────────────────┘         │ │ │
│  │  └─────────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### Core Components

**New:**
1. `BrowserTool` -- Kotlin built-in tool with screenshot and extract modes
2. `WebViewManager` -- Manages off-screen WebView lifecycle (create, load, capture, cleanup)
3. `BrowserScreenshotCapture` -- Utility for capturing WebView content to Bitmap/PNG
4. `BrowserContentExtractor` -- Built-in JS extraction script and Turndown integration

**Modified:**
5. `ToolModule` -- Register `BrowserTool`

## Detailed Design

### Directory Structure (New & Changed Files)

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── BrowserTool.kt              # NEW
│   │   │   ├── WebfetchTool.kt             # unchanged (FEAT-021)
│   │   │   ├── LoadSkillTool.kt            # unchanged
│   │   │   ├── CreateScheduledTaskTool.kt  # unchanged
│   │   │   └── CreateAgentTool.kt          # unchanged
│   │   └── browser/
│   │       ├── WebViewManager.kt           # NEW
│   │       ├── BrowserScreenshotCapture.kt # NEW
│   │       └── BrowserContentExtractor.kt  # NEW
│   └── di/
│       └── ToolModule.kt                   # MODIFIED
├── assets/
│   └── js/
│       ├── lib/
│       │   └── turndown.min.js             # unchanged (used by WebView)
│       └── browser/
│           └── extract.js                  # NEW - built-in extraction script

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   └── BrowserToolTest.kt           # NEW
        └── browser/
            ├── WebViewManagerTest.kt        # NEW
            └── BrowserContentExtractorTest.kt # NEW
```

### BrowserTool

```kotlin
/**
 * Located in: tool/builtin/BrowserTool.kt
 *
 * WebView-based browser tool that renders web pages and provides
 * screenshot capture and content extraction capabilities.
 */
class BrowserTool(
    private val context: Context,
    private val webViewManager: WebViewManager
) : Tool {

    companion object {
        private const val TAG = "BrowserTool"
        private const val DEFAULT_WIDTH = 412
        private const val DEFAULT_HEIGHT = 915
        private const val DEFAULT_WAIT_SECONDS = 2.0
        private const val DEFAULT_MAX_LENGTH = 50_000
        private const val TOOL_TIMEOUT_SECONDS = 60L
    }

    override val definition = ToolDefinition(
        name = "browser",
        description = "Render a web page in a browser, then take a screenshot or extract content. " +
            "Use 'screenshot' mode to capture a visual image of the page. " +
            "Use 'extract' mode to get the page content as Markdown after JavaScript rendering. " +
            "Prefer 'webfetch' for static pages; use 'browser' when content requires JavaScript rendering.",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "The URL to load in the browser"
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "Operation mode: 'screenshot' to capture an image, 'extract' to get page content as Markdown",
                    enum = listOf("screenshot", "extract")
                ),
                "width" to ToolParameter(
                    type = "integer",
                    description = "Viewport width in pixels (screenshot mode). Default: 412"
                ),
                "height" to ToolParameter(
                    type = "integer",
                    description = "Viewport height in pixels (screenshot mode). Default: 915"
                ),
                "wait_seconds" to ToolParameter(
                    type = "number",
                    description = "Seconds to wait after page load for JavaScript rendering. Default: 2"
                ),
                "full_page" to ToolParameter(
                    type = "boolean",
                    description = "Capture full scrollable page instead of just viewport (screenshot mode). Default: false"
                ),
                "max_length" to ToolParameter(
                    type = "integer",
                    description = "Maximum output length in characters (extract mode). Default: 50000"
                ),
                "javascript" to ToolParameter(
                    type = "string",
                    description = "Custom JavaScript to execute in extract mode. Must return a string."
                )
            ),
            required = listOf("url", "mode")
        )
    )

    override suspend fun execute(
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        val url = params["url"]?.toString()
            ?: return ToolResult.error("Parameter 'url' is required")
        val mode = params["mode"]?.toString()
            ?: return ToolResult.error("Parameter 'mode' is required")

        // Validate URL
        try {
            val parsedUrl = java.net.URL(url)
            if (parsedUrl.protocol !in listOf("http", "https")) {
                return ToolResult.error("Only HTTP and HTTPS URLs are supported")
            }
        } catch (e: Exception) {
            return ToolResult.error("Invalid URL: ${e.message}")
        }

        return when (mode) {
            "screenshot" -> executeScreenshot(url, params)
            "extract" -> executeExtract(url, params)
            else -> ToolResult.error("Invalid mode '$mode'. Use 'screenshot' or 'extract'")
        }
    }

    private suspend fun executeScreenshot(
        url: String,
        params: Map<String, Any?>
    ): ToolResult {
        val width = (params["width"] as? Number)?.toInt() ?: DEFAULT_WIDTH
        val height = (params["height"] as? Number)?.toInt() ?: DEFAULT_HEIGHT
        val waitSeconds = (params["wait_seconds"] as? Number)?.toDouble() ?: DEFAULT_WAIT_SECONDS
        val fullPage = params["full_page"] as? Boolean ?: false

        return try {
            val filePath = webViewManager.captureScreenshot(
                url = url,
                width = width,
                height = height,
                waitSeconds = waitSeconds,
                fullPage = fullPage
            )
            ToolResult.success("""{"image_path": "$filePath"}""")
        } catch (e: Exception) {
            Log.e(TAG, "Screenshot failed for $url", e)
            ToolResult.error("Screenshot failed: ${e.message}")
        }
    }

    private suspend fun executeExtract(
        url: String,
        params: Map<String, Any?>
    ): ToolResult {
        val waitSeconds = (params["wait_seconds"] as? Number)?.toDouble() ?: DEFAULT_WAIT_SECONDS
        val maxLength = (params["max_length"] as? Number)?.toInt() ?: DEFAULT_MAX_LENGTH
        val customJs = params["javascript"]?.toString()

        return try {
            val content = webViewManager.extractContent(
                url = url,
                waitSeconds = waitSeconds,
                customJavascript = customJs,
                maxLength = maxLength
            )
            ToolResult.success(content)
        } catch (e: Exception) {
            Log.e(TAG, "Content extraction failed for $url", e)
            ToolResult.error("Extraction failed: ${e.message}")
        }
    }
}
```

### WebViewManager

```kotlin
/**
 * Located in: tool/browser/WebViewManager.kt
 *
 * Manages an off-screen WebView instance for page rendering,
 * screenshot capture, and content extraction.
 *
 * Key design decisions:
 * - WebView is created lazily on first use
 * - WebView is reused across tool calls within the same session
 * - WebView state is cleaned between uses (cookies, cache, history)
 * - WebView is destroyed after 5 minutes of inactivity
 * - All WebView operations happen on the main thread (Android requirement)
 */
class WebViewManager(
    private val context: Context,
    private val screenshotCapture: BrowserScreenshotCapture,
    private val contentExtractor: BrowserContentExtractor
) {
    companion object {
        private const val TAG = "WebViewManager"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5 minutes
        private const val MAX_FULL_PAGE_HEIGHT = 10_000  // pixels
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleCleanupRunnable: Runnable? = null

    /**
     * Load a URL, wait for rendering, and capture a screenshot.
     * Returns the file path to the saved PNG.
     */
    suspend fun captureScreenshot(
        url: String,
        width: Int,
        height: Int,
        waitSeconds: Double,
        fullPage: Boolean
    ): String {
        val wv = getOrCreateWebView(width, height)

        // Load URL and wait for page load
        loadUrlAndWait(wv, url)

        // Wait for JavaScript rendering
        delay((waitSeconds * 1000).toLong())

        // Capture screenshot
        val bitmap = if (fullPage) {
            screenshotCapture.captureFullPage(wv, MAX_FULL_PAGE_HEIGHT)
        } else {
            screenshotCapture.captureViewport(wv)
        }

        // Save to file
        val file = File(context.cacheDir, "browser_screenshot_${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        // Schedule cleanup
        scheduleIdleCleanup()
        resetWebView(wv)

        return file.absolutePath
    }

    /**
     * Load a URL, wait for rendering, and extract page content.
     * Returns the extracted text (Markdown or custom JS result).
     */
    suspend fun extractContent(
        url: String,
        waitSeconds: Double,
        customJavascript: String?,
        maxLength: Int
    ): String {
        val wv = getOrCreateWebView(DEFAULT_WIDTH, DEFAULT_HEIGHT)

        // Load URL and wait for page load
        loadUrlAndWait(wv, url)

        // Wait for JavaScript rendering
        delay((waitSeconds * 1000).toLong())

        // Extract content
        val content = if (customJavascript != null) {
            contentExtractor.executeCustomJs(wv, customJavascript)
        } else {
            contentExtractor.extractAsMarkdown(wv)
        }

        // Truncate if needed
        val result = if (maxLength > 0 && content.length > maxLength) {
            val truncateAt = content.lastIndexOf("\n\n", maxLength)
            val cutoff = if (truncateAt > maxLength / 2) truncateAt else maxLength
            content.substring(0, cutoff) + "\n\n[Content truncated at $maxLength characters]"
        } else {
            content
        }

        // Schedule cleanup
        scheduleIdleCleanup()
        resetWebView(wv)

        return result
    }

    /**
     * Get or create the off-screen WebView on the main thread.
     */
    private suspend fun getOrCreateWebView(width: Int, height: Int): WebView {
        return withContext(Dispatchers.Main) {
            webView?.also {
                it.layoutParams = ViewGroup.LayoutParams(width, height)
                it.layout(0, 0, width, height)
            } ?: run {
                val wv = WebView(context).apply {
                    layoutParams = ViewGroup.LayoutParams(width, height)
                    layout(0, 0, width, height)

                    settings.apply {
                        javaScriptEnabled = true
                        domStorageEnabled = true
                        loadWithOverviewMode = true
                        useWideViewPort = true
                        // Disable zoom controls for clean rendering
                        builtInZoomControls = false
                        displayZoomControls = false
                        // Allow mixed content for pages with mixed HTTP/HTTPS resources
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // Set a reasonable user agent
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    }

                    // Block navigation to non-HTTP schemes
                    webViewClient = object : WebViewClient() {
                        override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                        ): Boolean {
                            val scheme = request?.url?.scheme?.lowercase()
                            return scheme !in listOf("http", "https")
                        }
                    }
                }
                webView = wv
                wv
            }
        }
    }

    /**
     * Load a URL and suspend until onPageFinished fires.
     */
    private suspend fun loadUrlAndWait(wv: WebView, url: String) {
        suspendCancellableCoroutine<Unit> { continuation ->
            mainHandler.post {
                wv.webViewClient = object : WebViewClient() {
                    override fun onPageFinished(view: WebView?, url: String?) {
                        if (continuation.isActive) {
                            continuation.resume(Unit) {}
                        }
                    }

                    override fun onReceivedError(
                        view: WebView?,
                        request: WebResourceRequest?,
                        error: WebResourceError?
                    ) {
                        // Only handle main frame errors
                        if (request?.isForMainFrame == true && continuation.isActive) {
                            continuation.resumeWithException(
                                IOException("Page load error: ${error?.description}")
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(
                        view: WebView?,
                        request: WebResourceRequest?
                    ): Boolean {
                        val scheme = request?.url?.scheme?.lowercase()
                        return scheme !in listOf("http", "https")
                    }
                }
                wv.loadUrl(url)
            }
        }
    }

    /**
     * Reset WebView state between uses.
     */
    private suspend fun resetWebView(wv: WebView) {
        withContext(Dispatchers.Main) {
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(false)
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    /**
     * Schedule WebView destruction after idle timeout.
     */
    private fun scheduleIdleCleanup() {
        idleCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        idleCleanupRunnable = Runnable { destroyWebView() }.also {
            mainHandler.postDelayed(it, IDLE_TIMEOUT_MS)
        }
    }

    /**
     * Destroy the WebView and free memory.
     */
    fun destroyWebView() {
        mainHandler.post {
            webView?.let { wv ->
                wv.stopLoading()
                wv.loadUrl("about:blank")
                wv.clearHistory()
                wv.clearCache(true)
                wv.removeAllViews()
                wv.destroy()
                Log.d(TAG, "WebView destroyed")
            }
            webView = null
        }
    }

    companion object {
        private const val DEFAULT_WIDTH = 412
        private const val DEFAULT_HEIGHT = 915
    }
}
```

### BrowserScreenshotCapture

```kotlin
/**
 * Located in: tool/browser/BrowserScreenshotCapture.kt
 *
 * Captures WebView content as Bitmap images.
 */
class BrowserScreenshotCapture {

    companion object {
        private const val TAG = "BrowserScreenshotCapture"
    }

    /**
     * Capture the visible viewport of the WebView.
     */
    suspend fun captureViewport(webView: WebView): Bitmap {
        return withContext(Dispatchers.Main) {
            val width = webView.width
            val height = webView.height

            if (width <= 0 || height <= 0) {
                throw IllegalStateException("WebView has zero dimensions: ${width}x${height}")
            }

            val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            bitmap
        }
    }

    /**
     * Capture the full scrollable content of the WebView.
     * Height is capped at maxHeight to prevent OOM.
     */
    suspend fun captureFullPage(webView: WebView, maxHeight: Int): Bitmap {
        return withContext(Dispatchers.Main) {
            val width = webView.width

            // Get the full content height
            val contentHeight = (webView.contentHeight * webView.scale).toInt()
                .coerceAtMost(maxHeight)
                .coerceAtLeast(webView.height)

            if (width <= 0 || contentHeight <= 0) {
                throw IllegalStateException(
                    "Invalid dimensions for full-page capture: ${width}x${contentHeight}"
                )
            }

            val bitmap = Bitmap.createBitmap(width, contentHeight, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)

            // Save the current scroll position
            val savedScrollX = webView.scrollX
            val savedScrollY = webView.scrollY

            // Scroll to top and draw
            webView.scrollTo(0, 0)
            webView.draw(canvas)

            // Restore scroll position
            webView.scrollTo(savedScrollX, savedScrollY)

            bitmap
        }
    }
}
```

### BrowserContentExtractor

```kotlin
/**
 * Located in: tool/browser/BrowserContentExtractor.kt
 *
 * Extracts content from a rendered WebView page using evaluateJavascript().
 * Uses Turndown (loaded into the WebView context) for HTML-to-Markdown conversion.
 */
class BrowserContentExtractor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserContentExtractor"
    }

    // Built-in extraction script loaded from assets
    private val extractionScript: String by lazy {
        context.assets.open("js/browser/extract.js")
            .bufferedReader().use { it.readText() }
    }

    // Turndown library source loaded from assets
    private val turndownSource: String by lazy {
        context.assets.open("js/lib/turndown.min.js")
            .bufferedReader().use { it.readText() }
    }

    /**
     * Extract page content as Markdown using the built-in extraction script.
     * Injects Turndown into the page context for DOM-based HTML-to-Markdown conversion.
     */
    suspend fun extractAsMarkdown(webView: WebView): String {
        return withContext(Dispatchers.Main) {
            // First inject Turndown into the page context
            evaluateJs(webView, turndownSource)

            // Then run the extraction script
            val result = evaluateJs(webView, extractionScript)

            // The extraction script returns a JSON-encoded string
            result.let {
                // evaluateJavascript returns JSON-encoded strings (with surrounding quotes)
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    // Unescape the JSON string
                    kotlinx.serialization.json.Json.decodeFromString<String>(it)
                } else if (it == "null" || it.isBlank()) {
                    ""
                } else {
                    it
                }
            }
        }
    }

    /**
     * Execute custom JavaScript in the WebView and return the result.
     */
    suspend fun executeCustomJs(webView: WebView, javascript: String): String {
        return withContext(Dispatchers.Main) {
            val wrappedJs = """
                (function() {
                    try {
                        var result = (function() { $javascript })();
                        return (typeof result === 'string') ? result : JSON.stringify(result);
                    } catch(e) {
                        return 'JavaScript error: ' + e.message;
                    }
                })()
            """.trimIndent()

            val result = evaluateJs(webView, wrappedJs)

            if (result.startsWith("\"") && result.endsWith("\"")) {
                kotlinx.serialization.json.Json.decodeFromString<String>(result)
            } else if (result == "null") {
                ""
            } else {
                result
            }
        }
    }

    /**
     * Evaluate JavaScript in the WebView and suspend until result is available.
     */
    private suspend fun evaluateJs(webView: WebView, script: String): String {
        return suspendCancellableCoroutine { continuation ->
            webView.evaluateJavascript(script) { result ->
                if (continuation.isActive) {
                    continuation.resume(result ?: "null") {}
                }
            }
        }
    }
}
```

### Built-in Extraction Script

```javascript
/**
 * Located in: assets/js/browser/extract.js
 *
 * Built-in content extraction script that runs in the WebView context.
 * Uses Turndown (injected beforehand) for HTML-to-Markdown conversion.
 * Falls back to innerText extraction if Turndown is unavailable.
 */
(function() {
    // Noise selectors to remove
    var noiseSelectors = [
        'script', 'style', 'noscript', 'svg', 'iframe',
        'nav', 'header', 'footer', 'aside',
        'form', 'button', 'input', 'select', 'textarea',
        '[role="navigation"]', '[role="banner"]', '[role="contentinfo"]',
        '.cookie-banner', '.popup', '.modal', '.advertisement', '.ad'
    ];

    // Clone the document to avoid modifying the actual page
    var clone = document.documentElement.cloneNode(true);

    // Remove noise elements
    noiseSelectors.forEach(function(selector) {
        try {
            var elements = clone.querySelectorAll(selector);
            elements.forEach(function(el) { el.remove(); });
        } catch(e) { /* ignore invalid selectors */ }
    });

    // Find main content area
    var content = clone.querySelector('article')
        || clone.querySelector('main')
        || clone.querySelector('[role="main"]')
        || clone.querySelector('body')
        || clone;

    // Get the title
    var title = document.title || '';

    // Try Turndown if available
    var markdown = '';
    if (typeof TurndownService !== 'undefined') {
        try {
            var td = new TurndownService({
                headingStyle: 'atx',
                codeBlockStyle: 'fenced',
                bulletListMarker: '-'
            });
            // Remove empty links and images without src
            td.addRule('removeEmptyLinks', {
                filter: function(node) {
                    return node.nodeName === 'A' && !node.textContent.trim();
                },
                replacement: function() { return ''; }
            });
            markdown = td.turndown(content.innerHTML);
        } catch(e) {
            // Fall back to innerText
            markdown = content.innerText || content.textContent || '';
        }
    } else {
        // No Turndown available, use innerText
        markdown = content.innerText || content.textContent || '';
    }

    // Prepend title if not already in content
    if (title && markdown.indexOf(title) === -1) {
        markdown = '# ' + title + '\n\n' + markdown;
    }

    // Clean up: collapse multiple blank lines
    markdown = markdown.replace(/\n{3,}/g, '\n\n').trim();

    return markdown;
})();
```

### ToolModule Changes

```kotlin
// In ToolModule.kt

val toolModule = module {
    // ... existing registrations ...

    // Browser tool components
    single { BrowserScreenshotCapture() }
    single { BrowserContentExtractor(get()) }
    single { WebViewManager(get(), get(), get()) }
    single { BrowserTool(get(), get()) }

    // Register in ToolRegistry
    single {
        val registry = get<ToolRegistry>()
        // ... existing tool registrations ...
        registry.register(get<WebfetchTool>())   // FEAT-021
        registry.register(get<BrowserTool>())     // FEAT-022
        // ...
        registry
    }
}
```

## Implementation Plan

### Phase 1: WebView Infrastructure

1. Create `WebViewManager.kt` with lifecycle management
2. Create `BrowserScreenshotCapture.kt` for Canvas-based capture
3. Create basic tests for WebView lifecycle

### Phase 2: Content Extraction

1. Create `BrowserContentExtractor.kt`
2. Create `assets/js/browser/extract.js` extraction script
3. Test extraction with Turndown in WebView context

### Phase 3: BrowserTool Integration

1. Create `BrowserTool.kt` implementing `Tool` interface
2. Update `ToolModule.kt` to register the browser tool
3. Create `BrowserToolTest.kt`
4. End-to-end testing

### Phase 4: Testing & Verification

1. Run Layer 1A tests (`./gradlew test`)
2. Run Layer 1B instrumented tests (WebView requires Android context)
3. Manual testing with real-world URLs (static pages, SPAs, JS-heavy pages)
4. Memory profiling to verify WebView cleanup

## Data Model

No data model changes. `BrowserTool` implements the existing `Tool` interface.

## API Design

### Tool Interface

```
Tool Name: browser
Parameters:
  - url: string (required) -- The URL to load
  - mode: string (required) -- "screenshot" or "extract"
  - width: integer (optional, default: 412) -- Viewport width (screenshot mode)
  - height: integer (optional, default: 915) -- Viewport height (screenshot mode)
  - wait_seconds: number (optional, default: 2) -- Wait after page load
  - full_page: boolean (optional, default: false) -- Full-page screenshot
  - max_length: integer (optional, default: 50000) -- Max output length (extract mode)
  - javascript: string (optional) -- Custom JS to execute (extract mode)

Returns on success (screenshot mode):
  JSON: {"image_path": "/path/to/screenshot.png"}

Returns on success (extract mode):
  Markdown string of extracted content

Returns on error:
  ToolResult.error with descriptive message
```

## Error Handling

| Error | Cause | Handling |
|-------|-------|----------|
| Invalid URL | Malformed URL or non-HTTP scheme | `ToolResult.error("Invalid URL: ...")` |
| Invalid mode | Mode is not "screenshot" or "extract" | `ToolResult.error("Invalid mode...")` |
| Page load error | Network failure, DNS error, SSL error | `ToolResult.error("Page load error: ...")` via `onReceivedError` |
| Page load timeout | Page never finishes loading | Tool-level timeout (60s) cancels the coroutine |
| JS execution error | Custom JS throws exception | `ToolResult.error("JavaScript error: ...")` |
| Screenshot failure | Zero-dimension WebView, bitmap allocation | `ToolResult.error("Screenshot failed: ...")` |
| WebView unavailable | System WebView missing | `ToolResult.error("WebView not available...")` |
| OOM on full-page | Extremely long page | Height capped at 10,000px |

## Security Considerations

1. **URL scheme restriction**: Only HTTP/HTTPS URLs accepted. `file://`, `content://`, `javascript:` schemes rejected both at tool level and in `WebViewClient.shouldOverrideUrlLoading`.
2. **No `addJavascriptInterface`**: The WebView does not expose any Android objects to page JavaScript. The page cannot access Android APIs.
3. **Custom JS sandboxing**: The `javascript` parameter runs in the same sandbox as page scripts. It can access the page DOM but not Android internals.
4. **State isolation**: WebView cache, cookies, and history are cleared between tool calls. No credential leakage across calls.
5. **No persistent storage**: `localStorage` and `sessionStorage` are cleared on reset.
6. **Content Security Policy**: The WebView's CSP is the page's own CSP. The tool does not weaken any security policies.
7. **Mixed content**: `MIXED_CONTENT_COMPATIBILITY_MODE` allows loading mixed HTTP/HTTPS resources for broader compatibility, matching standard mobile browser behavior.

## Performance

| Operation | Expected Time | Notes |
|-----------|--------------|-------|
| WebView creation (cold) | ~500ms | One-time cost per session |
| Page load | 1-5s | Depends on page and network |
| Wait for JS rendering | Configurable (default 2s) | User-controlled |
| Viewport screenshot capture | ~100ms | Canvas draw + PNG compress |
| Full-page screenshot capture | ~500ms | Multiple canvas draws |
| Content extraction (Turndown) | ~200ms | Turndown injection + execution |
| Custom JS execution | < 100ms | Depends on script complexity |
| WebView reset | ~50ms | Clear history + cache |

### Memory

| Resource | Peak Usage | Notes |
|----------|-----------|-------|
| WebView process | ~50-100MB | Managed by Android, separate process |
| Viewport bitmap (412x915, ARGB_8888) | ~1.5MB | Recycled after save |
| Full-page bitmap (412x10000) | ~16MB | Recycled after save |
| Turndown.js in memory | ~50KB | Injected per extraction |
| Screenshot PNG file | ~200KB-2MB | Saved to cache dir |

## Testing Strategy

### Unit Tests

**BrowserToolTest.kt:**
- `testDefinition` -- Tool definition has correct name, parameters, and required fields
- `testExecute_invalidMode` -- Invalid mode returns error
- `testExecute_invalidUrl` -- Invalid URL returns error
- `testExecute_missingUrl` -- Missing URL returns error
- `testExecute_missingMode` -- Missing mode returns error

**WebViewManagerTest.kt** (instrumented, requires Android context):
- `testGetOrCreateWebView` -- WebView is created with correct settings
- `testWebViewReuse` -- Second call reuses the same WebView instance
- `testResetWebView` -- State is cleared between uses
- `testDestroyWebView` -- WebView is properly destroyed
- `testIdleCleanup` -- WebView is destroyed after idle timeout

**BrowserContentExtractorTest.kt** (instrumented):
- `testExtractAsMarkdown_simpleHtml` -- Basic HTML extraction
- `testExtractAsMarkdown_withTurndown` -- Turndown produces clean Markdown
- `testExecuteCustomJs_returnsString` -- Custom JS result returned
- `testExecuteCustomJs_error` -- JS error handled gracefully

### Integration Tests

Manual verification:
- Screenshot of a simple static page
- Screenshot of a JavaScript-heavy SPA (e.g., React app)
- Content extraction from a static page (compare with `webfetch` output)
- Content extraction from a SPA
- Custom JavaScript execution
- Full-page screenshot of a long page

## Alternatives Considered

### 1. Headless Chrome via Chrome DevTools Protocol

**Approach**: Use Chrome DevTools Protocol to control a headless Chrome instance.
**Rejected because**: Requires bundling or installing Chrome on the device, significantly increases app complexity and size. Android WebView is already Chromium-based and available on all devices.

### 2. Selenium/Appium

**Approach**: Use Selenium WebDriver with Android WebView.
**Rejected because**: Massive dependency overhead, designed for testing workflows not runtime tool usage. WebView API is simpler and lighter for our use case.

### 3. PixelCopy API for screenshots

**Approach**: Use `PixelCopy.request()` instead of Canvas drawing.
**Rejected because**: `PixelCopy` requires a `Surface` (attached to a window), which means the WebView needs to be in the view hierarchy. Our off-screen approach uses Canvas drawing which works without a visible window. `PixelCopy` can be added later if higher-quality rendering is needed.

### 4. Separate screenshot and extract tools

**Approach**: Register `browser_screenshot` and `browser_extract` as two separate tools.
**Rejected because**: A single `browser` tool with a `mode` parameter is cleaner. It avoids cluttering the tool registry and makes it clear they share the same underlying WebView infrastructure. The AI model can easily choose the mode via the parameter.

## Dependencies

### External Dependencies

No new external dependencies. Uses:
- Android WebView (system component)
- Turndown.js (already bundled from FEAT-015)
- OkHttpClient (already available, but not directly used by this tool)

### Internal Dependencies

- `Tool` interface from `tool/` package
- `ToolResult`, `ToolDefinition`, `ToolParameters` from `tool/` package
- Android `Context` (via Koin DI)
- `assets/js/lib/turndown.min.js` (from FEAT-015)

## Change History

| Date | Version | Changes | Owner |
|------|---------|---------|-------|
| 2026-03-01 | 0.1 | Initial version | - |
