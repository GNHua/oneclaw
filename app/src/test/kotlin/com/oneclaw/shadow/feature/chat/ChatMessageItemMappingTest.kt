package com.oneclaw.shadow.feature.chat

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ChatMessageItemMappingTest {

    private fun makeMessage(
        tokenCountInput: Int? = null,
        tokenCountOutput: Int? = null,
        type: MessageType = MessageType.AI_RESPONSE
    ) = Message(
        id = "msg-1",
        sessionId = "session-1",
        type = type,
        content = "Hello world",
        thinkingContent = null,
        toolCallId = null,
        toolName = null,
        toolInput = null,
        toolOutput = null,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = tokenCountInput,
        tokenCountOutput = tokenCountOutput,
        modelId = "gpt-4",
        providerId = "provider-1",
        createdAt = 1_000_000L
    )

    @Test
    fun `toChatMessageItem maps tokenCountInput correctly`() {
        val message = makeMessage(tokenCountInput = 1234, tokenCountOutput = 567)
        val item = message.toChatMessageItem()

        assertEquals(1234, item.tokenCountInput)
    }

    @Test
    fun `toChatMessageItem maps tokenCountOutput correctly`() {
        val message = makeMessage(tokenCountInput = 1234, tokenCountOutput = 567)
        val item = message.toChatMessageItem()

        assertEquals(567, item.tokenCountOutput)
    }

    @Test
    fun `toChatMessageItem maps null tokenCountInput as null`() {
        val message = makeMessage(tokenCountInput = null, tokenCountOutput = null)
        val item = message.toChatMessageItem()

        assertNull(item.tokenCountInput)
    }

    @Test
    fun `toChatMessageItem maps null tokenCountOutput as null`() {
        val message = makeMessage(tokenCountInput = null, tokenCountOutput = null)
        val item = message.toChatMessageItem()

        assertNull(item.tokenCountOutput)
    }

    @Test
    fun `toChatMessageItem maps base fields correctly`() {
        val message = makeMessage(tokenCountInput = 100, tokenCountOutput = 50)
        val item = message.toChatMessageItem()

        assertEquals("msg-1", item.id)
        assertEquals(MessageType.AI_RESPONSE, item.type)
        assertEquals("Hello world", item.content)
        assertEquals("gpt-4", item.modelId)
        assertEquals(1_000_000L, item.timestamp)
    }

    @Test
    fun `toChatMessageItem sets isRetryable true for ERROR type`() {
        val message = makeMessage(type = MessageType.ERROR)
        val item = message.toChatMessageItem()

        assertEquals(true, item.isRetryable)
    }

    @Test
    fun `toChatMessageItem sets isRetryable false for AI_RESPONSE type`() {
        val message = makeMessage(type = MessageType.AI_RESPONSE)
        val item = message.toChatMessageItem()

        assertEquals(false, item.isRetryable)
    }
}
