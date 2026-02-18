package com.tomandy.palmclaw.web

import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

class ContentExtractorTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var extractor: ContentExtractor

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        extractor = ContentExtractor(client)
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `extract returns title and body text`() = runTest {
        val html = """
            <html>
            <head><title>Test Page</title></head>
            <body>
                <article>
                    <p>Hello world content here.</p>
                </article>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(server.url("/page").toString())
        assertEquals("Test Page", result.title)
        assertTrue(result.text.contains("Hello world content here."))
    }

    @Test
    fun `extract strips script and style tags`() = runTest {
        val html = """
            <html>
            <head><title>Clean Test</title></head>
            <body>
                <script>var x = 1;</script>
                <style>.foo { color: red; }</style>
                <article><p>Visible text only.</p></article>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(server.url("/page").toString())
        assertTrue(result.text.contains("Visible text only."))
        assertTrue(!result.text.contains("var x = 1"))
        assertTrue(!result.text.contains("color: red"))
    }

    @Test
    fun `extract strips nav and footer`() = runTest {
        val html = """
            <html>
            <head><title>Nav Test</title></head>
            <body>
                <nav><a href="/">Home</a></nav>
                <main><p>Main content here.</p></main>
                <footer><p>Copyright 2026</p></footer>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(server.url("/page").toString())
        assertTrue(result.text.contains("Main content here."))
        assertTrue(!result.text.contains("Home"))
        assertTrue(!result.text.contains("Copyright"))
    }

    @Test
    fun `extract uses CSS selector when provided`() = runTest {
        val html = """
            <html>
            <head><title>Selector Test</title></head>
            <body>
                <div class="sidebar">Sidebar content</div>
                <div class="article-body">Article body text here.</div>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(
            server.url("/page").toString(),
            selector = ".article-body"
        )
        assertEquals("Article body text here.", result.text)
    }

    @Test
    fun `extract truncates long content`() = runTest {
        val longText = "a".repeat(20_000)
        val html = """
            <html>
            <head><title>Long</title></head>
            <body><article><p>$longText</p></article></body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(server.url("/page").toString())
        assertTrue(result.text.length < 20_000)
        assertTrue(result.text.contains("[Content truncated"))
    }

    @Test
    fun `extract prefers article over body`() = runTest {
        val html = """
            <html>
            <head><title>Priority Test</title></head>
            <body>
                <div>Other stuff</div>
                <article><p>Article content</p></article>
            </body>
            </html>
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/html")
                .setBody(html)
        )

        val result = extractor.extract(server.url("/page").toString())
        assertEquals("Article content", result.text)
    }

    @Test
    fun `extract handles plain text content type`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "text/plain")
                .setBody("Just plain text.")
        )

        val result = extractor.extract(server.url("/page").toString())
        assertTrue(result.text.contains("Just plain text."))
    }

    @Test(expected = IllegalStateException::class)
    fun `extract throws on HTTP error`() = runTest {
        server.enqueue(MockResponse().setResponseCode(404))
        extractor.extract(server.url("/missing").toString())
    }
}
