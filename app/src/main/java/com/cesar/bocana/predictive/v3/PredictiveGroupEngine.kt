package com.cesar.bocana.predictive.v3

import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.FifoLotSnapshot
import com.cesar.bocana.predictive.v3.model.GroupAllocationInput
import com.cesar.bocana.predictive.v3.model.GroupAllocationResult
import com.cesar.bocana.predictive.v3.model.GroupMemberState
import com.cesar.bocana.predictive.v3.model.LotPick
import com.cesar.bocana.predictive.v3.model.MemberAllocation
import com.cesar.bocana.predictive.v3.model.ServiceAllocationInput
import com.cesar.bocana.predictive.v3.model.ServiceAllocationResult
import java.util.Date
import kotlin.math.max
import kotlin.math.min

/**
 * Matemática para grupos y relaciones de servicio.
 *
 * Regla crítica Bocana:
 * el pronóstico de un grupo sale de la SERIE AGREGADA POR PERIODO.
 * Nunca se suman promedios individuales ya calculados.
 */
object PredictiveGroupEngine {

    /**
     * Suma consumos de miembros dentro del mismo periodo y después devuelve
     * una única serie del grupo.
     */
    fun aggregateByPeriod(
        memberSeries: Map<String, List<DemandPoint>>
    ): List<DemandPoint> {
        val bucket = linkedMapOf<String, Double>()
        val regimeByPeriod = linkedMapOf<String, com.cesar.bocana.predictive.v3.model.SeasonRegime>()

        memberSeries.values.flatten().forEach { point ->
            bucket[point.periodKey] = (bucket[point.periodKey] ?: 0.0) + point.consumedKg.coerceAtLeast(0.0)
            regimeByPeriod.putIfAbsent(point.periodKey, point.regime)
        }

        return bucket.entries.map { (periodKey, kg) ->
            DemandPoint(
                periodKey = periodKey,
                consumedKg = kg,
                regime = regimeByPeriod[periodKey]
                    ?: com.cesar.bocana.predictive.v3.model.SeasonRegime.NORMAL
            )
        }
    }

    /**
     * Participación típica aprendida del HISTÓRICO REAL del grupo.
     * No depende del stock actual.
     */
    fun learnTypicalShares(
        memberSeries: Map<String, List<DemandPoint>>,
        periodsToUse: Set<String>? = null
    ): Map<String, Double> {
        val totalsByMember = memberSeries.mapValues { (_, series) ->
            series.asSequence()
                .filter { periodsToUse == null || periodsToUse.contains(it.periodKey) }
                .sumOf { it.consumedKg.coerceAtLeast(0.0) }
        }
        val groupTotal = totalsByMember.values.sum()
        if (groupTotal <= 0.0) {
            val active = totalsByMember.keys
            if (active.isEmpty()) return emptyMap()
            val equal = 1.0 / active.size
            return active.associateWith { equal }
        }
        return totalsByMember.mapValues { (_, kg) -> kg / groupTotal }
    }

    /**
     * Reparte una necesidad YA calculada del grupo entre integrantes.
     * Mezcla:
     * - déficit contra la participación histórica,
     * - presión FIFO por antigüedad,
     * - preferencia operativa suave (ej. HO principal),
     * siempre limitada por stock usable en Matriz.
     */
    fun allocateTransfer(input: GroupAllocationInput): GroupAllocationResult {
        val need = input.totalTransferNeedKg.coerceAtLeast(0.0)
        if (need <= 0.01 || input.members.isEmpty()) {
            return GroupAllocationResult(
                groupId = input.groupId,
                requestedKg = need,
                allocatedKg = 0.0,
                members = emptyList(),
                unallocatedKg = need,
                reasons = listOf("El grupo ya cubre la ventana operativa")
            )
        }

        val normalizedShares = normalizeShares(input.members)
        val maxAge = input.members.maxOfOrNull { oldestAgeDays(it.fifoLots, input.now) }?.coerceAtLeast(1.0) ?: 1.0

        data class Candidate(
            val member: GroupMemberState,
            val availableKg: Double,
            val desiredDeficitKg: Double,
            val score: Double
        )

        val candidates = input.members.map { member ->
            val share = normalizedShares[member.productId] ?: 0.0
            val memberTarget = input.groupDynamicTargetKg.coerceAtLeast(0.0) * share
            val deficit = max(0.0, memberTarget - member.stockC04Kg.coerceAtLeast(0.0))
            val available = max(0.0, member.stockMatrizKg - member.generalReserveKg)
            val agePressure = oldestAgeDays(member.fifoLots, input.now) / maxAge
            val deficitPressure = if (need > 0.0) (deficit / need).coerceIn(0.0, 2.0) else 0.0
            val preference = member.priorityWeight.coerceIn(0.25, 3.0)

            // FIFO puede dominar una preferencia, pero no ignora la necesidad operativa.
            val score = if (available <= 0.0) {
                0.0
            } else {
                0.55 * deficitPressure + 0.35 * agePressure + 0.10 * preference
            }.coerceAtLeast(0.001.takeIf { available > 0.0 } ?: 0.0)

            Candidate(member, available, deficit, score)
        }.toMutableList()

        val allocated = mutableMapOf<String, Double>()
        var remaining = need
        var guard = 0

        while (remaining > 0.01 && guard++ < 20) {
            val active = candidates.filter { candidate ->
                val already = allocated[candidate.member.productId] ?: 0.0
                candidate.availableKg - already > 0.01
            }
            if (active.isEmpty()) break

            val scoreSum = active.sumOf { it.score }.takeIf { it > 0.0 } ?: active.size.toDouble()
            var allocatedThisRound = 0.0

            active.forEach { candidate ->
                if (remaining <= 0.01) return@forEach
                val productId = candidate.member.productId
                val already = allocated[productId] ?: 0.0
                val capacity = max(0.0, candidate.availableKg - already)
                if (capacity <= 0.01) return@forEach

                val proportional = remaining * (candidate.score / scoreSum)
                val kg = min(capacity, max(0.01, proportional))
                allocated[productId] = already + kg
                allocatedThisRound += kg
            }

            remaining = max(0.0, need - allocated.values.sum())
            if (allocatedThisRound <= 0.001) break
        }

        val memberResults = input.members.mapNotNull { member ->
            val kg = (allocated[member.productId] ?: 0.0).coerceAtLeast(0.0)
            if (kg <= 0.01) return@mapNotNull null
            val picks = pickFifoLots(member.fifoLots, kg)
            val oldest = picks.minByOrNull { it.effectiveReceivedAt?.time ?: Long.MAX_VALUE }

            MemberAllocation(
                productId = member.productId,
                suggestedKg = picks.sumOf { it.kg },
                lotPicks = picks,
                reasons = buildList {
                    if (oldest?.effectiveReceivedAt != null) add("FIFO prioriza el lote más antiguo disponible")
                    val share = normalizedShares[member.productId] ?: 0.0
                    add("Participación histórica objetivo: ${String.format(java.util.Locale.US, "%.0f", share * 100.0)}%")
                    if (member.priorityWeight > 1.0) add("Tiene prioridad operativa dentro del grupo")
                }
            )
        }

        val allocatedTotal = memberResults.sumOf { it.suggestedKg }
        val unallocated = max(0.0, need - allocatedTotal)

        return GroupAllocationResult(
            groupId = input.groupId,
            requestedKg = need,
            allocatedKg = allocatedTotal,
            members = memberResults,
            unallocatedKg = unallocated,
            reasons = buildList {
                add("La cantidad total del grupo se calculó antes de repartir por producto")
                add("El reparto combina necesidad en C04, FIFO y participación histórica")
                if (unallocated > 0.01) add("Faltan ${String.format(java.util.Locale.US, "%.1f", unallocated)} kg por disponibilidad/reserva en Matriz")
            }
        )
    }

    /**
     * Relación Róbalo ↔ grupo Pargos.
     * El total del servicio se conserva; solo cambia el reparto.
     */
    fun allocateService(input: ServiceAllocationInput): ServiceAllocationResult {
        val total = input.totalWeeklyDemandKg.coerceAtLeast(0.0)
        if (total <= 0.0) {
            return ServiceAllocationResult(
                serviceId = input.serviceId,
                totalWeeklyDemandKg = 0.0,
                anchorWeeklyKg = 0.0,
                linkedGroupWeeklyKg = 0.0,
                anchorWasRestrictedByStock = false,
                reasons = listOf("No existe demanda válida del servicio")
            )
        }

        val normalShare = input.anchorNormalShare.coerceIn(0.0, 1.0)
        val minShare = min(normalShare, input.anchorMinimumShare.coerceIn(0.0, 1.0))
        val desiredAnchor = total * normalShare
        val minimumAnchor = total * minShare

        val usableAnchorStock = max(0.0, input.anchorStockAvailableKg - input.anchorReserveKg)
        val horizon = input.planningHorizonWeeks.coerceAtLeast(0.25)
        val sustainableAnchorWeekly = usableAnchorStock / horizon

        val anchor = when {
            usableAnchorStock <= 0.01 -> 0.0
            sustainableAnchorWeekly >= desiredAnchor -> desiredAnchor
            sustainableAnchorWeekly >= minimumAnchor -> sustainableAnchorWeekly
            else -> min(minimumAnchor, sustainableAnchorWeekly)
        }.coerceIn(0.0, total)

        val group = max(0.0, total - anchor)
        val restricted = anchor + 0.01 < desiredAnchor

        return ServiceAllocationResult(
            serviceId = input.serviceId,
            totalWeeklyDemandKg = total,
            anchorWeeklyKg = anchor,
            linkedGroupWeeklyKg = group,
            anchorWasRestrictedByStock = restricted,
            reasons = buildList {
                add("Demanda total del servicio conservada: ${String.format(java.util.Locale.US, "%.1f", total)} kg/sem")
                if (restricted) add("Róbalo restringido por cobertura; la diferencia pasa a Pargos")
                else add("Róbalo puede operar cerca de su participación normal")
            }
        )
    }

    fun pickFifoLots(lots: List<FifoLotSnapshot>, requestedKg: Double): List<LotPick> {
        var remaining = requestedKg.coerceAtLeast(0.0)
        val result = mutableListOf<LotPick>()
        lots.asSequence()
            .filter { it.currentKg > 0.01 }
            .sortedBy { it.effectiveReceivedAt()?.time ?: Long.MAX_VALUE }
            .forEach { lot ->
                if (remaining <= 0.01) return@forEach
                val kg = min(remaining, lot.currentKg)
                if (kg > 0.01) {
                    result.add(
                        LotPick(
                            lotId = lot.lotId,
                            productId = lot.productId,
                            kg = kg,
                            effectiveReceivedAt = lot.effectiveReceivedAt()
                        )
                    )
                    remaining -= kg
                }
            }
        return result
    }

    private fun normalizeShares(members: List<GroupMemberState>): Map<String, Double> {
        val raw = members.associate { it.productId to it.typicalShare.coerceAtLeast(0.0) }
        val total = raw.values.sum()
        if (total > 0.0) return raw.mapValues { it.value / total }
        if (members.isEmpty()) return emptyMap()
        val equal = 1.0 / members.size
        return members.associate { it.productId to equal }
    }

    private fun oldestAgeDays(lots: List<FifoLotSnapshot>, now: Date): Double {
        val oldest = lots.asSequence()
            .filter { it.currentKg > 0.01 }
            .mapNotNull { it.effectiveReceivedAt() }
            .minByOrNull { it.time }
            ?: return 0.0
        return max(0.0, (now.time - oldest.time) / 86_400_000.0)
    }
}
