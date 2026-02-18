package com.tomandy.oneclaw.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.jsoup.Jsoup
import org.jsoup.nodes.Document

class ContentExtractor(private val httpClient: OkHttpClient) {

    companion object {
        private const val MAX_CONTENT_LENGTH = 15_000
        private val BOILERPLATE_TAGS = setOf(
            "script", "style", "nav", "footer", "header",
            "aside", "noscript", "iframe", "svg"
        )
        private val USER_AGENT =
            "Mozilla/5.0 (compatible; OneClaw/1.0; +https://oneclaw.app)"
    }

    suspend fun extract(url: String, selector: String? = null): ExtractedContent =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(url)
                .header("User-Agent", USER_AGENT)
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val body = response.body?.string()
                ?: throw IllegalStateException("Empty response from $url")

            if (!response.isSuccessful) {
                throw IllegalStateException("HTTP ${response.code} fetching $url")
            }

            val contentType = response.header("Content-Type") ?: ""
            if (!contentType.contains("text/html", ignoreCase = true) &&
                !contentType.contains("text/plain", ignoreCase = true) &&
                !contentType.contains("application/xhtml", ignoreCase = true)
            ) {
                // Return raw text for non-HTML content types
                return@withContext ExtractedContent(
                    title = url,
                    text = body.take(MAX_CONTENT_LENGTH)
                )
            }

            val doc = Jsoup.parse(body, url)
            extractFromDocument(doc, selector)
        }

    fun extractFromDocument(doc: Document, selector: String? = null): ExtractedContent {
        // Remove boilerplate elements
        BOILERPLATE_TAGS.forEach { tag -> doc.select(tag).remove() }

        val title = doc.title().ifBlank { doc.location() }

        val contentElement = if (selector != null) {
            doc.select(selector).first()
        } else {
            // Auto-detect main content area
            doc.selectFirst("article")
                ?: doc.selectFirst("main")
                ?: doc.selectFirst("[role=main]")
                ?: doc.body()
        }

        val text = contentElement?.text()?.trim() ?: ""
        val truncated = if (text.length > MAX_CONTENT_LENGTH) {
            text.take(MAX_CONTENT_LENGTH) + "\n\n[Content truncated at ${MAX_CONTENT_LENGTH} characters]"
        } else {
            text
        }

        return ExtractedContent(title = title, text = truncated)
    }
}

data class ExtractedContent(
    val title: String,
    val text: String
)
