package com.oneclaw.shadow.tool.js.bridge

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.asyncFunction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * Injects file transfer functions into the QuickJS context.
 * Provides downloadToFile() and uploadMultipart() for Google Drive
 * and other tools that need binary file operations.
 *
 * These operations cannot be done through the standard fetch() bridge
 * because fetch() operates on text content (truncated at 100KB).
 * File transfer needs to handle binary data of arbitrary size.
 */
object FileTransferBridge {

    /**
     * JS wrapper code that provides downloadToFile() and uploadMultipart().
     * Must be evaluated in the QuickJS context before tool code runs.
     */
    val FILE_TRANSFER_WRAPPER_JS = """
        async function downloadToFile(url, savePath, headers) {
            const headersJson = headers ? JSON.stringify(headers) : "{}";
            const resultJson = await __downloadToFile(url, savePath, headersJson);
            return JSON.parse(resultJson);
        }

        async function uploadMultipart(url, parts, headers) {
            const partsJson = JSON.stringify(parts);
            const headersJson = headers ? JSON.stringify(headers) : "{}";
            const resultJson = await __uploadMultipart(url, partsJson, headersJson);
            return JSON.parse(resultJson);
        }
    """.trimIndent()

    /**
     * Inject the low-level async functions into the QuickJS context.
     */
    fun inject(quickJs: QuickJs, okHttpClient: OkHttpClient) {
        // __downloadToFile(url, savePath, headersJson) -> Promise<String>
        // Downloads a URL to a local file. Returns JSON: {success, path, size, error}
        quickJs.asyncFunction("__downloadToFile") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: url required")
            val savePath = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("downloadToFile: savePath required")
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"

            performDownload(okHttpClient, url, savePath, headersJson)
        }

        // __uploadMultipart(url, partsJson, headersJson) -> Promise<String>
        // Uploads a file as multipart/related. Returns JSON response.
        quickJs.asyncFunction("__uploadMultipart") { args: Array<Any?> ->
            val url = args.getOrNull(0)?.toString()
                ?: throw IllegalArgumentException("uploadMultipart: url required")
            val partsJson = args.getOrNull(1)?.toString()
                ?: throw IllegalArgumentException("uploadMultipart: parts required")
            val headersJson = args.getOrNull(2)?.toString() ?: "{}"

            performUpload(okHttpClient, url, partsJson, headersJson)
        }
    }

    /**
     * Download a URL to a local file path.
     * Returns JSON: { "success": true, "path": "/...", "size": 12345 }
     *          or:  { "success": false, "error": "..." }
     */
    internal suspend fun performDownload(
        okHttpClient: OkHttpClient,
        url: String,
        savePath: String,
        headersJson: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val headers = Json.parseToJsonElement(headersJson).jsonObject
                val requestBuilder = Request.Builder().url(url)
                headers.entries.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value.jsonPrimitive.content)
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                if (!response.isSuccessful) {
                    return@withContext buildJsonObject {
                        put("success", false)
                        put("error", "HTTP ${response.code}: ${response.message}")
                    }.toString()
                }

                val file = File(savePath)
                file.parentFile?.mkdirs()
                response.body?.byteStream()?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }

                buildJsonObject {
                    put("success", true)
                    put("path", file.absolutePath)
                    put("size", file.length())
                }.toString()
            } catch (e: Exception) {
                buildJsonObject {
                    put("success", false)
                    put("error", e.message ?: "Download failed")
                }.toString()
            }
        }
    }

    /**
     * Upload a file as multipart/related request.
     * partsJson format:
     * [
     *   { "type": "json", "contentType": "application/json", "body": "{...}" },
     *   { "type": "file", "contentType": "application/octet-stream", "path": "/path/to/file" }
     * ]
     */
    internal suspend fun performUpload(
        okHttpClient: OkHttpClient,
        url: String,
        partsJson: String,
        headersJson: String
    ): String {
        return withContext(Dispatchers.IO) {
            try {
                val parts = Json.parseToJsonElement(partsJson).jsonArray
                val headers = Json.parseToJsonElement(headersJson).jsonObject

                val multipartBuilder = MultipartBody.Builder()
                    .setType(MultipartBody.FORM)

                for (part in parts) {
                    val partObj = part.jsonObject
                    val contentType = partObj["contentType"]?.jsonPrimitive?.content
                        ?: "application/octet-stream"
                    val mediaType = contentType.toMediaTypeOrNull()

                    when (partObj["type"]?.jsonPrimitive?.content) {
                        "json" -> {
                            val body = partObj["body"]?.jsonPrimitive?.content ?: "{}"
                            multipartBuilder.addPart(body.toRequestBody(mediaType))
                        }
                        "file" -> {
                            val filePath = partObj["path"]?.jsonPrimitive?.content
                                ?: throw IllegalArgumentException("File part missing 'path'")
                            val file = File(filePath)
                            multipartBuilder.addPart(file.asRequestBody(mediaType))
                        }
                    }
                }

                val requestBuilder = Request.Builder()
                    .url(url)
                    .post(multipartBuilder.build())

                headers.entries.forEach { (key, value) ->
                    requestBuilder.addHeader(key, value.jsonPrimitive.content)
                }

                val response = okHttpClient.newCall(requestBuilder.build()).execute()
                val responseBody = response.body?.string() ?: ""

                buildJsonObject {
                    put("status", response.code)
                    put("body", responseBody)
                    put("ok", response.isSuccessful)
                }.toString()
            } catch (e: Exception) {
                buildJsonObject {
                    put("status", 0)
                    put("body", "")
                    put("ok", false)
                    put("error", e.message ?: "Upload failed")
                }.toString()
            }
        }
    }
}
