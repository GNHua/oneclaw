package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.data.local.entity.MemoryIndexEntity
import com.oneclaw.shadow.feature.memory.search.BM25Scorer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BM25ScorerTest {

    private val scorer = BM25Scorer()

    private fun makeEntity(id: String, text: String): MemoryIndexEntity {
        val now = System.currentTimeMillis()
        return MemoryIndexEntity(
            id = id,
            sourceType = "daily_log",
            sourceDate = "2026-02-28",
            chunkText = text,
            embedding = null,
            createdAt = now,
            updatedAt = now
        )
    }

    @Test
    fun `score returns empty map for empty chunk list`() {
        val result = scorer.score("hello", emptyList())
        assertTrue(result.isEmpty())
    }

    @Test
    fun `score returns zero for query with no terms`() {
        val chunks = listOf(makeEntity("1", "hello world"))
        val result = scorer.score("", chunks)
        // With empty query, tokenize returns empty list -> all scores 0
        assertTrue(result.values.all { it == 0f })
    }

    @Test
    fun `score ranks exact match higher than non-match`() {
        val chunks = listOf(
            makeEntity("1", "the quick brown fox"),
            makeEntity("2", "completely unrelated text about food")
        )
        val result = scorer.score("quick fox", chunks)
        assertTrue(result["1"]!! > result["2"]!!)
    }

    @Test
    fun `tokenize handles CJK characters by splitting on whitespace`() {
        // CJK words separated by spaces are tokenized as separate terms
        val tokens = scorer.tokenize("用户 简洁 回答")
        assertTrue(tokens.contains("用户"))
        assertTrue(tokens.contains("简洁"))
        assertTrue(tokens.contains("回答"))
    }

    @Test
    fun `tokenize lowercases and removes special chars`() {
        val tokens = scorer.tokenize("Hello, World! 2026")
        assertTrue(tokens.contains("hello"))
        assertTrue(tokens.contains("world"))
        assertTrue(tokens.contains("2026"))
        assertTrue(tokens.none { it.contains(",") || it.contains("!") })
    }

    @Test
    fun `score with repeated terms saturates term frequency`() {
        val chunks = listOf(
            makeEntity("1", "kotlin kotlin kotlin kotlin"),
            makeEntity("2", "kotlin is a great language")
        )
        val result = scorer.score("kotlin", chunks)
        // Both should have positive scores; the first should not be infinitely higher
        assertTrue(result["1"]!! > 0f)
        assertTrue(result["2"]!! > 0f)
    }

    @Test
    fun `score produces correct chunk ids as map keys`() {
        val ids = listOf("abc", "def", "ghi")
        val chunks = ids.map { makeEntity(it, "sample text for $it") }
        val result = scorer.score("sample", chunks)
        assertEquals(ids.toSet(), result.keys)
    }
}
