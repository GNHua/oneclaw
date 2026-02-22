package com.tomandy.oneclaw.ui.settings

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tomandy.oneclaw.navigation.NavigationState
import com.tomandy.oneclaw.skill.SkillEntry
import com.tomandy.oneclaw.skill.SkillFrontmatterParser
import com.tomandy.oneclaw.skill.SkillPreferences
import com.tomandy.oneclaw.skill.SkillRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.URL

class SkillsViewModel(
    private val skillRepository: SkillRepository,
    private val skillPreferences: SkillPreferences,
    private val userSkillsDir: File,
    private val navigationState: NavigationState
) : ViewModel() {

    val skills: StateFlow<List<SkillEntry>> = skillRepository.skills

    private val _enabledMap = MutableStateFlow<Map<String, Boolean>>(emptyMap())
    val enabledMap: StateFlow<Map<String, Boolean>> = _enabledMap.asStateFlow()

    private val _importStatus = MutableStateFlow<SkillImportStatus>(SkillImportStatus.Idle)
    val importStatus: StateFlow<SkillImportStatus> = _importStatus.asStateFlow()

    private val _saveStatus = MutableStateFlow<SkillSaveStatus>(SkillSaveStatus.Idle)
    val saveStatus: StateFlow<SkillSaveStatus> = _saveStatus.asStateFlow()

    init {
        skillRepository.reload()
        refreshEnabledMap()
        viewModelScope.launch {
            skills.collect { refreshEnabledMap() }
        }
    }

    private fun refreshEnabledMap() {
        _enabledMap.value = skills.value.associate { skill ->
            skill.metadata.name to skillPreferences.isSkillEnabled(skill.metadata.name)
        }
    }

    fun toggleSkill(name: String, enabled: Boolean) {
        skillPreferences.setSkillEnabled(name, enabled)
        _enabledMap.value = _enabledMap.value + (name to enabled)
    }

    fun isSkillEnabled(name: String): Boolean {
        return _enabledMap.value[name] ?: skillPreferences.isSkillEnabled(name)
    }

    fun deleteSkill(skillName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val skillDir = File(userSkillsDir, skillName)
            if (skillDir.exists()) {
                skillDir.deleteRecursively()
            }
            skillRepository.reload()
        }
    }

    fun importFromUrl(url: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _importStatus.value = SkillImportStatus.Importing
            try {
                val content = URL(url).readText()
                val parsed = SkillFrontmatterParser.parse(content)
                val name = parsed.metadata.name
                val skillDir = File(userSkillsDir, name)
                skillDir.mkdirs()
                File(skillDir, "SKILL.md").writeText(content)
                skillRepository.reload()
                skillPreferences.setSkillEnabled(name, true)
                _importStatus.value = SkillImportStatus.Success(name)
                delay(2000)
                _importStatus.value = SkillImportStatus.Idle
            } catch (e: Exception) {
                Log.w(TAG, "Failed to import skill from URL: ${e.message}")
                _importStatus.value = SkillImportStatus.Error(
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    fun saveSkill(name: String, description: String, body: String) {
        viewModelScope.launch(Dispatchers.IO) {
            _saveStatus.value = SkillSaveStatus.Saving
            try {
                val content = buildString {
                    appendLine("---")
                    appendLine("name: $name")
                    appendLine("description: $description")
                    appendLine("---")
                    appendLine()
                    append(body)
                }
                // Validate by parsing
                SkillFrontmatterParser.parse(content)

                val skillDir = File(userSkillsDir, name)
                skillDir.mkdirs()
                File(skillDir, "SKILL.md").writeText(content)
                skillRepository.reload()
                skillPreferences.setSkillEnabled(name, true)
                _saveStatus.value = SkillSaveStatus.Success
                delay(1500)
                _saveStatus.value = SkillSaveStatus.Idle
            } catch (e: Exception) {
                Log.w(TAG, "Failed to save skill: ${e.message}")
                _saveStatus.value = SkillSaveStatus.Error(
                    e.message ?: "Unknown error"
                )
            }
        }
    }

    fun findSkill(name: String): SkillEntry? {
        return skills.value.find { it.metadata.name == name }
    }

    fun loadRawContent(skill: SkillEntry): String? {
        return skillRepository.loadRawContent(skill)
    }

    fun startAgentCreateFlow() {
        val seedPrompt = buildString {
            append("Help me create a new skill. ")
            append("Skills are SKILL.md files with YAML frontmatter (name, description) ")
            append("followed by markdown instructions. ")
            append("The file should be saved to workspace/skills/{skill-name}/SKILL.md ")
            append("using the write_file tool. ")
            append("Start by asking me what the skill should do.")
        }
        navigationState.pendingSkillSeed.value = seedPrompt
    }

    fun startAgentEditFlow(skill: SkillEntry) {
        val path = "skills/${skill.metadata.name}/SKILL.md"
        val seedPrompt = buildString {
            append("Help me edit the skill '${skill.metadata.name}'. ")
            append("First, read the current content with read_file at path=\"$path\", ")
            append("then ask me what changes I'd like to make. ")
            append("Use write_file to save changes when done.")
        }
        navigationState.pendingSkillSeed.value = seedPrompt
    }

    fun resetImportStatus() {
        _importStatus.value = SkillImportStatus.Idle
    }

    fun resetSaveStatus() {
        _saveStatus.value = SkillSaveStatus.Idle
    }

    companion object {
        private const val TAG = "SkillsViewModel"
    }
}

sealed class SkillImportStatus {
    data object Idle : SkillImportStatus()
    data object Importing : SkillImportStatus()
    data class Success(val skillName: String) : SkillImportStatus()
    data class Error(val message: String) : SkillImportStatus()
}

sealed class SkillSaveStatus {
    data object Idle : SkillSaveStatus()
    data object Saving : SkillSaveStatus()
    data object Success : SkillSaveStatus()
    data class Error(val message: String) : SkillSaveStatus()
}
