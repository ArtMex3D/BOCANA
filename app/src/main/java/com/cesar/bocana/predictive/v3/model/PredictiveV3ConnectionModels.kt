package com.cesar.bocana.predictive.v3.model

import com.cesar.bocana.data.model.Product
import java.util.Date

data class PredictiveV3Analysis(
    val selectedProduct: Product,
    val individualForecast: ForecastResultV3,
    val individualOperational: OperationalRecommendation,
    val groupAnalysis: GroupAnalysisV3? = null,
    val serviceAnalysis: ServiceAnalysisV3? = null,
    val purchaseSignal: PurchaseHistorySignal? = null,
    val transferSignal: TransferPatternSignal? = null,
    val historicalReference: HistoricalReferenceSignal? = null,
    val fifoSignal: FifoSignal? = null,
    val purchaseAttention: PurchaseAttentionSignal? = null,
    val effectiveCoverageDays: Double? = null,
    val recentDeviationPct: Double? = null,
    val smartReasons: List<String> = emptyList(),
    val targetWindowDays: Double,
    val regime: SeasonRegime,
    val projectId: String? = null,
    val inventoryDeep: InventoryDeepSignal? = null,
    val groupInventory: GroupInventorySignal? = null,
    val packagingSignal: PackagingSignal? = null,
    val returnSignal: ReturnSignal? = null,
    val consumptionPattern: ConsumptionPatternSignal? = null,
    val backtest: BacktestSignal? = null
)

data class OperationalRecommendation(
    val effectiveWeeklyKg: Double,
    val dynamicC04TargetKg: Double,
    val suggestedTransferKg: Double,
    val limitedByMatrizReserve: Boolean,
    val source: String,
    val reasons: List<String> = emptyList()
)

data class GroupAnalysisV3(
    val config: PredictiveGroupConfig,
    val forecast: ForecastResultV3,
    val operational: OperationalRecommendation,
    val allocation: GroupAllocationResult,
    val memberNames: Map<String, String>
)

data class ServiceAnalysisV3(
    val relation: PredictiveServiceRelation,
    val serviceForecast: ForecastResultV3,
    val allocation: ServiceAllocationResult,
    val learnedAnchorNormalShare: Double,
    val learnedAnchorMinimumShare: Double,
    val anchorProductName: String,
    val linkedGroupName: String
)

data class PurchaseHistorySignal(
    val productId: String,
    val purchaseCount: Int,
    val totalPurchasedKg: Double,
    val averagePurchaseKg: Double,
    val lastPurchaseAt: Date?,
    val averageDaysBetweenPurchases: Double?
)

data class TransferPatternSignal(
    val productId: String,
    val transferCount: Int,
    val averageKgAll: Double,
    val averageKgWindowA: Double?,
    val averageKgWindowB: Double?,
    val lastTransferAt: Date?
)

data class HistoricalReferenceSignal(
    val weekOneYearAgoKg: Double?,
    val monthOneYearAgoKg: Double?
)

data class FifoSignal(
    val oldestLotAgeDays: Double?,
    val oldestLotKg: Double?,
    val totalActiveMatrizKg: Double,
    val oldestLotDate: Date? = null,
    val activeLotCount: Int = 0
)

enum class PurchaseAttentionLevel {
    NORMAL,
    WATCH,
    REVIEW_SOON
}

data class PurchaseAttentionSignal(
    val level: PurchaseAttentionLevel,
    val message: String,
    val daysSinceLastPurchase: Double?,
    val estimatedDaysUntilTypicalPurchase: Double?
)

/** Lote leído para análisis profundo. Nunca modifica inventario. */
data class LotInsight(
    val lotId: String,
    val productId: String,
    val productName: String,
    val location: String,
    val currentKg: Double,
    val receivedAt: Date?,
    val originalReceivedAt: Date?,
    val supplierName: String?,
    val unitName: String?,
    val kgPerUnit: Double?,
    val isDepleted: Boolean
) {
    fun effectiveReceivedAt(): Date? = originalReceivedAt ?: receivedAt
}

data class MonthStockSummary(
    val year: Int,
    val month: Int,
    val totalKg: Double,
    val byProductKg: Map<String, Double>
)

data class InventoryDeepSignal(
    val matrizLots: List<LotInsight>,
    val c04Lots: List<LotInsight>,
    val matrizByMonth: List<MonthStockSummary>,
    val c04ByMonth: List<MonthStockSummary>,
    val residualLotCount: Int,
    val residualKg: Double,
    val negativeLotCount: Int
)

data class GroupInventorySignal(
    val groupId: String,
    val groupName: String,
    val matrizByMonth: List<MonthStockSummary>,
    val c04ByMonth: List<MonthStockSummary>,
    val activeMatrizLots: Int,
    val activeC04Lots: Int
)

data class PackagingSignal(
    val pendingCount: Int,
    val pendingKg: Double,
    val oldestPendingAt: Date?,
    val suppliers: List<String>
)

data class ReturnSignal(
    val pendingCount: Int,
    val pendingKg: Double,
    val historicalCount: Int,
    val historicalKg: Double,
    val mainProviders: List<String>
)

enum class ConsumptionPatternType {
    ACTIVE,
    SPORADIC,
    NO_RECENT,
    NO_HISTORY
}

data class ConsumptionPatternSignal(
    val type: ConsumptionPatternType,
    val title: String,
    val explanation: String
)

data class BacktestSignal(
    val sampleCount: Int,
    val meanAbsolutePercentError: Double?,
    val meanAbsoluteKgError: Double?,
    val qualityLabel: String,
    val explanation: String
)
