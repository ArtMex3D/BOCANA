package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.FifoLotSnapshot
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.tasks.await
import java.util.Date

class PredictiveV3FirestoreDataSource(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val IN_LIMIT = 30
    }

    suspend fun loadProducts(): List<Product> {
        return firestore.collection("products")
            .get().await()
            .documents
            .mapNotNull { doc -> doc.toObject(Product::class.java)?.copy(id = doc.id) }
            .filter { it.isActive }
    }

    suspend fun loadActiveMatrizLots(productIds: Set<String>): Map<String, List<FifoLotSnapshot>> {
        if (productIds.isEmpty()) return emptyMap()
        val snapshot = firestore.collection("inventoryLots")
            .whereEqualTo("location", Location.MATRIZ)
            .whereEqualTo("isDepleted", false)
            .get().await()

        return snapshot.documents
            .mapNotNull { doc -> doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
            .asSequence()
            .filter { productIds.contains(it.productId) && it.currentQuantity > 0.01 }
            .map { lot ->
                FifoLotSnapshot(
                    lotId = lot.id,
                    productId = lot.productId,
                    currentKg = lot.currentQuantity,
                    receivedAt = lot.receivedAt,
                    originalReceivedAt = lot.originalReceivedAt,
                    unitName = lot.unidadDeEmpaque,
                    kgPerUnit = lot.pesoPorUnidad
                )
            }
            .groupBy { it.productId }
    }

    suspend fun loadCheckpointValues(
        productIds: Set<String>,
        weeks: List<PredictiveV3Time.WeekRef>
    ): Map<String, Double> {
        if (productIds.isEmpty() || weeks.isEmpty()) return emptyMap()
        val ids = LinkedHashSet<String>()
        productIds.forEach { productId ->
            weeks.forEach { week -> ids += week.checkpointId(productId) }
        }

        val result = mutableMapOf<String, Double>()
        ids.toList().chunked(IN_LIMIT).forEach { chunk ->
            val snapshot = firestore.collection("consumption_history")
                .whereIn(FieldPath.documentId(), chunk)
                .get().await()
            snapshot.documents.forEach { doc ->
                doc.getDouble("consumedKg")?.takeIf { it >= 0.0 }?.let { result[doc.id] = it }
            }
        }
        return result
    }

    suspend fun loadConsumptionForRange(
        productIds: Set<String>,
        startInclusive: Date,
        endExclusive: Date
    ): Map<String, Double> {
        if (productIds.isEmpty()) return emptyMap()
        val snapshot = firestore.collection("stockMovements")
            .whereGreaterThanOrEqualTo("timestamp", startInclusive)
            .whereLessThan("timestamp", endExclusive)
            .get().await()

        val result = mutableMapOf<String, Double>()
        snapshot.documents.forEach { doc ->
            val movement = doc.toObject(StockMovement::class.java) ?: return@forEach
            if (!productIds.contains(movement.productId)) return@forEach
            val isConsumption = when (movement.type.name) {
                "SALIDA_CONSUMO", "SALIDA_CONSUMO_C04", "AJUSTE_STOCK_C04" -> true
                else -> false
            }
            if (isConsumption && movement.quantity > 0.0) {
                result[movement.productId] = (result[movement.productId] ?: 0.0) + movement.quantity
            }
        }
        return result
    }

    suspend fun loadRelevantMovements(productIds: Set<String>): List<StockMovement> {
        if (productIds.isEmpty()) return emptyList()
        val snapshot = firestore.collection("stockMovements")
            .whereIn("type", listOf("COMPRA", "TRASPASO_M_C04"))
            .get().await()

        return snapshot.documents
            .mapNotNull { doc -> doc.toObject(StockMovement::class.java)?.copy(id = doc.id) }
            .filter { productIds.contains(it.productId) }
            .sortedBy { it.timestamp?.time ?: Long.MIN_VALUE }
    }

    fun seriesForProduct(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpointValues: Map<String, Double>
    ): List<DemandPoint> {
        // No convertimos checkpoint faltante en cero: se conserva como dato ausente.
        return weeks.mapNotNull { week ->
            val kg = checkpointValues[week.checkpointId(productId)] ?: return@mapNotNull null
            DemandPoint(week.key, kg)
        }
    }

    fun currentWeekConsumed(
        productId: String,
        currentWeek: PredictiveV3Time.WeekRef,
        checkpointValues: Map<String, Double>
    ): Double = checkpointValues[currentWeek.checkpointId(productId)] ?: 0.0
}
