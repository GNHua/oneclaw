package com.tomandy.oneclaw.workspace.embedding

import java.security.MessageDigest

data class Chunk(
    val index: Int,
    val content: String,
    val contentHash: String
)

object ChunkingUtils {

    private const val MIN_CHUNK_CHARS = 100
    private const val MAX_CHUNK_CHARS = 500

    private val SPLIT_PATTERN = Regex("""(?:\n\n+|(?=^#{1,3}\s))""", RegexOption.MULTILINE)

    fun chunkText(text: String): List<Chunk> {
        if (text.isBlank()) return emptyList()

        val rawSegments = SPLIT_PATTERN.split(text).filter { it.isNotBlank() }
        if (rawSegments.isEmpty()) return emptyList()

        val merged = mergeSmallSegments(rawSegments)
        val split = merged.flatMap { splitLargeSegment(it) }

        return split.mapIndexed { index, content ->
            val trimmed = content.trim()
            Chunk(
                index = index,
                content = trimmed,
                contentHash = sha256(trimmed)
            )
        }
    }

    private fun mergeSmallSegments(segments: List<String>): List<String> {
        val result = mutableListOf<String>()
        val buffer = StringBuilder()

        for (segment in segments) {
            if (buffer.isNotEmpty()) {
                buffer.append("\n\n")
            }
            buffer.append(segment.trim())

            if (buffer.length >= MIN_CHUNK_CHARS) {
                result.add(buffer.toString())
                buffer.clear()
            }
        }

        if (buffer.isNotEmpty()) {
            if (result.isNotEmpty() && buffer.length < MIN_CHUNK_CHARS) {
                result[result.lastIndex] = result.last() + "\n\n" + buffer.toString()
            } else {
                result.add(buffer.toString())
            }
        }

        return result
    }

    private fun splitLargeSegment(text: String): List<String> {
        if (text.length <= MAX_CHUNK_CHARS) return listOf(text)

        val parts = mutableListOf<String>()
        var remaining = text

        while (remaining.length > MAX_CHUNK_CHARS) {
            val splitAt = findSentenceBoundary(remaining, MAX_CHUNK_CHARS)
            parts.add(remaining.substring(0, splitAt).trim())
            remaining = remaining.substring(splitAt).trim()
        }

        if (remaining.isNotBlank()) {
            parts.add(remaining)
        }

        return parts
    }

    private fun findSentenceBoundary(text: String, maxPos: Int): Int {
        val searchRegion = text.substring(0, maxPos)
        val lastPeriod = searchRegion.lastIndexOf(". ")
        if (lastPeriod > maxPos / 2) return lastPeriod + 2

        val lastNewline = searchRegion.lastIndexOf('\n')
        if (lastNewline > maxPos / 2) return lastNewline + 1

        val lastSpace = searchRegion.lastIndexOf(' ')
        if (lastSpace > maxPos / 2) return lastSpace + 1

        return maxPos
    }

    fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        return digest.digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
}
