package com.cesar.bocana.ui.traspasos.plan

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.LoteDesglosado
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.TraspasoEstado
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.data.model.DetalleTraspasoPlan
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.predictive.v3.PredictiveTransferPlannerV3
import com.cesar.bocana.predictive.v3.TransferPlanV3
import com.cesar.bocana.predictive.v3.data.PredictiveV3ConfigRepository
import com.cesar.bocana.predictive.v3.data.PredictiveV3RoomDataSource
import com.cesar.bocana.predictive.v3.data.PredictiveV3Time
import com.cesar.bocana.util.StockQuantityPolicy
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * Caché del plan DEL DÍA.
 *
 * A diferencia de la versión anterior, "Continuar" no restaura una fotografía vieja:
 * conserva las decisiones humanas y vuelve a leer Room para detectar si cambió el
 * inventario (por ejemplo después de empacar mercancía).
 */
object TraspasoPlanCache {
    var planGuardado: List<TraspasoSugerenciaItem>? = null
    var timestamp: Long = 0

    var manualOverridesKg: Map<String, Double> = emptyMap()
    var manualRequestedUnits: Map<String, Int> = emptyMap()
    var manualSelectedLots: Map<String, List<StockLot>> = emptyMap()
    var manualExactBreakdowns: Map<String, List<LoteDesglosado>> = emptyMap()

    fun esValido(): Boolean {
        if (planGuardado == null) return false
        val zonaHoraria = TimeZone.getDefault()
        val ahora = Calendar.getInstance(zonaHoraria)
        val guardado = Calendar.getInstance(zonaHoraria).apply { timeInMillis = timestamp }
        return ahora.get(Calendar.DAY_OF_YEAR) == guardado.get(Calendar.DAY_OF_YEAR) &&
            ahora.get(Calendar.YEAR) == guardado.get(Calendar.YEAR)
    }

    fun limpiar() {
        planGuardado = null
        timestamp = 0
        manualOverridesKg = emptyMap()
        manualRequestedUnits = emptyMap()
        manualSelectedLots = emptyMap()
        manualExactBreakdowns = emptyMap()
    }
}

data class PlanTraspasoUiState(
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val sugerencias: List<TraspasoSugerenciaItem> = emptyList(),
    val error: String? = null,
    val snackbarMessage: String? = null,
    val preguntaCache: Boolean = false,
    val planGuardadoExitoso: Boolean = false
)

/**
 * Super Traspaso Predictivo V3.
 *
 * - Lecturas operativas desde Room.
 * - Una sola fotografía V3 para toda la pantalla.
 * - Firestore se usa para configuración (cacheada) y para guardar el PDF/plan.
 * - El motor sólo sugiere: nunca modifica inventario.
 */
class PlanificarTraspasoViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val firestore = Firebase.firestore
    private val auth = Firebase.auth

    private val localDb = AppDatabase.getDatabase(application.applicationContext)
    private val productDao = localDb.productDao()
    private val lotDao = localDb.stockLotDao()
    private val packagingDao = localDb.packagingDao()

    private val localSource = PredictiveV3RoomDataSource(localDb)
    private val configRepository = PredictiveV3ConfigRepository(firestore)

    private val _uiState = MutableStateFlow(PlanTraspasoUiState())
    val uiState: StateFlow<PlanTraspasoUiState> = _uiState

    private var allLotesEnMatriz: Map<String, List<StockLot>> = emptyMap()
    private var allOpenLots: List<StockLot> = emptyList()
    private var activeProducts: List<Product> = emptyList()
    private var lastSnapshot: PredictiveTransferPlannerV3.Snapshot? = null
    private var lastPlanV3: TransferPlanV3? = null
    private var baselinePlanV3: TransferPlanV3? = null

    /** Decisión humana en kg equivalente. Se puede cambiar cuantas veces quiera. */
    private val manualOverridesKg = linkedMapOf<String, Double>()

    /** Intención humana en unidades físicas: 50 cajas aunque hoy sólo existan 10. */
    private val manualRequestedUnits = linkedMapOf<String, Int>()

    /** Selector manual de lotes: se respeta mientras el usuario no regenere. */
    private val manualSelectedLots = linkedMapOf<String, List<StockLot>>()

    /** Desglose manual exacto por lote. */
    private val manualExactBreakdowns = linkedMapOf<String, List<LoteDesglosado>>()

    private val TAG = "PlanTraspasoViewModel"

    init {
        if (TraspasoPlanCache.esValido()) {
            _uiState.update { it.copy(preguntaCache = true, isLoading = false) }
        } else {
            cargarPlanDeTraspaso(descartarCache = true)
        }
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun onPlanGuardadoNavegado() {
        _uiState.update { it.copy(planGuardadoExitoso = false) }
    }

    fun onDialogoMostrado() {
        _uiState.update { it.copy(preguntaCache = false) }
    }

    /**
     * Continuar = conservar decisiones humanas PERO refrescar inventario local.
     * Esto permite salir a Empaque, regresar y ver las cajas recién creadas.
     */
    fun cargarPlanDesdeCache() {
        manualOverridesKg.clear()
        manualOverridesKg.putAll(TraspasoPlanCache.manualOverridesKg)

        manualRequestedUnits.clear()
        manualRequestedUnits.putAll(TraspasoPlanCache.manualRequestedUnits)

        manualSelectedLots.clear()
        manualSelectedLots.putAll(TraspasoPlanCache.manualSelectedLots)

        manualExactBreakdowns.clear()
        manualExactBreakdowns.putAll(TraspasoPlanCache.manualExactBreakdowns)

        cargarPlanDeTraspaso(descartarCache = false)
    }

    /**
     * Reiniciar/regenerar borra únicamente decisiones temporales del plan.
     * No toca stock, lotes ni configuración.
     */
    fun regenerarSugerencias() {
        manualOverridesKg.clear()
        manualRequestedUnits.clear()
        manualSelectedLots.clear()
        manualExactBreakdowns.clear()
        TraspasoPlanCache.limpiar()
        cargarPlanDeTraspaso(descartarCache = false)
    }

    fun cargarPlanDeTraspaso(descartarCache: Boolean) {
        if (descartarCache) {
            manualOverridesKg.clear()
            manualRequestedUnits.clear()
            manualSelectedLots.clear()
            manualExactBreakdowns.clear()
            TraspasoPlanCache.limpiar()
        }

        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    error = null,
                    snackbarMessage = null
                )
            }

            try {
                val products = productDao.getAllActiveProductsOnce()
                if (products.isEmpty()) {
                    error("Room todavía no tiene productos sincronizados. Espera unos segundos y vuelve a intentar.")
                }

                activeProducts = products

                allOpenLots = lotDao.getAllOpenLotsOnce()
                val packagedMatriz = lotDao.getOpenPackagedMatrizLotsOnce()
                    .filter {
                        it.estadoTraspaso == null &&
                            !it.isDepleted &&
                            StockQuantityPolicy.isUsable(it.currentQuantity)
                    }
                    .sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE }

                allLotesEnMatriz = packagedMatriz.groupBy { it.productId }

                val config = configRepository.load()
                val pendingPackaging = packagingDao.getAllPackagingTasksOnce()

                val now = Date()
                val weeks = (
                    PredictiveV3Time.completeWeeks(now, 16) +
                        listOf(PredictiveV3Time.currentWeek(now)) +
                        PredictiveV3Time.seasonalWeeks(now)
                    ).distinctBy { it.key }

                val checkpoints = localSource.loadCheckpointValues(
                    productIds = products.map { it.id }.toSet(),
                    weeks = weeks
                )

                val snapshot = PredictiveTransferPlannerV3.Snapshot(
                    products = products,
                    groups = config.groups,
                    services = config.services,
                    openLots = allOpenLots,
                    pendingPackaging = pendingPackaging,
                    checkpoints = checkpoints,
                    now = now
                )

                lastSnapshot = snapshot

                // La línea base conserva lo que V3 habría sugerido sin edición humana.
                // Así podemos explicar "V3 sugería X porque..." sin perder la decisión original.
                val baseline = PredictiveTransferPlannerV3.plan(
                    snapshot = snapshot,
                    manualOverridesKg = emptyMap()
                )
                baselinePlanV3 = baseline

                val plan = if (manualOverridesKg.isEmpty()) {
                    baseline
                } else {
                    PredictiveTransferPlannerV3.plan(
                        snapshot = snapshot,
                        manualOverridesKg = manualOverridesKg
                    )
                }
                lastPlanV3 = plan

                val sugerencias = buildSuggestionItems(plan)

                _uiState.update {
                    it.copy(
                        isLoading = false,
                        sugerencias = sugerencias,
                        error = null
                    )
                }
                guardarEnCache(sugerencias)
            } catch (e: Exception) {
                Log.e(TAG, "Error en cargarPlanDeTraspaso", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = e.localizedMessage ?: "Error desconocido"
                    )
                }
            }
        }
    }

    /**
     * Guarda el PLAN/PDF. No ejecuta inventario.
     *
     * Si una decisión humana pide más cajas de las físicamente existentes,
     * conserva la intención en pantalla pero bloquea el PDF hasta que el usuario
     * empaque o reduzca la cantidad.
     */
    fun guardarPlanEnFirestore(fechaPlan: Date) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.update { it.copy(snackbarMessage = "Error: Usuario no autenticado.") }
            return
        }

        val currentItems = _uiState.value.sugerencias

        val shortage = currentItems.firstOrNull { item ->
            if (!item.incluidoEnPdf || item.product.id == "FILA_VACIA") return@firstOrNull false

            val requestedUnits = item.cantidadSolicitadaUnidades
            if (requestedUnits != null) {
                requestedUnits > item.cantidadEditadaUnidades
            } else {
                item.v3RequestedKg > item.sugerenciaKg + 0.10
            }
        }

        if (shortage != null) {
            val requestedUnits = shortage.cantidadSolicitadaUnidades
            val message = if (requestedUnits != null) {
                "${shortage.product.name}: pediste $requestedUnits ${pluralUnit(shortage.unidadDeEmpaqueEditada, requestedUnits)}, " +
                    "pero hoy hay ${shortage.cantidadEditadaUnidades} disponibles."
            } else {
                "${shortage.product.name}: la cantidad pedida excede el stock empacado disponible."
            }
            _uiState.update {
                it.copy(
                    snackbarMessage = "$message Empaca o ajusta la cantidad antes de generar el PDF."
                )
            }
            return
        }

        val planParaGuardar = currentItems.filter { it.incluidoEnPdf }
        if (planParaGuardar.isEmpty()) {
            _uiState.update {
                it.copy(snackbarMessage = "No hay productos seleccionados para el traspaso.")
            }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            try {
                val planDocRef = firestore
                    .collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
                    .document()

                val planPrincipal = TraspasoPlanificado(
                    id = planDocRef.id,
                    createdAt = Date(),
                    createdBy = currentUser.displayName ?: currentUser.email ?: "Desconocido",
                    fechaPlan = fechaPlan,
                    estado = TraspasoEstado.PENDIENTE
                )

                val batch: WriteBatch = firestore.batch()
                batch.set(planDocRef, planPrincipal)

                planParaGuardar.forEachIndexed { index, item ->
                    val detalleDocRef = planDocRef.collection("detalles").document()

                    val detalle = DetalleTraspasoPlan(
                        id = detalleDocRef.id,
                        productId = item.product.id,
                        productName = item.product.name,
                        sugerenciaKg = item.sugerenciaKg,
                        sugerenciaUnidades = item.cantidadEditadaUnidades,
                        unidadDeEmpaque = item.unidadDeEmpaqueEditada,
                        lotesSugeridos = item.lotesParaTraspaso,
                        orden = index
                    )

                    batch.set(detalleDocRef, detalle)
                }

                batch.commit().await()

                TraspasoPlanCache.limpiar()
                manualOverridesKg.clear()
                manualRequestedUnits.clear()
                manualSelectedLots.clear()
                manualExactBreakdowns.clear()

                _uiState.update {
                    it.copy(
                        isSaving = false,
                        planGuardadoExitoso = true
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al guardar plan de traspaso", e)
                _uiState.update {
                    it.copy(
                        isSaving = false,
                        snackbarMessage = "Error al guardar: ${e.message}"
                    )
                }
            }
        }
    }

    private fun buildSuggestionItems(
        plan: TransferPlanV3
    ): List<TraspasoSugerenciaItem> {
        val orderedProducts = activeProducts.sortedWith(
            compareBy<Product> { it.ordenTraspaso }
                .thenBy { it.name.lowercase(Locale.getDefault()) }
        )

        return orderedProducts.map { product ->
            val meta = plan.products[product.id]
            val baselineMeta = baselinePlanV3?.products?.get(product.id) ?: meta
            val lots = allLotesEnMatriz[product.id].orEmpty()
            val selectedLots = manualSelectedLots[product.id]
                ?.filter { selected -> lots.any { it.id == selected.id } }
                ?.takeIf { it.isNotEmpty() }

            val physicalLots = selectedLots ?: lots
            val requestedKg = meta?.requestedKg?.coerceAtLeast(0.0)
                ?: legacyNeed(product)

            val isFixed = isTraspasoFijo(product, lots)
            val exact = manualExactBreakdowns[product.id]

            val breakdown: List<LoteDesglosado>
            val actualKg: Double
            val actualUnits: Int
            val requestedUnits: Int?
            val physicalUnit: String
            val availableUnits: Int?

            if (exact != null) {
                breakdown = exact
                actualKg = exact.sumOf { it.cantidadATomarKg }
                actualUnits = exact.sumOf {
                    (it.cantidadATomarUnidades ?: 0.0).toInt()
                }
                requestedUnits = manualRequestedUnits[product.id]
                    ?: actualUnits.takeIf { isFixed }
                physicalUnit = exact.firstOrNull()?.loteUnidad
                    ?: getUnidadReal(product, lots)
                availableUnits = if (isFixed) totalUnitsAvailable(lots) else null
            } else if (isFixed) {
                physicalUnit = getUnidadReal(product, lots)

                val autoUnits = convertirKgAUnidades(
                    kg = requestedKg,
                    lotesDisponibles = lots
                ).first

                val desiredUnits = manualRequestedUnits[product.id]
                    ?: autoUnits

                val available = totalUnitsAvailable(physicalLots)
                val actualDesired = min(desiredUnits, available)

                val result = desglosarLotesParaCantidadUnidades(
                    unidadesNecesarias = actualDesired,
                    lotesDisponibles = physicalLots
                )

                breakdown = result.first
                actualKg = result.second
                actualUnits = breakdown.sumOf {
                    (it.cantidadATomarUnidades ?: 0.0).toInt()
                }
                requestedUnits = desiredUnits
                availableUnits = totalUnitsAvailable(lots)
            } else {
                physicalUnit = "Kg"
                val result = desglosarLotesParaCantidadKg(
                    cantidadNecesariaKg = requestedKg,
                    lotesDisponibles = physicalLots
                )
                breakdown = result.first
                actualKg = result.second
                actualUnits = 0
                requestedUnits = null
                availableUnits = null
            }

            val physicallyShort = if (requestedUnits != null) {
                requestedUnits > actualUnits
            } else {
                requestedKg > actualKg + 0.10
            }

            val pendingKg = meta?.pendingPackagingKg ?: 0.0
            val baseReason = meta?.message
            val manual = manualOverridesKg.containsKey(product.id) ||
                manualRequestedUnits.containsKey(product.id) ||
                manualExactBreakdowns.containsKey(product.id)

            val originalKg = baselineMeta?.requestedKg ?: requestedKg
            val originalPhysicalText = if (isFixed) {
                val units = convertirKgAUnidades(
                    kg = originalKg,
                    lotesDisponibles = lots
                ).first
                "$units ${pluralUnit(physicalUnit, units)}"
            } else {
                "${format1(originalKg)} kg"
            }
            val baselineReason = baselineMeta?.message
                ?.takeIf { it.isNotBlank() }

            val contextualReason = when {
                physicallyShort && pendingKg > 0.10 && requestedUnits != null ->
                    "Pediste $requestedUnits ${pluralUnit(physicalUnit, requestedUnits)}; " +
                        "hay $actualUnits disponibles y ${format1(pendingKg)} kg pendientes de empacar."

                physicallyShort && pendingKg > 0.10 ->
                    "Falta mercancía empacada; hay ${format1(pendingKg)} kg pendientes de empacar."

                physicallyShort && requestedUnits != null ->
                    "Pediste $requestedUnits ${pluralUnit(physicalUnit, requestedUnits)}; hoy hay $actualUnits disponibles."

                physicallyShort ->
                    "La sugerencia supera el stock empacado disponible."

                manual && kotlin.math.abs(requestedKg - originalKg) > 0.01 ->
                    buildString {
                        append("V3 sugería $originalPhysicalText")
                        if (baselineReason != null) append(". $baselineReason")
                        else append(". El resto se recalculó respetando tu cantidad.")
                    }

                else -> baseReason
            }

            TraspasoSugerenciaItem(
                product = product,
                sugerenciaKg = actualKg,
                lotesParaTraspaso = breakdown,
                impactoStockMatriz = (product.stockMatriz - actualKg).coerceAtLeast(0.0),
                incluidoEnPdf = actualKg > 0.0 || manual,
                cantidadEditadaUnidades = actualUnits,
                unidadDeEmpaqueEditada = physicalUnit,
                lotesSeleccionadosManualmente = selectedLots,
                isRecalculating = false,
                isSugerenciaLiquidacion = false,
                v3OriginalSuggestedKg = originalKg,
                v3RequestedKg = requestedKg,
                cantidadSolicitadaUnidades = requestedUnits,
                v3AvailablePackagedKg = meta?.availablePackagedKg ?: actualKg,
                v3AvailableUnits = availableUnits,
                v3PendingPackagingKg = pendingKg,
                v3ReasonText = contextualReason,
                v3GroupId = meta?.groupId,
                v3GroupName = meta?.groupName,
                v3GroupHabitualTargetKg = meta?.groupHabitualTargetKg,
                v3GroupDynamicTargetKg = meta?.groupDynamicTargetKg,
                v3ShowGroupHeader = meta?.showGroupHeader == true,
                v3IsGroupPrimary = meta?.isGroupPrimary == true,
                v3ManualOverride = manual || meta?.isManualOverride == true
            )
        }
    }

    fun actualizarInclusionEnPdf(
        productId: String,
        incluido: Boolean
    ) {
        _uiState.update { currentState ->
            val nuevasSugerencias = currentState.sugerencias.map {
                if (it.product.id == productId) {
                    it.copy(incluidoEnPdf = incluido)
                } else {
                    it
                }
            }

            guardarEnCache(nuevasSugerencias)
            currentState.copy(sugerencias = nuevasSugerencias)
        }
    }

    /**
     * Edición humana por cajas/costales/unidades.
     *
     * NO recorta la intención. Si pide 50 y sólo existen 10, conserva 50 como intención,
     * usa 10 físicamente y muestra el aviso. Al volver después de empacar se recalcula.
     */
    fun recalcularSugerenciaPorUnidades(
        productId: String,
        cantidadEnUnidades: Int
    ) {
        val safeUnits = cantidadEnUnidades.coerceAtLeast(0)
        val lots = allLotesEnMatriz[productId].orEmpty()
        val product = activeProducts.firstOrNull { it.id == productId } ?: return

        val kgPerUnit = representativeKgPerUnit(lots)
        val intendedKg = if (kgPerUnit > 0.0) {
            safeUnits * kgPerUnit
        } else {
            // Si no existe peso físico conocido todavía, mantenemos la sugerencia previa.
            _uiState.value.sugerencias
                .firstOrNull { it.product.id == productId }
                ?.v3RequestedKg
                ?: 0.0
        }

        manualRequestedUnits[productId] = safeUnits
        manualOverridesKg[productId] = intendedKg
        manualExactBreakdowns.remove(productId)

        recalculatePure(
            focusedProductId = product.id,
            message = "Cantidad manual aplicada; V3 recalculó el resto del grupo."
        )
    }

    /** Edición humana en kg para productos que realmente se manejan por peso. */
    fun recalcularSugerenciaPorKg(
        productId: String,
        cantidadKg: Double
    ) {
        manualOverridesKg[productId] = cantidadKg.coerceAtLeast(0.0)
        manualRequestedUnits.remove(productId)
        manualExactBreakdowns.remove(productId)

        recalculatePure(
            focusedProductId = productId,
            message = "Cantidad manual aplicada; V3 recalculó las demás sugerencias."
        )
    }

    /**
     * Cambiar los lotes NO cambia por sí solo la decisión del usuario.
     * Sólo cambia de dónde sale físicamente la cantidad.
     */
    fun actualizarLotesManualmente(
        productId: String,
        lotesSeleccionados: List<StockLot>
    ) {
        val validIds = allLotesEnMatriz[productId].orEmpty()
            .map { it.id }
            .toSet()

        manualSelectedLots[productId] = lotesSeleccionados
            .filter { validIds.contains(it.id) }

        manualExactBreakdowns.remove(productId)

        recalculatePure(
            focusedProductId = productId,
            message = "Lotes manuales conservados."
        )
    }

    /**
     * Desglose manual exacto: el total elegido se convierte también en override humano
     * para que el grupo redistribuya el resto.
     */
    fun actualizarPorDesgloseManual(
        productId: String,
        desglose: List<DesgloseManualResult>
    ) {
        val lots = allLotesEnMatriz[productId].orEmpty()
        val product = activeProducts.firstOrNull { it.id == productId } ?: return
        val isFixed = isTraspasoFijo(product, lots)

        var totalKg = 0.0
        var totalUnits = 0

        val exact = desglose.mapNotNull { input ->
            val lot = lots.firstOrNull { it.id == input.loteId }
                ?: return@mapNotNull null

            val kg: Double
            val units: Double?

            if (isFixed) {
                val weight = lot.pesoPorUnidad ?: return@mapNotNull null
                val unitCount = input.cantidad.toInt().coerceAtLeast(0)
                val requested = unitCount * weight
                val withdrawal = StockQuantityPolicy.withdrawFromLot(
                    lot.currentQuantity,
                    requested
                )
                kg = withdrawal.actualTakenKg
                units = unitCount.toDouble()
                totalUnits += unitCount
            } else {
                val withdrawal = StockQuantityPolicy.withdrawFromLot(
                    lot.currentQuantity,
                    input.cantidad.coerceAtLeast(0.0)
                )
                kg = withdrawal.actualTakenKg
                units = null
            }

            if (kg <= StockQuantityPolicy.FLOAT_EPSILON) {
                return@mapNotNull null
            }

            totalKg += kg

            LoteDesglosado(
                loteId = lot.id,
                cantidadATomarKg = kg,
                cantidadATomarUnidades = units,
                lote = lot,
                loteFecha = effectiveDate(lot),
                loteProveedor = lot.supplierName ?: lot.originalSupplierName,
                loteUnidad = if (isFixed) lot.unidadDeEmpaque else "Kg",
                lotePesoPorUnidad = lot.pesoPorUnidad
            )
        }

        manualExactBreakdowns[productId] = exact
        manualSelectedLots[productId] = exact.mapNotNull { it.lote }
        manualOverridesKg[productId] = totalKg

        if (isFixed) {
            manualRequestedUnits[productId] = totalUnits
        } else {
            manualRequestedUnits.remove(productId)
        }

        recalculatePure(
            focusedProductId = productId,
            message = "Desglose manual aplicado; V3 recalculó el resto."
        )
    }

    private fun recalculatePure(
        focusedProductId: String,
        message: String
    ) {
        val snapshot = lastSnapshot ?: return

        val plan = PredictiveTransferPlannerV3.plan(
            snapshot = snapshot.copy(now = Date()),
            manualOverridesKg = manualOverridesKg
        )

        lastPlanV3 = plan
        val suggestions = buildSuggestionItems(plan)

        _uiState.update {
            it.copy(
                sugerencias = suggestions,
                snackbarMessage = message
            )
        }
        guardarEnCache(suggestions)

        Log.d(TAG, "V3 recalculado localmente por edición de $focusedProductId")
    }

    private fun guardarEnCache(
        sugerencias: List<TraspasoSugerenciaItem>
    ) {
        TraspasoPlanCache.planGuardado = sugerencias
        TraspasoPlanCache.timestamp = System.currentTimeMillis()
        TraspasoPlanCache.manualOverridesKg = manualOverridesKg.toMap()
        TraspasoPlanCache.manualRequestedUnits = manualRequestedUnits.toMap()
        TraspasoPlanCache.manualSelectedLots = manualSelectedLots.toMap()
        TraspasoPlanCache.manualExactBreakdowns = manualExactBreakdowns.toMap()
    }

    // -------------------------------------------------------------------------
    // LÓGICA FÍSICA EXISTENTE: cajas/costales/FIFO/PDF
    // -------------------------------------------------------------------------

    private fun isTraspasoFijo(
        product: Product,
        lotes: List<StockLot>
    ): Boolean {
        if (!product.requiresPackaging) return true

        return lotes.any {
            !it.unidadDeEmpaque.isNullOrBlank() &&
                it.unidadDeEmpaque != "Kg" &&
                (it.pesoPorUnidad ?: 0.0) > 0.0
        }
    }

    private fun getUnidadReal(
        product: Product,
        lotes: List<StockLot>
    ): String {
        val loteEmpacado = lotes.firstOrNull {
            !it.unidadDeEmpaque.isNullOrBlank() &&
                it.unidadDeEmpaque != "Kg"
        }

        return loteEmpacado?.unidadDeEmpaque
            ?: product.unit
    }

    private fun totalUnitsAvailable(
        lotes: List<StockLot>
    ): Int {
        return lotes.sumOf { lote ->
            val pesoUnidad = lote.pesoPorUnidad ?: return@sumOf 0
            if (pesoUnidad <= 0.0 || lote.unidadDeEmpaque.isNullOrBlank()) {
                0
            } else {
                Math.floor(lote.currentQuantity / pesoUnidad).toInt()
            }
        }
    }

    private fun representativeKgPerUnit(
        lotes: List<StockLot>
    ): Double {
        return lotes.firstOrNull {
            (it.pesoPorUnidad ?: 0.0) > 0.0 &&
                !it.unidadDeEmpaque.isNullOrBlank()
        }?.pesoPorUnidad ?: 0.0
    }

    private fun convertirKgAUnidades(
        kg: Double,
        lotesDisponibles: List<StockLot>
    ): Pair<Int, String> {
        val primerLoteConUnidad = lotesDisponibles.firstOrNull {
            it.pesoPorUnidad != null &&
                it.pesoPorUnidad > 0 &&
                !it.unidadDeEmpaque.isNullOrBlank()
        }

        val unidad = primerLoteConUnidad?.unidadDeEmpaque ?: "Kg"
        val pesoPorUnidad = primerLoteConUnidad?.pesoPorUnidad ?: 1.0

        val cantidadEnUnidades =
            if (kg > 0 && pesoPorUnidad > 0) {
                ceil(kg / pesoPorUnidad).toInt()
            } else {
                0
            }

        return cantidadEnUnidades to unidad
    }

    private fun desglosarLotesParaCantidadKg(
        cantidadNecesariaKg: Double,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val result = mutableListOf<LoteDesglosado>()
        var accumulated = 0.0
        var remaining = cantidadNecesariaKg.coerceAtLeast(0.0)

        for (lot in sortLotsFifo(lotesDisponibles)) {
            if (remaining <= StockQuantityPolicy.FLOAT_EPSILON) break

            val withdrawal = StockQuantityPolicy.withdrawFromLot(
                lot.currentQuantity,
                remaining
            )

            if (withdrawal.actualTakenKg <= StockQuantityPolicy.FLOAT_EPSILON) continue

            result += LoteDesglosado(
                loteId = lot.id,
                cantidadATomarKg = withdrawal.actualTakenKg,
                cantidadATomarUnidades = null,
                lote = lot,
                loteFecha = effectiveDate(lot),
                loteProveedor = lot.supplierName ?: lot.originalSupplierName,
                loteUnidad = "Kg",
                lotePesoPorUnidad = null
            )

            remaining = (remaining - withdrawal.actualTakenKg).coerceAtLeast(0.0)
            accumulated += withdrawal.actualTakenKg
        }

        return result to accumulated
    }

    private fun desglosarLotesParaCantidadUnidades(
        unidadesNecesarias: Int,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val result = mutableListOf<LoteDesglosado>()
        var accumulatedKg = 0.0
        var remainingUnits = unidadesNecesarias.coerceAtLeast(0)

        for (lot in sortLotsFifo(lotesDisponibles)) {
            if (remainingUnits <= 0) break

            val weight = lot.pesoPorUnidad ?: continue
            if (weight <= 0.0 || lot.unidadDeEmpaque.isNullOrBlank()) continue

            val availableUnits = Math.floor(lot.currentQuantity / weight).toInt()
            val takeUnits = min(availableUnits, remainingUnits)

            if (takeUnits <= 0) continue

            val requestedKg = takeUnits * weight
            val withdrawal = StockQuantityPolicy.withdrawFromLot(
                lot.currentQuantity,
                requestedKg
            )

            result += LoteDesglosado(
                loteId = lot.id,
                cantidadATomarKg = withdrawal.actualTakenKg,
                cantidadATomarUnidades = takeUnits.toDouble(),
                lote = lot,
                loteFecha = effectiveDate(lot),
                loteProveedor = lot.supplierName ?: lot.originalSupplierName,
                loteUnidad = lot.unidadDeEmpaque,
                lotePesoPorUnidad = lot.pesoPorUnidad
            )

            accumulatedKg += withdrawal.actualTakenKg
            remainingUnits -= takeUnits
        }

        return result to accumulatedKg
    }

    private fun sortLotsFifo(
        lots: List<StockLot>
    ): List<StockLot> =
        lots.asSequence()
            .filter {
                !it.isDepleted &&
                    it.isPackaged &&
                    it.estadoTraspaso == null &&
                    StockQuantityPolicy.isUsable(it.currentQuantity)
            }
            .sortedBy { effectiveDate(it)?.time ?: Long.MAX_VALUE }
            .toList()

    private fun effectiveDate(lot: StockLot): Date? =
        lot.originalReceivedAt ?: lot.receivedAt

    private fun legacyNeed(product: Product): Double {
        val need = max(
            0.0,
            product.stockIdealC04 - product.stockCongelador04
        )

        val reserve = max(
            0.0,
            product.minStock - product.stockCongelador04
        ).coerceAtMost(product.stockMatriz.coerceAtLeast(0.0))

        val usable = max(
            0.0,
            product.stockMatriz - reserve
        )

        return min(need, usable)
    }

    // -------------------------------------------------------------------------
    // FILAS VACÍAS — lógica existente conservada
    // -------------------------------------------------------------------------

    fun agregarFilaVacia(cantidadFilas: Int) {
        val filaVacia = TraspasoSugerenciaItem(
            product = Product(
                id = "FILA_VACIA",
                name = "Fila vacía"
            ),
            sugerenciaKg = 0.0,
            lotesParaTraspaso = emptyList(),
            impactoStockMatriz = 0.0,
            incluidoEnPdf = true,
            cantidadEditadaUnidades = cantidadFilas,
            unidadDeEmpaqueEditada = ""
        )

        _uiState.update { state ->
            val nuevas = state.sugerencias.toMutableList()
            val index = nuevas.indexOfFirst {
                it.product.id == "FILA_VACIA"
            }

            if (index != -1) {
                val current = nuevas[index].cantidadEditadaUnidades
                nuevas[index] = filaVacia.copy(
                    cantidadEditadaUnidades = current + cantidadFilas
                )
            } else {
                nuevas.add(filaVacia)
            }

            val next = state.copy(
                sugerencias = nuevas,
                snackbarMessage = "Filas vacías actualizadas"
            )
            guardarEnCache(nuevas)
            next
        }
    }

    fun eliminarFilaVacia() {
        _uiState.update { state ->
            val nextList = state.sugerencias.filter {
                it.product.id != "FILA_VACIA"
            }
            guardarEnCache(nextList)

            state.copy(
                sugerencias = nextList,
                snackbarMessage = "Fila vacía eliminada"
            )
        }
    }

    fun actualizarCantidadFilaVacia(
        nuevaCantidad: Int
    ) {
        _uiState.update { state ->
            val nextList = state.sugerencias.map {
                if (it.product.id == "FILA_VACIA") {
                    it.copy(cantidadEditadaUnidades = nuevaCantidad)
                } else {
                    it
                }
            }

            guardarEnCache(nextList)
            state.copy(sugerencias = nextList)
        }
    }

    private fun format1(value: Double): String =
        String.format(Locale.getDefault(), "%.1f", value)

    private fun pluralUnit(
        unit: String,
        amount: Int
    ): String {
        val clean = unit.trim().ifBlank { "unidades" }
        if (amount == 1) return clean

        return when {
            clean.endsWith("s", ignoreCase = true) -> clean
            clean.lastOrNull()?.lowercaseChar() in listOf('a', 'e', 'i', 'o', 'u') ->
                "${clean}s"
            else ->
                "${clean}es"
        }
    }
}
