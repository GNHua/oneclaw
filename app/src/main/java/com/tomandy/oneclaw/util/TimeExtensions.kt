package com.tomandy.oneclaw.util

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * Utility functions for formatting timestamps in chat messages.
 *
 * Provides human-readable time formatting with intelligent selection
 * based on how recent the timestamp is.
 */

/**
 * Format timestamp to human-readable string.
 *
 * Returns different formats based on time elapsed:
 * - Less than 1 minute: "Just now"
 * - Less than 1 hour: "5 min ago"
 * - Same day: "2:34 PM"
 * - Within a week: "Mon 2:34 PM"
 * - Older: "Dec 15"
 *
 * @param timestamp Unix timestamp in milliseconds
 * @return Human-readable time string
 */
fun formatTimestamp(timestamp: Long): String {
    val now = System.currentTimeMillis()
    val diff = now - timestamp

    return when {
        diff < TimeUnit.MINUTES.toMillis(1) -> "Just now"
        diff < TimeUnit.HOURS.toMillis(1) -> {
            val minutes = TimeUnit.MILLISECONDS.toMinutes(diff)
            "$minutes min ago"
        }
        diff < TimeUnit.DAYS.toMillis(1) -> {
            // Same day: show time
            SimpleDateFormat("h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        diff < TimeUnit.DAYS.toMillis(7) -> {
            // Within week: show day and time
            SimpleDateFormat("EEE h:mm a", Locale.getDefault()).format(Date(timestamp))
        }
        else -> {
            // Older: show date
            SimpleDateFormat("MMM d", Locale.getDefault()).format(Date(timestamp))
        }
    }
}

/**
 * Format timestamp to full date/time string.
 *
 * Returns a complete timestamp with date and time in format:
 * "Dec 15, 2024 at 2:34 PM"
 *
 * Useful for detailed views or tooltips.
 *
 * @param timestamp Unix timestamp in milliseconds
 * @return Full date and time string
 */
fun formatFullTimestamp(timestamp: Long): String {
    return SimpleDateFormat("MMM d, yyyy 'at' h:mm a", Locale.getDefault())
        .format(Date(timestamp))
}
