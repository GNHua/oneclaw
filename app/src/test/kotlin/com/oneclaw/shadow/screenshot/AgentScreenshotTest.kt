package com.oneclaw.shadow.screenshot

import android.app.Application
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ToolCallStatus
import com.oneclaw.shadow.feature.agent.AgentListItem
import com.oneclaw.shadow.feature.agent.AgentListScreenContent
import com.oneclaw.shadow.feature.agent.AgentListUiState
import com.oneclaw.shadow.feature.chat.ActiveToolCall
import com.oneclaw.shadow.feature.chat.ChatInput
import com.oneclaw.shadow.feature.chat.ChatMessageItem
import com.oneclaw.shadow.feature.chat.ChatTopBar
import com.oneclaw.shadow.feature.chat.EmptyChatState
import com.oneclaw.shadow.feature.chat.MessageList
import com.oneclaw.shadow.ui.theme.OneClawShadowTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w412dp-h915dp-440dpi", application = Application::class)
class AgentScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private fun themed(content: @Composable () -> Unit) {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = false, dynamicColor = false) {
                content()
            }
        }
    }

    // --- AgentListScreen ---

    @Test
    fun agentList_loading() {
        themed {
            AgentListScreenContent(
                uiState = AgentListUiState(isLoading = true),
                onAgentClick = {},
                onCreateAgent = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-002_AgentListScreen_loading.png")
    }

    @Test
    fun agentList_populated() {
        val agents = listOf(
            AgentListItem(
                id = "general",
                name = "General Assistant",
                description = "A versatile assistant for general tasks",
                isBuiltIn = true,
                toolCount = 4
            ),
            AgentListItem(
                id = "coder",
                name = "Code Helper",
                description = "Specialized for coding tasks",
                isBuiltIn = true,
                toolCount = 2
            ),
            AgentListItem(
                id = "custom-1",
                name = "My Custom Agent",
                description = "Custom agent with specific instructions",
                isBuiltIn = false,
                toolCount = 1
            )
        )
        themed {
            AgentListScreenContent(
                uiState = AgentListUiState(agents = agents, isLoading = false),
                onAgentClick = {},
                onCreateAgent = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-002_AgentListScreen_populated.png")
    }

    @Test
    fun agentList_noCustomAgents() {
        val agents = listOf(
            AgentListItem(
                id = "general",
                name = "General Assistant",
                description = "A versatile assistant for general tasks",
                isBuiltIn = true,
                toolCount = 4
            )
        )
        themed {
            AgentListScreenContent(
                uiState = AgentListUiState(agents = agents, isLoading = false),
                onAgentClick = {},
                onCreateAgent = {},
                onNavigateBack = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-002_AgentListScreen_noCustom.png")
    }

    @Test
    fun agentList_darkTheme() {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = true, dynamicColor = false) {
                AgentListScreenContent(
                    uiState = AgentListUiState(
                        agents = listOf(
                            AgentListItem(
                                id = "general",
                                name = "General Assistant",
                                description = "A versatile assistant",
                                isBuiltIn = true,
                                toolCount = 4
                            ),
                            AgentListItem(
                                id = "custom-1",
                                name = "My Agent",
                                description = null,
                                isBuiltIn = false,
                                toolCount = 0
                            )
                        ),
                        isLoading = false
                    ),
                    onAgentClick = {},
                    onCreateAgent = {},
                    onNavigateBack = {}
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-002_AgentListScreen_dark.png")
    }

    // --- ChatScreen components ---

    @Test
    fun chat_topBar() {
        themed {
            ChatTopBar(
                agentName = "General Assistant",
                onMenuClick = {},
                onAgentClick = {},
                onSettingsClick = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatTopBar.png")
    }

    @Test
    fun chat_input_empty() {
        themed {
            ChatInput(
                text = "",
                onTextChange = {},
                onSend = {},
                onStop = {},
                isStreaming = false,
                hasConfiguredProvider = false
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatInput_empty.png")
    }

    @Test
    fun chat_input_withText() {
        themed {
            ChatInput(
                text = "Explain how coroutines work",
                onTextChange = {},
                onSend = {},
                onStop = {},
                isStreaming = false,
                hasConfiguredProvider = true
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatInput_withText.png")
    }

    @Test
    fun chat_emptyState() {
        themed {
            EmptyChatState()
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatEmptyState.png")
    }

    @Test
    fun chat_messageList_conversation() {
        val messages = listOf(
            ChatMessageItem(
                id = "m1",
                type = MessageType.USER,
                content = "How does Room database work in Android?",
                timestamp = 1000L
            ),
            ChatMessageItem(
                id = "m2",
                type = MessageType.AI_RESPONSE,
                content = "Room is a persistence library that provides an abstraction layer over SQLite. It has three main components: **Database**, **DAO**, and **Entity**.",
                modelId = "gpt-4o",
                timestamp = 2000L
            ),
            ChatMessageItem(
                id = "m3",
                type = MessageType.USER,
                content = "Can you show me an example?",
                timestamp = 3000L
            )
        )
        themed {
            val listState = rememberLazyListState()
            MessageList(
                messages = messages,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                isStreaming = false,
                listState = listState,
                onCopy = {},
                onRetry = {},
                onRegenerate = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatMessageList_conversation.png")
    }

    @Test
    fun chat_messageList_withToolCall() {
        val messages = listOf(
            ChatMessageItem(
                id = "m1",
                type = MessageType.USER,
                content = "What time is it?",
                timestamp = 1000L
            ),
            ChatMessageItem(
                id = "m2",
                type = MessageType.TOOL_CALL,
                content = "",
                toolCallId = "tc1",
                toolName = "get_current_time",
                toolInput = "{}",
                toolStatus = ToolCallStatus.SUCCESS,
                timestamp = 2000L
            ),
            ChatMessageItem(
                id = "m3",
                type = MessageType.TOOL_RESULT,
                content = "",
                toolCallId = "tc1",
                toolName = "get_current_time",
                toolOutput = "2026-02-27T23:00:00Z",
                toolStatus = ToolCallStatus.SUCCESS,
                toolDurationMs = 12L,
                timestamp = 2100L
            ),
            ChatMessageItem(
                id = "m4",
                type = MessageType.AI_RESPONSE,
                content = "The current time is 11:00 PM UTC on February 27, 2026.",
                modelId = "claude-3-5-sonnet-20241022",
                timestamp = 3000L
            )
        )
        themed {
            val listState = rememberLazyListState()
            MessageList(
                messages = messages,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                isStreaming = false,
                listState = listState,
                onCopy = {},
                onRetry = {},
                onRegenerate = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatMessageList_toolCall.png")
    }

    @Test
    fun chat_messageList_streaming() {
        val messages = listOf(
            ChatMessageItem(
                id = "m1",
                type = MessageType.USER,
                content = "Tell me a story",
                timestamp = 1000L
            )
        )
        themed {
            val listState = rememberLazyListState()
            MessageList(
                messages = messages,
                streamingText = "Once upon a time, in a land far away, there lived a developer who loved Kotlin...",
                streamingThinkingText = "",
                activeToolCalls = emptyList(),
                isStreaming = true,
                listState = listState,
                onCopy = {},
                onRetry = {},
                onRegenerate = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatMessageList_streaming.png")
    }

    @Test
    fun chat_messageList_activeToolCall() {
        val messages = listOf(
            ChatMessageItem(
                id = "m1",
                type = MessageType.USER,
                content = "Read the README file",
                timestamp = 1000L
            )
        )
        val activeToolCalls = listOf(
            ActiveToolCall(
                toolCallId = "tc1",
                toolName = "read_file",
                arguments = """{"path": "README.md"}""",
                status = ToolCallStatus.PENDING
            )
        )
        themed {
            val listState = rememberLazyListState()
            MessageList(
                messages = messages,
                streamingText = "",
                streamingThinkingText = "",
                activeToolCalls = activeToolCalls,
                isStreaming = true,
                listState = listState,
                onCopy = {},
                onRetry = {},
                onRegenerate = {}
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-001_ChatMessageList_activeToolCall.png")
    }
}
