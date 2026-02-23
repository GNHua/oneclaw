package com.tomandy.oneclaw.workspace.embedding

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChunkingUtilsTest {

    // --- empty / blank input ---

    @Test
    fun `chunkText returns empty for blank input`() {
        assertEquals(emptyList<Chunk>(), ChunkingUtils.chunkText(""))
        assertEquals(emptyList<Chunk>(), ChunkingUtils.chunkText("   "))
        assertEquals(emptyList<Chunk>(), ChunkingUtils.chunkText("\n\n"))
    }

    // --- single short paragraph ---

    @Test
    fun `chunkText returns single chunk for short text`() {
        val text = "This is a simple memory note about PostgreSQL."
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(1, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(text, chunks[0].content)
    }

    // --- paragraph splitting ---

    @Test
    fun `chunkText splits on double newlines`() {
        val paragraph1 = "A".repeat(120)
        val paragraph2 = "B".repeat(120)
        val text = "$paragraph1\n\n$paragraph2"
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(2, chunks.size)
        assertEquals(paragraph1, chunks[0].content)
        assertEquals(paragraph2, chunks[1].content)
    }

    @Test
    fun `chunkText splits on triple newlines`() {
        val paragraph1 = "A".repeat(120)
        val paragraph2 = "B".repeat(120)
        val text = "$paragraph1\n\n\n$paragraph2"
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(2, chunks.size)
    }

    // --- header splitting ---

    @Test
    fun `chunkText splits on markdown headers`() {
        val text = """
            |## Section One
            |${"A".repeat(120)}
            |
            |## Section Two
            |${"B".repeat(120)}
        """.trimMargin()
        val chunks = ChunkingUtils.chunkText(text)
        assertTrue("Expected at least 2 chunks, got ${chunks.size}", chunks.size >= 2)
        assertTrue(chunks[0].content.contains("Section One"))
        assertTrue(chunks.last().content.contains("Section Two"))
    }

    // --- merging small segments ---

    @Test
    fun `chunkText merges small adjacent segments`() {
        // Each segment is under 100 chars, should be merged
        val text = "Short note one.\n\nShort note two.\n\nShort note three."
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(1, chunks.size)
        assertTrue(chunks[0].content.contains("Short note one."))
        assertTrue(chunks[0].content.contains("Short note three."))
    }

    // --- splitting large segments ---

    @Test
    fun `chunkText splits segments exceeding max size`() {
        // Create a single paragraph with multiple sentences totaling > 500 chars
        val sentences = (1..20).joinToString(". ") { "This is sentence number $it" } + "."
        assertTrue("Test precondition: text should exceed 500 chars", sentences.length > 500)

        val chunks = ChunkingUtils.chunkText(sentences)
        assertTrue("Expected more than 1 chunk, got ${chunks.size}", chunks.size > 1)
        for (chunk in chunks) {
            assertTrue(
                "Chunk should be <= ~500 chars, was ${chunk.content.length}",
                chunk.content.length <= 550 // allow some slack from sentence boundary finding
            )
        }
    }

    @Test
    fun `chunkText splits on sentence boundary`() {
        val sentence1 = "A".repeat(260) + ". "
        val sentence2 = "B".repeat(260) + "."
        val text = sentence1 + sentence2
        val chunks = ChunkingUtils.chunkText(text)
        assertTrue("Expected >= 2 chunks", chunks.size >= 2)
        // First chunk should end near the sentence boundary
        assertTrue(chunks[0].content.endsWith("."))
    }

    // --- sequential indices ---

    @Test
    fun `chunkText assigns sequential zero-based indices`() {
        val text = "${"A".repeat(150)}\n\n${"B".repeat(150)}\n\n${"C".repeat(150)}"
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(3, chunks.size)
        assertEquals(0, chunks[0].index)
        assertEquals(1, chunks[1].index)
        assertEquals(2, chunks[2].index)
    }

    // --- hash stability ---

    @Test
    fun `sha256 is deterministic`() {
        val text = "We decided to use PostgreSQL for the database."
        val hash1 = ChunkingUtils.sha256(text)
        val hash2 = ChunkingUtils.sha256(text)
        assertEquals(hash1, hash2)
    }

    @Test
    fun `sha256 produces different hashes for different input`() {
        val hash1 = ChunkingUtils.sha256("PostgreSQL")
        val hash2 = ChunkingUtils.sha256("MySQL")
        assertNotEquals(hash1, hash2)
    }

    @Test
    fun `sha256 produces 64-char hex string`() {
        val hash = ChunkingUtils.sha256("test")
        assertEquals(64, hash.length)
        assertTrue(hash.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `chunk content hash matches sha256 of content`() {
        val text = "A".repeat(150)
        val chunks = ChunkingUtils.chunkText(text)
        assertEquals(1, chunks.size)
        assertEquals(ChunkingUtils.sha256(chunks[0].content), chunks[0].contentHash)
    }

    // --- content hash changes when content changes ---

    @Test
    fun `chunk hash changes when content is modified`() {
        val text1 = "We use PostgreSQL. " + "A".repeat(100)
        val text2 = "We use MySQL. " + "A".repeat(100)
        val chunks1 = ChunkingUtils.chunkText(text1)
        val chunks2 = ChunkingUtils.chunkText(text2)
        assertNotEquals(chunks1[0].contentHash, chunks2[0].contentHash)
    }

    // --- realistic markdown document ---

    @Test
    fun `chunkText handles realistic memory document`() {
        val text = """
            |# Project Decisions
            |
            |## Database
            |We decided to use PostgreSQL for the main database. It provides good JSON support
            |and the team has experience with it. We considered MongoDB but decided relational
            |was a better fit for our data model.
            |
            |## Authentication
            |Using JWT tokens with refresh token rotation. Access tokens expire after 15 minutes.
            |Refresh tokens are stored in HTTP-only cookies. We use bcrypt for password hashing
            |with a cost factor of 12.
            |
            |## Deployment
            |Kubernetes on GKE with auto-scaling. CI/CD via GitHub Actions. Staging environment
            |mirrors production. Blue-green deployments for zero-downtime releases.
        """.trimMargin()

        val chunks = ChunkingUtils.chunkText(text)
        assertTrue("Expected multiple chunks", chunks.size >= 2)

        // All chunks should have non-empty content
        for (chunk in chunks) {
            assertTrue(chunk.content.isNotBlank())
            assertTrue(chunk.contentHash.isNotBlank())
        }

        // Content should be preserved (nothing lost)
        val allContent = chunks.joinToString(" ") { it.content }
        assertTrue(allContent.contains("PostgreSQL"))
        assertTrue(allContent.contains("JWT"))
        assertTrue(allContent.contains("Kubernetes"))
    }
}
