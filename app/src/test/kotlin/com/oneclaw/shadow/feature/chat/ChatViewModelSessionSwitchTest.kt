package com.oneclaw.shadow.feature.chat

import com.oneclaw.shadow.core.lifecycle.AppLifecycleObserver
import com.oneclaw.shadow.core.model.Agent
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.notification.NotificationHelper
import com.oneclaw.shadow.core.repository.AgentRepository
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.ProviderRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.feature.chat.usecase.SendMessageUseCase
import com.oneclaw.shadow.feature.memory.trigger.MemoryTriggerManager
import com.oneclaw.shadow.feature.session.usecase.CreateSessionUseCase
import com.oneclaw.shadow.feature.session.usecase.GenerateTitleUseCase
import com.oneclaw.shadow.testutil.MainDispatcherRule
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.ExtendWith

@ExtendWith(MainDispatcherRule::class)
class ChatViewModelSessionSwitchTest {

    private lateinit var sendMessageUseCase: SendMessageUseCase
    private lateinit var sessionRepository: SessionRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var agentRepository: AgentRepository
    private lateinit var providerRepository: ProviderRepository
    private lateinit var createSessionUseCase: CreateSessionUseCase
    private lateinit var generateTitleUseCase: GenerateTitleUseCase
    private lateinit var appLifecycleObserver: AppLifecycleObserver
    private lateinit var notificationHelper: NotificationHelper
    private lateinit var memoryTriggerManager: MemoryTriggerManager

    private val now = 1_000_000L

    private val agent = Agent(
        id = "agent-general",
        name = "General Assistant",
        description = null,
        systemPrompt = "You are helpful.",
        preferredProviderId = null,
        preferredModelId = null,
        isBuiltIn = true,
        createdAt = now,
        updatedAt = now
    )

    private fun makeSession(id: String) = Session(
        id = id,
        title = "Session $id",
        currentAgentId = agent.id,
        messageCount = 0,
        lastMessagePreview = null,
        isActive = true,
        deletedAt = null,
        createdAt = now,
        updatedAt = now
    )

    @BeforeEach
    fun setup() {
        sendMessageUseCase = mockk(relaxed = true)
        sessionRepository = mockk(relaxed = true)
        messageRepository = mockk(relaxed = true)
        agentRepository = mockk(relaxed = true)
        providerRepository = mockk(relaxed = true)
        createSessionUseCase = mockk(relaxed = true)
        generateTitleUseCase = mockk(relaxed = true)
        appLifecycleObserver = mockk(relaxed = true)
        notificationHelper = mockk(relaxed = true)
        memoryTriggerManager = mockk(relaxed = true)

        every { providerRepository.getAllProviders() } returns flowOf(emptyList())
        every { messageRepository.getMessagesForSession(any()) } returns flowOf(emptyList())
    }

    private fun createViewModel(withMemory: Boolean = true): ChatViewModel {
        return ChatViewModel(
            sendMessageUseCase = sendMessageUseCase,
            sessionRepository = sessionRepository,
            messageRepository = messageRepository,
            agentRepository = agentRepository,
            providerRepository = providerRepository,
            createSessionUseCase = createSessionUseCase,
            generateTitleUseCase = generateTitleUseCase,
            appLifecycleObserver = appLifecycleObserver,
            notificationHelper = notificationHelper,
            memoryTriggerManager = if (withMemory) memoryTriggerManager else null
        )
    }

    @Test
    fun `onSessionSwitch is called when switching from session-A to session-B`() = runTest {
        // Set up session-A to return a valid session so sessionId is populated in uiState
        coEvery { sessionRepository.getSessionById("session-A") } returns makeSession("session-A")
        coEvery { agentRepository.getAgentById(agent.id) } returns agent

        val viewModel = createViewModel()

        // Load session-A -- after this, uiState.sessionId == "session-A"
        viewModel.initialize("session-A")

        // Now switch to session-B -- this should trigger onSessionSwitch("session-A")
        coEvery { sessionRepository.getSessionById("session-B") } returns makeSession("session-B")
        viewModel.initialize("session-B")

        verify(exactly = 1) { memoryTriggerManager.onSessionSwitch("session-A") }
    }

    @Test
    fun `onSessionSwitch is NOT called when initialize(null) is called`() = runTest {
        val viewModel = createViewModel()

        viewModel.initialize(null)

        verify(exactly = 0) { memoryTriggerManager.onSessionSwitch(any()) }
    }

    @Test
    fun `onSessionSwitch is NOT called when no previous session (first load)`() = runTest {
        // Initial sessionId is null -- no trigger should fire
        val viewModel = createViewModel()

        coEvery { sessionRepository.getSessionById("session-B") } returns makeSession("session-B")
        coEvery { agentRepository.getAgentById(agent.id) } returns agent
        viewModel.initialize("session-B")

        verify(exactly = 0) { memoryTriggerManager.onSessionSwitch(any()) }
    }

    @Test
    fun `onSessionSwitch is NOT called when same session ID is re-initialized`() = runTest {
        coEvery { sessionRepository.getSessionById("session-A") } returns makeSession("session-A")
        coEvery { agentRepository.getAgentById(agent.id) } returns agent

        val viewModel = createViewModel()
        viewModel.initialize("session-A")

        // Re-initialize with the same session ID
        viewModel.initialize("session-A")

        verify(exactly = 0) { memoryTriggerManager.onSessionSwitch(any()) }
    }

    @Test
    fun `null memoryTriggerManager does not cause crash on session switch`() = runTest {
        // Create ViewModel without MemoryTriggerManager
        coEvery { sessionRepository.getSessionById("session-A") } returns makeSession("session-A")
        coEvery { agentRepository.getAgentById(agent.id) } returns agent

        val viewModel = createViewModel(withMemory = false)
        viewModel.initialize("session-A")

        // Switch session -- should not throw even without MemoryTriggerManager
        coEvery { sessionRepository.getSessionById("session-B") } returns makeSession("session-B")
        viewModel.initialize("session-B")

        // No crash is the assertion
    }

    @Test
    fun `onSessionSwitch fires with the previous session ID`() = runTest {
        coEvery { sessionRepository.getSessionById("session-A") } returns makeSession("session-A")
        coEvery { sessionRepository.getSessionById("session-C") } returns makeSession("session-C")
        coEvery { agentRepository.getAgentById(agent.id) } returns agent

        val viewModel = createViewModel()
        viewModel.initialize("session-A")
        viewModel.initialize("session-C")

        // Verifies the correct previous session ID is passed
        verify(exactly = 1) { memoryTriggerManager.onSessionSwitch("session-A") }
        verify(exactly = 0) { memoryTriggerManager.onSessionSwitch("session-C") }
    }
}
