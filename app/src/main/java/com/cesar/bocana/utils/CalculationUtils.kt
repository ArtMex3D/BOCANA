package com.cesar.bocana.util

import android.util.Log
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.StockMovement
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import java.util.Calendar
import java.util.Date

object CalculationUtils {

    private const val TAG = "CalculationUtils"

    fun getCurrentWeekDates(): Pair<Date, Date> {
        val calendar = Calendar.getInstance()

        val currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        val daysToSubtract = when (currentDayOfWeek) {
            Calendar.TUESDAY -> 0
            Calendar.WEDNESDAY -> 1
            Calendar.THURSDAY -> 2
            Calendar.FRIDAY -> 3
            Calendar.SATURDAY -> 4
            Calendar.SUNDAY -> 5
            Calendar.MONDAY -> 6
            else -> 0
        }
        calendar.add(Calendar.DAY_OF_YEAR, -daysToSubtract)
        calendar.set(Calendar.HOUR_OF_DAY, 0)
        calendar.set(Calendar.MINUTE, 0)
        calendar.set(Calendar.SECOND, 0)
        calendar.set(Calendar.MILLISECOND, 0)
        val startDate = calendar.time

        calendar.add(Calendar.DAY_OF_YEAR, 6)
        calendar.set(Calendar.HOUR_OF_DAY, 23)
        calendar.set(Calendar.MINUTE, 59)
        calendar.set(Calendar.SECOND, 59)
        calendar.set(Calendar.MILLISECOND, 999)
        val endDate = calendar.time

        Log.d(TAG, "Rango Semana Actual: Inicio=$startDate, Fin=$endDate")
        return Pair(startDate, endDate)
    }

    fun calculateWeeklyConsumption(
        firestore: FirebaseFirestore,
        callback: (result: Map<String, Double>?, error: Exception?) -> Unit // <-- Cambiado a Double
    ) {
        val (startDate, endDate) = getCurrentWeekDates()
        // --- Cambiado a Map<String, Double> y valor por defecto 0.0 ---
        val consumptionMap = mutableMapOf<String, Double>()

        Log.d(TAG, "Calculando consumo entre $startDate y $endDate")

        firestore.collection("stockMovements")
            .whereGreaterThanOrEqualTo("timestamp", Timestamp(startDate))
            .whereLessThanOrEqualTo("timestamp", Timestamp(endDate))
            .orderBy("timestamp", Query.Direction.ASCENDING)
            .get()
            .addOnSuccessListener { querySnapshot ->
                Log.d(TAG, "Consulta de movimientos exitosa. Documentos: ${querySnapshot.size()}")
                for (document in querySnapshot.documents) {
                    val movement = document.toObject(StockMovement::class.java)
                    if (movement != null) {
                        // movement.quantity ahora es Double
                        if (movement.type == MovementType.SALIDA_CONSUMO || movement.type == MovementType.AJUSTE_STOCK_C04) {
                            // --- Lógica de suma ahora con Double ---
                            val currentProductConsumption = consumptionMap.getOrDefault(movement.productId, 0.0) // <-- Valor por defecto 0.0
                            consumptionMap[movement.productId] = currentProductConsumption + movement.quantity // <-- Suma Double + Double
                            Log.d(TAG, "Movimiento relevante: Prod=${movement.productName}, Tipo=${movement.type}, Cant=${movement.quantity}")
                        }
                    } else {
                        Log.w(TAG, "Error convirtiendo movimiento: ${document.id}")
                    }
                }
                Log.d(TAG, "Cálculo finalizado: $consumptionMap")
                callback(consumptionMap, null)
            }
            .addOnFailureListener { e ->
                Log.e(TAG, "Error al obtener movimientos para cálculo de consumo", e)
                callback(null, e)
            }
    }
}