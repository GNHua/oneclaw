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

class OpenAiAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: OpenAiAdapter

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        adapter = OpenAiAdapter(OkHttpClient())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `listModels returns chat models filtered from full list`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[
                        {"id":"gpt-4o","owned_by":"openai"},
                        {"id":"gpt-4o-mini","owned_by":"openai"},
                        {"id":"text-embedding-3-small","owned_by":"openai"},
                        {"id":"whisper-1","owned_by":"openai"},
                        {"id":"o1","owned_by":"openai"}
                    ]}"""
                )
        )

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        // Should only contain gpt-4o, gpt-4o-mini, o1 (not embedding or whisper)
        assertEquals(3, models.size)
        assertTrue(models.all { it.source == ModelSource.DYNAMIC })
        assertTrue(models.all { it.providerId == "" })
    }

    @Test
    fun `listModels returns AUTH_ERROR on 401`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Incorrect API key"}}""")
        )

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "bad-key")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.AUTH_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `listModels returns AUTH_ERROR on 403`() = runTest {
        server.enqueue(MockResponse().setResponseCode(403))

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "bad-key")

        assertTrue(result is AppResult.Error)
        assertEquals(ErrorCode.AUTH_ERROR, (result as AppResult.Error).code)
    }

    @Test
    fun `testConnection returns success with model count`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"data":[
                        {"id":"gpt-4o","owned_by":"openai"},
                        {"id":"gpt-4o-mini","owned_by":"openai"}
                    ]}"""
                )
        )

        val result = adapter.testConnection(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertTrue(testResult.success)
        assertEquals(2, testResult.modelCount)
    }

    @Test
    fun `testConnection returns ConnectionTestResult with AUTH_FAILURE on 401`() = runTest {
        server.enqueue(MockResponse().setResponseCode(401))

        val result = adapter.testConnection(server.url("/").toString().trimEnd('/'), "bad-key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertFalse(testResult.success)
        assertEquals(ConnectionErrorType.AUTH_FAILURE, testResult.errorType)
    }

    @Test
    fun `sendMessageStream throws NotImplementedError`() {
        val thrown = try {
            adapter.sendMessageStream("url", "key", "gpt-4o", emptyList(), null, null)
            null
        } catch (e: NotImplementedError) {
            e
        }
        assertTrue(thrown != null)
    }
}
