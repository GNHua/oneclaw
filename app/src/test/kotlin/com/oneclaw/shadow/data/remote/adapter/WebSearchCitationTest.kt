package com.oneclaw.shadow.data.remote.adapter

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * RFC-031: Unit tests for citation parsing from all three provider adapters.
 *
 * SSE format reminder: each event must be followed by a blank line.
 */
class WebSearchCitationTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    // -------------------------------------------------------------------------
    // OpenAI annotation-based citations
    // -------------------------------------------------------------------------

    @Test
    fun `OpenAI - web_search_options is included in request body when webSearchEnabled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(openAiSseResponse()))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gpt-4o",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("web_search_options"), "Request should contain web_search_options")
    }

    @Test
    fun `OpenAI - web_search_options is absent when webSearchEnabled is false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(openAiSseResponse()))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gpt-4o",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = false
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(!body.contains("web_search_options"), "Request should NOT contain web_search_options")
    }

    @Test
    fun `OpenAI - citations emitted from url_citation annotations`() = runTest {
        // Proper SSE format: data: followed by blank line
        val sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\",\"annotations\":[{\"type\":\"url_citation\",\"url\":\"https://example.com/article\",\"title\":\"Example Article\"}]}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gpt-4o",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val citationEvent = events.filterIsInstance<StreamEvent.Citations>().firstOrNull()
        assertNotNull(citationEvent, "Expected a Citations event")
        assertEquals(1, citationEvent!!.citations.size)
        assertEquals("https://example.com/article", citationEvent.citations[0].url)
        assertEquals("Example Article", citationEvent.citations[0].title)
        assertEquals("example.com", citationEvent.citations[0].domain)
    }

    @Test
    fun `OpenAI - duplicate citations are deduplicated`() = runTest {
        // Both annotations in the same chunk (single SSE event)
        val annotationsJson = "[{\"type\":\"url_citation\",\"url\":\"https://dup.com\",\"title\":\"Dup\"},{\"type\":\"url_citation\",\"url\":\"https://dup.com\",\"title\":\"Dup\"}]"
        val sseBody = "data: {\"choices\":[{\"delta\":{\"content\":\"x\",\"annotations\":$annotationsJson}}]}\n\ndata: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gpt-4o",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val citationEvents = events.filterIsInstance<StreamEvent.Citations>()
        val allCitations = citationEvents.flatMap { it.citations }
        assertEquals(1, allCitations.size, "Duplicate URLs should be deduplicated")
    }

    @Test
    fun `OpenAI - no citations event emitted when no annotations present`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(openAiSseResponse()))

        val adapter = OpenAiAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gpt-4o",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val citationEvents = events.filterIsInstance<StreamEvent.Citations>()
        assertTrue(citationEvents.isEmpty(), "No Citations events should be emitted without annotations")
    }

    // -------------------------------------------------------------------------
    // Anthropic web_search tool citations
    // -------------------------------------------------------------------------

    @Test
    fun `Anthropic - web_search tool is included in request body when webSearchEnabled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(anthropicSseResponse()))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "claude-sonnet-4-20250514",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        val betaHeader = request.getHeader("anthropic-beta") ?: ""
        assertTrue(body.contains("web_search_20250305"), "Request should contain web_search_20250305 tool type, body=$body")
        assertTrue(betaHeader.contains("web-search"), "Anthropic-Beta header should include web-search, got: $betaHeader")
    }

    @Test
    fun `Anthropic - web_search_enabled false omits web search tool`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(anthropicSseResponse()))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "claude-sonnet-4-20250514",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = false
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(!body.contains("web_search_20250305"), "Should NOT contain web search tool when disabled")
        assertEquals("interleaved-thinking-2025-05-14", request.getHeader("anthropic-beta"))
    }

    @Test
    fun `Anthropic - WebSearchStart emitted on server_tool_use block_start`() = runTest {
        val sseBody = buildString {
            append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n")
            append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"server_tool_use\",\"id\":\"srvtool_1\",\"name\":\"web_search\"}}\n\n")
            append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "claude-sonnet-4-20250514",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val webSearchStartEvent = events.filterIsInstance<StreamEvent.WebSearchStart>().firstOrNull()
        assertNotNull(webSearchStartEvent, "Expected a WebSearchStart event")
    }

    @Test
    fun `Anthropic - citations emitted from web_search_tool_result block`() = runTest {
        val searchResults = "[{\"type\":\"web_search_result\",\"url\":\"https://anthropic.com\",\"title\":\"Anthropic\"},{\"type\":\"web_search_result\",\"url\":\"https://claude.ai\",\"title\":\"Claude\"}]"
        val sseBody = buildString {
            append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":10,\"output_tokens\":0}}}\n\n")
            append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"web_search_tool_result\",\"tool_use_id\":\"srvtool_1\",\"content\":$searchResults}}\n\n")
            append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
            append("data: {\"type\":\"message_stop\"}\n\n")
        }
        server.enqueue(MockResponse().setResponseCode(200).setBody(sseBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "claude-sonnet-4-20250514",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val citationEvents = events.filterIsInstance<StreamEvent.Citations>()
        assertTrue(citationEvents.isNotEmpty(), "Expected Citations events")
        val allCitations = citationEvents.flatMap { it.citations }
        val urls = allCitations.map { it.url }
        assertTrue("https://anthropic.com" in urls)
        assertTrue("https://claude.ai" in urls)
        assertEquals("anthropic.com", allCitations.first { it.url == "https://anthropic.com" }.domain)
    }

    // -------------------------------------------------------------------------
    // Gemini grounding metadata citations
    // -------------------------------------------------------------------------

    @Test
    fun `Gemini - google_search tool is included in request body when webSearchEnabled`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiJsonResponse()))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gemini-2.0-flash",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(body.contains("google_search"), "Request should contain google_search tool, body=$body")
    }

    @Test
    fun `Gemini - google_search tool is absent when webSearchEnabled is false`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiJsonResponse()))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gemini-2.0-flash",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = false
        ).toList()

        val request = server.takeRequest()
        val body = request.body.readUtf8()
        assertTrue(!body.contains("google_search"), "Should NOT contain google_search when disabled")
    }

    @Test
    fun `Gemini - citations emitted from groundingMetadata`() = runTest {
        // Gemini SSE format: each chunk is a "data: <json>" followed by blank line
        val candidate = """{"content":{"role":"model","parts":[{"text":"Result"}]},"finishReason":"STOP","groundingMetadata":{"groundingChunks":[{"web":{"uri":"https://google.com","title":"Google","domain":"google.com"}},{"web":{"uri":"https://example.org","title":"Example","domain":"example.org"}}]}}"""
        val responseBody = "data: {\"candidates\":[$candidate],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":10}}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(responseBody))

        val adapter = GeminiAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gemini-2.0-flash",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = true
        ).toList()

        val citationEvents = events.filterIsInstance<StreamEvent.Citations>()
        assertTrue(citationEvents.isNotEmpty(), "Expected Citations events from groundingMetadata")
        val allCitations = citationEvents.flatMap { it.citations }
        val urls = allCitations.map { it.url }
        assertTrue("https://google.com" in urls)
        assertTrue("https://example.org" in urls)
        assertEquals("google.com", allCitations.first { it.url == "https://google.com" }.domain)
    }

    @Test
    fun `Gemini - no citations when groundingMetadata absent`() = runTest {
        server.enqueue(MockResponse().setResponseCode(200).setBody(geminiJsonResponse()))

        val adapter = GeminiAdapter(OkHttpClient())
        val events = adapter.sendMessageStream(
            apiBaseUrl = server.url("/").toString().trimEnd('/'),
            apiKey = "test-key",
            modelId = "gemini-2.0-flash",
            messages = emptyList(),
            tools = null,
            systemPrompt = null,
            webSearchEnabled = false
        ).toList()

        val citationEvents = events.filterIsInstance<StreamEvent.Citations>()
        assertTrue(citationEvents.isEmpty(), "No Citations events expected without groundingMetadata")
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Minimal valid OpenAI SSE response: one text delta then [DONE].  */
    private fun openAiSseResponse() = "data: {\"choices\":[{\"delta\":{\"content\":\"Hello\"}}]}\n\ndata: [DONE]\n\n"

    /** Minimal valid Anthropic SSE response: message_start, text block, message_stop. */
    private fun anthropicSseResponse() = buildString {
        append("data: {\"type\":\"message_start\",\"message\":{\"usage\":{\"input_tokens\":5,\"output_tokens\":0}}}\n\n")
        append("data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n")
        append("data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":\"Hello\"}}\n\n")
        append("data: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
        append("data: {\"type\":\"message_stop\"}\n\n")
    }

    /** Minimal valid Gemini SSE response without grounding metadata. */
    private fun geminiJsonResponse() = "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"Hello\"}]},\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":5,\"candidatesTokenCount\":3}}\n\n"
}
