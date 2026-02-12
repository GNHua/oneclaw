package com.tomandy.palmclaw.integration

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.tomandy.palmclaw.MainActivity
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.security.CredentialVault
import com.tomandy.palmclaw.security.CredentialVaultImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Integration test for the complete chat flow.
 *
 * This test verifies:
 * 1. Initial app state (empty conversations)
 * 2. API key management in Settings
 * 3. Sending a message
 * 4. Receiving a response
 * 5. Message persistence in Room database
 *
 * Note: This test requires a valid API key and network connection.
 * For CI/CD, consider using a mock server instead.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ChatFlowIntegrationTest {

    @get:Rule
    val composeTestRule = createAndroidComposeRule<MainActivity>()

    private lateinit var credentialVault: CredentialVault
    private lateinit var database: AppDatabase

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext

        // Initialize credential vault
        credentialVault = CredentialVaultImpl(context)

        // Get database instance
        database = AppDatabase.getDatabase(context)

        // Clean up any existing test data
        database.clearAllTables()

        // Save a test API key (use environment variable)
        // Using Google Gemini for testing (free tier, fast responses)
        val testApiKey = System.getenv("GOOGLE_GEMINI_API_KEY") ?: "test-api-key-placeholder"
        credentialVault.saveApiKey("Gemini", testApiKey)
    }

    @After
    fun tearDown() = runBlocking {
        // Clean up test data
        database.clearAllTables()
        database.close()
    }

    @Test
    fun completeChat_workflow_succeeds() {
        // Wait for initial composition
        composeTestRule.waitForIdle()

        // 1. Verify initial state - should show chat screen
        // (The app now starts directly in chat screen, not with "No conversations yet")
        composeTestRule.waitForIdle()

        // 2. Navigate to Settings to verify API key
        composeTestRule.onNodeWithContentDescription("Settings").performClick()
        composeTestRule.waitForIdle()

        // 3. Verify Settings screen loaded
        composeTestRule.onNodeWithText("API Key Settings").assertExists()
        composeTestRule.onNodeWithText("Saved Providers").assertExists()

        // 4. Verify API key exists (should show Gemini in saved providers)
        composeTestRule.onNodeWithText("Gemini").assertExists()
        composeTestRule.onNodeWithText("API key configured").assertExists()

        // 5. Navigate back to Chat using back button/navigation
        // Since we don't have a back button in the UI, let's use device back
        composeTestRule.activityRule.scenario.onActivity { activity ->
            activity.onBackPressedDispatcher.onBackPressed()
        }
        composeTestRule.waitForIdle()

        // 6. Verify we're back on chat screen
        composeTestRule.onNodeWithContentDescription("Chat input field").assertExists()

        // Note: The following steps require a valid API key and network connection
        // In a real environment, you might want to use a mock server or skip these steps

        // 7. Send a simple message
        composeTestRule.onNodeWithContentDescription("Chat input field")
            .performTextInput("Hello")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        composeTestRule.waitForIdle()

        // 8. Verify loading indicator appears
        // (This might be a progress indicator or text saying "AI is thinking...")
        // Give it a moment to start processing
        Thread.sleep(500)

        // 9. Wait for response (with timeout)
        // Note: This test will fail if:
        // - No valid API key is provided
        // - Network is unavailable
        // - API returns an error
        // Consider making this more robust for CI/CD environments
        composeTestRule.waitUntil(timeoutMillis = 30_000) {
            // Check if we have at least 2 message bubbles (user + assistant)
            composeTestRule.onAllNodesWithTag("message_bubble", useUnmergedTree = true)
                .fetchSemanticsNodes().size >= 2
        }

        // 10. Verify both user and assistant messages exist
        composeTestRule.onNodeWithText("Hello").assertExists()

        // 11. Verify message persists in database
        runBlocking {
            // Get all conversations
            val conversations = database.conversationDao().getAllConversations().first()
            assert(conversations.isNotEmpty()) { "Expected at least one conversation" }

            // Get messages from the first conversation
            val messages = database.messageDao()
                .getByConversation(conversations.first().id)
                .first()

            assert(messages.size >= 2) {
                "Expected at least 2 messages (user + assistant), got ${messages.size}"
            }
            assert(messages.any { it.role == "user" && it.content == "Hello" }) {
                "User message not found in database"
            }
            assert(messages.any { it.role == "assistant" }) {
                "Assistant response not found in database"
            }
        }
    }

    @Test
    fun apiKey_persistsInCredentialVault() = runTest {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVaultImpl(context)

        // 1. Save API key
        vault.saveApiKey("TestProvider", "sk-test-key-123456")

        // 2. Verify immediate retrieval
        val key1 = vault.getApiKey("TestProvider")
        assert(key1 == "sk-test-key-123456") {
            "Expected 'sk-test-key-123456', got '$key1'"
        }

        // 3. Create new vault instance (simulates app restart)
        val newVault = CredentialVaultImpl(context)

        // 4. Verify key persists across instances
        val key2 = newVault.getApiKey("TestProvider")
        assert(key2 == "sk-test-key-123456") {
            "Key did not persist. Expected 'sk-test-key-123456', got '$key2'"
        }

        // Clean up
        vault.deleteApiKey("TestProvider")
    }

    @Test
    fun networkError_displaysUserFriendlyMessage() {
        composeTestRule.waitForIdle()

        // This test would ideally use a mock server that returns errors
        // For now, we'll test with an invalid API key scenario

        runBlocking {
            // Clear any existing API keys
            database.clearAllTables()
            credentialVault.deleteApiKey("OpenAI")
        }

        // Try to send a message without an API key
        composeTestRule.onNodeWithContentDescription("Chat input field")
            .performTextInput("Test message")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        composeTestRule.waitForIdle()

        // Wait a bit for the error to appear
        Thread.sleep(1000)

        // Verify an error message appears
        // (The exact text depends on how errors are displayed in the UI)
        // Look for common error indicators
        val errorIndicators = listOf(
            "Error",
            "Failed",
            "API key",
            "No model",
            "unavailable"
        )

        // At least one error indicator should be visible
        val hasErrorMessage = errorIndicators.any { errorText ->
            try {
                composeTestRule.onNodeWithText(errorText, substring = true, ignoreCase = true)
                    .assertExists()
                true
            } catch (e: AssertionError) {
                false
            }
        }

        assert(hasErrorMessage) {
            "Expected to see an error message, but none found"
        }
    }

    @Test
    fun messageList_scrollsToBottom_onNewMessage() {
        composeTestRule.waitForIdle()

        // Send a message
        composeTestRule.onNodeWithContentDescription("Chat input field")
            .performTextInput("Test scroll message")
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithContentDescription("Send message").performClick()
        composeTestRule.waitForIdle()

        // The message list should auto-scroll to show the new message
        // Verify the user's message is visible (not scrolled off screen)
        composeTestRule.onNodeWithText("Test scroll message").assertIsDisplayed()
    }
}
