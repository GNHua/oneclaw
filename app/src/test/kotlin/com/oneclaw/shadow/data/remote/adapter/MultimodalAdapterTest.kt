package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.AttachmentType
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MultimodalAdapterTest {

    private lateinit var server: MockWebServer
    private val json = Json { ignoreUnknownKeys = true }

    @BeforeEach
    fun setup() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun teardown() {
        server.shutdown()
    }

    private fun makeImageAttachment(data: String = "base64data") = ApiAttachment(
        type = AttachmentType.IMAGE,
        mimeType = "image/jpeg",
        base64Data = data,
        fileName = "photo.jpg"
    )

    private fun makeVideoAttachment(data: String = "videodata") = ApiAttachment(
        type = AttachmentType.VIDEO,
        mimeType = "video/mp4",
        base64Data = data,
        fileName = "video.mp4"
    )

    private fun makeFileAttachment(data: String = "filedata") = ApiAttachment(
        type = AttachmentType.FILE,
        mimeType = "application/pdf",
        base64Data = data,
        fileName = "doc.pdf"
    )

    // ---------- OpenAI tests ----------

    @Test
    fun `OpenAI text-only user message uses simple string content`() = runTest {
        val doneBody = "data: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gpt-4o",
            listOf(ApiMessage.User("Hello")),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)
        assertEquals("Hello", userMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `OpenAI multimodal user message uses array content with image_url`() = runTest {
        val doneBody = "data: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gpt-4o",
            listOf(ApiMessage.User("Describe this", listOf(makeImageAttachment("abc123")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array")

        assertEquals(2, content.size)
        assertEquals("text", content[0].jsonObject["type"]?.jsonPrimitive?.content)
        assertEquals("Describe this", content[0].jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("image_url", content[1].jsonObject["type"]?.jsonPrimitive?.content)
        val imageUrl = content[1].jsonObject["image_url"]?.jsonObject?.get("url")?.jsonPrimitive?.content
        assertTrue(imageUrl?.startsWith("data:image/jpeg;base64,abc123") == true)
    }

    @Test
    fun `OpenAI skips non-image attachments in multimodal message`() = runTest {
        val doneBody = "data: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gpt-4o",
            listOf(ApiMessage.User("Text", listOf(makeVideoAttachment(), makeFileAttachment()))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array for non-empty attachments")

        // Only text part since video and file are skipped
        val types = content.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue(types.none { it == "image_url" })
    }

    @Test
    fun `OpenAI multimodal with only image and no text omits text part`() = runTest {
        val doneBody = "data: [DONE]\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = OpenAiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gpt-4o",
            listOf(ApiMessage.User("", listOf(makeImageAttachment()))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array")

        // No text part (blank text)
        val types = content.map { it.jsonObject["type"]?.jsonPrimitive?.content }
        assertTrue(types.none { it == "text" })
        assertTrue(types.contains("image_url"))
    }

    // ---------- Anthropic tests ----------

    @Test
    fun `Anthropic text-only user message uses simple string content`() = runTest {
        val doneBody = "data: {\"type\":\"message_stop\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "claude-opus-4-5",
            listOf(ApiMessage.User("Hello")),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        assertEquals("user", userMsg["role"]?.jsonPrimitive?.content)
        assertEquals("Hello", userMsg["content"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Anthropic multimodal user message uses array content with image block`() = runTest {
        val doneBody = "data: {\"type\":\"message_stop\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "claude-opus-4-5",
            listOf(ApiMessage.User("Analyze", listOf(makeImageAttachment("imgdata")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array")

        val imageBlock = content.find { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
            ?: error("no image block")
        val source = imageBlock.jsonObject["source"]?.jsonObject ?: error("no source")
        assertEquals("base64", source["type"]?.jsonPrimitive?.content)
        assertEquals("image/jpeg", source["media_type"]?.jsonPrimitive?.content)
        assertEquals("imgdata", source["data"]?.jsonPrimitive?.content)

        val textBlock = content.find { it.jsonObject["type"]?.jsonPrimitive?.content == "text" }
            ?: error("no text block")
        assertEquals("Analyze", textBlock.jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Anthropic includes PDF file attachments as image blocks`() = runTest {
        val doneBody = "data: {\"type\":\"message_stop\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "claude-opus-4-5",
            listOf(ApiMessage.User("Read", listOf(makeFileAttachment("pdfdata")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array")

        val imageBlock = content.find { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
            ?: error("PDF should be included as image block")
        val source = imageBlock.jsonObject["source"]?.jsonObject ?: error("no source")
        assertEquals("pdfdata", source["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Anthropic skips VIDEO attachments`() = runTest {
        val doneBody = "data: {\"type\":\"message_stop\"}\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = AnthropicAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "claude-opus-4-5",
            listOf(ApiMessage.User("Watch", listOf(makeVideoAttachment()))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val messages = body["messages"]?.jsonArray ?: error("no messages")
        val userMsg = messages.last().jsonObject
        val content = userMsg["content"]?.jsonArray ?: error("content should be array")

        val imageBlocks = content.filter { it.jsonObject["type"]?.jsonPrimitive?.content == "image" }
        assertTrue(imageBlocks.isEmpty(), "Video should be skipped")
    }

    // ---------- Gemini tests ----------

    @Test
    fun `Gemini text-only user message has single text part`() = runTest {
        val doneBody = """data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""" + "\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gemini-2.0-flash",
            listOf(ApiMessage.User("Hello")),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val contents = body["contents"]?.jsonArray ?: error("no contents")
        val userContent = contents.last().jsonObject
        val parts = userContent["parts"]?.jsonArray ?: error("no parts")

        assertEquals(1, parts.size)
        assertEquals("Hello", parts[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Gemini multimodal user message has text and inlineData parts`() = runTest {
        val doneBody = """data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""" + "\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gemini-2.0-flash",
            listOf(ApiMessage.User("Describe", listOf(makeImageAttachment("imgbytes")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val contents = body["contents"]?.jsonArray ?: error("no contents")
        val userContent = contents.last().jsonObject
        val parts = userContent["parts"]?.jsonArray ?: error("no parts")

        assertEquals(2, parts.size)
        assertEquals("Describe", parts[0].jsonObject["text"]?.jsonPrimitive?.content)
        val inlineData = parts[1].jsonObject["inlineData"]?.jsonObject ?: error("no inlineData")
        assertEquals("image/jpeg", inlineData["mimeType"]?.jsonPrimitive?.content)
        assertEquals("imgbytes", inlineData["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Gemini multimodal user message includes video parts`() = runTest {
        val doneBody = """data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""" + "\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gemini-2.0-flash",
            listOf(ApiMessage.User("Watch", listOf(makeVideoAttachment("vid123")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val contents = body["contents"]?.jsonArray ?: error("no contents")
        val userContent = contents.last().jsonObject
        val parts = userContent["parts"]?.jsonArray ?: error("no parts")

        val inlinePart = parts.find { it.jsonObject.containsKey("inlineData") }
            ?: error("no inlineData part")
        val inlineData = inlinePart.jsonObject["inlineData"]?.jsonObject ?: error("no inlineData")
        assertEquals("video/mp4", inlineData["mimeType"]?.jsonPrimitive?.content)
        assertEquals("vid123", inlineData["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `Gemini skips FILE attachments`() = runTest {
        val doneBody = """data: {"candidates":[{"content":{"parts":[{"text":"ok"}]},"finishReason":"STOP"}],"usageMetadata":{"promptTokenCount":5,"candidatesTokenCount":1}}""" + "\n\n"
        server.enqueue(MockResponse().setResponseCode(200).setBody(doneBody))

        val adapter = GeminiAdapter(OkHttpClient())
        adapter.sendMessageStream(
            server.url("/").toString().trimEnd('/'),
            "test-key", "gemini-2.0-flash",
            listOf(ApiMessage.User("Read", listOf(makeFileAttachment("filedata")))),
            null, null
        ).toList()

        val request = server.takeRequest()
        val body = json.parseToJsonElement(request.body.readUtf8()).jsonObject
        val contents = body["contents"]?.jsonArray ?: error("no contents")
        val userContent = contents.last().jsonObject
        val parts = userContent["parts"]?.jsonArray ?: error("no parts")

        val inlineParts = parts.filter { it.jsonObject.containsKey("inlineData") }
        assertTrue(inlineParts.isEmpty(), "FILE attachment should be skipped by Gemini")
    }
}
