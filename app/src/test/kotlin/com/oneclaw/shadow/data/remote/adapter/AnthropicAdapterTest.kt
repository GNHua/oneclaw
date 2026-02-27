package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.ConnectionErrorType
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AnthropicAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: AnthropicAdapter

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        adapter = AnthropicAdapter(OkHttpClient())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `listModels parses response and returns only type=model items`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[
                        {"id":"claude-sonnet-4-20250514","display_name":"Claude Sonnet 4","type":"model"},
                        {"id":"claude-haiku-4-20250414","display_name":"Claude Haiku 4","type":"model"},
                        {"id":"deprecated-model","display_name":"Old","type":"other"}
                    ]}"""
                )
        )

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        assertEquals(2, models.size)
        assertEquals("claude-sonnet-4-20250514", models[0].id)
        assertEquals("Claude Sonnet 4", models[0].displayName)
        assertTrue(models.all { it.source == ModelSource.DYNAMIC })
    }

    @Test
    fun `listModels sends correct Anthropic headers`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"data":[]}""")
        )

        adapter.listModels(server.url("/").toString().trimEnd('/'), "sk-ant-test")

        val request = server.takeRequest()
        assertEquals("sk-ant-test", request.getHeader("x-api-key"))
        assertEquals("2023-06-01", request.getHeader("anthropic-version"))
    }

    @Test
    fun `listModels returns AUTH_ERROR on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "bad-key")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.AUTH_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `testConnection returns success`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[
                        {"id":"claude-sonnet-4-20250514","display_name":"Claude Sonnet 4","type":"model"}
                    ]}"""
                )
        )

        val result = adapter.testConnection(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertTrue(testResult.success)
        assertEquals(1, testResult.modelCount)
    }

    @Test
    fun `testConnection returns AUTH_FAILURE on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = adapter.testConnection(server.url("/").toString().trimEnd('/'), "bad-key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertFalse(testResult.success)
        assertEquals(ConnectionErrorType.AUTH_FAILURE, testResult.errorType)
    }
}
