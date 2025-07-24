package com.cesar.bocana.data.local

import androidx.room.TypeConverter
import com.cesar.bocana.data.model.MovementType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
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
            null
        }
    }

    @TypeConverter
    fun movementTypeToString(type: MovementType?): String? {
        return type?.name
    }

    @TypeConverter
    fun fromMapToString(map: Map<String, Any>?): String? {
        return map?.let { Gson().toJson(it) }
    }

    @TypeConverter
    fun fromStringToMap(json: String?): Map<String, Any>? {
        return json?.let {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            Gson().fromJson(json, type)
        }
    }

    // <<<--- INICIO DE LA CORRECCIÓN --- >>>
    @TypeConverter
    fun fromStringToList(value: String?): List<String>? {
        if (value == null) {
            return null
        }
        val listType = object : TypeToken<List<String>>() {}.type
        return Gson().fromJson(value, listType)
    }

    @TypeConverter
    fun fromListToString(list: List<String>?): String? {
        if (list == null) {
            return null
        }
        return Gson().toJson(list)
    }
    // <<<--- FIN DE LA CORRECCIÓN --- >>>
}