package com.tomandy.oneclaw.skill

data class SkillMetadata(
    val name: String,
    val description: String,
    val disableModelInvocation: Boolean = false
) {
    val command: String
        get() = "/skill:$name"
}
