package com.tomandy.palmclaw.web

import com.tomandy.palmclaw.engine.PluginMetadata
import com.tomandy.palmclaw.engine.ToolDefinition
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

object WebPluginMetadata {

    fun get(): PluginMetadata {
        return PluginMetadata(
            id = "web",
            name = "Web Search & Fetch",
            version = "1.0.0",
            description = "Search the internet and fetch web page content",
            author = "PalmClaw Team",
            entryPoint = "WebPlugin",
            tools = listOf(
                webSearchTool(),
                webFetchTool()
            ),
            category = "web"
        )
    }

    private fun webSearchTool() = ToolDefinition(
        name = "web_search",
        description = """Search the internet for information.
            |
            |Returns a list of search results with titles, URLs, and snippets.
            |Use this to find up-to-date information, answers to questions, or discover relevant web pages.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("query") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The search query"))
                }
                putJsonObject("max_results") {
                    put("type", JsonPrimitive("integer"))
                    put("description", JsonPrimitive("Maximum number of results to return (1-10, default 5)"))
                    put("default", JsonPrimitive(5))
                    put("minimum", JsonPrimitive(1))
                    put("maximum", JsonPrimitive(10))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("query"))
            }
        }
    )

    private fun webFetchTool() = ToolDefinition(
        name = "web_fetch",
        description = """Fetch a web page and extract its text content.
            |
            |Downloads the page, strips HTML boilerplate (scripts, styles, navigation, etc.),
            |and returns clean readable text. Automatically detects the main content area.
            |
            |Use an optional CSS selector to extract a specific section of the page.
            |Content is truncated at 15,000 characters.
        """.trimMargin(),
        parameters = buildJsonObject {
            put("type", JsonPrimitive("object"))
            putJsonObject("properties") {
                putJsonObject("url") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("The URL to fetch"))
                }
                putJsonObject("selector") {
                    put("type", JsonPrimitive("string"))
                    put("description", JsonPrimitive("Optional CSS selector to extract a specific section (e.g., '.article-body', '#content')"))
                }
            }
            putJsonArray("required") {
                add(JsonPrimitive("url"))
            }
        }
    )
}
