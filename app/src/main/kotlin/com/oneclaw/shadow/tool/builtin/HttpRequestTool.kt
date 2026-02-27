package com.oneclaw.shadow.tool.builtin

import com.oneclaw.shadow.core.model.ToolDefinition
import com.oneclaw.shadow.core.model.ToolParameter
import com.oneclaw.shadow.core.model.ToolParametersSchema
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.engine.Tool
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

class HttpRequestTool(private val okHttpClient: OkHttpClient) : Tool {

    companion object {
        private const val MAX_RESPONSE_SIZE = 100 * 1024  // 100KB
    }

    override val definition = ToolDefinition(
        name = "http_request",
        description = "Make an HTTP request to a URL",
        parametersSchema = ToolParametersSchema(
            properties = mapOf(
                "url" to ToolParameter(
                    type = "string",
                    description = "The URL to request"
                ),
                "method" to ToolParameter(
                    type = "string",
                    description = "HTTP method: GET, POST, PUT, DELETE. Defaults to GET.",
                    enum = listOf("GET", "POST", "PUT", "DELETE"),
                    default = "GET"
                ),
                "headers" to ToolParameter(
                    type = "object",
                    description = "Key-value pairs of HTTP headers (optional)"
                ),
                "body" to ToolParameter(
                    type = "string",
                    description = "Request body for POST/PUT requests (optional)"
                )
            ),
            required = listOf("url")
        ),
        requiredPermissions = emptyList(),  // INTERNET is granted by default in manifest
        timeoutSeconds = 30
    )

    override suspend fun execute(parameters: Map<String, Any?>): ToolResult {
        val url = parameters["url"] as? String
            ?: return ToolResult.error("validation_error", "Parameter 'url' is required")
        val method = (parameters["method"] as? String ?: "GET").uppercase()
        @Suppress("UNCHECKED_CAST")
        val headers = parameters["headers"] as? Map<*, *>
        val body = parameters["body"] as? String

        val httpUrl = url.toHttpUrlOrNull()
            ?: return ToolResult.error("validation_error", "Invalid URL: $url")

        val requestBuilder = Request.Builder().url(httpUrl)

        headers?.forEach { (key, value) ->
            if (key is String && value is String) {
                requestBuilder.addHeader(key, value)
            }
        }

        val requestBody = body?.toRequestBody("application/json".toMediaTypeOrNull())
        when (method) {
            "GET" -> requestBuilder.get()
            "POST" -> requestBuilder.post(requestBody ?: "".toRequestBody(null))
            "PUT" -> requestBuilder.put(requestBody ?: "".toRequestBody(null))
            "DELETE" -> {
                if (requestBody != null) requestBuilder.delete(requestBody)
                else requestBuilder.delete()
            }
            else -> return ToolResult.error("validation_error", "Unsupported HTTP method: $method")
        }

        return try {
            val response = okHttpClient.newCall(requestBuilder.build()).execute()

            val responseBody = response.body?.let { responseBody ->
                val bytes = responseBody.bytes()
                if (bytes.size > MAX_RESPONSE_SIZE) {
                    val truncated = String(bytes, 0, MAX_RESPONSE_SIZE, Charsets.UTF_8)
                    "$truncated\n\n(Response truncated. Showing first ${MAX_RESPONSE_SIZE / 1024}KB of ${bytes.size / 1024}KB total.)"
                } else {
                    String(bytes, Charsets.UTF_8)
                }
            } ?: "(empty response body)"

            val responseHeaders = buildString {
                response.header("Content-Type")?.let { appendLine("Content-Type: $it") }
                response.header("Content-Length")?.let { appendLine("Content-Length: $it") }
            }.trimEnd()

            val result = buildString {
                appendLine("HTTP ${response.code} ${response.message}")
                if (responseHeaders.isNotEmpty()) appendLine(responseHeaders)
                appendLine()
                append(responseBody)
            }

            ToolResult.success(result)
        } catch (e: java.net.UnknownHostException) {
            ToolResult.error("network_error", "Cannot resolve host: ${httpUrl.host}")
        } catch (e: java.net.SocketTimeoutException) {
            ToolResult.error("timeout", "HTTP request timed out")
        } catch (e: java.net.ConnectException) {
            ToolResult.error("network_error", "Connection refused: $url")
        } catch (e: Exception) {
            ToolResult.error("execution_error", "HTTP request failed: ${e.message}")
        }
    }
}
