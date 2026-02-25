package com.tomandy.oneclaw.llm

interface LlmClient {
    suspend fun complete(
        messages: List<Message>,
        model: String = "gpt-4o-mini",
        temperature: Float = 0.7f,
        maxTokens: Int? = null,
        tools: List<Tool>? = null,
        enableWebSearch: Boolean = false
    ): Result<LlmResponse>

    fun setApiKey(apiKey: String)
    fun setBaseUrl(baseUrl: String)
}
