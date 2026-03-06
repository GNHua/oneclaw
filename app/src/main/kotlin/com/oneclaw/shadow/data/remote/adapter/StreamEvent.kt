package com.oneclaw.shadow.data.remote.adapter

import com.oneclaw.shadow.core.model.Citation

sealed class StreamEvent {
    data class TextDelta(val text: String) : StreamEvent()
    data class ThinkingDelta(val text: String) : StreamEvent()
    data class ToolCallStart(val toolCallId: String, val toolName: String) : StreamEvent()
    data class ToolCallDelta(val toolCallId: String, val argumentsDelta: String) : StreamEvent()
    data class ToolCallEnd(val toolCallId: String, val thoughtSignature: String? = null) : StreamEvent()
    data class Usage(val inputTokens: Int, val outputTokens: Int) : StreamEvent()
    data class Error(val message: String, val code: String?) : StreamEvent()
    data object Done : StreamEvent()

    /** Provider is performing a server-side web search. */
    data class WebSearchStart(val query: String?) : StreamEvent()

    /** Citations extracted from the provider's response. */
    data class Citations(val citations: List<Citation>) : StreamEvent()
}
