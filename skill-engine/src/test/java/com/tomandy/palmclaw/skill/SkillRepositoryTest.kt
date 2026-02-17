package com.tomandy.palmclaw.skill

import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SkillRepositoryTest {

    private lateinit var mockLoader: SkillLoader
    private lateinit var mockPreferences: SkillPreferences
    private lateinit var repository: SkillRepository

    private fun skill(name: String, source: SkillSource = SkillSource.BUNDLED) = SkillEntry(
        metadata = SkillMetadata(name, "Description for $name"),
        source = source
    )

    @Before
    fun setup() {
        mockLoader = mockk(relaxed = true)
        mockPreferences = mockk(relaxed = true)
        every { mockLoader.loadBundledSkills() } returns emptyList()
        every { mockLoader.loadUserSkills() } returns emptyList()
        every { mockPreferences.isSkillEnabled(any(), any()) } returns true
        repository = SkillRepository(mockLoader, mockPreferences)
    }

    @Test
    fun `loadAll merges bundled and user skills`() {
        every { mockLoader.loadBundledSkills() } returns listOf(skill("explain"))
        every { mockLoader.loadUserSkills() } returns listOf(skill("review", SkillSource.USER))

        repository.loadAll()

        val names = repository.skills.value.map { it.metadata.name }
        assertTrue(names.contains("explain"))
        assertTrue(names.contains("review"))
        assertEquals(2, names.size)
    }

    @Test
    fun `loadAll user skills override bundled skills with same name`() {
        val bundled = skill("explain", SkillSource.BUNDLED)
        val user = skill("explain", SkillSource.USER)
        every { mockLoader.loadBundledSkills() } returns listOf(bundled)
        every { mockLoader.loadUserSkills() } returns listOf(user)

        repository.loadAll()

        val result = repository.skills.value.single()
        assertEquals(SkillSource.USER, result.source)
    }

    @Test
    fun `getEnabledSkills filters by preferences`() {
        every { mockLoader.loadBundledSkills() } returns listOf(skill("a"), skill("b"))
        every { mockPreferences.isSkillEnabled("a", any()) } returns true
        every { mockPreferences.isSkillEnabled("b", any()) } returns false
        repository.loadAll()

        val enabled = repository.getEnabledSkills()

        assertEquals(1, enabled.size)
        assertEquals("a", enabled.single().metadata.name)
    }

    @Test
    fun `findByCommand returns matching enabled skill`() {
        every { mockLoader.loadBundledSkills() } returns listOf(skill("explain"))
        repository.loadAll()

        val result = repository.findByCommand("/skill:explain")

        assertEquals("explain", result?.metadata?.name)
    }

    @Test
    fun `findByCommand is case-insensitive`() {
        every { mockLoader.loadBundledSkills() } returns listOf(skill("explain"))
        repository.loadAll()

        val result = repository.findByCommand("/SKILL:EXPLAIN")

        assertEquals("explain", result?.metadata?.name)
    }

    @Test
    fun `findByCommand returns null when no match`() {
        repository.loadAll()

        assertNull(repository.findByCommand("/skill:nonexistent"))
    }

}
