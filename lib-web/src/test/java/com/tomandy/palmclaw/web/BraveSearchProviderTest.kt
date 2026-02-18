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

class BraveSearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var provider: BraveSearchProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        provider = BraveSearchProvider(
            httpClient = client,
            apiKey = "test-brave-key",
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses Brave response correctly`() = runTest {
        val responseJson = """
            {
                "web": {
                    "results": [
                        {
                            "title": "Brave Result",
                            "url": "https://brave.com/1",
                            "description": "A brave search result."
                        }
                    ]
                }
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )

        val results = provider.search("brave test", 5)

        assertEquals(1, results.size)
        assertEquals("Brave Result", results[0].title)
        assertEquals("https://brave.com/1", results[0].url)
        assertEquals("A brave search result.", results[0].snippet)
    }

    @Test
    fun `search sends subscription token header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"web": {"results": []}}""")
        )

        provider.search("test", 3)

        val request = server.takeRequest()
        assertEquals("test-brave-key", request.getHeader("X-Subscription-Token"))
        assertEquals("GET", request.method)
    }

    @Test
    fun `search URL encodes query`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"web": {"results": []}}""")
        )

        provider.search("hello world", 3)

        val request = server.takeRequest()
        assertTrue(request.path!!.contains("q=hello+world") || request.path!!.contains("q=hello%20world"))
    }

    @Test
    fun `search returns empty list when no web results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"web": {"results": []}}""")
        )

        val results = provider.search("nothing", 5)
        assertTrue(results.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `search throws on API error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .setBody("Forbidden")
        )
        provider.search("test", 5)
    }
}
