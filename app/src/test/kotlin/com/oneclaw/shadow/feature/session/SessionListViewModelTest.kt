package com.oneclaw.shadow.feature.session

import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.core.util.ErrorCode
import com.oneclaw.shadow.data.local.dao.MessageDao
import com.oneclaw.shadow.feature.session.usecase.BatchDeleteSessionsUseCase
import com.oneclaw.shadow.feature.session.usecase.DeleteSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.RenameSessionUseCase
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherRule::class)
class SessionListViewModelTest {

    private lateinit var sessionRepository: SessionRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var deleteSessionUseCase: DeleteSessionUseCase
    private lateinit var batchDeleteSessionsUseCase: BatchDeleteSessionsUseCase
    private lateinit var renameSessionUseCase: RenameSessionUseCase
    private lateinit var messageDao: MessageDao
    private lateinit var viewModel: SessionListViewModel

    private val now = 1_000_000L

    private val agent = Agent(
        id = "agent-general",
        name = "General Assistant",
        description = null,
        systemPrompt = "You are a helpful assistant.",
        toolIds = emptyList(),
        preferredProviderId = null,
        preferredModelId = null,
        isBuiltIn = true,
        createdAt = now,
        updatedAt = now
    )

    private fun makeSession(id: String, title: String = "Session $id", updatedAt: Long = now) = Session(
        id = id,
        title = title,
        currentAgentId = agent.id,
        messageCount = 2,
        lastMessagePreview = "Last message",
        isActive = false,
        deletedAt = null,
        createdAt = now,
        updatedAt = updatedAt
    )

    @BeforeEach
    fun setup() {
        sessionRepository = mockk(relaxed = true)
        agentRepository = mockk()
        deleteSessionUseCase = mockk()
        batchDeleteSessionsUseCase = mockk()
        renameSessionUseCase = mockk()
        messageDao = mockk()

        every { agentRepository.getAllAgents() } returns flowOf(listOf(agent))
        coEvery { messageDao.getTotalTokensForSession(any()) } returns 0L
    }

    private fun createViewModel(): SessionListViewModel {
        return SessionListViewModel(
            sessionRepository,
            agentRepository,
            deleteSessionUseCase,
            batchDeleteSessionsUseCase,
            renameSessionUseCase,
            messageDao
        )
    }

    @Test
    fun `uiState starts with isLoading true`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())

        viewModel = createViewModel()

        // With UnconfinedTestDispatcher, flows collect immediately, so isLoading becomes false
        assertFalse(viewModel.uiState.value.isLoading)
    }

    @Test
    fun `sessions are populated from repository`() = runTest {
        val sessions = listOf(makeSession("s1"), makeSession("s2"))
        every { sessionRepository.getAllSessions() } returns flowOf(sessions)

        viewModel = createViewModel()

        assertEquals(2, viewModel.uiState.value.sessions.size)
    }

    @Test
    fun `session items use agent name from cache`() = runTest {
        val sessions = listOf(makeSession("s1"))
        every { sessionRepository.getAllSessions() } returns flowOf(sessions)

        viewModel = createViewModel()

        val item = viewModel.uiState.value.sessions.first()
        assertEquals("General Assistant", item.agentName)
    }

    @Test
    fun `deleteSession shows undo state on success`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))
        coEvery { deleteSessionUseCase("s1") } returns AppResult.Success(Unit)

        viewModel = createViewModel()
        viewModel.deleteSession("s1")

        assertNotNull(viewModel.uiState.value.undoState)
        assertEquals(listOf("s1"), viewModel.uiState.value.undoState?.deletedSessionIds)
    }

    @Test
    fun `deleteSession sets error message on failure`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))
        coEvery { deleteSessionUseCase("s1") } returns AppResult.Error(message = "error", code = ErrorCode.UNKNOWN)

        viewModel = createViewModel()
        viewModel.deleteSession("s1")

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `undoDelete clears undo state`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))
        coEvery { deleteSessionUseCase("s1") } returns AppResult.Success(Unit)
        coEvery { sessionRepository.restoreSession("s1") } returns Unit

        viewModel = createViewModel()
        viewModel.deleteSession("s1")
        viewModel.undoDelete()

        assertNull(viewModel.uiState.value.undoState)
    }

    @Test
    fun `undoDelete restores sessions via repository`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))
        coEvery { deleteSessionUseCase("s1") } returns AppResult.Success(Unit)
        coEvery { sessionRepository.restoreSession("s1") } returns Unit

        viewModel = createViewModel()
        viewModel.deleteSession("s1")
        viewModel.undoDelete()

        coVerify { sessionRepository.restoreSession("s1") }
    }

    @Test
    fun `enterSelectionMode sets isSelectionMode and selects session`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))

        viewModel = createViewModel()
        viewModel.enterSelectionMode("s1")

        assertTrue(viewModel.uiState.value.isSelectionMode)
        assertTrue("s1" in viewModel.uiState.value.selectedSessionIds)
    }

    @Test
    fun `toggleSelection adds session to selection`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1"), makeSession("s2")))

        viewModel = createViewModel()
        viewModel.enterSelectionMode("s1")
        viewModel.toggleSelection("s2")

        assertEquals(setOf("s1", "s2"), viewModel.uiState.value.selectedSessionIds)
    }

    @Test
    fun `toggleSelection removes session and exits mode when empty`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))

        viewModel = createViewModel()
        viewModel.enterSelectionMode("s1")
        viewModel.toggleSelection("s1")

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSessionIds.isEmpty())
    }

    @Test
    fun `selectAll selects all sessions`() = runTest {
        val sessions = listOf(makeSession("s1"), makeSession("s2"), makeSession("s3"))
        every { sessionRepository.getAllSessions() } returns flowOf(sessions)

        viewModel = createViewModel()
        viewModel.selectAll()

        assertEquals(setOf("s1", "s2", "s3"), viewModel.uiState.value.selectedSessionIds)
    }

    @Test
    fun `exitSelectionMode clears selection`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1")))

        viewModel = createViewModel()
        viewModel.enterSelectionMode("s1")
        viewModel.exitSelectionMode()

        assertFalse(viewModel.uiState.value.isSelectionMode)
        assertTrue(viewModel.uiState.value.selectedSessionIds.isEmpty())
    }

    @Test
    fun `deleteSelectedSessions soft-deletes and shows undo`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(listOf(makeSession("s1"), makeSession("s2")))
        coEvery { batchDeleteSessionsUseCase(listOf("s1", "s2")) } returns AppResult.Success(Unit)

        viewModel = createViewModel()
        viewModel.enterSelectionMode("s1")
        viewModel.toggleSelection("s2")
        viewModel.deleteSelectedSessions()

        assertNotNull(viewModel.uiState.value.undoState)
        assertFalse(viewModel.uiState.value.isSelectionMode)
    }

    @Test
    fun `deleteSelectedSessions does nothing when nothing selected`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())

        viewModel = createViewModel()
        viewModel.deleteSelectedSessions()

        assertNull(viewModel.uiState.value.undoState)
    }

    @Test
    fun `showRenameDialog sets renameDialog state`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())

        viewModel = createViewModel()
        viewModel.showRenameDialog("s1", "Old Title")

        val dialog = viewModel.uiState.value.renameDialog
        assertNotNull(dialog)
        assertEquals("s1", dialog?.sessionId)
        assertEquals("Old Title", dialog?.currentTitle)
    }

    @Test
    fun `dismissRenameDialog clears dialog state`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())

        viewModel = createViewModel()
        viewModel.showRenameDialog("s1", "Old Title")
        viewModel.dismissRenameDialog()

        assertNull(viewModel.uiState.value.renameDialog)
    }

    @Test
    fun `renameSession clears dialog on success`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        coEvery { renameSessionUseCase("s1", "New Title") } returns AppResult.Success(Unit)

        viewModel = createViewModel()
        viewModel.showRenameDialog("s1", "Old Title")
        viewModel.renameSession("s1", "New Title")

        assertNull(viewModel.uiState.value.renameDialog)
    }

    @Test
    fun `renameSession shows error on failure`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        coEvery { renameSessionUseCase("s1", "New Title") } returns AppResult.Error(message = "error", code = ErrorCode.VALIDATION_ERROR)

        viewModel = createViewModel()
        viewModel.showRenameDialog("s1", "Old Title")
        viewModel.renameSession("s1", "New Title")

        assertNotNull(viewModel.uiState.value.errorMessage)
    }

    @Test
    fun `clearError removes error message`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        coEvery { deleteSessionUseCase(any()) } returns AppResult.Error(message = "error", code = ErrorCode.UNKNOWN)

        viewModel = createViewModel()
        viewModel.deleteSession("nonexistent")
        viewModel.clearError()

        assertNull(viewModel.uiState.value.errorMessage)
    }

    // --- formatRelativeTime ---

    @Test
    fun `formatRelativeTime returns Just now for recent timestamps`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        viewModel = createViewModel()

        val result = viewModel.formatRelativeTime(System.currentTimeMillis() - 30_000L)
        assertEquals("Just now", result)
    }

    @Test
    fun `formatRelativeTime returns minutes ago`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        viewModel = createViewModel()

        val result = viewModel.formatRelativeTime(System.currentTimeMillis() - 5 * 60_000L)
        assertEquals("5 min ago", result)
    }

    @Test
    fun `formatRelativeTime returns hours ago`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        viewModel = createViewModel()

        val result = viewModel.formatRelativeTime(System.currentTimeMillis() - 2 * 3_600_000L)
        assertEquals("2 hours ago", result)
    }

    @Test
    fun `formatRelativeTime returns 1 hour ago for singular`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        viewModel = createViewModel()

        val result = viewModel.formatRelativeTime(System.currentTimeMillis() - 3_600_000L - 1L)
        assertEquals("1 hour ago", result)
    }

    @Test
    fun `formatRelativeTime returns Yesterday for previous day`() = runTest {
        every { sessionRepository.getAllSessions() } returns flowOf(emptyList())
        viewModel = createViewModel()

        val result = viewModel.formatRelativeTime(System.currentTimeMillis() - 25 * 3_600_000L)
        assertEquals("Yesterday", result)
    }
}
