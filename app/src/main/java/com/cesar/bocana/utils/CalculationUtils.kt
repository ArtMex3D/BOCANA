package com.cesar.bocana.util

import android.util.Log
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.StockMovement
import com.google.firebase.Timestamp // Importar Timestamp de Firebase
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Calendar
import java.util.Date
import com.google.firebase.ktx.Firebase
import com.google.firebase.firestore.ktx.firestore

object CalculationUtils {

    private const val TAG = "CalculationUtils"

    // --- Función para obtener las fechas de inicio (Martes) y fin (Lunes) de la semana actual ---
    fun getCurrentWeekDates(): Pair<Date, Date> {
        val calendar = Calendar.getInstance() // Obtiene calendario con fecha/hora actual

        // 1. Encontrar el Martes de esta semana (o el anterior si hoy es Lunes/Domingo/Sábado...)
        // Calendar.SUNDAY = 1, MONDAY=2, TUESDAY=3, ... SATURDAY=7
        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK) // Domingo=1, Lunes=2...
        val daysToSubtract = when (currentDayOfWeek) {
            Calendar.TUESDAY -> 0 // Si hoy es Martes, no restamos
            Calendar.WEDNESDAY -> 1
            Calendar.THURSDAY -> 2
            Calendar.FRIDAY -> 3
            Calendar.SATURDAY -> 4
            Calendar.SUNDAY -> 5 // Si es Domingo, restar 5 para llegar al Martes anterior
            Calendar.MONDAY -> 6 // Si es Lunes, restar 6 para llegar al Martes anterior
            else -> 0
        }
        calendar.add(Calendar.DAY_OF_YEAR, -daysToSubtract)
        // Ahora 'calendar' está en el Martes. Poner hora a 00:00:00
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.time // Fecha de inicio (Martes 00:00:00)

        // 2. Encontrar el Lunes siguiente
        // Avanzamos 6 días desde el Martes para llegar al Lunes
        calendar.add(Calendar.DAY_OF_YEAR, 6)

        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endDate = calendar.time // Fecha de fin (Lunes 23:59:59)

        Log.d(TAG, "Rango Semana Actual: Inicio=$startDate, Fin=$endDate")
        return Pair(startDate, endDate)
    }


    // --- Función PRINCIPAL para calcular el consumo ---
    // Llama a un 'callback' cuando el cálculo termina (porque la consulta a Firestore es asíncrona)
    // Devuelve un mapa: ProductID -> Cantidad Consumida, o null si hay error
    fun calculateWeeklyConsumption(
        firestore: FirebaseFirestore,
        callback: (result: Map<String, Long>?, error: Exception?) -> Unit
    ) {
        val (startDate, endDate) = getCurrentWeekDates()
        val consumptionMap = mutableMapOf<String, Long>() // Mapa para guardar consumo por producto

        Log.d(TAG, "Calculando consumo entre $startDate y $endDate")

        firestore.collection("stockMovements")
            .whereGreaterThanOrEqualTo("timestamp", Timestamp(startDate)) // Filtrar por fecha inicio
            .whereLessThanOrEqualTo("timestamp", Timestamp(endDate))      // Filtrar por fecha fin
            // No podemos filtrar por TIPO directamente si usamos OR, así que traemos ambos y filtramos en código
            // .whereIn("type", listOf(MovementType.SALIDA_CONSUMO, MovementType.AJUSTE_STOCK_C04)) // Esto requeriría índice
            .orderBy("timestamp", Query.Direction.ASCENDING) // Ordenar es opcional aquí
            .get()
            .addOnSuccessListener { querySnapshot ->
                Log.d(TAG, "Consulta de movimientos exitosa. Documentos: ${querySnapshot.size()}")
                for (document in querySnapshot.documents) {
                    val movement = document.toObject(StockMovement::class.java) // O usar toObject<StockMovement>()
                    if (movement != null) {
                        // Filtrar por tipo en el código
                        if (movement.type == MovementType.SALIDA_CONSUMO || movement.type == MovementType.AJUSTE_STOCK_C04) {
                            val currentProductConsumption = consumptionMap.getOrDefault(movement.productId, 0L)
                            consumptionMap[movement.productId] = currentProductConsumption + movement.quantity
                            Log.d(TAG, "Movimiento relevante: Prod=${movement.productName}, Tipo=${movement.type}, Cant=${movement.quantity}")
                        }
                    } else {
                        Log.w(TAG, "Error convirtiendo movimiento: ${document.id}")
                    }
                }
                Log.d(TAG, "Cálculo finalizado: $consumptionMap")
                callback(consumptionMap, null) // Devuelve el mapa de resultados, sin error
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al obtener movimientos para cálculo de consumo", e)
                callback(null, e) // Devuelve null y el error
            }
    }
}