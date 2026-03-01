# RFC-022：WebView 浏览器工具

## 文档信息
- **RFC ID**：RFC-022
- **关联 PRD**：[FEAT-022（WebView 浏览器工具）](../../prd/features/FEAT-022-webview-browser.md)
- **关联架构**：[RFC-000（整体架构）](../architecture/RFC-000-overall-architecture.md)
- **关联 RFC**：[RFC-004（工具系统）](RFC-004-tool-system.md)、[RFC-021（Kotlin Webfetch）](RFC-021-kotlin-webfetch.md)
- **创建日期**：2026-03-01
- **最后更新**：2026-03-01
- **状态**：草稿
- **作者**：TBD

## 概述

### 背景

FEAT-021 提供了一个 `webfetch` 工具，使用 Jsoup 抓取静态 HTML 并将其转换为 Markdown。对于传统服务端渲染页面，该工具效果良好，但现代 Web 应用越来越依赖客户端 JavaScript 进行内容渲染。基于 React、Vue、Angular 及类似框架构建的单页应用（SPA）在初始页面加载后，返回的 HTML 极少，内容由 JavaScript 动态填充。对于这类页面，`webfetch` 返回的内容大多为空。

此外，AI 智能体有时需要获取网页的视觉信息——布局验证、视觉检查以及基于截图的分析——这需要实际渲染页面。

RFC-022 引入了 `browser` 工具，该工具使用 Android 内置的 WebView（基于 Chromium 的浏览器引擎）以完整 JavaScript 支持渲染页面。该工具提供两种模式：截图捕获和 DOM 内容提取。

### 目标

1. 实现 `BrowserTool.kt`，作为支持 `screenshot` 和 `extract` 模式的 Kotlin 内置工具
2. 实现 `WebViewManager`，用于离屏 WebView 生命周期管理
3. 通过 Canvas/Bitmap 实现截图捕获
4. 通过 `evaluateJavascript()` 配合内置提取脚本实现内容提取
5. 在 WebView 上下文中集成 Turndown（复用已有的 `assets/js/lib/turndown.min.js`）
6. 妥善管理 WebView 实例的内存和生命周期

### 非目标

- 交互式浏览（点击、表单填写、多步导航）
- 跨工具调用的 Cookie/会话持久化
- Headless Chrome 或外部浏览器集成
- PDF 渲染或下载
- 浏览器开发者工具或网络检查
- 无障碍树提取

## 技术设计

### 架构概览

```
┌──────────────────────────────────────────────────────────────┐
│                     聊天层（RFC-001）                         │
│  SendMessageUseCase                                          │
│       │                                                      │
│       │  工具调用：browser(url, mode, ...)                    │
│       v                                                      │
├──────────────────────────────────────────────────────────────┤
│                   工具执行引擎（RFC-004）                     │
│  executeTool(name, params, availableToolIds)                 │
│       │                                                      │
│       v                                                      │
│  ┌────────────────────────────────────────────────────────┐  │
│  │                    ToolRegistry                         │  │
│  │  ┌──────────────────┐                                  │  │
│  │  │     browser       │  Kotlin 内置 [新增]              │  │
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
│  │  │  │   WebView    │  │   生命周期   │                 │ │ │
│  │  │  │  （离屏）    │  │     管理     │                 │ │ │
│  │  │  │              │  │              │                 │ │ │
│  │  │  │ - loadUrl()  │  │ - 创建       │                 │ │ │
│  │  │  │ - evaluate   │  │ - 复用       │                 │ │ │
│  │  │  │   Javascript │  │ - 清理       │                 │ │ │
│  │  │  │ - draw()     │  │ - 销毁       │                 │ │ │
│  │  │  └──────┬───────┘  └──────────────┘                 │ │ │
│  │  │         │                                            │ │ │
│  │  │    ┌────┴────────────────┐                          │ │ │
│  │  │    │                     │                          │ │ │
│  │  │    v                     v                          │ │ │
│  │  │  截图                  提取                         │ │ │
│  │  │  ┌───────────┐      ┌───────────────────┐         │ │ │
│  │  │  │ Canvas    │      │ evaluateJavascript │         │ │ │
│  │  │  │ -> Bitmap │      │ -> Turndown/文本   │         │ │ │
│  │  │  │ -> PNG    │      │ -> Markdown        │         │ │ │
│  │  │  │ -> 文件   │      │ -> 字符串          │         │ │ │
│  │  │  └───────────┘      └───────────────────┘         │ │ │
│  │  └─────────────────────────────────────────────────────┘ │ │
│  └──────────────────────────────────────────────────────────┘ │
└──────────────────────────────────────────────────────────────┘
```

### 核心组件

**新增：**
1. `BrowserTool` -- 支持截图和提取模式的 Kotlin 内置工具
2. `WebViewManager` -- 管理离屏 WebView 生命周期（创建、加载、捕获、清理）
3. `BrowserScreenshotCapture` -- 将 WebView 内容捕获为 Bitmap/PNG 的工具类
4. `BrowserContentExtractor` -- 内置 JS 提取脚本及 Turndown 集成

**修改：**
5. `ToolModule` -- 注册 `BrowserTool`

## 详细设计

### 目录结构（新增及变更文件）

```
app/src/main/
├── kotlin/com/oneclaw/shadow/
│   ├── tool/
│   │   ├── builtin/
│   │   │   ├── BrowserTool.kt              # 新增
│   │   │   ├── WebfetchTool.kt             # 不变（FEAT-021）
│   │   │   ├── LoadSkillTool.kt            # 不变
│   │   │   ├── CreateScheduledTaskTool.kt  # 不变
│   │   │   └── CreateAgentTool.kt          # 不变
│   │   └── browser/
│   │       ├── WebViewManager.kt           # 新增
│   │       ├── BrowserScreenshotCapture.kt # 新增
│   │       └── BrowserContentExtractor.kt  # 新增
│   └── di/
│       └── ToolModule.kt                   # 修改
├── assets/
│   └── js/
│       ├── lib/
│       │   └── turndown.min.js             # 不变（供 WebView 使用）
│       └── browser/
│           └── extract.js                  # 新增 - 内置提取脚本

app/src/test/kotlin/com/oneclaw/shadow/
    └── tool/
        ├── builtin/
        │   └── BrowserToolTest.kt           # 新增
        └── browser/
            ├── WebViewManagerTest.kt        # 新增
            └── BrowserContentExtractorTest.kt # 新增
```

### BrowserTool

```kotlin
/**
 * 位置：tool/builtin/BrowserTool.kt
 *
 * 基于 WebView 的浏览器工具，用于渲染网页并提供
 * 截图捕获和内容提取功能。
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
        description = "在浏览器中渲染网页，然后截图或提取内容。" +
            "使用 'screenshot' 模式捕获页面的视觉图像。" +
            "使用 'extract' 模式在 JavaScript 渲染后以 Markdown 格式获取页面内容。" +
            "静态页面优先使用 'webfetch'；仅当内容需要 JavaScript 渲染时才使用 'browser'。",
        parameters = ToolParameters(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "要在浏览器中加载的 URL"
                ),
                "mode" to ToolParameter(
                    type = "string",
                    description = "操作模式：'screenshot' 捕获图像，'extract' 以 Markdown 格式获取页面内容",
                    enum = listOf("screenshot", "extract")
                ),
                "width" to ToolParameter(
                    type = "integer",
                    description = "视口宽度（像素，截图模式）。默认值：412"
                ),
                "height" to ToolParameter(
                    type = "integer",
                    description = "视口高度（像素，截图模式）。默认值：915"
                ),
                "wait_seconds" to ToolParameter(
                    type = "number",
                    description = "页面加载后等待 JavaScript 渲染的秒数。默认值：2"
                ),
                "full_page" to ToolParameter(
                    type = "boolean",
                    description = "捕获完整可滚动页面而非仅视口（截图模式）。默认值：false"
                ),
                "max_length" to ToolParameter(
                    type = "integer",
                    description = "输出最大字符数（提取模式）。默认值：50000"
                ),
                "javascript" to ToolParameter(
                    type = "string",
                    description = "在提取模式下执行的自定义 JavaScript，必须返回字符串。"
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

        // 校验 URL
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
 * 位置：tool/browser/WebViewManager.kt
 *
 * 管理离屏 WebView 实例，用于页面渲染、截图捕获和内容提取。
 *
 * 关键设计决策：
 * - WebView 在首次使用时延迟创建
 * - 同一会话中的工具调用复用同一个 WebView
 * - 每次使用之间清理 WebView 状态（Cookie、缓存、历史）
 * - 空闲 5 分钟后销毁 WebView
 * - 所有 WebView 操作在主线程执行（Android 要求）
 */
class WebViewManager(
    private val context: Context,
    private val screenshotCapture: BrowserScreenshotCapture,
    private val contentExtractor: BrowserContentExtractor
) {
    companion object {
        private const val TAG = "WebViewManager"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L  // 5 分钟
        private const val MAX_FULL_PAGE_HEIGHT = 10_000  // 像素
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleCleanupRunnable: Runnable? = null

    /**
     * 加载 URL，等待渲染完成，并捕获截图。
     * 返回保存的 PNG 文件路径。
     */
    suspend fun captureScreenshot(
        url: String,
        width: Int,
        height: Int,
        waitSeconds: Double,
        fullPage: Boolean
    ): String {
        val wv = getOrCreateWebView(width, height)

        // 加载 URL 并等待页面加载完成
        loadUrlAndWait(wv, url)

        // 等待 JavaScript 渲染
        delay((waitSeconds * 1000).toLong())

        // 捕获截图
        val bitmap = if (fullPage) {
            screenshotCapture.captureFullPage(wv, MAX_FULL_PAGE_HEIGHT)
        } else {
            screenshotCapture.captureViewport(wv)
        }

        // 保存到文件
        val file = File(context.cacheDir, "browser_screenshot_${System.currentTimeMillis()}.png")
        file.outputStream().use { out ->
            bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
        }
        bitmap.recycle()

        // 调度清理
        scheduleIdleCleanup()
        resetWebView(wv)

        return file.absolutePath
    }

    /**
     * 加载 URL，等待渲染完成，并提取页面内容。
     * 返回提取的文本（Markdown 或自定义 JS 结果）。
     */
    suspend fun extractContent(
        url: String,
        waitSeconds: Double,
        customJavascript: String?,
        maxLength: Int
    ): String {
        val wv = getOrCreateWebView(DEFAULT_WIDTH, DEFAULT_HEIGHT)

        // 加载 URL 并等待页面加载完成
        loadUrlAndWait(wv, url)

        // 等待 JavaScript 渲染
        delay((waitSeconds * 1000).toLong())

        // 提取内容
        val content = if (customJavascript != null) {
            contentExtractor.executeCustomJs(wv, customJavascript)
        } else {
            contentExtractor.extractAsMarkdown(wv)
        }

        // 按需截断
        val result = if (maxLength > 0 && content.length > maxLength) {
            val truncateAt = content.lastIndexOf("\n\n", maxLength)
            val cutoff = if (truncateAt > maxLength / 2) truncateAt else maxLength
            content.substring(0, cutoff) + "\n\n[Content truncated at $maxLength characters]"
        } else {
            content
        }

        // 调度清理
        scheduleIdleCleanup()
        resetWebView(wv)

        return result
    }

    /**
     * 在主线程上获取或创建离屏 WebView。
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
                        // 禁用缩放控件以实现干净渲染
                        builtInZoomControls = false
                        displayZoomControls = false
                        // 允许混合内容，兼容包含 HTTP/HTTPS 混合资源的页面
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        // 设置合理的 User Agent
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    }

                    // 拦截非 HTTP 协议的导航
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
     * 加载 URL 并挂起，直到 onPageFinished 触发。
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
                        // 仅处理主框架错误
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
     * 在两次使用之间重置 WebView 状态。
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
     * 在空闲超时后调度 WebView 销毁。
     */
    private fun scheduleIdleCleanup() {
        idleCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        idleCleanupRunnable = Runnable { destroyWebView() }.also {
            mainHandler.postDelayed(it, IDLE_TIMEOUT_MS)
        }
    }

    /**
     * 销毁 WebView 并释放内存。
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
 * 位置：tool/browser/BrowserScreenshotCapture.kt
 *
 * 将 WebView 内容捕获为 Bitmap 图像。
 */
class BrowserScreenshotCapture {

    companion object {
        private const val TAG = "BrowserScreenshotCapture"
    }

    /**
     * 捕获 WebView 的可见视口。
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
     * 捕获 WebView 的完整可滚动内容。
     * 高度上限为 maxHeight，防止 OOM。
     */
    suspend fun captureFullPage(webView: WebView, maxHeight: Int): Bitmap {
        return withContext(Dispatchers.Main) {
            val width = webView.width

            // 获取完整内容高度
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

            // 保存当前滚动位置
            val savedScrollX = webView.scrollX
            val savedScrollY = webView.scrollY

            // 滚动到顶部并绘制
            webView.scrollTo(0, 0)
            webView.draw(canvas)

            // 恢复滚动位置
            webView.scrollTo(savedScrollX, savedScrollY)

            bitmap
        }
    }
}
```

### BrowserContentExtractor

```kotlin
/**
 * 位置：tool/browser/BrowserContentExtractor.kt
 *
 * 使用 evaluateJavascript() 从已渲染的 WebView 页面中提取内容。
 * 使用注入 WebView 上下文的 Turndown 进行 HTML 到 Markdown 的转换。
 */
class BrowserContentExtractor(private val context: Context) {

    companion object {
        private const val TAG = "BrowserContentExtractor"
    }

    // 从 assets 中加载的内置提取脚本
    private val extractionScript: String by lazy {
        context.assets.open("js/browser/extract.js")
            .bufferedReader().use { it.readText() }
    }

    // 从 assets 中加载的 Turndown 库源码
    private val turndownSource: String by lazy {
        context.assets.open("js/lib/turndown.min.js")
            .bufferedReader().use { it.readText() }
    }

    /**
     * 使用内置提取脚本将页面内容提取为 Markdown。
     * 将 Turndown 注入页面上下文，以进行基于 DOM 的 HTML 到 Markdown 转换。
     */
    suspend fun extractAsMarkdown(webView: WebView): String {
        return withContext(Dispatchers.Main) {
            // 首先将 Turndown 注入页面上下文
            evaluateJs(webView, turndownSource)

            // 然后运行提取脚本
            val result = evaluateJs(webView, extractionScript)

            // 提取脚本返回 JSON 编码的字符串
            result.let {
                // evaluateJavascript 返回 JSON 编码的字符串（带有外层引号）
                if (it.startsWith("\"") && it.endsWith("\"")) {
                    // 反转义 JSON 字符串
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
     * 在 WebView 中执行自定义 JavaScript 并返回结果。
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
     * 在 WebView 中执行 JavaScript，并挂起直到结果返回。
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

### 内置提取脚本

```javascript
/**
 * 位置：assets/js/browser/extract.js
 *
 * 在 WebView 上下文中运行的内置内容提取脚本。
 * 使用预先注入的 Turndown 进行 HTML 到 Markdown 的转换。
 * 若 Turndown 不可用，则回退为 innerText 提取。
 */
(function() {
    // 需要移除的噪音选择器
    var noiseSelectors = [
        'script', 'style', 'noscript', 'svg', 'iframe',
        'nav', 'header', 'footer', 'aside',
        'form', 'button', 'input', 'select', 'textarea',
        '[role="navigation"]', '[role="banner"]', '[role="contentinfo"]',
        '.cookie-banner', '.popup', '.modal', '.advertisement', '.ad'
    ];

    // 克隆文档，避免修改实际页面
    var clone = document.documentElement.cloneNode(true);

    // 移除噪音元素
    noiseSelectors.forEach(function(selector) {
        try {
            var elements = clone.querySelectorAll(selector);
            elements.forEach(function(el) { el.remove(); });
        } catch(e) { /* 忽略无效选择器 */ }
    });

    // 查找主内容区域
    var content = clone.querySelector('article')
        || clone.querySelector('main')
        || clone.querySelector('[role="main"]')
        || clone.querySelector('body')
        || clone;

    // 获取标题
    var title = document.title || '';

    // 若 Turndown 可用则优先使用
    var markdown = '';
    if (typeof TurndownService !== 'undefined') {
        try {
            var td = new TurndownService({
                headingStyle: 'atx',
                codeBlockStyle: 'fenced',
                bulletListMarker: '-'
            });
            // 移除空链接和无 src 的图片
            td.addRule('removeEmptyLinks', {
                filter: function(node) {
                    return node.nodeName === 'A' && !node.textContent.trim();
                },
                replacement: function() { return ''; }
            });
            markdown = td.turndown(content.innerHTML);
        } catch(e) {
            // 回退为 innerText
            markdown = content.innerText || content.textContent || '';
        }
    } else {
        // Turndown 不可用，使用 innerText
        markdown = content.innerText || content.textContent || '';
    }

    // 若标题不在内容中，则在前面添加标题
    if (title && markdown.indexOf(title) === -1) {
        markdown = '# ' + title + '\n\n' + markdown;
    }

    // 清理：合并多个空行
    markdown = markdown.replace(/\n{3,}/g, '\n\n').trim();

    return markdown;
})();
```

### ToolModule 变更

```kotlin
// 在 ToolModule.kt 中

val toolModule = module {
    // ... 现有注册 ...

    // 浏览器工具组件
    single { BrowserScreenshotCapture() }
    single { BrowserContentExtractor(get()) }
    single { WebViewManager(get(), get(), get()) }
    single { BrowserTool(get(), get()) }

    // 在 ToolRegistry 中注册
    single {
        val registry = get<ToolRegistry>()
        // ... 现有工具注册 ...
        registry.register(get<WebfetchTool>())   // FEAT-021
        registry.register(get<BrowserTool>())     // FEAT-022
        // ...
        registry
    }
}
```

## 实施计划

### 阶段一：WebView 基础设施

1. 创建 `WebViewManager.kt`，实现生命周期管理
2. 创建 `BrowserScreenshotCapture.kt`，实现基于 Canvas 的捕获
3. 创建 WebView 生命周期的基本测试

### 阶段二：内容提取

1. 创建 `BrowserContentExtractor.kt`
2. 创建 `assets/js/browser/extract.js` 提取脚本
3. 在 WebView 上下文中测试 Turndown 提取效果

### 阶段三：BrowserTool 集成

1. 创建实现 `Tool` 接口的 `BrowserTool.kt`
2. 更新 `ToolModule.kt`，注册浏览器工具
3. 创建 `BrowserToolTest.kt`
4. 端到端测试

### 阶段四：测试与验证

1. 运行 Layer 1A 测试（`./gradlew test`）
2. 运行 Layer 1B 真机测试（WebView 需要 Android 上下文）
3. 使用真实 URL 进行手动测试（静态页面、SPA、JavaScript 密集型页面）
4. 内存分析，验证 WebView 清理效果

## 数据模型

无数据模型变更。`BrowserTool` 实现现有的 `Tool` 接口。

## API 设计

### 工具接口

```
工具名称：browser
参数：
  - url: string（必填）-- 要加载的 URL
  - mode: string（必填）-- "screenshot" 或 "extract"
  - width: integer（可选，默认值：412）-- 视口宽度（截图模式）
  - height: integer（可选，默认值：915）-- 视口高度（截图模式）
  - wait_seconds: number（可选，默认值：2）-- 页面加载后等待时间
  - full_page: boolean（可选，默认值：false）-- 全页截图
  - max_length: integer（可选，默认值：50000）-- 最大输出长度（提取模式）
  - javascript: string（可选）-- 要执行的自定义 JS（提取模式）

成功返回（截图模式）：
  JSON: {"image_path": "/path/to/screenshot.png"}

成功返回（提取模式）：
  提取内容的 Markdown 字符串

错误返回：
  ToolResult.error，附带描述性错误信息
```

## 错误处理

| 错误 | 原因 | 处理方式 |
|------|------|----------|
| URL 无效 | URL 格式错误或非 HTTP 协议 | `ToolResult.error("Invalid URL: ...")` |
| 模式无效 | 模式不是 "screenshot" 或 "extract" | `ToolResult.error("Invalid mode...")` |
| 页面加载错误 | 网络故障、DNS 错误、SSL 错误 | `ToolResult.error("Page load error: ...")`，通过 `onReceivedError` 触发 |
| 页面加载超时 | 页面始终未加载完成 | 工具级超时（60 秒）取消协程 |
| JS 执行错误 | 自定义 JS 抛出异常 | `ToolResult.error("JavaScript error: ...")` |
| 截图失败 | WebView 尺寸为零、Bitmap 分配失败 | `ToolResult.error("Screenshot failed: ...")` |
| WebView 不可用 | 系统 WebView 缺失 | `ToolResult.error("WebView not available...")` |
| 全页 OOM | 页面过长 | 高度上限为 10,000 像素 |

## 安全考量

1. **URL 协议限制**：仅接受 HTTP/HTTPS URL。`file://`、`content://`、`javascript:` 协议在工具层和 `WebViewClient.shouldOverrideUrlLoading` 中均被拒绝。
2. **禁止使用 `addJavascriptInterface`**：WebView 不向页面 JavaScript 暴露任何 Android 对象，页面无法访问 Android API。
3. **自定义 JS 沙箱**：`javascript` 参数在与页面脚本相同的沙箱中运行，可访问页面 DOM 但无法访问 Android 内部接口。
4. **状态隔离**：WebView 的缓存、Cookie 和历史记录在工具调用之间清除，防止跨调用的凭据泄漏。
5. **无持久存储**：`localStorage` 和 `sessionStorage` 在重置时清除。
6. **内容安全策略**：WebView 的 CSP 为页面自身的 CSP，工具不会削弱任何安全策略。
7. **混合内容**：`MIXED_CONTENT_COMPATIBILITY_MODE` 允许加载 HTTP/HTTPS 混合资源，以提升兼容性，与标准移动浏览器行为一致。

## 性能

| 操作 | 预期耗时 | 备注 |
|------|----------|------|
| WebView 创建（冷启动） | ~500ms | 每个会话的一次性开销 |
| 页面加载 | 1-5s | 取决于页面和网络 |
| 等待 JS 渲染 | 可配置（默认 2s） | 用户可控 |
| 视口截图捕获 | ~100ms | Canvas 绘制 + PNG 压缩 |
| 全页截图捕获 | ~500ms | 多次 Canvas 绘制 |
| 内容提取（Turndown） | ~200ms | Turndown 注入 + 执行 |
| 自定义 JS 执行 | < 100ms | 取决于脚本复杂度 |
| WebView 重置 | ~50ms | 清除历史 + 缓存 |

### 内存

| 资源 | 峰值占用 | 备注 |
|------|----------|------|
| WebView 进程 | ~50-100MB | 由 Android 管理，独立进程 |
| 视口 Bitmap（412x915，ARGB_8888） | ~1.5MB | 保存后回收 |
| 全页 Bitmap（412x10000） | ~16MB | 保存后回收 |
| 内存中的 Turndown.js | ~50KB | 每次提取时注入 |
| 截图 PNG 文件 | ~200KB-2MB | 保存至缓存目录 |

## 测试策略

### 单元测试

**BrowserToolTest.kt：**
- `testDefinition` -- 工具定义包含正确的名称、参数和必填字段
- `testExecute_invalidMode` -- 无效模式返回错误
- `testExecute_invalidUrl` -- 无效 URL 返回错误
- `testExecute_missingUrl` -- 缺少 URL 返回错误
- `testExecute_missingMode` -- 缺少模式返回错误

**WebViewManagerTest.kt**（真机测试，需要 Android 上下文）：
- `testGetOrCreateWebView` -- 以正确设置创建 WebView
- `testWebViewReuse` -- 第二次调用复用同一个 WebView 实例
- `testResetWebView` -- 两次使用之间清除状态
- `testDestroyWebView` -- WebView 被正确销毁
- `testIdleCleanup` -- 空闲超时后 WebView 被销毁

**BrowserContentExtractorTest.kt**（真机测试）：
- `testExtractAsMarkdown_simpleHtml` -- 基本 HTML 提取
- `testExtractAsMarkdown_withTurndown` -- Turndown 生成干净的 Markdown
- `testExecuteCustomJs_returnsString` -- 返回自定义 JS 结果
- `testExecuteCustomJs_error` -- 优雅处理 JS 错误

### 集成测试

手动验证：
- 简单静态页面截图
- JavaScript 密集型 SPA 截图（如 React 应用）
- 从静态页面提取内容（与 `webfetch` 输出对比）
- 从 SPA 提取内容
- 自定义 JavaScript 执行
- 长页面全页截图

## 已考虑的替代方案

### 1. 通过 Chrome DevTools Protocol 使用 Headless Chrome

**方案**：使用 Chrome DevTools Protocol 控制 Headless Chrome 实例。
**拒绝原因**：需要在设备上捆绑或安装 Chrome，大幅增加应用复杂度和体积。Android WebView 本身基于 Chromium，且在所有设备上均可用。

### 2. Selenium/Appium

**方案**：结合 Android WebView 使用 Selenium WebDriver。
**拒绝原因**：依赖项庞大，设计面向测试工作流而非运行时工具使用。WebView API 对我们的场景更简洁、更轻量。

### 3. 使用 PixelCopy API 截图

**方案**：使用 `PixelCopy.request()` 替代 Canvas 绘制。
**拒绝原因**：`PixelCopy` 需要 `Surface`（需附加到窗口），这意味着 WebView 必须在视图层级中。我们的离屏方案使用 Canvas 绘制，无需可见窗口即可工作。如有需要，后续可添加 `PixelCopy` 以获得更高质量的渲染。

### 4. 将截图和提取拆分为独立工具

**方案**：将 `browser_screenshot` 和 `browser_extract` 注册为两个独立工具。
**拒绝原因**：带 `mode` 参数的单个 `browser` 工具更简洁，避免工具注册表过于臃肿，并清晰表明两者共享同一底层 WebView 基础设施。AI 模型可以通过参数轻松选择模式。

## 依赖项

### 外部依赖

无新增外部依赖。使用：
- Android WebView（系统组件）
- Turndown.js（已随 FEAT-015 捆绑）
- OkHttpClient（已可用，但本工具不直接使用）

### 内部依赖

- `tool/` 包中的 `Tool` 接口
- `tool/` 包中的 `ToolResult`、`ToolDefinition`、`ToolParameters`
- Android `Context`（通过 Koin DI 注入）
- `assets/js/lib/turndown.min.js`（来自 FEAT-015）

## 变更记录

| 日期 | 版本 | 变更内容 | 负责人 |
|------|------|----------|--------|
| 2026-03-01 | 0.1 | 初始版本 | - |
