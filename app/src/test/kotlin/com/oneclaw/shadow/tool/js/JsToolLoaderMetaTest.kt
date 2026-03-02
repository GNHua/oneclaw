package com.oneclaw.shadow.tool.js

import android.content.Context
import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

/**
 * Tests for RFC-040: _meta entry parsing in JsToolLoader.
 *
 * Covers:
 * - _meta entry extracted as ToolGroupDefinition, not as a tool
 * - Correct displayName and description from _meta
 * - Auto-generated ToolGroupDefinition when no _meta present
 * - Tool entries after _meta are parsed correctly
 * - parseJsonManifest backward compatibility (returns only tools, no groupDef)
 */
class JsToolLoaderMetaTest {

    private lateinit var loader: JsToolLoader

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        val context = mockk<Context>(relaxed = true)
        val engine = mockk<JsExecutionEngine>()
        val envVarStore = mockk<EnvironmentVariableStore>()
        coEvery { envVarStore.getAll() } returns emptyMap()
        loader = JsToolLoader(context, engine, envVarStore)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `_meta entry is extracted as ToolGroupDefinition with correct fields`() {
        val json = """
        [
          {"_meta": true, "display_name": "Google Gmail", "description": "Email tools"},
          {"name": "gmail_search", "description": "Search email", "function": "gmailSearch"}
        ]
        """.trimIndent()

        val (tools, groupDef) = loader.parseJsonManifestWithMeta(json, "google_gmail", "google_gmail.json")

        assertEquals(1, tools.size)
        assertNotNull(groupDef)
        assertEquals("google_gmail", groupDef!!.name)
        assertEquals("Google Gmail", groupDef.displayName)
        assertEquals("Email tools", groupDef.description)
    }

    @Test
    fun `_meta entry is not registered as a tool`() {
        val json = """
        [
          {"_meta": true, "display_name": "Test Group", "description": "Test"},
          {"name": "test_tool", "description": "A test", "function": "testFn"}
        ]
        """.trimIndent()

        val (tools, _) = loader.parseJsonManifestWithMeta(json, "test", "test.json")

        assertEquals(1, tools.size)
        assertEquals("test_tool", tools[0].first.name)
    }

    @Test
    fun `auto-generates ToolGroupDefinition when no _meta entry`() {
        val json = """
        [
          {"name": "tool_one", "description": "Tool 1", "function": "toolOne"},
          {"name": "tool_two", "description": "Tool 2", "function": "toolTwo"}
        ]
        """.trimIndent()

        val (tools, groupDef) = loader.parseJsonManifestWithMeta(json, "my_tools", "my_tools.json")

        assertEquals(2, tools.size)
        assertNotNull(groupDef)
        assertEquals("my_tools", groupDef!!.name)
        assertEquals("My Tools", groupDef.displayName)
        assertTrue(groupDef.description.contains("my_tools"))
    }

    @Test
    fun `auto-generated displayName capitalizes each word`() {
        val json = """
        [
          {"name": "google_sheets_get", "description": "Get sheet", "function": "sheetsGet"}
        ]
        """.trimIndent()

        val (_, groupDef) = loader.parseJsonManifestWithMeta(json, "google_sheets", "google_sheets.json")

        assertNotNull(groupDef)
        assertEquals("Google Sheets", groupDef!!.displayName)
    }

    @Test
    fun `single-tool object manifest returns null groupDef`() {
        val json = """
        {
          "name": "get_current_time",
          "description": "Get the current time"
        }
        """.trimIndent()

        val (tools, groupDef) = loader.parseJsonManifestWithMeta(json, "get_current_time", "get_current_time.json")

        assertEquals(1, tools.size)
        assertNull(groupDef)
    }

    @Test
    fun `parseJsonManifest backward compatibility returns tool list only`() {
        val json = """
        [
          {"_meta": true, "display_name": "Test", "description": "Test desc"},
          {"name": "tool_a", "description": "Tool A", "function": "toolA"},
          {"name": "tool_b", "description": "Tool B", "function": "toolB"}
        ]
        """.trimIndent()

        val tools = loader.parseJsonManifest(json, "test_group", "test_group.json")

        assertEquals(2, tools.size)
        assertEquals("tool_a", tools[0].first.name)
        assertEquals("tool_b", tools[1].first.name)
    }

    @Test
    fun `_meta with partial fields uses defaults`() {
        val json = """
        [
          {"_meta": true},
          {"name": "my_tool", "description": "My tool", "function": "myTool"}
        ]
        """.trimIndent()

        val (tools, groupDef) = loader.parseJsonManifestWithMeta(json, "partial_meta", "partial_meta.json")

        assertEquals(1, tools.size)
        assertNotNull(groupDef)
        // Should fall back to auto-generated displayName
        assertEquals("Partial Meta", groupDef!!.displayName)
    }
}
