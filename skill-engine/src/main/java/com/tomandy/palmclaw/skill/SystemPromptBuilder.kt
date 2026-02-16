package com.tomandy.palmclaw.skill

object SystemPromptBuilder {

    /**
     * Build the <available_skills> XML block from eligible skills.
     */
    fun buildSkillsBlock(skills: List<SkillEntry>): String {
        val visibleSkills = skills.filter { !it.metadata.disableModelInvocation }
        if (visibleSkills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("The following skills provide specialized instructions for specific tasks.")
        sb.appendLine("Use the read_file tool to load a skill's file when the task matches its description.")
        sb.appendLine("When a skill file references a relative path, resolve it against the skill directory (parent of SKILL.md) and use that absolute path in tool commands.")
        sb.appendLine()
        sb.appendLine("<available_skills>")
        for (skill in visibleSkills) {
            sb.appendLine("  <skill>")
            sb.appendLine("    <name>${escapeXml(skill.metadata.name)}</name>")
            sb.appendLine("    <description>${escapeXml(skill.metadata.description)}</description>")
            if (skill.filePath != null) {
                sb.appendLine("    <location>${escapeXml(skill.filePath)}</location>")
            }
            sb.appendLine("  </skill>")
        }
        sb.appendLine("</available_skills>")
        return sb.toString()
    }

    /**
     * Append skills block to a base system prompt.
     */
    fun augmentSystemPrompt(basePrompt: String, skills: List<SkillEntry>): String {
        val block = buildSkillsBlock(skills)
        if (block.isEmpty()) return basePrompt
        return "$basePrompt\n$block"
    }

    private fun escapeXml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }
}
