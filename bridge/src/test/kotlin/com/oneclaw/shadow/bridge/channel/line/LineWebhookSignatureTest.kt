package com.oneclaw.shadow.bridge.channel.line

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LineWebhookSignatureTest {

    private lateinit var server: LineWebhookServer
    private val channelSecret = "test_channel_secret_12345"
    private val scope = CoroutineScope(Dispatchers.Unconfined)

    @BeforeEach
    fun setUp() {
        server = LineWebhookServer(
            port = 19999,
            channelSecret = channelSecret,
            onEvent = {},
            scope = scope
        )
    }

    private fun computeHmac(body: ByteArray, secret: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val computed = mac.doFinal(body)
        return Base64.getEncoder().encodeToString(computed)
    }

    @Test
    fun `valid HMAC signature passes verification`() {
        val body = """{"events":[]}""".toByteArray(Charsets.UTF_8)
        val signature = computeHmac(body, channelSecret)

        assertTrue(server.verifySignature(body, signature), "Valid signature should pass")
    }

    @Test
    fun `invalid HMAC signature fails verification`() {
        val body = """{"events":[]}""".toByteArray(Charsets.UTF_8)
        val wrongSignature = "invalid_signature_base64=="

        assertFalse(server.verifySignature(body, wrongSignature), "Invalid signature should fail")
    }

    @Test
    fun `null signature fails verification`() {
        val body = """{"events":[]}""".toByteArray(Charsets.UTF_8)

        assertFalse(server.verifySignature(body, null), "Null signature should fail")
    }

    @Test
    fun `signature with wrong secret fails verification`() {
        val body = """{"events":[]}""".toByteArray(Charsets.UTF_8)
        val wrongSignature = computeHmac(body, "wrong_secret")

        assertFalse(server.verifySignature(body, wrongSignature), "Signature with wrong secret should fail")
    }

    @Test
    fun `signature for different body fails verification`() {
        val body = """{"events":[]}""".toByteArray(Charsets.UTF_8)
        val otherBody = """{"events":[{"type":"message"}]}""".toByteArray(Charsets.UTF_8)
        val signature = computeHmac(otherBody, channelSecret)

        assertFalse(server.verifySignature(body, signature), "Signature for different body should fail")
    }

    @Test
    fun `empty body with valid HMAC passes verification`() {
        val body = "".toByteArray(Charsets.UTF_8)
        val signature = computeHmac(body, channelSecret)

        assertTrue(server.verifySignature(body, signature), "Empty body with valid HMAC should pass")
    }

    @Test
    fun `large body with valid HMAC passes verification`() {
        val body = """{"events":[${(1..100).joinToString(",") { """{"type":"message","id":"$it"}""" }}]}"""
            .toByteArray(Charsets.UTF_8)
        val signature = computeHmac(body, channelSecret)

        assertTrue(server.verifySignature(body, signature), "Large body with valid HMAC should pass")
    }
}
