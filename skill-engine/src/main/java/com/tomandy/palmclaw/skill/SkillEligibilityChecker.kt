package com.tomandy.palmclaw.skill

/**
 * Checks whether a skill's requirements are met.
 *
 * Uses a provider function to check credential availability,
 * avoiding a direct dependency on CredentialVault.
 */
class SkillEligibilityChecker(
    private val hasCredential: (providerName: String) -> Boolean
) {
    fun isEligible(skill: SkillEntry): Boolean {
        return skill.metadata.requirements.all { req ->
            when (req) {
                is SkillRequirement.Credential -> hasCredential(req.provider)
            }
        }
    }
}
