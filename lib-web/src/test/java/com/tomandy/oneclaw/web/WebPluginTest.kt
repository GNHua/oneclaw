package com.tomandy.oneclaw.web

import com.tomandy.oneclaw.engine.PluginContext
import com.tomandy.oneclaw.engine.ToolResult
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import okhttp3.OkHttpClient
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class WebPluginTest {

    private lateinit var plugin: WebPlugin
    private lateinit var mockContext: PluginContext

    @Before
    fun setUp() = runTest {
        mockContext = mockk(relaxed = true)
        every { mockContext.httpClient } returns OkHttpClient()
        // Return null for credentials so search will fail with a clear message
        coEvery { mockContext.getCredential(any()) } returns null

        plugin = WebPlugin()
        plugin.onLoad(mockContext)
    }

    @Test
    fun `web_search fails with missing query`() = runTest {
        val result = plugin.execute("web_search", buildJsonObject { })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("query"))
    }

    @Test
    fun `web_fetch fails with missing url`() = runTest {
        val result = plugin.execute("web_fetch", buildJsonObject { })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("url"))
    }

    @Test
    fun `unknown tool returns failure`() = runTest {
        val result = plugin.execute("unknown_tool", buildJsonObject { })
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("Unknown tool"))
    }

    @Test
    fun `web_search fails when no API key configured`() = runTest {
        val args = buildJsonObject {
            put("query", JsonPrimitive("test query"))
        }
        val result = plugin.execute("web_search", args)
        assertTrue(result is ToolResult.Failure)
        assertTrue((result as ToolResult.Failure).error.contains("failed") ||
            result.error.contains("API key") ||
            result.error.contains("not configured"))
    }

    @Test
    fun `web_fetch fails with invalid url`() = runTest {
        val args = buildJsonObject {
            put("url", JsonPrimitive("not-a-valid-url"))
        }
        val result = plugin.execute("web_fetch", args)
        assertTrue(result is ToolResult.Failure)
    }

    @Test
    fun `metadata has correct tool count`() {
        val metadata = WebPluginMetadata.get()
        assertTrue(metadata.tools.size == 2)
        assertTrue(metadata.tools.any { it.name == "web_search" })
        assertTrue(metadata.tools.any { it.name == "web_fetch" })
    }

    @Test
    fun `metadata category is web`() {
        val metadata = WebPluginMetadata.get()
        assertTrue(metadata.category == "web")
    }
}
