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
import com.cesar.bocana.util.StockQuantityPolicy
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
     * Relación directa de APOYO entre un producto/lado directo y un grupo.
     *
     * NO conserva una bolsa fija de kg y NO convierte un faltante en compensación 1:1.
     * El producto directo conserva su propia predicción. Si su cobertura cae, el grupo
     * relacionado puede recibir una presión adicional únicamente en la proporción que
     * el histórico haya demostrado, con un límite de seguridad.
     */
    fun allocateService(input: ServiceAllocationInput): ServiceAllocationResult {
        val anchor = input.anchorForecastWeeklyKg.coerceAtLeast(0.0)
        val groupBase = input.linkedGroupForecastWeeklyKg.coerceAtLeast(0.0)

        val horizonDays = input.planningHorizonWeeks.coerceAtLeast(0.25) * 7.0
        val scarcity = when (val coverage = input.anchorCoverageDays) {
            null -> 0.0
            else -> when {
                coverage <= 0.0 -> 1.0
                coverage >= horizonDays -> 0.0
                else -> (1.0 - coverage / horizonDays).coerceIn(0.0, 1.0)
            }
        }

        val learnedUplift = input.learnedSupportUpliftPct
            .coerceIn(0.0, input.maxSupportUpliftPct.coerceAtLeast(0.0))

        val pressurePct = (learnedUplift * scarcity)
            .coerceIn(0.0, input.maxSupportUpliftPct.coerceAtLeast(0.0))

        val adjustedGroup = groupBase * (1.0 + pressurePct)
        val restricted = scarcity > 0.05

        return ServiceAllocationResult(
            serviceId = input.serviceId,
            totalWeeklyDemandKg = anchor + adjustedGroup,
            anchorWeeklyKg = anchor,
            linkedGroupWeeklyKg = adjustedGroup,
            anchorWasRestrictedByStock = restricted,
            supportPressurePct = pressurePct,
            reasons = buildList {
                add("Cada lado conserva su propia demanda; no existe reemplazo kilo por kilo")
                if (pressurePct > 0.005) {
                    add(
                        "La cobertura del producto directo está baja y el histórico sugiere " +
                            "aproximadamente ${String.format(java.util.Locale.US, "%.0f", pressurePct * 100.0)}% " +
                            "de presión adicional sobre el grupo"
                    )
                } else {
                    add("No se detecta presión adicional relevante sobre el grupo en este momento")
                }
            }
        )
    }


    fun pickFifoLots(lots: List<FifoLotSnapshot>, requestedKg: Double): List<LotPick> {
        var remaining = requestedKg.coerceAtLeast(0.0)
        val result = mutableListOf<LotPick>()
        lots.asSequence()
            .filter { StockQuantityPolicy.isUsable(it.currentKg) }
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
            .filter { StockQuantityPolicy.isUsable(it.currentKg) }
            .mapNotNull { it.effectiveReceivedAt() }
            .minByOrNull { it.time }
            ?: return 0.0
        return max(0.0, (now.time - oldest.time) / 86_400_000.0)
    }
}
