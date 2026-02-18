package com.tomandy.palmclaw.web

import com.tomandy.palmclaw.engine.Plugin
import com.tomandy.palmclaw.engine.PluginContext
import com.tomandy.palmclaw.engine.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive

class WebPlugin : Plugin {

    private lateinit var context: PluginContext
    private lateinit var contentExtractor: ContentExtractor
    private var searchProvider: WebSearchProvider? = null

    override suspend fun onLoad(context: PluginContext) {
        this.context = context
        this.contentExtractor = ContentExtractor(context.httpClient)
    }

    override suspend fun execute(toolName: String, arguments: JsonObject): ToolResult {
        return when (toolName) {
            "web_search" -> webSearch(arguments)
            "web_fetch" -> webFetch(arguments)
            else -> ToolResult.Failure("Unknown tool: $toolName")
        }
    }

    override suspend fun onUnload() {
        // No cleanup needed
    }

    private suspend fun webSearch(arguments: JsonObject): ToolResult {
        return try {
            val query = arguments["query"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required parameter: query")

            val maxResults = arguments["max_results"]?.jsonPrimitive?.int ?: 5
            val clampedMax = maxResults.coerceIn(1, 10)

            val provider = getOrCreateSearchProvider()
            val results = provider.search(query, clampedMax)

            if (results.isEmpty()) {
                return ToolResult.Success(output = "No results found for: $query")
            }

            val formatted = results.mapIndexed { index, result ->
                "${index + 1}. ${result.title}\n   URL: ${result.url}\n   ${result.snippet}"
            }.joinToString("\n\n")

            ToolResult.Success(
                output = "Search results for \"$query\":\n\n$formatted"
            )
        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Web search failed: ${e.message}",
                exception = e
            )
        }
    }

    private suspend fun webFetch(arguments: JsonObject): ToolResult {
        return try {
            val url = arguments["url"]?.jsonPrimitive?.content
                ?: return ToolResult.Failure("Missing required parameter: url")

            val selector = arguments["selector"]?.jsonPrimitive?.content

            val content = contentExtractor.extract(url, selector)

            val output = buildString {
                appendLine("Title: ${content.title}")
                appendLine()
                append(content.text)
            }

            ToolResult.Success(output = output)
        } catch (e: Exception) {
            ToolResult.Failure(
                error = "Failed to fetch URL: ${e.message}",
                exception = e
            )
        }
    }

    private suspend fun getOrCreateSearchProvider(): WebSearchProvider {
        searchProvider?.let { return it }
        val provider = WebSearchProviderFactory.create(context)
        searchProvider = provider
        return provider
    }
}
