package com.cesar.bocana.data.local

import androidx.room.TypeConverter
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.MovementType
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import java.util.Date

class Converters {
    private val gson = Gson() // Instancia de Gson para reutilizar

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
        return value?.let { enumValueOf<MovementType>(it) }
    }

    @TypeConverter
    fun movementTypeToString(type: MovementType?): String? {
        return type?.name
    }

    @TypeConverter
    fun fromStringToDevolucionStatus(value: String?): DevolucionStatus? {
        return value?.let { enumValueOf<DevolucionStatus>(it) }
    }

    @TypeConverter
    fun devolucionStatusToString(status: DevolucionStatus?): String? {
        return status?.name
    }

    @TypeConverter
    fun fromStringToList(value: String?): List<String>? {
        if (value == null) return null
        val listType = object : TypeToken<List<String>>() {}.type
        return gson.fromJson(value, listType)
    }

    @TypeConverter
    fun fromListToString(list: List<String>?): String? {
        return gson.toJson(list)
    }

    // --- CONVERSOR PARA EL MAPA (LA SOLUCIÓN AL ERROR) ---
    @TypeConverter
    fun fromMapToString(map: Map<String, Any>?): String? {
        return map?.let { gson.toJson(it) }
    }

    @TypeConverter
    fun fromStringToMap(json: String?): Map<String, Any>? {
        return json?.let {
            val type = object : TypeToken<Map<String, Any>>() {}.type
            gson.fromJson(json, type)
        }
    }
}
