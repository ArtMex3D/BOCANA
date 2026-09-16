package com.cesar.bocana.util

import kotlin.math.max
import kotlin.math.min

/**
 * Regla única de cantidades físicas de Bocana.
 *
 * - Todo lote con MENOS de 0.10 kg deja de existir operativamente.
 * - 0.10 kg exactos siguen siendo una cantidad válida.
 * - Si una salida/traspaso dejaría menos de 0.10 kg, se toma el lote completo.
 * - No redondea cálculos normales: sólo elimina residuos físicos sin sentido operativo.
 *
 * FLOAT_EPSILON protege el límite 0.10 contra artefactos binarios como
 * 0.09999999999999998 cuando matemáticamente el resultado era 0.10.
 */
object StockQuantityPolicy {
    const val MIN_USABLE_KG = 0.10
    const val FLOAT_EPSILON = 0.000000001

    private fun isBelowOperationalMinimum(quantityKg: Double): Boolean =
        quantityKg + FLOAT_EPSILON < MIN_USABLE_KG

    fun isUsable(quantityKg: Double): Boolean =
        quantityKg.isFinite() && quantityKg > FLOAT_EPSILON && !isBelowOperationalMinimum(quantityKg)

    fun isResidual(quantityKg: Double): Boolean =
        quantityKg.isFinite() && quantityKg >= 0.0 && isBelowOperationalMinimum(quantityKg)

    fun normalizeLotQuantity(quantityKg: Double): Double = when {
        !quantityKg.isFinite() -> 0.0
        quantityKg <= FLOAT_EPSILON -> 0.0
        isBelowOperationalMinimum(quantityKg) -> 0.0
        else -> quantityKg
    }

    /**
     * Calcula cuánto sacar realmente de un lote.
     * Si la cantidad solicitada dejaría MENOS de 0.10 kg, el lote se cierra completo
     * y actualTakenKg incluye ese pequeño residuo para que stock, movimiento y destino cuadren.
     */
    fun withdrawFromLot(currentKg: Double, requestedKg: Double): Withdrawal {
        val current = max(0.0, currentKg)
        val requested = max(0.0, requestedKg)

        if (!isUsable(current) || requested <= FLOAT_EPSILON) {
            return Withdrawal(
                actualTakenKg = 0.0,
                remainingKg = normalizeLotQuantity(current),
                depleted = !isUsable(current)
            )
        }

        val rawTaken = min(current, requested)
        val rawRemaining = max(0.0, current - rawTaken)
        val closeLot = rawRemaining <= FLOAT_EPSILON || isBelowOperationalMinimum(rawRemaining)
        val actualTaken = if (closeLot) current else rawTaken
        val remaining = if (closeLot) 0.0 else rawRemaining

        return Withdrawal(
            actualTakenKg = actualTaken,
            remainingKg = remaining,
            depleted = closeLot
        )
    }

    data class Withdrawal(
        val actualTakenKg: Double,
        val remainingKg: Double,
        val depleted: Boolean
    )
}
