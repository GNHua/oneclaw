package com.tomandy.palmclaw.skill

object SystemPromptBuilder {

    /**
     * Build the <available_skills> XML block from eligible skills.
     */
    fun buildSkillsBlock(skills: List<SkillEntry>): String {
        if (skills.isEmpty()) return ""

        val sb = StringBuilder()
        sb.appendLine()
        sb.appendLine("You also have access to skills that users can invoke via slash commands.")
        sb.appendLine("When a skill is invoked, you will receive its instructions as context.")
        sb.appendLine()
        sb.appendLine("<available_skills>")
        for (skill in skills) {
            sb.append("  <skill name=\"${escapeXml(skill.metadata.name)}\"")
            sb.appendLine(" command=\"${escapeXml(skill.metadata.command)}\">")
            sb.appendLine("    <description>${escapeXml(skill.metadata.description)}</description>")
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
