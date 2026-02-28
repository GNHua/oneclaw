package com.oneclaw.shadow.feature.memory

import com.oneclaw.shadow.feature.memory.log.DailyLogWriter
import io.mockk.mockk
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class DailyLogWriterParsingTest {

    /**
     * We access parseSummarizationResponse via a minimal subclass since it is internal.
     * Instead, we create the writer with mocked dependencies and test the parsing logic directly
     * by calling the internal method via a dedicated helper method on a test subclass.
     */
    private val writer = DailyLogWriter(
        messageRepository = mockk(),
        sessionRepository = mockk(),
        agentRepository = mockk(),
        providerRepository = mockk(),
        apiKeyStorage = mockk(),
        adapterFactory = mockk(),
        memoryFileStorage = mockk(),
        longTermMemoryManager = mockk(),
        memoryIndexDao = mockk(),
        embeddingEngine = mockk()
    )

    @Test
    fun `parseSummarizationResponse extracts daily summary and long-term facts`() {
        val response = """
            ## Daily Summary
            - Discussed Kotlin coroutines
            - Reviewed Room DB migration steps

            ## Long-term Facts
            - User prefers concise answers
            - Project uses Koin for DI
        """.trimIndent()

        val (daily, longTerm) = writer.parseSummarizationResponse(response)
        assertTrue(daily.contains("Discussed Kotlin coroutines"))
        assertTrue(longTerm.contains("User prefers concise answers"))
    }

    @Test
    fun `parseSummarizationResponse handles None in long-term facts`() {
        val response = """
            ## Daily Summary
            - Quick chat about settings

            ## Long-term Facts
            None
        """.trimIndent()

        val (daily, longTerm) = writer.parseSummarizationResponse(response)
        assertTrue(daily.contains("Quick chat about settings"))
        assertTrue(longTerm.isEmpty())
    }

    @Test
    fun `parseSummarizationResponse handles None with period`() {
        val response = """
            ## Daily Summary
            - Reviewed code

            ## Long-term Facts
            None.
        """.trimIndent()

        val (_, longTerm) = writer.parseSummarizationResponse(response)
        assertTrue(longTerm.isEmpty())
    }

    @Test
    fun `parseSummarizationResponse returns full response as daily if no sections`() {
        val response = "Some plain text response without headers"
        val (daily, longTerm) = writer.parseSummarizationResponse(response)
        assertEquals("Some plain text response without headers", daily)
        assertTrue(longTerm.isEmpty())
    }

    @Test
    fun `parseSummarizationResponse trims whitespace from extracted sections`() {
        val response = """
            ## Daily Summary
              - Topic with leading spaces

            ## Long-term Facts
              - Fact with spaces
        """.trimIndent()

        val (daily, longTerm) = writer.parseSummarizationResponse(response)
        assertTrue(!daily.startsWith("  "))
        assertTrue(!longTerm.startsWith("  "))
    }

    @Test
    fun `parseSummarizationResponse is case insensitive for section headers`() {
        val response = """
            ## daily summary
            - lowercase headers

            ## long-term facts
            - also lowercase
        """.trimIndent()

        val (daily, longTerm) = writer.parseSummarizationResponse(response)
        assertTrue(daily.contains("lowercase headers"))
        assertTrue(longTerm.contains("also lowercase"))
    }
}
