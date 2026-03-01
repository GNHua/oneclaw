package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.Session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CompactAwareMessageBuilderTest {

    private val now = System.currentTimeMillis()

    private fun makeSession(
        compactedSummary: String? = null,
        compactBoundaryTimestamp: Long? = null
    ) = Session(
        id = "session-1",
        title = "Test",
        currentAgentId = "agent-1",
        messageCount = 0,
        lastMessagePreview = null,
        isActive = true,
        deletedAt = null,
        createdAt = now,
        updatedAt = now,
        compactedSummary = compactedSummary,
        compactBoundaryTimestamp = compactBoundaryTimestamp
    )

    private fun makeUserMessage(content: String, createdAt: Long) = Message(
        id = "msg-${createdAt}",
        sessionId = "session-1",
        type = MessageType.USER,
        content = content,
        thinkingContent = null,
        toolCallId = null,
        toolName = null,
        toolInput = null,
        toolOutput = null,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = null,
        tokenCountOutput = null,
        modelId = null,
        providerId = null,
        createdAt = createdAt
    )

    private fun makeAiMessage(content: String, createdAt: Long) = Message(
        id = "ai-${createdAt}",
        sessionId = "session-1",
        type = MessageType.AI_RESPONSE,
        content = content,
        thinkingContent = null,
        toolCallId = null,
        toolName = null,
        toolInput = null,
        toolOutput = null,
        toolStatus = null,
        toolDurationMs = null,
        tokenCountInput = null,
        tokenCountOutput = null,
        modelId = null,
        providerId = null,
        createdAt = createdAt
    )

    @Test
    fun `returns all messages unchanged when no compact summary`() {
        val session = makeSession()
        val messages = listOf(
            makeUserMessage("Hello", 1000),
            makeAiMessage("Hi there", 2000)
        )

        val (systemPrompt, apiMessages) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = "You are an assistant"
        )

        assertEquals("You are an assistant", systemPrompt)
        assertEquals(2, apiMessages.size)
    }

    @Test
    fun `returns all messages unchanged when summary present but no boundary timestamp`() {
        val session = makeSession(compactedSummary = "Some summary", compactBoundaryTimestamp = null)
        val messages = listOf(makeUserMessage("Hello", 1000))

        val (_, apiMessages) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = null
        )

        assertEquals(1, apiMessages.size)
    }

    @Test
    fun `filters messages before boundary timestamp when summary exists`() {
        val boundary = 5000L
        val session = makeSession(
            compactedSummary = "Previous summary",
            compactBoundaryTimestamp = boundary
        )
        val messages = listOf(
            makeUserMessage("Old message 1", 1000),
            makeAiMessage("Old response 1", 2000),
            makeUserMessage("Old message 2", 3000),
            makeAiMessage("Old response 2", 4000),
            makeUserMessage("Recent message", 5000),  // >= boundary: included
            makeAiMessage("Recent response", 6000)
        )

        val (_, apiMessages) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = null
        )

        // Only messages with createdAt >= 5000 should be included
        assertEquals(2, apiMessages.size)
    }

    @Test
    fun `prepends summary to original system prompt`() {
        val summary = "This is the previous conversation summary."
        val session = makeSession(
            compactedSummary = summary,
            compactBoundaryTimestamp = 5000L
        )
        val messages = listOf(makeUserMessage("Recent", 5000))

        val (systemPrompt, _) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = "You are a helpful assistant."
        )

        assertTrue(systemPrompt!!.startsWith("Previous conversation summary:"))
        assertTrue(systemPrompt.contains(summary))
        assertTrue(systemPrompt.contains("You are a helpful assistant."))
    }

    @Test
    fun `uses fallback prompt when no original system prompt and summary exists`() {
        val summary = "Previous context."
        val session = makeSession(
            compactedSummary = summary,
            compactBoundaryTimestamp = 1000L
        )
        val messages = listOf(makeUserMessage("Hello", 1000))

        val (systemPrompt, _) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = null
        )

        assertTrue(systemPrompt!!.contains("Continue the conversation based on the summary above."))
        assertTrue(systemPrompt.contains(summary))
    }

    @Test
    fun `returns null system prompt when no summary and no original prompt`() {
        val session = makeSession()
        val messages = listOf(makeUserMessage("Hello", 1000))

        val (systemPrompt, _) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = null
        )

        assertNull(systemPrompt)
    }

    @Test
    fun `includes messages exactly at boundary timestamp`() {
        val boundary = 3000L
        val session = makeSession(
            compactedSummary = "Summary",
            compactBoundaryTimestamp = boundary
        )
        val messages = listOf(
            makeUserMessage("Before", 2999),
            makeUserMessage("At boundary", boundary),
            makeAiMessage("After", 4000)
        )

        val (_, apiMessages) = CompactAwareMessageBuilder.build(
            session = session,
            allMessages = messages,
            originalSystemPrompt = null
        )

        // Only messages with createdAt >= 3000 (at boundary and after)
        assertEquals(2, apiMessages.size)
    }
}
