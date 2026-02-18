package com.tomandy.oneclaw.web

data class SearchResult(
    val title: String,
    val url: String,
    val snippet: String
)

interface WebSearchProvider {
    suspend fun search(query: String, maxResults: Int): List<SearchResult>
}
