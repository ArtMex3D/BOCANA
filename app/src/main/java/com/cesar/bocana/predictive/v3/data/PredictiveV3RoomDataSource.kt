package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.DevolucionStatus
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.model.LotInsight
import com.cesar.bocana.predictive.v3.model.PackagingSignal
import com.cesar.bocana.predictive.v3.model.ReturnSignal
import java.util.Calendar
import java.util.Date
import kotlin.math.abs

/**
 * Fuente LOCAL de V3.
 *
 * No descarga datos por abrir una pantalla. Usa la fotografía de Room que los listeners
 * y la sincronización de InventoryRepository ya mantienen actualizada.
 */
class PredictiveV3RoomDataSource(
    private val db: AppDatabase
) : PredictiveV3DataSource {

    private val productDao = db.productDao()
    private val lotDao = db.stockLotDao()
    private val movementDao = db.stockMovementDao()
    private val packagingDao = db.packagingDao()
    private val devolucionDao = db.devolucionDao()

    override suspend fun loadProducts(): List<Product> =
        productDao.getAllActiveProductsOnce()

    override suspend fun loadOpenLotInsights(productIds: Set<String>): List<LotInsight> {
        if (productIds.isEmpty()) return emptyList()
        return lotDao.getAllOpenLotsOnce()
            .asSequence()
            .filter { productIds.contains(it.productId) }
            .map(::toInsight)
            .toList()
    }

    /**
     * Reconstruye los checkpoints solicitados desde movimientos LOCALES.
     * No escribe consumption_history y no hace ninguna consulta a Firestore.
     */
    override suspend fun loadCheckpointValues(
        productIds: Set<String>,
        weeks: List<PredictiveV3Time.WeekRef>
    ): Map<String, Double> {
        if (productIds.isEmpty() || weeks.isEmpty()) return emptyMap()

        val requested = weeks.associateBy { it.year to it.week }
        val result = mutableMapOf<String, Double>()

        movementDao.getAllMovementsOnce().forEach { movement ->
            if (!productIds.contains(movement.productId)) return@forEach
            val timestamp = movement.timestamp ?: return@forEach
            val kg = consumptionKg(movement) ?: return@forEach

            val cal = Calendar.getInstance().apply { time = timestamp }
            val key = cal.get(Calendar.YEAR) to cal.get(Calendar.WEEK_OF_YEAR)
            val week = requested[key] ?: return@forEach
            val checkpointId = week.checkpointId(movement.productId)
            result[checkpointId] = (result[checkpointId] ?: 0.0) + kg
        }

        return result
    }

    override suspend fun loadConsumptionForRange(
        productIds: Set<String>,
        startInclusive: Date,
        endExclusive: Date
    ): Map<String, Double> {
        if (productIds.isEmpty()) return emptyMap()

        val result = mutableMapOf<String, Double>()
        movementDao.getMovementsBetweenOnce(
            startMillis = startInclusive.time,
            endMillis = endExclusive.time
        ).forEach { movement ->
            if (!productIds.contains(movement.productId)) return@forEach
            val kg = consumptionKg(movement) ?: return@forEach
            result[movement.productId] = (result[movement.productId] ?: 0.0) + kg
        }
        return result
    }

    override suspend fun loadMovementsForProduct(productId: String): List<StockMovement> =
        movementDao.getMovementsForProductOnce(productId)

    override suspend fun loadPackagingSignal(productId: String): PackagingSignal? {
        val tasks = packagingDao.getPackagingTasksForProductOnce(productId)
        if (tasks.isEmpty()) return null

        return PackagingSignal(
            pendingCount = tasks.size,
            pendingKg = tasks.sumOf { it.quantityReceived.coerceAtLeast(0.0) },
            oldestPendingAt = tasks.mapNotNull { it.receivedAt }.minByOrNull { it.time },
            suppliers = tasks.mapNotNull { it.supplierName?.takeIf(String::isNotBlank) }.distinct()
        )
    }

    override suspend fun loadReturnSignal(
        productId: String,
        productMovements: List<StockMovement>
    ): ReturnSignal? {
        val pending = devolucionDao.getDevolucionesForProductOnce(productId)
            .filter { it.status == DevolucionStatus.PENDIENTE }

        val historical = productMovements.filter {
            it.type == MovementType.SALIDA_DEVOLUCION ||
                it.type == MovementType.DEVOLUCION_PROVEEDOR ||
                it.type == MovementType.DEVOLUCION_CLIENTE
        }

        if (pending.isEmpty() && historical.isEmpty()) return null

        val providers = linkedSetOf<String>()
        pending.mapNotNullTo(providers) { it.provider.takeIf(String::isNotBlank) }
        historical.mapNotNullTo(providers) { extractProviderFromReason(it.reason) }

        return ReturnSignal(
            pendingCount = pending.size,
            pendingKg = pending.sumOf { it.quantity.coerceAtLeast(0.0) },
            historicalCount = historical.size,
            historicalKg = historical.sumOf { abs(it.quantity) },
            mainProviders = providers.take(4)
        )
    }

    private fun toInsight(lot: StockLot): LotInsight =
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

    private fun consumptionKg(movement: StockMovement): Double? {
        val isConsumption = when (movement.type) {
            MovementType.SALIDA_CONSUMO,
            MovementType.SALIDA_CONSUMO_C04,
            MovementType.AJUSTE_STOCK_C04 -> true

            MovementType.AJUSTE_MANUAL ->
                movement.quantity < -0.1 &&
                    (movement.locationFrom == Location.MATRIZ ||
                        movement.locationFrom == Location.CONGELADOR_04)

            else -> false
        }

        if (!isConsumption) return null
        val kg = if (movement.quantity < 0.0) abs(movement.quantity) else movement.quantity
        return kg.takeIf { it > 0.1 }
    }

    private fun extractProviderFromReason(reason: String?): String? {
        val text = reason ?: return null
        val match = Regex("^A\\s+(.+?)\\.\\s*Motivo:", RegexOption.IGNORE_CASE).find(text)
        return match?.groupValues?.getOrNull(1)?.trim()?.takeIf { it.isNotBlank() }
    }
}
