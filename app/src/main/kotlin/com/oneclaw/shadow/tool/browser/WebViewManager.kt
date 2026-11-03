package com.oneclaw.shadow.tool.browser

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Located in: tool/browser/WebViewManager.kt
 *
 * DULU: Manages WebView for screenshot (Bitmap PNG)
 * SEKARANG: Manages WebView for screen record (Video MP4)
 */
class WebViewManager(
    private val context: Context,
    private val screenshotCapture: BrowserScreenshotCapture,
    private val contentExtractor: BrowserContentExtractor
) {
    companion object {
        private const val TAG = "WebViewManager"
        private const val IDLE_TIMEOUT_MS = 5 * 60 * 1000L
        private const val MAX_FULL_PAGE_HEIGHT = 10_000
        private const val DEFAULT_WIDTH = 412
        private const val DEFAULT_HEIGHT = 915
    }

    private var webView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private var idleCleanupRunnable: Runnable? = null

    /**
     * DULU: captureScreenshot -> PNG
     * SEKARANG: captureScreenshot -> MP4 (screen record)
     */
    suspend fun captureScreenshot(
        url: String,
        width: Int,
        height: Int,
        waitSeconds: Double,
        fullPage: Boolean
    ): String {
        val wv = getOrCreateWebView(width, height)

        loadUrlAndWait(wv, url)
        delay((waitSeconds * 1000).toLong())

        // output jadi mp4, bukan png
        val outputFile = File(context.cacheDir, "browser_record_${System.currentTimeMillis()}.mp4")

        val videoFile = if (fullPage) {
            screenshotCapture.captureFullPage(wv, outputFile, MAX_FULL_PAGE_HEIGHT)
        } else {
            screenshotCapture.captureViewport(wv, outputFile)
        }

        scheduleIdleCleanup()
        resetWebView(wv)

        return videoFile.absolutePath
    }

    suspend fun extractContent(
        url: String,
        waitSeconds: Double,
        customJavascript: String?,
        maxLength: Int
    ): String {
        val wv = getOrCreateWebView(DEFAULT_WIDTH, DEFAULT_HEIGHT)
        loadUrlAndWait(wv, url)
        delay((waitSeconds * 1000).toLong())

        val content = if (customJavascript != null) {
            contentExtractor.executeCustomJs(wv, customJavascript)
        } else {
            contentExtractor.extractAsMarkdown(wv)
        }

        val result = if (maxLength > 0 && content.length > maxLength) {
            val truncateAt = content.lastIndexOf("\n\n", maxLength)
            val cutoff = if (truncateAt > maxLength / 2) truncateAt else maxLength
            content.substring(0, cutoff) + "\n\n[Content truncated at $maxLength characters]"
        } else {
            content
        }

        scheduleIdleCleanup()
        resetWebView(wv)

        return result
    }

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
                        builtInZoomControls = false
                        displayZoomControls = false
                        mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                        userAgentString = "Mozilla/5.0 (Linux; Android 14) " +
                            "AppleWebKit/537.36 (KHTML, like Gecko) " +
                            "Chrome/120.0.0.0 Mobile Safari/537.36"
                    }

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

    private suspend fun resetWebView(wv: WebView) {
        withContext(Dispatchers.Main) {
            wv.loadUrl("about:blank")
            wv.clearHistory()
            wv.clearCache(false)
            CookieManager.getInstance().removeAllCookies(null)
        }
    }

    private fun scheduleIdleCleanup() {
        idleCleanupRunnable?.let { mainHandler.removeCallbacks(it) }
        idleCleanupRunnable = Runnable { destroyWebView() }.also {
            mainHandler.postDelayed(it, IDLE_TIMEOUT_MS)
        }
    }

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
}
