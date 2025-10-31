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

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun onPlanGuardadoNavegado() {
        _uiState.update { it.copy(planGuardadoExitoso = false) }
    }

    fun onDialogoMostrado() {
        _uiState.update { it.copy(preguntaCache = false) }
    }

    fun cargarPlanDesdeCache() {
        if (TraspasoPlanCache.esValido()) {
            _uiState.update {
                it.copy(
                    isLoading = false,
                    sugerencias = TraspasoPlanCache.planGuardado!!
                )
            }
            viewModelScope.launch { cargarLotesLibresEnMatriz() } // Recargar lotes por si acaso
        } else {
            cargarPlanDeTraspaso(true)
        }
    }

    fun cargarPlanDeTraspaso(descartarCache: Boolean) {
        if (descartarCache) {
            TraspasoPlanCache.limpiar()
        }
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

                val sugerencias =
                    generarSugerenciasInteligentes(productsOrdenados, allLotesLibresMatriz)

                _uiState.update { it.copy(isLoading = false, sugerencias = sugerencias) }
                guardarEnCache(sugerencias)

            } catch (e: Exception) {
                Log.e(TAG, "Error crítico en cargarPlanDeTraspaso", e)
                _uiState.update {
                    it.copy(
                        isLoading = false,
                        error = "Error al generar plan: ${e.localizedMessage}"
                    )
                }
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
            Log.d(
                TAG,
                "Cargados ${lotesDisponibles.size} lotes libres de Matriz (estadoTraspaso == null)."
            )
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
            val (sugerenciaInicial, lotesUsadosEnSugerencia) = generarSugerenciaBaseYLiquidacion(
                product,
                lotesLibresDelProducto
            )
            sugerenciasMutables[product.id] = sugerenciaInicial
            lotesComprometidos.addAll(lotesUsadosEnSugerencia.map { it.loteId }) // Guardar IDs
            Log.v(
                TAG,
                "Sugerencia Base/Liquidación para ${product.name}: ${sugerenciaInicial.sugerenciaKg} Kg ${if (sugerenciaInicial.isSugerenciaLiquidacion) "(Incluye Liq.)" else ""}. Lotes usados: ${
                    lotesUsadosEnSugerencia.joinToString {
                        it.loteId.takeLast(4)
                    }
                }"
            )
        }

        Log.d(TAG, "Sugerencias Base/Liquidación calculadas. Iniciando lógica Rector/Regido...")

        val mesesAnosRectoresSugeridos = mutableMapOf<String, MutableSet<Pair<Int, Int>>>()
        sugerenciasMutables.values.forEach { sugerencia ->
            if (sugerencia.product.categoria == "PESCADO_CHICO" && sugerencia.product.productoRectorId == sugerencia.product.id) {
                sugerencia.lotesParaTraspaso.forEach { desglose ->
                    desglose.loteFecha?.let { fecha ->
                        val cal = Calendar.getInstance().apply { time = fecha }
                        val mesAno = cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR)
                        mesesAnosRectoresSugeridos.getOrPut(sugerencia.product.id) { mutableSetOf() }
                            .add(mesAno)
                    }
                }
                if (sugerencia.lotesParaTraspaso.isNotEmpty()) {
                    Log.d(
                        TAG,
                        "Rector ${sugerencia.product.name} sugiere traspaso de lotes de meses/años: ${mesesAnosRectoresSugeridos[sugerencia.product.id]?.joinToString()}"
                    )
                }
            }
        }

        sugerenciasMutables.values.toList().forEach { sugerenciaRegido ->
            if (sugerenciaRegido.product.categoria == "PESCADO_CHICO" &&
                !sugerenciaRegido.product.productoRectorId.isNullOrBlank() &&
                sugerenciaRegido.product.productoRectorId != sugerenciaRegido.product.id &&
                sugerenciaRegido.sugerenciaKg < stockEpsilon
            ) {

                val rectorId = sugerenciaRegido.product.productoRectorId
                val mesesAnosDelRector = mesesAnosRectoresSugeridos[rectorId]

                if (!mesesAnosDelRector.isNullOrEmpty()) {
                    Log.d(
                        TAG,
                        "Intentando inducir sugerencia para Regido ${sugerenciaRegido.product.name} basado en Rector ${rectorId} (meses: ${mesesAnosDelRector.joinToString()})"
                    )
                    val lotesLibresRegido =
                        (lotesLibresPorProducto[sugerenciaRegido.product.id] ?: emptyList())
                            .filter { it.id !in lotesComprometidos }

                    val lotesCoincidentes = lotesLibresRegido.filter { lote ->
                        lote.receivedAt?.let { fechaLote ->
                            val cal = Calendar.getInstance().apply { time = fechaLote }
                            (cal.get(Calendar.MONTH) to cal.get(Calendar.YEAR)) in mesesAnosDelRector
                        } ?: false
                    }

                    if (lotesCoincidentes.isNotEmpty()) {
                        Log.i(
                            TAG,
                            "¡Éxito! Lotes coincidentes encontrados para ${sugerenciaRegido.product.name}. Generando sugerencia inducida..."
                        )
                        val (nuevaSugerenciaRegido, lotesUsadosRegido) = generarSugerenciaBaseYLiquidacion(
                            sugerenciaRegido.product,
                            lotesCoincidentes
                        )

                        if (nuevaSugerenciaRegido.sugerenciaKg > stockEpsilon) {
                            sugerenciasMutables[sugerenciaRegido.product.id] = nuevaSugerenciaRegido
                            lotesComprometidos.addAll(lotesUsadosRegido.map { it.loteId }) // Guardar IDs
                            Log.d(
                                TAG,
                                "Sugerencia inducida aplicada a ${sugerenciaRegido.product.name}: ${nuevaSugerenciaRegido.sugerenciaKg} Kg. Lotes usados: ${
                                    lotesUsadosRegido.joinToString {
                                        it.loteId.takeLast(4)
                                    }
                                }"
                            )
                        } else {
                            Log.d(
                                TAG,
                                "Aunque hubo lotes coincidentes para ${sugerenciaRegido.product.name}, no se necesita traspaso (stock C04 >= ideal)."
                            )
                        }
                    } else {
                        Log.d(
                            TAG,
                            "No se encontraron lotes libres/coincidentes para ${sugerenciaRegido.product.name} de los meses del Rector."
                        )
                    }
                }
            }
        }

        Log.d(TAG, "Generación de sugerencias completada.")
        return productsOrdenados.mapNotNull { sugerenciasMutables[it.id] }
    }

    /**
     * Lógica de sugerencia corregida según "Opción A".
     * 1. Cubre el MÍNIMO (stockIdealC04).
     * 2. Revisa si el *resto del primer lote* cabe en el *espacio extra hasta el MÁXIMO* (stockMaximoC04).
     * 3. Si cabe, lo sugiere como liquidación (💡). Si no, se queda con la sugerencia base.
     */
    private fun generarSugerenciaBaseYLiquidacion(
        product: Product,
        lotesLibresDisponibles: List<StockLot>
    ): Pair<TraspasoSugerenciaItem, List<LoteDesglosado>> {

        // --- INICIO DE LA CORRECCIÓN DE LÓGICA (Opción A) ---
        // 1. Calcular la "Necesidad Base" para llegar al MÍNIMO (stockIdealC04)
        val necesidadKgBase = max(0.0, product.stockIdealC04 - product.stockCongelador04)

        // 2. Calcular el "Espacio Total" disponible hasta el MÁXIMO (stockMaximoC04)
        val espacioHastaMaximoKg = max(0.0, product.stockMaximoC04 - product.stockCongelador04)
        // --- FIN DE LA CORRECCIÓN DE LÓGICA ---

        var isLiquidacionAplicada = false
        var kgSugeridosTotal = 0.0
        var lotesDesglosadosFinal = mutableListOf<LoteDesglosado>()

        val esFijo = !product.requiresPackaging
        // Fallback por si `labelConfig` no está, pero `unit` sí.
        val unidadDefault =
            if (esFijo) product.unit.takeIf { !it.isNullOrBlank() } ?: "Unidad" else "Kg"
        // Fallback para el peso (usado si el lote no tiene info)
        val pesoUnidadFallback =
            if (esFijo) product.labelConfig?.get("weightPerUnit") as? Double ?: 1.0 else 1.0


        // --- PASO 1: CUBRIR LA "SUGERENCIA BASE" (EL MÍNIMO) ---
        if (necesidadKgBase > stockEpsilon) {
            val (desgloseBase, kgTomadosBase) = if (esFijo) {
                // ***** INICIO DE LA CORRECCIÓN DEL BUG *****
                // El problema estaba aquí. Usaba `pesoUnidadDefault` (que era 1.0)
                // La lógica correcta (la "antigua") es usar `convertirKgAUnidades`
                // que mira los lotes disponibles para saber el peso por unidad.
                Log.d(
                    TAG,
                    "Calculando [FIJO] para ${product.name}. Necesidad Base: ${
                        String.format(
                            "%.2f",
                            necesidadKgBase
                        )
                    } Kg"
                )
                // 1. Convertir KG a Unidades basándonos en los LOTES
                val (unidadesNecesariasBase, _) = convertirKgAUnidades(
                    necesidadKgBase,
                    lotesLibresDisponibles
                )
                Log.d(TAG, " -> Convertido a $unidadesNecesariasBase unidades (basado en lotes)")

                // 2. Desglosar usando esas unidades
                desglosarLotesParaCantidadUnidades(unidadesNecesariasBase, lotesLibresDisponibles)
                // ***** FIN DE LA CORRECCIÓN DEL BUG *****
            } else {
                // Lógica de Granel (esta estaba bien)
                Log.d(
                    TAG,
                    "Calculando [GRANEL] para ${product.name}. Necesidad Base: ${
                        String.format(
                            "%.2f",
                            necesidadKgBase
                        )
                    } Kg"
                )
                desglosarLotesParaCantidadKg(necesidadKgBase, lotesLibresDisponibles)
            }
            lotesDesglosadosFinal.addAll(desgloseBase)
            kgSugeridosTotal = kgTomadosBase
            Log.d(
                TAG,
                " -> Paso 1 completado. KG Tomados: ${String.format("%.2f", kgSugeridosTotal)}"
            )
        }

        // --- PASO 2: INTENTAR "LIQUIDACIÓN" SI QUEDA ESPACIO HASTA EL MÁXIMO ---

        // Calcular cuánto espacio queda *después* de sugerir el mínimo
        var kgFaltantesParaMaximoActual = max(0.0, espacioHastaMaximoKg - kgSugeridosTotal)

        // Solo intentar liquidar si hay espacio extra Y si ya tomamos algo en el Paso 1
        if (kgFaltantesParaMaximoActual > stockEpsilon && lotesDesglosadosFinal.isNotEmpty()) {

            // Lógica del usuario: "solo ese lote el primero siempre sera en base al primer lote"
            val primerDesglose = lotesDesglosadosFinal.first()
            val primerLoteUsado = primerDesglose.lote // Objeto StockLot completo
            val kgTomadosDelPrimerLote = primerDesglose.cantidadATomarKg

            if (primerLoteUsado != null) {
                val kgRestantesEnPrimerLote =
                    primerLoteUsado.currentQuantity - kgTomadosDelPrimerLote
                val kgParaLiquidar = kgRestantesEnPrimerLote // Lo que queda de ese lote

                // REGLA DE ORO: ¿Lo que queda del lote cabe en el espacio extra?
                if (kgParaLiquidar > stockEpsilon && kgParaLiquidar <= (kgFaltantesParaMaximoActual + stockEpsilon)) {
                    // SÍ CABE.
                    Log.d(
                        TAG,
                        "💡 Sugiriendo liquidación para ${product.name}, lote ${
                            primerLoteUsado.id.takeLast(4)
                        } (${String.format("%.2f", kgParaLiquidar)} Kg extra)"
                    )

                    val nuevaCantidadKg = primerDesglose.cantidadATomarKg + kgParaLiquidar
                    var nuevasUnidades: Double? = primerDesglose.cantidadATomarUnidades

                    // Actualizar unidades si es fijo
                    if (esFijo && primerLoteUsado.pesoPorUnidad != null && primerLoteUsado.pesoPorUnidad > 0) {
                        // Usar floor porque estamos liquidando, no cubriendo necesidad
                        nuevasUnidades = floor(nuevaCantidadKg / primerLoteUsado.pesoPorUnidad)
                    }

                    // Crear una nueva instancia actualizada
                    val desgloseActualizado = primerDesglose.copy(
                        cantidadATomarKg = nuevaCantidadKg,
                        cantidadATomarUnidades = nuevasUnidades
                    )

                    // Reemplazar el objeto antiguo en la lista con el nuevo
                    lotesDesglosadosFinal[0] = desgloseActualizado

                    // Actualizar totales
                    kgSugeridosTotal += kgParaLiquidar
                    isLiquidacionAplicada = true

                } else if (kgParaLiquidar > stockEpsilon) {
                    // NO CABE. (Ejemplo: 246 Kg total vs 245 Kg max)
                    Log.d(
                        TAG, "Liquidación de lote ${primerLoteUsado.id.takeLast(4)} no sugerida. " +
                                "Restante (${String.format("%.2f", kgParaLiquidar)} Kg) " +
                                "excede espacio extra (${
                                    String.format(
                                        "%.2f",
                                        kgFaltantesParaMaximoActual
                                    )
                                } Kg)."
                    )
                }
            }
        }

        // --- PASO 3: RE-DESGLOSAR PARA FIJOS ---
        // Si es Fijo, recalculamos las unidades totales y re-desglosamos para asegurar KGs correctos
        // Esto es crucial si la "Liquidación" (Paso 2) cambió los KGs
        val unidadesFinales: Int
        if (esFijo) {
            // Usar el peso de referencia del primer lote, o el fallback del producto
            val pesoRef =
                lotesDesglosadosFinal.firstNotNullOfOrNull { it.lotePesoPorUnidad?.takeIf { p -> p > 0 } }
                    ?: pesoUnidadFallback
            // Usar ceil para asegurar que cubrimos la cantidad de KG total (que puede incluir liquidación)
            unidadesFinales = ceil(kgSugeridosTotal / pesoRef).toInt()
            Log.d(
                TAG,
                "Paso 3 [FIJO] ${product.name}: KG Totales ${
                    String.format(
                        "%.2f",
                        kgSugeridosTotal
                    )
                } / PesoRef ${String.format("%.2f", pesoRef)} = $unidadesFinales unidades (ceil)"
            )


            // Si la cantidad de unidades es > 0, re-desglosar para obtener los KG exactos
            if (unidadesFinales > 0) {
                // Usamos *todos* los lotes libres, porque la liquidación podría haber requerido más de lo que cabía en el primer lote
                val (desgloseFinalReal, kgTomadosFinalReal) = desglosarLotesParaCantidadUnidades(
                    unidadesFinales,
                    lotesLibresDisponibles
                )
                lotesDesglosadosFinal = desgloseFinalReal.toMutableList()
                kgSugeridosTotal = kgTomadosFinalReal
                Log.d(
                    TAG,
                    " -> Re-desglose por $unidadesFinales unidades dio ${
                        String.format(
                            "%.2f",
                            kgSugeridosTotal
                        )
                    } Kg reales"
                )
            }
        } else {
            unidadesFinales = 0 // 0 para granel
        }

        // --- PASO 4: CREAR EL ITEM FINAL ---
        val itemFinal = TraspasoSugerenciaItem(
            product = product,
            sugerenciaKg = kgSugeridosTotal,
            lotesParaTraspaso = lotesDesglosadosFinal.sortedBy { it.loteFecha },
            impactoStockMatriz = product.stockMatriz - kgSugeridosTotal,
            incluidoEnPdf = kgSugeridosTotal > stockEpsilon,
            cantidadEditadaUnidades = unidadesFinales, // Usar las unidades finales calculadas
            unidadDeEmpaqueEditada = if (esFijo) lotesDesglosadosFinal.firstNotNullOfOrNull { it.loteUnidad }
                ?: unidadDefault else "Kg",
            lotesSeleccionadosManualmente = null, // Inicialmente no es manual
            isRecalculating = false,
            isSugerenciaLiquidacion = isLiquidacionAplicada
        )
        // Devolver la lista final de LoteDesglosado que realmente se usaron
        return Pair(itemFinal, lotesDesglosadosFinal)
    }


    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        val sugerenciaAfectada =
            _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        if (sugerenciaAfectada.product.requiresPackaging) {
            Log.w(
                TAG,
                "recalcularSugerenciaPorUnidades llamado para producto Granel ${sugerenciaAfectada.product.name}"
            )
            return
        }
        setRecalculatingState(productId, true)
        viewModelScope.launch {
            val lotesDisponibles = allLotesLibresMatriz[productId] ?: emptyList()
            val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(
                cantidadEnUnidades,
                lotesDisponibles
            )
            val nuevaUnidad =
                lotesDesglosados.firstNotNullOfOrNull { desglose -> desglose.loteUnidad }
                    ?: sugerenciaAfectada.unidadDeEmpaqueEditada

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
                    } else {
                        it
                    }
                }
                guardarEnCache(nuevasSugerencias)
                state.copy(sugerencias = nuevasSugerencias)
            }
        }
    }

    fun recalcularSugerenciaPorKg(productId: String, cantidadEnKg: Double) {
        // Esta función podría habilitarse si quieres permitir editar KG para Granel
        // La lógica sería similar a la de unidades, pero llamando a desglosarLotesParaCantidadKg
        val sugerenciaAfectada =
            _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        if (!sugerenciaAfectada.product.requiresPackaging) {
            Log.w(
                TAG,
                "recalcularSugerenciaPorKg llamado para producto Fijo ${sugerenciaAfectada.product.name}"
            )
            return
        }
        setRecalculatingState(productId, true)
        viewModelScope.launch {
            val lotesDisponibles = allLotesLibresMatriz[productId] ?: emptyList()
            val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadKg(
                cantidadEnKg,
                lotesDisponibles
            )
            // Para granel, la unidad siempre es "Kg" y las unidades "0" (a menos que se edite en PDF)

            _uiState.update { state ->
                val nuevasSugerencias = state.sugerencias.map {
                    if (it.product.id == productId) {
                        val pdfUnidades = it.cantidadEditadaUnidades // Mantener las unidades de PDF
                        val pdfUnidad = it.unidadDeEmpaqueEditada // Mantener la unidad de PDF
                        it.copy(
                            sugerenciaKg = kgRealesTomados,
                            lotesParaTraspaso = lotesDesglosados,
                            impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                            cantidadEditadaUnidades = pdfUnidades, // Mantener
                            unidadDeEmpaqueEditada = pdfUnidad, // Mantener
                            isRecalculating = false,
                            lotesSeleccionadosManualmente = null,
                            isSugerenciaLiquidacion = false
                        )
                    } else {
                        it
                    }
                }
                guardarEnCache(nuevasSugerencias)
                state.copy(sugerencias = nuevasSugerencias)
            }
        }
    }


    fun actualizarLotesManualmentePorIds(productId: String, loteIdsSeleccionados: List<String>) {
        val sugerenciaAfectada =
            _uiState.value.sugerencias.find { it.product.id == productId } ?: return
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

    fun actualizarPorDesgloseManual(
        productId: String,
        desgloseManualUsuario: List<DesgloseManualResult>
    ) {
        val sugerenciaAfectada =
            _uiState.value.sugerencias.find { it.product.id == productId } ?: return
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
                var unidadFinal =
                    sugerenciaAfectada.unidadDeEmpaqueEditada // Usar la actual como fallback
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
                            val unidadesDisponibles =
                                floor(loteOriginal.currentQuantity / pesoUnidad).toInt()
                            val unidadesRealesATomar = min(unidadesUsuario, unidadesDisponibles)
                            val kgATomar = unidadesRealesATomar * pesoUnidad
                            unidadFinal = loteOriginal.unidadDeEmpaque
                                ?: unidadFinal // Actualizar unidad si es válida

                            if (unidadesRealesATomar > 0) {
                                if (unidadesRealesATomar < unidadesUsuario) ajusteRealizadoMsg =
                                    "Una o más cantidades ajustadas al stock."
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
                                if (kotlin.math.abs(kgUsuario - kgRealesATomar) > stockEpsilon) ajusteRealizadoMsg =
                                    "Una o más cantidades ajustadas al stock."
                                cantidadRealTomadaKg = kgRealesATomar
                                // totalUnidadesDesglosadas se queda en 0 para granel
                            }
                        }

                        if (cantidadRealTomadaKg > stockEpsilon) {
                            totalKgDesglosado += cantidadRealTomadaKg
                            lotesDesglosadosFinal.add(
                                LoteDesglosado(
                                    loteId = loteOriginal.id,
                                    cantidadATomarKg = cantidadRealTomadaKg,
                                    cantidadATomarUnidades = cantidadRealTomadaUnidades,
                                    lote = loteOriginal,
                                    loteFecha = loteOriginal.receivedAt,
                                    loteProveedor = loteOriginal.supplierName,
                                    loteUnidad = loteOriginal.unidadDeEmpaque,
                                    lotePesoPorUnidad = loteOriginal.pesoPorUnidad
                                )
                            )
                        }
                    } else {
                        Log.w(
                            TAG,
                            "Lote ${itemUsuario.loteId} no encontrado/libre para desglose manual de ${sugerenciaAfectada.product.name}"
                        )
                        ajusteRealizadoMsg = "Algunos lotes no estaban disponibles."
                    }
                } // Fin forEach

                // Recalcular unidades totales si es fijo, basado en el total de KG y el peso unitario
                if (esFijo) {
                    val pesoRef =
                        lotesDesglosadosFinal.firstNotNullOfOrNull { it.lotePesoPorUnidad?.takeIf { p -> p > 0 } }
                            ?: 1.0
                    totalUnidadesDesglosadas = ceil(totalKgDesglosado / pesoRef).toInt()
                }

                finalMessage = ajusteRealizadoMsg ?: finalMessage
                Log.d(
                    TAG,
                    "actualizarPorDesgloseManual para $productId: Desglose manual (switch) aplicado. KG totales: ${
                        String.format(
                            "%.2f",
                            totalKgDesglosado
                        )
                    }, Unidades: $totalUnidadesDesglosadas"
                )

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

    private fun actualizarEstadoConLotesManuales(
        sugerenciaOriginal: TraspasoSugerenciaItem,
        lotesSeleccionadosCompletos: List<StockLot> // Lotes seleccionados por checkbox
    ) {
        val esFijo = !sugerenciaOriginal.product.requiresPackaging
        // Usar la cantidad que el usuario YA TENÍA (editada o sugerida) como base
        val cantidadUnidadesNecesarias =
            if (esFijo) sugerenciaOriginal.cantidadEditadaUnidades else 0
        // **CORRECCIÓN**: Usar la cantidad de KG solo si es Granel
        val cantidadKgNecesarios = if (!esFijo) sugerenciaOriginal.sugerenciaKg else 0.0

        val (lotesDesglosados, kgRealesTomados) = if (esFijo && cantidadUnidadesNecesarias > 0) {
            Log.d(
                TAG,
                "actualizarEstadoConLotesManuales (Fijo): Desglosando $cantidadUnidadesNecesarias unidades en ${lotesSeleccionadosCompletos.size} lotes"
            )
            desglosarLotesParaCantidadUnidades(
                cantidadUnidadesNecesarias,
                lotesSeleccionadosCompletos
            )
        } else if (!esFijo && cantidadKgNecesarios > 0) {
            Log.d(
                TAG,
                "actualizarEstadoConLotesManuales (Granel): Desglosando ${
                    String.format(
                        "%.2f",
                        cantidadKgNecesarios
                    )
                } Kg en ${lotesSeleccionadosCompletos.size} lotes"
            )
            desglosarLotesParaCantidadKg(cantidadKgNecesarios, lotesSeleccionadosCompletos)
        } else {
            Log.d(
                TAG,
                "actualizarEstadoConLotesManuales: Cantidad necesaria es 0, resultado vacío."
            )
            Pair(
                emptyList<LoteDesglosado>(),
                0.0
            ) // Si no hay cantidad necesaria, el resultado es vacío
        }
        Log.d(
            TAG,
            " -> Desglose manual (checkbox) dio ${String.format("%.2f", kgRealesTomados)} Kg reales"
        )


        // Recalcular unidades reales tomadas si es fijo
        val unidadesRealesTomadas = if (esFijo) {
            val pesoUnidadRef =
                lotesSeleccionadosCompletos.firstNotNullOfOrNull { it.pesoPorUnidad?.takeIf { p -> p > 0 } }
                    ?: 1.0
            ceil(kgRealesTomados / pesoUnidadRef).toInt()
        } else {
            0 // Granel siempre es 0 unidades (a menos que se edite PDF)
        }

        val nuevaUnidad =
            lotesSeleccionadosCompletos.firstNotNullOfOrNull { it.unidadDeEmpaque?.takeIf { u -> u.isNotBlank() } }
                ?: sugerenciaOriginal.unidadDeEmpaqueEditada

        // Mensaje de ajuste si la cantidad real difiere de la necesaria
        val msg =
            if ((esFijo && cantidadUnidadesNecesarias > unidadesRealesTomadas) || (!esFijo && cantidadKgNecesarios > kgRealesTomados + stockEpsilon)) {
                val necesariaStr = if (esFijo) "$cantidadUnidadesNecesarias $nuevaUnidad" else "${
                    String.format(
                        "%.2f",
                        cantidadKgNecesarios
                    )
                } Kg"
                val realStr = if (esFijo) "$unidadesRealesTomadas $nuevaUnidad" else "${
                    String.format(
                        "%.2f",
                        kgRealesTomados
                    )
                } Kg"
                "Cantidad necesaria ($necesariaStr) excede stock. Ajustado a $realStr."
            } else {
                "Selección de lotes manual aplicada."
            }

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == sugerenciaOriginal.product.id) {
                    // **CORRECCIÓN**: Mantener unidades PDF para granel
                    val pdfUnidades = if (esFijo) 0 else it.cantidadEditadaUnidades
                    val pdfUnidad = if (esFijo) nuevaUnidad else it.unidadDeEmpaqueEditada

                    it.copy(
                        lotesSeleccionadosManualmente = lotesSeleccionadosCompletos, // Guardar la lista COMPLETA seleccionada
                        lotesParaTraspaso = lotesDesglosados.sortedBy { d ->
                            d.loteFecha ?: Date(0)
                        }, // Guardar el desglose REAL
                        sugerenciaKg = kgRealesTomados, // Actualizar KG REALES
                        impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                        cantidadEditadaUnidades = if (esFijo) unidadesRealesTomadas else pdfUnidades, // Actualizar unidades REALES (o mantener PDF)
                        unidadDeEmpaqueEditada = if (esFijo) nuevaUnidad else pdfUnidad, // Actualizar unidad (o mantener PDF)
                        isRecalculating = false, // Quitar estado recalculando
                        isSugerenciaLiquidacion = false // Selección manual anula sugerencia de liquidación
                    )
                } else it
            }
            guardarEnCache(nuevasSugerencias) // Guardar en caché el nuevo estado
            state.copy(isLoading = false, sugerencias = nuevasSugerencias, snackbarMessage = msg)
        }
    }

    private fun actualizarEstadoConDesgloseManual(
        sugerenciaOriginal: TraspasoSugerenciaItem,
        lotesSeleccionadosCompletos: List<StockLot>, // Lotes usados en el desglose
        lotesDesglosadosCalculados: List<LoteDesglosado>, // Desglose con cantidades
        totalKgCalculado: Double,
        totalUnidadesCalculadas: Int,
        unidadFinalCalculada: String,
        mensaje: String
    ) {
        val esFijo = !sugerenciaOriginal.product.requiresPackaging
        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == sugerenciaOriginal.product.id) {
                    // **CORRECCIÓN**: Mantener unidades PDF para granel
                    val pdfUnidades = if (esFijo) 0 else it.cantidadEditadaUnidades
                    val pdfUnidad = if (esFijo) unidadFinalCalculada else it.unidadDeEmpaqueEditada

                    it.copy(
                        lotesSeleccionadosManualmente = lotesSeleccionadosCompletos, // Guardar lotes USADOS
                        lotesParaTraspaso = lotesDesglosadosCalculados.sortedBy { ld ->
                            ld.loteFecha ?: Date(0)
                        }, // Guardar desglose calculado
                        sugerenciaKg = totalKgCalculado, // KG totales calculados
                        impactoStockMatriz = it.product.stockMatriz - totalKgCalculado,
                        cantidadEditadaUnidades = if (esFijo) totalUnidadesCalculadas else pdfUnidades, // Unidades totales calculadas (o mantener PDF)
                        unidadDeEmpaqueEditada = if (esFijo) unidadFinalCalculada else pdfUnidad, // Unidad final calculada (o mantener PDF)
                        isRecalculating = false, // Quitar estado
                        isSugerenciaLiquidacion = false // Desglose manual anula liquidación
                    )
                } else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(
                isLoading = false,
                sugerencias = nuevasSugerencias,
                snackbarMessage = mensaje
            )
        }
    }

    private fun setRecalculatingState(productId: String, isRecalculating: Boolean) {
        _uiState.update { currentState ->
            val updatedSugerencias = currentState.sugerencias.map {
                if (it.product.id == productId) it.copy(isRecalculating = isRecalculating) else it
            }
            currentState.copy(sugerencias = updatedSugerencias)
        }
    }

    /**
     * Esta es la función "antigua" que pegaste.
     * La renombro y la dejo aquí para referencia, pero la lógica
     * se ha integrado en `generarSugerenciaBaseYLiquidacion`.
     * Las funciones auxiliares `desglosarLotesParaCantidadKg`,
     * `desglosarLotesParaCantidadUnidades`, y `convertirKgAUnidades`
     * se mantienen al final del archivo.
     */
    private fun generarSugerenciaInicial_OLD(
        product: Product,
        lotesDelProducto: List<StockLot>
    ): TraspasoSugerenciaItem {
        // 1. Calcular necesidad y sugerencia base en KILOS
        val necesidadKg = product.stockIdealC04 - product.stockCongelador04
        val disponibleKg = lotesDelProducto.sumOf { it.currentQuantity }
        val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))
        val incluido = sugerenciaKg > 0.0

        // 2. Comprobar si este producto se maneja por unidades (es "Fijo")
        val tieneUnidadesDeEmpaque =
            lotesDelProducto.any { !it.unidadDeEmpaque.isNullOrBlank() && it.pesoPorUnidad != null && it.pesoPorUnidad > 0 }

        if (tieneUnidadesDeEmpaque && sugerenciaKg > 0) {
            // --- LÓGICA PARA "FIJOS" (Cajas, Piezas, etc.) ---
            // Esta es la lógica que tenías y que funcionaba.

            // 3. Convertir la necesidad (105kg) en unidades (7 cajas)
            val (cantidadEnUnidades, unidad) = convertirKgAUnidades(sugerenciaKg, lotesDelProducto)

            // 4. Buscar lotes para cumplir con las 7 cajas
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadUnidades(
                cantidadEnUnidades,
                lotesDelProducto
            )

            // 5. Devolver la sugerencia basada en UNIDADES
            return TraspasoSugerenciaItem(
                product,
                kgTomados, // Kilos reales tomados (ej. 7 cajas * 15kg = 105kg)
                lotesDesglosados,
                product.stockMatriz - kgTomados,
                incluidoEnPdf = incluido,
                cantidadEditadaUnidades = cantidadEnUnidades, // 7
                unidadDeEmpaqueEditada = unidad, // "Cajas"
                isSugerenciaLiquidacion = false // Añadido flag faltante
            )
        } else {
            // --- LÓGICA PARA "GRANEL" (Solo Kilos) ---
            // Esta es la lógica que "arreglamos" y que ahora funciona.

            // 3. Buscar lotes para cumplir con los 105kg
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadKg(
                sugerenciaKg,
                lotesDelProducto
            )

            // 4. Devolver la sugerencia basada en KILOS
            return TraspasoSugerenciaItem(
                product,
                kgTomados, // Kilos reales tomados (ej. 105kg)
                lotesDesglosados,
                product.stockMatriz - kgTomados,
                incluidoEnPdf = incluido,
                cantidadEditadaUnidades = 0, // 0 unidades
                unidadDeEmpaqueEditada = "Kg", // La unidad es "Kg"
                isSugerenciaLiquidacion = false // Añadido flag faltante
            )
        }
    }

    /**
     * Función auxiliar para productos "GRANEL".
     * Toma una cantidad de KG y busca lotes para cubrirlos (FIFO).
     */
    private fun desglosarLotesParaCantidadKg(
        cantidadNecesariaKg: Double,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesariaKg

        for (lote in lotesDisponibles) { // Ya vienen ordenados por fecha
            if (kgRestantes <= stockEpsilon) break

            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)

            if (aTomarDeEsteLote > stockEpsilon) {
                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id,
                        cantidadATomarKg = aTomarDeEsteLote,
                        cantidadATomarUnidades = null, // Granel no maneja unidades
                        lote = lote,
                        loteFecha = lote.receivedAt,
                        loteProveedor = lote.supplierName,
                        loteUnidad = "Kg",
                        lotePesoPorUnidad = null
                    )
                )
                kgRestantes -= aTomarDeEsteLote
                kgAcumulados += aTomarDeEsteLote
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    /**
     * Función auxiliar para productos "FIJOS".
     * Toma una cantidad de UNIDADES y busca lotes para cubrirlas (FIFO).
     */
    private fun desglosarLotesParaCantidadUnidades(
        unidadesNecesarias: Int,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var unidadesRestantes = unidadesNecesarias
        for (lote in lotesDisponibles) { // Ya vienen ordenados por fecha
            if (unidadesRestantes <= 0) break
            val pesoPorUnidad = lote.pesoPorUnidad // Es Double?
            // Solo procesar si el lote tiene info de unidad válida
            if (pesoPorUnidad != null && pesoPorUnidad > 0 && !lote.unidadDeEmpaque.isNullOrBlank()) {

                val unidadesDisponiblesEnLote = floor(lote.currentQuantity / pesoPorUnidad).toInt()
                val unidadesA_TomarDeEsteLote = min(unidadesDisponiblesEnLote, unidadesRestantes)

                if (unidadesA_TomarDeEsteLote > 0) {
                    val kgA_TomarDeEsteLote = unidadesA_TomarDeEsteLote * pesoPorUnidad
                    lotesDesglosados.add(
                        LoteDesglosado(
                            loteId = lote.id,
                            cantidadATomarKg = kgA_TomarDeEsteLote,
                            cantidadATomarUnidades = unidadesA_TomarDeEsteLote.toDouble(),
                            lote = lote, // Se pasa el objeto para uso temporal en la UI
                            loteFecha = lote.receivedAt,
                            loteProveedor = lote.supplierName,
                            loteUnidad = lote.unidadDeEmpaque,
                            lotePesoPorUnidad = lote.pesoPorUnidad
                        )
                    )
                    kgAcumulados += kgA_TomarDeEsteLote
                    unidadesRestantes -= unidadesA_TomarDeEsteLote
                }
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    /**
     * Función auxiliar para productos "FIJOS".
     * Convierte una necesidad de KG en una cantidad de UNIDADES.
     * Esta es la lógica "antigua" que queremos restaurar.
     */
    private fun convertirKgAUnidades(
        kg: Double,
        lotesDisponibles: List<StockLot>
    ): Pair<Int, String> {
        // Busca el primer lote con información de empaque válida para usarlo como referencia
        val primerLoteConUnidad =
            lotesDisponibles.firstOrNull { it.pesoPorUnidad != null && it.pesoPorUnidad > 0 && !it.unidadDeEmpaque.isNullOrBlank() }

        val unidad =
            primerLoteConUnidad?.unidadDeEmpaque ?: "Unidad" // Default a "Unidad" si no encuentra
        val pesoPorUnidad = primerLoteConUnidad?.pesoPorUnidad ?: 1.0 // Default a 1.0

        // Redondea hacia ARRIBA (ceil) para asegurar que se cubra la necesidad
        // Ej: 105kg / 15kg/caja = 7.0 -> 7 cajas
        // Ej: 106kg / 15kg/caja = 7.06 -> 8 cajas
        val cantidadEnUnidades =
            if (kg > 0 && pesoPorUnidad > 0) ceil(kg / pesoPorUnidad).toInt() else 0

        Log.d(
            TAG,
            "convertirKgAUnidades: ${
                String.format(
                    "%.2f",
                    kg
                )
            } Kg -> $cantidadEnUnidades $unidad (usando peso ref: ${
                String.format(
                    "%.2f",
                    pesoPorUnidad
                )
            })"
        )
        return Pair(cantidadEnUnidades, unidad)
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
                sugerenciaKg = 0.0,
                lotesParaTraspaso = emptyList(),
                impactoStockMatriz = 0.0,
                incluidoEnPdf = true,
                cantidadEditadaUnidades = 0,
                unidadDeEmpaqueEditada = "",
                lotesSeleccionadosManualmente = null,
                isRecalculating = false,
                isSugerenciaLiquidacion = false
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
        Log.d(TAG, "Guardando plan con ${planParaGuardar.count()} items (incluyendo filas vacías).")

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

                planParaGuardar.forEachIndexed { index, item ->
                    val detalleDocRef = planDocRef.collection("detalles").document()

                    if (item.product.name != "FILA_VACIA") {
                        // --- LÓGICA NORMAL PARA UN PRODUCTO ---
                        Log.v(TAG, "Procesando item ${item.product.name} (Orden: $index) para guardar. KG: ${item.sugerenciaKg}")
                        val lotesAGuardar = item.lotesParaTraspaso.map { it.copy(lote=null) }

                        val esFijo = !item.product.requiresPackaging
                        val unidadesAGuardar = if(esFijo) item.cantidadEditadaUnidades else 0
                        val unidadAGuardar = if(esFijo) item.unidadDeEmpaqueEditada else "Kg"

                        val detalle = DetalleTraspasoPlan(
                            id = detalleDocRef.id, productId = item.product.id, productName = item.product.name,
                            sugerenciaKg = item.sugerenciaKg,
                            sugerenciaUnidades = unidadesAGuardar,
                            unidadDeEmpaque = unidadAGuardar,
                            lotesSugeridos = lotesAGuardar,
                            orden = index // Guardar el orden (requiere el cambio en el modelo)
                        )
                        batch.set(detalleDocRef, detalle)
                        Log.v(TAG, " -> Detalle creado para ${item.product.name}. Lotes a reservar: ${lotesAGuardar.size}")

                        lotesAGuardar.forEach { desglose ->
                            if (desglose.cantidadATomarKg > stockEpsilon && desglose.loteId.isNotBlank()) {
                                val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                                batch.update(loteRef, "estadoTraspaso", planPrincipal.id)
                                Log.v(TAG, "    -> Reservando lote ${desglose.loteId.takeLast(4)} con ID de plan ${planPrincipal.id.takeLast(4)}")
                            }
                        }
                    } else {
                        // --- LÓGICA PARA FILA VACÍA ---
                        Log.v(TAG, "Procesando item FILA_VACIA (Orden: $index) para guardar.")
                        val detalleVacio = DetalleTraspasoPlan(
                            id = detalleDocRef.id,
                            productId = item.product.id,
                            productName = item.product.name,
                            sugerenciaKg = 0.0,
                            sugerenciaUnidades = 0,
                            unidadDeEmpaque = "",
                            lotesSugeridos = emptyList(),
                            orden = index // Guardar el orden (requiere el cambio en el modelo)
                        )
                        batch.set(detalleDocRef, detalleVacio)
                        Log.v(TAG, " -> Detalle 'FILA_VACIA' creado.")
                    }
                }

                batch.commit().await()
                Log.i(TAG, "¡Plan de traspaso ${planPrincipal.id} guardado exitosamente!")
                TraspasoPlanCache.limpiar()
                allLotesLibresMatriz = cargarLotesLibresEnMatriz()

                // ***** INICIO DE LA CORRECCIÓN (TYPO) *****
                // Cambiado "planGuardadoExitosito" a "planGuardadoExitoso"
                _uiState.update { it.copy(isSaving = false, planGuardadoExitoso = true, sugerencias = emptyList()) }
                // ***** FIN DE LA CORRECCIÓN (TYPO) *****

            } catch (e: Exception) {
                Log.e(TAG, "Error crítico al guardar plan de traspaso", e)
                _uiState.update { it.copy(isSaving = false, snackbarMessage = "Error al guardar: ${e.message}") }
            }
        }
    }
}
