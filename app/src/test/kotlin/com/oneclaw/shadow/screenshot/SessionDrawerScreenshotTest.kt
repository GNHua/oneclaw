package com.oneclaw.shadow.screenshot

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.junit4.createComposeRule
import com.github.takahirom.roborazzi.captureRoboImage
import com.oneclaw.shadow.feature.session.RenameDialogState
import com.oneclaw.shadow.feature.session.SessionDrawerContentInternal
import com.oneclaw.shadow.feature.session.SessionListItem
import com.oneclaw.shadow.feature.session.SessionListUiState
import com.oneclaw.shadow.feature.session.UndoState
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
class SessionDrawerScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    private val sampleSessions = listOf(
        SessionListItem(
            id = "s1",
            title = "How to implement a ViewModel",
            agentName = "General Assistant",
            lastMessagePreview = "You can use the viewModelOf DSL with Koin 3.5...",
            relativeTime = "5 min ago",
            isActive = true,
            isSelected = false
        ),
        SessionListItem(
            id = "s2",
            title = "Kotlin coroutines explained",
            agentName = "General Assistant",
            lastMessagePreview = "Coroutines are lightweight threads managed by...",
            relativeTime = "Yesterday",
            isActive = false,
            isSelected = false
        ),
        SessionListItem(
            id = "s3",
            title = "Build a REST client",
            agentName = "General Assistant",
            lastMessagePreview = null,
            relativeTime = "Mar 1",
            isActive = false,
            isSelected = false
        )
    )

    private fun themed(content: @Composable () -> Unit) {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = false, dynamicColor = false) {
                content()
            }
        }
    }

    @Test
    fun sessionDrawer_loading() {
        themed {
            SessionDrawerContentInternal(
                uiState = SessionListUiState(isLoading = true),
                onNewConversation = {},
                onSessionClick = {},
                onDeleteSession = {},
                onEnterSelectionMode = {},
                onToggleSelection = {},
                onSelectAll = {},
                onExitSelectionMode = {},
                onDeleteSelected = {},
                onShowRenameDialog = { _, _ -> },
                onDismissRenameDialog = {},
                onConfirmRename = { _, _ -> }
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_loading.png")
    }

    @Test
    fun sessionDrawer_empty() {
        themed {
            SessionDrawerContentInternal(
                uiState = SessionListUiState(sessions = emptyList(), isLoading = false),
                onNewConversation = {},
                onSessionClick = {},
                onDeleteSession = {},
                onEnterSelectionMode = {},
                onToggleSelection = {},
                onSelectAll = {},
                onExitSelectionMode = {},
                onDeleteSelected = {},
                onShowRenameDialog = { _, _ -> },
                onDismissRenameDialog = {},
                onConfirmRename = { _, _ -> }
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_empty.png")
    }

    @Test
    fun sessionDrawer_withSessions() {
        themed {
            SessionDrawerContentInternal(
                uiState = SessionListUiState(sessions = sampleSessions, isLoading = false),
                onNewConversation = {},
                onSessionClick = {},
                onDeleteSession = {},
                onEnterSelectionMode = {},
                onToggleSelection = {},
                onSelectAll = {},
                onExitSelectionMode = {},
                onDeleteSelected = {},
                onShowRenameDialog = { _, _ -> },
                onDismissRenameDialog = {},
                onConfirmRename = { _, _ -> }
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_populated.png")
    }

    @Test
    fun sessionDrawer_selectionMode() {
        themed {
            SessionDrawerContentInternal(
                uiState = SessionListUiState(
                    sessions = sampleSessions.map {
                        it.copy(isSelected = it.id == "s1")
                    },
                    isLoading = false,
                    isSelectionMode = true,
                    selectedSessionIds = setOf("s1")
                ),
                onNewConversation = {},
                onSessionClick = {},
                onDeleteSession = {},
                onEnterSelectionMode = {},
                onToggleSelection = {},
                onSelectAll = {},
                onExitSelectionMode = {},
                onDeleteSelected = {},
                onShowRenameDialog = { _, _ -> },
                onDismissRenameDialog = {},
                onConfirmRename = { _, _ -> }
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_selectionMode.png")
    }

    // RenameSessionDialog uses AlertDialog which triggers AppNotIdleException in Robolectric
    // due to M3 animation. Skipped — covered by unit tests.

    @Test
    fun sessionDrawer_undoSnackbar() {
        themed {
            SessionDrawerContentInternal(
                uiState = SessionListUiState(
                    sessions = sampleSessions.drop(1),
                    isLoading = false,
                    undoState = UndoState(
                        deletedSessionIds = listOf("s1"),
                        message = "Session deleted"
                    )
                ),
                onNewConversation = {},
                onSessionClick = {},
                onDeleteSession = {},
                onEnterSelectionMode = {},
                onToggleSelection = {},
                onSelectAll = {},
                onExitSelectionMode = {},
                onDeleteSelected = {},
                onShowRenameDialog = { _, _ -> },
                onDismissRenameDialog = {},
                onConfirmRename = { _, _ -> }
            )
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_undoState.png")
    }

    @Test
    fun sessionDrawer_darkTheme() {
        composeRule.setContent {
            OneClawShadowTheme(darkTheme = true, dynamicColor = false) {
                SessionDrawerContentInternal(
                    uiState = SessionListUiState(sessions = sampleSessions, isLoading = false),
                    onNewConversation = {},
                    onSessionClick = {},
                    onDeleteSession = {},
                    onEnterSelectionMode = {},
                    onToggleSelection = {},
                    onSelectAll = {},
                    onExitSelectionMode = {},
                    onDeleteSelected = {},
                    onShowRenameDialog = { _, _ -> },
                    onDismissRenameDialog = {},
                    onConfirmRename = { _, _ -> }
                )
            }
        }
        composeRule.onRoot()
            .captureRoboImage("../docs/testing/reports/screenshots/RFC-005_SessionDrawer_dark.png")
    }
}
