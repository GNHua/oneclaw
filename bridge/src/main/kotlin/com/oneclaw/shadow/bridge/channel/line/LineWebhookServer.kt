package com.oneclaw.shadow.bridge.channel.line

import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class LineWebhookServer(
    port: Int,
    private val channelSecret: String,
    private val onEvent: suspend (JsonObject) -> Unit,
    private val scope: CoroutineScope
) : NanoHTTPD(port) {

    private val json = Json { ignoreUnknownKeys = true }

    override fun serve(session: IHTTPSession): Response {
        if (session.method != Method.POST || session.uri != "/webhook") {
            return newFixedLengthResponse(Response.Status.NOT_FOUND, MIME_PLAINTEXT, "Not Found")
        }

        val contentLength = session.headers["content-length"]?.toLongOrNull() ?: 0L
        val bodyBytes = session.inputStream.readBytes()
        val signature = session.headers["x-line-signature"]
        if (!verifySignature(bodyBytes, signature)) {
            Log.w(TAG, "LINE webhook signature verification failed")
            return newFixedLengthResponse(Response.Status.UNAUTHORIZED, MIME_PLAINTEXT, "Unauthorized")
        }

        try {
            val body = String(bodyBytes, Charsets.UTF_8)
            val parsed = json.parseToJsonElement(body).jsonObject
            val events = parsed["events"]?.jsonArray ?: return okResponse()
            events.forEach { event ->
                scope.launch { onEvent(event.jsonObject) }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error processing LINE webhook: ${e.message}")
        }

        return okResponse()
    }

    fun verifySignature(body: ByteArray, signature: String?): Boolean {
        if (signature == null) return false
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(channelSecret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
            val computed = mac.doFinal(body)
            val expected = Base64.getEncoder().encodeToString(computed)
            expected == signature
        } catch (e: Exception) {
            false
        }
    }

    private fun okResponse(): Response =
        newFixedLengthResponse(Response.Status.OK, MIME_PLAINTEXT, "OK")

    companion object {
        private const val TAG = "LineWebhookServer"
    }
}
