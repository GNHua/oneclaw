package com.tomandy.oneclaw.scheduler.util

fun formatCronExpression(expression: String): String {
    val parts = expression.trim().split("\\s+".toRegex())
    if (parts.size != 5) return expression

    val (minute, hour, dayOfMonth, month, dayOfWeek) = parts

    return try {
        when {
            // Every minute: * * * * *
            minute == "*" && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" ->
                "Every minute"

            // Every N minutes: */N * * * *
            minute.startsWith("*/") && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
                val n = minute.removePrefix("*/").toInt()
                "Every $n minutes"
            }

            // Every hour: 0 * * * *
            minute == "0" && hour == "*" && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" ->
                "Every hour"

            // Every N hours: 0 */N * * *
            minute == "0" && hour.startsWith("*/") && dayOfMonth == "*" && month == "*" && dayOfWeek == "*" -> {
                val n = hour.removePrefix("*/").toInt()
                "Every $n hours"
            }

            // Specific time patterns
            minute.toIntOrNull() != null && hour.toIntOrNull() != null -> {
                val timeStr = formatTime(hour.toInt(), minute.toInt())
                when {
                    // Yearly: 0 9 1 6 *
                    dayOfMonth.toIntOrNull() != null && month.toIntOrNull() != null && dayOfWeek == "*" ->
                        "Yearly on ${formatMonth(month.toInt())} ${dayOfMonth.toInt()} at $timeStr"

                    // Monthly: 0 9 1 * *
                    dayOfMonth.toIntOrNull() != null && month == "*" && dayOfWeek == "*" ->
                        "Monthly on the ${ordinal(dayOfMonth.toInt())} at $timeStr"

                    // Weekly on specific days: 0 9 * * MON,WED,FRI
                    dayOfMonth == "*" && month == "*" && dayOfWeek != "*" -> {
                        val days = dayOfWeek.split(",").map { formatDayOfWeek(it.trim()) }
                        if (days.size == 1) {
                            "Every ${days[0]} at $timeStr"
                        } else {
                            "Every ${days.joinToString(", ")} at $timeStr"
                        }
                    }

                    // Daily: 0 9 * * *
                    dayOfMonth == "*" && month == "*" && dayOfWeek == "*" ->
                        "Daily at $timeStr"

                    else -> expression
                }
            }

            else -> expression
        }
    } catch (_: Exception) {
        expression
    }
}

fun formatIntervalMinutes(minutes: Int): String {
    return when {
        minutes < 60 -> "Every $minutes min"
        minutes == 60 -> "Every hour"
        minutes % 1440 == 0 -> {
            val days = minutes / 1440
            if (days == 1) "Every day"
            else if (days == 7) "Every week"
            else "Every $days days"
        }
        minutes % 60 == 0 -> {
            val hours = minutes / 60
            "Every $hours hours"
        }
        else -> "Every $minutes min"
    }
}

private fun formatTime(hour: Int, minute: Int): String {
    val amPm = if (hour < 12) "AM" else "PM"
    val displayHour = when {
        hour == 0 -> 12
        hour > 12 -> hour - 12
        else -> hour
    }
    return if (minute == 0) "$displayHour:00 $amPm"
    else "$displayHour:${minute.toString().padStart(2, '0')} $amPm"
}

private fun formatDayOfWeek(day: String): String {
    return when (day.uppercase()) {
        "MON", "1" -> "Monday"
        "TUE", "2" -> "Tuesday"
        "WED", "3" -> "Wednesday"
        "THU", "4" -> "Thursday"
        "FRI", "5" -> "Friday"
        "SAT", "6" -> "Saturday"
        "SUN", "0", "7" -> "Sunday"
        else -> day
    }
}

private fun formatMonth(month: Int): String {
    return when (month) {
        1 -> "Jan"; 2 -> "Feb"; 3 -> "Mar"; 4 -> "Apr"
        5 -> "May"; 6 -> "Jun"; 7 -> "Jul"; 8 -> "Aug"
        9 -> "Sep"; 10 -> "Oct"; 11 -> "Nov"; 12 -> "Dec"
        else -> month.toString()
    }
}

private fun ordinal(n: Int): String {
    val suffix = when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
    return "$n$suffix"
}
