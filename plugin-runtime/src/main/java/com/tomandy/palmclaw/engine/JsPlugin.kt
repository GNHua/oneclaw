package com.tomandy.palmclaw.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.binding.define
import com.dokar.quickjs.binding.function
import com.dokar.quickjs.binding.asyncFunction
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.Base64

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
 * - palmclaw.log: Logging (info/error/debug/warn)
 */
class JsPlugin(
    private val scriptSource: String,
    private val metadata: PluginMetadata
) : Plugin {
    private var quickJs: QuickJs? = null
    private lateinit var workspaceRoot: File

    override suspend fun onLoad(context: PluginContext) {
        quickJs = QuickJs.create(Dispatchers.Default)
        val js = quickJs!!

        workspaceRoot = File(
            context.getApplicationContext().filesDir, "workspace"
        ).also { it.mkdirs() }

        injectHostBindings(js, context, workspaceRoot)

        js.evaluate<Any?>(scriptSource)
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        val js = quickJs ?: return ToolResult.Failure("Plugin not loaded")

        return try {
            val argsJson = arguments.toString()
            // Use Promise.resolve() to handle both sync and async execute() functions.
            // The .then() stores the JSON-stringified result in a global variable.
            // evaluate() internally calls awaitAsyncJobs(), so by the time it returns,
            // the Promise chain has resolved and __pcResult holds the final value.
            js.evaluate<Any?>(
                "Promise.resolve(execute('$toolName', $argsJson))" +
                    ".then(function(r) { globalThis.__pcResult = JSON.stringify(r); })"
            )
            val resultJson = js.evaluate<String>("globalThis.__pcResult")

            val resultObj = Json.parseToJsonElement(resultJson).jsonObject
            val error = resultObj["error"]?.jsonPrimitive?.content
            if (error != null) {
                ToolResult.Failure(error)
            } else {
                val output = resultObj["output"]?.jsonPrimitive?.content ?: resultJson
                val imagePaths = resultObj["imagePaths"]?.jsonArray?.mapNotNull { elem ->
                    val relativePath = elem.jsonPrimitive.content
                    val resolved = File(workspaceRoot, relativePath).canonicalFile
                    if (resolved.path.startsWith(workspaceRoot.canonicalPath) && resolved.exists()) {
                        resolved.absolutePath
                    } else null
                } ?: emptyList()
                ToolResult.Success(output, imagePaths = imagePaths)
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

                function<String, String>("readFileBase64") { path ->
                    val file = resolveSafePath(workspaceRoot, path)
                    if (!file.exists()) error("File not found: $path")
                    if (!file.isFile) error("Not a file: $path")
                    Base64.getEncoder().encodeToString(file.readBytes())
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

            // -- HTTP (async to avoid blocking QuickJS dispatcher) --
            define("http") {
                asyncFunction<String, String>("get") { url ->
                    executeHttp(context) {
                        Request.Builder().url(url).build()
                    }
                }

                asyncFunction("post") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    executeHttp(context) {
                        Request.Builder().url(url)
                            .post(body.toRequestBody(contentType.toMediaType()))
                            .build()
                    }
                }

                asyncFunction("put") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    executeHttp(context) {
                        Request.Builder().url(url)
                            .put(body.toRequestBody(contentType.toMediaType()))
                            .build()
                    }
                }

                asyncFunction("patch") { args ->
                    val url = args[0] as String
                    val body = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/json"
                    executeHttp(context) {
                        Request.Builder().url(url)
                            .patch(body.toRequestBody(contentType.toMediaType()))
                            .build()
                    }
                }

                asyncFunction<String, String>("delete") { url ->
                    executeHttp(context) {
                        Request.Builder().url(url).delete().build()
                    }
                }

                asyncFunction("request") { args ->
                    val method = (args[0] as String).uppercase()
                    val url = args[1] as String
                    val body = args.getOrNull(2) as? String
                    val contentType = (args.getOrNull(3) as? String) ?: "application/json"
                    val headers = args.getOrNull(4)
                    executeHttp(context) {
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
                        builder.build()
                    }
                }

                asyncFunction("fetch") { args ->
                    val method = (args[0] as String).uppercase()
                    val url = args[1] as String
                    val body = args.getOrNull(2) as? String
                    val contentType = (args.getOrNull(3) as? String) ?: "application/json"
                    val headers = args.getOrNull(4)
                    withContext(Dispatchers.IO) {
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

                asyncFunction("downloadToFile") { args ->
                    val url = args[0] as String
                    val destPath = args[1] as String
                    val headers = args.getOrNull(2)
                    withContext(Dispatchers.IO) {
                        val builder = Request.Builder().url(url)
                        @Suppress("UNCHECKED_CAST")
                        if (headers is Map<*, *>) {
                            (headers as Map<String, Any?>).forEach { (k, v) ->
                                builder.addHeader(k, v.toString())
                            }
                        }
                        context.httpClient.newCall(builder.build()).execute().use { resp ->
                            if (!resp.isSuccessful) {
                                error("Download failed (HTTP ${resp.code})")
                            }
                            val file = resolveSafePath(workspaceRoot, destPath)
                            file.parentFile?.mkdirs()
                            val bytes = resp.body?.bytes() ?: ByteArray(0)
                            file.writeBytes(bytes)
                            val ct = resp.header("Content-Type") ?: "application/octet-stream"
                            "{\"size\":${bytes.size},\"path\":\"$destPath\",\"contentType\":\"$ct\"}"
                        }
                    }
                }

                asyncFunction("uploadFile") { args ->
                    val url = args[0] as String
                    val filePath = args[1] as String
                    val contentType = (args.getOrNull(2) as? String) ?: "application/octet-stream"
                    val headers = args.getOrNull(3)
                    withContext(Dispatchers.IO) {
                        val file = resolveSafePath(workspaceRoot, filePath)
                        if (!file.exists()) error("File not found: $filePath")
                        val builder = Request.Builder().url(url)
                            .post(file.asRequestBody(contentType.toMediaType()))
                        @Suppress("UNCHECKED_CAST")
                        if (headers is Map<*, *>) {
                            (headers as Map<String, Any?>).forEach { (k, v) ->
                                builder.addHeader(k, v.toString())
                            }
                        }
                        context.httpClient.newCall(builder.build()).execute().use { resp ->
                            resp.body?.string() ?: ""
                        }
                    }
                }

                asyncFunction("uploadMultipart") { args ->
                    val url = args[0] as String
                    val parts = args[1]
                    val headers = args.getOrNull(2)
                    withContext(Dispatchers.IO) {
                        val multipartBuilder = MultipartBody.Builder()
                            .setType(MultipartBody.FORM)
                        @Suppress("UNCHECKED_CAST")
                        if (parts is List<*>) {
                            parts.forEach { partRaw ->
                                val part = partRaw as Map<String, Any?>
                                val name = part["name"] as String
                                val partContentType = (part["contentType"] as? String) ?: "application/octet-stream"
                                val filePath = part["filePath"] as? String
                                val filename = part["filename"] as? String
                                val body = part["body"] as? String
                                if (filePath != null) {
                                    val file = resolveSafePath(workspaceRoot, filePath)
                                    if (!file.exists()) error("File not found: $filePath")
                                    multipartBuilder.addFormDataPart(
                                        name,
                                        filename ?: file.name,
                                        file.asRequestBody(partContentType.toMediaType())
                                    )
                                } else if (body != null) {
                                    multipartBuilder.addFormDataPart(
                                        name,
                                        filename,
                                        body.toRequestBody(partContentType.toMediaType())
                                    )
                                }
                            }
                        }
                        val reqBuilder = Request.Builder().url(url)
                            .post(multipartBuilder.build())
                        @Suppress("UNCHECKED_CAST")
                        if (headers is Map<*, *>) {
                            (headers as Map<String, Any?>).forEach { (k, v) ->
                                reqBuilder.addHeader(k, v.toString())
                            }
                        }
                        context.httpClient.newCall(reqBuilder.build()).execute().use { resp ->
                            resp.body?.string() ?: ""
                        }
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

                asyncFunction<String, String?>("getProviderKey") { provider ->
                    context.getProviderCredential(provider)
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

            // -- Google OAuth (optional, only if provider available) --
            val googleAuth = context.googleAuthProvider
            if (googleAuth != null) {
                define("google") {
                    asyncFunction<String?>("getAccessToken") {
                        googleAuth.getAccessToken()
                    }

                    asyncFunction<Boolean>("isSignedIn") {
                        googleAuth.isSignedIn()
                    }

                    asyncFunction<String?>("getAccountEmail") {
                        googleAuth.getAccountEmail()
                    }
                }
            }

            // -- Logging --
            define("log") {
                function("info") { args ->
                    context.log(LogLevel.INFO, formatLog(args))
                }

                function("error") { args ->
                    context.log(LogLevel.ERROR, formatLog(args))
                }

                function("debug") { args ->
                    context.log(LogLevel.DEBUG, formatLog(args))
                }

                function("warn") { args ->
                    context.log(LogLevel.WARN, formatLog(args))
                }
            }
        }
    }

    companion object {
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

        private suspend fun executeHttp(
            context: PluginContext,
            buildRequest: () -> Request
        ): String = withContext(Dispatchers.IO) {
            context.httpClient.newCall(buildRequest()).execute().use {
                it.body?.string() ?: ""
            }
        }

        private fun formatLog(args: Array<Any?>): String =
            args.joinToString(" ") { it?.toString() ?: "null" }
    }
}
