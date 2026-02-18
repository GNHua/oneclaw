package com.tomandy.palmclaw.web

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

class TavilySearchProvider(
    private val httpClient: OkHttpClient,
    private val apiKey: String,
    private val baseUrl: String = "https://api.tavily.com"
) : WebSearchProvider {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun search(query: String, maxResults: Int): List<SearchResult> =
        withContext(Dispatchers.IO) {
            val body = buildJsonObject {
                put("query", query)
                put("max_results", maxResults)
                put("include_answer", false)
            }

            val request = Request.Builder()
                .url("$baseUrl/search")
                .header("Authorization", "Bearer $apiKey")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json".toMediaType()))
                .build()

            val response = httpClient.newCall(request).execute()
            val responseBody = response.body?.string()
                ?: throw IllegalStateException("Empty response from Tavily API")

            if (!response.isSuccessful) {
                throw IllegalStateException(
                    "Tavily API error ${response.code}: $responseBody"
                )
            }

            val jsonResponse = json.parseToJsonElement(responseBody).jsonObject
            val results = jsonResponse["results"]?.jsonArray ?: return@withContext emptyList()

            results.map { element ->
                val obj = element.jsonObject
                SearchResult(
                    title = obj["title"]?.jsonPrimitive?.content ?: "",
                    url = obj["url"]?.jsonPrimitive?.content ?: "",
                    snippet = obj["content"]?.jsonPrimitive?.content ?: ""
                )
            }
        }
}
