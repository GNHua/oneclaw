package com.oneclaw.shadow.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderCapabilityTest {

    @Test
    fun `OpenAI supports only IMAGE attachments`() {
        assertTrue(ProviderCapability.supportsAttachmentType(ProviderType.OPENAI, AttachmentType.IMAGE))
        assertFalse(ProviderCapability.supportsAttachmentType(ProviderType.OPENAI, AttachmentType.VIDEO))
        assertFalse(ProviderCapability.supportsAttachmentType(ProviderType.OPENAI, AttachmentType.FILE))
    }

    @Test
    fun `Anthropic supports IMAGE and FILE attachments`() {
        assertTrue(ProviderCapability.supportsAttachmentType(ProviderType.ANTHROPIC, AttachmentType.IMAGE))
        assertTrue(ProviderCapability.supportsAttachmentType(ProviderType.ANTHROPIC, AttachmentType.FILE))
        assertFalse(ProviderCapability.supportsAttachmentType(ProviderType.ANTHROPIC, AttachmentType.VIDEO))
    }

    @Test
    fun `Gemini supports IMAGE and VIDEO attachments`() {
        assertTrue(ProviderCapability.supportsAttachmentType(ProviderType.GEMINI, AttachmentType.IMAGE))
        assertTrue(ProviderCapability.supportsAttachmentType(ProviderType.GEMINI, AttachmentType.VIDEO))
        assertFalse(ProviderCapability.supportsAttachmentType(ProviderType.GEMINI, AttachmentType.FILE))
    }

    @Test
    fun `getUnsupportedTypes returns VIDEO and FILE for OpenAI`() {
        val types = listOf(AttachmentType.IMAGE, AttachmentType.VIDEO, AttachmentType.FILE)
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.OPENAI, types)
        assertEquals(2, unsupported.size)
        assertTrue(unsupported.contains(AttachmentType.VIDEO))
        assertTrue(unsupported.contains(AttachmentType.FILE))
    }

    @Test
    fun `getUnsupportedTypes returns empty list when all types supported`() {
        val types = listOf(AttachmentType.IMAGE)
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.OPENAI, types)
        assertTrue(unsupported.isEmpty())
    }

    @Test
    fun `getUnsupportedTypes returns VIDEO for Anthropic`() {
        val types = listOf(AttachmentType.IMAGE, AttachmentType.VIDEO, AttachmentType.FILE)
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.ANTHROPIC, types)
        assertEquals(1, unsupported.size)
        assertTrue(unsupported.contains(AttachmentType.VIDEO))
    }

    @Test
    fun `getUnsupportedTypes returns FILE for Gemini`() {
        val types = listOf(AttachmentType.IMAGE, AttachmentType.VIDEO, AttachmentType.FILE)
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.GEMINI, types)
        assertEquals(1, unsupported.size)
        assertTrue(unsupported.contains(AttachmentType.FILE))
    }

    @Test
    fun `getUnsupportedTypes deduplicates input types`() {
        val types = listOf(AttachmentType.VIDEO, AttachmentType.VIDEO, AttachmentType.FILE)
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.OPENAI, types)
        assertEquals(2, unsupported.size)
        assertEquals(1, unsupported.count { it == AttachmentType.VIDEO })
    }

    @Test
    fun `getUnsupportedTypes returns empty list for empty input`() {
        val unsupported = ProviderCapability.getUnsupportedTypes(ProviderType.OPENAI, emptyList())
        assertTrue(unsupported.isEmpty())
    }
}
