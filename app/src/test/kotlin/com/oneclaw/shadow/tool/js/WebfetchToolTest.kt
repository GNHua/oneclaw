package com.oneclaw.shadow.tool.js

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.core.model.ToolResultStatus
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for the webfetch built-in JS tool.
 *
 * These tests verify the JsTool wrapper behavior for the webfetch tool via a mocked
 * JsExecutionEngine. The actual HTML-to-Markdown conversion logic in webfetch.js
 * is exercised at runtime through the QuickJS engine.
 */
class WebfetchToolTest {

    private lateinit var engine: JsExecutionEngine
    private lateinit var envVarStore: EnvironmentVariableStore

    private val webfetchDefinition = ToolDefinition(
        name = "webfetch",
        description = "Fetch a web page and return its content as Markdown",
        parametersSchema = ToolParametersSchema(
            properties = emptyMap(),
            required = listOf("url")
        ),
        requiredPermissions = emptyList(),
        timeoutSeconds = 30
    )

    private val webfetchJsSource = """
        async function execute(params) {
            var url = params.url;
            if (!url) return { error: "Parameter 'url' is required" };
            var response = await fetch(url);
            if (!response.ok) {
                return { error: "HTTP " + response.status + ": " + response.statusText, url: url };
            }
            var body = await response.text();
            var contentType = (response.headers["content-type"] || "").toLowerCase();
            if (contentType.indexOf("text/html") === -1) {
                return body;
            }
            var TurndownService = lib("turndown");
            var td = new TurndownService({ headingStyle: "atx" });
            return td.turndown(body);
        }
    """.trimIndent()

    @BeforeEach
    fun setup() {
        engine = mockk()
        envVarStore = mockk()
        coEvery { envVarStore.getAll() } returns emptyMap()
    }

    private fun makeWebfetchTool() = JsTool(
        definition = webfetchDefinition,
        jsSource = webfetchJsSource,
        jsExecutionEngine = engine,
        envVarStore = envVarStore
    )

    @Test
    fun `webfetch tool returns success with Markdown for HTML page`() = runTest {
        val expectedMarkdown = "# Hello World\n\nThis is a test page.\n\n- Item 1\n- Item 2"
        coEvery {
            engine.executeFromSource(
                jsSource = webfetchJsSource,
                toolName = "webfetch",
                functionName = null,
                params = mapOf("url" to "https://example.com"),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns ToolResult.success(expectedMarkdown)

        val result = makeWebfetchTool().execute(mapOf("url" to "https://example.com"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(expectedMarkdown, result.result)
    }

    @Test
    fun `webfetch tool returns raw body for non-HTML content`() = runTest {
        val jsonBody = """{"key": "value"}"""
        coEvery {
            engine.executeFromSource(any(), eq("webfetch"), any(), any(), any(), any())
        } returns ToolResult.success(jsonBody)

        val result = makeWebfetchTool().execute(mapOf("url" to "https://api.example.com/data.json"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals(jsonBody, result.result)
    }

    @Test
    fun `webfetch tool returns error object for HTTP error response`() = runTest {
        val errorJson = """{"error":"HTTP 404: Not Found","url":"https://example.com/missing"}"""
        coEvery {
            engine.executeFromSource(any(), eq("webfetch"), any(), any(), any(), any())
        } returns ToolResult.success(errorJson)

        val result = makeWebfetchTool().execute(mapOf("url" to "https://example.com/missing"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("404"))
    }

    @Test
    fun `webfetch tool routes to executeFromSource not execute`() = runTest {
        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns ToolResult.success("# Markdown")

        makeWebfetchTool().execute(mapOf("url" to "https://example.com"))

        coVerify(exactly = 1) { engine.executeFromSource(any(), any(), any(), any(), any(), any()) }
        coVerify(exactly = 0) { engine.execute(any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun `webfetch tool passes url parameter correctly`() = runTest {
        val url = "https://test.example.com/article"
        coEvery {
            engine.executeFromSource(
                jsSource = webfetchJsSource,
                toolName = "webfetch",
                functionName = null,
                params = mapOf("url" to url),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        } returns ToolResult.success("# Article\n\nContent here.")

        val result = makeWebfetchTool().execute(mapOf("url" to url))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        coVerify(exactly = 1) {
            engine.executeFromSource(
                jsSource = webfetchJsSource,
                toolName = "webfetch",
                functionName = null,
                params = mapOf("url" to url),
                env = emptyMap(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `webfetch tool returns execution_error on engine failure`() = runTest {
        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns ToolResult.error("execution_error", "JS tool 'webfetch' failed: network error")

        val result = makeWebfetchTool().execute(mapOf("url" to "https://unreachable.invalid/"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("execution_error", result.errorType)
    }

    @Test
    fun `webfetch tool uses timeout from definition`() = runTest {
        coEvery {
            engine.executeFromSource(
                jsSource = any(),
                toolName = "webfetch",
                functionName = any(),
                params = any(),
                env = any(),
                timeoutSeconds = 30
            )
        } returns ToolResult.success("content")

        makeWebfetchTool().execute(mapOf("url" to "https://example.com"))

        coVerify {
            engine.executeFromSource(
                jsSource = any(),
                toolName = "webfetch",
                functionName = any(),
                params = any(),
                env = any(),
                timeoutSeconds = 30
            )
        }
    }

    @Test
    fun `webfetch headings links and code blocks are converted`() = runTest {
        val markdownWithFormatting = "# Main Heading\n\n## Sub Heading\n\n[Link](https://example.com)\n\n```kotlin\nval x = 1\n```"
        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns ToolResult.success(markdownWithFormatting)

        val result = makeWebfetchTool().execute(mapOf("url" to "https://example.com/docs"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("#"))
    }

    @Test
    fun `webfetch empty HTML body returns empty or minimal Markdown`() = runTest {
        coEvery {
            engine.executeFromSource(any(), any(), any(), any(), any(), any())
        } returns ToolResult.success("")

        val result = makeWebfetchTool().execute(mapOf("url" to "https://example.com/empty"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertEquals("", result.result)
    }
}
