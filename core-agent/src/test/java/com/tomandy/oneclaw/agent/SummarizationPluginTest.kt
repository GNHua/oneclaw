package com.tomandy.oneclaw.agent

import com.tomandy.oneclaw.engine.ToolResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SummarizationPluginTest {

    @Test
    fun `execute with correct tool name calls onSummarize and returns Success`() = runTest {
        var called = false
        val plugin = SummarizationPlugin { called = true; "Summary complete" }

        val result = plugin.execute("summarize_conversation", buildJsonObject {})

        assertTrue(called)
        assertTrue(result is ToolResult.Success)
        assertEquals("Summary complete", (result as ToolResult.Success).output)
    }

    @Test
    fun `execute with wrong tool name returns Failure`() = runTest {
        val plugin = SummarizationPlugin { "should not be called" }

        val result = plugin.execute("wrong_tool", buildJsonObject {})

        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Unknown tool"))
    }

    @Test
    fun `execute returns Failure when onSummarize throws`() = runTest {
        val plugin = SummarizationPlugin { throw RuntimeException("summarization broke") }

        val result = plugin.execute("summarize_conversation", buildJsonObject {})

        assertTrue(result is ToolResult.Failure)
        val failure = result as ToolResult.Failure
        assertTrue(failure.error.contains("summarization broke"))
        assertTrue(failure.error.contains("Summarization failed"))
    }

    @Test
    fun `metadata returns correct plugin ID and tool name`() {
        val metadata = SummarizationPlugin.metadata()

        assertEquals("summarization", metadata.id)
        assertEquals(1, metadata.tools.size)
        assertEquals("summarize_conversation", metadata.tools[0].name)
    }

    @Test
    fun `createLoadedPlugin returns LoadedPlugin with correct metadata and working instance`() = runTest {
        val loadedPlugin = SummarizationPlugin.createLoadedPlugin { "done" }

        assertEquals("summarization", loadedPlugin.metadata.id)

        val result = loadedPlugin.instance.execute("summarize_conversation", buildJsonObject {})
        assertTrue(result is ToolResult.Success)
        assertEquals("done", (result as ToolResult.Success).output)
    }
}
