package com.tomandy.palmclaw.skill

data class SkillEntry(
    val metadata: SkillMetadata,
    val source: SkillSource,
    val filePath: String? = null,
    val baseDir: String? = null
)

enum class SkillSource {
    BUNDLED,
    USER
}
