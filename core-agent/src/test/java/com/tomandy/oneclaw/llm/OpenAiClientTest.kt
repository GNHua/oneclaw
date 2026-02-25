package com.tomandy.oneclaw.llm

import kotlinx.coroutines.test.runTest
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
    private lateinit var client: OpenAiResponsesClient

    @Before
    fun setup() {
        mockWebServer = MockWebServer()
        mockWebServer.start()
        client = OpenAiResponsesClient(
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
        val responseJson = """
        {
            "id": "resp-123",
            "output": [
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {
                            "type": "output_text",
                            "text": "Hello! How can I help you today?"
                        }
                    ]
                }
            ],
            "usage": {
                "input_tokens": 10,
                "output_tokens": 9
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isSuccess)
        val response = requireNotNull(result.getOrNull())
        assertEquals("resp-123", response.id)
        assertEquals(1, response.choices.size)
        assertEquals("Hello! How can I help you today?", response.choices[0].message.content)
        assertEquals("stop", response.choices[0].finish_reason)

        val request = mockWebServer.takeRequest()
        assertEquals("Bearer test-key", request.getHeader("Authorization"))
        assertTrue(request.getHeader("Content-Type")!!.startsWith("application/json"))
        assertEquals("/responses", request.path)
        assertEquals("POST", request.method)
    }

    @Test
    fun `completion request with tool calls`() = runTest {
        val responseJson = """
        {
            "id": "resp-456",
            "output": [
                {
                    "type": "function_call",
                    "call_id": "call_abc123",
                    "name": "get_weather",
                    "arguments": "{\"location\":\"San Francisco\"}"
                }
            ],
            "usage": {
                "input_tokens": 50,
                "output_tokens": 20
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
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
        assertEquals("resp-456", response.id)
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
        mockWebServer.shutdown()

        val messages = listOf(Message(role = "user", content = "Hello"))
        val result = client.complete(messages = messages)

        assertTrue(result.isFailure)
        val error = requireNotNull(result.exceptionOrNull())
        assertTrue(error.message!!.contains("Network error") || error.message!!.contains("error"))
    }

    @Test
    fun `verify request body uses Responses API format`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","output":[],"usage":{"input_tokens":0,"output_tokens":0}}""")
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
        assertTrue(requestBody.contains("\"max_output_tokens\":100"))
        // System message becomes developer role in Responses API
        assertTrue(requestBody.contains("\"role\":\"developer\""))
        assertTrue(requestBody.contains("\"role\":\"user\""))
        assertTrue(requestBody.contains("You are a helpful assistant"))
        assertTrue(requestBody.contains("Hello"))
        assertTrue(requestBody.contains("\"input\""))
    }

    @Test
    fun `setApiKey updates client configuration`() = runTest {
        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody("""{"id":"test","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"test"}]}]}""")
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
                .setBody("""{"id":"test","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"test"}]}]}""")
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
                .setBody("""{"id":"test","output":[{"type":"message","role":"assistant","content":[{"type":"output_text","text":"test"}]}]}""")
                .addHeader("Content-Type", "application/json")
        )

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
    fun `response with web search citations`() = runTest {
        val responseJson = """
        {
            "id": "resp-789",
            "output": [
                {
                    "type": "web_search_call",
                    "id": "ws_123",
                    "status": "completed"
                },
                {
                    "type": "message",
                    "role": "assistant",
                    "content": [
                        {
                            "type": "output_text",
                            "text": "The latest news is...",
                            "annotations": [
                                {
                                    "type": "url_citation",
                                    "url": "https://example.com/news",
                                    "title": "Example News"
                                }
                            ]
                        }
                    ]
                }
            ],
            "usage": {
                "input_tokens": 20,
                "output_tokens": 15
            }
        }
        """.trimIndent()

        mockWebServer.enqueue(
            MockResponse()
                .setResponseCode(200)
                .setBody(responseJson)
                .addHeader("Content-Type", "application/json")
        )

        val messages = listOf(Message(role = "user", content = "What's in the news?"))
        val result = client.complete(messages = messages, enableWebSearch = true)

        assertTrue(result.isSuccess)
        val response = requireNotNull(result.getOrNull())
        assertTrue(response.choices[0].message.content!!.contains("The latest news is..."))
        assertTrue(response.choices[0].message.content!!.contains("[Example News](https://example.com/news)"))
        assertEquals("stop", response.choices[0].finish_reason)
    }
}
