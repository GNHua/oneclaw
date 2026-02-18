package com.tomandy.oneclaw.llm

import kotlinx.coroutines.test.runTest
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class OpenAiClientTest {
    private lateinit var mockWebServer: MockWebServer
    private lateinit var client: OpenAiClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = OpenAiClient(
            apiKey = "test-key",
            baseUrl = mockWebServer.url("/").toString()
        )
    }

    @After
    fun teardown() {
        mockWebServer.shutdown()
    }

    @Test
    fun `successful completion request`() = runTest {
        val mockResponse = LlmResponse(
            id = "chatcmpl-123",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(
                        role = "assistant",
                        content = "Hello! How can I help you today?",
                        tool_calls = null
                    ),
                    finish_reason = "stop"
                )
            ),
            usage = Usage(
                prompt_tokens = 10,
                completion_tokens = 9,
                total_tokens = 19
            )
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Json.encodeToString(mockResponse))
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isSuccess)
        val response = requireNotNull(result.getOrNull())
        assertEquals("chatcmpl-123", response.id)
        assertEquals(1, response.choices.size)
        assertEquals("Hello! How can I help you today?", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finish_reason)

        // Verify request headers
        val request = mockWebServer.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("/chat/completions", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun `completion request with tools`() = runTest {
        val mockResponse = LlmResponse(
            id = "chatcmpl-456",
            choices = listOf(
                Choice(
                    index = 0,
                    message = MessageResponse(
                        role = "assistant",
                        content = null,
                        tool_calls = listOf(
                            ToolCall(
                                id = "call_abc123",
                                type = "function",
                                function = FunctionCall(
                                    name = "get_weather",
                                    arguments = "{\"location\":\"San Francisco\"}"
                                )
                            )
                        )
                    ),
                    finish_reason = "tool_calls"
                )
            ),
            usage = Usage(
                prompt_tokens = 50,
                completion_tokens = 20,
                total_tokens = 70
            )
        )

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(Json.encodeToString(mockResponse))
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "What's the weather in San Francisco?"))
        val tools = listOf(
            Tool(
                type = "function",
                function = ToolFunction(
                    name = "get_weather",
                    description = "Get the current weather",
                    parameters = kotlinx.serialization.json.buildJsonObject {
                        put("type", kotlinx.serialization.json.JsonPrimitive("object"))
                    }
                )
            )
        )

        val result = client.complete(messages = messages, tools = tools)

        assertTrue(result.isSuccess)
        val response = requireNotNull(result.getOrNull())
        assertEquals("chatcmpl-456", response.id)
        assertEquals("tool_calls", response.choices[0].finish_reason)

        val toolCalls = requireNotNull(response.choices[0].message.tool_calls)
        assertEquals(1, toolCalls.size)
        assertEquals("get_weather", toolCalls[0].function.name)
        assertEquals("{\"location\":\"San Francisco\"}", toolCalls[0].function.arguments)
    }

    @Test
    fun `unauthorized error 401`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(401)
                .setBody("""{"error":{"message":"Invalid API key","type":"invalid_request_error"}}""")
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Unauthorized"))
    }

    @Test
    fun `rate limit error 429`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(429)
                .setBody("""{"error":{"message":"Rate limit exceeded","type":"rate_limit_error"}}""")
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Rate limit exceeded"))
    }

    @Test
    fun `server error 500`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(500)
                .setBody("""{"error":{"message":"Internal server error","type":"server_error"}}""")
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Server error"))
    }

    @Test
    fun `service unavailable 503`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(503)
                .setBody("Service temporarily unavailable")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Service unavailable"))
    }

    @Test
    fun `network error handling`() = runTest {
        // Don't enqueue any response - this will cause a connection error
        mockWebServer.shutdown()

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Network error") || error.message!!.contains("error"))
    }

    @Test
    fun `verify request body serialization`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","choices":[],"usage":null}""")
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(
            Message(role = "system", content = "You are a helpful assistant"),
            Message(role = "user", content = "Hello")
        )

        client.complete(
            messages = messages,
            model = "gpt-4o-mini",
            temperature = 0.5f,
            maxTokens = 100
        )

        val request = mockWebServer.takeRequest()
        val requestBody = request.body.readUtf8()

        assertTrue(requestBody.contains("\"model\":\"gpt-4o-mini\""))
        assertTrue(requestBody.contains("\"temperature\":0.5"))
        assertTrue(requestBody.contains("\"max_tokens\":100"))
        assertTrue(requestBody.contains("\"role\":\"system\""))
        assertTrue(requestBody.contains("\"role\":\"user\""))
        assertTrue(requestBody.contains("You are a helpful assistant"))
        assertTrue(requestBody.contains("Hello"))
        // Note: stream field is not included because encodeDefaults=false and stream=false is the default
    }

    @Test
    fun `setApiKey updates client configuration`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"test"},"finish_reason":"stop"}]}""")
                .addHeader("Content-Type", "application/json")
        )

        client.setApiKey("new-api-key")

        val messages = listOf(Message(role = "user", content = "Test"))
        client.complete(messages = messages)

        val request = mockWebServer.takeRequest()
        assertEquals("Bearer new-api-key", request.getHeader("Authorization"))
    }

    @Test
    fun `setBaseUrl updates client configuration`() = runTest {
        val newServer = MockWebServer()
        newServer.start()

        newServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"test"},"finish_reason":"stop"}]}""")
                .addHeader("Content-Type", "application/json")
        )

        client.setBaseUrl(newServer.url("/").toString())

        val messages = listOf(Message(role = "user", content = "Test"))
        val result = client.complete(messages = messages)

        assertTrue(result.isSuccess)
        assertEquals(1, newServer.requestCount)

        newServer.shutdown()
    }

    @Test
    fun `setBaseUrl adds trailing slash if missing`() = runTest {
        val newServer = MockWebServer()
        newServer.start()

        newServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","choices":[{"index":0,"message":{"role":"assistant","content":"test"},"finish_reason":"stop"}]}""")
                .addHeader("Content-Type", "application/json")
        )

        // Set base URL without trailing slash
        val baseUrlWithoutSlash = newServer.url("/").toString().trimEnd('/')
        client.setBaseUrl(baseUrlWithoutSlash)

        val messages = listOf(Message(role = "user", content = "Test"))
        val result = client.complete(messages = messages)

        assertTrue(result.isSuccess)

        newServer.shutdown()
    }

    @Test
    fun `invalid JSON response handling`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("Not valid JSON")
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        assertFalse(result.isSuccess)
    }

    @Test
    fun `response with unknown fields is parsed successfully`() = runTest {
        val responseWithExtraFields = """
        {
            "id": "chatcmpl-789",
            "object": "chat.completion",
            "created": 1677652288,
            "model": "gpt-4o-mini",
            "choices": [
                {
                    "index": 0,
                    "message": {
                        "role": "assistant",
                        "content": "Test response"
                    },
                    "finish_reason": "stop",
                    "logprobs": null
                }
            ],
            "usage": {
                "prompt_tokens": 5,
                "completion_tokens": 2,
                "total_tokens": 7
            },
            "system_fingerprint": "fp_123"
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseWithExtraFields)
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isSuccess)
        val response = requireNotNull(result.getOrNull())
        assertEquals("chatcmpl-789", response.id)
        assertEquals("Test response", response.choices[0].message.content)
    }
}
