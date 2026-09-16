package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.FifoLotSnapshot
import com.cesar.bocana.predictive.v3.model.LotInsight
import com.cesar.bocana.predictive.v3.model.PackagingSignal
import com.cesar.bocana.predictive.v3.model.ReturnSignal
import com.cesar.bocana.util.StockQuantityPolicy
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.tasks.await
import java.util.Date

class PredictiveV3FirestoreDataSource(
    private val firestore: FirebaseFirestore
) {
    companion object {
        private const val IN_LIMIT = 30
        const val ACTIVE_EPSILON_KG = StockQuantityPolicy.MIN_USABLE_KG
    }

    suspend fun loadProducts(): List<Product> {
        return firestore.collection("products")
            .get().await()
            .documents
            .mapNotNull { doc -> doc.toObject(Product::class.java)?.copy(id = doc.id) }
            .filter { it.isActive }
    }

    /**
     * Lee lotes abiertos de Matriz y C04 en una sola consulta.
     * Los residuos < 0.10 kg se conservan en el resultado para poder reportarlos,
     * pero NO se consideran stock activo por el motor.
     */
    suspend fun loadOpenLotInsights(productIds: Set<String>): List<LotInsight> {
        if (productIds.isEmpty()) return emptyList()
        val docs = coroutineScope {
            productIds.map { productId ->
                async {
                    firestore.collection("inventoryLots")
                        .whereEqualTo("productId", productId)
                        .whereEqualTo("isDepleted", false)
                        .get().await()
                        .documents
                }
            }.awaitAll().flatten()
        }

        return docs
            .mapNotNull { doc -> doc.toObject(StockLot::class.java)?.copy(id = doc.id) }
            .asSequence()
            .map { lot ->
                LotInsight(
                    lotId = lot.id,
                    productId = lot.productId,
                    productName = lot.productName,
                    location = lot.location,
                    currentKg = lot.currentQuantity,
                    receivedAt = lot.receivedAt,
                    originalReceivedAt = lot.originalReceivedAt,
                    supplierName = lot.supplierName ?: lot.originalSupplierName,
                    unitName = lot.unidadDeEmpaque,
                    kgPerUnit = lot.pesoPorUnidad,
                    isDepleted = lot.isDepleted
                )
            }
            .toList()
    }

    fun activeMatrizFifoByProduct(lots: List<LotInsight>): Map<String, List<FifoLotSnapshot>> {
        return lots.asSequence()
            .filter { it.location == Location.MATRIZ && StockQuantityPolicy.isUsable(it.currentKg) }
            .map { lot ->
                FifoLotSnapshot(
                    lotId = lot.lotId,
                    productId = lot.productId,
                    currentKg = lot.currentKg,
                    receivedAt = lot.receivedAt,
                    originalReceivedAt = lot.originalReceivedAt,
                    unitName = lot.unitName,
                    kgPerUnit = lot.kgPerUnit
                )
            }
            .groupBy { it.productId }
            .mapValues { (_, value) -> value.sortedBy { it.effectiveReceivedAt()?.time ?: Long.MAX_VALUE } }
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
                "AJUSTE_MANUAL" -> movement.quantity < -0.1 &&
                    (movement.locationFrom == Location.MATRIZ || movement.locationFrom == Location.CONGELADOR_04)
                else -> false
            }
            if (isConsumption) {
                val kg = if (movement.quantity < 0) kotlin.math.abs(movement.quantity) else movement.quantity
                if (kg > 0.1) result[movement.productId] = (result[movement.productId] ?: 0.0) + kg
            }
        }
        return result
    }

    /** Todos los movimientos de UN producto: compras, traspasos y devoluciones se derivan sin consultas extra. */
    suspend fun loadMovementsForProduct(productId: String): List<StockMovement> {
        val snapshot = firestore.collection("stockMovements")
            .whereEqualTo("productId", productId)
            .get().await()
        return snapshot.documents
            .mapNotNull { doc -> doc.toObject(StockMovement::class.java)?.copy(id = doc.id) }
            .sortedBy { it.timestamp?.time ?: Long.MIN_VALUE }
    }

    suspend fun loadPackagingSignal(productId: String): PackagingSignal? {
        val snapshot = firestore.collection("pendingPackaging")
            .whereEqualTo("productId", productId)
            .get().await()
        val docs = snapshot.documents
        if (docs.isEmpty()) return null
        val dates = docs.mapNotNull { it.getDate("receivedAt") }
        return PackagingSignal(
            pendingCount = docs.size,
            pendingKg = docs.sumOf { it.getDouble("quantityReceived") ?: 0.0 },
            oldestPendingAt = dates.minByOrNull { it.time },
            suppliers = docs.mapNotNull { it.getString("supplierName")?.takeIf(String::isNotBlank) }.distinct()
        )
    }

    suspend fun loadReturnSignal(productId: String, productMovements: List<StockMovement>): ReturnSignal? {
        val snapshot = firestore.collection("pendingDevoluciones")
            .whereEqualTo("productId", productId)
            .get().await()
        val pendingDocs = snapshot.documents.filter {
            (it.getString("status") ?: "PENDIENTE").uppercase() == "PENDIENTE"
        }

        val historical = productMovements.filter {
            it.type.name == "SALIDA_DEVOLUCION" ||
                it.type.name == "DEVOLUCION_PROVEEDOR" ||
                it.type.name == "DEVOLUCION_CLIENTE"
        }

        if (pendingDocs.isEmpty() && historical.isEmpty()) return null

        val providers = linkedSetOf<String>()
        pendingDocs.mapNotNullTo(providers) { it.getString("provider")?.takeIf(String::isNotBlank) }
        historical.mapNotNullTo(providers) { movement -> extractProviderFromReason(movement.reason) }

        return ReturnSignal(
            pendingCount = pendingDocs.size,
            pendingKg = pendingDocs.sumOf { it.getDouble("quantity") ?: 0.0 },
            historicalCount = historical.size,
            historicalKg = historical.sumOf { kotlin.math.abs(it.quantity) },
            mainProviders = providers.take(4)
        )
    }

    fun seriesForProduct(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpointValues: Map<String, Double>
    ): List<DemandPoint> {
        // No convertimos checkpoint faltante en cero: se conserva como dato ausente para la predicción.
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

    private fun extractProviderFromReason(reason: String?): String? {
        val text = reason ?: return null
        val match = Regex("^A\\s+(.+?)\\.\\s*Motivo:", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
