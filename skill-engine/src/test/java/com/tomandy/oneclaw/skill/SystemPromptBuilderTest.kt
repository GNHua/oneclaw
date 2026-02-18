package com.tomandy.oneclaw.skill

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemPromptBuilderTest {

    private fun skill(
        name: String = "test",
        description: String = "A test skill",
        disableModelInvocation: Boolean = false,
        filePath: String? = "/path/to/SKILL.md"
    ) = SkillEntry(
        metadata = SkillMetadata(name, description, disableModelInvocation),
        source = SkillSource.BUNDLED,
        filePath = filePath
    )

    @Test
    fun `buildSkillsBlock returns empty string for empty list`() {
        assertEquals("", SystemPromptBuilder.buildSkillsBlock(emptyList()))
    }

    @Test
    fun `buildSkillsBlock filters out skills with disableModelInvocation true`() {
        val skills = listOf(
            skill(name = "visible"),
            skill(name = "hidden", disableModelInvocation = true)
        )

        val result = SystemPromptBuilder.buildSkillsBlock(skills)

        assertTrue(result.contains("visible"))
        assertFalse(result.contains("hidden"))
    }

    @Test
    fun `buildSkillsBlock generates XML with name description and location`() {
        val skills = listOf(skill(name = "my-skill", description = "Does things", filePath = "/skills/my-skill/SKILL.md"))

        val result = SystemPromptBuilder.buildSkillsBlock(skills)

        assertTrue(result.contains("<available_skills>"))
        assertTrue(result.contains("</available_skills>"))
        assertTrue(result.contains("<name>my-skill</name>"))
        assertTrue(result.contains("<description>Does things</description>"))
        assertTrue(result.contains("<location>/skills/my-skill/SKILL.md</location>"))
    }

    @Test
    fun `buildSkillsBlock omits location tag when filePath is null`() {
        val skills = listOf(skill(filePath = null))

        val result = SystemPromptBuilder.buildSkillsBlock(skills)

        assertFalse(result.contains("<location>"))
    }

    @Test
    fun `buildSkillsBlock escapes XML special characters`() {
        val skills = listOf(skill(
            name = "test",
            description = "Uses <tags> & \"quotes\""
        ))

        val result = SystemPromptBuilder.buildSkillsBlock(skills)

        assertTrue(result.contains("&amp;"))
        assertTrue(result.contains("&lt;tags&gt;"))
        assertTrue(result.contains("&quot;quotes&quot;"))
    }

    @Test
    fun `augmentSystemPrompt appends skills block to base prompt`() {
        val base = "You are a helpful assistant."
        val skills = listOf(skill())

        val result = SystemPromptBuilder.augmentSystemPrompt(base, skills)

        assertTrue(result.startsWith(base))
        assertTrue(result.contains("<available_skills>"))
    }

    @Test
    fun `buildFullSystemPrompt appends memory context when non-blank`() {
        val result = SystemPromptBuilder.buildFullSystemPrompt(
            basePrompt = "Base",
            skills = emptyList(),
            memoryContext = "Remember: user likes cats"
        )

        assertTrue(result.contains("Base"))
        assertTrue(result.contains("Remember: user likes cats"))
    }

    @Test
    fun `buildFullSystemPrompt skips memory context when blank`() {
        val result = SystemPromptBuilder.buildFullSystemPrompt(
            basePrompt = "Base",
            skills = emptyList(),
            memoryContext = "   "
        )

        assertEquals("Base", result)
    }
}
