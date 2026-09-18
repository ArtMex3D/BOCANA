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

/**
 * Grupo de demanda conjunta.
 *
 * memberProductIds conserva los IDs reales de Product. El nombre del grupo puede
 * cambiar sin romper históricos porque el documento Firestore mantiene un ID estable.
 *
 * primaryProductId y secondaryProductIds son reglas operativas suaves:
 * FIFO y necesidad C04 siguen teniendo prioridad sobre estas preferencias.
 */
data class PredictiveGroupConfig(
    val id: String = "",
    val name: String = "",
    val memberProductIds: List<String> = emptyList(),
    // Miembros retirados/desactivados: sólo se usan para conservar la historia del grupo.
    val historicalProductIds: List<String> = emptyList(),
    val memberRules: List<GroupMemberRule> = emptyList(),
    val primaryProductId: String? = null,
    val secondaryProductIds: List<String> = emptyList(),
    val enabled: Boolean = true
)

/**
 * Grupo de equilibrio/complemento.
 *
 * Se mantiene el nombre histórico PredictiveServiceRelation por compatibilidad,
 * pero en la UI se administra exactamente igual que cualquier otro grupo.
 *
 * Ejemplo Bocana:
 *   Róbalo + Pargos
 *   - lado directo: Róbalo (anchorProductIds)
 *   - grupo relacionado: Pargos (linkedGroupId)
 *   - producto de apoyo preferente dentro de Pargos: HO
 *
 * IMPORTANTE: la relación es directa, pero NO significa 1 kg faltante = 1 kg del otro.
 * El motor aprende del historial cuánto aumenta la presión del grupo cuando el lado
 * directo está corto y limita ese ajuste.
 */
data class PredictiveServiceRelation(
    val id: String = "",
    val name: String = "",
    // Campo antiguo: se conserva para leer documentos ya existentes.
    val anchorProductId: String = "",
    // Campo nuevo: permite más de un producto directo en el futuro.
    val anchorProductIds: List<String> = emptyList(),
    // Productos directos antiguos: no participan en stock actual, pero conservan contexto histórico.
    val historicalAnchorProductIds: List<String> = emptyList(),
    val primaryAnchorProductId: String? = null,
    val linkedGroupId: String = "",
    val preferredGroupProductId: String? = null,
    val relationMode: String = "INVERSE_SUPPORT",
    val enabled: Boolean = true
) {
    fun effectiveAnchorProductIds(): List<String> =
        (anchorProductIds + listOf(anchorProductId))
            .filter { it.isNotBlank() }
            .distinct()

    fun effectivePrimaryAnchorId(): String? =
        primaryAnchorProductId?.takeIf { it.isNotBlank() }
            ?: anchorProductId.takeIf { it.isNotBlank() }
            ?: effectiveAnchorProductIds().firstOrNull()

    fun allHistoricalAnchorIds(): List<String> =
        (effectiveAnchorProductIds() + historicalAnchorProductIds)
            .filter { it.isNotBlank() }
            .distinct()
}

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

/**
 * Entrada de la relación complementaria.
 *
 * No redistribuye un total fijo. Conserva la predicción propia del producto directo
 * y solamente puede elevar moderadamente la presión del grupo relacionado cuando:
 * 1) la cobertura del producto directo es baja, y
 * 2) el histórico realmente muestra que el grupo suele subir en esas condiciones.
 */
data class ServiceAllocationInput(
    val serviceId: String,
    val anchorForecastWeeklyKg: Double,
    val linkedGroupForecastWeeklyKg: Double,
    val anchorCoverageDays: Double?,
    val learnedSupportUpliftPct: Double,
    val planningHorizonWeeks: Double = 2.0,
    val maxSupportUpliftPct: Double = 0.50
)

data class ServiceAllocationResult(
    val serviceId: String,
    // Suma informativa después del ajuste; NO representa una bolsa que se reparte 1:1.
    val totalWeeklyDemandKg: Double,
    val anchorWeeklyKg: Double,
    val linkedGroupWeeklyKg: Double,
    val anchorWasRestrictedByStock: Boolean,
    val supportPressurePct: Double = 0.0,
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
