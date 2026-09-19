package com.cesar.bocana.predictive.v3.data

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.FifoLotSnapshot
import com.cesar.bocana.predictive.v3.model.LotInsight
import com.cesar.bocana.predictive.v3.model.PackagingSignal
import com.cesar.bocana.predictive.v3.model.ReturnSignal
import com.cesar.bocana.util.StockQuantityPolicy
import java.util.Date

/**
 * Fuente de datos de SOLO LECTURA para V3.
 *
 * Permite usar Firestore o Room sin cambiar la matemática del motor.
 * La UI diaria debe preferir Room después de que la sincronización inicial terminó.
 */
interface PredictiveV3DataSource {

    suspend fun loadProducts(): List<Product>

    suspend fun loadOpenLotInsights(productIds: Set<String>): List<LotInsight>

    suspend fun loadCheckpointValues(
        productIds: Set<String>,
        weeks: List<PredictiveV3Time.WeekRef>
    ): Map<String, Double>

    suspend fun loadConsumptionForRange(
        productIds: Set<String>,
        startInclusive: Date,
        endExclusive: Date
    ): Map<String, Double>

    suspend fun loadMovementsForProduct(productId: String): List<StockMovement>

    suspend fun loadPackagingSignal(productId: String): PackagingSignal?

    suspend fun loadReturnSignal(
        productId: String,
        productMovements: List<StockMovement>
    ): ReturnSignal?

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
            .mapValues { (_, value) ->
                value.sortedBy { it.effectiveReceivedAt()?.time ?: Long.MAX_VALUE }
            }
    }

    fun seriesForProduct(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpointValues: Map<String, Double>
    ): List<DemandPoint> {
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
