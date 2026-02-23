package com.tomandy.oneclaw.scheduler.util

import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Compute the next occurrence of a 5-field cron expression (minute hour day-of-month month day-of-week)
 * relative to [after] (defaults to now).  Returns epoch millis in the system default timezone.
 *
 * Supports:
 * - Literal values: 0, 10, 1, etc.
 * - Wildcards: *
 * - Lists: 1,15 or MON,WED,FRI
 * - Ranges: 1-5
 * - Steps: * /15 (every 15 min), 0-30/10
 *
 * Day-of-week: 0 or 7 = Sunday, 1 = Monday ... 6 = Saturday.
 * Also accepts three-letter abbreviations (MON, TUE, ...).
 */
fun nextCronOccurrence(
    expression: String,
    after: Long = System.currentTimeMillis()
): Long? {
    val parts = expression.trim().split("\\s+".toRegex())
    if (parts.size != 5) return null

    val minuteSet = parseField(parts[0], 0, 59) ?: return null
    val hourSet = parseField(parts[1], 0, 23) ?: return null
    val domSet = parseField(parts[2], 1, 31) ?: return null
    val monthSet = parseField(parts[3], 1, 12) ?: return null
    val dowSet = parseDowField(parts[4]) ?: return null

    val zone = ZoneId.systemDefault()
    var candidate = LocalDateTime.ofInstant(
        java.time.Instant.ofEpochMilli(after), zone
    ).plusMinutes(1).withSecond(0).withNano(0)

    // Search up to 2 years ahead to find a match
    val limit = candidate.plusYears(2)

    while (candidate.isBefore(limit)) {
        if (candidate.monthValue !in monthSet) {
            candidate = candidate.plusMonths(1).withDayOfMonth(1).withHour(0).withMinute(0)
            continue
        }
        if (candidate.dayOfMonth !in domSet) {
            candidate = candidate.plusDays(1).withHour(0).withMinute(0)
            continue
        }
        // java DayOfWeek: MONDAY=1 ... SUNDAY=7; cron uses 0=Sun,1=Mon,...,6=Sat
        val cronDow = candidate.dayOfWeek.value % 7 // Mon=1..Sat=6, Sun=0
        if (cronDow !in dowSet) {
            candidate = candidate.plusDays(1).withHour(0).withMinute(0)
            continue
        }
        if (candidate.hour !in hourSet) {
            candidate = candidate.plusHours(1).withMinute(0)
            continue
        }
        if (candidate.minute !in minuteSet) {
            candidate = candidate.plusMinutes(1)
            continue
        }
        return candidate.atZone(zone).toInstant().toEpochMilli()
    }

    return null // no match within 2 years
}

private fun parseField(field: String, min: Int, max: Int): Set<Int>? {
    val result = mutableSetOf<Int>()
    for (part in field.split(",")) {
        val stepSplit = part.split("/")
        val rangePart = stepSplit[0]
        val step = stepSplit.getOrNull(1)?.toIntOrNull() ?: 1
        if (step < 1) return null

        val (start, end) = if (rangePart == "*") {
            min to max
        } else if (rangePart.contains("-")) {
            val bounds = rangePart.split("-")
            val s = bounds[0].toIntOrNull() ?: return null
            val e = bounds[1].toIntOrNull() ?: return null
            s to e
        } else {
            val v = rangePart.toIntOrNull() ?: return null
            v to v
        }

        var i = start
        while (i <= end) {
            if (i in min..max) result.add(i)
            i += step
        }
    }
    return result.ifEmpty { null }
}

private val DOW_MAP = mapOf(
    "SUN" to 0, "MON" to 1, "TUE" to 2, "WED" to 3,
    "THU" to 4, "FRI" to 5, "SAT" to 6
)

private fun parseDowField(field: String): Set<Int>? {
    // Replace named days before parsing
    var replaced = field.uppercase()
    for ((name, num) in DOW_MAP) {
        replaced = replaced.replace(name, num.toString())
    }
    val result = parseField(replaced, 0, 7) ?: return null
    // Normalize: both 0 and 7 mean Sunday
    val normalized = result.toMutableSet()
    if (7 in normalized) {
        normalized.remove(7)
        normalized.add(0)
    }
    return normalized
}
