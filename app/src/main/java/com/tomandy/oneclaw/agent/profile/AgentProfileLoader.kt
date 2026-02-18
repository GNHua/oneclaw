package com.tomandy.oneclaw.agent.profile

import android.content.Context
import android.util.Log
import java.io.File

class AgentProfileLoader(
    private val context: Context,
    private val userAgentsDir: File
) {

    fun loadBundledProfiles(): List<AgentProfileEntry> {
        val files = try {
            context.assets.list("agents") ?: emptyArray()
        } catch (e: Exception) {
            Log.w(TAG, "No bundled agents directory found")
            emptyArray()
        }

        return files
            .filter { it.endsWith(".md") }
            .mapNotNull { fileName ->
                try {
                    val assetPath = "agents/$fileName"
                    val content = context.assets.open(assetPath)
                        .bufferedReader().use { it.readText() }
                    val stem = fileName.removeSuffix(".md")
                    val parsed = AgentProfileParser.parse(content, stem)
                    AgentProfileEntry(
                        name = parsed.name,
                        description = parsed.description,
                        systemPrompt = parsed.body,
                        model = parsed.model,
                        allowedTools = parsed.allowedTools,
                        enabledSkills = parsed.enabledSkills,
                        source = AgentProfileSource.BUNDLED,
                        filePath = assetPath
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load bundled agent '$fileName': ${e.message}")
                    null
                }
            }
    }

    fun loadUserProfiles(): List<AgentProfileEntry> {
        if (!userAgentsDir.exists()) return emptyList()

        return userAgentsDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.mapNotNull { file ->
                try {
                    val content = file.readText()
                    val stem = file.nameWithoutExtension
                    val parsed = AgentProfileParser.parse(content, stem)
                    AgentProfileEntry(
                        name = parsed.name,
                        description = parsed.description,
                        systemPrompt = parsed.body,
                        model = parsed.model,
                        allowedTools = parsed.allowedTools,
                        enabledSkills = parsed.enabledSkills,
                        source = AgentProfileSource.USER,
                        filePath = "agents/${file.name}"
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to load user agent '${file.name}': ${e.message}")
                    null
                }
            } ?: emptyList()
    }

    /**
     * One-time migration: moves directory-based profiles ({name}/AGENT.md)
     * to flat file layout ({name}.md).
     */
    fun migrateDirectoryLayout() {
        if (!userAgentsDir.exists()) return
        userAgentsDir.listFiles()
            ?.filter { it.isDirectory }
            ?.forEach { dir ->
                val agentFile = File(dir, "AGENT.md")
                if (agentFile.exists()) {
                    val target = File(userAgentsDir, "${dir.name}.md")
                    if (!target.exists()) {
                        agentFile.copyTo(target)
                    }
                    dir.deleteRecursively()
                }
            }
    }

    fun loadRawContent(profile: AgentProfileEntry): String? {
        val path = profile.filePath ?: return null
        return try {
            when (profile.source) {
                AgentProfileSource.BUNDLED -> context.assets.open(path)
                    .bufferedReader().use { it.readText() }
                AgentProfileSource.USER -> File(userAgentsDir.parentFile, path).readText()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to load raw content for agent '${profile.name}': ${e.message}")
            null
        }
    }

    companion object {
        private const val TAG = "AgentProfileLoader"
    }
}
