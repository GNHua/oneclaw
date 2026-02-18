package com.tomandy.palmclaw.web

import com.tomandy.palmclaw.engine.PluginContext

object WebSearchProviderFactory {

    suspend fun create(context: PluginContext): WebSearchProvider {
        val provider = context.getCredential("search_provider") ?: "tavily"
        val apiKey = context.getCredential("api_key")
            ?: throw IllegalStateException(
                "Web search API key not configured. " +
                    "Set the 'web_api_key' credential in Settings."
            )

        return when (provider.lowercase()) {
            "brave" -> BraveSearchProvider(context.httpClient, apiKey)
            else -> TavilySearchProvider(context.httpClient, apiKey)
        }
    }
}
