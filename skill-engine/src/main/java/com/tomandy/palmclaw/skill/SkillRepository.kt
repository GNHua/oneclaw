package com.tomandy.palmclaw.skill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SkillRepository(
    private val loader: SkillLoader,
    private val preferences: SkillPreferences
) {
    private val _skills = MutableStateFlow<List<SkillEntry>>(emptyList())
    val skills: StateFlow<List<SkillEntry>> = _skills.asStateFlow()

    /**
     * Load all skills with precedence: user overrides bundled.
     */
    fun loadAll() {
        val bundled = loader.loadBundledSkills()
        val user = loader.loadUserSkills()

        // User skills override bundled skills with same name
        val merged = (bundled.associateBy { it.metadata.name } +
            user.associateBy { it.metadata.name }).values.toList()

        _skills.value = merged
    }

    /**
     * Get all enabled skills.
     */
    fun getEnabledSkills(): List<SkillEntry> {
        return _skills.value.filter { skill ->
            preferences.isSkillEnabled(skill.metadata.name)
        }
    }

    /**
     * Reload all skills from disk. Call before each agent execution
     * to pick up skills created or edited via workspace file tools.
     */
    fun reload() = loadAll()

    /**
     * Find a skill by its /skill:name command.
     */
    fun findByCommand(command: String): SkillEntry? {
        val enabled = getEnabledSkills()
        return enabled.find { skill ->
            skill.metadata.command.equals(command, ignoreCase = true)
        }
    }

    /**
     * Load the full body of a skill on demand (reads from disk/assets).
     * Returns null if the skill file cannot be read.
     */
    fun loadBody(skill: SkillEntry): String? {
        return loader.loadBody(skill)
    }

    /**
     * Load the raw SKILL.md content (frontmatter + body) without parsing.
     * Used by the skill editor to display and edit full file content.
     */
    fun loadRawContent(skill: SkillEntry): String? {
        return loader.loadRawContent(skill)
    }
}
