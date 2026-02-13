package com.tomandy.palmclaw.engine

import com.dokar.quickjs.QuickJs
import com.dokar.quickjs.evaluate
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * JavaScript plugin implementation using QuickJS.
 *
 * Bridges the Plugin interface to JavaScript execution:
 * - onLoad: Creates QuickJS runtime and evaluates the plugin script
 * - execute: Calls the JS `execute(toolName, args)` function
 * - onUnload: Closes the QuickJS runtime
 */
class JsPlugin(
    private val scriptSource: String,
    private val metadata: PluginMetadata
) : Plugin {
    private var quickJs: QuickJs? = null

    override suspend fun onLoad(context: PluginContext) {
        quickJs = QuickJs.create(Dispatchers.Default)
        quickJs!!.evaluate<Any?>(scriptSource)
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        val js = quickJs ?: return ToolResult.Failure("Plugin not loaded")

        return try {
            val argsJson = arguments.toString()
            val resultJson = js.evaluate<String>(
                "JSON.stringify(execute('$toolName', $argsJson))"
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
}
