package com.tomandy.oneclaw.llm

/**
 * Thrown when the LLM API rejects a request because the prompt exceeds
 * the model's context window. LLM client implementations detect
 * provider-specific error responses and wrap them in this exception so
 * the ReAct loop can attempt recovery (e.g. trimming older messages).
 */
class ContextOverflowException(
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)
