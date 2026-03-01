package com.oneclaw.shadow.feature.chat

import com.oneclaw.shadow.core.model.AgentConstants
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.SkillDefinition
import com.oneclaw.shadow.core.model.ToolCallStatus

data class ChatUiState(
    val sessionId: String? = null,
    val sessionTitle: String = "New Conversation",

    val currentAgentId: String = AgentConstants.GENERAL_ASSISTANT_ID,
    val currentAgentName: String = "General Assistant",

    val messages: List<ChatMessageItem> = emptyList(),

    val isStreaming: Boolean = false,
    val streamingText: String = "",
    val streamingThinkingText: String = "",
    val activeToolCalls: List<ActiveToolCall> = emptyList(),

    val inputText: String = "",
    val pendingCount: Int = 0,

    val shouldAutoScroll: Boolean = true,

    val showAgentSelector: Boolean = false,

    val errorMessage: String? = null,

    val hasConfiguredProvider: Boolean = false,
    val isCompacting: Boolean = false,
    val compactSnackbarMessage: String? = null,

    // RFC-014: Slash command autocomplete state
    val slashCommandState: SlashCommandState = SlashCommandState(),

    // RFC-014: Skill selection bottom sheet
    val showSkillSheet: Boolean = false,

    // RFC-014: All available skills (for skill sheet)
    val allSkills: List<SkillDefinition> = emptyList()
)

/**
 * State for the slash command autocomplete popup.
 * Active when user types "/" as first character in chat input.
 * RFC-014
 */
data class SlashCommandState(
    val isActive: Boolean = false,
    val query: String = "",
    val matchingSkills: List<SkillDefinition> = emptyList()
)

data class ChatMessageItem(
    val id: String,
    val type: MessageType,
    val content: String,
    val thinkingContent: String? = null,
    val toolCallId: String? = null,
    val toolName: String? = null,
    val toolInput: String? = null,
    val toolOutput: String? = null,
    val toolStatus: ToolCallStatus? = null,
    val toolDurationMs: Long? = null,
    val modelId: String? = null,
    val tokenCountInput: Int? = null,
    val tokenCountOutput: Int? = null,
    val isRetryable: Boolean = false,
    val timestamp: Long = 0
)

data class ActiveToolCall(
    val toolCallId: String,
    val toolName: String,
    val arguments: String = "",
    val status: ToolCallStatus = ToolCallStatus.PENDING
)
