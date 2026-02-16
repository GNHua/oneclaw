package com.tomandy.palmclaw.skill

data class SkillMetadata(
    val name: String,
    val description: String,
    val disableModelInvocation: Boolean = false
) {
    val command: String
        get() = "/skill:$name"
}
