package com.cesar.bocana.util

/**
 * Pruebas JVM simples para la regla física de stock de Bocana.
 * No necesitan Android ni Firebase.
 */
object StockQuantityPolicySelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        closeTinyRemainderCompletely()
        keepRealRemainder()
        normalizeFloatingGarbage()
        normalizeNineHundredths()
        zeroOpenLotIsResidualGhost()
        keepThreeTenths()
        keepExactlyOneTenth()
        preserveMathematicalOneTenthAfterSubtraction()
        println("OK - StockQuantityPolicy Fase 5.1")
    }

    private fun closeTinyRemainderCompletely() {
        val result = StockQuantityPolicy.withdrawFromLot(200.455, 200.45)
        check(kotlin.math.abs(result.actualTakenKg - 200.455) < 0.000001)
        check(result.remainingKg == 0.0)
        check(result.depleted)
    }

    private fun keepRealRemainder() {
        val result = StockQuantityPolicy.withdrawFromLot(200.80, 200.45)
        check(kotlin.math.abs(result.actualTakenKg - 200.45) < 0.000001)
        check(kotlin.math.abs(result.remainingKg - 0.35) < 0.000001)
        check(!result.depleted)
    }

    private fun normalizeFloatingGarbage() {
        check(StockQuantityPolicy.normalizeLotQuantity(0.00000001) == 0.0)
    }

    private fun normalizeNineHundredths() {
        check(StockQuantityPolicy.normalizeLotQuantity(0.09) == 0.0)
        check(StockQuantityPolicy.isResidual(0.09))
    }

    private fun zeroOpenLotIsResidualGhost() {
        check(StockQuantityPolicy.isResidual(0.0))
        check(!StockQuantityPolicy.isUsable(0.0))
    }

    private fun keepThreeTenths() {
        check(kotlin.math.abs(StockQuantityPolicy.normalizeLotQuantity(0.30) - 0.30) < 0.000001)
    }

    private fun keepExactlyOneTenth() {
        check(StockQuantityPolicy.isUsable(0.10))
        check(!StockQuantityPolicy.isResidual(0.10))
        check(kotlin.math.abs(StockQuantityPolicy.normalizeLotQuantity(0.10) - 0.10) < 0.000001)
    }

    private fun preserveMathematicalOneTenthAfterSubtraction() {
        // 10.2 - 10.1 suele representarse como 0.09999999999999964 en Double.
        // Operativamente es 0.10 exactos y NO debe agotarse.
        val result = StockQuantityPolicy.withdrawFromLot(10.2, 10.1)
        check(kotlin.math.abs(result.actualTakenKg - 10.1) < 0.000001)
        check(kotlin.math.abs(result.remainingKg - 0.10) < 0.000001)
        check(!result.depleted)
    }
}
