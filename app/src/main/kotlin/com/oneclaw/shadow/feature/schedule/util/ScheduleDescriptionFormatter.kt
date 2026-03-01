package com.oneclaw.shadow.feature.schedule.util

import com.oneclaw.shadow.core.model.ScheduleType
import com.oneclaw.shadow.core.model.ScheduledTask
import java.time.DayOfWeek
import java.time.Instant
import java.time.ZoneId

object ScheduleDescriptionFormatter {
    fun format(task: ScheduledTask): String {
        val time = String.format("%02d:%02d", task.hour, task.minute)
        return when (task.scheduleType) {
            ScheduleType.DAILY -> "Daily at $time"
            ScheduleType.WEEKLY -> {
                val dayName = task.dayOfWeek?.let {
                    DayOfWeek.of(it).name.lowercase().replaceFirstChar { c -> c.uppercase() }
                } ?: "Monday"
                "Every $dayName at $time"
            }
            ScheduleType.ONE_TIME -> {
                val dateStr = task.dateMillis?.let {
                    Instant.ofEpochMilli(it)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                        .toString()
                } ?: "unknown date"
                "One-time on $dateStr at $time"
            }
        }
    }
}
