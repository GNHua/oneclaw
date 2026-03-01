package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.feature.search.model.UnifiedSearchResult
import com.oneclaw.shadow.feature.search.usecase.SearchHistoryUseCase
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class SearchHistoryToolTest {

    private lateinit var searchHistoryUseCase: SearchHistoryUseCase
    private lateinit var tool: SearchHistoryTool

    @BeforeEach
    fun setup() {
        searchHistoryUseCase = mockk()
        tool = SearchHistoryTool(searchHistoryUseCase)

        // Default: return empty results
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns emptyList()
    }

    // --- Helpers ---

    private fun baseParams(overrides: Map<String, Any?> = emptyMap()): Map<String, Any?> {
        val defaults: Map<String, Any?> = mapOf("query" to "restaurant")
        return defaults + overrides
    }

    private fun makeResult(
        id: String,
        text: String,
        score: Float,
        sourceType: UnifiedSearchResult.SourceType = UnifiedSearchResult.SourceType.MESSAGE,
        sourceDate: String? = "2026-02-20",
        sessionTitle: String? = null
    ) = UnifiedSearchResult(
        id = id,
        text = text,
        sourceType = sourceType,
        sourceDate = sourceDate,
        sessionTitle = sessionTitle,
        rawScore = score,
        finalScore = score,
        createdAt = 1_700_000_000_000L
    )

    // --- Definition tests ---

    @Test
    fun testDefinition() {
        assertEquals("search_history", tool.definition.name)
        assertTrue(tool.definition.parametersSchema.required.contains("query"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("query"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("scope"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("date_from"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("date_to"))
        assertTrue(tool.definition.parametersSchema.properties.containsKey("max_results"))
        assertEquals(30, tool.definition.timeoutSeconds)
    }

    // --- Query validation tests ---

    @Test
    fun testExecute_emptyQuery() = runTest {
        val result = tool.execute(mapOf("query" to ""))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("query"))
    }

    @Test
    fun testExecute_blankQuery() = runTest {
        val result = tool.execute(mapOf("query" to "   "))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_missingQuery() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    // --- Scope validation tests ---

    @Test
    fun testExecute_invalidScope() = runTest {
        val result = tool.execute(baseParams(mapOf("scope" to "invalid_scope")))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("invalid_scope"))
    }

    @Test
    fun testExecute_scopeAll() = runTest {
        tool.execute(baseParams(mapOf("scope" to "all")))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 10) }
    }

    @Test
    fun testExecute_scopeMemory() = runTest {
        tool.execute(baseParams(mapOf("scope" to "memory")))

        coVerify { searchHistoryUseCase.search("restaurant", "memory", null, null, 10) }
    }

    @Test
    fun testExecute_scopeDailyLog() = runTest {
        tool.execute(baseParams(mapOf("scope" to "daily_log")))

        coVerify { searchHistoryUseCase.search("restaurant", "daily_log", null, null, 10) }
    }

    @Test
    fun testExecute_scopeSessions() = runTest {
        tool.execute(baseParams(mapOf("scope" to "sessions")))

        coVerify { searchHistoryUseCase.search("restaurant", "sessions", null, null, 10) }
    }

    @Test
    fun testExecute_scopeIsCaseInsensitive() = runTest {
        tool.execute(baseParams(mapOf("scope" to "ALL")))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 10) }
    }

    // --- Date validation tests ---

    @Test
    fun testExecute_invalidDateFromFormat() = runTest {
        val result = tool.execute(baseParams(mapOf("date_from" to "20-02-2026")))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("YYYY-MM-DD"))
    }

    @Test
    fun testExecute_invalidDateToFormat() = runTest {
        val result = tool.execute(baseParams(mapOf("date_to" to "not-a-date")))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("YYYY-MM-DD"))
    }

    @Test
    fun testExecute_invalidDateValue() = runTest {
        // Passes format regex but fails parsing (month 13)
        val result = tool.execute(baseParams(mapOf("date_from" to "2026-13-45")))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
    }

    @Test
    fun testExecute_validDateRange() = runTest {
        tool.execute(baseParams(mapOf("date_from" to "2026-01-01", "date_to" to "2026-03-01")))

        coVerify {
            searchHistoryUseCase.search(
                query = "restaurant",
                scope = "all",
                dateFrom = any(),   // epoch for 2026-01-01
                dateTo = any(),     // epoch for end of 2026-03-01
                maxResults = 10
            )
        }
    }

    @Test
    fun testExecute_dateToIsEndOfDay() = runTest {
        // Capture the dateTo value passed to the use case
        var capturedDateTo: Long? = null
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } answers {
            capturedDateTo = arg(3)
            emptyList()
        }

        tool.execute(baseParams(mapOf("date_to" to "2026-03-01")))

        // dateTo should include end-of-day (not midnight)
        val midnight = java.time.LocalDate.parse("2026-03-01")
            .atStartOfDay(java.time.ZoneId.systemDefault())
            .toInstant()
            .toEpochMilli()
        val endOfDay = midnight + 24 * 60 * 60 * 1000 - 1
        assertEquals(endOfDay, capturedDateTo)
    }

    // --- max_results tests ---

    @Test
    fun testExecute_defaultMaxResults() = runTest {
        tool.execute(baseParams())

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 10) }
    }

    @Test
    fun testExecute_customMaxResults() = runTest {
        tool.execute(baseParams(mapOf("max_results" to 25)))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 25) }
    }

    @Test
    fun testExecute_maxResultsClamped() = runTest {
        tool.execute(baseParams(mapOf("max_results" to 200)))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 50) }
    }

    @Test
    fun testExecute_maxResultsMinClamped() = runTest {
        tool.execute(baseParams(mapOf("max_results" to 0)))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 1) }
    }

    @Test
    fun testExecute_maxResultsAsString() = runTest {
        tool.execute(baseParams(mapOf("max_results" to "15")))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 15) }
    }

    @Test
    fun testExecute_maxResultsAsDouble() = runTest {
        tool.execute(baseParams(mapOf("max_results" to 20.0)))

        coVerify { searchHistoryUseCase.search("restaurant", "all", null, null, 20) }
    }

    // --- Success and result formatting tests ---

    @Test
    fun testExecute_simpleQuery() = runTest {
        val results = listOf(
            makeResult("msg_1", "I want to try Sakura Sushi in Shibuya", score = 0.87f, sourceDate = "2026-02-25")
        )
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns results

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("restaurant"))
        assertTrue(result.result!!.contains("1 results"))
        assertTrue(result.result!!.contains("Result 1"))
        assertTrue(result.result!!.contains("Sakura Sushi"))
    }

    @Test
    fun testExecute_noResults() = runTest {
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns emptyList()

        val result = tool.execute(baseParams(mapOf("query" to "quantum physics")))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("0 results"))
        assertTrue(result.result!!.contains("No matching results found"))
    }

    @Test
    fun testExecute_multipleResults() = runTest {
        val results = listOf(
            makeResult("m1", "memory text about restaurant", score = 0.87f,
                sourceType = UnifiedSearchResult.SourceType.DAILY_LOG, sourceDate = "2026-02-25"),
            makeResult("m2", "message about sushi", score = 0.64f,
                sourceType = UnifiedSearchResult.SourceType.MESSAGE, sourceDate = "2026-02-24"),
            makeResult("m3", "session preview", score = 0.52f,
                sourceType = UnifiedSearchResult.SourceType.SESSION, sourceDate = "2026-02-20",
                sessionTitle = "Restaurant Recommendations")
        )
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns results

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("3 results"))
        assertTrue(result.result!!.contains("Result 1"))
        assertTrue(result.result!!.contains("Result 2"))
        assertTrue(result.result!!.contains("Result 3"))
        assertTrue(result.result!!.contains("daily_log"))
        assertTrue(result.result!!.contains("message"))
        assertTrue(result.result!!.contains("session"))
        assertTrue(result.result!!.contains("Restaurant Recommendations"))
    }

    @Test
    fun testExecute_outputIncludesScore() = runTest {
        val results = listOf(makeResult("r1", "test content", score = 0.75f))
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns results

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("0.75"))
    }

    @Test
    fun testExecute_outputIncludesDate() = runTest {
        val results = listOf(makeResult("r1", "test content", score = 0.5f, sourceDate = "2026-02-15"))
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns results

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("2026-02-15"))
    }

    @Test
    fun testExecute_outputIncludesSessionTitle() = runTest {
        val results = listOf(makeResult("r1", "session text", score = 0.5f,
            sourceType = UnifiedSearchResult.SourceType.SESSION, sessionTitle = "My Important Session"))
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } returns results

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("My Important Session"))
    }

    // --- Exception handling tests ---

    @Test
    fun testExecute_useCaseThrowsException() = runTest {
        coEvery { searchHistoryUseCase.search(any(), any(), any(), any(), any()) } throws RuntimeException("DB error")

        val result = tool.execute(baseParams())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("search_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("DB error"))
    }

    // --- Scope header in output ---

    @Test
    fun testExecute_outputIncludesScope() = runTest {
        tool.execute(baseParams(mapOf("scope" to "memory")))

        coVerify { searchHistoryUseCase.search(any(), "memory", any(), any(), any()) }
    }
}
