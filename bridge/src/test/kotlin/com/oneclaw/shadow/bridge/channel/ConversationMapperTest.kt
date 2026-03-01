package com.oneclaw.shadow.bridge.channel

import com.oneclaw.shadow.bridge.BridgeConversationManager
import com.oneclaw.shadow.bridge.BridgePreferences
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class ConversationMapperTest {

    private lateinit var preferences: BridgePreferences
    private lateinit var conversationManager: BridgeConversationManager
    private lateinit var mapper: ConversationMapper

    @BeforeEach
    fun setUp() {
        preferences = mockk(relaxed = true)
        conversationManager = mockk(relaxed = true)
        mapper = ConversationMapper(preferences, conversationManager)
    }

    @Test
    fun `resolveConversationId returns stored ID when conversation exists`() = runTest {
        val storedId = "existing-conv-id"
        every { preferences.getBridgeConversationId() } returns storedId
        coEvery { conversationManager.conversationExists(storedId) } returns true

        val result = mapper.resolveConversationId()

        assertEquals(storedId, result)
        coVerify(exactly = 0) { conversationManager.createNewConversation() }
    }

    @Test
    fun `resolveConversationId creates new conversation when stored ID is null`() = runTest {
        val newId = "new-conv-id"
        every { preferences.getBridgeConversationId() } returns null
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.resolveConversationId()

        assertEquals(newId, result)
        coVerify { conversationManager.createNewConversation() }
        verify { preferences.setBridgeConversationId(newId) }
    }

    @Test
    fun `resolveConversationId creates new conversation when stored conversation does not exist`() = runTest {
        val oldId = "old-conv-id"
        val newId = "new-conv-id"
        every { preferences.getBridgeConversationId() } returns oldId
        coEvery { conversationManager.conversationExists(oldId) } returns false
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.resolveConversationId()

        assertEquals(newId, result)
        coVerify { conversationManager.createNewConversation() }
        verify { preferences.setBridgeConversationId(newId) }
    }

    @Test
    fun `createNewConversation calls manager and saves conversation ID`() = runTest {
        val newId = "brand-new-id"
        coEvery { conversationManager.createNewConversation() } returns newId

        val result = mapper.createNewConversation()

        assertEquals(newId, result)
        verify { preferences.setBridgeConversationId(newId) }
    }

    @Test
    fun `resolveConversationId does not call setBridgeConversationId when conversation already exists`() = runTest {
        val storedId = "existing-id"
        every { preferences.getBridgeConversationId() } returns storedId
        coEvery { conversationManager.conversationExists(storedId) } returns true

        mapper.resolveConversationId()

        verify(exactly = 0) { preferences.setBridgeConversationId(any()) }
    }
}
