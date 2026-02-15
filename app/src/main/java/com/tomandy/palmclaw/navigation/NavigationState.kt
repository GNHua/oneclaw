package com.tomandy.palmclaw.navigation

import kotlinx.coroutines.flow.MutableStateFlow

class NavigationState {
    val pendingConversationId = MutableStateFlow<String?>(null)
}
