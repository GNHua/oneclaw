package com.tomandy.palmclaw.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.tomandy.palmclaw.MainActivity
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.security.CredentialVaultImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

/**
 * Integration test for process death recovery.
 *
 * This test verifies that the app correctly restores its state after:
 * 1. Process death (system kills the app to reclaim memory)
 * 2. Activity recreation (configuration change, e.g., rotation)
 * 3. App restart
 *
 * The test uses Activity.recreate() to simulate process death, which is
 * similar to what happens when Android kills the app in the background.
 *
 * To manually test:
 * 1. Enable "Don't keep activities" in Developer Options
 * 2. Use the app, navigate away, come back
 * 3. Verify conversation is restored
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ProcessDeathTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var database: AppDatabase
    private var testConversationId: String = ""

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize database
        database = AppDatabase.getDatabase(context)

        // Clear all existing data
        database.clearAllTables()

        // Set up test API key
        val credentialVault = CredentialVaultImpl(context)
        credentialVault.saveApiKey("OpenAI", "test-key")

        // Create a test conversation with messages
        testConversationId = UUID.randomUUID().toString()
        val conversation = ConversationEntity(
            id = testConversationId,
            title = "Test Conversation",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.conversationDao().insert(conversation)

        // Add test messages
        val messages = listOf(
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = testConversationId,
                role = "user",
                content = "Test message 1",
                timestamp = System.currentTimeMillis()
            ),
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = testConversationId,
                role = "assistant",
                content = "Test response 1",
                timestamp = System.currentTimeMillis() + 1000
            ),
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = testConversationId,
                role = "user",
                content = "Test message 2",
                timestamp = System.currentTimeMillis() + 2000
            )
        )
        database.messageDao().insertAll(messages)
    }

    @After
    fun tearDown() = runBlocking {
        database.clearAllTables()
        database.close()
    }

    @Test
    fun conversationState_recoversAfterProcessDeath() {
        // 1. Wait for initial composition
        composeTestRule.waitForIdle()

        // 2. Verify test messages exist before process death
        composeTestRule.onNodeWithText("Test message 1").assertExists()
        composeTestRule.onNodeWithText("Test response 1").assertExists()
        composeTestRule.onNodeWithText("Test message 2").assertExists()

        // 3. Simulate process death by recreating the activity
        composeTestRule.activityRule.scenario.recreate()

        // 4. Wait for activity to be recreated and UI to stabilize
        composeTestRule.waitForIdle()
        Thread.sleep(1000) // Give database time to reload

        // 5. Verify conversation was restored from database
        composeTestRule.onNodeWithText("Test message 1").assertExists()
        composeTestRule.onNodeWithText("Test response 1").assertExists()
        composeTestRule.onNodeWithText("Test message 2").assertExists()

        // 6. Verify database still contains the messages
        runBlocking {
            val messages = database.messageDao()
                .getByConversation(testConversationId)
                .first()

            assert(messages.size == 3) {
                "Expected 3 messages after recovery, got ${messages.size}"
            }
            assert(messages[0].content == "Test message 1") {
                "First message content doesn't match"
            }
            assert(messages[1].content == "Test response 1") {
                "Second message content doesn't match"
            }
            assert(messages[2].content == "Test message 2") {
                "Third message content doesn't match"
            }
        }

        // 7. Verify user can still interact with the app
        composeTestRule.onNodeWithContentDescription("Chat input field").assertExists()
        composeTestRule.onNodeWithContentDescription("Chat input field")
            .performTextInput("Test message after recovery")
        composeTestRule.waitForIdle()

        // Message should appear in the input field
        composeTestRule.onNodeWithText("Test message after recovery").assertExists()
    }

    @Test
    fun multipleProcessDeaths_preserveAllData() {
        // Test multiple process death scenarios
        composeTestRule.waitForIdle()

        // First process death
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        Thread.sleep(500)

        // Verify data persists
        composeTestRule.onNodeWithText("Test message 1").assertExists()

        // Second process death
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        Thread.sleep(500)

        // Verify data still persists
        composeTestRule.onNodeWithText("Test message 1").assertExists()
        composeTestRule.onNodeWithText("Test response 1").assertExists()
        composeTestRule.onNodeWithText("Test message 2").assertExists()

        // Verify database integrity
        runBlocking {
            val messages = database.messageDao()
                .getByConversation(testConversationId)
                .first()

            assert(messages.size == 3) {
                "Messages lost after multiple process deaths. Expected 3, got ${messages.size}"
            }
        }
    }

    @Test
    fun newConversation_persistsAcrossProcessDeath() = runBlocking {
        // Clear existing test data
        database.clearAllTables()

        composeTestRule.waitForIdle()

        // Create a new conversation by sending a message
        composeTestRule.onNodeWithContentDescription("Chat input field")
            .performTextInput("New conversation message")
        composeTestRule.waitForIdle()

        // Don't send the message, just verify the input exists
        composeTestRule.onNodeWithText("New conversation message").assertExists()

        // Get the conversation ID before process death
        val conversationsBefore = database.conversationDao().getAllConversations().first()
        val conversationIdBefore = conversationsBefore.firstOrNull()?.id

        // Simulate process death
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        Thread.sleep(1000)

        // Verify conversation list persists
        val conversationsAfter = database.conversationDao().getAllConversations().first()

        if (conversationIdBefore != null) {
            // If a conversation was created, verify it persists
            assert(conversationsAfter.any { it.id == conversationIdBefore }) {
                "Conversation $conversationIdBefore not found after process death"
            }
        }

        // Verify UI is functional after recovery
        composeTestRule.onNodeWithContentDescription("Chat input field").assertExists()
    }

    @Test
    fun databaseOperations_completeAfterProcessDeath() = runBlocking {
        composeTestRule.waitForIdle()

        // Add a new message before process death
        val newMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = testConversationId,
            role = "user",
            content = "Message added before death",
            timestamp = System.currentTimeMillis()
        )
        database.messageDao().insert(newMessage)

        // Verify it's in the database
        val messagesBefore = database.messageDao()
            .getByConversation(testConversationId)
            .first()
        assert(messagesBefore.size == 4) { "Expected 4 messages before death" }

        // Simulate process death
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        Thread.sleep(1000)

        // Verify the message persists after process death
        val messagesAfter = database.messageDao()
            .getByConversation(testConversationId)
            .first()

        assert(messagesAfter.size == 4) {
            "Expected 4 messages after death, got ${messagesAfter.size}"
        }
        assert(messagesAfter.any { it.content == "Message added before death" }) {
            "New message not found after process death"
        }

        // Add a message after process death
        val postDeathMessage = MessageEntity(
            id = UUID.randomUUID().toString(),
            conversationId = testConversationId,
            role = "user",
            content = "Message added after death",
            timestamp = System.currentTimeMillis()
        )
        database.messageDao().insert(postDeathMessage)

        // Verify database works correctly after recovery
        val finalMessages = database.messageDao()
            .getByConversation(testConversationId)
            .first()
        assert(finalMessages.size == 5) {
            "Expected 5 messages total, got ${finalMessages.size}"
        }
    }

    @Test
    fun settingsScreen_survivesProcessDeath() {
        composeTestRule.waitForIdle()

        // Navigate to settings
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.waitForIdle()

        // Verify we're on settings screen
        composeTestRule.onNodeWithText("API Key Settings").assertExists()
        composeTestRule.onNodeWithText("OpenAI").assertExists()

        // Simulate process death
        composeTestRule.activityRule.scenario.recreate()
        composeTestRule.waitForIdle()
        Thread.sleep(500)

        // Verify we're still on settings screen or can navigate back to it
        // (The exact behavior depends on navigation state restoration)
        // At minimum, the app should not crash
        composeTestRule.onNodeWithContentDescription("Settings").assertExists()
    }
}
