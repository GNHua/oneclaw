package com.oneclaw.shadow.bridge

import com.oneclaw.shadow.bridge.channel.ChannelType
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class BridgeStateTrackerTest {

    @BeforeEach
    fun setUp() {
        BridgeStateTracker.reset()
    }

    @Test
    fun `initial state is not running and has no channel states`() = runTest {
        assertFalse(BridgeStateTracker.serviceRunning.first())
        assertTrue(BridgeStateTracker.channelStates.first().isEmpty())
    }

    @Test
    fun `updateServiceRunning sets running to true`() = runTest {
        BridgeStateTracker.updateServiceRunning(true)
        assertTrue(BridgeStateTracker.serviceRunning.first())
    }

    @Test
    fun `updateServiceRunning sets running to false`() = runTest {
        BridgeStateTracker.updateServiceRunning(true)
        BridgeStateTracker.updateServiceRunning(false)
        assertFalse(BridgeStateTracker.serviceRunning.first())
    }

    @Test
    fun `updateChannelState adds new channel state`() = runTest {
        val state = BridgeStateTracker.ChannelState(isRunning = true, connectedSince = 1000L)
        BridgeStateTracker.updateChannelState(ChannelType.TELEGRAM, state)

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals(1, channelStates.size)
        assertEquals(state, channelStates[ChannelType.TELEGRAM])
    }

    @Test
    fun `updateChannelState updates existing channel state`() = runTest {
        val initialState = BridgeStateTracker.ChannelState(isRunning = true, messageCount = 0)
        BridgeStateTracker.updateChannelState(ChannelType.TELEGRAM, initialState)

        val updatedState = BridgeStateTracker.ChannelState(isRunning = true, messageCount = 5)
        BridgeStateTracker.updateChannelState(ChannelType.TELEGRAM, updatedState)

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals(5, channelStates[ChannelType.TELEGRAM]?.messageCount)
    }

    @Test
    fun `updateChannelState can track multiple channels`() = runTest {
        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(isRunning = true)
        )
        BridgeStateTracker.updateChannelState(
            ChannelType.DISCORD,
            BridgeStateTracker.ChannelState(isRunning = false)
        )

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals(2, channelStates.size)
        assertTrue(channelStates[ChannelType.TELEGRAM]?.isRunning == true)
        assertFalse(channelStates[ChannelType.DISCORD]?.isRunning == true)
    }

    @Test
    fun `removeChannelState removes a channel`() = runTest {
        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(isRunning = true)
        )
        BridgeStateTracker.updateChannelState(
            ChannelType.DISCORD,
            BridgeStateTracker.ChannelState(isRunning = true)
        )

        BridgeStateTracker.removeChannelState(ChannelType.TELEGRAM)

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals(1, channelStates.size)
        assertNull(channelStates[ChannelType.TELEGRAM])
        assertTrue(channelStates.containsKey(ChannelType.DISCORD))
    }

    @Test
    fun `reset clears all state`() = runTest {
        BridgeStateTracker.updateServiceRunning(true)
        BridgeStateTracker.updateChannelState(
            ChannelType.TELEGRAM,
            BridgeStateTracker.ChannelState(isRunning = true)
        )
        BridgeStateTracker.updateChannelState(
            ChannelType.DISCORD,
            BridgeStateTracker.ChannelState(isRunning = true)
        )

        BridgeStateTracker.reset()

        assertFalse(BridgeStateTracker.serviceRunning.first())
        assertTrue(BridgeStateTracker.channelStates.first().isEmpty())
    }

    @Test
    fun `ChannelState has correct default values`() {
        val state = BridgeStateTracker.ChannelState(isRunning = true)
        assertTrue(state.isRunning)
        assertNull(state.connectedSince)
        assertNull(state.lastMessageAt)
        assertNull(state.error)
        assertEquals(0, state.messageCount)
    }

    @Test
    fun `ChannelState with error stores error message`() {
        val state = BridgeStateTracker.ChannelState(
            isRunning = false,
            error = "Connection refused"
        )
        assertEquals("Connection refused", state.error)
    }

    @Test
    fun `updateChannelState with error state is reflected`() = runTest {
        val errorState = BridgeStateTracker.ChannelState(
            isRunning = true,
            error = "Network error"
        )
        BridgeStateTracker.updateChannelState(ChannelType.SLACK, errorState)

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals("Network error", channelStates[ChannelType.SLACK]?.error)
    }

    @Test
    fun `removeChannelState on non-existent channel does not throw`() = runTest {
        BridgeStateTracker.removeChannelState(ChannelType.MATRIX)
        assertTrue(BridgeStateTracker.channelStates.first().isEmpty())
    }

    @Test
    fun `all ChannelTypes can be tracked`() = runTest {
        ChannelType.entries.forEach { type ->
            BridgeStateTracker.updateChannelState(
                type,
                BridgeStateTracker.ChannelState(isRunning = true)
            )
        }

        val channelStates = BridgeStateTracker.channelStates.first()
        assertEquals(ChannelType.entries.size, channelStates.size)
    }
}
