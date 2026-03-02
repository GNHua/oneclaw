package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeConversationManager
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConversationMapperTest {

    private lateinit var conversationManager: BridgeConversationManager
    private lateinit var mapper: ConversationMapper

    @BeforeEach
    fun setUp() {
        conversationManager = mockk(relaxed = true)
        mapper = ConversationMapper(conversationManager)
    }

    @Test
    fun `resolveConversationId returns active ID when conversation exists`() = runTest {
        val activeId = "existing-conv-id"
        coEvery { conversationManager.getActiveConversationId() } returns activeId
        coEvery { conversationManager.conversationExists(activeId) } returns true

        val result = mapper.resolveConversationId()

        assertEquals(activeId, result)
        coVerify(exactly = 0) { conversationManager.createNewConversation() }
    }

    @Test
    fun `resolveConversationId creates new conversation when active ID is null`() = runTest {
        val newId = "new-conv-id"
        coEvery { conversationManager.getActiveConversationId() } returns null
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.resolveConversationId()

        assertEquals(newId, result)
        coVerify { conversationManager.createNewConversation() }
    }

    @Test
    fun `resolveConversationId creates new conversation when active conversation does not exist`() = runTest {
        val oldId = "old-conv-id"
        val newId = "new-conv-id"
        coEvery { conversationManager.getActiveConversationId() } returns oldId
        coEvery { conversationManager.conversationExists(oldId) } returns false
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.resolveConversationId()

        assertEquals(newId, result)
        coVerify { conversationManager.createNewConversation() }
    }

    @Test
    fun `createNewConversation delegates to conversation manager`() = runTest {
        val newId = "brand-new-id"
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.createNewConversation()

        assertEquals(newId, result)
    }
}
