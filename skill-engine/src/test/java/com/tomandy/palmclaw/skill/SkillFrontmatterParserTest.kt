package com.tomandy.palmclaw.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SkillFrontmatterParserTest {

    @Test
    fun `parse extracts metadata and body from valid content`() {
        val content = """
            ---
            name: my-skill
            description: A test skill
            ---
            Body content here.
        """.trimIndent()

        val result = SkillFrontmatterParser.parse(content)

        assertEquals("my-skill", result.metadata.name)
        assertEquals("A test skill", result.metadata.description)
        assertEquals("Body content here.", result.body)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse throws when content does not start with delimiter`() {
        SkillFrontmatterParser.parse("no frontmatter here")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse throws when closing delimiter is missing`() {
        SkillFrontmatterParser.parse("---\nname: test\ndescription: desc\n")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse throws when name is missing`() {
        SkillFrontmatterParser.parse("---\ndescription: desc\n---\nBody")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parse throws when description is missing`() {
        SkillFrontmatterParser.parse("---\nname: test\n---\nBody")
    }

    @Test
    fun `parseMetadataOnly returns metadata without body`() {
        val content = "---\nname: test\ndescription: desc\n---\nBody content"

        val metadata = SkillFrontmatterParser.parseMetadataOnly(content)

        assertEquals("test", metadata.name)
        assertEquals("desc", metadata.description)
    }

    @Test
    fun `unquotes double-quoted values`() {
        val content = "---\nname: \"my-skill\"\ndescription: \"A description\"\n---\n"

        val result = SkillFrontmatterParser.parse(content)

        assertEquals("my-skill", result.metadata.name)
        assertEquals("A description", result.metadata.description)
    }

    @Test
    fun `parses disable-model-invocation true`() {
        val content = "---\nname: test\ndescription: desc\ndisable-model-invocation: true\n---\n"

        val result = SkillFrontmatterParser.parse(content)

        assertTrue(result.metadata.disableModelInvocation)
    }

    @Test
    fun `invalid boolean for disable-model-invocation defaults to false`() {
        val content = "---\nname: test\ndescription: desc\ndisable-model-invocation: maybe\n---\n"

        val result = SkillFrontmatterParser.parse(content)

        assertFalse(result.metadata.disableModelInvocation)
    }

    @Test
    fun `unknown frontmatter fields are silently ignored`() {
        val content = "---\nname: test\ndescription: desc\nauthor: someone\nversion: 1.0\n---\n"

        val result = SkillFrontmatterParser.parse(content)

        assertEquals("test", result.metadata.name)
        assertEquals("desc", result.metadata.description)
    }

}
