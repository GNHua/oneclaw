package com.tomandy.palmclaw.skill

import android.content.Context
import android.util.Log
import java.io.File

class SkillLoader(
    private val context: Context,
    private val userSkillsDir: File
) {

    /**
     * Load bundled skill metadata from assets/skills/{skill-name}/SKILL.md.
     * Only parses frontmatter -- bodies are loaded on demand via [loadBody].
     */
    fun loadBundledSkills(): List<SkillEntry> {
        val skillDirs = try {
            context.assets.list("skills") ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "No bundled skills directory found")
            emptyArray()
        }

        return skillDirs.mapNotNull { dirName ->
            try {
                val assetPath = "skills/$dirName/SKILL.md"
                val content = context.assets.open(assetPath)
                    .bufferedReader().use { it.readText() }
                val metadata = SkillFrontmatterParser.parseMetadataOnly(content, dirName)
                SkillEntry(
                    metadata = metadata,
                    source = SkillSource.BUNDLED,
                    filePath = assetPath,
                    baseDir = dirName
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load bundled skill '$dirName': ${e.message}")
                null
            }
        }
    }

    /**
     * Load user-installed skill metadata from {userSkillsDir}/{skill-name}/SKILL.md.
     * Only parses frontmatter -- bodies are loaded on demand via [loadBody].
     */
    fun loadUserSkills(): List<SkillEntry> {
        if (!userSkillsDir.exists()) return emptyList()

        return userSkillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = File(dir, "SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                try {
                    val content = skillFile.readText()
                    val metadata = SkillFrontmatterParser.parseMetadataOnly(content, dir.name)
                    SkillEntry(
                        metadata = metadata,
                        source = SkillSource.USER,
                        filePath = "skills/${dir.name}/SKILL.md",
                        baseDir = dir.name
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load user skill '${dir.name}': ${e.message}")
                    null
                }
            } ?: emptyList()
    }

    /**
     * Load the full body of a skill on demand (reads from disk/assets).
     * Returns null if the skill file cannot be read.
     */
    fun loadBody(skill: SkillEntry): String? {
        val path = skill.filePath ?: return null
        return try {
            val content = when (skill.source) {
                SkillSource.BUNDLED -> context.assets.open(path)
                    .bufferedReader().use { it.readText() }
                SkillSource.USER -> File(userSkillsDir.parentFile, path).readText()
            }
            SkillFrontmatterParser.parse(content).body
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load body for skill '${skill.metadata.name}': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "SkillLoader"
    }
}
