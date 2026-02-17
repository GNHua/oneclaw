package com.tomandy.palmclaw.skill

import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SlashCommandRouterTest {

    private lateinit var mockRepository: SkillRepository
    private lateinit var router: SlashCommandRouter

    @Before
    fun setup() {
        mockRepository = mockk(relaxed = true)
        router = SlashCommandRouter(mockRepository)
    }

    @Test
    fun `isSlashCommand returns true for slash-prefixed text`() {
        assertTrue(router.isSlashCommand("/skill:explain"))
        assertTrue(router.isSlashCommand("/summarize"))
        assertTrue(router.isSlashCommand("  /command  "))
    }

    @Test
    fun `isSlashCommand returns false for empty string, single slash, and double slash`() {
        assertFalse(router.isSlashCommand(""))
        assertFalse(router.isSlashCommand("/"))
        assertFalse(router.isSlashCommand("//comment"))
        assertFalse(router.isSlashCommand("not a command"))
    }

    @Test
    fun `parse extracts command and arguments`() {
        val result = router.parse("/skill:explain how does this work")

        assertEquals("/skill:explain", result?.command)
        assertEquals("how does this work", result?.arguments)
    }

    @Test
    fun `parse returns command with empty arguments when no space`() {
        val result = router.parse("/skill:explain")

        assertEquals("/skill:explain", result?.command)
        assertEquals("", result?.arguments)
    }

    @Test
    fun `parse lowercases the command`() {
        val result = router.parse("/Skill:Explain some arg")

        assertEquals("/skill:explain", result?.command)
    }

    @Test
    fun `resolve delegates to repository findByCommand`() {
        val skill = SkillEntry(
            metadata = SkillMetadata("explain", "Explain things"),
            source = SkillSource.BUNDLED
        )
        every { mockRepository.findByCommand("/skill:explain") } returns skill

        val command = SlashCommandRouter.SlashCommand("/skill:explain", "")
        val result = router.resolve(command)

        assertEquals(skill, result)
        verify { mockRepository.findByCommand("/skill:explain") }
    }
}
