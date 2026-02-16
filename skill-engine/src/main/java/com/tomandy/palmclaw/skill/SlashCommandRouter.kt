package com.tomandy.palmclaw.skill

/**
 * Parses slash commands from user input and resolves them to skills.
 *
 * Skills use the standard /skill:name format.
 */
class SlashCommandRouter(private val repository: SkillRepository) {

    data class SlashCommand(
        val command: String,
        val arguments: String
    )

    /**
     * Check if text starts with a skill slash command (not a built-in like /summarize).
     */
    fun isSlashCommand(text: String): Boolean {
        val trimmed = text.trim()
        return trimmed.startsWith("/") &&
            trimmed.length > 1 &&
            !trimmed.startsWith("//")
    }

    /**
     * Parse a slash command from user input.
     */
    fun parse(text: String): SlashCommand? {
        val trimmed = text.trim()
        if (!isSlashCommand(trimmed)) return null

        val parts = trimmed.split(" ", limit = 2)
        return SlashCommand(
            command = parts[0].lowercase(),
            arguments = parts.getOrElse(1) { "" }.trim()
        )
    }

    /**
     * Resolve a slash command to a skill.
     * Returns null if no matching skill is found.
     */
    fun resolve(command: SlashCommand): SkillEntry? {
        return repository.findByCommand(command.command)
    }
}
