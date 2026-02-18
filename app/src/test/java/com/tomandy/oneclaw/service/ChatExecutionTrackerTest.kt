package com.tomandy.oneclaw.service

import com.tomandy.oneclaw.agent.AgentState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class ChatExecutionTrackerTest {

    @Before
    fun setup() {
        // Reset singleton state between tests
        ChatExecutionTracker.activeConversations.value.forEach {
            ChatExecutionTracker.markInactive(it)
        }
        ChatExecutionTracker.errors.value.keys.forEach {
            ChatExecutionTracker.clearError(it)
        }
    }

    @Test
    fun `markActive adds conversation to activeConversations`() {
        ChatExecutionTracker.markActive("conv1")

        assertTrue(ChatExecutionTracker.activeConversations.value.contains("conv1"))
    }

    @Test
    fun `markActive clears existing error for that conversation`() {
        ChatExecutionTracker.setError("conv1", "some error")

        ChatExecutionTracker.markActive("conv1")

        assertFalse(ChatExecutionTracker.errors.value.containsKey("conv1"))
    }

    @Test
    fun `markInactive removes conversation from activeConversations`() {
        ChatExecutionTracker.markActive("conv1")

        ChatExecutionTracker.markInactive("conv1")

        assertFalse(ChatExecutionTracker.activeConversations.value.contains("conv1"))
    }

    @Test
    fun `markInactive removes associated agentState`() {
        ChatExecutionTracker.markActive("conv1")
        ChatExecutionTracker.updateAgentState("conv1", AgentState.Thinking)

        ChatExecutionTracker.markInactive("conv1")

        assertFalse(ChatExecutionTracker.agentStates.value.containsKey("conv1"))
    }

    @Test
    fun `updateAgentState sets state for conversation`() {
        val state = AgentState.Completed("done")
        ChatExecutionTracker.updateAgentState("conv1", state)

        assertEquals(state, ChatExecutionTracker.agentStates.value["conv1"])
    }

    @Test
    fun `setError stores error for conversation`() {
        ChatExecutionTracker.setError("conv1", "Network timeout")

        assertEquals("Network timeout", ChatExecutionTracker.errors.value["conv1"])
    }

    @Test
    fun `clearError removes error for conversation`() {
        ChatExecutionTracker.setError("conv1", "error")

        ChatExecutionTracker.clearError("conv1")

        assertFalse(ChatExecutionTracker.errors.value.containsKey("conv1"))
    }
}
