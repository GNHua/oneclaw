package com.tomandy.oneclaw.scheduler.data

/**
 * Type of schedule for a cronjob
 */
enum class ScheduleType {
    /**
     * Run once at a specific time
     */
    ONE_TIME,

    /**
     * Repeat on an interval or cron expression
     */
    RECURRING,

    /**
     * Trigger based on a condition (future enhancement)
     */
    CONDITIONAL
}
