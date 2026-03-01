package com.oneclaw.shadow.core.util

import java.text.NumberFormat
import java.util.Locale

/**
 * Formats a number with comma separators: 1234567 -> "1,234,567"
 */
fun formatWithCommas(value: Long): String {
    return NumberFormat.getNumberInstance(Locale.US).format(value)
}

fun formatWithCommas(value: Int): String {
    return NumberFormat.getNumberInstance(Locale.US).format(value)
}

/**
 * Abbreviates large numbers: 1234 -> "1.2K", 1234567 -> "1.2M"
 * Numbers below 1000 are returned as-is with comma formatting.
 */
fun abbreviateNumber(value: Long): String {
    return when {
        value >= 1_000_000 -> String.format(Locale.US, "%.1fM", value / 1_000_000.0)
        value >= 1_000 -> String.format(Locale.US, "%.1fK", value / 1_000.0)
        else -> formatWithCommas(value)
    }
}
