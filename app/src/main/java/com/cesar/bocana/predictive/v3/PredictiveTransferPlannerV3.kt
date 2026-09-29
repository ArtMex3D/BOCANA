package com.cesar.bocana.predictive.v3

import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.PendingPackagingTask
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.predictive.v3.data.PredictiveV3Time
import com.cesar.bocana.predictive.v3.data.PredictiveV3Snapshot
import com.cesar.bocana.predictive.v3.model.DemandEntityType
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.ConfidenceLevel
import com.cesar.bocana.predictive.v3.model.ForecastContext
import com.cesar.bocana.predictive.v3.model.ForecastResultV3
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.cesar.bocana.predictive.v3.model.ServiceAllocationInput
import com.cesar.bocana.predictive.v3.model.TrendSignal
import com.cesar.bocana.util.StockQuantityPolicy
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

enum class TransferReasonCode {
    C04_COVERED,
    C04_BELOW_HABITUAL,
    FIFO_OLDEST,
    SAME_COHORT_ABUNDANCE,
    PRIMARY_MINIMUM,
    PRIMARY_SHORTAGE,
    GROUP_DYNAMIC_UP,
    GROUP_DYNAMIC_DOWN,
    SUPPORT_PRODUCT_LOW,
    SERVICE_SUPPORT_APPLIED,
    REVERSE_SERVICE_SUPPORT,
    NEXT_COHORT_USED,
    NO_NEARBY_MEMBER,
    PACKAGING_SHORTAGE,
    PACKAGING_PENDING_AVAILABLE,
    MATRIX_RESERVE,
    LIVE_SPIKE_GUARDED,
    ROLE_SAME_DAY_BALANCE,
    SECONDARY_COMPENSATION,
    OLDER_SUPPORT_LOT,
    RECTOR_CURSOR_ADVANCED,
    RECTOR_CURSOR_BACKLOG,
    NORMAL_TARGET_GUARD,
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
    // Campos diagnósticos: no cambian la sugerencia, sólo explican de dónde salió.
    val baselineWeeklyKg: Double = 0.0,
    val forecastWeeklyKg: Double = 0.0,
    val seasonalReferenceWeeklyKg: Double? = null,
    val regimeName: String = "NORMAL",
    val rawDynamicTargetC04Kg: Double = dynamicTargetC04Kg,
    val serviceSupportExtraKg: Double = 0.0,
    // Lotes autorizados por el reparto automático. La UI no debe saltar a fechas posteriores.
    val suggestedLotIds: List<String> = emptyList(),
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
    val rawDynamicTargetKg: Double = dynamicTargetKg,
    // Campos diagnósticos: permiten verificar predicción/estacionalidad sin recalcular.
    val baselineWeeklyKg: Double = 0.0,
    val forecastWeeklyKg: Double = 0.0,
    val effectiveWeeklyKg: Double = 0.0,
    val seasonalReferenceWeeklyKg: Double? = null,
    val regimeName: String = "NORMAL",
    val serviceId: String? = null,
    val preferredSupportProductId: String? = null,
    val baseOperationalTargetKg: Double = dynamicTargetKg,
    val serviceSupportCandidateKg: Double = 0.0,
    val serviceSupportExtraKg: Double = 0.0,
    val serviceAnchorResidualKg: Double = 0.0,
    val reverseSupportKg: Double = 0.0,
    val allocationTrace: List<String> = emptyList(),
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
        // Resultado persistente del predictor central, cargado desde Room.
        // Si está disponible evita releer todo el histórico remoto para abrir Traspasos.
        val persistedPredictions: Map<String, PredictiveV3Snapshot> = emptyMap(),
        val productionAdvanceProductIds: Set<String> = emptySet(),
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

        // Compensación inversa opcional de una misma relación de equilibrio.
        // Se calcula una sola vez por grupo y se aplica después a productos individuales,
        // evitando crear una segunda relación que pudiera formar un bucle.
        val reverseServiceExtraByProduct = linkedMapOf<String, Double>()

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

            val persistedGroupPrediction = members
                .asSequence()
                .mapNotNull { snapshot.persistedPredictions[it.id] }
                .firstOrNull { it.groupId == group.id }

            val groupForecast = persistedGroupPrediction?.let {
                forecastFromPersisted(
                    persisted = it,
                    entityId = group.id,
                    entityType = DemandEntityType.GROUP,
                    legacyTargetKg = habitualGroupTarget,
                    targetWindowDays = targetWindowDays
                )
            } ?: PredictiveV3Engine.forecast(
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
                    regime = regime,
                    productionAdvanceMode = historicalIds.any(snapshot.productionAdvanceProductIds::contains)
                )
            )

            val service = snapshot.services.firstOrNull {
                it.enabled && it.linkedGroupId == group.id
            }

            // El forecast semanal del grupo sigue perteneciendo al predictor central.
            // La relación complementaria de esta fase actúa sobre la NECESIDAD OPERATIVA
            // del traspaso, no reescribe ni duplica el histórico.
            val serviceAdjustedWeekly = persistedGroupPrediction?.forecastWeeklyKg
                ?: service?.let {
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
                        checkpoints = snapshot.checkpoints,
                        productionAdvanceProductIds = snapshot.productionAdvanceProductIds
                    )
                }
                ?: groupForecast.forecastWeeklyKg

            val rawGroupDynamicTarget = persistedGroupPrediction?.dynamicC04TargetKg
                ?: adaptiveTarget(
                    habitualKg = habitualGroupTarget,
                    forecast = groupForecast,
                    effectiveWeeklyKg = serviceAdjustedWeekly,
                    targetWindowDays = targetWindowDays
                )

            // Protección operativa bidireccional:
            // - no deja que un pico aislado dispare cientos de kg;
            // - tampoco deja que una bajada reciente hunda de golpe el objetivo habitual.
            // La caída puede ocurrir, pero exige una señal realmente sostenida.
            val baseOperationalTarget = prudentGroupTarget(
                habitualKg = habitualGroupTarget,
                rawTargetKg = rawGroupDynamicTarget,
                baselineWeeklyKg = groupForecast.baselineWeeklyKg,
                forecastWeeklyKg = groupForecast.forecastWeeklyKg,
                targetWindowDays = targetWindowDays,
                regimeName = regime.name
            )

            // Si el producto directo (ej. Róbalo) no puede cubrir su propia necesidad con
            // lo físicamente disponible, una fracción prudente del faltante presiona al grupo
            // relacionado (ej. Pargos). NO es 1:1 y nunca sustituye la predicción propia.
            val servicePressure = service?.let { relation ->
                calculateOperationalServicePressure(
                    relation = relation,
                    linkedGroupHabitualKg = habitualGroupTarget,
                    linkedGroupBaseTargetKg = baseOperationalTarget,
                    productsById = productsById,
                    persistedPredictions = snapshot.persistedPredictions,
                    packagedMatrizLots = packagedMatrizLots,
                    regimeName = regime.name
                )
            } ?: OperationalServicePressure.NONE

            val roleAwareGroup = group.primaryProductId
                ?.takeIf { id -> members.any { it.id == id } && memberEnabled(group, id) } != null

            // Los grupos guiados por rector usan el objetivo habitual como PISO operativo.
            // La predicción decide cuánto subir; en NORMAL sólo puede apartarse poco del ideal.
            // Las temporadas especiales abren deliberadamente la banda.
            val guardedBaseTarget = if (roleAwareGroup && habitualGroupTarget > 0.01) {
                max(habitualGroupTarget, baseOperationalTarget)
            } else {
                baseOperationalTarget
            }

            val finalTargetCeilingFactor = when (regime.name) {
                "LENT" -> 1.80
                "DECEMBER" -> 1.65
                "HOLIDAY", "HIGH_SEASON" -> 1.70
                else -> if (roleAwareGroup) 1.10 else 1.50
            }
            val finalTargetCeiling = if (habitualGroupTarget > 0.01) {
                habitualGroupTarget * finalTargetCeilingFactor
            } else {
                Double.POSITIVE_INFINITY
            }

            val candidateWithSupport = guardedBaseTarget + servicePressure.extraGroupKg
            val groupDynamicTarget = min(candidateWithSupport, finalTargetCeiling)
            val appliedServiceExtraKg = max(0.0, groupDynamicTarget - guardedBaseTarget)

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
            if (rawGroupDynamicTarget > groupDynamicTarget + 0.10) {
                groupCodes += TransferReasonCode.LIVE_SPIKE_GUARDED
            }
            if (appliedServiceExtraKg > 0.10) {
                groupCodes += TransferReasonCode.SUPPORT_PRODUCT_LOW
                groupCodes += TransferReasonCode.SERVICE_SUPPORT_APPLIED
            }
            if (roleAwareGroup && habitualGroupTarget > 0.01 &&
                (candidateWithSupport > finalTargetCeiling + 0.10 || baseOperationalTarget < habitualGroupTarget - 0.10)
            ) {
                groupCodes += TransferReasonCode.NORMAL_TARGET_GUARD
            }

            var reverseSupportKg = 0.0
            if (allocation.remainingKg > 0.10 && service != null) {
                val reverse = calculateReverseServiceSupport(
                    relation = service,
                    unresolvedGroupKg = allocation.remainingKg,
                    productsById = productsById,
                    persistedPredictions = snapshot.persistedPredictions,
                    packagedMatrizLots = packagedMatrizLots
                )
                reverse.byAnchorProduct.forEach { (productId, kg) ->
                    reverseServiceExtraByProduct[productId] =
                        (reverseServiceExtraByProduct[productId] ?: 0.0) + kg
                }
                reverseSupportKg = reverse.totalKg
                if (reverseSupportKg > 0.10) {
                    groupCodes += TransferReasonCode.REVERSE_SERVICE_SUPPORT
                }
            }

            if (allocation.remainingKg > reverseSupportKg + 0.1) {
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
                rawDynamicTargetKg = rawGroupDynamicTarget,
                baselineWeeklyKg = groupForecast.baselineWeeklyKg,
                forecastWeeklyKg = groupForecast.forecastWeeklyKg,
                effectiveWeeklyKg = serviceAdjustedWeekly,
                seasonalReferenceWeeklyKg = seasonalReference,
                regimeName = regime.name,
                serviceId = service?.id,
                preferredSupportProductId = service?.preferredGroupProductId,
                baseOperationalTargetKg = guardedBaseTarget,
                serviceSupportCandidateKg = servicePressure.extraGroupKg,
                serviceSupportExtraKg = appliedServiceExtraKg,
                serviceAnchorResidualKg = servicePressure.anchorResidualKg,
                reverseSupportKg = reverseSupportKg,
                allocationTrace = buildList {
                    if (roleAwareGroup && habitualGroupTarget > 0.01) {
                        add(
                            "Cinturón operativo ${regime.name}: piso=${formatTraceKg(habitualGroupTarget)} kg, " +
                                "techo=${formatTraceKg(finalTargetCeiling)} kg; " +
                                "base protegida=${formatTraceKg(guardedBaseTarget)} kg."
                        )
                    }
                    if (servicePressure.extraGroupKg > 0.10) {
                        add(
                            "Apoyo complementario: déficit directo=${formatTraceKg(servicePressure.anchorResidualKg)} kg; " +
                                "presión calculada=${formatTraceKg(servicePressure.extraGroupKg)} kg; " +
                                "aplicada=${formatTraceKg(appliedServiceExtraKg)} kg después del cinturón operativo."
                        )
                    }
                    addAll(allocation.trace)
                    if (reverseSupportKg > 0.10) {
                        add(
                            "Equilibrio inverso: faltan ${formatTraceKg(allocation.remainingKg)} kg del grupo; " +
                                "el lado directo puede compensar ${formatTraceKg(reverseSupportKg)} kg."
                        )
                    }
                },
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
                val packaged = availablePackagedForInternalTransfer(
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
                    baselineWeeklyKg = groupForecast.baselineWeeklyKg,
                    forecastWeeklyKg = serviceAdjustedWeekly,
                    seasonalReferenceWeeklyKg = seasonalReference,
                    regimeName = regime.name,
                    rawDynamicTargetC04Kg = rawGroupDynamicTarget,
                    serviceSupportExtraKg =
                        if (product.id == service?.preferredGroupProductId) appliedServiceExtraKg else 0.0,
                    suggestedLotIds = allocation.lotIdsByProduct[product.id].orEmpty(),
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
                        availablePackagedKg = packaged,
                        currentC04Kg = groupStockC04,
                        targetC04Kg = groupDynamicTarget,
                        habitualC04Kg = habitualGroupTarget,
                        baselineWeeklyKg = groupForecast.baselineWeeklyKg,
                        forecastWeeklyKg = serviceAdjustedWeekly,
                        regimeName = regime.name,
                        groupName = group.name
                    ) ?: groupMessage.takeIf { index == 0 }
                )
            }
        }

        snapshot.products
            .filterNot { groupedIds.contains(it.id) }
            .forEach { product ->
                val persistedProductPrediction = snapshot.persistedPredictions[product.id]
                    ?.takeIf { it.groupId.isNullOrBlank() }

                val forecast = persistedProductPrediction?.let {
                    forecastFromPersisted(
                        persisted = it,
                        entityId = product.id,
                        entityType = DemandEntityType.PRODUCT,
                        legacyTargetKg = product.stockIdealC04.coerceAtLeast(0.0),
                        targetWindowDays = targetWindowDays
                    )
                } ?: forecastProduct(
                    product = product,
                    completeWeeks = completeWeeks,
                    currentWeek = currentWeek,
                    seasonalWeeks = seasonalWeeks,
                    checkpoints = snapshot.checkpoints,
                    targetWindowDays = targetWindowDays,
                    elapsedDays = elapsedDays,
                    regime = regime,
                    productionAdvanceMode = snapshot.productionAdvanceProductIds.contains(product.id)
                )

                // Piso operativo 4.3 SOLO para productos individuales:
                // lo configurado manualmente en stockIdealC04 jamás baja por menor demanda.
                // La predicción persistida puede ser anterior a esta regla, por eso
                // también se protege DESPUÉS de leer la fotografía de Room.
                val habitualTarget = product.stockIdealC04.coerceAtLeast(0.0)
                val rawBaseDynamicTarget = persistedProductPrediction?.dynamicC04TargetKg
                    ?: adaptiveTarget(
                        habitualKg = habitualTarget,
                        forecast = forecast,
                        effectiveWeeklyKg = forecast.forecastWeeklyKg,
                        targetWindowDays = targetWindowDays
                    )
                val baseDynamicTarget = max(habitualTarget, rawBaseDynamicTarget)

                // Si el grupo relacionado quedó físicamente corto y este producto directo
                // tiene excedente transferible sobre su propia necesidad, puede ayudar en
                // sentido inverso. Es una sola relación, sin crear retroalimentación circular.
                val reverseServiceExtra = reverseServiceExtraByProduct[product.id]
                    ?.coerceAtLeast(0.0)
                    ?: 0.0
                val dynamicTarget = baseDynamicTarget + reverseServiceExtra
                val need = max(0.0, dynamicTarget - product.stockCongelador04.coerceAtLeast(0.0))
                val packaged = availablePackagedForInternalTransfer(
                    product,
                    packagedMatrizLots[product.id].orEmpty()
                )
                // Matriz -> C04 es un movimiento interno: minStock no desaparece del
                // inventario total y por eso no debe inmovilizar kilos físicamente transferibles.
                val usableMatriz = product.stockMatriz.coerceAtLeast(0.0)
                val intelligentNeed = min(need, usableMatriz)
                val original = intelligentNeed
                val requested = manualOverridesKg[product.id]?.coerceAtLeast(0.0) ?: intelligentNeed
                val pending = pendingByProduct[product.id] ?: 0.0

                val codes = mutableListOf<TransferReasonCode>()
                if (manualOverridesKg.containsKey(product.id)) codes += TransferReasonCode.USER_OVERRIDE
                if (habitualTarget > 0.01 && product.stockCongelador04 + 0.01 < habitualTarget) {
                    codes += TransferReasonCode.C04_BELOW_HABITUAL
                }
                if (reverseServiceExtra > 0.10) codes += TransferReasonCode.REVERSE_SERVICE_SUPPORT
                if (need <= 0.01) codes += TransferReasonCode.C04_COVERED
                if (requested > packaged + 0.1) {
                    codes += TransferReasonCode.PACKAGING_SHORTAGE
                    if (pending > 0.1) codes += TransferReasonCode.PACKAGING_PENDING_AVAILABLE
                }
                productPlans[product.id] = TransferProductPlanV3(
                    productId = product.id,
                    requestedKg = requested,
                    originalSuggestedKg = original,
                    availablePackagedKg = packaged,
                    pendingPackagingKg = pending,
                    dynamicTargetC04Kg = dynamicTarget,
                    habitualTargetC04Kg = habitualTarget,
                    baselineWeeklyKg = forecast.baselineWeeklyKg,
                    forecastWeeklyKg = forecast.forecastWeeklyKg,
                    seasonalReferenceWeeklyKg = averageExisting(product.id, seasonalWeeks, snapshot.checkpoints),
                    regimeName = persistedProductPrediction?.regime ?: regime.name,
                    rawDynamicTargetC04Kg = rawBaseDynamicTarget,
                    serviceSupportExtraKg = reverseServiceExtra,
                    isManualOverride = manualOverridesKg.containsKey(product.id),
                    reasonCodes = codes.distinct(),
                    message = TransferMessageFactory.productMessage(
                        productName = product.name,
                        codes = codes.distinct(),
                        pendingPackagingKg = pending,
                        availablePackagedKg = packaged,
                        currentC04Kg = product.stockCongelador04.coerceAtLeast(0.0),
                        targetC04Kg = dynamicTarget,
                        habitualC04Kg = habitualTarget,
                        baselineWeeklyKg = forecast.baselineWeeklyKg,
                        forecastWeeklyKg = forecast.forecastWeeklyKg,
                        regimeName = regime.name,
                        groupName = null
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
        val remainingKg: Double,
        val trace: List<String> = emptyList(),
        val lotIdsByProduct: Map<String, List<String>> = emptyMap()
    )


    /**
     * Selecciona la política del grupo sin depender de nombres visibles.
     *
     * - Grupo con principal configurado: roles + PEPS estricto por día.
     * - Grupo sin principal: conserva el reparto equivalente ya validado.
     */
    private fun allocateGroupByCohort(
        group: PredictiveGroupConfig,
        members: List<Product>,
        matrizLotsByProduct: Map<String, List<StockLot>>,
        groupNeedKg: Double,
        overridesKg: Map<String, Double>
    ): GroupAllocation {
        val primaryId = group.primaryProductId
            ?.takeIf { id -> members.any { it.id == id } && memberEnabled(group, id) }

        return if (primaryId != null) {
            allocateRoleAwareGroup(
                group = group,
                members = members,
                matrizLotsByProduct = matrizLotsByProduct,
                groupNeedKg = groupNeedKg,
                overridesKg = overridesKg
            )
        } else {
            allocateEquivalentGroupByCohort(
                group = group,
                members = members,
                matrizLotsByProduct = matrizLotsByProduct,
                groupNeedKg = groupNeedKg,
                overridesKg = overridesKg
            )
        }
    }

    /**
     * Reparto para grupos con principal/secundarios.
     *
     * Reglas:
     * 1. El mínimo del principal es un PISO operativo, no un máximo.
     * 2. Después de cubrir el piso, manda PEPS por DÍA exacto; no se mezclan fechas
     *    sólo por estar dentro de la misma semana.
     * 3. Si principal y secundarios comparten exactamente el día PEPS, se equilibran
     *    por disponibilidad: cerca de mitad y mitad, favoreciendo suavemente al que
     *    tiene más existencia. Un secundario nunca se fuerza sólo por ser secundario.
     * 4. Un miembro auxiliar más antiguo sí puede salir antes que principal/secundario.
     *    Si es más nuevo, no entra mientras una fecha anterior alcance.
     * 5. Las cantidades manuales permanecen exactas y no reciben kilos adicionales.
     */
    private fun allocateRoleAwareGroup(
        group: PredictiveGroupConfig,
        members: List<Product>,
        matrizLotsByProduct: Map<String, List<StockLot>>,
        groupNeedKg: Double,
        overridesKg: Map<String, Double>
    ): GroupAllocation {
        val membersById = members.associateBy { it.id }
        val primaryId = group.primaryProductId
            ?.takeIf { id -> membersById.containsKey(id) && memberEnabled(group, id) }
            ?: return allocateEquivalentGroupByCohort(
                group, members, matrizLotsByProduct, groupNeedKg, overridesKg
            )

        val secondaryIds = group.secondaryProductIds
            .filter { id -> membersById.containsKey(id) && memberEnabled(group, id) }
            .toSet()
        val fillerIds = members
            .map { it.id }
            .filter { it != primaryId && !secondaryIds.contains(it) && memberEnabled(group, it) }
            .toSet()

        val allocated = linkedMapOf<String, Double>()
        val codes = linkedMapOf<String, MutableList<TransferReasonCode>>()
        val trace = mutableListOf<String>()
        val lotIds = linkedMapOf<String, MutableList<String>>()

        overridesKg.forEach { (productId, kg) ->
            if (membersById.containsKey(productId)) {
                allocated[productId] = kg.coerceAtLeast(0.0)
                codes.getOrPut(productId) { mutableListOf() } += TransferReasonCode.USER_OVERRIDE
                trace += "Manual ${membersById[productId]?.name ?: productId}: ${formatTraceKg(kg)} kg."
            }
        }

        data class GuidedLot(
            val productId: String,
            val lot: StockLot,
            val date: Date?,
            var remainingKg: Double
        )

        fun buildLots(product: Product): MutableList<GuidedLot> {
            if (overridesKg.containsKey(product.id)) return mutableListOf()
            var stockCap = product.stockMatriz.coerceAtLeast(0.0)
            return matrizLotsByProduct[product.id]
                .orEmpty()
                .sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE }
                .mapNotNull { lot ->
                    if (stockCap <= 0.01) return@mapNotNull null
                    val movable = min(stockCap, movablePackagedKg(lot))
                    if (movable <= 0.01) return@mapNotNull null
                    stockCap -= movable
                    GuidedLot(product.id, lot, effectiveDate(lot), movable)
                }
                .toMutableList()
        }

        val lotsByProduct = members.associate { product -> product.id to buildLots(product) }
        var remaining = max(0.0, groupNeedKg - allocated.values.sum())
        var rectorCursor: Date? = null

        fun recordTake(state: GuidedLot, kg: Double, reasonCodes: List<TransferReasonCode>) {
            if (kg <= 0.01) return
            allocated[state.productId] = (allocated[state.productId] ?: 0.0) + kg
            state.remainingKg = max(0.0, state.remainingKg - kg)
            codes.getOrPut(state.productId) { mutableListOf() }.addAll(reasonCodes)
            lotIds.getOrPut(state.productId) { mutableListOf() }.apply {
                if (!contains(state.lot.id)) add(state.lot.id)
            }
            remaining = max(0.0, remaining - kg)
        }

        fun nextRectorLot(): GuidedLot? = lotsByProduct[primaryId]
            .orEmpty()
            .firstOrNull { it.remainingKg > 0.01 }

        fun advanceRector(amountWanted: Double, floorMode: Boolean): Double {
            var wanted = min(amountWanted.coerceAtLeast(0.0), remaining.coerceAtLeast(0.0))
            var taken = 0.0
            while (wanted > 0.01) {
                val state = nextRectorLot() ?: break
                val kg = min(wanted, state.remainingKg)
                if (kg <= 0.01) break
                recordTake(
                    state,
                    kg,
                    buildList {
                        add(TransferReasonCode.FIFO_OLDEST)
                        add(TransferReasonCode.RECTOR_CURSOR_ADVANCED)
                        if (floorMode) add(TransferReasonCode.PRIMARY_MINIMUM)
                        if (taken > 0.01 || rectorCursor != null) add(TransferReasonCode.NEXT_COHORT_USED)
                    }
                )
                rectorCursor = laterDate(rectorCursor, state.date)
                wanted -= kg
                taken += kg
            }
            return taken
        }

        // Si el rector fue editado manualmente, estimamos hasta qué fecha PEPS habría llegado
        // esa cantidad. Sólo sirve para no permitir que los rellenos salten hacia el futuro.
        if (overridesKg.containsKey(primaryId)) {
            var cursorNeed = overridesKg[primaryId]?.coerceAtLeast(0.0) ?: 0.0
            matrizLotsByProduct[primaryId].orEmpty()
                .sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE }
                .forEach { lot ->
                    if (cursorNeed <= 0.01) return@forEach
                    val movable = movablePackagedKg(lot)
                    if (movable <= 0.01) return@forEach
                    val used = min(cursorNeed, movable)
                    cursorNeed -= used
                    rectorCursor = laterDate(rectorCursor, effectiveDate(lot))
                }
        }

        val primary = membersById.getValue(primaryId)
        val primaryFloorMissing = max(
            0.0,
            group.primaryMinimumC04Kg.coerceAtLeast(0.0) - primary.stockCongelador04.coerceAtLeast(0.0)
        )

        if (!overridesKg.containsKey(primaryId) && primaryFloorMissing > 0.01 && remaining > 0.01) {
            val floorTaken = advanceRector(primaryFloorMissing, floorMode = true)
            if (floorTaken > 0.01) {
                trace += "Rector ${primary.name}: +${formatTraceKg(floorTaken)} kg para acercarse a su piso; " +
                    "cursor PEPS=${formatTraceDay(rectorCursor)}."
            }
            if (floorTaken + 0.10 < primaryFloorMissing) {
                codes.getOrPut(primaryId) { mutableListOf() } += TransferReasonCode.PRIMARY_SHORTAGE
                trace += "Rector ${primary.name}: faltan ${formatTraceKg(primaryFloorMissing - floorTaken)} kg " +
                    "para el piso por falta de producto físicamente movible."
            }
        }

        fun dateAllowed(date: Date?): Boolean {
            val cursor = rectorCursor ?: return false
            val candidate = date ?: return false
            return candidate.time <= endOfOperationalDay(cursor).time
        }

        fun companionStatesAtOrBehindCursor(): List<GuidedLot> {
            val fillers = fillerIds.flatMap { lotsByProduct[it].orEmpty() }
                .filter { it.remainingKg > 0.01 && dateAllowed(it.date) }
            val secondary = secondaryIds.flatMap { lotsByProduct[it].orEmpty() }
                .filter { it.remainingKg > 0.01 && dateAllowed(it.date) }

            // PEPS manda entre fechas. Si comparten el mismo día, primero intentamos vaciar
            // rellenos escasos/atrasados y después usamos al secundario como compensador.
            return (fillers + secondary).sortedWith(
                compareBy<GuidedLot> { it.date?.time ?: Long.MAX_VALUE }
                    .thenBy { if (fillerIds.contains(it.productId)) 0 else 1 }
                    .thenByDescending { it.remainingKg }
            )
        }

        fun drainBacklog(): Double {
            if (remaining <= 0.01 || rectorCursor == null) return 0.0
            var total = 0.0
            for (state in companionStatesAtOrBehindCursor()) {
                if (remaining <= 0.01) break
                val kg = min(remaining, state.remainingKg)
                if (kg <= 0.01) continue
                val isSecondary = secondaryIds.contains(state.productId)
                recordTake(
                    state,
                    kg,
                    buildList {
                        add(TransferReasonCode.FIFO_OLDEST)
                        add(TransferReasonCode.RECTOR_CURSOR_BACKLOG)
                        if (isSecondary) add(TransferReasonCode.SECONDARY_COMPENSATION)
                        else add(TransferReasonCode.OLDER_SUPPORT_LOT)
                    }
                )
                total += kg
                trace += "Detrás del rector ${formatTraceDay(rectorCursor)}: " +
                    "${membersById[state.productId]?.name ?: state.productId} +${formatTraceKg(kg)} kg " +
                    "del ${formatTraceDay(state.date)}."
            }
            return total
        }

        // Con el cursor abierto por el rector, primero vaciamos todo lo viejo que pueda
        // acompañarlo. Ningún relleno puede abrir una fecha posterior por sí solo.
        drainBacklog()

        var guard = 0
        while (remaining > 0.01 && guard++ < 100) {
            // Si todavía falta, el rector abre/avanza el siguiente tramo. No toma todo el
            // faltante de golpe: abre como máximo un "bloque rector" para dar oportunidad
            // a que H.M. y los rellenos atrasados completen la necesidad.
            val nextPrimary = nextRectorLot()
            if (nextPrimary != null) {
                val activationBlock = group.primaryMinimumC04Kg
                    .takeIf { it > 0.01 }
                    ?: remaining
                val beforeCursor = rectorCursor
                val rectorTaken = advanceRector(min(remaining, activationBlock), floorMode = false)
                if (rectorTaken > 0.01) {
                    trace += "Rector abre/continúa ${formatTraceDay(rectorCursor)}: " +
                        "${primary.name} +${formatTraceKg(rectorTaken)} kg" +
                        if (beforeCursor != rectorCursor) "; se habilita sólo mercancía igual o anterior." else "."
                }
                drainBacklog()
                continue
            }

            // Excepción controlada: si ya no existe rector físicamente movible y aún falta
            // grupo, únicamente el secundario puede adelantarse para compensar. Ese avance
            // NO habilita rellenos posteriores al último cursor del rector.
            val secondaryFuture = secondaryIds
                .flatMap { lotsByProduct[it].orEmpty() }
                .filter { it.remainingKg > 0.01 && (rectorCursor == null || !dateAllowed(it.date)) }
                .sortedBy { it.date?.time ?: Long.MAX_VALUE }
                .firstOrNull()

            if (secondaryFuture != null) {
                val kg = min(remaining, secondaryFuture.remainingKg)
                recordTake(
                    secondaryFuture,
                    kg,
                    listOf(
                        TransferReasonCode.FIFO_OLDEST,
                        TransferReasonCode.SECONDARY_COMPENSATION,
                        TransferReasonCode.NEXT_COHORT_USED
                    )
                )
                trace += "Secundario compensa sin rector disponible: " +
                    "${membersById[secondaryFuture.productId]?.name ?: secondaryFuture.productId} " +
                    "+${formatTraceKg(kg)} kg del ${formatTraceDay(secondaryFuture.date)}; " +
                    "no habilita rellenos futuros."
                continue
            }

            break
        }

        if (remaining > 0.10) {
            trace += "Sin combinación guiada suficiente: quedan ${formatTraceKg(remaining)} kg por cubrir."
        }

        return GroupAllocation(
            byProduct = allocated,
            originalByProduct = allocated.toMap(),
            codesByProduct = codes.mapValues { it.value.distinct() },
            remainingKg = remaining.coerceAtLeast(0.0),
            trace = trace,
            lotIdsByProduct = lotIds.mapValues { it.value.distinct() }
        )
    }

    private fun allocateEquivalentGroupByCohort(
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
            product.id to if (memberEnabled(group, product.id)) {
                availableMatrizForInternalTransfer(
                    product,
                    matrizLotsByProduct[product.id].orEmpty()
                )
            } else {
                0.0
            }
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
            val balancedSameDateGroup = group.balanceSameReceivedDate
            val cohort = candidates.filter { candidate ->
                if (consumedLots.contains(candidate.lot.id)) return@filter false
                if ((capacities[candidate.productId] ?: 0.0) <= 0.01) return@filter false

                if (balancedSameDateGroup) {
                    sameOperationalDay(first.date, candidate.date)
                } else if (startTime == null || candidate.date == null) {
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

            var roundTaken = 0.0

            if (balancedSameDateGroup && availableByProduct.size > 1) {
                // Modo configurado por el usuario: con la misma fecha efectiva no existe
                // ventaja PEPS entre miembros, así que repartimos lo más parejo posible.
                // La disponibilidad física puede romper el equilibrio. Con fechas distintas,
                // el bucle de cohortes mantiene PEPS y usa primero la fecha más antigua.
                val cohortCaps = availableByProduct.toMutableMap()
                val activeIds = cohortCaps.keys.toMutableList()
                var leftInCohort = takeThisCohort

                while (leftInCohort > 0.01 && activeIds.isNotEmpty()) {
                    val share = leftInCohort / activeIds.size.toDouble()
                    var takenThisPass = 0.0
                    val exhausted = mutableListOf<String>()

                    activeIds.forEach { productId ->
                        val generalCap = capacities[productId] ?: 0.0
                        val cohortCap = cohortCaps[productId] ?: 0.0
                        val kg = min(share, min(generalCap, cohortCap))
                        if (kg > 0.01) {
                            allocated[productId] = (allocated[productId] ?: 0.0) + kg
                            capacities[productId] = max(0.0, generalCap - kg)
                            cohortCaps[productId] = max(0.0, cohortCap - kg)
                            remaining -= kg
                            leftInCohort -= kg
                            roundTaken += kg
                            takenThisPass += kg
                            codes.getOrPut(productId) { mutableListOf() } += TransferReasonCode.FIFO_OLDEST
                        }
                        if ((cohortCaps[productId] ?: 0.0) <= 0.01 ||
                            (capacities[productId] ?: 0.0) <= 0.01
                        ) {
                            exhausted += productId
                        }
                    }

                    activeIds.removeAll(exhausted.toSet())
                    if (takenThisPass <= 0.001) break
                }
            } else {
                val weighted = availableByProduct.mapValues { (productId, kg) ->
                    val role = when {
                        productId == group.primaryProductId -> 1.12
                        group.secondaryProductIds.contains(productId) -> 1.06
                        else -> 1.0
                    }
                    kg * role * memberPriorityWeight(group, productId)
                }
                val scoreTotal = weighted.values.sum().takeIf { it > 0.0 } ?: 1.0

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
        regime: com.cesar.bocana.predictive.v3.model.SeasonRegime,
        productionAdvanceMode: Boolean
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
                regime = regime,
                productionAdvanceMode = productionAdvanceMode
            )
        )
    }


    private data class OperationalServicePressure(
        val extraGroupKg: Double,
        val anchorResidualKg: Double,
        val anchorTargetKg: Double,
        val anchorCurrentC04Kg: Double,
        val anchorTransferableKg: Double,
        val supportFraction: Double
    ) {
        companion object {
            val NONE = OperationalServicePressure(
                extraGroupKg = 0.0,
                anchorResidualKg = 0.0,
                anchorTargetKg = 0.0,
                anchorCurrentC04Kg = 0.0,
                anchorTransferableKg = 0.0,
                supportFraction = 0.0
            )
        }
    }

    private data class ReverseServiceSupport(
        val totalKg: Double,
        val byAnchorProduct: Map<String, Double>
    ) {
        companion object {
            val NONE = ReverseServiceSupport(0.0, emptyMap())
        }
    }

    /**
     * Presión OPERATIVA de una relación complementaria.
     *
     * Ejemplo Róbalo -> Pargos:
     * 1) Róbalo conserva su objetivo propio.
     * 2) Se calcula cuánto de ese objetivo puede cubrir con C04 + mercancía empacada transferible.
     * 3) Sólo el faltante residual genera presión sobre Pargos.
     * 4) La presión nunca es 1:1: usa una fracción moderada dependiente de la severidad.
     *
     * No toca histórico ni inventario; sólo ajusta la cobertura sugerida del grupo.
     */
    private fun calculateOperationalServicePressure(
        relation: PredictiveServiceRelation,
        linkedGroupHabitualKg: Double,
        linkedGroupBaseTargetKg: Double,
        productsById: Map<String, Product>,
        persistedPredictions: Map<String, PredictiveV3Snapshot>,
        packagedMatrizLots: Map<String, List<StockLot>>,
        regimeName: String
    ): OperationalServicePressure {
        val anchors = relation.effectiveAnchorProductIds()
            .mapNotNull(productsById::get)
        if (anchors.isEmpty()) return OperationalServicePressure.NONE

        var totalTarget = 0.0
        var totalCurrent = 0.0
        var totalTransferable = 0.0
        var totalResidual = 0.0

        anchors.forEach { product ->
            val predictedTarget = persistedPredictions[product.id]
                ?.dynamicC04TargetKg
                ?.coerceAtLeast(0.0)
                ?: product.stockIdealC04.coerceAtLeast(0.0)

            val current = product.stockCongelador04.coerceAtLeast(0.0)
            val transferable = availablePackagedForInternalTransfer(
                product,
                packagedMatrizLots[product.id].orEmpty()
            )
            val ownNeed = max(0.0, predictedTarget - current)
            val physicallyCoverable = min(ownNeed, transferable)
            val residual = max(0.0, ownNeed - physicallyCoverable)

            totalTarget += predictedTarget
            totalCurrent += current
            totalTransferable += transferable
            totalResidual += residual
        }

        if (totalResidual <= 0.10 || totalTarget <= 0.10) {
            return OperationalServicePressure.NONE
        }

        val severity = (totalResidual / totalTarget).coerceIn(0.0, 1.0)

        // Si el directo apenas está corto, el apoyo es pequeño.
        // Si prácticamente no puede abastecerse, el grupo puede absorber hasta ~44%.
        val normalFraction = (0.20 + severity * 0.24).coerceIn(0.20, 0.44)
        val seasonalBoost = when (regimeName) {
            "LENT" -> 1.15
            "DECEMBER" -> 1.10
            "HOLIDAY", "HIGH_SEASON" -> 1.12
            else -> 1.0
        }
        val supportFraction = (normalFraction * seasonalBoost).coerceIn(0.20, 0.50)

        val candidateExtra = totalResidual * supportFraction

        // El complemento también tiene freno propio. Una relación no puede por sí sola
        // duplicar el objetivo del grupo en una sola sugerencia.
        val relationCeiling = max(
            linkedGroupHabitualKg.coerceAtLeast(0.0) * 0.65,
            linkedGroupBaseTargetKg.coerceAtLeast(0.0) * 0.55
        ).coerceAtLeast(0.0)

        val extra = min(candidateExtra, relationCeiling)

        return OperationalServicePressure(
            extraGroupKg = extra.coerceAtLeast(0.0),
            anchorResidualKg = totalResidual,
            anchorTargetKg = totalTarget,
            anchorCurrentC04Kg = totalCurrent,
            anchorTransferableKg = totalTransferable,
            supportFraction = supportFraction
        )
    }

    /**
     * Sentido inverso de la MISMA relación.
     *
     * Sólo se usa si el grupo realmente quedó sin mercancía empacada suficiente.
     * Un producto directo puede ayudar únicamente con disponibilidad que le sobra
     * después de cubrir su propia necesidad. De esta forma no existe doble presión.
     */
    private fun calculateReverseServiceSupport(
        relation: PredictiveServiceRelation,
        unresolvedGroupKg: Double,
        productsById: Map<String, Product>,
        persistedPredictions: Map<String, PredictiveV3Snapshot>,
        packagedMatrizLots: Map<String, List<StockLot>>
    ): ReverseServiceSupport {
        if (unresolvedGroupKg <= 0.10) return ReverseServiceSupport.NONE

        data class Spare(val productId: String, val kg: Double)

        val spares = relation.effectiveAnchorProductIds()
            .mapNotNull(productsById::get)
            .mapNotNull { product ->
                val target = persistedPredictions[product.id]
                    ?.dynamicC04TargetKg
                    ?.coerceAtLeast(0.0)
                    ?: product.stockIdealC04.coerceAtLeast(0.0)
                val current = product.stockCongelador04.coerceAtLeast(0.0)
                val transferable = availablePackagedForInternalTransfer(
                    product,
                    packagedMatrizLots[product.id].orEmpty()
                )
                val ownNeed = max(0.0, target - current)
                val spare = max(0.0, transferable - ownNeed)
                spare.takeIf { it > 0.10 }?.let { Spare(product.id, it) }
            }

        val totalSpare = spares.sumOf { it.kg }
        if (totalSpare <= 0.10) return ReverseServiceSupport.NONE

        // Inverso aún más conservador: el directo sólo cubre una parte del faltante grupal.
        val wanted = min(unresolvedGroupKg * 0.35, totalSpare)
        if (wanted <= 0.10) return ReverseServiceSupport.NONE

        val byProduct = linkedMapOf<String, Double>()
        var remaining = wanted
        spares.forEachIndexed { index, spare ->
            if (remaining <= 0.01) return@forEachIndexed
            val take = if (index == spares.lastIndex) {
                min(spare.kg, remaining)
            } else {
                min(spare.kg, wanted * (spare.kg / totalSpare))
            }
            if (take > 0.01) {
                byProduct[spare.productId] = take
                remaining -= take
            }
        }

        return ReverseServiceSupport(
            totalKg = byProduct.values.sum(),
            byAnchorProduct = byProduct
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
        checkpoints: Map<String, Double>,
        productionAdvanceProductIds: Set<String>
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
                regime = regime,
                productionAdvanceMode = anchorHistoryIds.any(productionAdvanceProductIds::contains)
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

    private fun forecastFromPersisted(
        persisted: PredictiveV3Snapshot,
        entityId: String,
        entityType: DemandEntityType,
        legacyTargetKg: Double,
        targetWindowDays: Double
    ): ForecastResultV3 {
        val baseline = persisted.baselineWeeklyKg.coerceAtLeast(0.0)
        val forecast = persisted.forecastWeeklyKg.coerceAtLeast(0.0)
        val ratio = if (baseline > 0.01) forecast / baseline else 1.0
        val trend = when {
            ratio >= 1.75 -> TrendSignal.SURGE
            ratio >= 1.20 -> TrendSignal.RISING
            ratio <= 0.78 -> TrendSignal.FALLING
            else -> TrendSignal.STABLE
        }
        val derivedSafetyDays = if (forecast > 0.01) {
            (persisted.dynamicC04TargetKg * 7.0 / forecast - targetWindowDays)
                .coerceIn(0.50, 3.00)
        } else {
            1.0
        }

        return ForecastResultV3(
            entityId = entityId,
            entityType = entityType,
            baselineWeeklyKg = baseline,
            liveWeeklyPaceKg = forecast,
            forecastWeeklyKg = forecast,
            lowScenarioWeeklyKg = persisted.lowScenarioWeeklyKg.coerceAtLeast(0.0),
            highScenarioWeeklyKg = persisted.highScenarioWeeklyKg.coerceAtLeast(0.0),
            trendSignal = trend,
            confidence = ConfidenceLevel.MEDIUM,
            variability = 0.0,
            coverageDays = persisted.coverageDays?.toDouble(),
            safetyDays = derivedSafetyDays,
            dynamicC04TargetKg = persisted.dynamicC04TargetKg.coerceAtLeast(0.0),
            suggestedTransferKg = persisted.suggestedTransferKg.coerceAtLeast(0.0),
            limitedByMatrizReserve = false,
            legacyC04ReferenceKg = legacyTargetKg.coerceAtLeast(0.0),
            reasons = emptyList()
        )
    }

    /**
     * Capa de prudencia exclusiva de Traspasos.
     *
     * Conserva el pronóstico crudo para análisis, pero evita que un pico vivo aislado
     * multiplique de golpe un objetivo grupal estable. En temporada especial permite
     * una expansión mayor; fuera de temporada el crecimiento es deliberadamente gradual.
     */
    private fun prudentGroupTarget(
        habitualKg: Double,
        rawTargetKg: Double,
        baselineWeeklyKg: Double,
        forecastWeeklyKg: Double,
        targetWindowDays: Double,
        regimeName: String
    ): Double {
        val raw = rawTargetKg.coerceAtLeast(0.0)
        val habitual = habitualKg.coerceAtLeast(0.0)
        if (raw <= 0.01 || habitual <= 0.01) return raw

        val targetFactor = when (regimeName) {
            "LENT" -> 1.70
            "DECEMBER" -> 1.55
            "HOLIDAY", "HIGH_SEASON" -> 1.60
            else -> 1.20
        }

        val historicalWindowFactor = when (regimeName) {
            "LENT" -> 1.65
            "DECEMBER" -> 1.50
            "HOLIDAY", "HIGH_SEASON" -> 1.55
            else -> 1.25
        }

        val baselineWindow = if (baselineWeeklyKg > 0.01) {
            baselineWeeklyKg / 7.0 * (targetWindowDays.coerceAtLeast(0.0) + 1.5)
        } else {
            0.0
        }

        val ceiling = max(
            habitual * targetFactor,
            baselineWindow * historicalWindowFactor
        )

        val cappedUp = min(raw, ceiling.coerceAtLeast(habitual * 0.60))
        if (raw >= habitual) return cappedUp

        // Descenso amortiguado: una caída sí es válida, pero cuanto más parecida siga
        // siendo la demanda prevista al histórico, menos permitimos hundir el objetivo
        // en una sola semana.
        val demandRatio = if (baselineWeeklyKg > 0.01) {
            (forecastWeeklyKg.coerceAtLeast(0.0) / baselineWeeklyKg).coerceIn(0.0, 2.0)
        } else {
            1.0
        }

        val lowerFactor = when (regimeName) {
            "LENT" -> 1.00
            "DECEMBER" -> 0.98
            "HOLIDAY", "HIGH_SEASON" -> 0.98
            else -> when {
                demandRatio >= 0.90 -> 0.90
                demandRatio >= 0.75 -> 0.82
                demandRatio >= 0.60 -> 0.72
                else -> 0.60
            }
        }

        return max(cappedUp, habitual * lowerFactor)
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
        // Piso operativo C04: la predicción puede aumentar, pero no reducir la
        // cantidad habitual configurada por la persona.
        return max(habitualKg, max(windowTarget, adaptiveHabitual))
    }

    /**
     * Cantidad físicamente movible de un lote.
     * Si el lote trabaja por cajas/costales, los residuos menores a una unidad completa
     * NO deben abrir PEPS ni provocar que la UI salte a un lote posterior para "completarlos".
     */
    private fun movablePackagedKg(lot: StockLot): Double {
        val current = lot.currentQuantity.coerceAtLeast(0.0)
        val weight = lot.pesoPorUnidad
        val unit = lot.unidadDeEmpaque
        if (weight != null && weight > 0.0 && !unit.isNullOrBlank()) {
            val units = kotlin.math.floor(current / weight).toInt()
            return (units * weight).coerceAtMost(current)
        }
        return current
    }

    private fun laterDate(first: Date?, second: Date?): Date? = when {
        first == null -> second
        second == null -> first
        second.after(first) -> second
        else -> first
    }

    private fun endOfOperationalDay(date: Date): Date = Calendar.getInstance().apply {
        time = date
        set(Calendar.HOUR_OF_DAY, 23)
        set(Calendar.MINUTE, 59)
        set(Calendar.SECOND, 59)
        set(Calendar.MILLISECOND, 999)
    }.time

    private fun availableMatrizForInternalTransfer(
        product: Product,
        matrizLots: List<StockLot>
    ): Double {
        val lotKg = matrizLots
            .filter { StockQuantityPolicy.isUsable(it.currentQuantity) }
            .sumOf { it.currentQuantity.coerceAtLeast(0.0) }

        // minStock sigue siendo referencia de cobertura/compra. No es una reserva física
        // de Matriz durante un traspaso interno porque el stock total no disminuye.
        return min(lotKg, product.stockMatriz.coerceAtLeast(0.0))
    }

    private fun availablePackagedForInternalTransfer(
        product: Product,
        packagedLots: List<StockLot>
    ): Double {
        val packagedKg = packagedLots
            .filter { StockQuantityPolicy.isUsable(it.currentQuantity) }
            .sumOf { it.currentQuantity.coerceAtLeast(0.0) }

        // Traspasos sólo puede usar mercancía empacada y realmente existente en Matriz.
        // El mínimo general NO reduce esta cantidad.
        return min(packagedKg, product.stockMatriz.coerceAtLeast(0.0))
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

    private fun memberEnabled(
        group: PredictiveGroupConfig,
        productId: String
    ): Boolean {
        return group.memberRules
            .firstOrNull { it.productId == productId }
            ?.enabled
            ?: true
    }

    private fun memberPriorityWeight(
        group: PredictiveGroupConfig,
        productId: String
    ): Double {
        return group.memberRules
            .firstOrNull { it.productId == productId && it.enabled }
            ?.priorityWeight
            ?.coerceIn(0.25, 4.0)
            ?: 1.0
    }

    private fun roleRank(
        group: PredictiveGroupConfig,
        productId: String
    ): Int = when {
        productId == group.primaryProductId -> 0
        group.secondaryProductIds.contains(productId) -> 1
        else -> 2
    }

    private fun formatTraceDay(date: Date?): String =
        date?.let {
            SimpleDateFormat("dd/MM/yy", Locale.getDefault()).format(it)
        } ?: "sin fecha"

    private fun formatTraceKg(value: Double): String =
        String.format(Locale.getDefault(), "%.1f", value.coerceAtLeast(0.0))

    private fun sameOperationalDay(first: Date?, second: Date?): Boolean {
        if (first == null || second == null) return false
        val a = Calendar.getInstance().apply { time = first }
        val b = Calendar.getInstance().apply { time = second }
        return a.get(Calendar.YEAR) == b.get(Calendar.YEAR) &&
            a.get(Calendar.DAY_OF_YEAR) == b.get(Calendar.DAY_OF_YEAR)
    }

    /** PEPS se basa en la llegada original más antigua conocida. */
    private fun effectiveDate(lot: StockLot): Date? =
        listOfNotNull(lot.receivedAt, lot.originalReceivedAt)
            .minByOrNull { it.time }

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
        availablePackagedKg: Double,
        currentC04Kg: Double? = null,
        targetC04Kg: Double? = null,
        habitualC04Kg: Double? = null,
        baselineWeeklyKg: Double? = null,
        forecastWeeklyKg: Double? = null,
        regimeName: String? = null,
        groupName: String? = null
    ): String? {
        val set = codes.toSet()
        val current = currentC04Kg?.coerceAtLeast(0.0)
        val target = targetC04Kg?.coerceAtLeast(0.0)
        val deficit = if (current != null && target != null) max(0.0, target - current) else null
        val demandHigh = baselineWeeklyKg != null && baselineWeeklyKg > 0.01 &&
            forecastWeeklyKg != null && forecastWeeklyKg > baselineWeeklyKg * 1.15
        val specialSeason = regimeName in setOf("LENT", "DECEMBER", "HOLIDAY", "HIGH_SEASON")

        return when {
            set.contains(TransferReasonCode.PACKAGING_SHORTAGE) &&
                set.contains(TransferReasonCode.PACKAGING_PENDING_AVAILABLE) ->
                "Faltan mercancía empacada para completar la sugerencia. " +
                    "Disponible: ${format1(availablePackagedKg)} kg; pendiente de empacar: ${format1(pendingPackagingKg)} kg."

            set.contains(TransferReasonCode.PRIMARY_SHORTAGE) ->
                "$productName está por debajo de su mínimo operativo y no hay suficiente mercancía transferible para recuperarlo."

            set.contains(TransferReasonCode.PRIMARY_MINIMUM) ->
                "$productName está bajo su mínimo operativo en C04; por eso se prioriza su reposición."

            set.contains(TransferReasonCode.C04_BELOW_HABITUAL) && current != null -> {
                val habitual = habitualC04Kg?.coerceAtLeast(0.0) ?: 0.0
                val base = "C04 tiene ${format1(current)} kg: está por debajo del objetivo habitual de ${format1(habitual)} kg. " +
                    "Objetivo actual ${format1(target ?: habitual)} kg; se prioriza su reposición."
                if (set.contains(TransferReasonCode.PACKAGING_SHORTAGE)) {
                    "$base Empacado disponible: ${format1(availablePackagedKg)} kg; revisa empaque y disponibilidad."
                } else base
            }

            deficit != null && deficit > 0.10 && specialSeason -> {
                val scope = groupName?.let { "El grupo $it" } ?: "C04"
                when (regimeName) {
                    "LENT" ->
                        "Cuaresma elevó la demanda esperada. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual subió a ${format1(target ?: 0.0)} kg."
                    "DECEMBER" ->
                        "Fiestas decembrinas elevan la demanda esperada. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual es ${format1(target ?: 0.0)} kg."
                    "HOLIDAY" ->
                        "El periodo festivo elevó la demanda esperada. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual es ${format1(target ?: 0.0)} kg."
                    "HIGH_SEASON" ->
                        "Temporada alta elevó la demanda esperada. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual es ${format1(target ?: 0.0)} kg."
                    else ->
                        "La referencia estacional elevó la demanda esperada. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual es ${format1(target ?: 0.0)} kg."
                }
            }

            deficit != null && deficit > 0.10 && demandHigh -> {
                val scope = groupName?.let { "El grupo $it" } ?: "C04"
                "La demanda reciente está por arriba de lo habitual. $scope tiene ${format1(current ?: 0.0)} kg y el objetivo actual es ${format1(target ?: 0.0)} kg."
            }

            deficit != null && deficit > 0.10 -> {
                val scope = groupName?.let { "El grupo $it" } ?: "C04"
                val habitual = habitualC04Kg?.takeIf { it > 0.01 }
                if (habitual != null && kotlin.math.abs((target ?: 0.0) - habitual) > 0.10) {
                    "Stock bajo: $scope tiene ${format1(current ?: 0.0)} kg. Objetivo habitual ${format1(habitual)} kg; objetivo actual ${format1(target ?: 0.0)} kg."
                } else {
                    "Stock bajo: $scope tiene ${format1(current ?: 0.0)} kg y el objetivo para este traspaso es ${format1(target ?: 0.0)} kg."
                }
            }

            set.contains(TransferReasonCode.REVERSE_SERVICE_SUPPORT) ->
                "$productName puede apoyar al grupo relacionado porque conserva disponibilidad transferible después de cubrir su propia necesidad."

            set.contains(TransferReasonCode.USER_OVERRIDE) ->
                "Cantidad modificada. Se conserva tu decisión."

            set.contains(TransferReasonCode.C04_COVERED) ->
                "C04 ya cubre la necesidad estimada hasta el próximo traspaso."

            set.contains(TransferReasonCode.ROLE_SAME_DAY_BALANCE) ->
                "PEPS: comparte la fecha activa con otro producto principal/secundario; se equilibró según la existencia disponible."

            set.contains(TransferReasonCode.SECONDARY_COMPENSATION) ->
                "$productName apoya la cobertura del grupo por PEPS y disponibilidad; no se fuerza al secundario si otra opción anterior puede cubrirla."

            set.contains(TransferReasonCode.OLDER_SUPPORT_LOT) ->
                "PEPS: este producto tiene mercancía más antigua que los productos principales, por eso se aprovecha primero."

            set.contains(TransferReasonCode.NEXT_COHORT_USED) ->
                "PEPS: el lote más antiguo no alcanzó y se completó con la siguiente fecha disponible."

            set.contains(TransferReasonCode.SAME_COHORT_ABUNDANCE) ->
                "PEPS: se priorizó $productName por mayor existencia disponible en la fecha más antigua."

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
            set.contains(TransferReasonCode.SERVICE_SUPPORT_APPLIED) ->
                "$groupName aumenta su cobertura porque el producto relacionado no puede cubrir toda su necesidad con la mercancía disponible."

            set.contains(TransferReasonCode.REVERSE_SERVICE_SUPPORT) ->
                "El grupo no alcanza a cubrir toda su necesidad; el producto relacionado puede compensar una parte con disponibilidad sobrante."

            set.contains(TransferReasonCode.NORMAL_TARGET_GUARD) ->
                "La demanda cambió, pero el objetivo operativo se mantuvo dentro de una banda estable. Los saltos grandes se reservan para temporada especial."

            set.contains(TransferReasonCode.LIVE_SPIKE_GUARDED) ->
                "El ritmo reciente subió con fuerza. La sugerencia se moderó con el histórico para evitar reaccionar de más a un pico aislado."

            set.contains(TransferReasonCode.SUPPORT_PRODUCT_LOW) ->
                "$groupName necesita más apoyo porque el producto directo está por debajo de su nivel habitual."

            set.contains(TransferReasonCode.GROUP_DYNAMIC_UP) ->
                "$groupName requiere más cobertura: objetivo habitual ${format1(habitualKg)} kg; objetivo actual ${format1(dynamicKg)} kg."

            set.contains(TransferReasonCode.GROUP_DYNAMIC_DOWN) ->
                "La demanda reciente de $groupName es menor; el objetivo actual bajó a ${format1(dynamicKg)} kg."

            set.contains(TransferReasonCode.NO_NEARBY_MEMBER) ->
                "No hay suficiente mercancía empacada de las fechas disponibles para completar el objetivo del grupo."

            primaryName != null && habitualKg > 0.0 ->
                "$primaryName marca el piso operativo del grupo; después el reparto respeta PEPS, fecha y disponibilidad real."

            else -> null
        }
    }

    private fun format1(value: Double): String =
        String.format(java.util.Locale.getDefault(), "%.1f", value)
}

