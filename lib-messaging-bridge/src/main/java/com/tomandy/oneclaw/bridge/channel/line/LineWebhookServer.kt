package com.tomandy.oneclaw.bridge.channel.line

import android.util.Base64
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.serialization.json.Json
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LineWebhookServer(
    port: Int,
    private val channelSecret: String,
    private val onWebhookEvent: (LineWebhookEvent) -> Unit
) : NanoHTTPD(port) {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/webhook") {
            return newFixedLengthResponse(Response.Status.OK, "text/plain", "OK")
        }

        try {
            // Read body
            val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
            val buffer = ByteArray(contentLength)
            session.inputStream.read(buffer)
            val body = String(buffer, Charsets.UTF_8)

            // Verify signature
            val signature = session.headers["x-line-signature"]
            if (signature == null || !verifySignature(body, signature)) {
                Log.w(TAG, "Invalid webhook signature")
                return newFixedLengthResponse(
                    Response.Status.FORBIDDEN, "text/plain", "Invalid signature"
                )
            }

            // Parse events
            val webhookBody = json.decodeFromString<LineWebhookBody>(body)
            for (event in webhookBody.events) {
                onWebhookEvent(event)
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", "{}")
        } catch (e: Exception) {
            Log.e(TAG, "Error processing LINE webhook", e)
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/plain", "Error"
            )
        }
    }

    private fun verifySignature(body: String, signature: String): Boolean {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(channelSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val hash = mac.doFinal(body.toByteArray(Charsets.UTF_8))
            val expected = Base64.encodeToString(hash, Base64.NO_WRAP)
            expected == signature
        } catch (e: Exception) {
            Log.e(TAG, "Signature verification error", e)
            false
        }
    }

    companion object {
        private const val TAG = "LineWebhookServer"
    }
}
