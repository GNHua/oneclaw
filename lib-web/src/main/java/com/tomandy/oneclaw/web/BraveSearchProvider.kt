package com.tomandy.oneclaw.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request

class BraveSearchProvider(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.search.brave.com"
) : WebSearchProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val url = "$baseUrl/res/v1/web/search" +
                "?q=${java.net.URLEncoder.encode(query, "UTF-8")}" +
                "&count=$maxResults"

            val request = Request.Builder()
                .url(url)
                .header("X-Subscription-Token", apiKey)
                .header("Accept", "application/json")
                .get()
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response from Brave Search API")

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Brave Search API error ${response.code}: $responseBody"
                )
            }

            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val webResults = jsonResponse["web"]?.jsonObject
                ?.get("results")?.jsonArray
                ?: return@withContext emptyList()

            webResults.map { element ->
                val obj = element.jsonObject
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    snippet = obj["description"]?.jsonPrimitive?.content ?: ""
                )
            }
        }
}
