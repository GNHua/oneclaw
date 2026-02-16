package com.tomandy.palmclaw.skill

import android.content.Context
import android.util.Log
import java.io.File

class SkillLoader(
    private val context: Context,
    private val userSkillsDir: File
) {

    /**
     * Load bundled skills from assets/skills/{skill-name}/SKILL.md
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
                val content = context.assets.open("skills/$dirName/SKILL.md")
                    .bufferedReader().use { it.readText() }
                val result = SkillFrontmatterParser.parse(content)
                SkillEntry(
                    metadata = result.metadata,
                    body = result.body,
                    source = SkillSource.BUNDLED
                )
            } catch (e: Exception) {
                Log.w(TAG, "Failed to load bundled skill '$dirName': ${e.message}")
                null
            }
        }
    }

    /**
     * Load user-installed skills from {userSkillsDir}/{skill-name}/SKILL.md
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
                    val result = SkillFrontmatterParser.parse(content)
                    SkillEntry(
                        metadata = result.metadata,
                        body = result.body,
                        source = SkillSource.USER
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load user skill '${dir.name}': ${e.message}")
                    null
                }
            } ?: emptyList()
    }

    companion object {
        private const val TAG = "SkillLoader"
    }
}
