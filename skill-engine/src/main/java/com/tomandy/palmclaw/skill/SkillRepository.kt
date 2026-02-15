package com.tomandy.palmclaw.skill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class SkillRepository(
    private val loader: SkillLoader,
    private val preferences: SkillPreferences,
    private val eligibilityChecker: SkillEligibilityChecker
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
     * Get all eligible skills (enabled + requirements met).
     */
    fun getEligibleSkills(): List<SkillEntry> {
        return _skills.value.filter { skill ->
            preferences.isSkillEnabled(skill.metadata.name, skill.metadata.defaultEnabled) &&
                eligibilityChecker.isEligible(skill)
        }
    }

    /**
     * Find a skill by its slash command.
     */
    fun findByCommand(command: String): SkillEntry? {
        val eligible = getEligibleSkills()
        return eligible.find {
            it.metadata.command.equals(command, ignoreCase = true)
        }
    }
}
