package com.oneclaw.shadow.tool.skill

import android.content.Context
import android.util.Log
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import java.io.File

/**
 * Manages the lifecycle and lookup of all skills.
 * Scans built-in (assets/skills/) and user (filesDir/skills/) directories on initialization.
 * RFC-014
 */
class SkillRegistry(
    private val context: Context,
    private val parser: SkillFileParser
) {
    companion object {
        private const val TAG = "SkillRegistry"
        private const val ASSETS_SKILLS_DIR = "skills"
        private const val USER_SKILLS_DIR = "skills"
        private const val SKILL_FILENAME = "SKILL.md"
    }

    private val skills = mutableMapOf<String, SkillDefinition>()
    private val promptCache = mutableMapOf<String, String>()

    /**
     * Initialize by scanning built-in and user-defined skill directories.
     * Called once at app startup. Errors are logged and skipped.
     */
    fun initialize() {
        scanBuiltInSkills()
        scanUserSkills()
        Log.i(TAG, "Initialized: ${skills.size} skill(s) loaded")
    }

    /** Get all skill definitions (built-in + user-defined). */
    fun getAllSkills(): List<SkillDefinition> = skills.values.toList()

    /** Get built-in skills only. */
    fun getBuiltInSkills(): List<SkillDefinition> = skills.values.filter { it.isBuiltIn }

    /** Get user-defined skills only. */
    fun getUserSkills(): List<SkillDefinition> = skills.values.filter { !it.isBuiltIn }

    /** Get a skill definition by name. */
    fun getSkill(name: String): SkillDefinition? = skills[name]

    /** Check if a skill name is registered. */
    fun hasSkill(name: String): Boolean = skills.containsKey(name)

    /**
     * Load full prompt content for a skill.
     * Content is cached after first load.
     * Optionally substitutes {{param}} placeholders.
     */
    fun loadSkillContent(
        name: String,
        parameterValues: Map<String, String> = emptyMap()
    ): AppResult<String> {
        val definition = skills[name]
            ?: return AppResult.Error(
                message = "Skill '$name' not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        // Return cached content (no substitution in cache; substitute on return)
        val cached = promptCache[name]
        if (cached != null) {
            val content = if (parameterValues.isNotEmpty()) {
                parser.substituteParameters(cached, parameterValues)
            } else cached
            return AppResult.Success(content)
        }

        // Load from source
        return try {
            val rawContent = if (definition.isBuiltIn) {
                loadAssetSkillContent(definition.name)
            } else {
                loadFileSkillContent(definition.directoryPath)
            }

            when (rawContent) {
                is AppResult.Success -> {
                    promptCache[name] = rawContent.data
                    val finalContent = if (parameterValues.isNotEmpty()) {
                        parser.substituteParameters(rawContent.data, parameterValues)
                    } else rawContent.data
                    AppResult.Success(finalContent)
                }
                is AppResult.Error -> rawContent
            }
        } catch (e: Exception) {
            AppResult.Error(
                exception = e,
                message = "Failed to load skill '$name': ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Create a new user-defined skill.
     * Returns error if a skill with the same name already exists.
     */
    fun createSkill(
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition> {
        if (hasSkill(definition.name)) {
            return AppResult.Error(
                message = "Skill '${definition.name}' already exists",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val skillDir = File(context.filesDir, "$USER_SKILLS_DIR/${definition.name}")
        return try {
            skillDir.mkdirs()
            val skillFile = File(skillDir, SKILL_FILENAME)
            val withDir = definition.copy(isBuiltIn = false, directoryPath = skillDir.absolutePath)
            val content = parser.serialize(withDir, promptContent)
            skillFile.writeText(content, Charsets.UTF_8)
            skills[definition.name] = withDir
            promptCache.remove(definition.name)
            AppResult.Success(withDir)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create skill '${definition.name}'", e)
            AppResult.Error(
                exception = e,
                message = "Failed to create skill: ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Update an existing user-defined skill.
     * Returns error if not found or is a built-in skill.
     */
    fun updateSkill(
        name: String,
        definition: SkillDefinition,
        promptContent: String
    ): AppResult<SkillDefinition> {
        val existing = skills[name]
            ?: return AppResult.Error(
                message = "Skill '$name' not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (existing.isBuiltIn) {
            return AppResult.Error(
                message = "Cannot modify built-in skill '$name'",
                code = ErrorCode.PERMISSION_ERROR
            )
        }

        return try {
            val skillDir = File(existing.directoryPath)
            val skillFile = File(skillDir, SKILL_FILENAME)
            val updated = definition.copy(
                isBuiltIn = false,
                directoryPath = existing.directoryPath
            )
            val content = parser.serialize(updated, promptContent)
            skillFile.writeText(content, Charsets.UTF_8)
            skills[name] = updated
            promptCache.remove(name)
            AppResult.Success(updated)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to update skill '$name'", e)
            AppResult.Error(
                exception = e,
                message = "Failed to update skill: ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Delete a user-defined skill (removes directory).
     * Returns error if not found or is built-in.
     */
    fun deleteSkill(name: String): AppResult<Unit> {
        val existing = skills[name]
            ?: return AppResult.Error(
                message = "Skill '$name' not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        if (existing.isBuiltIn) {
            return AppResult.Error(
                message = "Cannot delete built-in skill '$name'",
                code = ErrorCode.PERMISSION_ERROR
            )
        }

        return try {
            val skillDir = File(existing.directoryPath)
            skillDir.deleteRecursively()
            skills.remove(name)
            promptCache.remove(name)
            AppResult.Success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete skill '$name'", e)
            AppResult.Error(
                exception = e,
                message = "Failed to delete skill: ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    /**
     * Import a skill from SKILL.md content string.
     * Returns error if name conflicts or content is invalid.
     */
    fun importSkill(content: String): AppResult<SkillDefinition> {
        val parseResult = parser.parseContent(content, isBuiltIn = false, directoryPath = "")
        if (parseResult is AppResult.Error) {
            return AppResult.Error(
                message = "Invalid SKILL.md content: ${parseResult.message}",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        val parsed = (parseResult as AppResult.Success).data
        if (hasSkill(parsed.definition.name)) {
            return AppResult.Error(
                message = "Skill '${parsed.definition.name}' already exists",
                code = ErrorCode.VALIDATION_ERROR
            )
        }

        return createSkill(parsed.definition, parsed.promptContent)
    }

    /**
     * Export a skill as SKILL.md content string.
     */
    fun exportSkill(name: String): AppResult<String> {
        val definition = skills[name]
            ?: return AppResult.Error(
                message = "Skill '$name' not found",
                code = ErrorCode.VALIDATION_ERROR
            )

        return when (val contentResult = loadSkillContent(name)) {
            is AppResult.Success -> {
                val serialized = parser.serialize(definition, contentResult.data)
                AppResult.Success(serialized)
            }
            is AppResult.Error -> contentResult
        }
    }

    /**
     * Generate the skill registry text for injection into the system prompt.
     */
    fun generateRegistryPrompt(): String {
        val allSkills = getAllSkills()
        if (allSkills.isEmpty()) return ""

        return buildString {
            appendLine("## Available Skills")
            appendLine()
            appendLine(
                "The following skills are available. Use the `load_skill` tool to load full " +
                "instructions when needed. You can proactively load a skill when you recognize " +
                "the user's request matches one."
            )
            appendLine()
            allSkills.forEach { skill ->
                appendLine("- ${skill.name}: ${skill.description}")
            }
        }.trimEnd()
    }

    /**
     * Re-scan all skill directories and rebuild the index.
     * Called after create/update/delete/import.
     */
    fun refresh() {
        skills.clear()
        promptCache.clear()
        scanBuiltInSkills()
        scanUserSkills()
        Log.i(TAG, "Refreshed: ${skills.size} skill(s) loaded")
    }

    // ---- Internal scanning helpers ----

    private fun scanBuiltInSkills() {
        val assetManager = context.assets
        val skillDirs = try {
            assetManager.list(ASSETS_SKILLS_DIR) ?: return
        } catch (e: Exception) {
            Log.w(TAG, "No assets/skills directory found", e)
            return
        }

        skillDirs.forEach { dirName ->
            val assetPath = "$ASSETS_SKILLS_DIR/$dirName/$SKILL_FILENAME"
            try {
                val content = assetManager.open(assetPath).bufferedReader().use { it.readText() }
                // For built-in, directoryPath is empty (assets are not file-system directories)
                val result = parser.parseContent(content, isBuiltIn = true, directoryPath = "assets://$ASSETS_SKILLS_DIR/$dirName")
                when (result) {
                    is AppResult.Success -> {
                        skills[result.data.definition.name] = result.data.definition
                        promptCache[result.data.definition.name] = result.data.promptContent
                    }
                    is AppResult.Error -> Log.w(TAG, "Skipping invalid built-in skill '$dirName': ${result.message}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to read built-in skill '$dirName': ${e.message}")
            }
        }
    }

    private fun scanUserSkills() {
        val userSkillsDir = File(context.filesDir, USER_SKILLS_DIR)
        if (!userSkillsDir.exists()) return

        userSkillsDir.listFiles()?.forEach { dir ->
            if (!dir.isDirectory) return@forEach
            val skillFile = File(dir, SKILL_FILENAME)
            if (!skillFile.exists()) return@forEach

            val result = parser.parse(skillFile.absolutePath, isBuiltIn = false)
            when (result) {
                is AppResult.Success -> skills[result.data.definition.name] = result.data.definition
                is AppResult.Error -> Log.w(TAG, "Skipping invalid user skill '${dir.name}': ${result.message}")
            }
        }
    }

    private fun loadAssetSkillContent(name: String): AppResult<String> {
        // Find the definition to get directory path
        val definition = skills[name]
        // Extract asset path from directoryPath prefix "assets://skills/..."
        val dirPath = definition?.directoryPath ?: "assets://$ASSETS_SKILLS_DIR/$name"
        val assetPath = dirPath.removePrefix("assets://") + "/$SKILL_FILENAME"
        return try {
            val content = context.assets.open(assetPath).bufferedReader().use { it.readText() }
            val parseResult = parser.parseContent(content, isBuiltIn = true, directoryPath = dirPath)
            when (parseResult) {
                is AppResult.Success -> AppResult.Success(parseResult.data.promptContent)
                is AppResult.Error -> parseResult
            }
        } catch (e: Exception) {
            AppResult.Error(
                exception = e,
                message = "Failed to load built-in skill '$name': ${e.message}",
                code = ErrorCode.STORAGE_ERROR
            )
        }
    }

    private fun loadFileSkillContent(directoryPath: String): AppResult<String> {
        val skillFile = File(directoryPath, SKILL_FILENAME)
        val parseResult = parser.parse(skillFile.absolutePath, isBuiltIn = false)
        return when (parseResult) {
            is AppResult.Success -> AppResult.Success(parseResult.data.promptContent)
            is AppResult.Error -> parseResult
        }
    }
}
