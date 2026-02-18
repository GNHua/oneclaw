package com.tomandy.palmclaw.web

import com.tomandy.palmclaw.engine.PluginContext

object WebSearchProviderFactory {

    suspend fun create(context: PluginContext): WebSearchProvider {
        val provider = context.getCredential("search_provider") ?: "tavily"
        val apiKey = context.getCredential("${provider}_api_key")
            ?: throw IllegalStateException(
                "API key for $provider not configured. " +
                    "Set it in Settings > Plugins > Web Search & Fetch."
            )

        return when (provider.lowercase()) {
            "brave" -> BraveSearchProvider(context.httpClient, apiKey)
            else -> TavilySearchProvider(context.httpClient, apiKey)
        }
    }
}
