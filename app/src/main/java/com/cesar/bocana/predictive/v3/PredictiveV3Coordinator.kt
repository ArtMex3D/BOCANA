package com.cesar.bocana.predictive.v3

import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.predictive.v3.data.PredictiveV3ConfigRepository
import com.cesar.bocana.predictive.v3.data.PredictiveV3FirestoreDataSource
import com.cesar.bocana.predictive.v3.data.PredictiveV3Time
import com.cesar.bocana.predictive.v3.model.DemandEntityType
import com.cesar.bocana.predictive.v3.model.DemandPoint
import com.cesar.bocana.predictive.v3.model.ForecastContext
import com.cesar.bocana.predictive.v3.model.GroupAllocationInput
import com.cesar.bocana.predictive.v3.model.GroupAnalysisV3
import com.cesar.bocana.predictive.v3.model.GroupMemberState
import com.cesar.bocana.predictive.v3.model.HistoricalReferenceSignal
import com.cesar.bocana.predictive.v3.model.OperationalRecommendation
import com.cesar.bocana.predictive.v3.model.PredictiveGroupConfig
import com.cesar.bocana.predictive.v3.model.PredictiveServiceRelation
import com.cesar.bocana.predictive.v3.model.PredictiveV3Analysis
import com.cesar.bocana.predictive.v3.model.PurchaseHistorySignal
import com.cesar.bocana.predictive.v3.model.TransferPatternSignal
import com.cesar.bocana.predictive.v3.model.ServiceAllocationInput
import com.cesar.bocana.predictive.v3.model.ServiceAnalysisV3
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import java.util.Calendar
import java.util.Date
import kotlin.math.max
import kotlin.math.min

class PredictiveV3Coordinator(
    private val firestore: FirebaseFirestore,
    private val nowProvider: () -> Date = { Date() }
) {
    companion object {
        private const val HISTORY_WEEKS = 16
        private const val SERVICE_HISTORY_WEEKS = 16
        private const val SERVICE_STOCK_HORIZON_WEEKS = 2.0
    }

    private val dataSource = PredictiveV3FirestoreDataSource(firestore)
    private val configRepository = PredictiveV3ConfigRepository(firestore)

    suspend fun analyzeProduct(productId: String): PredictiveV3Analysis {
        val now = nowProvider()
        val projectId = runCatching { FirebaseApp.getInstance().options.projectId }.getOrNull()
        val products = dataSource.loadProducts()
        val selected = products.firstOrNull { it.id == productId }
            ?: error("Producto no encontrado para V3: $productId")

        // Sólo crea configuración automática en el laboratorio DEV y sólo si la colección está vacía.
        configRepository.ensureInitialDevConfigIfMissing(projectId, products)
        val config = configRepository.load()

        val completeWeeks = PredictiveV3Time.completeWeeks(now, HISTORY_WEEKS)
        val currentWeek = PredictiveV3Time.currentWeek(now)
        val seasonalWeeks = PredictiveV3Time.seasonalWeeks(now)
        val previousYearWeek = PredictiveV3Time.sameWeekPreviousYear(now)
        val previousYearMonth = PredictiveV3Time.sameMonthPreviousYearRange(now)
        val allWeeks = (completeWeeks + currentWeek + seasonalWeeks + previousYearWeek).distinctBy { it.key }

        val relevantProductIds = linkedSetOf(selected.id)
        val selectedGroups = config.groups.filter { it.memberProductIds.contains(selected.id) }
        selectedGroups.flatMapTo(relevantProductIds) { it.memberProductIds }

        val serviceRelations = config.services.filter { relation ->
            relation.anchorProductId == selected.id || selectedGroups.any { it.id == relation.linkedGroupId }
        }
        serviceRelations.forEach { relation ->
            relevantProductIds += relation.anchorProductId
            config.groups.firstOrNull { it.id == relation.linkedGroupId }
                ?.memberProductIds
                ?.let { relevantProductIds.addAll(it) }
        }

        val checkpoints = dataSource.loadCheckpointValues(relevantProductIds, allWeeks)
        val lotsByProduct = dataSource.loadActiveMatrizLots(relevantProductIds)
        val movements = dataSource.loadRelevantMovements(relevantProductIds)
        val previousYearMonthConsumption = dataSource.loadConsumptionForRange(
            productIds = relevantProductIds,
            startInclusive = previousYearMonth.startInclusive,
            endExclusive = previousYearMonth.endExclusive
        )
        val productsById = products.associateBy { it.id }

        val targetWindowDays = PredictiveV3Time.targetWindowDays(now)
        val regime = PredictiveV3Time.regime(now)
        val elapsedDays = PredictiveV3Time.elapsedDaysInCurrentWeek(now)

        val individual = forecastProduct(
            product = selected,
            completeWeeks = completeWeeks,
            currentWeek = currentWeek,
            seasonalWeeks = seasonalWeeks,
            checkpoints = checkpoints,
            targetWindowDays = targetWindowDays,
            elapsedDays = elapsedDays,
            regime = regime
        )

        var individualOperational = operationalFromForecast(
            forecast = individual,
            weeklyKg = individual.forecastWeeklyKg,
            stockC04Kg = selected.stockCongelador04,
            stockMatrizKg = selected.stockMatriz,
            matrixReserveKg = matrixReserveForCommitment(selected),
            targetWindowDays = targetWindowDays,
            source = "PRODUCT_FORECAST"
        )

        var groupAnalysis = selectedGroups.firstOrNull()?.let { group ->
            buildGroupAnalysis(
                group = group,
                productsById = productsById,
                completeWeeks = completeWeeks,
                currentWeek = currentWeek,
                seasonalWeeks = seasonalWeeks,
                checkpoints = checkpoints,
                lotsByProduct = lotsByProduct,
                targetWindowDays = targetWindowDays,
                elapsedDays = elapsedDays,
                regime = regime,
                effectiveWeeklyOverride = null
            )
        }

        val serviceAnalysis = serviceRelations.firstOrNull()?.let { relation ->
            val linkedGroup = config.groups.firstOrNull { it.id == relation.linkedGroupId }
            if (linkedGroup == null) null else buildServiceAnalysis(
                relation = relation,
                linkedGroup = linkedGroup,
                productsById = productsById,
                completeWeeks = PredictiveV3Time.completeWeeks(now, SERVICE_HISTORY_WEEKS),
                currentWeek = currentWeek,
                seasonalWeeks = seasonalWeeks,
                checkpoints = checkpoints,
                targetWindowDays = targetWindowDays,
                elapsedDays = elapsedDays,
                regime = regime
            )
        }

        // Aplicar el reparto servicio Róbalo↔Pargos a la recomendación operativa,
        // sin alterar ni borrar la predicción cruda que usamos para comparar/backtesting.
        if (serviceAnalysis != null) {
            val relation = serviceAnalysis.relation
            if (selected.id == relation.anchorProductId) {
                individualOperational = operationalFromForecast(
                    forecast = individual,
                    weeklyKg = serviceAnalysis.allocation.anchorWeeklyKg,
                    stockC04Kg = selected.stockCongelador04,
                    stockMatrizKg = selected.stockMatriz,
                    matrixReserveKg = matrixReserveForCommitment(selected),
                    targetWindowDays = targetWindowDays,
                    source = "SERVICE_ALLOCATION",
                    extraReason = "Demanda ajustada por relación Róbalo/Pargos"
                )
            }

            val selectedGroup = groupAnalysis?.config
            if (selectedGroup != null && selectedGroup.id == relation.linkedGroupId) {
                groupAnalysis = buildGroupAnalysis(
                    group = selectedGroup,
                    productsById = productsById,
                    completeWeeks = completeWeeks,
                    currentWeek = currentWeek,
                    seasonalWeeks = seasonalWeeks,
                    checkpoints = checkpoints,
                    lotsByProduct = lotsByProduct,
                    targetWindowDays = targetWindowDays,
                    elapsedDays = elapsedDays,
                    regime = regime,
                    effectiveWeeklyOverride = serviceAnalysis.allocation.linkedGroupWeeklyKg
                )
            }
        }

        val purchaseSignal = buildPurchaseSignal(selected.id, movements)
        val transferSignal = buildTransferSignal(selected.id, movements)
        val effectiveCoverageDays = if (individualOperational.effectiveWeeklyKg > 0.01) {
            selected.totalStock.coerceAtLeast(0.0) / (individualOperational.effectiveWeeklyKg / 7.0)
        } else null
        val deviationPct = PredictiveV3Signals.recentDeviationPct(
            baselineWeeklyKg = individual.baselineWeeklyKg,
            liveWeeklyKg = individual.liveWeeklyPaceKg
        )
        val fifoSignal = PredictiveV3Signals.fifoSignal(lotsByProduct[selected.id].orEmpty(), now)
        val purchaseAttention = PredictiveV3Signals.purchaseAttention(
            purchase = purchaseSignal,
            coverageDays = effectiveCoverageDays,
            now = now
        )
        val smartReasons = PredictiveV3Signals.plainReasons(
            deviationPct = deviationPct,
            fifo = fifoSignal,
            purchaseAttention = purchaseAttention,
            transferSuggestedKg = individualOperational.suggestedTransferKg,
            limitedByReserve = individualOperational.limitedByMatrizReserve
        )

        return PredictiveV3Analysis(
            selectedProduct = selected,
            individualForecast = individual,
            individualOperational = individualOperational,
            groupAnalysis = groupAnalysis,
            serviceAnalysis = serviceAnalysis,
            purchaseSignal = purchaseSignal,
            transferSignal = transferSignal,
            historicalReference = HistoricalReferenceSignal(
                weekOneYearAgoKg = checkpoints[previousYearWeek.checkpointId(selected.id)],
                monthOneYearAgoKg = previousYearMonthConsumption[selected.id]
            ),
            fifoSignal = fifoSignal,
            purchaseAttention = purchaseAttention,
            effectiveCoverageDays = effectiveCoverageDays,
            recentDeviationPct = deviationPct,
            smartReasons = smartReasons,
            targetWindowDays = targetWindowDays,
            regime = regime,
            projectId = projectId
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
    ) = PredictiveV3Engine.forecast(
        ForecastContext(
            entityId = product.id,
            entityType = DemandEntityType.PRODUCT,
            completeWeeks = dataSource.seriesForProduct(product.id, completeWeeks, checkpoints),
            currentWeekConsumedKg = dataSource.currentWeekConsumed(product.id, currentWeek, checkpoints),
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

    private fun buildGroupAnalysis(
        group: PredictiveGroupConfig,
        productsById: Map<String, Product>,
        completeWeeks: List<PredictiveV3Time.WeekRef>,
        currentWeek: PredictiveV3Time.WeekRef,
        seasonalWeeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>,
        lotsByProduct: Map<String, List<com.cesar.bocana.predictive.v3.model.FifoLotSnapshot>>,
        targetWindowDays: Double,
        elapsedDays: Double,
        regime: com.cesar.bocana.predictive.v3.model.SeasonRegime,
        effectiveWeeklyOverride: Double?
    ): GroupAnalysisV3 {
        val members = group.memberProductIds.mapNotNull { productsById[it] }
        val memberSeries = members.associate { product ->
            product.id to dataSource.seriesForProduct(product.id, completeWeeks, checkpoints)
        }
        val groupSeries = aggregateGroupByRequestedWeeks(members.map { it.id }, completeWeeks, checkpoints)
        val currentConsumed = members.sumOf {
            dataSource.currentWeekConsumed(it.id, currentWeek, checkpoints)
        }
        val stockC04 = members.sumOf { it.stockCongelador04 }
        val stockMatriz = members.sumOf { it.stockMatriz }
        val stockTotal = members.sumOf { it.totalStock }
        val matrixReserve = members.sumOf(::matrixReserveForCommitment)
        val legacyC04 = members.sumOf { it.stockIdealC04 }

        val forecast = PredictiveV3Engine.forecast(
            ForecastContext(
                entityId = group.id,
                entityType = DemandEntityType.GROUP,
                completeWeeks = groupSeries,
                currentWeekConsumedKg = currentConsumed,
                currentWeekElapsedDays = elapsedDays,
                targetWindowDays = targetWindowDays,
                stockC04Kg = stockC04,
                stockMatrizKg = stockMatriz,
                stockTotalKg = stockTotal,
                generalReserveKg = matrixReserve,
                legacyC04ReferenceKg = legacyC04,
                seasonalReferenceWeeklyKg = averageGroupExisting(members.map { it.id }, seasonalWeeks, checkpoints),
                regime = regime
            )
        )

        val effectiveWeekly = effectiveWeeklyOverride ?: forecast.forecastWeeklyKg
        val operational = operationalFromForecast(
            forecast = forecast,
            weeklyKg = effectiveWeekly,
            stockC04Kg = stockC04,
            stockMatrizKg = stockMatriz,
            matrixReserveKg = matrixReserve,
            targetWindowDays = targetWindowDays,
            source = if (effectiveWeeklyOverride != null) "SERVICE_ALLOCATION" else "GROUP_FORECAST",
            extraReason = if (effectiveWeeklyOverride != null) "Demanda del grupo corregida por el servicio compartido" else null
        )

        val periodsUsed = groupSeries.map { it.periodKey }.toSet()
        val shares = PredictiveGroupEngine.learnTypicalShares(memberSeries, periodsUsed)
        val rulesByProduct = group.memberRules.associateBy { it.productId }

        val memberStates = members.map { product ->
            GroupMemberState(
                productId = product.id,
                typicalShare = shares[product.id] ?: 0.0,
                priorityWeight = rulesByProduct[product.id]?.priorityWeight ?: 1.0,
                stockC04Kg = product.stockCongelador04,
                stockMatrizKg = product.stockMatriz,
                generalReserveKg = matrixReserveForCommitment(product),
                fifoLots = lotsByProduct[product.id].orEmpty()
            )
        }

        val allocation = PredictiveGroupEngine.allocateTransfer(
            GroupAllocationInput(
                groupId = group.id,
                groupDynamicTargetKg = operational.dynamicC04TargetKg,
                totalTransferNeedKg = operational.suggestedTransferKg,
                members = memberStates
            )
        )

        return GroupAnalysisV3(
            config = group,
            forecast = forecast,
            operational = operational,
            allocation = allocation,
            memberNames = members.associate { it.id to it.name }
        )
    }

    private fun buildServiceAnalysis(
        relation: PredictiveServiceRelation,
        linkedGroup: PredictiveGroupConfig,
        productsById: Map<String, Product>,
        completeWeeks: List<PredictiveV3Time.WeekRef>,
        currentWeek: PredictiveV3Time.WeekRef,
        seasonalWeeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>,
        targetWindowDays: Double,
        elapsedDays: Double,
        regime: com.cesar.bocana.predictive.v3.model.SeasonRegime
    ): ServiceAnalysisV3? {
        val anchor = productsById[relation.anchorProductId] ?: return null
        val groupMembers = linkedGroup.memberProductIds.mapNotNull { productsById[it] }
        if (groupMembers.isEmpty()) return null

        val serviceSeries = aggregateGroupByRequestedWeeks(
            listOf(anchor.id) + groupMembers.map { it.id },
            completeWeeks,
            checkpoints
        )
        if (serviceSeries.size < 3) return null

        val anchorShares = mutableListOf<Double>()
        completeWeeks.forEach { week ->
            val anchorKg = checkpoints[week.checkpointId(anchor.id)] ?: 0.0
            val groupKg = groupMembers.sumOf { checkpoints[week.checkpointId(it.id)] ?: 0.0 }
            val total = anchorKg + groupKg
            if (total > 0.01) anchorShares += (anchorKg / total).coerceIn(0.0, 1.0)
        }
        if (anchorShares.size < 3) return null

        val normalShare = median(anchorShares)
        val minimumShare = percentile(anchorShares, 0.25).coerceAtMost(normalShare)

        val currentConsumed = dataSource.currentWeekConsumed(anchor.id, currentWeek, checkpoints) +
            groupMembers.sumOf { dataSource.currentWeekConsumed(it.id, currentWeek, checkpoints) }
        val serviceStockC04 = anchor.stockCongelador04 + groupMembers.sumOf { it.stockCongelador04 }
        val serviceStockMatriz = anchor.stockMatriz + groupMembers.sumOf { it.stockMatriz }
        val serviceTotal = anchor.totalStock + groupMembers.sumOf { it.totalStock }
        val serviceReserve = matrixReserveForCommitment(anchor) + groupMembers.sumOf(::matrixReserveForCommitment)

        val serviceForecast = PredictiveV3Engine.forecast(
            ForecastContext(
                entityId = relation.id,
                entityType = DemandEntityType.SERVICE,
                completeWeeks = serviceSeries,
                currentWeekConsumedKg = currentConsumed,
                currentWeekElapsedDays = elapsedDays,
                targetWindowDays = targetWindowDays,
                stockC04Kg = serviceStockC04,
                stockMatrizKg = serviceStockMatriz,
                stockTotalKg = serviceTotal,
                generalReserveKg = serviceReserve,
                seasonalReferenceWeeklyKg = averageGroupExisting(
                    listOf(anchor.id) + groupMembers.map { it.id }, seasonalWeeks, checkpoints
                ),
                regime = regime
            )
        )

        val allocation = PredictiveGroupEngine.allocateService(
            ServiceAllocationInput(
                serviceId = relation.id,
                totalWeeklyDemandKg = serviceForecast.forecastWeeklyKg,
                anchorNormalShare = normalShare,
                anchorMinimumShare = minimumShare,
                anchorStockAvailableKg = anchor.totalStock,
                anchorReserveKg = anchor.minStock,
                planningHorizonWeeks = SERVICE_STOCK_HORIZON_WEEKS
            )
        )

        return ServiceAnalysisV3(
            relation = relation,
            serviceForecast = serviceForecast,
            allocation = allocation,
            learnedAnchorNormalShare = normalShare,
            learnedAnchorMinimumShare = minimumShare,
            anchorProductName = anchor.name,
            linkedGroupName = linkedGroup.name
        )
    }

    private fun aggregateGroupByRequestedWeeks(
        productIds: List<String>,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): List<DemandPoint> {
        return weeks.mapNotNull { week ->
            var foundAny = false
            var total = 0.0
            productIds.forEach { productId ->
                checkpoints[week.checkpointId(productId)]?.let {
                    foundAny = true
                    total += it
                }
            }
            if (foundAny) DemandPoint(week.key, total) else null
        }
    }

    private fun averageExisting(
        productId: String,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): Double? {
        val values = weeks.mapNotNull { checkpoints[it.checkpointId(productId)] }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun averageGroupExisting(
        productIds: List<String>,
        weeks: List<PredictiveV3Time.WeekRef>,
        checkpoints: Map<String, Double>
    ): Double? {
        val values = weeks.mapNotNull { week ->
            var foundAny = false
            var total = 0.0
            productIds.forEach { id ->
                checkpoints[week.checkpointId(id)]?.let {
                    foundAny = true
                    total += it
                }
            }
            if (foundAny) total else null
        }
        return values.takeIf { it.isNotEmpty() }?.average()
    }

    private fun matrixReserveForCommitment(product: Product): Double {
        // minStock es una reserva general del producto. C04 ya forma parte del total;
        // sólo la parte de la reserva que aún no está cubierta por C04 restringe lo que
        // podemos comprometer desde Matriz para consumo operativo.
        return max(0.0, product.minStock - product.stockCongelador04)
            .coerceAtMost(product.stockMatriz.coerceAtLeast(0.0))
    }

    private fun operationalFromForecast(
        forecast: com.cesar.bocana.predictive.v3.model.ForecastResultV3,
        weeklyKg: Double,
        stockC04Kg: Double,
        stockMatrizKg: Double,
        matrixReserveKg: Double,
        targetWindowDays: Double,
        source: String,
        extraReason: String? = null
    ): OperationalRecommendation {
        val safeWeekly = weeklyKg.coerceAtLeast(0.0)
        val target = safeWeekly / 7.0 * (targetWindowDays + forecast.safetyDays)
        val need = max(0.0, target - stockC04Kg.coerceAtLeast(0.0))
        val usableMatriz = max(0.0, stockMatrizKg.coerceAtLeast(0.0) - matrixReserveKg.coerceAtLeast(0.0))
        val suggestion = min(need, usableMatriz)
        return OperationalRecommendation(
            effectiveWeeklyKg = safeWeekly,
            dynamicC04TargetKg = target,
            suggestedTransferKg = suggestion,
            limitedByMatrizReserve = need > usableMatriz + 0.01,
            source = source,
            reasons = buildList {
                if (extraReason != null) add(extraReason)
                add("Objetivo calculado por consumo esperado + seguridad, no por mínimo fijo")
                if (need <= 0.01) add("El stock actual de C04 ya cubre la ventana")
                if (need > usableMatriz + 0.01) add("La sugerencia se limita para respetar la reserva general")
            }
        )
    }

    private fun buildPurchaseSignal(
        productId: String,
        movements: List<StockMovement>
    ): PurchaseHistorySignal? {
        val purchases = movements.filter {
            it.productId == productId && it.type.name == "COMPRA" && it.quantity > 0.0
        }.sortedBy { it.timestamp?.time ?: Long.MIN_VALUE }
        if (purchases.isEmpty()) return null

        val intervals = purchases.zipWithNext().mapNotNull { (a, b) ->
            val aTime = a.timestamp?.time ?: return@mapNotNull null
            val bTime = b.timestamp?.time ?: return@mapNotNull null
            ((bTime - aTime).coerceAtLeast(0L) / 86_400_000.0)
        }
        return PurchaseHistorySignal(
            productId = productId,
            purchaseCount = purchases.size,
            totalPurchasedKg = purchases.sumOf { it.quantity.coerceAtLeast(0.0) },
            averagePurchaseKg = purchases.map { it.quantity.coerceAtLeast(0.0) }.average(),
            lastPurchaseAt = purchases.lastOrNull()?.timestamp,
            averageDaysBetweenPurchases = intervals.takeIf { it.isNotEmpty() }?.average()
        )
    }

    private fun buildTransferSignal(
        productId: String,
        movements: List<StockMovement>
    ): TransferPatternSignal? {
        val transfers = movements.filter {
            it.productId == productId && it.type.name == "TRASPASO_M_C04" && it.quantity > 0.0
        }.sortedBy { it.timestamp?.time ?: Long.MIN_VALUE }
        if (transfers.isEmpty()) return null

        val windowA = mutableListOf<Double>()
        val windowB = mutableListOf<Double>()
        transfers.forEach { movement ->
            val date = movement.timestamp ?: return@forEach
            val cal = Calendar.getInstance().apply { time = date }
            when (cal.get(Calendar.DAY_OF_WEEK)) {
                Calendar.MONDAY, Calendar.TUESDAY -> windowA += movement.quantity
                Calendar.THURSDAY, Calendar.FRIDAY -> windowB += movement.quantity
            }
        }
        return TransferPatternSignal(
            productId = productId,
            transferCount = transfers.size,
            averageKgAll = transfers.map { it.quantity }.average(),
            averageKgWindowA = windowA.takeIf { it.isNotEmpty() }?.average(),
            averageKgWindowB = windowB.takeIf { it.isNotEmpty() }?.average(),
            lastTransferAt = transfers.lastOrNull()?.timestamp
        )
    }


    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 0) (sorted[mid - 1] + sorted[mid]) / 2.0 else sorted[mid]
    }

    private fun percentile(values: List<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val index = ((sorted.size - 1) * p.coerceIn(0.0, 1.0)).toInt()
        return sorted[index]
    }
}
