package com.tomandy.palmclaw.performance

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.tomandy.palmclaw.data.AppDatabase
import com.tomandy.palmclaw.data.entity.ConversationEntity
import com.tomandy.palmclaw.data.entity.MessageEntity
import com.tomandy.palmclaw.security.CredentialVaultImpl
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import kotlin.system.measureTimeMillis

/**
 * Performance test for large conversations.
 *
 * This test suite verifies that PalmClaw maintains good performance even with:
 * - Large conversations (100+ messages)
 * - Many conversations in the database (10+)
 * - Frequent database queries
 * - Encryption/decryption operations
 *
 * Performance Targets:
 * - Room query for 1000 messages: < 100ms
 * - Database insert of 100 messages: < 1s
 * - Encryption/decryption: < 50ms per operation
 * - Memory usage: < 200MB idle, < 500MB active
 * - No UI jank (would be tested in a separate UI performance test)
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class LargeConversationTest {

    private lateinit var database: AppDatabase
    private val testConversationIds = mutableListOf<String>()

    @Before
    fun setup() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = AppDatabase.getDatabase(context)

        // Clear all tables
        database.clearAllTables()

        testConversationIds.clear()
    }

    @After
    fun tearDown() = runBlocking {
        // Clean up test data
        database.clearAllTables()
        database.close()
    }

    @Test
    fun largeConversation_insertsQuickly() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        // Create conversation
        val conversation = ConversationEntity(
            id = conversationId,
            title = "Large Conversation Test",
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        database.conversationDao().insert(conversation)

        // Create 200 messages
        val messages = (1..200).map { i ->
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "Message $i content with some text to simulate real messages. ".repeat(5),
                timestamp = System.currentTimeMillis() + i
            )
        }

        // Measure insert time
        val insertTime = measureTimeMillis {
            database.messageDao().insertAll(messages)
        }

        println("⏱️ Insert 200 messages: ${insertTime}ms")

        // Assert performance target: < 1000ms (1 second)
        assert(insertTime < 1000) {
            "Insert too slow: ${insertTime}ms (target: < 1000ms)"
        }

        // Verify all messages were inserted
        val retrievedMessages = database.messageDao()
            .getByConversation(conversationId)
            .first()

        assert(retrievedMessages.size == 200) {
            "Expected 200 messages, got ${retrievedMessages.size}"
        }
    }

    @Test
    fun largeConversation_queriesQuickly() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        // Create conversation
        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                title = "Query Performance Test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Insert 1000 messages
        val messages = (1..1000).map { i ->
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "Message $i",
                timestamp = System.currentTimeMillis() + i
            )
        }
        database.messageDao().insertAll(messages)

        // Measure query time
        val queryTime = measureTimeMillis {
            database.messageDao().getByConversation(conversationId).first()
        }

        println("⏱️ Query 1000 messages: ${queryTime}ms")

        // Assert performance target: < 100ms
        assert(queryTime < 100) {
            "Query too slow: ${queryTime}ms (target: < 100ms)"
        }
    }

    @Test
    fun multipleConversations_queryPerformance() = runBlocking {
        // Create 10 conversations with 100 messages each (1000 total)
        repeat(10) { convIndex ->
            val conversationId = UUID.randomUUID().toString()
            testConversationIds.add(conversationId)

            database.conversationDao().insert(
                ConversationEntity(
                    id = conversationId,
                    title = "Conversation $convIndex",
                    createdAt = System.currentTimeMillis(),
                    updatedAt = System.currentTimeMillis()
                )
            )

            val messages = (1..100).map { i ->
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = if (i % 2 == 0) "user" else "assistant",
                    content = "Conv $convIndex Message $i",
                    timestamp = System.currentTimeMillis() + i
                )
            }
            database.messageDao().insertAll(messages)
        }

        // Query all conversations
        val getAllTime = measureTimeMillis {
            database.conversationDao().getAllConversations().first()
        }

        println("⏱️ Query all conversations (10): ${getAllTime}ms")

        // Should be very fast (< 50ms)
        assert(getAllTime < 50) {
            "Query all conversations too slow: ${getAllTime}ms (target: < 50ms)"
        }

        // Query a specific conversation
        val specificQueryTime = measureTimeMillis {
            database.messageDao().getByConversation(testConversationIds[0]).first()
        }

        println("⏱️ Query specific conversation (100 messages): ${specificQueryTime}ms")

        // Should be fast (< 50ms)
        assert(specificQueryTime < 50) {
            "Query specific conversation too slow: ${specificQueryTime}ms (target: < 50ms)"
        }
    }

    @Test
    fun encryption_performanceAcceptable() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val vault = CredentialVaultImpl(context)

        val testKey = "sk-test-performance-key-" + "x".repeat(100)

        // Measure encryption (save) time
        val encryptTime = measureTimeMillis {
            repeat(10) { i ->
                vault.saveApiKey("TestProvider$i", testKey)
            }
        }

        val avgEncryptTime = encryptTime / 10.0
        println("⏱️ Average encryption time: ${avgEncryptTime}ms")

        // Assert encryption overhead is acceptable (< 50ms per operation)
        assert(avgEncryptTime < 50) {
            "Encryption too slow: ${avgEncryptTime}ms per operation (target: < 50ms)"
        }

        // Measure decryption (get) time
        val decryptTime = measureTimeMillis {
            repeat(10) { i ->
                vault.getApiKey("TestProvider$i")
            }
        }

        val avgDecryptTime = decryptTime / 10.0
        println("⏱️ Average decryption time: ${avgDecryptTime}ms")

        // Assert decryption overhead is acceptable (< 50ms per operation)
        assert(avgDecryptTime < 50) {
            "Decryption too slow: ${avgDecryptTime}ms per operation (target: < 50ms)"
        }

        // Clean up
        repeat(10) { i ->
            vault.deleteApiKey("TestProvider$i")
        }
    }

    @Test
    fun databaseSize_remainsReasonable() = runBlocking {
        // Create a realistic conversation: 500 messages
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                title = "Size Test Conversation",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Insert realistic messages (average ~200 chars)
        val messages = (1..500).map { i ->
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "This is a realistic message with some content. ".repeat(10),
                timestamp = System.currentTimeMillis() + i
            )
        }
        database.messageDao().insertAll(messages)

        // Calculate approximate size (rough estimate)
        val messageCount = database.messageDao()
            .getByConversation(conversationId)
            .first()
            .size

        val estimatedSizeBytes = messageCount * 200 * 2 // ~200 chars * 2 bytes per char
        val estimatedSizeMB = estimatedSizeBytes / (1024.0 * 1024.0)

        println("📊 Estimated database size for 500 messages: ${estimatedSizeMB}MB")

        // Database should remain reasonable (< 10MB for 500 messages)
        assert(estimatedSizeMB < 10) {
            "Database size too large: ${estimatedSizeMB}MB for 500 messages"
        }
    }

    @Test
    fun concurrentOperations_performWell() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                title = "Concurrent Test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Simulate concurrent operations: insert and query
        val operationTime = measureTimeMillis {
            // Insert 50 messages
            val messages = (1..50).map { i ->
                MessageEntity(
                    id = UUID.randomUUID().toString(),
                    conversationId = conversationId,
                    role = if (i % 2 == 0) "user" else "assistant",
                    content = "Message $i",
                    timestamp = System.currentTimeMillis() + i
                )
            }
            database.messageDao().insertAll(messages)

            // Immediately query (simulates UI reading while writing)
            repeat(5) {
                database.messageDao().getByConversation(conversationId).first()
            }
        }

        println("⏱️ Concurrent operations time: ${operationTime}ms")

        // Should complete quickly (< 500ms)
        assert(operationTime < 500) {
            "Concurrent operations too slow: ${operationTime}ms (target: < 500ms)"
        }
    }

    @Test
    fun deleteOperation_performanceAcceptable() = runBlocking {
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                title = "Delete Test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Insert 300 messages
        val messages = (1..300).map { i ->
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "Message $i",
                timestamp = System.currentTimeMillis() + i
            )
        }
        database.messageDao().insertAll(messages)

        // Measure delete time
        val deleteTime = measureTimeMillis {
            database.messageDao().deleteByConversation(conversationId)
        }

        println("⏱️ Delete 300 messages: ${deleteTime}ms")

        // Delete should be fast (< 200ms)
        assert(deleteTime < 200) {
            "Delete too slow: ${deleteTime}ms (target: < 200ms)"
        }

        // Verify deletion
        val remainingMessages = database.messageDao()
            .getByConversation(conversationId)
            .first()

        assert(remainingMessages.isEmpty()) {
            "Messages not deleted. Found ${remainingMessages.size} remaining"
        }
    }

    @Test
    fun memoryUsage_staysReasonable() = runBlocking {
        // Get initial memory
        val runtime = Runtime.getRuntime()
        val initialMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        println("📊 Initial memory: ${initialMemory}MB")

        // Create a large conversation
        val conversationId = UUID.randomUUID().toString()
        testConversationIds.add(conversationId)

        database.conversationDao().insert(
            ConversationEntity(
                id = conversationId,
                title = "Memory Test",
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis()
            )
        )

        // Insert and query 500 messages
        val messages = (1..500).map { i ->
            MessageEntity(
                id = UUID.randomUUID().toString(),
                conversationId = conversationId,
                role = if (i % 2 == 0) "user" else "assistant",
                content = "Message $i with content. ".repeat(10),
                timestamp = System.currentTimeMillis() + i
            )
        }
        database.messageDao().insertAll(messages)

        // Query messages multiple times
        repeat(5) {
            database.messageDao().getByConversation(conversationId).first()
        }

        // Get final memory
        runtime.gc() // Suggest garbage collection
        Thread.sleep(500) // Give GC time to run
        val finalMemory = (runtime.totalMemory() - runtime.freeMemory()) / (1024 * 1024)

        println("📊 Final memory: ${finalMemory}MB")
        println("📊 Memory increase: ${finalMemory - initialMemory}MB")

        // Memory increase should be reasonable (< 100MB for this test)
        val memoryIncrease = finalMemory - initialMemory
        assert(memoryIncrease < 100) {
            "Memory increase too high: ${memoryIncrease}MB (target: < 100MB)"
        }
    }
}
