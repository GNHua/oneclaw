package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.ToolResultStatus
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.tool.skill.SkillRegistry
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class LoadSkillToolTest {

    private lateinit var skillRegistry: SkillRegistry
    private lateinit var tool: LoadSkillTool

    private val sampleSkill = SkillDefinition(
        name = "summarize-file",
        displayName = "Summarize File",
        description = "Read and summarize a file",
        toolsRequired = listOf("read_file"),
        isBuiltIn = true,
        directoryPath = "assets://skills/summarize-file"
    )

    @BeforeEach
    fun setup() {
        skillRegistry = mockk<SkillRegistry>(relaxed = true)
        tool = LoadSkillTool(skillRegistry)
    }

    @Test
    fun `execute returns skill content on success`() = runTest {
        every { skillRegistry.getSkill("summarize-file") } returns sampleSkill
        every { skillRegistry.loadSkillContent("summarize-file") } returns
            AppResult.Success("## Instructions\n1. Read the file\n2. Summarize")

        val result = tool.execute(mapOf("name" to "summarize-file"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("Summarize File"))
        assertTrue(result.result!!.contains("Read and summarize a file"))
        assertTrue(result.result!!.contains("read_file"))
        assertTrue(result.result!!.contains("## Instructions"))
    }

    @Test
    fun `execute returns validation_error when name parameter is missing`() = runTest {
        val result = tool.execute(emptyMap())

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("validation_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("name"))
    }

    @Test
    fun `execute returns skill_not_found when skill does not exist`() = runTest {
        every { skillRegistry.getSkill("nonexistent") } returns null
        every { skillRegistry.getAllSkills() } returns listOf(sampleSkill)

        val result = tool.execute(mapOf("name" to "nonexistent"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("skill_not_found", result.errorType)
        assertTrue(result.errorMessage!!.contains("summarize-file"))
    }

    @Test
    fun `execute returns load_error when skill content cannot be loaded`() = runTest {
        every { skillRegistry.getSkill("summarize-file") } returns sampleSkill
        every { skillRegistry.loadSkillContent("summarize-file") } returns
            AppResult.Error(message = "File not found", code = com.oneclaw.shadow.core.util.ErrorCode.STORAGE_ERROR)

        val result = tool.execute(mapOf("name" to "summarize-file"))

        assertEquals(ToolResultStatus.ERROR, result.status)
        assertEquals("load_error", result.errorType)
        assertTrue(result.errorMessage!!.contains("summarize-file"))
    }

    @Test
    fun `execute includes parameters section when skill has parameters`() = runTest {
        val skillWithParams = sampleSkill.copy(
            parameters = listOf(
                com.oneclaw.shadow.core.model.SkillParameter(
                    name = "file_path",
                    type = "string",
                    required = true,
                    description = "Path to the file"
                )
            )
        )
        every { skillRegistry.getSkill("summarize-file") } returns skillWithParams
        every { skillRegistry.loadSkillContent("summarize-file") } returns
            AppResult.Success("Prompt content")

        val result = tool.execute(mapOf("name" to "summarize-file"))

        assertEquals(ToolResultStatus.SUCCESS, result.status)
        assertTrue(result.result!!.contains("file_path"))
        assertTrue(result.result!!.contains("(required)"))
    }

    @Test
    fun `definition has correct name and required parameters`() {
        assertEquals("load_skill", tool.definition.name)
        assertTrue(tool.definition.parametersSchema.required.contains("name"))
        assertTrue(tool.definition.requiredPermissions.isEmpty())
    }
}
