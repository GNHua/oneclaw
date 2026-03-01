package com.oneclaw.shadow.data.local.mapper

import com.oneclaw.shadow.core.model.Citation
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.data.local.entity.MessageEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * RFC-031: Tests for citation serialization in Message mapper.
 */
class CitationMapperTest {

    @Test
    fun `toDomain returns null citations when entity citations is null`() {
        val entity = buildMessageEntity(citations = null)
        val domain = entity.toDomain()
        assertNull(domain.citations)
    }

    @Test
    fun `toDomain parses citations JSON correctly`() {
        val citationsJson = """[{"url":"https://example.com","title":"Example","domain":"example.com"}]"""
        val entity = buildMessageEntity(citations = citationsJson)

        val domain = entity.toDomain()

        assertNotNull(domain.citations)
        assertEquals(1, domain.citations!!.size)
        assertEquals("https://example.com", domain.citations[0].url)
        assertEquals("Example", domain.citations[0].title)
        assertEquals("example.com", domain.citations[0].domain)
    }

    @Test
    fun `toDomain parses multiple citations correctly`() {
        val citationsJson = """[
            {"url":"https://a.com","title":"A","domain":"a.com"},
            {"url":"https://b.org","title":"B","domain":"b.org"}
        ]"""
        val entity = buildMessageEntity(citations = citationsJson)

        val domain = entity.toDomain()

        assertNotNull(domain.citations)
        assertEquals(2, domain.citations!!.size)
        assertEquals("https://a.com", domain.citations[0].url)
        assertEquals("https://b.org", domain.citations[1].url)
    }

    @Test
    fun `toDomain returns null citations for malformed JSON`() {
        val entity = buildMessageEntity(citations = "not-valid-json{{{")
        val domain = entity.toDomain()
        // Should not throw; malformed JSON yields null
        assertNull(domain.citations)
    }

    @Test
    fun `toEntity serializes citations to JSON string`() {
        val domain = buildMessage(citations = listOf(
            Citation(url = "https://test.com", title = "Test", domain = "test.com")
        ))

        val entity = domain.toEntity()

        assertNotNull(entity.citations)
        val json = entity.citations!!
        assert(json.contains("https://test.com")) { "Expected URL in citations JSON: $json" }
        assert(json.contains("Test")) { "Expected title in citations JSON: $json" }
        assert(json.contains("test.com")) { "Expected domain in citations JSON: $json" }
    }

    @Test
    fun `toEntity sets citations to null when domain citations is null`() {
        val domain = buildMessage(citations = null)
        val entity = domain.toEntity()
        assertNull(entity.citations)
    }

    @Test
    fun `roundtrip preserves citations`() {
        val original = buildMessage(citations = listOf(
            Citation(url = "https://roundtrip.io", title = "Roundtrip", domain = "roundtrip.io")
        ))

        val roundtripped = original.toEntity().toDomain()

        assertEquals(original.citations, roundtripped.citations)
    }

    @Test
    fun `roundtrip preserves null citations`() {
        val original = buildMessage(citations = null)
        val roundtripped = original.toEntity().toDomain()
        assertNull(roundtripped.citations)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun buildMessageEntity(citations: String?) = MessageEntity(
        id = "msg-test",
        sessionId = "session-1",
        type = "AI_RESPONSE",
        content = "Test response",
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
        createdAt = 1000L,
        citations = citations
    )

    private fun buildMessage(citations: List<Citation>?) = Message(
        id = "msg-test",
        sessionId = "session-1",
        type = MessageType.AI_RESPONSE,
        content = "Test response",
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
        createdAt = 1000L,
        citations = citations
    )
}
