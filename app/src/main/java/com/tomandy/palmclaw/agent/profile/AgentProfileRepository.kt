package com.tomandy.palmclaw.agent.profile

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class AgentProfileRepository(
    private val loader: AgentProfileLoader
) {
    private val _profiles = MutableStateFlow<List<AgentProfileEntry>>(emptyList())
    val profiles: StateFlow<List<AgentProfileEntry>> = _profiles.asStateFlow()

    fun loadAll() {
        loader.migrateDirectoryLayout()
        val bundled = loader.loadBundledProfiles()
        val user = loader.loadUserProfiles()
        val merged = (bundled.associateBy { it.name } +
            user.associateBy { it.name }).values.toList()
        _profiles.value = merged
    }

    fun reload() = loadAll()

    fun findByName(name: String): AgentProfileEntry? {
        return _profiles.value.find { it.name == name }
    }

    fun loadRawContent(profile: AgentProfileEntry): String? {
        return loader.loadRawContent(profile)
    }
}
