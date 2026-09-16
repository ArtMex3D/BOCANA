package com.cesar.bocana.predictive.v3

import com.cesar.bocana.predictive.v3.model.FifoLotSnapshot
import com.cesar.bocana.predictive.v3.model.FifoSignal
import com.cesar.bocana.predictive.v3.model.PurchaseAttentionLevel
import com.cesar.bocana.predictive.v3.model.PurchaseAttentionSignal
import com.cesar.bocana.predictive.v3.model.PurchaseHistorySignal
import com.cesar.bocana.util.StockQuantityPolicy
import java.util.Date
import kotlin.math.abs

/**
 * Señales auxiliares V3. Son informativas/sugerentes y NUNCA modifican inventario.
 */
object PredictiveV3Signals {

    fun recentDeviationPct(baselineWeeklyKg: Double, liveWeeklyKg: Double): Double? {
        if (baselineWeeklyKg <= 0.01) return null
        return ((liveWeeklyKg / baselineWeeklyKg) - 1.0) * 100.0
    }

    fun fifoSignal(lots: List<FifoLotSnapshot>, now: Date): FifoSignal? {
        val active = lots.filter { StockQuantityPolicy.isUsable(it.currentKg) }
        if (active.isEmpty()) return null
        val oldest = active.minByOrNull { it.effectiveReceivedAt()?.time ?: Long.MAX_VALUE }
        val oldestDate = oldest?.effectiveReceivedAt()
        val ageDays = oldestDate?.let { ((now.time - it.time).coerceAtLeast(0L) / 86_400_000.0) }
        return FifoSignal(
            oldestLotAgeDays = ageDays,
            oldestLotKg = oldest?.currentKg,
            totalActiveMatrizKg = active.sumOf { it.currentKg.coerceAtLeast(0.0) },
            oldestLotDate = oldestDate,
            activeLotCount = active.size
        )
    }

    fun purchaseAttention(
        purchase: PurchaseHistorySignal?,
        coverageDays: Double?,
        now: Date
    ): PurchaseAttentionSignal? {
        if (purchase == null || coverageDays == null || coverageDays <= 0.0) return null

        val lastTime = purchase.lastPurchaseAt?.time
        val daysSinceLast = lastTime?.let { ((now.time - it).coerceAtLeast(0L) / 86_400_000.0) }
        val cadence = purchase.averageDaysBetweenPurchases?.takeIf { it > 0.0 }
        val untilTypical = if (cadence != null && daysSinceLast != null) cadence - daysSinceLast else null

        val level = when {
            coverageDays <= 7.0 -> PurchaseAttentionLevel.REVIEW_SOON
            cadence != null && coverageDays <= cadence * 0.55 -> PurchaseAttentionLevel.REVIEW_SOON
            cadence != null && coverageDays <= cadence * 0.90 -> PurchaseAttentionLevel.WATCH
            untilTypical != null && untilTypical <= 7.0 && cadence != null && coverageDays <= cadence * 1.25 -> PurchaseAttentionLevel.WATCH
            else -> PurchaseAttentionLevel.NORMAL
        }

        val message = when (level) {
            PurchaseAttentionLevel.NORMAL -> "La cobertura actual no muestra urgencia de compra según el historial"
            PurchaseAttentionLevel.WATCH -> "Conviene vigilar la próxima compra: el stock se acerca al ritmo habitual de reposición"
            PurchaseAttentionLevel.REVIEW_SOON -> "Conviene revisar compra pronto: la cobertura es corta frente al historial"
        }

        return PurchaseAttentionSignal(
            level = level,
            message = message,
            daysSinceLastPurchase = daysSinceLast,
            estimatedDaysUntilTypicalPurchase = untilTypical
        )
    }

    /**
     * Información avanzada para explicar qué observó el motor.
     * Se redacta con lenguaje operativo, no técnico.
     */
    fun plainReasons(
        deviationPct: Double?,
        fifo: FifoSignal?,
        purchaseAttention: PurchaseAttentionSignal?,
        transferSuggestedKg: Double,
        limitedByReserve: Boolean
    ): List<String> = buildList {
        deviationPct?.let { deviation ->
            if (abs(deviation) >= 15.0) {
                if (deviation > 0) add("El consumo reciente está ${formatPct(abs(deviation))}% por arriba de lo habitual")
                else add("El consumo reciente está ${formatPct(abs(deviation))}% por debajo de lo habitual")
            }
        }
        fifo?.oldestLotAgeDays?.let { age ->
            add("Hay mercancía antigua disponible: el lote más viejo tiene aproximadamente ${format0(age)} días")
        }
        purchaseAttention?.takeIf { it.level != PurchaseAttentionLevel.NORMAL }?.let {
            add(it.message)
        }
        if (transferSuggestedKg <= 0.01) add("C04 ya tiene suficiente para cubrir la siguiente ventana operativa")
        if (limitedByReserve) add("La sugerencia se reduce para no comprometer la reserva general de Matriz")
    }

    private fun formatPct(value: Double): String = String.format(java.util.Locale.US, "%.0f", value)
    private fun format0(value: Double): String = String.format(java.util.Locale.US, "%.0f", value)
}
