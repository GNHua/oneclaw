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

class GeminiAdapterTest {

    private lateinit var server: MockWebServer
    private lateinit var adapter: GeminiAdapter

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
        adapter = GeminiAdapter(OkHttpClient())
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    @Test
    fun `listModels filters to generateContent models and strips models prefix`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"models":[
                        {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","supportedGenerationMethods":["generateContent","countTokens"]},
                        {"name":"models/gemini-2.5-pro","displayName":"Gemini 2.5 Pro","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/embedding-001","displayName":"Embedding 001","supportedGenerationMethods":["embedContent"]}
                    ]}"""
                )
        )

        val result = adapter.listModels(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val models = (result as AppResult.Success).data
        // Only models with generateContent
        assertEquals(2, models.size)
        assertEquals("gemini-2.0-flash", models[0].id)  // "models/" prefix stripped
        assertEquals("Gemini 2.0 Flash", models[0].displayName)
        assertTrue(models.all { it.source == ModelSource.DYNAMIC })
    }

    @Test
    fun `listModels sends key as query parameter not header`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"models":[]}""")
        )

        adapter.listModels(server.url("/").toString().trimEnd('/'), "my-gemini-key")

        val request = server.takeRequest()
        assertTrue(request.requestUrl?.queryParameter("key") == "my-gemini-key")
        // No Authorization header
        assertTrue(request.getHeader("Authorization") == null)
    }

    @Test
    fun `listModels returns AUTH_ERROR on 400`() = runTest {
        server.enqueue(MockResponse().setResponseCode(400))

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
    fun `testConnection returns success with filtered model count`() = runTest {
        server.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(
                    """{"models":[
                        {"name":"models/gemini-2.0-flash","displayName":"Gemini 2.0 Flash","supportedGenerationMethods":["generateContent"]},
                        {"name":"models/embedding-001","displayName":"Embedding","supportedGenerationMethods":["embedContent"]}
                    ]}"""
                )
        )

        val result = adapter.testConnection(server.url("/").toString().trimEnd('/'), "test-key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertTrue(testResult.success)
        assertEquals(1, testResult.modelCount)  // Only the generateContent model
    }

    @Test
    fun `testConnection returns NETWORK_FAILURE type in result on unknown host`() = runTest {
        // Use an invalid URL to trigger UnknownHostException
        val result = adapter.testConnection("https://this-host-does-not-exist-xyz.invalid", "key")

        assertTrue(result is AppResult.Success)
        val testResult = (result as AppResult.Success).data
        assertFalse(testResult.success)
        assertEquals(ConnectionErrorType.NETWORK_FAILURE, testResult.errorType)
    }
}
