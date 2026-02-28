package com.oneclaw.shadow.navigation

sealed class Route(val path: String) {
    data object Chat : Route("chat/new")
    data class ChatSession(val sessionId: String) : Route("chat/{sessionId}") {
        companion object {
            const val PATH = "chat/{sessionId}"
            fun create(sessionId: String) = "chat/$sessionId"
        }
    }
    data object AgentList : Route("agents")
    data class AgentDetail(val agentId: String) : Route("agents/{agentId}") {
        companion object {
            const val PATH = "agents/{agentId}"
            fun create(agentId: String) = "agents/$agentId"
        }
    }
    data object AgentCreate : Route("agents/create")
    data object ProviderList : Route("providers")
    data class ProviderDetail(val providerId: String) : Route("providers/{providerId}") {
        companion object {
            const val PATH = "providers/{providerId}"
            fun create(providerId: String) = "providers/$providerId"
        }
    }
    data object Setup : Route("setup")
    data object Settings : Route("settings")
    data object UsageStatistics : Route("usage")
    data object DataBackup : Route("data-backup")
    data object Memory : Route("memory")
}
