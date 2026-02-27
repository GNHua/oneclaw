package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class HttpRequestToolTest {

    private val server = MockWebServer()
    private lateinit var tool: HttpRequestTool

    @BeforeEach
    fun setup() {
        server.start()
        tool = HttpRequestTool(OkHttpClient())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `execute GET request returns response body`() = runTest {
        server.enqueue(MockResponse().setBody("response body").setResponseCode(200))

        val result = tool.execute(mapOf("url" to server.url("/test").toString()))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("200"))
        assertTrue(result.result!!.contains("response body"))
    }

    @Test
    fun `execute POST request sends body`() = runTest {
        server.enqueue(MockResponse().setBody("created").setResponseCode(201))

        val result = tool.execute(mapOf(
            "url" to server.url("/create").toString(),
            "method" to "POST",
            "body" to """{"key":"value"}"""
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val request = server.takeRequest()
        assertEquals("POST", request.method)
        assertEquals("""{"key":"value"}""", request.body.readUtf8())
    }

    @Test
    fun `execute sends custom headers`() = runTest {
        server.enqueue(MockResponse().setBody("ok").setResponseCode(200))

        tool.execute(mapOf(
            "url" to server.url("/test").toString(),
            "headers" to mapOf("X-Custom" to "test-value")
        ))

        val request = server.takeRequest()
        assertEquals("test-value", request.getHeader("X-Custom"))
    }

    @Test
    fun `execute returns validation_error for missing url`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns validation_error for invalid url`() = runTest {
        val result = tool.execute(mapOf("url" to "not a url"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute returns network_error for unreachable host`() = runTest {
        val result = tool.execute(mapOf("url" to "http://this-host-does-not-exist-xyz.invalid/"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("network_error", result.errorType)
    }

    @Test
    fun `execute truncates large response bodies`() = runTest {
        val bigBody = "x".repeat(150 * 1024)  // 150KB > 100KB limit
        server.enqueue(MockResponse().setBody(bigBody).setResponseCode(200))

        val result = tool.execute(mapOf("url" to server.url("/big").toString()))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("truncated"))
    }

    @Test
    fun `execute returns validation_error for unsupported HTTP method`() = runTest {
        val result = tool.execute(mapOf(
            "url" to server.url("/test").toString(),
            "method" to "PATCH"
        ))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun `execute DELETE request succeeds`() = runTest {
        server.enqueue(MockResponse().setResponseCode(204))

        val result = tool.execute(mapOf(
            "url" to server.url("/item/1").toString(),
            "method" to "DELETE"
        ))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        val request = server.takeRequest()
        assertEquals("DELETE", request.method)
    }
}
