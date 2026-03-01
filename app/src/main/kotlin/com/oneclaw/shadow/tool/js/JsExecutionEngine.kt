package com.oneclaw.shadow.tool.js

import android.util.Log
import com.dokar.quickjs.quickJs
import com.oneclaw.shadow.core.model.ToolResult
import com.oneclaw.shadow.tool.js.bridge.ConsoleBridge
import com.oneclaw.shadow.tool.js.bridge.FetchBridge
import com.oneclaw.shadow.tool.js.bridge.FsBridge
import com.oneclaw.shadow.tool.js.bridge.LibraryBridge
import com.oneclaw.shadow.tool.js.bridge.TimeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import java.io.File

/**
 * Manages QuickJS runtime lifecycle and executes JS tool code.
 * Each execution gets a fresh QuickJS context for isolation.
 */
class JsExecutionEngine(
    private val okHttpClient: OkHttpClient,
    private val libraryBridge: LibraryBridge
) {
    companion object {
        private const val TAG = "JsExecutionEngine"
        private const val MAX_HEAP_SIZE = 16L * 1024 * 1024  // 16MB
        private const val MAX_STACK_SIZE = 1L * 1024 * 1024   // 1MB
    }

    /**
     * Execute a JS tool file with the given parameters.
     *
     * Creates a fresh QuickJS context, injects bridge functions,
     * loads the JS file, calls execute(params), and returns the result.
     *
     * @param functionName If non-null, calls the named function instead of execute().
     *                     Used for tool group dispatch. Defaults to null (single-tool mode).
     */
    suspend fun execute(
        jsFilePath: String,
        toolName: String,
        functionName: String? = null,
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs(jsFilePath, null, toolName, functionName, params, env)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "JS tool '$toolName' execution timed out after ${timeoutSeconds}s")
        } catch (e: CancellationException) {
            throw e  // propagate coroutine cancellation
        } catch (e: Exception) {
            Log.e(TAG, "JS tool '$toolName' execution failed", e)
            ToolResult.error("execution_error", "JS tool '$toolName' failed: ${e.message}")
        }
    }

    /**
     * Execute from pre-loaded source code (built-in JS tools from assets).
     *
     * @param functionName If non-null, calls the named function instead of execute().
     *                     Used for tool group dispatch. Defaults to null (single-tool mode).
     */
    suspend fun executeFromSource(
        jsSource: String,
        toolName: String,
        functionName: String? = null,
        params: Map<String, Any?>,
        env: Map<String, String>,
        timeoutSeconds: Int
    ): ToolResult {
        return try {
            withTimeout(timeoutSeconds * 1000L) {
                executeInQuickJs("", jsSource, toolName, functionName, params, env)
            }
        } catch (e: TimeoutCancellationException) {
            ToolResult.error("timeout", "JS tool '$toolName' execution timed out after ${timeoutSeconds}s")
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "JS tool '$toolName' execution failed", e)
            ToolResult.error("execution_error", "JS tool '$toolName' failed: ${e.message}")
        }
    }

    private suspend fun executeInQuickJs(
        jsFilePath: String,
        jsSource: String?,
        toolName: String,
        functionName: String?,
        params: Map<String, Any?>,
        env: Map<String, String>
    ): ToolResult {
        // Merge _env into params
        val paramsWithEnv = params.toMutableMap()
        paramsWithEnv["_env"] = env

        val result = quickJs {
            // Configure memory limits
            memoryLimit = MAX_HEAP_SIZE
            maxStackSize = MAX_STACK_SIZE

            // Inject bridge: console
            ConsoleBridge.inject(this, toolName)

            // Inject bridge: fs
            FsBridge.inject(this)

            // Inject bridge: fetch
            FetchBridge.inject(this, okHttpClient)

            // Inject bridge: _time
            TimeBridge.inject(this)

            // Inject bridge: lib()
            libraryBridge.inject(this)

            // Load JS source -- from file or from pre-loaded string (assets)
            val jsCode = jsSource ?: File(jsFilePath).readText()

            // Build the wrapper that calls the entry function and captures the result.
            // We serialize params as JSON, parse it in JS, call the function,
            // and serialize the result back.
            val paramsJson = anyToJsonElement(paramsWithEnv).toString()

            // Use the named function if provided, otherwise default to execute()
            val entryFunction = functionName ?: "execute"

            val wrapperCode = """
                ${FetchBridge.FETCH_WRAPPER_JS}
                ${libraryBridge.LIB_WRAPPER_JS}

                $jsCode

                const __params__ = JSON.parse(${quoteJsString(paramsJson)});
                const __result__ = await $entryFunction(__params__);
                if (__result__ === null || __result__ === undefined) {
                    "";
                } else if (typeof __result__ === "string") {
                    __result__;
                } else {
                    JSON.stringify(__result__);
                }
            """.trimIndent()

            evaluate<String>(wrapperCode)
        }

        return ToolResult.success(result ?: "")
    }

    /**
     * Convert any Kotlin value to JsonElement for serialization.
     */
    @Suppress("UNCHECKED_CAST")
    private fun anyToJsonElement(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        is String -> JsonPrimitive(value)
        is Map<*, *> -> buildJsonObject {
            (value as Map<String, Any?>).forEach { (k, v) -> put(k, anyToJsonElement(v)) }
        }
        is List<*> -> buildJsonArray {
            value.forEach { add(anyToJsonElement(it)) }
        }
        else -> JsonPrimitive(value.toString())
    }

    /**
     * Escape a string for safe embedding as a JS string literal.
     */
    private fun quoteJsString(s: String): String {
        val escaped = s
            .replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }
}
