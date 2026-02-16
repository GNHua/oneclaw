package com.tomandy.palmclaw.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File

/**
 * JavaScript plugin implementation using QuickJS.
 *
 * Bridges the Plugin interface to JavaScript execution:
 * - onLoad: Creates QuickJS runtime, injects host API bindings, evaluates the plugin script
 * - execute: Calls the JS `execute(toolName, args)` function
 * - onUnload: Closes the QuickJS runtime
 *
 * Host bindings exposed to JavaScript plugins under the `palmclaw` namespace:
 * - palmclaw.fs: Filesystem operations (shared workspace directory)
 * - palmclaw.http: HTTP client (get/post/put/patch/delete/request/fetch)
 * - palmclaw.credentials: Encrypted credential storage (async)
 * - palmclaw.notifications: Android notifications
 * - palmclaw.env: Plugin metadata (pluginId, pluginName, pluginVersion)
 * - palmclaw.log: Logging
 */
class JsPlugin(
    private val scriptSource: String,
    private val metadata: PluginMetadata
) : Plugin {
    private var quickJs: QuickJs? = null

    override suspend fun onLoad(context: PluginContext) {
        quickJs = QuickJs.create(Dispatchers.Default)
        val js = quickJs!!

        val workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }

        injectHostBindings(js, context, workspaceRoot)

        js.evaluate<Any?>(scriptSource)
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        val js = quickJs ?: return ToolResult.Failure("Plugin not loaded")

        return try {
            val argsJson = arguments.toString()
            val resultJson = js.evaluate<String>(
                "(async () => JSON.stringify(await execute('$toolName', $argsJson)))()"
            ) ?: return ToolResult.Failure("Plugin returned null")

            val resultObj = Json.parseToJsonElement(resultJson).jsonObject
            val error = resultObj["error"]?.jsonPrimitive?.content
            if (error != null) {
                ToolResult.Failure(error)
            } else {
                val output = resultObj["output"]?.jsonPrimitive?.content ?: resultJson
                ToolResult.Success(output)
            }
        } catch (e: Exception) {
            ToolResult.Failure("JS execution error: ${e.message}", e)
        }
    }

    override suspend fun onUnload() {
        quickJs?.close()
        quickJs = null
    }

    private fun injectHostBindings(js: QuickJs, context: PluginContext, workspaceRoot: File) {
        js.define("palmclaw") {
            // -- Filesystem (shared workspace) --
            define("fs") {
                function<String, String>("readFile") { path ->
                    val file = resolveSafePath(workspaceRoot, path)
                    if (!file.exists()) error("File not found: $path")
                    if (!file.isFile) error("Not a file: $path")
                    file.readText()
                }

                function("writeFile") { args ->
                    val path = args[0] as String
                    val content = args[1] as String
                    val file = resolveSafePath(workspaceRoot, path)
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    content.toByteArray().size
                }

                function<String, String>("listFiles") { path ->
                    val dir = if (path.isEmpty()) workspaceRoot
                    else resolveSafePath(workspaceRoot, path)
                    if (!dir.exists()) error("Directory not found: ${path.ifEmpty { "." }}")
                    if (!dir.isDirectory) error("Not a directory: $path")
                    val children = dir.listFiles()
                        ?.sortedBy { it.name.lowercase() }
                        ?: emptyList()
                    children.joinToString("\n") { child ->
                        if (child.isDirectory) "${child.name}/" else child.name
                    }
                }

                function<String, Boolean>("exists") { path ->
                    val file = resolveSafePath(workspaceRoot, path)
                    file.exists()
                }

                function("editFile") { args ->
                    val path = args[0] as String
                    val oldText = args[1] as String
                    val newText = args[2] as String
                    val file = resolveSafePath(workspaceRoot, path)
                    if (!file.exists()) error("File not found: $path")
                    val content = file.readText()
                    val index = content.indexOf(oldText)
                    if (index == -1) error("Text not found in file")
                    val secondIndex = content.indexOf(oldText, index + oldText.length)
                    if (secondIndex != -1) {
                        error("Multiple matches found. Provide more context to make the match unique.")
                    }
                    val newContent = content.substring(0, index) +
                        newText + content.substring(index + oldText.length)
                    file.writeText(newContent)
                    "ok"
                }

                function("appendFile") { args ->
                    val path = args[0] as String
                    val content = args[1] as String
                    val file = resolveSafePath(workspaceRoot, path)
                    file.parentFile?.mkdirs()
                    file.appendText(content)
                    content.toByteArray().size
                }

                function<String, Boolean>("deleteFile") { path ->
                    val file = resolveSafePath(workspaceRoot, path)
                    if (!file.exists()) error("File not found: $path")
                    file.delete()
                }

                function<String, String>("readDir") { path ->
                    val dir = if (path.isEmpty()) workspaceRoot
                    else resolveSafePath(workspaceRoot, path)
                    if (!dir.exists()) error("Directory not found: ${path.ifEmpty { "." }}")
                    if (!dir.isDirectory) error("Not a directory: $path")
                    val entries = mutableListOf<String>()
                    dir.walkTopDown().forEach { file ->
                        if (file == dir) return@forEach
                        val relative = file.relativeTo(dir).path
                        entries.add(
                            if (file.isDirectory) "$relative/" else relative
                        )
                    }
                    entries.sorted().joinToString("\n")
                }
            }

            // -- HTTP --
            define("http") {
                function<String, String>("get") { url ->
                    val request = Request.Builder().url(url).build()
                    context.httpClient.newCall(request).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function("post") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    val requestBody = body.toRequestBody(contentType.toMediaType())
                    val request = Request.Builder().url(url).post(requestBody).build()
                    context.httpClient.newCall(request).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function("put") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    val requestBody = body.toRequestBody(contentType.toMediaType())
                    val request = Request.Builder().url(url).put(requestBody).build()
                    context.httpClient.newCall(request).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function("patch") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    val requestBody = body.toRequestBody(contentType.toMediaType())
                    val request = Request.Builder().url(url).patch(requestBody).build()
                    context.httpClient.newCall(request).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function<String, String>("delete") { url ->
                    val request = Request.Builder().url(url).delete().build()
                    context.httpClient.newCall(request).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function("request") { args ->
                    val method = (args[0] as String).uppercase()
                    val url = args[1] as String
                    val body = args.getOrNull(2) as? String
                    val contentType = (args.getOrNull(3) as? String) ?: "application/json"
                    val headers = args.getOrNull(4)
                    val builder = Request.Builder().url(url)
                    if (body != null) {
                        builder.method(method, body.toRequestBody(contentType.toMediaType()))
                    } else {
                        builder.method(method, null)
                    }
                    @Suppress("UNCHECKED_CAST")
                    if (headers is Map<*, *>) {
                        (headers as Map<String, Any?>).forEach { (k, v) ->
                            builder.addHeader(k, v.toString())
                        }
                    }
                    context.httpClient.newCall(builder.build()).execute().use {
                        it.body?.string() ?: ""
                    }
                }

                function("fetch") { args ->
                    val method = (args[0] as String).uppercase()
                    val url = args[1] as String
                    val body = args.getOrNull(2) as? String
                    val contentType = (args.getOrNull(3) as? String) ?: "application/json"
                    val headers = args.getOrNull(4)
                    val builder = Request.Builder().url(url)
                    if (body != null) {
                        builder.method(method, body.toRequestBody(contentType.toMediaType()))
                    } else {
                        builder.method(method, null)
                    }
                    @Suppress("UNCHECKED_CAST")
                    if (headers is Map<*, *>) {
                        (headers as Map<String, Any?>).forEach { (k, v) ->
                            builder.addHeader(k, v.toString())
                        }
                    }
                    context.httpClient.newCall(builder.build()).execute().use { resp ->
                        val respHeaders = buildMap {
                            resp.headers.forEach { (name, value) -> put(name, value) }
                        }
                        val headersJson = respHeaders.entries.joinToString(",") { (k, v) ->
                            "\"${k.replace("\"", "\\\"")}\":" +
                                "\"${v.replace("\"", "\\\"")}\""
                        }
                        val respBody = resp.body?.string() ?: ""
                        val escapedBody = respBody
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\"")
                            .replace("\n", "\\n")
                            .replace("\r", "\\r")
                            .replace("\t", "\\t")
                        "{\"status\":${resp.code}," +
                            "\"headers\":{$headersJson}," +
                            "\"body\":\"$escapedBody\"}"
                    }
                }
            }

            // -- Credentials (async due to suspend functions) --
            define("credentials") {
                asyncFunction<String, String?>("get") { key ->
                    context.getCredential(key)
                }

                asyncFunction("save") { args ->
                    val key = args[0] as String
                    val value = args[1] as String
                    context.saveCredential(key, value)
                }

                asyncFunction<String, Unit>("delete") { key ->
                    context.deleteCredential(key)
                }
            }

            // -- Notifications --
            define("notifications") {
                function("show") { args ->
                    val title = args[0] as String
                    val message = args[1] as String
                    context.showNotification(title, message)
                    "ok"
                }
            }

            // -- Plugin metadata --
            define("env") {
                property<String>("pluginId") { getter { metadata.id } }
                property<String>("pluginName") { getter { metadata.name } }
                property<String>("pluginVersion") { getter { metadata.version } }
            }

            // -- Logging --
            function("log") { args ->
                context.log(
                    LogLevel.INFO,
                    args.joinToString(" ") { it?.toString() ?: "null" }
                )
            }
        }
    }

    companion object {
        /**
         * Resolve a relative path within the workspace root, preventing traversal attacks.
         */
        private fun resolveSafePath(root: File, relativePath: String): File {
            if (relativePath.startsWith("/")) {
                throw SecurityException("Absolute paths not allowed: $relativePath")
            }
            val resolved = File(root, relativePath).canonicalFile
            if (!resolved.path.startsWith(root.canonicalPath)) {
                throw SecurityException("Path traversal not allowed: $relativePath")
            }
            return resolved
        }
    }
}
