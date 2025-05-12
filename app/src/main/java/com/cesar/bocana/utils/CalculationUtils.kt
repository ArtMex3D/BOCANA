package com.cesar.bocana.util

import android.util.Log
import com.cesar.bocana.data.model.Location // Importar Location
import com.cesar.bocana.data.model.MovementType // Importar MovementType
import com.cesar.bocana.data.model.StockMovement
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CalculationUtils {

    private const val TAG = "CalculationUtils"
    private const val CONSUMPTION_EPSILON = 0.1 // Definir el mismo épsilon que en ProductListFragment

    fun getCurrentWeekDates(): Pair<Date, Date> {
        val calendar = Calendar.getInstance(Locale.getDefault())
        val endDateCalendar = calendar.clone() as Calendar
        val currentDayOfWeek = endDateCalendar.get(Calendar.DAY_OF_WEEK)

        val daysToAdjustEnd: Int = when (currentDayOfWeek) {
            Calendar.WEDNESDAY -> -1
            Calendar.THURSDAY -> -2
            Calendar.FRIDAY -> -3
            Calendar.SATURDAY -> -4
            Calendar.SUNDAY -> -5
            Calendar.MONDAY -> -6
            Calendar.TUESDAY -> 0
            else -> 0
        }

        endDateCalendar.add(Calendar.DAY_OF_YEAR, daysToAdjustEnd)
        endDateCalendar.set(Calendar.HOUR_OF_DAY, 23)
        endDateCalendar.set(Calendar.MINUTE, 59)
        endDateCalendar.set(Calendar.SECOND, 59)
        endDateCalendar.set(Calendar.MILLISECOND, 999)
        val endDate = endDateCalendar.time

        val startDateCalendar = endDateCalendar.clone() as Calendar
        startDateCalendar.add(Calendar.DAY_OF_YEAR, -6)
        startDateCalendar.set(Calendar.HOUR_OF_DAY, 0)
        startDateCalendar.set(Calendar.MINUTE, 0)
        startDateCalendar.set(Calendar.SECOND, 0)
        startDateCalendar.set(Calendar.MILLISECOND, 0)
        val startDate = startDateCalendar.time

        Log.d(TAG, "Rango Semana Consumo: Inicio=$startDate, Fin=$endDate (Miércoles a Martes)")
        return Pair(startDate, endDate)
    }

    fun calculateWeeklyConsumption(
        firestore: FirebaseFirestore,
        callback: (result: Map<String, Double>?, error: Exception?) -> Unit
    ) {
        val (startDate, endDate) = getCurrentWeekDates()
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
                        var quantityConsumed = 0.0
                        var isValidConsumptionType = false

                        when (movement.type) {
                            MovementType.SALIDA_CONSUMO,
                            MovementType.SALIDA_CONSUMO_C04, // Asegúrate que este enum exista
                            MovementType.AJUSTE_STOCK_C04 -> {
                                quantityConsumed = movement.quantity // Asume que quantity es positiva para salidas
                                isValidConsumptionType = true
                            }
                            MovementType.AJUSTE_MANUAL -> {
                                if (movement.quantity < -CONSUMPTION_EPSILON &&
                                    (movement.locationFrom == Location.MATRIZ || movement.locationFrom == Location.CONGELADOR_04)) {
                                    quantityConsumed = kotlin.math.abs(movement.quantity)
                                    isValidConsumptionType = true
                                }
                            }
                            else -> { /* No es un tipo de consumo relevante */ }
                        }

                        if (isValidConsumptionType && quantityConsumed > CONSUMPTION_EPSILON) {
                            val currentProductConsumption = consumptionMap.getOrDefault(movement.productId, 0.0)
                            consumptionMap[movement.productId] = currentProductConsumption + quantityConsumed
                            Log.d(TAG, "Movimiento relevante: Prod=${movement.productName}, Tipo=${movement.type}, CantConsumida=${String.format(Locale.US, "%.2f", quantityConsumed)}")
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
