package com.oneclaw.shadow.feature.chat.usecase

import com.oneclaw.shadow.core.model.AiModel
import com.oneclaw.shadow.core.model.Message
import com.oneclaw.shadow.core.model.MessageType
import com.oneclaw.shadow.core.model.ModelSource
import com.oneclaw.shadow.core.model.Provider
import com.oneclaw.shadow.core.model.ProviderType
import com.oneclaw.shadow.core.model.Session
import com.oneclaw.shadow.core.repository.MessageRepository
import com.oneclaw.shadow.core.repository.SessionRepository
import com.oneclaw.shadow.core.util.AppResult
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapter
import com.oneclaw.shadow.data.remote.adapter.ModelApiAdapterFactory
import com.oneclaw.shadow.data.security.ApiKeyStorage
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class AutoCompactUseCaseTest {

    private lateinit var sessionRepository: SessionRepository
    private lateinit var messageRepository: MessageRepository
    private lateinit var apiKeyStorage: ApiKeyStorage
    private lateinit var adapterFactory: ModelApiAdapterFactory
    private lateinit var adapter: ModelApiAdapter
    private lateinit var useCase: AutoCompactUseCase

    private val now = System.currentTimeMillis()

    private val provider = Provider(
        id = "provider-openai",
        name = "OpenAI",
        type = ProviderType.OPENAI,
        apiBaseUrl = "https://api.openai.com/v1",
        isPreConfigured = true,
        isActive = true,
        createdAt = now,
        updatedAt = now
    )

    private fun makeSession(
        compactedSummary: String? = null,
        compactBoundaryTimestamp: Long? = null
    ) = Session(
        id = "session-1",
        title = "Test",
        currentAgentId = "agent-1",
        messageCount = 0,
        lastMessagePreview = null,
        isActive = true,
        deletedAt = null,
        createdAt = now,
        updatedAt = now,
        compactedSummary = compactedSummary,
        compactBoundaryTimestamp = compactBoundaryTimestamp
    )

    private fun makeModel(contextWindowSize: Int?) = AiModel(
        id = "gpt-4o",
        displayName = "GPT-4o",
        providerId = "provider-openai",
        isDefault = true,
        source = ModelSource.PRESET,
        contextWindowSize = contextWindowSize
    )

    private fun makeMessages(count: Int, charsEach: Int = 400): List<Message> {
        return (1..count).map { i ->
            Message(
                id = "msg-$i",
                sessionId = "session-1",
                type = MessageType.USER,
                content = "a".repeat(charsEach),
                thinkingContent = null,
                toolCallId = null,
                toolName = null,
                toolInput = null,
                toolOutput = null,
                toolStatus = null,
                toolDurationMs = null,
                tokenCountInput = null,
                tokenCountOutput = null,
                modelId = null,
                providerId = null,
                createdAt = (now + i * 1000)
            )
        }
    }

    @BeforeEach
    fun setup() {
        sessionRepository = mockk(relaxed = true)
        messageRepository = mockk()
        apiKeyStorage = mockk()
        adapterFactory = mockk()
        adapter = mockk()
        useCase = AutoCompactUseCase(sessionRepository, messageRepository, apiKeyStorage, adapterFactory)
    }

    @Test
    fun `returns no-op when contextWindowSize is null`() = runTest {
        val model = makeModel(contextWindowSize = null)

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
        coVerify(exactly = 0) { messageRepository.getMessagesSnapshot(any()) }
    }

    @Test
    fun `returns no-op when total tokens below threshold`() = runTest {
        val model = makeModel(contextWindowSize = 10000)
        // 5 messages * 400 chars / 4 CHARS_PER_TOKEN = 500 tokens total
        // threshold = 10000 * 0.85 = 8500, so 500 <= 8500 => no compact
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
        coVerify(exactly = 0) { sessionRepository.updateCompactedSummary(any(), any(), any()) }
    }

    @Test
    fun `returns no-op when api key not found`() = runTest {
        // Use a small context window so threshold is exceeded
        val model = makeModel(contextWindowSize = 100)
        // 5 messages * 400 chars each = 2000 chars / 4 = 500 tokens > 85 threshold
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns makeSession()
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns null

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
        coVerify(exactly = 0) { sessionRepository.updateCompactedSummary(any(), any(), any()) }
    }

    @Test
    fun `returns no-op when session not found`() = runTest {
        val model = makeModel(contextWindowSize = 100)
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns null

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
    }

    @Test
    fun `compacts when tokens exceed threshold and api succeeds`() = runTest {
        val model = makeModel(contextWindowSize = 100)
        // 5 messages * 400 chars = 500 tokens, threshold=85
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns makeSession()
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns "sk-test"
        coEvery { adapterFactory.getAdapter(ProviderType.OPENAI) } returns adapter
        coEvery {
            adapter.generateSimpleCompletion(any(), any(), any(), any(), any())
        } returns AppResult.Success("This is a compact summary of the conversation.")

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertTrue(result.didCompact)
        coVerify { sessionRepository.updateCompactedSummary("session-1", any(), any()) }
    }

    @Test
    fun `returns no-op when api returns empty summary`() = runTest {
        val model = makeModel(contextWindowSize = 100)
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns makeSession()
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns "sk-test"
        coEvery { adapterFactory.getAdapter(ProviderType.OPENAI) } returns adapter
        coEvery {
            adapter.generateSimpleCompletion(any(), any(), any(), any(), any())
        } returns AppResult.Success("")

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
        coVerify(exactly = 0) { sessionRepository.updateCompactedSummary(any(), any(), any()) }
    }

    @Test
    fun `returns no-op when api fails on both attempts`() = runTest {
        val model = makeModel(contextWindowSize = 100)
        val messages = makeMessages(5, charsEach = 400)
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns makeSession()
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns "sk-test"
        coEvery { adapterFactory.getAdapter(ProviderType.OPENAI) } returns adapter
        coEvery {
            adapter.generateSimpleCompletion(any(), any(), any(), any(), any())
        } returns AppResult.Error(message = "API error")

        val result = useCase.compactIfNeeded("session-1", model, provider)

        assertFalse(result.didCompact)
    }

    @Test
    fun `splitMessages puts older messages before protectedBudget boundary`() {
        // 10 messages with 100 chars each = 25 tokens each
        val messages = (1..10).map { i ->
            Message(
                id = "msg-$i", sessionId = "s", type = MessageType.USER,
                content = "a".repeat(100), thinkingContent = null,
                toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                toolStatus = null, toolDurationMs = null, tokenCountInput = null,
                tokenCountOutput = null, modelId = null, providerId = null,
                createdAt = i.toLong()
            )
        }
        // protectedBudget = 75 tokens: should protect last 3 messages (3*25=75)
        val (older, protected) = useCase.splitMessages(messages, 75)
        assertEquals(7, older.size)
        assertEquals(3, protected.size)
    }

    @Test
    fun `splitMessages returns all messages as protected when all fit in budget`() {
        val messages = (1..3).map { i ->
            Message(
                id = "msg-$i", sessionId = "s", type = MessageType.USER,
                content = "a".repeat(4), thinkingContent = null,
                toolCallId = null, toolName = null, toolInput = null, toolOutput = null,
                toolStatus = null, toolDurationMs = null, tokenCountInput = null,
                tokenCountOutput = null, modelId = null, providerId = null,
                createdAt = i.toLong()
            )
        }
        // Each message = 1 token, budget = 100, all fit
        val (older, protected) = useCase.splitMessages(messages, 100)
        assertEquals(0, older.size)
        assertEquals(3, protected.size)
    }

    @Test
    fun `compact passes existing summary in prompt when session has prior summary`() = runTest {
        val existingSummary = "Existing context summary"
        val model = makeModel(contextWindowSize = 100)
        val messages = makeMessages(5, charsEach = 400)
        val promptSlot = io.mockk.slot<String>()
        coEvery { messageRepository.getMessagesSnapshot("session-1") } returns messages
        coEvery { sessionRepository.getSessionById("session-1") } returns makeSession(
            compactedSummary = existingSummary,
            compactBoundaryTimestamp = messages.first().createdAt
        )
        coEvery { apiKeyStorage.getApiKey("provider-openai") } returns "sk-test"
        coEvery { adapterFactory.getAdapter(ProviderType.OPENAI) } returns adapter
        coEvery {
            adapter.generateSimpleCompletion(any(), any(), any(), capture(promptSlot), any())
        } returns AppResult.Success("New summary")

        useCase.compactIfNeeded("session-1", model, provider)

        assertTrue(promptSlot.captured.contains(existingSummary))
    }
}
