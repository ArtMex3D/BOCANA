package com.cesar.bocana.predictive.v3

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.predictive.v3.data.PredictiveV3ConfigRepository
import com.cesar.bocana.predictive.v3.data.PredictiveV3HybridDataSource
import com.cesar.bocana.predictive.v3.data.PredictiveV3Snapshot
import com.cesar.bocana.predictive.v3.model.PredictiveV3Analysis
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.Calendar
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

/**
 * Coordinador central V3.
 *
 * V3 se revisa en silencio al iniciar/retomar la app y conserva en Room una fotografía
 * mínima del último cálculo válido. El análisis completo se mantiene en RAM durante
 * la sesión. El motor sigue siendo sólo lector/sugerente: nunca ejecuta movimientos.
 */
class PredictiveV3Manager private constructor(
    context: Context,
    private val firestore: FirebaseFirestore
) {

    data class RefreshResult(
        val checked: Int,
        val refreshed: Int,
        val failed: Int
    )

    private data class DataRevision(
        val dayKey: Int,
        val productUpdatedAtMillis: Long,
        val stockMatrizBits: Long,
        val stockC04Bits: Long,
        val totalStockBits: Long,
        val latestMovementMillis: Long,
        val packagingFingerprint: Int,
        val returnsFingerprint: Int,
        val configFingerprint: Int,
        val engineRevision: Int
    )

    private data class MemoryEntry(
        val revision: DataRevision,
        val analysis: PredictiveV3Analysis,
        val snapshot: PredictiveV3Snapshot,
        val relatedFingerprint: Int
    )

    private val debugLogsEnabled =
        (context.applicationContext.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
    private val database = AppDatabase.getDatabase(context.applicationContext)
    private val snapshotDao = database.predictiveV3SnapshotDao()
    private val productDao = database.productDao()
    private val configRepository = PredictiveV3ConfigRepository(firestore)
    private val dataSource = PredictiveV3HybridDataSource(database, firestore, debugLogsEnabled)
    private val coordinator = PredictiveV3Coordinator(
        firestore = firestore,
        dataSource = dataSource
    )

    private val memoryAnalysis = ConcurrentHashMap<String, MemoryEntry>()
    private val mutex = Mutex()

    fun observeSnapshots(): Flow<List<PredictiveV3Snapshot>> = snapshotDao.observeAll()

    suspend fun getSnapshot(productId: String): PredictiveV3Snapshot? =
        snapshotDao.getByProductId(productId)

    /** Sólo detalle que corresponde EXACTAMENTE a la fotografía mostrada. Cero lecturas remotas. */
    fun getCachedAnalysis(snapshot: PredictiveV3Snapshot): PredictiveV3Analysis? =
        memoryAnalysis[snapshot.productId]?.takeIf { it.snapshot == snapshot }?.analysis

    /**
     * Revisión silenciosa. Si la fotografía sigue vigente, no lee histórico remoto.
     * El cambio de día fuerza una revisión diaria; los cambios operativos sólo invalidan
     * el producto afectado y, si aplica, sus compañeros de grupo/relación.
     */
    suspend fun refreshAllIfNeeded(force: Boolean = false): RefreshResult = mutex.withLock {
        val products = productDao.getAllActiveProductsOnce()
        if (products.isEmpty()) return@withLock RefreshResult(0, 0, 0)

        val config = try {
            configRepository.load()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (e: Exception) {
            Log.w(TAG, "Configuración V3 no disponible; se conservan snapshots existentes.", e)
            return@withLock RefreshResult(products.size, 0, products.size)
        }
        val configFingerprint = fingerprintConfig(config)

        val existingById = snapshotDao.getAllOnce().associateBy { it.productId }
        val revisions = linkedMapOf<String, DataRevision>()
        val initiallyStale = linkedSetOf<String>()

        for (product in products) {
            val revision = buildRevision(product, configFingerprint)
            revisions[product.id] = revision
            val existing = existingById[product.id]
            if (force || existing == null || !matches(existing, revision)) {
                initiallyStale += product.id
            }
        }

        if (initiallyStale.isEmpty()) {
            trace("CACHE_VIGENTE | productos=${products.size} | consultasHistorial=0")
            return@withLock RefreshResult(products.size, 0, 0)
        }

        val activeIds = products.mapTo(linkedSetOf()) { it.id }
        val staleIds = expandRelatedIds(initiallyStale, activeIds, config)
        val productsById = products.associateBy { it.id }

        // Hay cambios reales: invalidar sólo el caché RAM de histórico y compartir
        // las nuevas lecturas entre todos los productos que se recalculen en este lote.
        dataSource.clearHistoricalCache()

        var refreshed = 0
        var failed = 0

        for (productId in staleIds) {
            val product = productsById[productId] ?: continue
            try {
                calculateAndStore(productId, configFingerprint, config)
                refreshed++
            } catch (cancelled: CancellationException) {
                // Cambiar de pantalla no es un error de cada producto ni un pronóstico vacío.
                throw cancelled
            } catch (e: Exception) {
                failed++
                Log.e(TAG, "Error refrescando V3 para ${product.name} ($productId)", e)
            }
        }

        RefreshResult(products.size, refreshed, failed)
    }

    /**
     * Análisis completo para popup/futuros módulos.
     * RAM primero. Si sólo existe el snapshot persistente, calcula el detalle una vez
     * durante la sesión y vuelve a dejarlo en memoria.
     */
    suspend fun getAnalysis(productId: String, force: Boolean = false): PredictiveV3Analysis = mutex.withLock {
        val product = productDao.getProductByIdOnce(productId)
            ?: error("Producto no encontrado para V3: $productId")

        val config = configRepository.load()
        val configFingerprint = fingerprintConfig(config)
        val revision = buildRevision(product, configFingerprint)
        val persisted = snapshotDao.getByProductId(productId)
        val memory = memoryAnalysis[productId]
        val related = relatedFingerprint(productId, config)

        if (!force && persisted != null && matches(persisted, revision) &&
            memory != null && memory.revision == revision && memory.snapshot == persisted &&
            memory.relatedFingerprint == related) {
            trace("ANALISIS_RAM | producto=$productId | revision=${persisted.calculatedAtMillis} | consultasHistorial=0")
            return@withLock memory.analysis
        }

        // Si el snapshot está vencido, no reutilizar histórico de antes del movimiento/cambio.
        if (force || persisted == null || !matches(persisted, revision) ||
            (memory != null && memory.relatedFingerprint != related)) {
            dataSource.clearHistoricalCache()
        }
        calculateAndStore(productId, configFingerprint, config)
    }

    /** Todos los consumidores publican por esta ruta. No cambia la matemática ni el planificador. */
    private suspend fun calculateAndStore(
        productId: String,
        configFingerprint: Int,
        config: PredictiveV3ConfigRepository.ConfigBundle
    ): PredictiveV3Analysis {
        val beforeProduct = productDao.getProductByIdOnce(productId)
            ?: error("Producto no encontrado para V3: $productId")
        val before = buildRevision(beforeProduct, configFingerprint)
        val relatedBefore = relatedFingerprint(productId, config)
        val analysis = coordinator.analyzeProduct(productId)
        val latestProduct = productDao.getProductByIdOnce(productId)
            ?: error("Producto retirado durante el cálculo V3")
        val after = buildRevision(latestProduct, configFingerprint)
        val relatedAfter = relatedFingerprint(productId, config)

        // No etiquetar un análisis viejo con la revisión del stock que llegó durante la descarga.
        if (before != after || relatedBefore != relatedAfter || analysis.selectedProduct != latestProduct ||
            fingerprintConfig(configRepository.load()) != configFingerprint) {
            trace("CONSERVADO | producto=$productId | motivo=DATOS_CAMBIARON_DURANTE_CALCULO")
            dataSource.clearHistoricalCache()
            error("Los datos se están sincronizando; se conserva el último resultado guardado")
        }

        val partial = analysis.smartReasons.any { it.startsWith("Información parcial:") }
        if (partial || !dataSource.lastCheckpointIsCanonical || dataSource.lastCheckpointCount == 0) {
            trace("CONSERVADO | producto=$productId | motivo=LECTURA_PARCIAL | " +
                dataSource.lastCheckpointSummary + " | avisos=${analysis.smartReasons.filter { it.startsWith("Información parcial:") }}")
            error("No se pudo validar el historial y el inventario; se conserva el último resultado guardado")
        }

        val snapshot = snapshotFromAnalysis(analysis, after)
        check(listOf(snapshot.forecastWeeklyKg, snapshot.baselineWeeklyKg, snapshot.primaryTotalStockKg,
            snapshot.highScenarioWeeklyKg, snapshot.lowScenarioWeeklyKg).all { it.isFinite() && it >= 0.0 }) {
            "Resultado V3 no válido; no se reemplaza el último resultado guardado"
        }

        // Poner el detalle antes del upsert permite que el observador Room lo encuentre al emitir.
        val previousMemory = memoryAnalysis[productId]
        memoryAnalysis[productId] = MemoryEntry(after, analysis, snapshot, relatedAfter)
        try {
            snapshotDao.upsert(snapshot)
        } catch (e: Exception) {
            if (previousMemory != null) memoryAnalysis[productId] = previousMemory
            else memoryAnalysis.remove(productId)
            throw e
        }
        val centralForecast = analysis.groupAnalysis?.forecast ?: analysis.individualForecast
        trace("RESULTADO_CENTRAL | producto=$productId | grupo=${snapshot.groupId ?: "INDIVIDUAL"} | " +
            "matrizIndividual=${latestProduct.stockMatriz} | c04Individual=${latestProduct.stockCongelador04} | " +
            "stockCobertura=${snapshot.primaryTotalStockKg} | promedioSemanal=${snapshot.baselineWeeklyKg} | " +
            "ritmoVivo=${centralForecast.liveWeeklyPaceKg} | tendencia=${centralForecast.trendSignal} | " +
            "demandaSemanal=${snapshot.forecastWeeklyKg} | dias=${snapshot.coverageDays} | " +
            "revision=${snapshot.calculatedAtMillis} | ${dataSource.lastCheckpointSummary}")
        return analysis
    }

    /** Invalida RAM también si cambió un compañero de grupo/servicio, no sólo el producto abierto. */
    private suspend fun relatedFingerprint(
        productId: String,
        config: PredictiveV3ConfigRepository.ConfigBundle
    ): Int {
        val products = productDao.getAllActiveProductsOnce()
        val ids = expandRelatedIds(setOf(productId), products.map { it.id }.toSet(), config)
        return products.filter { it.id in ids }.sortedBy { it.id }
            .fold(1) { hash, product -> 31 * hash + product.hashCode() }
    }

    private fun trace(message: String) {
        if (debugLogsEnabled) Log.d("BocanaCoberturaTrace", message)
    }

    fun clearSessionMemory() {
        memoryAnalysis.clear()
    }

    private suspend fun buildRevision(product: Product, configFingerprint: Int): DataRevision {
        val calendar = Calendar.getInstance()
        val dayKey = calendar.get(Calendar.YEAR) * 1000 + calendar.get(Calendar.DAY_OF_YEAR)

        val latestMovement = database.stockMovementDao()
            .getMovementsForProductOnce(product.id)
            .maxOfOrNull { it.timestamp?.time ?: 0L }
            ?: 0L

        val packagingFingerprint = database.packagingDao()
            .getPackagingTasksForProductOnce(product.id)
            .fold(1) { acc, task ->
                var value = 31 * acc + task.id.hashCode()
                value = 31 * value + task.quantityReceived.toBits().hashCode()
                value = 31 * value + (task.receivedAt?.time ?: 0L).hashCode()
                value
            }

        val returnsFingerprint = database.devolucionDao()
            .getDevolucionesForProductOnce(product.id)
            .fold(1) { acc, item ->
                var value = 31 * acc + item.id.hashCode()
                value = 31 * value + item.quantity.toBits().hashCode()
                value = 31 * value + item.status.name.hashCode()
                value = 31 * value + (item.completedAt?.time ?: 0L).hashCode()
                value
            }

        return DataRevision(
            dayKey = dayKey,
            productUpdatedAtMillis = product.updatedAt?.time ?: 0L,
            stockMatrizBits = product.stockMatriz.toBits(),
            stockC04Bits = product.stockCongelador04.toBits(),
            totalStockBits = product.totalStock.toBits(),
            latestMovementMillis = latestMovement,
            packagingFingerprint = packagingFingerprint,
            returnsFingerprint = returnsFingerprint,
            configFingerprint = configFingerprint,
            engineRevision = ENGINE_REVISION
        )
    }

    private fun matches(snapshot: PredictiveV3Snapshot, revision: DataRevision): Boolean =
        snapshot.dayKey == revision.dayKey &&
            snapshot.productUpdatedAtMillis == revision.productUpdatedAtMillis &&
            snapshot.stockMatrizBits == revision.stockMatrizBits &&
            snapshot.stockC04Bits == revision.stockC04Bits &&
            snapshot.totalStockBits == revision.totalStockBits &&
            snapshot.latestMovementMillis == revision.latestMovementMillis &&
            snapshot.packagingFingerprint == revision.packagingFingerprint &&
            snapshot.returnsFingerprint == revision.returnsFingerprint &&
            snapshot.configFingerprint == revision.configFingerprint &&
            snapshot.engineRevision == revision.engineRevision

    private fun snapshotFromAnalysis(
        analysis: PredictiveV3Analysis,
        revision: DataRevision
    ): PredictiveV3Snapshot {
        val group = analysis.groupAnalysis
        val forecast = group?.forecast ?: analysis.individualForecast
        val operational = group?.operational ?: analysis.individualOperational

        val groupStock = analysis.groupInventory?.let { inventory ->
            val matriz = inventory.matrizByMonth.sumOf { it.totalKg.coerceAtLeast(0.0) }
            val c04 = inventory.c04ByMonth.sumOf { it.totalKg.coerceAtLeast(0.0) }
            matriz to c04
        }

        val primaryTotalStock = if (group != null) {
            groupStock?.let { it.first + it.second }
                ?: analysis.selectedProduct.totalStock.coerceAtLeast(0.0)
        } else {
            analysis.selectedProduct.totalStock.coerceAtLeast(0.0)
        }

        val c04Current = if (group != null) {
            groupStock?.second ?: analysis.selectedProduct.stockCongelador04.coerceAtLeast(0.0)
        } else {
            analysis.selectedProduct.stockCongelador04.coerceAtLeast(0.0)
        }

        val coverage = if (group != null) {
            if (operational.effectiveWeeklyKg > 0.01) {
                primaryTotalStock / (operational.effectiveWeeklyKg / 7.0)
            } else {
                group.forecast.coverageDays
            }
        } else {
            analysis.effectiveCoverageDays
        }
        val coverageInt = coverage?.coerceAtLeast(0.0)?.roundToInt()

        fun daysFor(weeklyKg: Double): Int? {
            if (weeklyKg <= 0.01) return null
            return (primaryTotalStock / (weeklyKg / 7.0)).coerceAtLeast(0.0).roundToInt()
        }

        val highDays = daysFor(forecast.highScenarioWeeklyKg)
        val lowDays = daysFor(forecast.lowScenarioWeeklyKg)
        val probableMin = listOfNotNull(highDays, coverageInt, lowDays).minOrNull()
        val probableMax = listOfNotNull(highDays, coverageInt, lowDays).maxOrNull()

        return PredictiveV3Snapshot(
            productId = analysis.selectedProduct.id,
            coverageDays = coverageInt,
            probableMinDays = probableMin,
            probableMaxDays = probableMax,
            forecastWeeklyKg = operational.effectiveWeeklyKg.coerceAtLeast(0.0),
            baselineWeeklyKg = forecast.baselineWeeklyKg.coerceAtLeast(0.0),
            highScenarioWeeklyKg = forecast.highScenarioWeeklyKg.coerceAtLeast(0.0),
            lowScenarioWeeklyKg = forecast.lowScenarioWeeklyKg.coerceAtLeast(0.0),
            primaryTotalStockKg = primaryTotalStock,
            c04CurrentKg = c04Current,
            dynamicC04TargetKg = operational.dynamicC04TargetKg.coerceAtLeast(0.0),
            suggestedTransferKg = operational.suggestedTransferKg.coerceAtLeast(0.0),
            regime = analysis.regime.name,
            groupId = group?.config?.id,
            groupName = group?.config?.name?.takeIf { it.isNotBlank() },
            calculatedAtMillis = System.currentTimeMillis(),
            dayKey = revision.dayKey,
            productUpdatedAtMillis = revision.productUpdatedAtMillis,
            stockMatrizBits = revision.stockMatrizBits,
            stockC04Bits = revision.stockC04Bits,
            totalStockBits = revision.totalStockBits,
            latestMovementMillis = revision.latestMovementMillis,
            packagingFingerprint = revision.packagingFingerprint,
            returnsFingerprint = revision.returnsFingerprint,
            configFingerprint = revision.configFingerprint,
            engineRevision = revision.engineRevision
        )
    }

    private fun expandRelatedIds(
        initial: Set<String>,
        activeIds: Set<String>,
        config: PredictiveV3ConfigRepository.ConfigBundle
    ): Set<String> {
        val result = initial.toMutableSet()
        var changed: Boolean
        do {
            changed = false

            for (group in config.groups) {
                val members = group.memberProductIds.filter { it in activeIds }.toSet()
                if (members.any { it in result }) {
                    members.forEach { if (result.add(it)) changed = true }
                }
            }

            val groupsById = config.groups.associateBy { it.id }
            for (service in config.services) {
                val anchors = service.effectiveAnchorProductIds().filter { it in activeIds }.toSet()
                val linkedMembers = groupsById[service.linkedGroupId]
                    ?.memberProductIds
                    ?.filter { it in activeIds }
                    ?.toSet()
                    .orEmpty()

                if ((anchors + linkedMembers).any { it in result }) {
                    (anchors + linkedMembers).forEach { if (result.add(it)) changed = true }
                }
            }
        } while (changed)

        return result
    }

    private fun fingerprintConfig(config: PredictiveV3ConfigRepository.ConfigBundle): Int {
        var hash = 1

        config.groups.sortedBy { it.id }.forEach { group ->
            hash = 31 * hash + group.id.hashCode()
            hash = 31 * hash + group.name.hashCode()
            hash = 31 * hash + group.c04GroupTargetKg.toBits().hashCode()
            hash = 31 * hash + group.primaryMinimumC04Kg.toBits().hashCode()
            hash = 31 * hash + (group.primaryProductId ?: "").hashCode()
            group.memberProductIds.sorted().forEach { hash = 31 * hash + it.hashCode() }
            group.historicalProductIds.sorted().forEach { hash = 31 * hash + it.hashCode() }
            group.secondaryProductIds.sorted().forEach { hash = 31 * hash + it.hashCode() }
            hash = 31 * hash + group.enabled.hashCode()
            group.memberRules.sortedBy { it.productId }.forEach { rule ->
                hash = 31 * hash + rule.productId.hashCode()
                hash = 31 * hash + rule.priorityWeight.toBits().hashCode()
                hash = 31 * hash + rule.enabled.hashCode()
            }
        }

        config.services.sortedBy { it.id }.forEach { service ->
            hash = 31 * hash + service.id.hashCode()
            hash = 31 * hash + service.name.hashCode()
            hash = 31 * hash + service.linkedGroupId.hashCode()
            hash = 31 * hash + service.relationMode.hashCode()
            hash = 31 * hash + (service.primaryAnchorProductId ?: "").hashCode()
            hash = 31 * hash + (service.preferredGroupProductId ?: "").hashCode()
            hash = 31 * hash + service.enabled.hashCode()
            service.effectiveAnchorProductIds().sorted().forEach { hash = 31 * hash + it.hashCode() }
            service.historicalAnchorProductIds.sorted().forEach { hash = 31 * hash + it.hashCode() }
        }

        return hash
    }

    companion object {
        private const val TAG = "PredictiveV3Manager"
        const val ENGINE_REVISION = 2

        @Volatile
        private var INSTANCE: PredictiveV3Manager? = null

        fun getInstance(
            context: Context,
            firestore: FirebaseFirestore = FirebaseFirestore.getInstance()
        ): PredictiveV3Manager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: PredictiveV3Manager(context.applicationContext, firestore).also {
                    INSTANCE = it
                }
            }
        }
    }
}
