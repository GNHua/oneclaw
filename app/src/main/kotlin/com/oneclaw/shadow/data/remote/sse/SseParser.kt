package com.oneclaw.shadow.data.remote.sse

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

data class SseEvent(
    val type: String?,
    val data: String
)

fun ResponseBody.asSseFlow(): Flow<SseEvent> = channelFlow {
    withContext(Dispatchers.IO) {
        val reader = byteStream().bufferedReader(Charsets.UTF_8)
        try {
            var eventType: String? = null
            val dataBuilder = StringBuilder()

            var line: String?
            while (reader.readLine().also { line = it } != null) {
                val l = line!!

                when {
                    l.startsWith("event:") -> {
                        eventType = l.removePrefix("event:").trim()
                    }
                    l.startsWith("data:") -> {
                        dataBuilder.append(l.removePrefix("data:").trim())
                    }
                    l.isEmpty() -> {
                        if (dataBuilder.isNotEmpty()) {
                            val event = SseEvent(type = eventType, data = dataBuilder.toString())
                            eventType = null
                            dataBuilder.clear()
                            send(event)
                        }
                    }
                    else -> { /* ignore */ }
                }
            }
            // Flush remaining data if stream ends without trailing newline
            if (dataBuilder.isNotEmpty()) {
                send(SseEvent(type = eventType, data = dataBuilder.toString()))
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw e
        } finally {
            reader.close()
        }
    }
}
