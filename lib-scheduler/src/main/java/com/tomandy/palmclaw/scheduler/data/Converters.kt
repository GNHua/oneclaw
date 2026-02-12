package com.tomandy.palmclaw.scheduler.data

import androidx.room.TypeConverter

/**
 * Room type converters for enums
 */
class Converters {

    @TypeConverter
    fun fromScheduleType(value: ScheduleType): String {
        return value.name
    }

    @TypeConverter
    fun toScheduleType(value: String): ScheduleType {
        return ScheduleType.valueOf(value)
    }

    @TypeConverter
    fun fromExecutionStatus(value: ExecutionStatus): String {
        return value.name
    }

    @TypeConverter
    fun toExecutionStatus(value: String): ExecutionStatus {
        return ExecutionStatus.valueOf(value)
    }
}
