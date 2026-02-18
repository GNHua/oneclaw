package com.tomandy.oneclaw.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomandy.oneclaw.data.dao.ConversationDao
import com.tomandy.oneclaw.data.dao.MessageDao
import com.tomandy.oneclaw.data.entity.ConversationEntity
import com.tomandy.oneclaw.data.entity.MessageEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MessageDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var conversationDao: ConversationDao
    private lateinit var messageDao: MessageDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(
            context,
            AppDatabase::class.java
        )
        .allowMainThreadQueries()
        .build()

        conversationDao = database.conversationDao()
        messageDao = database.messageDao()
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveMessage() = runTest {
        // Given - create a conversation first
        val conversation = ConversationEntity(
            id = "conv-1",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val message = MessageEntity(
            id = "msg-1",
            conversationId = "conv-1",
            role = "user",
            content = "Hello, world!",
            timestamp = 1000L
        )

        // When
        messageDao.insert(message)
        val retrieved = messageDao.getMessage("msg-1")

        // Then
        assertNotNull(retrieved)
        assertEquals(message.id, retrieved?.id)
        assertEquals(message.conversationId, retrieved?.conversationId)
        assertEquals(message.role, retrieved?.role)
        assertEquals(message.content, retrieved?.content)
        assertEquals(message.timestamp, retrieved?.timestamp)
    }

    @Test
    fun testGetMessagesOrderedByTimestamp() = runTest {
        // Given - create a conversation
        val conversation = ConversationEntity(
            id = "conv-2",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        // Insert messages with different timestamps
        val message1 = MessageEntity(
            id = "msg-1",
            conversationId = "conv-2",
            role = "user",
            content = "First message",
            timestamp = 1000L
        )
        val message2 = MessageEntity(
            id = "msg-2",
            conversationId = "conv-2",
            role = "assistant",
            content = "Second message",
            timestamp = 2000L
        )
        val message3 = MessageEntity(
            id = "msg-3",
            conversationId = "conv-2",
            role = "user",
            content = "Third message",
            timestamp = 3000L
        )

        // Insert in random order
        messageDao.insert(message2)
        messageDao.insert(message1)
        messageDao.insert(message3)

        // When
        val messages = messageDao.getMessages("conv-2").first()

        // Then - should be ordered by timestamp ASC (oldest first)
        assertEquals(3, messages.size)
        assertEquals("First message", messages[0].content)
        assertEquals("Second message", messages[1].content)
        assertEquals("Third message", messages[2].content)
    }

    @Test
    fun testInsertAll() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-3",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val messages = listOf(
            MessageEntity(
                id = "msg-1",
                conversationId = "conv-3",
                role = "user",
                content = "Message 1",
                timestamp = 1000L
            ),
            MessageEntity(
                id = "msg-2",
                conversationId = "conv-3",
                role = "assistant",
                content = "Message 2",
                timestamp = 2000L
            ),
            MessageEntity(
                id = "msg-3",
                conversationId = "conv-3",
                role = "user",
                content = "Message 3",
                timestamp = 3000L
            )
        )

        // When
        messageDao.insertAll(messages)
        val retrieved = messageDao.getMessages("conv-3").first()

        // Then
        assertEquals(3, retrieved.size)
    }

    @Test
    fun testDeleteMessage() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-4",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val message = MessageEntity(
            id = "msg-1",
            conversationId = "conv-4",
            role = "user",
            content = "To be deleted"
        )
        messageDao.insert(message)

        // Verify it exists
        var retrieved = messageDao.getMessage("msg-1")
        assertNotNull(retrieved)

        // When
        messageDao.delete(message)
        retrieved = messageDao.getMessage("msg-1")

        // Then
        assertNull(retrieved)
    }

    @Test
    fun testDeleteAllInConversation() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-5",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val messages = listOf(
            MessageEntity(id = "msg-1", conversationId = "conv-5", role = "user", content = "Message 1"),
            MessageEntity(id = "msg-2", conversationId = "conv-5", role = "assistant", content = "Message 2"),
            MessageEntity(id = "msg-3", conversationId = "conv-5", role = "user", content = "Message 3")
        )
        messageDao.insertAll(messages)

        // Verify messages exist
        var retrieved = messageDao.getMessages("conv-5").first()
        assertEquals(3, retrieved.size)

        // When
        messageDao.deleteAllInConversation("conv-5")
        retrieved = messageDao.getMessages("conv-5").first()

        // Then
        assertTrue(retrieved.isEmpty())
    }

    @Test
    fun testGetMessageCount() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-6",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val messages = listOf(
            MessageEntity(id = "msg-1", conversationId = "conv-6", role = "user", content = "Message 1"),
            MessageEntity(id = "msg-2", conversationId = "conv-6", role = "assistant", content = "Message 2"),
            MessageEntity(id = "msg-3", conversationId = "conv-6", role = "user", content = "Message 3")
        )
        messageDao.insertAll(messages)

        // When
        val count = messageDao.getMessageCount("conv-6")

        // Then
        assertEquals(3, count)
    }

    @Test
    fun testGetMessageCountEmpty() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-7",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        // When
        val count = messageDao.getMessageCount("conv-7")

        // Then
        assertEquals(0, count)
    }

    @Test
    fun testCascadeDelete() = runTest {
        // Given - create conversation with messages
        val conversation = ConversationEntity(
            id = "conv-8",
            title = "Test Conversation"
        )
        conversationDao.insert(conversation)

        val messages = listOf(
            MessageEntity(id = "msg-1", conversationId = "conv-8", role = "user", content = "Message 1"),
            MessageEntity(id = "msg-2", conversationId = "conv-8", role = "assistant", content = "Message 2"),
            MessageEntity(id = "msg-3", conversationId = "conv-8", role = "user", content = "Message 3")
        )
        messageDao.insertAll(messages)

        // Verify messages exist
        var messagesRetrieved = messageDao.getMessages("conv-8").first()
        assertEquals(3, messagesRetrieved.size)

        // When - delete the conversation
        conversationDao.deleteById("conv-8")

        // Then - messages should be deleted automatically (cascade)
        messagesRetrieved = messageDao.getMessages("conv-8").first()
        assertTrue(messagesRetrieved.isEmpty())

        // Verify individual messages are gone
        assertNull(messageDao.getMessage("msg-1"))
        assertNull(messageDao.getMessage("msg-2"))
        assertNull(messageDao.getMessage("msg-3"))
    }

    @Test
    fun testMessagesFromDifferentConversations() = runTest {
        // Given - create two conversations
        val conversation1 = ConversationEntity(id = "conv-9", title = "Conversation 1")
        val conversation2 = ConversationEntity(id = "conv-10", title = "Conversation 2")
        conversationDao.insert(conversation1)
        conversationDao.insert(conversation2)

        // Insert messages in different conversations
        val messagesConv1 = listOf(
            MessageEntity(id = "msg-1", conversationId = "conv-9", role = "user", content = "Conv1 Message 1"),
            MessageEntity(id = "msg-2", conversationId = "conv-9", role = "assistant", content = "Conv1 Message 2")
        )
        val messagesConv2 = listOf(
            MessageEntity(id = "msg-3", conversationId = "conv-10", role = "user", content = "Conv2 Message 1"),
            MessageEntity(id = "msg-4", conversationId = "conv-10", role = "assistant", content = "Conv2 Message 2"),
            MessageEntity(id = "msg-5", conversationId = "conv-10", role = "user", content = "Conv2 Message 3")
        )
        messageDao.insertAll(messagesConv1)
        messageDao.insertAll(messagesConv2)

        // When
        val conv1Messages = messageDao.getMessages("conv-9").first()
        val conv2Messages = messageDao.getMessages("conv-10").first()

        // Then
        assertEquals(2, conv1Messages.size)
        assertEquals(3, conv2Messages.size)

        // Verify message content
        assertTrue(conv1Messages.all { it.conversationId == "conv-9" })
        assertTrue(conv2Messages.all { it.conversationId == "conv-10" })
    }

    @Test
    fun testGetMessagesEmpty() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "conv-11",
            title = "Empty Conversation"
        )
        conversationDao.insert(conversation)

        // When
        val messages = messageDao.getMessages("conv-11").first()

        // Then
        assertTrue(messages.isEmpty())
    }

    @Test
    fun testGetNonExistentMessage() = runTest {
        // When
        val message = messageDao.getMessage("non-existent-id")

        // Then
        assertNull(message)
    }
}
