package com.oneclaw.shadow.core.util

fun extractDomain(url: String): String {
    return try {
        java.net.URI(url).host?.removePrefix("www.") ?: url
    } catch (_: Exception) {
        url
    }
}
