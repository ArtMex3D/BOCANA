package com.cesar.bocana.util

import android.util.Log
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.StockMovement
import com.google.firebase.Timestamp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.toObject
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.Locale

object CalculationUtils {

    private const val TAG = "CalculationUtils"
    private const val CONSUMPTION_EPSILON = 0.1

    /**
     * Calcula el consumo total para una lista de productos dentro de un rango de fechas específico.
     * @param firestore Instancia de Firestore.
     * @param productIds Lista de IDs de los productos a consultar.
     * @param startDate Fecha de inicio del período.
     * @param endDate Fecha de fin del período.
     * @return Un mapa donde la clave es el ID del producto y el valor es su consumo total.
     */
    suspend fun getConsumptionForProducts(
        firestore: FirebaseFirestore,
        productIds: List<String>,
        startDate: Date,
        endDate: Date
    ): Map<String, Double> {
        if (productIds.isEmpty()) {
            return emptyMap()
        }

        val consumptionMap = mutableMapOf<String, Double>()
        productIds.forEach { consumptionMap[it] = 0.0 } // Inicializar mapa

        Log.d(TAG, "Calculando consumo para ${productIds.size} productos entre $startDate y $endDate")

        // Firestore solo permite 'in' en un campo. Haremos la consulta por fecha
        // y filtraremos los productos en la app. Esto es eficiente si el número de movimientos
        // no es astronómico. Para millones de movimientos, se requeriría otra estrategia.
        val querySnapshot = firestore.collection("stockMovements")
            .whereGreaterThanOrEqualTo("timestamp", Timestamp(startDate))
            .whereLessThanOrEqualTo("timestamp", Timestamp(endDate))
            .orderBy("timestamp")
            .get()
            .await()

        Log.d(TAG, "Movimientos encontrados en el rango de fechas: ${querySnapshot.size()}")

        for (document in querySnapshot.documents) {
            try {
                val movement = document.toObject<StockMovement>()
                if (movement != null && productIds.contains(movement.productId)) {
                    var quantityConsumed = 0.0
                    var isValidConsumptionType = false

                    when (movement.type) {
                        MovementType.SALIDA_CONSUMO,
                        MovementType.SALIDA_CONSUMO_C04,
                        MovementType.AJUSTE_STOCK_C04 -> {
                            quantityConsumed = movement.quantity
                            isValidConsumptionType = true
                        }
                        MovementType.AJUSTE_MANUAL -> {
                            // Un ajuste manual es consumo si es una salida (negativo) desde una ubicación de stock
                            if (movement.quantity < -CONSUMPTION_EPSILON &&
                                (movement.locationFrom == Location.MATRIZ || movement.locationFrom == Location.CONGELADOR_04)
                            ) {
                                quantityConsumed = kotlin.math.abs(movement.quantity)
                                isValidConsumptionType = true
                            }
                        }
                        else -> { /* No es un tipo de consumo relevante */ }
                    }

                    if (isValidConsumptionType && quantityConsumed > CONSUMPTION_EPSILON) {
                        val currentConsumption = consumptionMap.getOrDefault(movement.productId, 0.0)
                        consumptionMap[movement.productId] = currentConsumption + quantityConsumed
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error convirtiendo movimiento: ${document.id}", e)
            }
        }
        Log.d(TAG, "Cálculo de consumo finalizado: $consumptionMap")
        return consumptionMap
    }
}