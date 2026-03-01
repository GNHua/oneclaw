package com.oneclaw.shadow.tool.skill

import android.util.Log
import com.oneclaw.shadow.core.util.AppResult
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SkillFileParserTest {

    private lateinit var parser: SkillFileParser
    private lateinit var tempDir: File

    @BeforeEach
    fun setup() {
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        parser = SkillFileParser()
        tempDir = createTempDir("skill_parser_test")
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    @Test
    fun `parseContent succeeds for valid minimal SKILL_md`() {
        val content = """
            ---
            name: my-skill
            display_name: "My Skill"
            description: "Does something"
            ---

            # Prompt content here
        """.trimIndent()

        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "/tmp")
        assertInstanceOf(AppResult.Success::class.java, result)
        val parsed = (result as AppResult.Success).data
        assertEquals("my-skill", parsed.definition.name)
        assertEquals("My Skill", parsed.definition.displayName)
        assertEquals("Does something", parsed.definition.description)
        assertEquals("1.0", parsed.definition.version)
        assertTrue(parsed.definition.toolsRequired.isEmpty())
        assertTrue(parsed.definition.parameters.isEmpty())
        assertTrue(parsed.promptContent.contains("Prompt content"))
    }

    @Test
    fun `parseContent succeeds with full frontmatter including parameters and tools`() {
        val content = """
            ---
            name: summarize-file
            display_name: "Summarize File"
            description: "Read a local file and produce a structured summary"
            version: "2.0"
            tools_required:
              - read_file
              - write_file
            parameters:
              - name: file_path
                type: string
                required: true
                description: "Path to file"
              - name: language
                type: string
                required: false
                description: "Output language"
            ---

            ## Instructions
            1. Read {{file_path}}
        """.trimIndent()

        val result = parser.parseContent(content, isBuiltIn = true, directoryPath = "assets://skills/summarize-file")
        assertInstanceOf(AppResult.Success::class.java, result)
        val parsed = (result as AppResult.Success).data
        assertEquals("summarize-file", parsed.definition.name)
        assertEquals("2.0", parsed.definition.version)
        assertEquals(listOf("read_file", "write_file"), parsed.definition.toolsRequired)
        assertEquals(2, parsed.definition.parameters.size)
        assertEquals("file_path", parsed.definition.parameters[0].name)
        assertTrue(parsed.definition.parameters[0].required)
        assertEquals("language", parsed.definition.parameters[1].name)
        assertTrue(!parsed.definition.parameters[1].required)
        assertTrue(parsed.definition.isBuiltIn)
        assertTrue(parsed.promptContent.contains("Instructions"))
    }

    @Test
    fun `parseContent fails when frontmatter delimiter is missing`() {
        val content = "name: my-skill\ndisplay_name: Test\n\nPrompt"
        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        assertInstanceOf(AppResult.Error::class.java, result)
        assertTrue((result as AppResult.Error).message.contains("frontmatter delimiter"))
    }

    @Test
    fun `parseContent fails when closing delimiter is absent`() {
        val content = "---\nname: my-skill\ndisplay_name: Test\ndescription: desc\nPrompt"
        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        assertInstanceOf(AppResult.Error::class.java, result)
        assertTrue((result as AppResult.Error).message.contains("not closed"))
    }

    @Test
    fun `parseContent fails when name field is missing`() {
        val content = """
            ---
            display_name: "My Skill"
            description: "desc"
            ---
            Prompt
        """.trimIndent()
        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        assertInstanceOf(AppResult.Error::class.java, result)
        assertTrue((result as AppResult.Error).message.contains("name"))
    }

    @Test
    fun `parseContent fails when name contains invalid characters`() {
        val content = """
            ---
            name: My Skill!
            display_name: "My Skill"
            description: "desc"
            ---
            Prompt
        """.trimIndent()
        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        assertInstanceOf(AppResult.Error::class.java, result)
    }

    @Test
    fun `parseContent fails when prompt content is empty`() {
        val content = """
            ---
            name: my-skill
            display_name: "My Skill"
            description: "desc"
            ---

        """.trimIndent()
        val result = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        assertInstanceOf(AppResult.Error::class.java, result)
        assertTrue((result as AppResult.Error).message.contains("empty"))
    }

    @Test
    fun `substituteParameters replaces placeholders correctly`() {
        val prompt = "Read the file at {{file_path}} and output in {{language}}."
        val result = parser.substituteParameters(
            prompt,
            mapOf("file_path" to "/tmp/test.txt", "language" to "Chinese")
        )
        assertEquals("Read the file at /tmp/test.txt and output in Chinese.", result)
    }

    @Test
    fun `substituteParameters leaves unknown placeholders unchanged`() {
        val prompt = "Use {{known}} and {{unknown}}."
        val result = parser.substituteParameters(prompt, mapOf("known" to "value"))
        assertEquals("Use value and {{unknown}}.", result)
    }

    @Test
    fun `serialize produces valid SKILL_md that round-trips back`() {
        val content = """
            ---
            name: round-trip
            display_name: "Round Trip"
            description: "Test round-trip serialization"
            version: "1.0"
            ---

            Prompt content for round trip.
        """.trimIndent()

        val parseResult = parser.parseContent(content, isBuiltIn = false, directoryPath = "/tmp")
        assertInstanceOf(AppResult.Success::class.java, parseResult)
        val parsed = (parseResult as AppResult.Success).data

        val serialized = parser.serialize(parsed.definition, parsed.promptContent)
        val reParseResult = parser.parseContent(serialized, isBuiltIn = false, directoryPath = "/tmp")
        assertInstanceOf(AppResult.Success::class.java, reParseResult)
        val reParsed = (reParseResult as AppResult.Success).data

        assertEquals(parsed.definition.name, reParsed.definition.name)
        assertEquals(parsed.definition.displayName, reParsed.definition.displayName)
        assertEquals(parsed.definition.description, reParsed.definition.description)
        assertEquals(parsed.promptContent.trim(), reParsed.promptContent.trim())
    }

    @Test
    fun `parse reads file from file system correctly`() {
        val skillDir = File(tempDir, "my-skill")
        skillDir.mkdirs()
        val skillFile = File(skillDir, "SKILL.md")
        skillFile.writeText("""
            ---
            name: my-skill
            display_name: "My Skill"
            description: "desc"
            ---

            File content prompt.
        """.trimIndent())

        val result = parser.parse(skillFile.absolutePath, isBuiltIn = false)
        assertInstanceOf(AppResult.Success::class.java, result)
        val parsed = (result as AppResult.Success).data
        assertEquals("my-skill", parsed.definition.name)
        assertTrue(parsed.promptContent.contains("File content prompt"))
    }

    @Test
    fun `parse returns error for missing file`() {
        val result = parser.parse("/nonexistent/path/SKILL.md", isBuiltIn = false)
        assertInstanceOf(AppResult.Error::class.java, result)
    }
}
