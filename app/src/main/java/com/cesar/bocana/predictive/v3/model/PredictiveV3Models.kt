package com.cesar.bocana.predictive.v3.model

import java.util.Date

enum class DemandEntityType {
    PRODUCT,
    GROUP,
    SERVICE
}

enum class SeasonRegime {
    NORMAL,
    LENT,
    DECEMBER,
    HOLIDAY,
    HIGH_SEASON
}

enum class TrendSignal {
    FALLING,
    STABLE,
    RISING,
    SURGE
}

enum class ConfidenceLevel {
    LOW,
    MEDIUM,
    HIGH
}

data class DemandPoint(
    val periodKey: String,
    val consumedKg: Double,
    val regime: SeasonRegime = SeasonRegime.NORMAL
)

data class ForecastContext(
    val entityId: String,
    val entityType: DemandEntityType,
    val completeWeeks: List<DemandPoint>,
    val currentWeekConsumedKg: Double,
    val currentWeekElapsedDays: Double,
    val targetWindowDays: Double,
    val stockC04Kg: Double,
    val stockMatrizKg: Double,
    val stockTotalKg: Double,
    val generalReserveKg: Double = 0.0,
    val legacyC04ReferenceKg: Double = 0.0,
    val seasonalReferenceWeeklyKg: Double? = null,
    val regime: SeasonRegime = SeasonRegime.NORMAL,
    val regimeMultiplier: Double = 1.0
)

data class ForecastResultV3(
    val entityId: String,
    val entityType: DemandEntityType,
    val baselineWeeklyKg: Double,
    val liveWeeklyPaceKg: Double,
    val forecastWeeklyKg: Double,
    val lowScenarioWeeklyKg: Double,
    val highScenarioWeeklyKg: Double,
    val trendSignal: TrendSignal,
    val confidence: ConfidenceLevel,
    val variability: Double,
    val coverageDays: Double?,
    val safetyDays: Double,
    val dynamicC04TargetKg: Double,
    val suggestedTransferKg: Double,
    val limitedByMatrizReserve: Boolean,
    val legacyC04ReferenceKg: Double,
    val reasons: List<String>
)

data class FifoLotSnapshot(
    val lotId: String,
    val productId: String,
    val currentKg: Double,
    val receivedAt: Date?,
    val originalReceivedAt: Date? = null,
    val unitName: String? = null,
    val kgPerUnit: Double? = null
) {
    fun effectiveReceivedAt(): Date? = originalReceivedAt ?: receivedAt
}

data class GroupMemberRule(
    val productId: String = "",
    val priorityWeight: Double = 1.0,
    val enabled: Boolean = true
)

data class PredictiveGroupConfig(
    val id: String = "",
    val name: String = "",
    val memberProductIds: List<String> = emptyList(),
    val memberRules: List<GroupMemberRule> = emptyList(),
    val enabled: Boolean = true
)

data class PredictiveServiceRelation(
    val id: String = "",
    val name: String = "",
    val anchorProductId: String = "",
    val linkedGroupId: String = "",
    val preferredGroupProductId: String? = null,
    val enabled: Boolean = true
)

data class GroupMemberState(
    val productId: String,
    val typicalShare: Double,
    val priorityWeight: Double = 1.0,
    val stockC04Kg: Double,
    val stockMatrizKg: Double,
    val generalReserveKg: Double = 0.0,
    val fifoLots: List<FifoLotSnapshot> = emptyList()
)

data class GroupAllocationInput(
    val groupId: String,
    val groupDynamicTargetKg: Double,
    val totalTransferNeedKg: Double,
    val members: List<GroupMemberState>,
    val now: Date = Date()
)

data class LotPick(
    val lotId: String,
    val productId: String,
    val kg: Double,
    val effectiveReceivedAt: Date?
)

data class MemberAllocation(
    val productId: String,
    val suggestedKg: Double,
    val lotPicks: List<LotPick>,
    val reasons: List<String>
)

data class GroupAllocationResult(
    val groupId: String,
    val requestedKg: Double,
    val allocatedKg: Double,
    val members: List<MemberAllocation>,
    val unallocatedKg: Double,
    val reasons: List<String>
)

data class ServiceAllocationInput(
    val serviceId: String,
    val totalWeeklyDemandKg: Double,
    val anchorNormalShare: Double,
    val anchorMinimumShare: Double,
    val anchorStockAvailableKg: Double,
    val anchorReserveKg: Double,
    val planningHorizonWeeks: Double
)

data class ServiceAllocationResult(
    val serviceId: String,
    val totalWeeklyDemandKg: Double,
    val anchorWeeklyKg: Double,
    val linkedGroupWeeklyKg: Double,
    val anchorWasRestrictedByStock: Boolean,
    val reasons: List<String>
)

data class PredictiveDecisionRecord(
    val id: String = "",
    val entityId: String = "",
    val entityType: String = "",
    val createdAt: Date? = null,
    val suggestedKg: Double = 0.0,
    val chosenKg: Double? = null,
    val actualConsumedKg: Double? = null,
    val horizonStart: Date? = null,
    val horizonEnd: Date? = null,
    val modelVersion: Int = 0,
    val explanationCodes: List<String> = emptyList()
)
