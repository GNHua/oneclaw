package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeAgentExecutor
import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgeMessage
import com.oneclaw.shadow.bridge.BridgeMessageObserver
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class MessagingChannelTest {

    private lateinit var preferences: BridgePreferences
    private lateinit var conversationMapper: ConversationMapper
    private lateinit var agentExecutor: BridgeAgentExecutor
    private lateinit var messageObserver: BridgeMessageObserver
    private lateinit var conversationManager: BridgeConversationManager
    private lateinit var scope: CoroutineScope
    private lateinit var channel: TestMessagingChannel

    private val sentResponses = mutableListOf<Pair<String, BridgeMessage>>()

    @BeforeEach
    fun setUp() {
        BridgeStateTracker.reset()
        preferences = mockk(relaxed = true)
        conversationMapper = mockk(relaxed = true)
        agentExecutor = mockk(relaxed = true)
        messageObserver = mockk(relaxed = true)
        conversationManager = mockk(relaxed = true)
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        sentResponses.clear()

        // Default mocks
        every { preferences.getAllowedTelegramUserIds() } returns emptySet()
        every { preferences.getLastChatId(any()) } returns null
        coEvery { conversationMapper.resolveConversationId() } returns "test-conv-id"
        coEvery { messageObserver.awaitNextAssistantMessage(any(), any(), any()) } returns
                BridgeMessage("Agent response", System.currentTimeMillis())

        channel = TestMessagingChannel(
            channelType = ChannelType.TELEGRAM,
            preferences = preferences,
            conversationMapper = conversationMapper,
            agentExecutor = agentExecutor,
            messageObserver = messageObserver,
            conversationManager = conversationManager,
            scope = scope,
            onSendResponse = { chatId, msg -> sentResponses.add(chatId to msg) }
        )
    }

    @AfterEach
    fun tearDown() {
        BridgeStateTracker.reset()
    }

    @Test
    fun `processInboundMessage sends agent response to user`() = runTest {
        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "Hello",
            messageId = "msg-1"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isNotEmpty(), "Expected at least one response sent")
        assertEquals("chat-123", sentResponses.first().first)
        assertEquals("Agent response", sentResponses.first().second.content)
    }

    @Test
    fun `processInboundMessage deduplicates identical message IDs`() = runTest {
        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "Hello",
            messageId = "msg-dedup"
        )

        channel.testProcessInboundMessage(msg)
        channel.testProcessInboundMessage(msg)

        assertEquals(1, sentResponses.size, "Duplicate message should be ignored")
    }

    @Test
    fun `processInboundMessage allows message with different message ID`() = runTest {
        val msg1 = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "Hello",
            messageId = "msg-1"
        )
        val msg2 = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "Hello again",
            messageId = "msg-2"
        )

        channel.testProcessInboundMessage(msg1)
        channel.testProcessInboundMessage(msg2)

        assertEquals(2, sentResponses.size, "Different messages should each get a response")
    }

    @Test
    fun `processInboundMessage blocks user not in whitelist`() = runTest {
        every { preferences.getAllowedTelegramUserIds() } returns setOf("allowed-user")

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "Blocked",
            senderId = "blocked-user",
            text = "Hello",
            messageId = "msg-blocked"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isEmpty(), "Blocked user should not get a response")
        coVerify(exactly = 0) { agentExecutor.executeMessage(any(), any(), any()) }
    }

    @Test
    fun `processInboundMessage allows user in whitelist`() = runTest {
        every { preferences.getAllowedTelegramUserIds() } returns setOf("allowed-user")

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "Allowed",
            senderId = "allowed-user",
            text = "Hello",
            messageId = "msg-allowed"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isNotEmpty(), "Allowed user should get a response")
    }

    @Test
    fun `processInboundMessage allows any user when whitelist is empty`() = runTest {
        every { preferences.getAllowedTelegramUserIds() } returns emptySet()

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "Anyone",
            senderId = "any-user-id",
            text = "Hello",
            messageId = "msg-open"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isNotEmpty(), "Open access should allow any user")
    }

    @Test
    fun `processInboundMessage saves last chat ID for broadcast`() = runTest {
        val msg = ChannelMessage(
            externalChatId = "chat-456",
            senderName = "User",
            senderId = "user-1",
            text = "Hello",
            messageId = "msg-last-chat"
        )

        channel.testProcessInboundMessage(msg)

        verify { preferences.setLastChatId(ChannelType.TELEGRAM, "chat-456") }
    }

    @Test
    fun `processInboundMessage handles clear command`() = runTest {
        coEvery { conversationMapper.createNewConversation() } returns "new-conv-id"

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "/clear",
            messageId = "msg-clear"
        )

        channel.testProcessInboundMessage(msg)

        coVerify { conversationMapper.createNewConversation() }
        assertTrue(sentResponses.isNotEmpty(), "Clear command should send confirmation")
        assertTrue(
            sentResponses.first().second.content.contains("cleared", ignoreCase = true),
            "Clear confirmation message should mention 'cleared'"
        )
    }

    @Test
    fun `processInboundMessage inserts user message before executing agent`() = runTest {
        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = "User",
            senderId = "user-1",
            text = "Test message",
            messageId = "msg-insert"
        )

        channel.testProcessInboundMessage(msg)

        coVerify { conversationManager.insertUserMessage("test-conv-id", "Test message", any()) }
        coVerify { agentExecutor.executeMessage("test-conv-id", "Test message", any()) }
    }

    @Test
    fun `processInboundMessage handles null senderId with empty whitelist`() = runTest {
        every { preferences.getAllowedTelegramUserIds() } returns emptySet()

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = null,
            senderId = null,
            text = "Anonymous message",
            messageId = "msg-anon"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isNotEmpty(), "Null senderId with empty whitelist should be allowed")
    }

    @Test
    fun `processInboundMessage blocks null senderId when whitelist is non-empty`() = runTest {
        every { preferences.getAllowedTelegramUserIds() } returns setOf("user-123")

        val msg = ChannelMessage(
            externalChatId = "chat-123",
            senderName = null,
            senderId = null,
            text = "Anonymous message",
            messageId = "msg-anon-blocked"
        )

        channel.testProcessInboundMessage(msg)

        assertTrue(sentResponses.isEmpty(), "Null senderId with non-empty whitelist should be blocked")
    }
}

class TestMessagingChannel(
    channelType: ChannelType,
    preferences: BridgePreferences,
    conversationMapper: ConversationMapper,
    agentExecutor: BridgeAgentExecutor,
    messageObserver: BridgeMessageObserver,
    conversationManager: BridgeConversationManager,
    scope: CoroutineScope,
    private val onSendResponse: suspend (String, BridgeMessage) -> Unit
) : MessagingChannel(
    channelType = channelType,
    preferences = preferences,
    conversationMapper = conversationMapper,
    agentExecutor = agentExecutor,
    messageObserver = messageObserver,
    conversationManager = conversationManager,
    scope = scope
) {
    override suspend fun start() {}
    override suspend fun stop() {}
    override fun isRunning(): Boolean = true
    override suspend fun sendResponse(externalChatId: String, message: BridgeMessage) {
        onSendResponse(externalChatId, message)
    }

    suspend fun testProcessInboundMessage(msg: ChannelMessage) {
        processInboundMessage(msg)
    }
}
