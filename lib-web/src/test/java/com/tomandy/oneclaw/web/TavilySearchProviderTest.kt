package com.tomandy.oneclaw.web

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

class TavilySearchProviderTest {

    private lateinit var server: MockWebServer
    private lateinit var client: OkHttpClient
    private lateinit var provider: TavilySearchProvider

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = OkHttpClient.Builder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        provider = TavilySearchProvider(
            httpClient = client,
            apiKey = "test-key",
            baseUrl = server.url("").toString().trimEnd('/')
        )
    }

    @After
    fun tearDown() {
        server.shutdown()
    }

    @Test
    fun `search parses response correctly`() = runTest {
        val responseJson = """
            {
                "results": [
                    {
                        "title": "Result One",
                        "url": "https://example.com/1",
                        "content": "First result snippet."
                    },
                    {
                        "title": "Result Two",
                        "url": "https://example.com/2",
                        "content": "Second result snippet."
                    }
                ]
            }
        """.trimIndent()

        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody(responseJson)
        )

        val results = provider.search("test query", 5)

        assertEquals(2, results.size)
        assertEquals("Result One", results[0].title)
        assertEquals("https://example.com/1", results[0].url)
        assertEquals("First result snippet.", results[0].snippet)
        assertEquals("Result Two", results[1].title)
    }

    @Test
    fun `search sends correct authorization header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"results": []}""")
        )

        provider.search("test", 3)

        val request = server.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertEquals("POST", request.method)
    }

    @Test
    fun `search returns empty list when no results`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .addHeader("Content-Type", "application/json")
                .setBody("""{"results": []}""")
        )

        val results = provider.search("obscure query", 5)
        assertTrue(results.isEmpty())
    }

    @Test(expected = IllegalStateException::class)
    fun `search throws on API error`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("Unauthorized")
        )
        provider.search("test", 5)
    }
}
