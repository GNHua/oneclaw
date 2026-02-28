package com.oneclaw.shadow.tool.skill

import android.content.Context
import android.content.res.AssetManager
import android.util.Log
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.util.AppResult
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.File

class SkillRegistryTest {

    private lateinit var context: Context
    private lateinit var assetManager: AssetManager
    private lateinit var tempDir: File
    private lateinit var userSkillsDir: File
    private lateinit var registry: SkillRegistry
    private lateinit var parser: SkillFileParser

    @BeforeEach
    fun setup() {
        // android.util.Log is not available in JVM unit tests -- mock all static methods
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0

        tempDir = createTempDir("skill_registry_test")
        userSkillsDir = File(tempDir, "skills")
        userSkillsDir.mkdirs()

        assetManager = mockk<AssetManager>(relaxed = true)
        context = mockk<Context>(relaxed = true)
        every { context.filesDir } returns tempDir
        every { context.assets } returns assetManager

        // Mock empty assets/skills directory
        every { assetManager.list("skills") } returns emptyArray()

        parser = SkillFileParser()
        registry = SkillRegistry(context, parser)
    }

    @AfterEach
    fun teardown() {
        unmockkStatic(Log::class)
        tempDir.deleteRecursively()
    }

    private fun createUserSkill(name: String, content: String) {
        val skillDir = File(userSkillsDir, name)
        skillDir.mkdirs()
        File(skillDir, "SKILL.md").writeText(content)
    }

    private fun validSkillContent(name: String, displayName: String, desc: String) = """
        ---
        name: $name
        display_name: "$displayName"
        description: "$desc"
        ---

        This is the prompt content.
    """.trimIndent()

    @Test
    fun `initialize scans user skills directory`() {
        createUserSkill("my-skill", validSkillContent("my-skill", "My Skill", "desc"))
        registry.initialize()

        assertTrue(registry.hasSkill("my-skill"))
        assertEquals(1, registry.getAllSkills().size)
    }

    @Test
    fun `initialize skips invalid skill directories gracefully`() {
        createUserSkill("good-skill", validSkillContent("good-skill", "Good Skill", "desc"))
        // Create an invalid SKILL.md
        val badDir = File(userSkillsDir, "bad-skill")
        badDir.mkdirs()
        File(badDir, "SKILL.md").writeText("invalid content no frontmatter")

        registry.initialize()

        // Only good skill should be loaded
        assertEquals(1, registry.getAllSkills().size)
        assertTrue(registry.hasSkill("good-skill"))
    }

    @Test
    fun `getSkill returns definition for registered skill`() {
        createUserSkill("my-skill", validSkillContent("my-skill", "My Skill", "desc"))
        registry.initialize()

        val skill = registry.getSkill("my-skill")
        assertNotNull(skill)
        assertEquals("My Skill", skill!!.displayName)
    }

    @Test
    fun `getSkill returns null for unknown skill`() {
        registry.initialize()
        assertNull(registry.getSkill("nonexistent"))
    }

    @Test
    fun `loadSkillContent returns prompt content for registered skill`() {
        createUserSkill("my-skill", validSkillContent("my-skill", "My Skill", "desc"))
        registry.initialize()

        val result = registry.loadSkillContent("my-skill")
        assertInstanceOf(AppResult.Success::class.java, result)
        assertTrue((result as AppResult.Success).data.contains("prompt content"))
    }

    @Test
    fun `loadSkillContent returns error for unknown skill`() {
        registry.initialize()
        val result = registry.loadSkillContent("nonexistent")
        assertInstanceOf(AppResult.Error::class.java, result)
    }

    @Test
    fun `createSkill adds new user skill`() {
        registry.initialize()

        val definition = SkillDefinition(
            name = "new-skill",
            displayName = "New Skill",
            description = "A new skill",
            isBuiltIn = false,
            directoryPath = ""
        )
        val result = registry.createSkill(definition, "Prompt for new skill.")
        assertInstanceOf(AppResult.Success::class.java, result)
        assertTrue(registry.hasSkill("new-skill"))

        // Verify file was written
        val skillFile = File(tempDir, "skills/new-skill/SKILL.md")
        assertTrue(skillFile.exists())
    }

    @Test
    fun `createSkill returns error for duplicate name`() {
        createUserSkill("existing", validSkillContent("existing", "Existing", "desc"))
        registry.initialize()

        val definition = SkillDefinition(
            name = "existing",
            displayName = "Existing",
            description = "duplicate",
            isBuiltIn = false,
            directoryPath = ""
        )
        val result = registry.createSkill(definition, "Prompt")
        assertInstanceOf(AppResult.Error::class.java, result)
        assertTrue((result as AppResult.Error).message.contains("already exists"))
    }

    @Test
    fun `deleteSkill removes user skill`() {
        createUserSkill("to-delete", validSkillContent("to-delete", "To Delete", "desc"))
        registry.initialize()
        assertTrue(registry.hasSkill("to-delete"))

        val result = registry.deleteSkill("to-delete")
        assertInstanceOf(AppResult.Success::class.java, result)
        assertTrue(!registry.hasSkill("to-delete"))
    }

    @Test
    fun `deleteSkill returns error for unknown skill`() {
        registry.initialize()
        val result = registry.deleteSkill("nonexistent")
        assertInstanceOf(AppResult.Error::class.java, result)
    }

    @Test
    fun `updateSkill modifies existing user skill`() {
        createUserSkill("editable", validSkillContent("editable", "Editable", "original desc"))
        registry.initialize()

        val existing = registry.getSkill("editable")!!
        val updated = existing.copy(description = "updated desc")
        val result = registry.updateSkill("editable", updated, "Updated prompt content.")
        assertInstanceOf(AppResult.Success::class.java, result)
        assertEquals("updated desc", registry.getSkill("editable")?.description)
    }

    @Test
    fun `exportSkill returns serialized SKILL_md content`() {
        createUserSkill("exportable", validSkillContent("exportable", "Exportable", "desc"))
        registry.initialize()

        val result = registry.exportSkill("exportable")
        assertInstanceOf(AppResult.Success::class.java, result)
        val content = (result as AppResult.Success).data
        assertTrue(content.contains("name: exportable"))
        assertTrue(content.contains("---"))
    }

    @Test
    fun `importSkill creates skill from SKILL_md content string`() {
        registry.initialize()

        val content = validSkillContent("imported-skill", "Imported Skill", "imported desc")
        val result = registry.importSkill(content)
        assertInstanceOf(AppResult.Success::class.java, result)
        assertTrue(registry.hasSkill("imported-skill"))
    }

    @Test
    fun `importSkill returns error for invalid content`() {
        registry.initialize()
        val result = registry.importSkill("not valid SKILL.md content")
        assertInstanceOf(AppResult.Error::class.java, result)
    }

    @Test
    fun `generateRegistryPrompt returns formatted skill list`() {
        createUserSkill("skill-one", validSkillContent("skill-one", "Skill One", "First skill"))
        createUserSkill("skill-two", validSkillContent("skill-two", "Skill Two", "Second skill"))
        registry.initialize()

        val prompt = registry.generateRegistryPrompt()
        assertTrue(prompt.contains("Available Skills"))
        assertTrue(prompt.contains("skill-one"))
        assertTrue(prompt.contains("skill-two"))
        assertTrue(prompt.contains("load_skill"))
    }

    @Test
    fun `generateRegistryPrompt returns empty string when no skills`() {
        registry.initialize()
        val prompt = registry.generateRegistryPrompt()
        assertEquals("", prompt)
    }

    @Test
    fun `getUserSkills returns only non-built-in skills`() {
        createUserSkill("user-skill", validSkillContent("user-skill", "User Skill", "desc"))
        registry.initialize()

        val userSkills = registry.getUserSkills()
        assertEquals(1, userSkills.size)
        assertTrue(!userSkills[0].isBuiltIn)
    }

    @Test
    fun `refresh re-scans directories`() {
        registry.initialize()
        assertEquals(0, registry.getAllSkills().size)

        // Create a skill after initialization
        createUserSkill("new-skill", validSkillContent("new-skill", "New Skill", "desc"))
        registry.refresh()

        assertEquals(1, registry.getAllSkills().size)
    }
}
