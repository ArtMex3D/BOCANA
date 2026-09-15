package com.cesar.bocana.predictive.v3

import com.cesar.bocana.predictive.v3.model.DemandEntityType
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.ForecastContext
import com.cesar.bocana.predictive.v3.model.GroupAllocationInput
import com.cesar.bocana.predictive.v3.model.GroupMemberState
import com.cesar.bocana.predictive.v3.model.SeasonRegime
import com.cesar.bocana.predictive.v3.model.ServiceAllocationInput
import com.cesar.bocana.predictive.v3.model.PurchaseAttentionLevel
import com.cesar.bocana.predictive.v3.model.PurchaseHistorySignal
import java.util.Date

/**
 * Pruebas simples sin JUnit para validar invariantes matemáticas del motor.
 * Se pueden ejecutar con Kotlin/JVM aislado.
 */
object PredictiveV3SelfTest {
    @JvmStatic
    fun main(args: Array<String>) {
        testGroupAggregationDoesNotDoubleAverages()
        testDynamicC04CanBeBelowLegacyReference()
        testServiceDemandIsConserved()
        testGroupAllocationConservesRequestedNeed()
        testRecentDeviationSignal()
        testPurchaseAttentionSignal()
        println("OK - Predictive V3 core self tests")
    }

    private fun testGroupAggregationDoesNotDoubleAverages() {
        val series = mapOf(
            "LENGUA" to listOf(DemandPoint("JAN", 1000.0), DemandPoint("FEB", 0.0)),
            "CURVINA" to listOf(DemandPoint("JAN", 0.0), DemandPoint("FEB", 2000.0))
        )
        val group = PredictiveGroupEngine.aggregateByPeriod(series)
        check(group.size == 2)
        check(group.first { it.periodKey == "JAN" }.consumedKg == 1000.0)
        check(group.first { it.periodKey == "FEB" }.consumedKg == 2000.0)
        check(group.map { it.consumedKg }.average() == 1500.0)
    }

    private fun testDynamicC04CanBeBelowLegacyReference() {
        val result = PredictiveV3Engine.forecast(
            ForecastContext(
                entityId = "X",
                entityType = DemandEntityType.PRODUCT,
                completeWeeks = List(10) { DemandPoint("W$it", 70.0) },
                currentWeekConsumedKg = 15.0,
                currentWeekElapsedDays = 2.0,
                targetWindowDays = 3.0,
                stockC04Kg = 10.0,
                stockMatrizKg = 1000.0,
                stockTotalKg = 1010.0,
                legacyC04ReferenceKg = 200.0,
                regime = SeasonRegime.NORMAL
            )
        )
        check(result.dynamicC04TargetKg < 200.0)
    }

    private fun testServiceDemandIsConserved() {
        val result = PredictiveGroupEngine.allocateService(
            ServiceAllocationInput(
                serviceId = "ROBALO_PARGOS",
                totalWeeklyDemandKg = 500.0,
                anchorNormalShare = 0.40,
                anchorMinimumShare = 0.20,
                anchorStockAvailableKg = 100.0,
                anchorReserveKg = 0.0,
                planningHorizonWeeks = 2.0
            )
        )
        check(kotlin.math.abs(result.anchorWeeklyKg + result.linkedGroupWeeklyKg - 500.0) < 0.001)
        check(result.anchorWasRestrictedByStock)
    }

    private fun testGroupAllocationConservesRequestedNeed() {
        val result = PredictiveGroupEngine.allocateTransfer(
            GroupAllocationInput(
                groupId = "PARGOS",
                groupDynamicTargetKg = 300.0,
                totalTransferNeedKg = 250.0,
                members = listOf(
                    GroupMemberState("HO", 0.65, 1.3, 0.0, 500.0),
                    GroupMemberState("HM", 0.35, 1.0, 0.0, 500.0)
                )
            )
        )
        check(result.allocatedKg <= 250.001)
        check(result.unallocatedKg >= -0.001)
    }
    private fun testRecentDeviationSignal() {
        val pct = PredictiveV3Signals.recentDeviationPct(100.0, 130.0)
        check(pct != null && kotlin.math.abs(pct - 30.0) < 0.001)
    }

    private fun testPurchaseAttentionSignal() {
        val now = Date(1_000_000_000L)
        val signal = PredictiveV3Signals.purchaseAttention(
            purchase = PurchaseHistorySignal(
                productId = "X",
                purchaseCount = 10,
                totalPurchasedKg = 1000.0,
                averagePurchaseKg = 100.0,
                lastPurchaseAt = Date(now.time - 20L * 86_400_000L),
                averageDaysBetweenPurchases = 30.0
            ),
            coverageDays = 10.0,
            now = now
        )
        check(signal?.level == PurchaseAttentionLevel.REVIEW_SOON)
    }

}
