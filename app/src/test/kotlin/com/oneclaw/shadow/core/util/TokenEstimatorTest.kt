package com.oneclaw.shadow.core.util

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TokenEstimatorTest {

    private fun makeMessage(
        content: String = "",
        thinkingContent: String? = null,
        toolInput: String? = null,
        toolOutput: String? = null,
        type: MessageType = MessageType.USER
    ) = Message(
        id = "msg-1",
        sessionId = "session-1",
        type = type,
        content = content,
        thinkingContent = thinkingContent,
        toolCallId = null,
        toolName = null,
        toolInput = toolInput,
        toolOutput = toolOutput,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = null,
        tokenCountOutput = null,
        modelId = null,
        providerId = null,
        createdAt = 0
    )

    @Test
    fun `CHARS_PER_TOKEN constant is 4`() {
        assertEquals(4, TokenEstimator.CHARS_PER_TOKEN)
    }

    @Test
    fun `estimateFromText returns 0 for empty string`() {
        assertEquals(0, TokenEstimator.estimateFromText(""))
    }

    @Test
    fun `estimateFromText returns at least 1 for non-empty string`() {
        assertEquals(1, TokenEstimator.estimateFromText("a"))
    }

    @Test
    fun `estimateFromText divides by CHARS_PER_TOKEN`() {
        val text = "a".repeat(20)
        assertEquals(5, TokenEstimator.estimateFromText(text))
    }

    @Test
    fun `estimateFromText coerces result to at least 1 for short strings`() {
        val text = "ab" // length 2, 2/4 = 0, should be coerced to 1
        assertEquals(1, TokenEstimator.estimateFromText(text))
    }

    @Test
    fun `estimateMessageTokens counts content only for simple message`() {
        val msg = makeMessage(content = "a".repeat(40))
        assertEquals(10, TokenEstimator.estimateMessageTokens(msg))
    }

    @Test
    fun `estimateMessageTokens includes thinkingContent when present`() {
        val msg = makeMessage(content = "a".repeat(8), thinkingContent = "b".repeat(8))
        // content: 8/4=2, thinking: 8/4=2, total=4
        assertEquals(4, TokenEstimator.estimateMessageTokens(msg))
    }

    @Test
    fun `estimateMessageTokens includes toolInput when present`() {
        val msg = makeMessage(content = "", toolInput = "c".repeat(12))
        // content: empty=0, toolInput: 12/4=3
        assertEquals(3, TokenEstimator.estimateMessageTokens(msg))
    }

    @Test
    fun `estimateMessageTokens includes toolOutput when present`() {
        val msg = makeMessage(content = "", toolOutput = "d".repeat(16))
        // toolOutput: 16/4=4
        assertEquals(4, TokenEstimator.estimateMessageTokens(msg))
    }

    @Test
    fun `estimateMessageTokens sums all fields`() {
        val msg = makeMessage(
            content = "a".repeat(4),         // 1
            thinkingContent = "b".repeat(4), // 1
            toolInput = "c".repeat(4),       // 1
            toolOutput = "d".repeat(4)       // 1
        )
        assertEquals(4, TokenEstimator.estimateMessageTokens(msg))
    }

    @Test
    fun `estimateTotalTokens returns 0 for empty list`() {
        assertEquals(0, TokenEstimator.estimateTotalTokens(emptyList()))
    }

    @Test
    fun `estimateTotalTokens sums all messages`() {
        val messages = listOf(
            makeMessage(content = "a".repeat(4)),  // 1
            makeMessage(content = "b".repeat(8)),  // 2
            makeMessage(content = "c".repeat(12))  // 3
        )
        assertEquals(6, TokenEstimator.estimateTotalTokens(messages))
    }
}
