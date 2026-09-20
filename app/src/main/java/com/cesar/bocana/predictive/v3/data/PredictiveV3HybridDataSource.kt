package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.model.LotInsight
import com.cesar.bocana.predictive.v3.model.PackagingSignal
import com.cesar.bocana.predictive.v3.model.ReturnSignal
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/**
 * Fuente híbrida para el popup predictivo V3.
 *
 * Objetivo Bocana:
 * - reutilizar Room para los datos operativos que ya sincroniza la app;
 * - consultar Firestore únicamente para el histórico predictivo que Room no garantiza completo;
 * - no duplicar todo consumption_history dentro de Room;
 * - si Firestore falla, intentar reconstruir el histórico con los movimientos locales como respaldo.
 *
 * Esta clase es SOLO LECTURA. No modifica inventario ni escribe predicciones.
 */
class PredictiveV3HybridDataSource(
    database: AppDatabase,
    firestore: FirebaseFirestore
) : PredictiveV3DataSource {

    private val room = PredictiveV3RoomDataSource(database)
    private val cloud = PredictiveV3FirestoreDataSource(firestore)

    // Caché RAM por proceso para compartir las mismas lecturas históricas entre varios
    // productos/grupos durante una revisión V3. Nunca se persiste y se limpia cuando
    // el manager detecta un cambio operativo real.
    private val checkpointRequestCache = ConcurrentHashMap<String, Map<String, Double>>()
    private val rangeRequestCache = ConcurrentHashMap<String, Map<String, Double>>()

    fun clearHistoricalCache() {
        checkpointRequestCache.clear()
        rangeRequestCache.clear()
    }

    override suspend fun loadProducts(): List<Product> =
        room.loadProducts()

    override suspend fun loadOpenLotInsights(productIds: Set<String>): List<LotInsight> =
        room.loadOpenLotInsights(productIds)

    override suspend fun loadCheckpointValues(
        productIds: Set<String>,
        weeks: List<PredictiveV3Time.WeekRef>
    ): Map<String, Double> {
        val key = productIds.sorted().joinToString(",") + "|" +
            weeks.map { it.key }.sorted().joinToString(",")
        checkpointRequestCache[key]?.let { return it }

        val result = try {
            cloud.loadCheckpointValues(productIds, weeks)
        } catch (_: Exception) {
            room.loadCheckpointValues(productIds, weeks)
        }
        checkpointRequestCache[key] = result
        return result
    }

    override suspend fun loadConsumptionForRange(
        productIds: Set<String>,
        startInclusive: Date,
        endExclusive: Date
    ): Map<String, Double> {
        val key = productIds.sorted().joinToString(",") + "|" +
            startInclusive.time + "|" + endExclusive.time
        rangeRequestCache[key]?.let { return it }

        val result = try {
            cloud.loadConsumptionForRange(productIds, startInclusive, endExclusive)
        } catch (_: Exception) {
            room.loadConsumptionForRange(productIds, startInclusive, endExclusive)
        }
        rangeRequestCache[key] = result
        return result
    }

    override suspend fun loadMovementsForProduct(productId: String): List<StockMovement> =
        room.loadMovementsForProduct(productId)

    override suspend fun loadPackagingSignal(productId: String): PackagingSignal? =
        room.loadPackagingSignal(productId)

    override suspend fun loadReturnSignal(
        productId: String,
        productMovements: List<StockMovement>
    ): ReturnSignal? = room.loadReturnSignal(productId, productMovements)
}
