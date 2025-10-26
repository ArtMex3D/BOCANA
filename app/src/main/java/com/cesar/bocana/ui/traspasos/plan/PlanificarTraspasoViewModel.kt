// main/java/com/cesar/bocana/ui/traspasos/plan/PlanificarTraspasoViewModel.kt
package com.cesar.bocana.ui.traspasos.plan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.*
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.WriteBatch
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.Date
import java.util.TimeZone
import java.util.UUID
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

// (TraspasoPlanCache, PlanTraspasoUiState, TAGs, constantes... se mantienen igual)
object TraspasoPlanCache {
    var planGuardado: List<TraspasoSugerenciaItem>? = null
    var timestamp: Long = 0

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


class PlanificarTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val _uiState = MutableStateFlow(PlanTraspasoUiState())
    val uiState: StateFlow<PlanTraspasoUiState> = _uiState

    private var allLotesLibresMatriz: Map<String, List<StockLot>> = emptyMap()
    private val TAG = "PlanTraspasoViewModel"
    private val stockEpsilon = 0.01
    private val umbralLiquidacionFijoUnidades = 1.5
    private val umbralLiquidacionGranelKg = 10.0

    init {
        if (TraspasoPlanCache.esValido()) {
            _uiState.update { it.copy(preguntaCache = true, isLoading = false) }
        } else {
            cargarPlanDeTraspaso(descartarCache = true)
        }
    }

    fun onSnackbarShown() { _uiState.update { it.copy(snackbarMessage = null) } }
    fun onPlanGuardadoNavegado() { _uiState.update { it.copy(planGuardadoExitoso = false) } }
    fun onDialogoMostrado() { _uiState.update { it.copy(preguntaCache = false) } }

    fun cargarPlanDesdeCache() {
        if (TraspasoPlanCache.esValido()) {
            _uiState.update { it.copy(isLoading = false, sugerencias = TraspasoPlanCache.planGuardado!!) }
            viewModelScope.launch { cargarLotesLibresEnMatriz() } // Recargar lotes por si acaso
        } else {
            cargarPlanDeTraspaso(true)
        }
    }

    fun cargarPlanDeTraspaso(descartarCache: Boolean) {
        if (descartarCache) { TraspasoPlanCache.limpiar() }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, sugerencias = emptyList(), error = null) }
            try {
                val productsDeferred = async(Dispatchers.IO) { loadProductsWithDetails() }
                allLotesLibresMatriz = cargarLotesLibresEnMatriz() // Ejecutar directamente
                val products = productsDeferred.await() // Esperar a que los productos terminen

                val productsOrdenados = products.sortedWith(
                    compareByDescending<Product> {
                        when (it.prioridadDesabasto) {
                            PrioridadDesabasto.ALTA -> 3
                            PrioridadDesabasto.MEDIA -> 2
                            PrioridadDesabasto.BAJA -> 1
                            else -> 0
                        }
                    }.thenBy { it.ordenTraspaso }
                        .thenBy { it.name }
                )

                val sugerencias = generarSugerenciasInteligentes(productsOrdenados, allLotesLibresMatriz)

                _uiState.update { it.copy(isLoading = false, sugerencias = sugerencias) }
                guardarEnCache(sugerencias)

            } catch (e: Exception) {
                Log.e(TAG, "Error crítico en cargarPlanDeTraspaso", e)
                _uiState.update { it.copy(isLoading = false, error = "Error al generar plan: ${e.localizedMessage}") }
            }
        }
    }

    private suspend fun loadProductsWithDetails(): List<Product> {
        return db.collection(FirestoreCollections.PRODUCTS)
            .whereEqualTo("isActive", true)
            .get().await().toObjects(Product::class.java)
    }

    private suspend fun cargarLotesLibresEnMatriz(): Map<String, List<StockLot>> {
        return try {
            val lotesSnapshot = db.collection(FirestoreCollections.INVENTORY_LOTS)
                .whereEqualTo("location", Location.MATRIZ)
                .whereEqualTo("isDepleted", false)
                .whereEqualTo("estadoTraspaso", null)
                .orderBy("receivedAt")
                .get().await()

            val lotesDisponibles = lotesSnapshot.documents.mapNotNull { doc ->
                // Incluir el ID del documento en el objeto StockLot
                doc.toObject(StockLot::class.java)?.copy(id = doc.id)
            }
            Log.d(TAG, "Cargados ${lotesDisponibles.size} lotes libres de Matriz (estadoTraspaso == null).")
            lotesDisponibles.groupBy { it.productId }
        } catch (e: Exception) {
            Log.e(TAG, "Error al cargar lotes libres de Matriz", e)
            _uiState.update { it.copy(error = "Error al cargar lotes disponibles: ${e.message}") }
            emptyMap()
        }
    }

    private fun generarSugerenciasInteligentes(
        productsOrdenados: List<Product>,
        lotesLibresPorProducto: Map<String, List<StockLot>>
    ): List<TraspasoSugerenciaItem> {
        val sugerenciasMutables = mutableMapOf<String, TraspasoSugerenciaItem>()
        val lotesComprometidos = mutableSetOf<String>()

        Log.d(TAG, "Iniciando generación de sugerencias...")

        productsOrdenados.forEach { product ->
            val lotesLibresDelProducto = lotesLibresPorProducto[product.id] ?: emptyList()
            val (sugerenciaInicial, lotesUsadosEnSugerencia) = generarSugerenciaBaseYLiquidacion(product, lotesLibresDelProducto)
            sugerenciasMutables[product.id] = sugerenciaInicial
            lotesComprometidos.addAll(lotesUsadosEnSugerencia.map { it.loteId }) // Guardar IDs
            Log.v(TAG, "Sugerencia Base/Liquidación para ${product.name}: ${sugerenciaInicial.sugerenciaKg} Kg ${if(sugerenciaInicial.isSugerenciaLiquidacion) "(Incluye Liq.)" else ""}. Lotes usados: ${lotesUsadosEnSugerencia.joinToString { it.loteId.takeLast(4) }}")
        }

        Log.d(TAG, "Sugerencias Base/Liquidación calculadas. Iniciando lógica Rector/Regido...")

        val mesesAnosRectoresSugeridos = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        sugerenciasMutables.values.forEach { sugerencia ->
            if (sugerencia.product.categoria == "PESCADO_CHICO" && sugerencia.product.productoRectorId == sugerencia.product.id) {
                sugerencia.lotesParaTraspaso.forEach { desglose ->
                    desglose.loteFecha?.let { fecha ->
                        val cal = Calendar.getInstance().apply { time = fecha }
                        val mesAno = cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR)
                        mesesAnosRectoresSugeridos.getOrPut(sugerencia.product.id) { mutableSetOf() }.add(mesAno)
                    }
                }
                if (sugerencia.lotesParaTraspaso.isNotEmpty()) {
                    Log.d(TAG, "Rector ${sugerencia.product.name} sugiere traspaso de lotes de meses/años: ${mesesAnosRectoresSugeridos[sugerencia.product.id]?.joinToString()}")
                }
            }
        }

        sugerenciasMutables.values.toList().forEach { sugerenciaRegido ->
            if (sugerenciaRegido.product.categoria == "PESCADO_CHICO" &&
                !sugerenciaRegido.product.productoRectorId.isNullOrBlank() &&
                sugerenciaRegido.product.productoRectorId != sugerenciaRegido.product.id &&
                sugerenciaRegido.sugerenciaKg < stockEpsilon) {

                val rectorId = sugerenciaRegido.product.productoRectorId
                val mesesAnosDelRector = mesesAnosRectoresSugeridos[rectorId]

                if (!mesesAnosDelRector.isNullOrEmpty()) {
                    Log.d(TAG, "Intentando inducir sugerencia para Regido ${sugerenciaRegido.product.name} basado en Rector ${rectorId} (meses: ${mesesAnosDelRector.joinToString()})")
                    val lotesLibresRegido = (lotesLibresPorProducto[sugerenciaRegido.product.id] ?: emptyList())
                        .filter { it.id !in lotesComprometidos }

                    val lotesCoincidentes = lotesLibresRegido.filter { lote ->
                        lote.receivedAt?.let { fechaLote ->
                            val cal = Calendar.getInstance().apply { time = fechaLote }
                            (cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR)) in mesesAnosDelRector
                        } ?: false
                    }

                    if (lotesCoincidentes.isNotEmpty()) {
                        Log.i(TAG, "¡Éxito! Lotes coincidentes encontrados para ${sugerenciaRegido.product.name}. Generando sugerencia inducida...")
                        val (nuevaSugerenciaRegido, lotesUsadosRegido) = generarSugerenciaBaseYLiquidacion(sugerenciaRegido.product, lotesCoincidentes)

                        if(nuevaSugerenciaRegido.sugerenciaKg > stockEpsilon) {
                            sugerenciasMutables[sugerenciaRegido.product.id] = nuevaSugerenciaRegido
                            lotesComprometidos.addAll(lotesUsadosRegido.map { it.loteId }) // Guardar IDs
                            Log.d(TAG, "Sugerencia inducida aplicada a ${sugerenciaRegido.product.name}: ${nuevaSugerenciaRegido.sugerenciaKg} Kg. Lotes usados: ${lotesUsadosRegido.joinToString { it.loteId.takeLast(4) }}")
                        } else {
                            Log.d(TAG, "Aunque hubo lotes coincidentes para ${sugerenciaRegido.product.name}, no se necesita traspaso (stock C04 >= ideal).")
                        }
                    } else {
                        Log.d(TAG, "No se encontraron lotes libres/coincidentes para ${sugerenciaRegido.product.name} de los meses del Rector.")
                    }
                }
            }
        }

        Log.d(TAG, "Generación de sugerencias completada.")
        return productsOrdenados.mapNotNull { sugerenciasMutables[it.id] }
    }

    private fun generarSugerenciaBaseYLiquidacion(
        product: Product,
        lotesLibresDisponibles: List<StockLot>
    ): Pair<TraspasoSugerenciaItem, List<LoteDesglosado>> { // Devuelve la sugerencia y la lista de LoteDesglosado usados

        val necesidadKgBase = max(0.0, product.stockMaximoC04 - product.stockCongelador04)
        val espacioHastaMaximoKg = max(0.0, product.stockMaximoC04 - product.stockCongelador04)
        val kgFaltantesParaMaximoInicial = espacioHastaMaximoKg

        val lotesUsadosTemporalmente = mutableSetOf<String>() // IDs
        var isLiquidacionAplicada = false
        var kgSugeridosTotal = 0.0
        var lotesDesglosadosFinal = mutableListOf<LoteDesglosado>()

        val esFijo = !product.requiresPackaging
        val unidadDefault = if(esFijo) product.unit else "Kg" // Kg para granel, unidad del producto para fijo
        val pesoUnidadDefault = if(esFijo) product.labelConfig?.get("weightPerUnit") as? Double ?: 1.0 else 1.0

        if (necesidadKgBase > stockEpsilon) {
            val (desgloseBase, kgTomadosBase) = if (esFijo) {
                val unidadesNecesariasBase = ceil(necesidadKgBase / pesoUnidadDefault).toInt()
                desglosarLotesParaCantidadUnidades(unidadesNecesariasBase, lotesLibresDisponibles)
            } else {
                desglosarLotesParaCantidadKg(necesidadKgBase, lotesLibresDisponibles)
            }
            lotesDesglosadosFinal.addAll(desgloseBase)
            kgSugeridosTotal = kgTomadosBase
            lotesUsadosTemporalmente.addAll(desgloseBase.map { it.loteId })
        }

        var kgFaltantesParaMaximoActual = kgFaltantesParaMaximoInicial - kgSugeridosTotal

        if (kgFaltantesParaMaximoActual > stockEpsilon) {
            val lotesLibresNoUsados = lotesLibresDisponibles.filter { it.id !in lotesUsadosTemporalmente }

            for (lote in lotesLibresNoUsados) {
                val kgParaLiquidar: Double
                val umbral: Double
                val pesoUnidadLote = lote.pesoPorUnidad ?: pesoUnidadDefault // Usar el del lote si existe, sino el default
                if (esFijo && pesoUnidadLote > 0) {
                    umbral = umbralLiquidacionFijoUnidades * pesoUnidadLote
                    kgParaLiquidar = if (lote.currentQuantity < umbral) lote.currentQuantity else 0.0
                } else {
                    umbral = umbralLiquidacionGranelKg
                    kgParaLiquidar = if (lote.currentQuantity < umbral) lote.currentQuantity else 0.0
                }

                if (kgParaLiquidar > stockEpsilon && kgParaLiquidar <= (kgFaltantesParaMaximoActual + stockEpsilon) ) {
                    Log.d(TAG, "💡 Sugiriendo liquidación para ${product.name}, lote ${lote.id.takeLast(4)} (${String.format("%.2f", kgParaLiquidar)} Kg)")
                    val unidadesLiquidar = if (esFijo && pesoUnidadLote > 0) floor(kgParaLiquidar / pesoUnidadLote) else null

                    lotesDesglosadosFinal.add(
                        LoteDesglosado(
                            loteId = lote.id, cantidadATomarKg = kgParaLiquidar, cantidadATomarUnidades = unidadesLiquidar?.toDouble(),
                            lote = lote, loteFecha = lote.receivedAt, loteProveedor = lote.supplierName,
                            loteUnidad = lote.unidadDeEmpaque, lotePesoPorUnidad = lote.pesoPorUnidad
                        )
                    )
                    lotesUsadosTemporalmente.add(lote.id)
                    kgSugeridosTotal += kgParaLiquidar
                    kgFaltantesParaMaximoActual -= kgParaLiquidar
                    isLiquidacionAplicada = true
                    break // Solo liquidamos un lote por producto por pasada
                }
            }
        }

        val unidadesFinales = if (esFijo) {
            ceil(kgSugeridosTotal / pesoUnidadDefault).toInt()
        } else {
            0
        }

        // Re-desglosar para Fijos si se aplicó liquidación para asegurar unidades enteras
        if (esFijo && isLiquidacionAplicada && kgSugeridosTotal > stockEpsilon) {
            val (desgloseFinalReal, kgTomadosFinalReal) = desglosarLotesParaCantidadUnidades(unidadesFinales, lotesLibresDisponibles.filter { it.id in lotesUsadosTemporalmente })
            lotesDesglosadosFinal = desgloseFinalReal.toMutableList()
            kgSugeridosTotal = kgTomadosFinalReal
        }


        val itemFinal = TraspasoSugerenciaItem(
            product = product,
            sugerenciaKg = kgSugeridosTotal,
            lotesParaTraspaso = lotesDesglosadosFinal.sortedBy { it.loteFecha },
            impactoStockMatriz = product.stockMatriz - kgSugeridosTotal,
            incluidoEnPdf = kgSugeridosTotal > stockEpsilon,
            cantidadEditadaUnidades = unidadesFinales,
            unidadDeEmpaqueEditada = if (esFijo) lotesDesglosadosFinal.firstNotNullOfOrNull { it.loteUnidad } ?: unidadDefault else "Kg",
            lotesSeleccionadosManualmente = null, // Inicialmente no es manual
            isRecalculating = false,
            isSugerenciaLiquidacion = isLiquidacionAplicada
        )
        // Devolver la lista final de LoteDesglosado que realmente se usaron
        return Pair(itemFinal, lotesDesglosadosFinal)
    }

    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        if (sugerenciaAfectada.product.requiresPackaging) {
            Log.w(TAG, "recalcularSugerenciaPorUnidades llamado para producto Granel ${sugerenciaAfectada.product.name}")
            return
        }
        setRecalculatingState(productId, true)
        viewModelScope.launch {
            val lotesDisponibles = allLotesLibresMatriz[productId] ?: emptyList()
            val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDisponibles)
            val nuevaUnidad = lotesDesglosados.firstNotNullOfOrNull { desglose -> desglose.loteUnidad } ?: sugerenciaAfectada.unidadDeEmpaqueEditada

            _uiState.update { state ->
                val nuevasSugerencias = state.sugerencias.map {
                    if (it.product.id == productId) {
                        it.copy(
                            sugerenciaKg = kgRealesTomados,
                            lotesParaTraspaso = lotesDesglosados,
                            impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                            cantidadEditadaUnidades = cantidadEnUnidades,
                            unidadDeEmpaqueEditada = nuevaUnidad,
                            isRecalculating = false,
                            lotesSeleccionadosManualmente = null, // Limpiar selección manual si se edita cantidad
                            isSugerenciaLiquidacion = false // Limpiar flag de liquidación
                        )
                    } else { it }
                }
                guardarEnCache(nuevasSugerencias)
                state.copy(sugerencias = nuevasSugerencias)
            }
        }
    }

    fun recalcularSugerenciaPorKg(productId: String, cantidadEnKg: Double) {
        // Esta función podría habilitarse si quieres permitir editar KG para Granel
        // La lógica sería similar a la de unidades, pero llamando a desglosarLotesParaCantidadKg
        Log.w(TAG, "recalcularSugerenciaPorKg no está implementada actualmente.")
        setRecalculatingState(productId, false) // Asegurarse de quitar el flag
    }

    // --- FUNCIÓN MODIFICADA ---
    fun actualizarLotesManualmentePorIds(productId: String, loteIdsSeleccionados: List<String>) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        setRecalculatingState(productId, true)

        viewModelScope.launch {
            try {
                // Usar la función auxiliar para cargar lotes LIBRES
                val lotesCompletos = fetchLotesLibresCompletos(loteIdsSeleccionados)

                // **INICIO CORRECCIÓN**: Llamar a la lógica común de actualización con los lotes cargados
                actualizarEstadoConLotesManuales(sugerenciaAfectada, lotesCompletos)
                // **FIN CORRECCIÓN**

            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar lotes por IDs para $productId (Plan)", e)
                _uiState.update { it.copy(snackbarMessage = "Error al procesar selección: ${e.message}") }
                setRecalculatingState(productId, false)
            }
        }
    }
    // --- FIN FUNCIÓN MODIFICADA ---

    // Función auxiliar SÓLO para cargar lotes LIBRES por ID
    private suspend fun fetchLotesLibresCompletos(loteIds: List<String>): List<StockLot> {
        if (loteIds.isEmpty()) return emptyList()
        return loteIds.chunked(30).flatMap { chunk ->
            try {
                db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereIn(com.google.firebase.firestore.FieldPath.documentId(), chunk)
                    .whereEqualTo("estadoTraspaso", null) // Solo libres
                    .whereEqualTo("isDepleted", false) // Solo con stock
                    .get().await().documents.mapNotNull { doc ->
                        doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchLotesLibresCompletos chunk", e)
                emptyList<StockLot>()
            }
        }
    }

    // --- FUNCIÓN OBSOLETA (será reemplazada por la lógica común) ---
    // fun actualizarLotesManualmente(productId: String, lotesSeleccionadosManualmente: List<StockLot>) { ... }
    // --- FIN FUNCIÓN OBSOLETA ---

    // --- FUNCIÓN MODIFICADA Y CENTRALIZADA ---
    fun actualizarPorDesgloseManual(productId: String, desgloseManualUsuario: List<DesgloseManualResult>) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        setRecalculatingState(productId, true)

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lotIdsNecesarios = desgloseManualUsuario.map { it.loteId }
                // Cargar lotes completos (solo libres)
                val lotesCompletos = fetchLotesLibresCompletos(lotIdsNecesarios)
                val lotesCompletosMap = lotesCompletos.associateBy { it.id }

                var totalKgDesglosado = 0.0
                var totalUnidadesDesglosadas = 0 // Necesitamos recalcular unidades si es fijo
                val lotesDesglosadosFinal = mutableListOf<LoteDesglosado>()
                var unidadFinal = sugerenciaAfectada.unidadDeEmpaqueEditada // Usar la actual como fallback
                var ajusteRealizadoMsg: String? = null
                var finalMessage = "Desglose manual aplicado."

                val esFijo = !sugerenciaAfectada.product.requiresPackaging

                desgloseManualUsuario.forEach { itemUsuario ->
                    val loteOriginal = lotesCompletosMap[itemUsuario.loteId]
                    if (loteOriginal != null) {
                        val cantidadUsuario = itemUsuario.cantidad // Es Double (Kg o Unidades)
                        var cantidadRealTomadaKg = 0.0
                        var cantidadRealTomadaUnidades: Double? = null

                        if (esFijo) {
                            val unidadesUsuario = cantidadUsuario.toInt()
                            val pesoUnidad = loteOriginal.pesoPorUnidad ?: 1.0
                            val unidadesDisponibles = floor(loteOriginal.currentQuantity / pesoUnidad).toInt()
                            val unidadesRealesATomar = min(unidadesUsuario, unidadesDisponibles)
                            val kgATomar = unidadesRealesATomar * pesoUnidad
                            unidadFinal = loteOriginal.unidadDeEmpaque ?: unidadFinal // Actualizar unidad si es válida

                            if (unidadesRealesATomar > 0) {
                                if (unidadesRealesATomar < unidadesUsuario) ajusteRealizadoMsg = "Una o más cantidades ajustadas al stock."
                                cantidadRealTomadaKg = kgATomar
                                cantidadRealTomadaUnidades = unidadesRealesATomar.toDouble()
                                // No sumar a totalUnidadesDesglosadas aquí, se recalcula al final
                            }
                        } else { // Granel
                            val kgUsuario = cantidadUsuario
                            val kgDisponibles = loteOriginal.currentQuantity
                            val kgRealesATomar = min(kgUsuario, kgDisponibles)
                            unidadFinal = "Kg" // Siempre Kg para granel

                            if (kgRealesATomar > stockEpsilon) {
                                if (kotlin.math.abs(kgUsuario - kgRealesATomar) > stockEpsilon) ajusteRealizadoMsg = "Una o más cantidades ajustadas al stock."
                                cantidadRealTomadaKg = kgRealesATomar
                                // totalUnidadesDesglosadas se queda en 0 para granel
                            }
                        }

                        if (cantidadRealTomadaKg > stockEpsilon) {
                            totalKgDesglosado += cantidadRealTomadaKg
                            lotesDesglosadosFinal.add(
                                LoteDesglosado(
                                    loteId = loteOriginal.id, cantidadATomarKg = cantidadRealTomadaKg, cantidadATomarUnidades = cantidadRealTomadaUnidades,
                                    lote = loteOriginal, loteFecha = loteOriginal.receivedAt, loteProveedor = loteOriginal.supplierName,
                                    loteUnidad = loteOriginal.unidadDeEmpaque, lotePesoPorUnidad = loteOriginal.pesoPorUnidad
                                )
                            )
                        }
                    } else {
                        Log.w(TAG, "Lote ${itemUsuario.loteId} no encontrado/libre para desglose manual de ${sugerenciaAfectada.product.name}")
                        ajusteRealizadoMsg = "Algunos lotes no estaban disponibles."
                    }
                } // Fin forEach

                // Recalcular unidades totales si es fijo, basado en el total de KG y el peso unitario
                if (esFijo) {
                    val pesoRef = lotesDesglosadosFinal.firstNotNullOfOrNull { it.lotePesoPorUnidad?.takeIf { p -> p > 0 } } ?: 1.0
                    totalUnidadesDesglosadas = ceil(totalKgDesglosado / pesoRef).toInt()
                }

                finalMessage = ajusteRealizadoMsg ?: finalMessage
                Log.d(TAG, "actualizarPorDesgloseManual para $productId: Desglose manual (switch) aplicado. KG totales: ${String.format("%.2f", totalKgDesglosado)}, Unidades: $totalUnidadesDesglosadas")

                // **INICIO CORRECCIÓN**: Llamar a la lógica común de actualización de estado
                withContext(Dispatchers.Main) {
                    actualizarEstadoConDesgloseManual(
                        sugerenciaAfectada,
                        lotesDesglosadosFinal.mapNotNull { it.lote }, // Lista de StockLot usados
                        lotesDesglosadosFinal, // Lista de LoteDesglosado con cantidades
                        totalKgDesglosado,
                        totalUnidadesDesglosadas,
                        unidadFinal,
                        finalMessage
                    )
                }
                // **FIN CORRECCIÓN**

            } catch (e: Exception) {
                Log.e(TAG, "Error en actualizarPorDesgloseManual para $productId", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(snackbarMessage = "Error al procesar desglose: ${e.message}") }
                    setRecalculatingState(productId, false)
                }
            }
        }
    }
    // --- FIN FUNCIÓN MODIFICADA ---

    // --- NUEVA FUNCIÓN PRIVADA PARA ACTUALIZAR ESTADO (CHECKBOX) ---
    private fun actualizarEstadoConLotesManuales(
        sugerenciaOriginal: TraspasoSugerenciaItem,
        lotesSeleccionadosCompletos: List<StockLot> // Lotes seleccionados por checkbox
    ) {
        val esFijo = !sugerenciaOriginal.product.requiresPackaging
        // Usar la cantidad que el usuario YA TENÍA (editada o sugerida) como base
        val cantidadUnidadesNecesarias = if (esFijo) sugerenciaOriginal.cantidadEditadaUnidades else 0
        val cantidadKgNecesarios = if (!esFijo) sugerenciaOriginal.sugerenciaKg else 0.0

        val (lotesDesglosados, kgRealesTomados) = if (esFijo && cantidadUnidadesNecesarias > 0) {
            desglosarLotesParaCantidadUnidades(cantidadUnidadesNecesarias, lotesSeleccionadosCompletos)
        } else if (!esFijo && cantidadKgNecesarios > 0) {
            desglosarLotesParaCantidadKg(cantidadKgNecesarios, lotesSeleccionadosCompletos)
        } else {
            Pair(emptyList<LoteDesglosado>(), 0.0) // Si no hay cantidad necesaria, el resultado es vacío
        }

        // Recalcular unidades reales tomadas si es fijo
        val unidadesRealesTomadas = if (esFijo && kgRealesTomados > 0) {
            val pesoUnidadRef = lotesSeleccionadosCompletos.firstNotNullOfOrNull { it.pesoPorUnidad?.takeIf { p -> p > 0 } } ?: 1.0
            ceil(kgRealesTomados / pesoUnidadRef).toInt()
        } else {
            if (esFijo) 0 else sugerenciaOriginal.cantidadEditadaUnidades // Mantener si granel
        }

        val nuevaUnidad = lotesSeleccionadosCompletos.firstNotNullOfOrNull { it.unidadDeEmpaque?.takeIf { u -> u.isNotBlank() } }
            ?: sugerenciaOriginal.unidadDeEmpaqueEditada

        // Mensaje de ajuste si la cantidad real difiere de la necesaria
        val msg = if ((esFijo && cantidadUnidadesNecesarias > unidadesRealesTomadas) || (!esFijo && cantidadKgNecesarios > kgRealesTomados + stockEpsilon)) {
            val necesariaStr = if(esFijo) "$cantidadUnidadesNecesarias $nuevaUnidad" else "${String.format("%.2f", cantidadKgNecesarios)} Kg"
            val realStr = if(esFijo) "$unidadesRealesTomadas $nuevaUnidad" else "${String.format("%.2f", kgRealesTomados)} Kg"
            "Cantidad necesaria ($necesariaStr) excede stock. Ajustado a $realStr."
        } else {
            "Selección de lotes manual aplicada."
        }

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == sugerenciaOriginal.product.id) it.copy(
                    lotesSeleccionadosManualmente = lotesSeleccionadosCompletos, // Guardar la lista COMPLETA seleccionada
                    lotesParaTraspaso = lotesDesglosados.sortedBy { d -> d.loteFecha ?: Date(0) }, // Guardar el desglose REAL
                    sugerenciaKg = kgRealesTomados, // Actualizar KG REALES
                    impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                    cantidadEditadaUnidades = unidadesRealesTomadas, // Actualizar unidades REALES
                    unidadDeEmpaqueEditada = nuevaUnidad,
                    isRecalculating = false, // Quitar estado recalculando
                    isSugerenciaLiquidacion = false // Selección manual anula sugerencia de liquidación
                ) else it
            }
            guardarEnCache(nuevasSugerencias) // Guardar en caché el nuevo estado
            state.copy(isLoading = false, sugerencias = nuevasSugerencias, snackbarMessage = msg)
        }
    }
    // --- FIN NUEVA FUNCIÓN PRIVADA (CHECKBOX) ---

    // --- NUEVA FUNCIÓN PRIVADA PARA ACTUALIZAR ESTADO (DESGLOSE MANUAL) ---
    private fun actualizarEstadoConDesgloseManual(
        sugerenciaOriginal: TraspasoSugerenciaItem,
        lotesSeleccionadosCompletos: List<StockLot>, // Lotes usados en el desglose
        lotesDesglosadosCalculados: List<LoteDesglosado>, // Desglose con cantidades
        totalKgCalculado: Double,
        totalUnidadesCalculadas: Int,
        unidadFinalCalculada: String,
        mensaje: String
    ) {
        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == sugerenciaOriginal.product.id) it.copy(
                    lotesSeleccionadosManualmente = lotesSeleccionadosCompletos, // Guardar lotes USADOS
                    lotesParaTraspaso = lotesDesglosadosCalculados.sortedBy { ld -> ld.loteFecha ?: Date(0) }, // Guardar desglose calculado
                    sugerenciaKg = totalKgCalculado, // KG totales calculados
                    impactoStockMatriz = it.product.stockMatriz - totalKgCalculado,
                    cantidadEditadaUnidades = totalUnidadesCalculadas, // Unidades totales calculadas (si aplica)
                    unidadDeEmpaqueEditada = unidadFinalCalculada, // Unidad final calculada
                    isRecalculating = false, // Quitar estado
                    isSugerenciaLiquidacion = false // Desglose manual anula liquidación
                ) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(isLoading = false, sugerencias = nuevasSugerencias, snackbarMessage = mensaje)
        }
    }
    // --- FIN NUEVA FUNCIÓN PRIVADA (DESGLOSE MANUAL) ---


    private fun setRecalculatingState(productId: String, isRecalculating: Boolean) {
        _uiState.update { currentState ->
            val updatedSugerencias = currentState.sugerencias.map {
                if (it.product.id == productId) it.copy(isRecalculating = isRecalculating) else it
            }
            currentState.copy(sugerencias = updatedSugerencias)
        }
    }

    private fun desglosarLotesParaCantidadKg(
        cantidadNecesaria: Double,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesaria

        for (lote in lotesDisponibles.sortedBy { it.receivedAt }) {
            if (kgRestantes <= stockEpsilon) break

            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)

            if (aTomarDeEsteLote > stockEpsilon) {
                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id, cantidadATomarKg = aTomarDeEsteLote, cantidadATomarUnidades = null,
                        lote = lote, loteFecha = lote.receivedAt, loteProveedor = lote.supplierName,
                        loteUnidad = lote.unidadDeEmpaque, lotePesoPorUnidad = lote.pesoPorUnidad
                    )
                )
                kgRestantes -= aTomarDeEsteLote
                kgAcumulados += aTomarDeEsteLote
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    private fun desglosarLotesParaCantidadUnidades(
        unidadesNecesarias: Int,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var unidadesRestantes = unidadesNecesarias

        for (lote in lotesDisponibles.sortedBy { it.receivedAt }) {
            if (unidadesRestantes <= 0) break

            val pesoPorUnidad = lote.pesoPorUnidad
            if (pesoPorUnidad != null && pesoPorUnidad > 0) {
                val unidadesDisponiblesEnLote = floor(lote.currentQuantity / pesoPorUnidad).toInt()
                val unidadesATomarDeEsteLote = min(unidadesDisponiblesEnLote, unidadesRestantes)

                if (unidadesATomarDeEsteLote > 0) {
                    val kgATomar = unidadesATomarDeEsteLote * pesoPorUnidad
                    lotesDesglosados.add(
                        LoteDesglosado(
                            loteId = lote.id, cantidadATomarKg = kgATomar, cantidadATomarUnidades = unidadesATomarDeEsteLote.toDouble(),
                            lote = lote, loteFecha = lote.receivedAt, loteProveedor = lote.supplierName,
                            loteUnidad = lote.unidadDeEmpaque, lotePesoPorUnidad = lote.pesoPorUnidad
                        )
                    )
                    unidadesRestantes -= unidadesATomarDeEsteLote
                    kgAcumulados += kgATomar
                }
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    fun guardarEnCache(sugerencias: List<TraspasoSugerenciaItem>) {
        // Limpiar objeto lote antes de guardar en caché
        TraspasoPlanCache.planGuardado = sugerencias.map { item ->
            item.copy(
                lotesParaTraspaso = item.lotesParaTraspaso.map { d -> d.copy(lote = null) },
                lotesSeleccionadosManualmente = item.lotesSeleccionadosManualmente?.map { it.copy() } // Guardar copia sin referencias
            )
        }
        TraspasoPlanCache.timestamp = System.currentTimeMillis()
        Log.d(TAG, "Plan guardado en caché con ${sugerencias.size} items.")
    }

    fun agregarFilaVacia() {
        _uiState.update { currentState ->
            val filaVacia = TraspasoSugerenciaItem(
                product = Product(id = UUID.randomUUID().toString(), name = "FILA_VACIA"),
                sugerenciaKg = 0.0, lotesParaTraspaso = emptyList(), impactoStockMatriz = 0.0,
                incluidoEnPdf = true,
                cantidadEditadaUnidades = 0, unidadDeEmpaqueEditada = "",
                lotesSeleccionadosManualmente = null, isRecalculating = false, isSugerenciaLiquidacion = false
            )
            val nuevasSugerencias = currentState.sugerencias + filaVacia
            guardarEnCache(nuevasSugerencias)
            currentState.copy(sugerencias = nuevasSugerencias)
        }
    }

    fun actualizarInclusionEnPdf(productId: String, incluido: Boolean) {
        _uiState.update { currentState ->
            val nuevasSugerencias = currentState.sugerencias.map {
                if (it.product.id == productId) it.copy(incluidoEnPdf = incluido) else it
            }
            guardarEnCache(nuevasSugerencias)
            currentState.copy(sugerencias = nuevasSugerencias)
        }
    }

    fun guardarPlanEnFirestore(fechaPlan: Date) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.update { it.copy(snackbarMessage = "Error: Usuario no autenticado.") }
            return
        }
        val planParaGuardar = _uiState.value.sugerencias.filter {
            it.incluidoEnPdf && (it.sugerenciaKg > stockEpsilon || it.product.name == "FILA_VACIA")
        }

        if (planParaGuardar.all { it.product.name == "FILA_VACIA" }) {
            _uiState.update { it.copy(snackbarMessage = "No hay productos con cantidad > 0 seleccionados para el traspaso.") }
            return
        }
        Log.d(TAG, "Guardando plan con ${planParaGuardar.count { it.product.name != "FILA_VACIA" }} productos.")

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val planDocRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document()
                val planPrincipal = TraspasoPlanificado(
                    id = planDocRef.id,
                    createdBy = currentUser.displayName ?: currentUser.email ?: "Desconocido",
                    fechaPlan = fechaPlan,
                    estado = TraspasoEstado.PENDIENTE
                    // createdAt se seteará por Firestore
                )

                val batch: WriteBatch = db.batch()
                batch.set(planDocRef, planPrincipal)

                planParaGuardar.forEach { item ->
                    if (item.product.name != "FILA_VACIA") {
                        Log.v(TAG, "Procesando item ${item.product.name} para guardar. KG: ${item.sugerenciaKg}")
                        val detalleDocRef = planDocRef.collection("detalles").document()
                        // Usar lotesParaTraspaso (que ya tiene la selección manual o FIFO)
                        val lotesAGuardar = item.lotesParaTraspaso.map { it.copy(lote=null) } // Quitar el objeto lote

                        val detalle = DetalleTraspasoPlan(
                            id = detalleDocRef.id, productId = item.product.id, productName = item.product.name,
                            sugerenciaKg = item.sugerenciaKg, sugerenciaUnidades = item.cantidadEditadaUnidades,
                            unidadDeEmpaque = item.unidadDeEmpaqueEditada, lotesSugeridos = lotesAGuardar // Guardar los lotes correctos
                        )
                        batch.set(detalleDocRef, detalle)
                        Log.v(TAG, " -> Detalle creado para ${item.product.name}. Lotes a reservar: ${lotesAGuardar.size}")

                        // Reservar los lotes que REALMENTE se van a usar según lotesParaTraspaso
                        lotesAGuardar.forEach { desglose ->
                            if (desglose.cantidadATomarKg > stockEpsilon && desglose.loteId.isNotBlank()) {
                                val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                                batch.update(loteRef, "estadoTraspaso", planPrincipal.id)
                                Log.v(TAG, "    -> Reservando lote ${desglose.loteId.takeLast(4)} con ID de plan ${planPrincipal.id.takeLast(4)}")
                            }
                        }
                    }
                }

                batch.commit().await()
                Log.i(TAG, "¡Plan de traspaso ${planPrincipal.id} guardado exitosamente!")
                TraspasoPlanCache.limpiar()
                // Recargar lotes libres después de guardar para reflejar las reservas
                allLotesLibresMatriz = cargarLotesLibresEnMatriz()
                _uiState.update { it.copy(isSaving = false, planGuardadoExitoso = true, sugerencias = emptyList()) }

            } catch (e: Exception) {
                Log.e(TAG, "Error crítico al guardar plan de traspaso", e)
                _uiState.update { it.copy(isSaving = false, snackbarMessage = "Error al guardar: ${e.message}") }
            }
        }
    }
}
