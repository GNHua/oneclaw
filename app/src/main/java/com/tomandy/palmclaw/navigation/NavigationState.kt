package com.tomandy.palmclaw.navigation

import kotlinx.coroutines.flow.MutableStateFlow

class NavigationState {
    val pendingConversationId = MutableStateFlow<String?>(null)
    val pendingSkillSeed = MutableStateFlow<String?>(null)
    val pendingSharedText = MutableStateFlow<String?>(null)
}
