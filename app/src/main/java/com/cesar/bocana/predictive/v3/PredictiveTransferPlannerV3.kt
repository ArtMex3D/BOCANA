package com.cesar.bocana.predictive.v3

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.predictive.v3.data.PredictiveV3Time
import com.cesar.bocana.predictive.v3.model.DemandEntityType
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.ForecastContext
import com.cesar.bocana.predictive.v3.model.ForecastResultV3
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.cesar.bocana.predictive.v3.model.ServiceAllocationInput
import com.cesar.bocana.util.StockQuantityPolicy
import java.util.Calendar
import java.util.Date
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

enum class TransferReasonCode {
    C04_COVERED,
    FIFO_OLDEST,
    SAME_COHORT_ABUNDANCE,
    PRIMARY_MINIMUM,
    PRIMARY_SHORTAGE,
    GROUP_DYNAMIC_UP,
    GROUP_DYNAMIC_DOWN,
    SUPPORT_PRODUCT_LOW,
    NEXT_COHORT_USED,
    NO_NEARBY_MEMBER,
    PACKAGING_SHORTAGE,
    PACKAGING_PENDING_AVAILABLE,
    MATRIX_RESERVE,
    USER_OVERRIDE
}

data class TransferProductPlanV3(
    val productId: String,
    val requestedKg: Double,
    val originalSuggestedKg: Double,
    val availablePackagedKg: Double,
    val pendingPackagingKg: Double,
    val dynamicTargetC04Kg: Double,
    val habitualTargetC04Kg: Double,
    val groupId: String? = null,
    val groupName: String? = null,
    val groupHabitualTargetKg: Double? = null,
    val groupDynamicTargetKg: Double? = null,
    val isGroupPrimary: Boolean = false,
    val showGroupHeader: Boolean = false,
    val isManualOverride: Boolean = false,
    val reasonCodes: List<TransferReasonCode> = emptyList(),
    val message: String? = null
)

data class TransferGroupPlanV3(
    val groupId: String,
    val groupName: String,
    val habitualTargetKg: Double,
    val dynamicTargetKg: Double,
    val currentC04Kg: Double,
    val requestedTransferKg: Double,
    val allocatedIntentKg: Double,
    val remainingKg: Double,
    val primaryProductId: String?,
    val reasonCodes: List<TransferReasonCode> = emptyList(),
    val message: String? = null
)

data class TransferPlanV3(
    val products: Map<String, TransferProductPlanV3>,
    val groups: Map<String, TransferGroupPlanV3>,
    val calculatedAt: Date
)

/**
 * Planificador sugerente de Traspasos.
 *
 * Es PURO: no lee ni escribe Firestore/Room. Recibe una fotografía y devuelve sugerencias.
 * La UI conserva cajas/costales, selector manual, PDF y decisiones humanas.
 */
object PredictiveTransferPlannerV3 {

    private const val HISTORY_WEEKS = 16
    private const val COHORT_DAYS = 7.0
    private const val MAX_DYNAMIC_FACTOR = 1.80
    private const val MIN_DYNAMIC_FACTOR = 0.60

    data class Snapshot(
        val products: List<Product>,
        val groups: List<PredictiveGroupConfig>,
        val services: List<PredictiveServiceRelation>,
        val openLots: List<StockLot>,
        val pendingPackaging: List<PendingPackagingTask>,
        val checkpoints: Map<String, Double>,
        val now: Date = Date()
    )

    fun plan(
        snapshot: Snapshot,
        manualOverridesKg: Map<String, Double> = emptyMap()
    ): TransferPlanV3 {
        val productsById = snapshot.products.associateBy { it.id }
        val allMatrizLots = snapshot.openLots
            .filter {
                it.location == Location.MATRIZ &&
                        !it.isDepleted &&
                        it.estadoTraspaso == null &&
                        StockQuantityPolicy.isUsable(it.currentQuantity)
            }
            .groupBy { it.productId }
            .mapValues { (_, lots) ->
                lots.sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE }
            }

        val packagedMatrizLots = allMatrizLots
            .mapValues { (_, lots) -> lots.filter { it.isPackaged } }

        val pendingByProduct = snapshot.pendingPackaging
            .groupBy { it.productId }
            .mapValues { (_, tasks) -> tasks.sumOf { it.quantityReceived.coerceAtLeast(0.0) } }

        val currentWeek = PredictiveV3Time.currentWeek(snapshot.now)
        val completeWeeks = PredictiveV3Time.completeWeeks(snapshot.now, HISTORY_WEEKS)
        val seasonalWeeks = PredictiveV3Time.seasonalWeeks(snapshot.now)
        val targetWindowDays = PredictiveV3Time.targetWindowDays(snapshot.now)
        val elapsedDays = PredictiveV3Time.elapsedDaysInCurrentWeek(snapshot.now)
        val regime = PredictiveV3Time.regime(snapshot.now)

        val groupedIds = snapshot.groups
            .flatMap { it.memberProductIds }
            .toSet()

        val productPlans = linkedMapOf<String, TransferProductPlanV3>()
        val groupPlans = linkedMapOf<String, TransferGroupPlanV3>()

        snapshot.groups.filter { it.enabled }.forEach { group ->
            val members = group.memberProductIds.mapNotNull(productsById::get)
            if (members.isEmpty()) return@forEach

            val historicalIds = (group.memberProductIds + group.historicalProductIds)
                .filter { it.isNotBlank() }
                .distinct()

            val groupSeries = aggregateSeries(
                productIds = historicalIds,
                weeks = completeWeeks,
                checkpoints = snapshot.checkpoints
            )
            val currentConsumed = members.sumOf {
                snapshot.checkpoints[currentWeek.checkpointId(it.id)] ?: 0.0
            }
            val seasonalReference = averageExistingGroup(
                productIds = historicalIds,
                weeks = seasonalWeeks,
                checkpoints = snapshot.checkpoints
            )

            val groupStockC04 = members.sumOf { it.stockCongelador04.coerceAtLeast(0.0) }
            val groupStockMatriz = members.sumOf { it.stockMatriz.coerceAtLeast(0.0) }
            val groupTotal = members.sumOf { it.totalStock.coerceAtLeast(0.0) }
            val groupReserve = members.sumOf(::matrixReserveForCommitment)
            val habitualGroupTarget = group.c04GroupTargetKg.coerceAtLeast(0.0)

            val groupForecast = PredictiveV3Engine.forecast(
                ForecastContext(
                    entityId = group.id,
                    entityType = DemandEntityType.GROUP,
                    completeWeeks = groupSeries,
                    currentWeekConsumedKg = currentConsumed,
                    currentWeekElapsedDays = elapsedDays,
                    targetWindowDays = targetWindowDays,
                    stockC04Kg = groupStockC04,
                    stockMatrizKg = groupStockMatriz,
                    stockTotalKg = groupTotal,
                    generalReserveKg = groupReserve,
                    legacyC04ReferenceKg = habitualGroupTarget,
                    seasonalReferenceWeeklyKg = seasonalReference,
                    regime = regime
                )
            )

            val service = snapshot.services.firstOrNull {
                it.enabled && it.linkedGroupId == group.id
            }
            val serviceAdjustedWeekly = service?.let {
                adjustedGroupWeeklyFromService(
                    relation = it,
                    groupForecast = groupForecast,
                    productsById = productsById,
                    groupHistoricalIds = historicalIds,
                    completeWeeks = completeWeeks,
                    currentWeek = currentWeek,
                    targetWindowDays = targetWindowDays,
                    elapsedDays = elapsedDays,
                    regime = regime,
                    checkpoints = snapshot.checkpoints
                )
            } ?: groupForecast.forecastWeeklyKg

            val groupDynamicTarget = adaptiveTarget(
                habitualKg = habitualGroupTarget,
                forecast = groupForecast,
                effectiveWeeklyKg = serviceAdjustedWeekly,
                targetWindowDays = targetWindowDays
            )

            val primaryMinimumNeed = group.primaryProductId
                ?.let(productsById::get)
                ?.let { primary ->
                    max(
                        0.0,
                        group.primaryMinimumC04Kg.coerceAtLeast(0.0) -
                                primary.stockCongelador04.coerceAtLeast(0.0)
                    )
                }
                ?: 0.0

            // El objetivo grupal no puede esconder un rector vacío. Si el grupo ya suma
            // suficiente por H.M./V.J./otros, H.O. todavía recupera su mínimo operativo
            // cuando exista mercancía transferible. Esto puede dejar el grupo temporalmente
            // por encima del objetivo habitual y es intencional.
            val rawGroupNeed = max(
                max(0.0, groupDynamicTarget - groupStockC04),
                primaryMinimumNeed
            )

            val groupOverrides = manualOverridesKg
                .filterKeys { group.memberProductIds.contains(it) }
                .mapValues { (_, kg) -> kg.coerceAtLeast(0.0) }

            // Traspasos sólo puede proponer mercancía físicamente lista para salir.
            // Lo no empacado sigue visible como señal/aviso, pero NO participa en el reparto.
            val allocation = allocateGroupByCohort(
                group = group,
                members = members,
                matrizLotsByProduct = packagedMatrizLots,
                groupNeedKg = rawGroupNeed,
                overridesKg = groupOverrides
            )

            val groupCodes = mutableListOf<TransferReasonCode>()
            if (habitualGroupTarget > 0.01) {
                when {
                    groupDynamicTarget > habitualGroupTarget * 1.08 ->
                        groupCodes += TransferReasonCode.GROUP_DYNAMIC_UP
                    groupDynamicTarget < habitualGroupTarget * 0.92 ->
                        groupCodes += TransferReasonCode.GROUP_DYNAMIC_DOWN
                }
            }
            if (serviceAdjustedWeekly > groupForecast.forecastWeeklyKg * 1.01) {
                groupCodes += TransferReasonCode.SUPPORT_PRODUCT_LOW
            }
            if (allocation.remainingKg > 0.1) {
                groupCodes += TransferReasonCode.NO_NEARBY_MEMBER
            }

            val primaryName = group.primaryProductId?.let(productsById::get)?.name
            val groupMessage = TransferMessageFactory.groupMessage(
                groupName = group.name,
                codes = groupCodes,
                habitualKg = habitualGroupTarget,
                dynamicKg = groupDynamicTarget,
                primaryName = primaryName
            )

            groupPlans[group.id] = TransferGroupPlanV3(
                groupId = group.id,
                groupName = group.name,
                habitualTargetKg = habitualGroupTarget,
                dynamicTargetKg = groupDynamicTarget,
                currentC04Kg = groupStockC04,
                requestedTransferKg = rawGroupNeed,
                allocatedIntentKg = allocation.byProduct.values.sum(),
                remainingKg = allocation.remainingKg,
                primaryProductId = group.primaryProductId,
                reasonCodes = groupCodes.distinct(),
                message = groupMessage
            )

            val orderedMembers = members.sortedWith(
                compareBy<Product> {
                    when {
                        it.id == group.primaryProductId -> 0
                        group.secondaryProductIds.contains(it.id) -> 1
                        else -> 2
                    }
                }.thenBy { it.name.lowercase() }
            )

            orderedMembers.forEachIndexed { index, product ->
                val desiredKg = allocation.byProduct[product.id] ?: 0.0
                val packaged = availablePackagedWithinReserve(
                    product,
                    packagedMatrizLots[product.id].orEmpty()
                )
                val pending = pendingByProduct[product.id] ?: 0.0
                val codes = allocation.codesByProduct[product.id].orEmpty().toMutableList()
                if (groupOverrides.containsKey(product.id)) {
                    codes += TransferReasonCode.USER_OVERRIDE
                }
                if (desiredKg > packaged + 0.1) {
                    codes += TransferReasonCode.PACKAGING_SHORTAGE
                    if (pending > 0.1) codes += TransferReasonCode.PACKAGING_PENDING_AVAILABLE
                } else if (allocation.remainingKg > 0.1 && pending > 0.1) {
                    // El grupo necesita más, pero este producto sólo puede ayudar después de empacarse.
                    // Se informa; nunca se contabiliza como transferible.
                    codes += TransferReasonCode.PACKAGING_SHORTAGE
                    codes += TransferReasonCode.PACKAGING_PENDING_AVAILABLE
                }
                if (desiredKg <= 0.01 && product.stockCongelador04 > 0.0 && groupStockC04 >= groupDynamicTarget - 0.1) {
                    codes += TransferReasonCode.C04_COVERED
                }

                productPlans[product.id] = TransferProductPlanV3(
                    productId = product.id,
                    requestedKg = desiredKg,
                    originalSuggestedKg = allocation.originalByProduct[product.id] ?: desiredKg,
                    availablePackagedKg = packaged,
                    pendingPackagingKg = pending,
                    dynamicTargetC04Kg = groupDynamicTarget,
                    habitualTargetC04Kg = product.stockIdealC04.coerceAtLeast(0.0),
                    groupId = group.id,
                    groupName = group.name,
                    groupHabitualTargetKg = habitualGroupTarget,
                    groupDynamicTargetKg = groupDynamicTarget,
                    isGroupPrimary = product.id == group.primaryProductId,
                    showGroupHeader = index == 0,
                    isManualOverride = groupOverrides.containsKey(product.id),
                    reasonCodes = codes.distinct(),
                    message = TransferMessageFactory.productMessage(
                        productName = product.name,
                        codes = codes.distinct(),
                        pendingPackagingKg = pending,
                        availablePackagedKg = packaged
                    )
                )
            }
        }

        snapshot.products
            .filterNot { groupedIds.contains(it.id) }
            .forEach { product ->
                val forecast = forecastProduct(
                    product = product,
                    completeWeeks = completeWeeks,
                    currentWeek = currentWeek,
                    seasonalWeeks = seasonalWeeks,
                    checkpoints = snapshot.checkpoints,
                    targetWindowDays = targetWindowDays,
                    elapsedDays = elapsedDays,
                    regime = regime
                )

                val dynamicTarget = adaptiveTarget(
                    habitualKg = product.stockIdealC04.coerceAtLeast(0.0),
                    forecast = forecast,
                    effectiveWeeklyKg = forecast.forecastWeeklyKg,
                    targetWindowDays = targetWindowDays
                )
                val need = max(0.0, dynamicTarget - product.stockCongelador04.coerceAtLeast(0.0))
                val packaged = availablePackagedWithinReserve(
                    product,
                    packagedMatrizLots[product.id].orEmpty()
                )
                val usableMatriz = max(
                    0.0,
                    product.stockMatriz.coerceAtLeast(0.0) - matrixReserveForCommitment(product)
                )
                val intelligentNeed = min(need, usableMatriz)
                val original = intelligentNeed
                val requested = manualOverridesKg[product.id]?.coerceAtLeast(0.0) ?: intelligentNeed
                val pending = pendingByProduct[product.id] ?: 0.0

                val codes = mutableListOf<TransferReasonCode>()
                if (manualOverridesKg.containsKey(product.id)) codes += TransferReasonCode.USER_OVERRIDE
                if (need <= 0.01) codes += TransferReasonCode.C04_COVERED
                if (requested > packaged + 0.1) {
                    codes += TransferReasonCode.PACKAGING_SHORTAGE
                    if (pending > 0.1) codes += TransferReasonCode.PACKAGING_PENDING_AVAILABLE
                }
                if (need > packaged + 0.1 && matrixReserveForCommitment(product) > 0.01) {
                    codes += TransferReasonCode.MATRIX_RESERVE
                }

                productPlans[product.id] = TransferProductPlanV3(
                    productId = product.id,
                    requestedKg = requested,
                    originalSuggestedKg = original,
                    availablePackagedKg = packaged,
                    pendingPackagingKg = pending,
                    dynamicTargetC04Kg = dynamicTarget,
                    habitualTargetC04Kg = product.stockIdealC04.coerceAtLeast(0.0),
                    isManualOverride = manualOverridesKg.containsKey(product.id),
                    reasonCodes = codes.distinct(),
                    message = TransferMessageFactory.productMessage(
                        productName = product.name,
                        codes = codes.distinct(),
                        pendingPackagingKg = pending,
                        availablePackagedKg = packaged
                    )
                )
            }

        return TransferPlanV3(
            products = productPlans,
            groups = groupPlans,
            calculatedAt = snapshot.now
        )
    }

    private data class GroupAllocation(
        val byProduct: Map<String, Double>,
        val originalByProduct: Map<String, Double>,
        val codesByProduct: Map<String, List<TransferReasonCode>>,
        val remainingKg: Double
    )

    private fun allocateGroupByCohort(
        group: PredictiveGroupConfig,
        members: List<Product>,
        matrizLotsByProduct: Map<String, List<StockLot>>,
        groupNeedKg: Double,
        overridesKg: Map<String, Double>
    ): GroupAllocation {
        val membersById = members.associateBy { it.id }
        val allocated = linkedMapOf<String, Double>()
        val codes = linkedMapOf<String, MutableList<TransferReasonCode>>()

        overridesKg.forEach { (productId, kg) ->
            if (membersById.containsKey(productId)) {
                allocated[productId] = kg.coerceAtLeast(0.0)
                codes.getOrPut(productId) { mutableListOf() } += TransferReasonCode.USER_OVERRIDE
            }
        }

        var remaining = max(0.0, groupNeedKg - allocated.values.sum())

        val capacities = members.associate { product ->
            product.id to availableMatrizWithinReserve(
                product,
                matrizLotsByProduct[product.id].orEmpty()
            )
        }.toMutableMap()

        // Una cantidad manual es EXACTA para esta recalculación: el usuario puede volver a
        // editarla cuando quiera, pero V3 no añade más kilos a esa misma fila a escondidas.
        // La intención puede incluso superar la disponibilidad física; la UI avisará/bloqueará
        // el PDF y redistribuirá únicamente entre los demás miembros del grupo.
        overridesKg.keys.forEach { productId ->
            capacities[productId] = 0.0
        }

        val primaryId = group.primaryProductId
        val primary = primaryId?.let(membersById::get)

        // El rector conserva su mínimo operativo siempre que haya mercancía transferible,
        // incluso si un override manual de H.M./V.J./otro miembro ya cubrió o superó
        // el objetivo grupal. La única excepción es cuando el usuario editó directamente
        // al rector: en ese caso su cantidad manual se respeta exactamente.
        //
        // Por eso este refuerzo NO se limita por `remaining`: una decisión humana sobre otro
        // miembro puede dejar el total por encima del objetivo dinámico, pero no debe sacrificar
        // el mínimo de H.O. a escondidas.
        if (primary != null && !overridesKg.containsKey(primary.id)) {
            val missingPrimary = max(
                0.0,
                group.primaryMinimumC04Kg.coerceAtLeast(0.0) -
                        primary.stockCongelador04.coerceAtLeast(0.0)
            )
            val forced = min(missingPrimary, capacities[primary.id] ?: 0.0)
            if (forced > 0.01) {
                allocated[primary.id] = (allocated[primary.id] ?: 0.0) + forced
                capacities[primary.id] = max(0.0, (capacities[primary.id] ?: 0.0) - forced)
                codes.getOrPut(primary.id) { mutableListOf() } += TransferReasonCode.PRIMARY_MINIMUM
            }
            if (missingPrimary > forced + 0.1) {
                codes.getOrPut(primary.id) { mutableListOf() } += TransferReasonCode.PRIMARY_SHORTAGE
            }

            // Recalcular después de proteger al rector. Si la edición manual + mínimo H.O.
            // ya exceden el objetivo del grupo, no quitamos kilos de ninguna fila: el usuario
            // podrá volver a editar y V3 recalculará de nuevo.
            remaining = max(0.0, groupNeedKg - allocated.values.sum())
        }

        if (remaining <= 0.01) {
            return GroupAllocation(
                byProduct = allocated,
                originalByProduct = allocated.toMap(),
                codesByProduct = codes,
                remainingKg = 0.0
            )
        }

        data class LotCandidate(
            val productId: String,
            val availableKg: Double,
            val date: Date?,
            val lot: StockLot
        )

        val candidates = members.flatMap { product ->
            val productCapacity = capacities[product.id] ?: 0.0
            var capRemaining = productCapacity
            matrizLotsByProduct[product.id].orEmpty().mapNotNull { lot ->
                if (capRemaining <= 0.01) return@mapNotNull null
                val kg = min(capRemaining, lot.currentQuantity.coerceAtLeast(0.0))
                if (kg <= 0.01) return@mapNotNull null
                capRemaining -= kg
                LotCandidate(
                    productId = product.id,
                    availableKg = kg,
                    date = effectiveDate(lot),
                    lot = lot
                )
            }
        }.sortedBy { it.date?.time ?: Long.MAX_VALUE }

        val consumedLots = mutableSetOf<String>()
        var cohortIndex = 0

        while (remaining > 0.01) {
            val first = candidates.firstOrNull {
                !consumedLots.contains(it.lot.id) &&
                        (capacities[it.productId] ?: 0.0) > 0.01
            } ?: break

            val startTime = first.date?.time
            val cohort = candidates.filter { candidate ->
                if (consumedLots.contains(candidate.lot.id)) return@filter false
                if ((capacities[candidate.productId] ?: 0.0) <= 0.01) return@filter false

                if (startTime == null || candidate.date == null) {
                    candidate.lot.id == first.lot.id
                } else {
                    val days = abs(candidate.date.time - startTime) / 86_400_000.0
                    days <= COHORT_DAYS
                }
            }

            if (cohort.isEmpty()) break
            if (cohortIndex > 0) {
                cohort.forEach {
                    codes.getOrPut(it.productId) { mutableListOf() } += TransferReasonCode.NEXT_COHORT_USED
                }
            }

            val availableByProduct = cohort
                .groupBy { it.productId }
                .mapValues { (productId, lots) ->
                    min(
                        capacities[productId] ?: 0.0,
                        lots.sumOf { it.availableKg }
                    )
                }
                .filterValues { it > 0.01 }

            if (availableByProduct.isEmpty()) {
                cohort.forEach { consumedLots += it.lot.id }
                cohortIndex++
                continue
            }

            val totalCohort = availableByProduct.values.sum()
            val takeThisCohort = min(remaining, totalCohort)

            val weighted = availableByProduct.mapValues { (productId, kg) ->
                val role = when {
                    productId == group.primaryProductId -> 1.12
                    group.secondaryProductIds.contains(productId) -> 1.06
                    else -> 1.0
                }
                kg * role
            }
            val scoreTotal = weighted.values.sum().takeIf { it > 0.0 } ?: 1.0

            var roundTaken = 0.0
            val ordered = availableByProduct.keys.sortedByDescending { weighted[it] ?: 0.0 }

            ordered.forEachIndexed { index, productId ->
                if (remaining <= 0.01) return@forEachIndexed
                val capacity = capacities[productId] ?: 0.0
                if (capacity <= 0.01) return@forEachIndexed

                val cohortCapacity = availableByProduct[productId] ?: 0.0
                val target = if (index == ordered.lastIndex) {
                    max(0.0, takeThisCohort - roundTaken)
                } else {
                    takeThisCohort * ((weighted[productId] ?: 0.0) / scoreTotal)
                }
                val kg = min(capacity, min(cohortCapacity, target.coerceAtLeast(0.0)))
                if (kg > 0.01) {
                    allocated[productId] = (allocated[productId] ?: 0.0) + kg
                    capacities[productId] = max(0.0, capacity - kg)
                    remaining -= kg
                    roundTaken += kg

                    codes.getOrPut(productId) { mutableListOf() } += TransferReasonCode.FIFO_OLDEST
                    val maxAvailable = availableByProduct.maxByOrNull { it.value }
                    if (maxAvailable?.key == productId &&
                        availableByProduct.size > 1 &&
                        cohortCapacity > (totalCohort / availableByProduct.size) * 1.35
                    ) {
                        codes.getOrPut(productId) { mutableListOf() } += TransferReasonCode.SAME_COHORT_ABUNDANCE
                    }
                }
            }

            cohort.forEach { consumedLots += it.lot.id }
            cohortIndex++

            if (roundTaken <= 0.001) break
        }

        val originals = allocated.toMap()
        return GroupAllocation(
            byProduct = allocated,
            originalByProduct = originals,
            codesByProduct = codes.mapValues { it.value.distinct() },
            remainingKg = remaining.coerceAtLeast(0.0)
        )
    }

    private fun forecastProduct(
        product: Product,
        completeWeeks: List<PredictiveV3Time.WeekRef>,
        currentWeek: PredictiveV3Time.WeekRef,
        seasonalWeeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>,
        targetWindowDays: Double,
        elapsedDays: Double,
        regime: com.cesar.bocana.predictive.v3.model.SeasonRegime
    ): ForecastResultV3 {
        return PredictiveV3Engine.forecast(
            ForecastContext(
                entityId = product.id,
                entityType = DemandEntityType.PRODUCT,
                completeWeeks = seriesForProduct(product.id, completeWeeks, checkpoints),
                currentWeekConsumedKg = checkpoints[currentWeek.checkpointId(product.id)] ?: 0.0,
                currentWeekElapsedDays = elapsedDays,
                targetWindowDays = targetWindowDays,
                stockC04Kg = product.stockCongelador04,
                stockMatrizKg = product.stockMatriz,
                stockTotalKg = product.totalStock,
                generalReserveKg = matrixReserveForCommitment(product),
                legacyC04ReferenceKg = product.stockIdealC04,
                seasonalReferenceWeeklyKg = averageExisting(product.id, seasonalWeeks, checkpoints),
                regime = regime
            )
        )
    }

    private fun adjustedGroupWeeklyFromService(
        relation: PredictiveServiceRelation,
        groupForecast: ForecastResultV3,
        productsById: Map<String, Product>,
        groupHistoricalIds: List<String>,
        completeWeeks: List<PredictiveV3Time.WeekRef>,
        currentWeek: PredictiveV3Time.WeekRef,
        targetWindowDays: Double,
        elapsedDays: Double,
        regime: com.cesar.bocana.predictive.v3.model.SeasonRegime,
        checkpoints: Map<String, Double>
    ): Double {
        val anchorIds = relation.effectiveAnchorProductIds()
        val anchors = anchorIds.mapNotNull(productsById::get)
        if (anchors.isEmpty()) return groupForecast.forecastWeeklyKg

        val anchorHistoryIds = relation.allHistoricalAnchorIds()
        val anchorSeries = aggregateSeries(anchorHistoryIds, completeWeeks, checkpoints)
        val anchorCurrent = anchors.sumOf {
            checkpoints[currentWeek.checkpointId(it.id)] ?: 0.0
        }

        val anchorForecast = PredictiveV3Engine.forecast(
            ForecastContext(
                entityId = "${relation.id}_DIRECT",
                entityType = DemandEntityType.SERVICE,
                completeWeeks = anchorSeries,
                currentWeekConsumedKg = anchorCurrent,
                currentWeekElapsedDays = elapsedDays,
                targetWindowDays = targetWindowDays,
                stockC04Kg = anchors.sumOf { it.stockCongelador04 },
                stockMatrizKg = anchors.sumOf { it.stockMatriz },
                stockTotalKg = anchors.sumOf { it.totalStock },
                generalReserveKg = anchors.sumOf(::matrixReserveForCommitment),
                seasonalReferenceWeeklyKg = null,
                regime = regime
            )
        )

        val weeklyPairs = completeWeeks.map { week ->
            val anchorKg = anchorHistoryIds.sumOf { id ->
                checkpoints[week.checkpointId(id)] ?: 0.0
            }
            val groupKg = groupHistoricalIds.sumOf { id ->
                checkpoints[week.checkpointId(id)] ?: 0.0
            }
            anchorKg to groupKg
        }.filter { (a, g) -> a + g > 0.01 }

        val learnedUplift = learnedSupportUplift(weeklyPairs)
        val allocation = PredictiveGroupEngine.allocateService(
            ServiceAllocationInput(
                serviceId = relation.id,
                anchorForecastWeeklyKg = anchorForecast.forecastWeeklyKg,
                linkedGroupForecastWeeklyKg = groupForecast.forecastWeeklyKg,
                anchorCoverageDays = anchorForecast.coverageDays,
                learnedSupportUpliftPct = learnedUplift,
                planningHorizonWeeks = 2.0,
                maxSupportUpliftPct = 0.50
            )
        )
        return allocation.linkedGroupWeeklyKg
    }

    private fun learnedSupportUplift(
        weeklyPairs: List<Pair<Double, Double>>
    ): Double {
        if (weeklyPairs.size < 4) return 0.0

        val anchorValues = weeklyPairs.map { it.first }
        val lowThreshold = percentile(anchorValues, 0.35)
        val normalThreshold = percentile(anchorValues, 0.65)

        val groupWhenLow = weeklyPairs
            .filter { it.first <= lowThreshold }
            .map { it.second }
        val groupWhenNormal = weeklyPairs
            .filter { it.first >= normalThreshold }
            .map { it.second }

        if (groupWhenLow.size < 2 || groupWhenNormal.size < 2) return 0.0

        val low = median(groupWhenLow)
        val normal = median(groupWhenNormal)
        if (normal <= 0.01) return 0.0

        return (low / normal - 1.0).coerceIn(0.0, 0.60)
    }

    private fun adaptiveTarget(
        habitualKg: Double,
        forecast: ForecastResultV3,
        effectiveWeeklyKg: Double,
        targetWindowDays: Double
    ): Double {
        val windowTarget = if (effectiveWeeklyKg > 0.0) {
            effectiveWeeklyKg / 7.0 * (targetWindowDays + forecast.safetyDays)
        } else {
            0.0
        }

        if (habitualKg <= 0.01) return windowTarget

        val baseline = forecast.baselineWeeklyKg
        val factor = if (baseline > 0.01) {
            (effectiveWeeklyKg / baseline)
                .coerceIn(MIN_DYNAMIC_FACTOR, MAX_DYNAMIC_FACTOR)
        } else {
            1.0
        }

        val adaptiveHabitual = habitualKg * factor
        return max(windowTarget, adaptiveHabitual)
    }

    private fun availableMatrizWithinReserve(
        product: Product,
        matrizLots: List<StockLot>
    ): Double {
        val lotKg = matrizLots
            .filter { StockQuantityPolicy.isUsable(it.currentQuantity) }
            .sumOf { it.currentQuantity.coerceAtLeast(0.0) }

        val usableMatriz = max(
            0.0,
            product.stockMatriz.coerceAtLeast(0.0) - matrixReserveForCommitment(product)
        )
        return min(lotKg, usableMatriz)
    }

    private fun availablePackagedWithinReserve(
        product: Product,
        packagedLots: List<StockLot>
    ): Double {
        val packagedKg = packagedLots
            .filter { StockQuantityPolicy.isUsable(it.currentQuantity) }
            .sumOf { it.currentQuantity.coerceAtLeast(0.0) }

        val usableMatriz = max(
            0.0,
            product.stockMatriz.coerceAtLeast(0.0) - matrixReserveForCommitment(product)
        )
        return min(packagedKg, usableMatriz)
    }

    private fun matrixReserveForCommitment(product: Product): Double =
        max(0.0, product.minStock - product.stockCongelador04)
            .coerceAtMost(product.stockMatriz.coerceAtLeast(0.0))

    private fun aggregateSeries(
        productIds: List<String>,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): List<DemandPoint> {
        return weeks.mapNotNull { week ->
            var found = false
            var total = 0.0
            productIds.forEach { id ->
                checkpoints[week.checkpointId(id)]?.let {
                    found = true
                    total += it
                }
            }
            if (found) DemandPoint(week.key, total) else null
        }
    }

    private fun seriesForProduct(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): List<DemandPoint> =
        weeks.mapNotNull { week ->
            checkpoints[week.checkpointId(productId)]?.let {
                DemandPoint(week.key, it)
            }
        }

    private fun averageExisting(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): Double? =
        weeks.mapNotNull { checkpoints[it.checkpointId(productId)] }
            .takeIf { it.isNotEmpty() }
            ?.average()

    private fun averageExistingGroup(
        productIds: List<String>,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): Double? {
        val values = weeks.mapNotNull { week ->
            var found = false
            var total = 0.0
            productIds.forEach { id ->
                checkpoints[week.checkpointId(id)]?.let {
                    found = true
                    total += it
                }
            }
            if (found) total else null
        }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun effectiveDate(lot: StockLot): Date? =
        lot.originalReceivedAt ?: lot.receivedAt

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) {
            (sorted[mid - 1] + sorted[mid]) / 2.0
        } else {
            sorted[mid]
        }
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[index]
    }
}

object TransferMessageFactory {

    fun productMessage(
        productName: String,
        codes: List<TransferReasonCode>,
        pendingPackagingKg: Double,
        availablePackagedKg: Double
    ): String? {
        val set = codes.toSet()
        return when {
            set.contains(TransferReasonCode.PACKAGING_SHORTAGE) &&
                    set.contains(TransferReasonCode.PACKAGING_PENDING_AVAILABLE) ->
                "Disponible empacado: ${format1(availablePackagedKg)} kg. Hay ${format1(pendingPackagingKg)} kg pendientes de empacar."

            set.contains(TransferReasonCode.PRIMARY_SHORTAGE) ->
                "$productName está por debajo del mínimo del rector con el stock disponible."

            set.contains(TransferReasonCode.SAME_COHORT_ABUNDANCE) ->
                "Se priorizó $productName por mayor existencia en la mercancía más antigua."

            set.contains(TransferReasonCode.PRIMARY_MINIMUM) ->
                "Se conservó el mínimo operativo de $productName en C04."

            set.contains(TransferReasonCode.NEXT_COHORT_USED) ->
                "El lote más antiguo no alcanzó; se completó con la siguiente fecha disponible."

            set.contains(TransferReasonCode.USER_OVERRIDE) ->
                "Cantidad ajustada por ti; V3 recalculó el resto sin cambiar esta decisión."

            set.contains(TransferReasonCode.C04_COVERED) ->
                "C04 ya cubre la necesidad estimada hasta el próximo traspaso."

            else -> null
        }
    }

    fun groupMessage(
        groupName: String,
        codes: List<TransferReasonCode>,
        habitualKg: Double,
        dynamicKg: Double,
        primaryName: String?
    ): String? {
        val set = codes.toSet()
        return when {
            set.contains(TransferReasonCode.SUPPORT_PRODUCT_LOW) ->
                "$groupName aumentó porque el producto de apoyo está por debajo de su nivel habitual."

            set.contains(TransferReasonCode.GROUP_DYNAMIC_UP) ->
                "Objetivo habitual ${format1(habitualKg)} kg; V3 sugiere ${format1(dynamicKg)} kg hoy."

            set.contains(TransferReasonCode.GROUP_DYNAMIC_DOWN) ->
                "V3 redujo temporalmente el objetivo por menor demanda reciente."

            set.contains(TransferReasonCode.NO_NEARBY_MEMBER) ->
                "No hay suficiente mercancía empacada de fechas cercanas para completar el objetivo."

            primaryName != null && habitualKg > 0.0 ->
                "$primaryName rige el equilibrio; el resto se reparte por FIFO y existencia."

            else -> null
        }
    }

    private fun format1(value: Double): String =
        String.format(java.util.Locale.US, "%.1f", value)
}
