package com.tomandy.palmclaw.skill

import android.content.Context
import android.util.Log
import java.io.File

class SkillLoader(private val context: Context) {

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
                val assetPath = "skills/$dirName/SKILL.md"
                val content = context.assets.open(assetPath)
                    .bufferedReader().use { it.readText() }
                val result = SkillFrontmatterParser.parse(content, dirName)
                SkillEntry(
                    metadata = result.metadata,
                    body = result.body,
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
     * Load workspace skills from files/workspace/skills/{skill-name}/SKILL.md
     *
     * These live inside the agent workspace so users can create and edit
     * skill files via chat using the workspace tools.
     */
    fun loadUserSkills(): List<SkillEntry> {
        val userSkillsDir = File(context.filesDir, "workspace/skills")
        if (!userSkillsDir.exists()) return emptyList()

        return userSkillsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.mapNotNull { dir ->
                val skillFile = File(dir, "SKILL.md")
                if (!skillFile.exists()) return@mapNotNull null
                try {
                    val content = skillFile.readText()
                    val result = SkillFrontmatterParser.parse(content, dir.name)
                    SkillEntry(
                        metadata = result.metadata,
                        body = result.body,
                        source = SkillSource.USER,
                        filePath = skillFile.absolutePath,
                        baseDir = dir.name
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
