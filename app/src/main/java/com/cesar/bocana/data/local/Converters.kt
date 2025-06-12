package com.cesar.bocana.data.local

import androidx.room.TypeConverter
import com.cesar.bocana.data.model.MovementType
import java.util.Date

class Converters {
    @TypeConverter
    fun fromTimestamp(value: Long?): Date? {
        return value?.let { Date(it) }
    }

    @TypeConverter
    fun dateToTimestamp(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun fromStringToMovementType(value: String?): MovementType? {
        return try {
            value?.let { enumValueOf<MovementType>(it) }
        } catch (e: IllegalArgumentException) {
            null // Devuelve nulo si el String no coincide con ningún enum
        }
    }

    @TypeConverter
    fun movementTypeToString(type: MovementType?): String? {
        return type?.name
    }
}