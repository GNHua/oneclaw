package com.tomandy.palmclaw.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.tomandy.palmclaw.data.dao.ConversationDao
import com.tomandy.palmclaw.data.entity.ConversationEntity
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
class ConversationDaoTest {

    private lateinit var database: AppDatabase
    private lateinit var conversationDao: ConversationDao

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
    }

    @After
    fun teardown() {
        database.close()
    }

    @Test
    fun testInsertAndRetrieveConversation() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "test-id-1",
            title = "Test Conversation",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )

        // When
        conversationDao.insert(conversation)
        val retrieved = conversationDao.getConversation("test-id-1").first()

        // Then
        assertNotNull(retrieved)
        assertEquals(conversation.id, retrieved?.id)
        assertEquals(conversation.title, retrieved?.title)
        assertEquals(conversation.createdAt, retrieved?.createdAt)
        assertEquals(conversation.updatedAt, retrieved?.updatedAt)
    }

    @Test
    fun testUpdateConversation() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "test-id-2",
            title = "Original Title",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        conversationDao.insert(conversation)

        // When
        val updated = conversation.copy(
            title = "Updated Title",
            updatedAt = 2000L
        )
        conversationDao.update(updated)
        val retrieved = conversationDao.getConversation("test-id-2").first()

        // Then
        assertNotNull(retrieved)
        assertEquals("Updated Title", retrieved?.title)
        assertEquals(2000L, retrieved?.updatedAt)
        assertEquals(1000L, retrieved?.createdAt) // createdAt should remain unchanged
    }

    @Test
    fun testDeleteConversation() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "test-id-3",
            title = "To Be Deleted"
        )
        conversationDao.insert(conversation)

        // Verify it exists
        var retrieved = conversationDao.getConversation("test-id-3").first()
        assertNotNull(retrieved)

        // When
        conversationDao.delete(conversation)
        retrieved = conversationDao.getConversation("test-id-3").first()

        // Then
        assertNull(retrieved)
    }

    @Test
    fun testDeleteConversationById() = runTest {
        // Given
        val conversation = ConversationEntity(
            id = "test-id-4",
            title = "To Be Deleted By ID"
        )
        conversationDao.insert(conversation)

        // When
        conversationDao.deleteById("test-id-4")
        val retrieved = conversationDao.getConversation("test-id-4").first()

        // Then
        assertNull(retrieved)
    }

    @Test
    fun testGetAllConversationsOrdering() = runTest {
        // Given - insert conversations with different updatedAt times
        val conversation1 = ConversationEntity(
            id = "test-id-5",
            title = "Oldest",
            createdAt = 1000L,
            updatedAt = 1000L
        )
        val conversation2 = ConversationEntity(
            id = "test-id-6",
            title = "Middle",
            createdAt = 2000L,
            updatedAt = 2000L
        )
        val conversation3 = ConversationEntity(
            id = "test-id-7",
            title = "Newest",
            createdAt = 3000L,
            updatedAt = 3000L
        )

        conversationDao.insert(conversation1)
        conversationDao.insert(conversation2)
        conversationDao.insert(conversation3)

        // When
        val conversations = conversationDao.getAllConversations().first()

        // Then - should be ordered by updatedAt DESC (newest first)
        assertEquals(3, conversations.size)
        assertEquals("Newest", conversations[0].title)
        assertEquals("Middle", conversations[1].title)
        assertEquals("Oldest", conversations[2].title)
    }

    @Test
    fun testGetAllConversationsEmpty() = runTest {
        // When
        val conversations = conversationDao.getAllConversations().first()

        // Then
        assertTrue(conversations.isEmpty())
    }

    @Test
    fun testGetNonExistentConversation() = runTest {
        // When
        val retrieved = conversationDao.getConversation("non-existent-id").first()

        // Then
        assertNull(retrieved)
    }

    @Test
    fun testInsertWithConflictReplace() = runTest {
        // Given
        val conversation1 = ConversationEntity(
            id = "test-id-8",
            title = "Original",
            updatedAt = 1000L
        )
        conversationDao.insert(conversation1)

        // When - insert with same ID (should replace)
        val conversation2 = ConversationEntity(
            id = "test-id-8",
            title = "Replaced",
            updatedAt = 2000L
        )
        conversationDao.insert(conversation2)

        // Then
        val retrieved = conversationDao.getConversation("test-id-8").first()
        assertNotNull(retrieved)
        assertEquals("Replaced", retrieved?.title)
        assertEquals(2000L, retrieved?.updatedAt)

        // Verify only one conversation with this ID exists
        val all = conversationDao.getAllConversations().first()
        assertEquals(1, all.size)
    }
}
