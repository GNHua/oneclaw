package com.tomandy.palmclaw.skill

data class SkillEntry(
    val metadata: SkillMetadata,
    val body: String,
    val source: SkillSource
)

enum class SkillSource {
    BUNDLED,
    USER
}
