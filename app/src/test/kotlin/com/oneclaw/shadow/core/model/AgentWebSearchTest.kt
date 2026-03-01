package com.oneclaw.shadow.core.model

import com.oneclaw.shadow.data.local.entity.AgentEntity
import com.oneclaw.shadow.data.local.mapper.toDomain
import com.oneclaw.shadow.data.local.mapper.toEntity
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * RFC-031: Unit tests for Agent.webSearchEnabled field and its mapper/entity handling.
 */
class AgentWebSearchTest {

    @Test
    fun `Agent has webSearchEnabled field defaulting to false`() {
        val agent = Agent(
            id = "agent-1",
            name = "Test",
            description = null,
            systemPrompt = "Be helpful.",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0
        )
        assertFalse(agent.webSearchEnabled)
    }

    @Test
    fun `Agent can be created with webSearchEnabled true`() {
        val agent = Agent(
            id = "agent-2",
            name = "Searcher",
            description = null,
            systemPrompt = "Search and answer.",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0,
            webSearchEnabled = true
        )
        assertTrue(agent.webSearchEnabled)
    }

    @Test
    fun `AgentEntity has webSearchEnabled field defaulting to false`() {
        val entity = AgentEntity(
            id = "agent-1",
            name = "Test",
            description = null,
            systemPrompt = "Be helpful.",
            toolIds = "[]",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0
        )
        assertFalse(entity.webSearchEnabled)
    }

    @Test
    fun `entity toDomain maps webSearchEnabled correctly`() {
        val entity = AgentEntity(
            id = "agent-3",
            name = "Search Agent",
            description = null,
            systemPrompt = "Search.",
            toolIds = "[]",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0,
            webSearchEnabled = true
        )
        val domain = entity.toDomain()
        assertTrue(domain.webSearchEnabled)
    }

    @Test
    fun `domain toEntity maps webSearchEnabled correctly`() {
        val domain = Agent(
            id = "agent-4",
            name = "Search Agent",
            description = null,
            systemPrompt = "Search.",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 0,
            updatedAt = 0,
            webSearchEnabled = true
        )
        val entity = domain.toEntity()
        assertTrue(entity.webSearchEnabled)
    }

    @Test
    fun `roundtrip preserves webSearchEnabled = false`() {
        val original = Agent(
            id = "agent-rt-false",
            name = "No Search",
            description = null,
            systemPrompt = "No web.",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 1000,
            updatedAt = 2000,
            webSearchEnabled = false
        )
        val roundtripped = original.toEntity().toDomain()
        assertEquals(original, roundtripped)
    }

    @Test
    fun `roundtrip preserves webSearchEnabled = true`() {
        val original = Agent(
            id = "agent-rt-true",
            name = "Web Searcher",
            description = "Searches the web",
            systemPrompt = "Use web search.",
            preferredProviderId = null,
            preferredModelId = null,
            isBuiltIn = false,
            createdAt = 1000,
            updatedAt = 2000,
            webSearchEnabled = true
        )
        val roundtripped = original.toEntity().toDomain()
        assertEquals(original, roundtripped)
    }
}
