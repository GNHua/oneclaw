package com.oneclaw.shadow.tool.js.bridge

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Injects a fetch()-like API into the QuickJS context.
 * Delegates HTTP requests to OkHttpClient.
 *
 * This injects the __fetchImpl async function. The JS-side fetch() wrapper
 * must be injected separately via [FETCH_WRAPPER_JS] before executing tool code.
 */
object FetchBridge {

    private const val MAX_RESPONSE_SIZE = 100 * 1024  // 100KB, same as HttpRequestTool

    /**
     * JS wrapper code that must be evaluated in the QuickJS context
     * to provide the fetch() API. Evaluate this before running tool code.
     */
    val FETCH_WRAPPER_JS = """
        async function fetch(url, options) {
            const optionsJson = options ? JSON.stringify(options) : "{}";
            const responseJson = await __fetchImpl(url, optionsJson);
            const raw = JSON.parse(responseJson);
            return {
                ok: raw.status >= 200 && raw.status < 300,
                status: raw.status,
                statusText: raw.statusText,
                _body: raw.body,
                async text() { return this._body; },
                async json() { return JSON.parse(this._body); }
            };
        }
    """.trimIndent()

    /**
     * Inject the __fetchImpl async function into the QuickJS context.
     * Call this in the quickJs {} block, then evaluate FETCH_WRAPPER_JS separately.
     */
    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // __fetchImpl(url, optionsJson) -> Promise<String>
        // Returns a JSON-serialized response object.
        quickJs.asyncFunction("__fetchImpl") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("__fetchImpl: url argument required")
            val optionsJson = args.getOrNull(1)?.toString() ?: "{}"
            performFetch(okHttpClient, url, optionsJson)
        }
    }

    private suspend fun performFetch(
        okHttpClient: OkHttpClient,
        url: String,
        optionsJson: String
    ): String {
        val options = try {
            Json.parseToJsonElement(optionsJson).jsonObject
        } catch (e: Exception) {
            JsonObject(emptyMap())
        }

        val method = options["method"]?.jsonPrimitive?.content?.uppercase() ?: "GET"
        val headers = options["headers"]?.jsonObject
        val body = options["body"]?.jsonPrimitive?.content

        val httpUrl = url.toHttpUrlOrNull()
            ?: throw IllegalArgumentException("Invalid URL: $url")

        val requestBuilder = Request.Builder().url(httpUrl)

        headers?.entries?.forEach { (key, value) ->
            requestBuilder.addHeader(key, value.jsonPrimitive.content)
        }

        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> if (requestBody != null) requestBuilder.delete(requestBody) else requestBuilder.delete()
            else -> throw IllegalArgumentException("Unsupported HTTP method: $method")
        }

        val response = withContext(Dispatchers.IO) {
            okHttpClient.newCall(requestBuilder.build()).execute()
        }

        val responseBody = response.body?.let { responseBody ->
            val bytes = responseBody.bytes()
            if (bytes.size > MAX_RESPONSE_SIZE) {
                val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                "$truncated\n\n(Response truncated. First ${MAX_RESPONSE_SIZE / 1024}KB of ${bytes.size / 1024}KB.)"
            } else {
                String(bytes, Charsets.UTF_8)
            }
        } ?: ""

        // Return as JSON for the JS wrapper to parse
        val result = buildJsonObject {
            put("status", response.code)
            put("statusText", response.message)
            put("body", responseBody)
        }

        return result.toString()
    }
}
