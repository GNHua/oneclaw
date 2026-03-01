package com.oneclaw.shadow.feature.bridge

import app.cash.turbine.test
import com.oneclaw.shadow.bridge.BridgePreferences
import com.oneclaw.shadow.bridge.BridgeStateTracker
import com.oneclaw.shadow.bridge.channel.ChannelType
import com.oneclaw.shadow.bridge.service.BridgeCredentialProvider
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BridgeSettingsViewModelTest {

    private lateinit var preferences: BridgePreferences
    private lateinit var credentialProvider: BridgeCredentialProvider
    private lateinit var viewModel: BridgeSettingsViewModel

    private val testDispatcher = StandardTestDispatcher()

    @BeforeEach
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        BridgeStateTracker.reset()

        preferences = mockk(relaxed = true)
        credentialProvider = mockk(relaxed = true)

        // Default return values
        every { preferences.isBridgeEnabled() } returns false
        every { preferences.isWakeLockEnabled() } returns false
        every { preferences.isTelegramEnabled() } returns false
        every { preferences.isDiscordEnabled() } returns false
        every { preferences.isSlackEnabled() } returns false
        every { preferences.isMatrixEnabled() } returns false
        every { preferences.isLineEnabled() } returns false
        every { preferences.isWebChatEnabled() } returns false
        every { preferences.getAllowedTelegramUserIds() } returns emptySet()
        every { preferences.getAllowedDiscordUserIds() } returns emptySet()
        every { preferences.getAllowedSlackUserIds() } returns emptySet()
        every { preferences.getAllowedMatrixUserIds() } returns emptySet()
        every { preferences.getAllowedLineUserIds() } returns emptySet()
        every { preferences.getMatrixHomeserver() } returns ""
        every { preferences.getWebChatPort() } returns 8080
        every { preferences.getLineWebhookPort() } returns 8081
        every { credentialProvider.getTelegramBotToken() } returns null
        every { credentialProvider.getDiscordBotToken() } returns null
        every { credentialProvider.getSlackBotToken() } returns null
        every { credentialProvider.getSlackAppToken() } returns null
        every { credentialProvider.getMatrixAccessToken() } returns null
        every { credentialProvider.getLineChannelAccessToken() } returns null
        every { credentialProvider.getLineChannelSecret() } returns null
        every { credentialProvider.getWebChatAccessToken() } returns null

        viewModel = BridgeSettingsViewModel(preferences, credentialProvider)
    }

    @AfterEach
    fun tearDown() {
        BridgeStateTracker.reset()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state has correct defaults`() = runTest {
        val state = viewModel.uiState.value
        assertFalse(state.bridgeEnabled)
        assertFalse(state.serviceRunning)
        assertFalse(state.wakeLockEnabled)
        assertFalse(state.telegramEnabled)
        assertFalse(state.discordEnabled)
        assertFalse(state.slackEnabled)
        assertFalse(state.matrixEnabled)
        assertFalse(state.lineEnabled)
        assertFalse(state.webChatEnabled)
        assertEquals("", state.telegramBotToken)
        assertEquals(8080, state.webChatPort)
        assertEquals(8081, state.lineWebhookPort)
    }

    @Test
    fun `initial state loads existing preferences`() = runTest {
        every { preferences.isBridgeEnabled() } returns true
        every { preferences.isTelegramEnabled() } returns true
        every { credentialProvider.getTelegramBotToken() } returns "test-bot-token"
        every { preferences.getAllowedTelegramUserIds() } returns setOf("123456", "789012")

        val vm = BridgeSettingsViewModel(preferences, credentialProvider)
        val state = vm.uiState.value

        assertTrue(state.bridgeEnabled)
        assertTrue(state.telegramEnabled)
        assertEquals("test-bot-token", state.telegramBotToken)
        assertTrue(state.telegramAllowedUserIds.contains("123456"))
        assertTrue(state.telegramAllowedUserIds.contains("789012"))
    }

    @Test
    fun `toggleBridge updates state and saves preference`() = runTest {
        viewModel.toggleBridge(true)

        assertTrue(viewModel.uiState.value.bridgeEnabled)
        verify { preferences.setBridgeEnabled(true) }
    }

    @Test
    fun `toggleBridge to false updates state and saves preference`() = runTest {
        viewModel.toggleBridge(true)
        viewModel.toggleBridge(false)

        assertFalse(viewModel.uiState.value.bridgeEnabled)
        verify { preferences.setBridgeEnabled(false) }
    }

    @Test
    fun `toggleWakeLock updates state and saves preference`() = runTest {
        viewModel.toggleWakeLock(true)

        assertTrue(viewModel.uiState.value.wakeLockEnabled)
        verify { preferences.setWakeLockEnabled(true) }
    }

    @Test
    fun `toggleTelegram updates state and saves preference`() = runTest {
        viewModel.toggleTelegram(true)

        assertTrue(viewModel.uiState.value.telegramEnabled)
        verify { preferences.setTelegramEnabled(true) }
    }

    @Test
    fun `updateTelegramBotToken updates state and saves credential`() = runTest {
        viewModel.updateTelegramBotToken("new-token-123")

        assertEquals("new-token-123", viewModel.uiState.value.telegramBotToken)
        verify { credentialProvider.saveTelegramBotToken("new-token-123") }
    }

    @Test
    fun `updateTelegramAllowedUserIds parses comma-separated IDs`() = runTest {
        viewModel.updateTelegramAllowedUserIds("123, 456, 789")

        verify { preferences.setAllowedTelegramUserIds(setOf("123", "456", "789")) }
        assertEquals("123, 456, 789", viewModel.uiState.value.telegramAllowedUserIds)
    }

    @Test
    fun `updateTelegramAllowedUserIds handles empty string`() = runTest {
        viewModel.updateTelegramAllowedUserIds("")

        verify { preferences.setAllowedTelegramUserIds(emptySet()) }
    }

    @Test
    fun `toggleDiscord updates state and saves preference`() = runTest {
        viewModel.toggleDiscord(true)

        assertTrue(viewModel.uiState.value.discordEnabled)
        verify { preferences.setDiscordEnabled(true) }
    }

    @Test
    fun `updateDiscordBotToken updates state and saves credential`() = runTest {
        viewModel.updateDiscordBotToken("discord-token")

        assertEquals("discord-token", viewModel.uiState.value.discordBotToken)
        verify { credentialProvider.saveDiscordBotToken("discord-token") }
    }

    @Test
    fun `toggleSlack updates state and saves preference`() = runTest {
        viewModel.toggleSlack(true)

        assertTrue(viewModel.uiState.value.slackEnabled)
        verify { preferences.setSlackEnabled(true) }
    }

    @Test
    fun `updateSlackBotToken and AppToken updates state and saves credentials`() = runTest {
        viewModel.updateSlackBotToken("xoxb-bot-token")
        viewModel.updateSlackAppToken("xapp-app-token")

        assertEquals("xoxb-bot-token", viewModel.uiState.value.slackBotToken)
        assertEquals("xapp-app-token", viewModel.uiState.value.slackAppToken)
        verify { credentialProvider.saveSlackBotToken("xoxb-bot-token") }
        verify { credentialProvider.saveSlackAppToken("xapp-app-token") }
    }

    @Test
    fun `toggleMatrix updates state and saves preference`() = runTest {
        viewModel.toggleMatrix(true)

        assertTrue(viewModel.uiState.value.matrixEnabled)
        verify { preferences.setMatrixEnabled(true) }
    }

    @Test
    fun `updateMatrixHomeserver updates state and saves preference`() = runTest {
        viewModel.updateMatrixHomeserver("https://matrix.example.com")

        assertEquals("https://matrix.example.com", viewModel.uiState.value.matrixHomeserver)
        verify { preferences.setMatrixHomeserver("https://matrix.example.com") }
    }

    @Test
    fun `toggleLine updates state and saves preference`() = runTest {
        viewModel.toggleLine(true)

        assertTrue(viewModel.uiState.value.lineEnabled)
        verify { preferences.setLineEnabled(true) }
    }

    @Test
    fun `updateLineWebhookPort updates state and saves preference`() = runTest {
        viewModel.updateLineWebhookPort(9090)

        assertEquals(9090, viewModel.uiState.value.lineWebhookPort)
        verify { preferences.setLineWebhookPort(9090) }
    }

    @Test
    fun `toggleWebChat updates state and saves preference`() = runTest {
        viewModel.toggleWebChat(true)

        assertTrue(viewModel.uiState.value.webChatEnabled)
        verify { preferences.setWebChatEnabled(true) }
    }

    @Test
    fun `updateWebChatPort updates state and saves preference`() = runTest {
        viewModel.updateWebChatPort(9080)

        assertEquals(9080, viewModel.uiState.value.webChatPort)
        verify { preferences.setWebChatPort(9080) }
    }

    @Test
    fun `service running state reflects BridgeStateTracker`() = runTest {
        BridgeStateTracker.updateServiceRunning(true)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.serviceRunning)
    }

    @Test
    fun `channel states reflect BridgeStateTracker`() = runTest {
        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(isRunning = true, messageCount = 42)
        )
        advanceUntilIdle()

        val channelState = viewModel.uiState.value.channelStates[ChannelType.TELEGRAM]
        assertTrue(channelState?.isRunning == true)
        assertEquals(42, channelState?.messageCount)
    }

    @Test
    fun `updateDiscordAllowedUserIds with whitespace-heavy input parses correctly`() = runTest {
        viewModel.updateDiscordAllowedUserIds("  111 , 222  ,333")

        verify { preferences.setAllowedDiscordUserIds(setOf("111", "222", "333")) }
    }

    @Test
    fun `updateSlackAllowedUserIds with single ID works`() = runTest {
        viewModel.updateSlackAllowedUserIds("U12345")

        verify { preferences.setAllowedSlackUserIds(setOf("U12345")) }
    }

    @Test
    fun `updateWebChatAccessToken updates state and saves credential`() = runTest {
        viewModel.updateWebChatAccessToken("secret-webchat-token")

        assertEquals("secret-webchat-token", viewModel.uiState.value.webChatAccessToken)
        verify { credentialProvider.saveWebChatAccessToken("secret-webchat-token") }
    }
}
