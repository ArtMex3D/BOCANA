package com.cesar.bocana.ui.traspasos.plan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.*
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
import java.util.TimeZone
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

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

    private var allLotesEnMatriz: Map<String, List<StockLot>> = emptyMap()
    private val TAG = "PlanTraspasoViewModel"

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
            _uiState.value = PlanTraspasoUiState(isLoading = false, sugerencias = TraspasoPlanCache.planGuardado!!)
            viewModelScope.launch { cargarLotesEnMatriz() }
        } else {
            cargarPlanDeTraspaso(true)
        }
    }

    fun cargarPlanDeTraspaso(descartarCache: Boolean) {
        if (descartarCache) {
            TraspasoPlanCache.limpiar()
        }
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val products = db.collection(FirestoreCollections.PRODUCTS)
                    .whereEqualTo("isActive", true)
                    .orderBy("ordenTraspaso")
                    .orderBy("name")
                    .get().await().toObjects(Product::class.java)

                cargarLotesEnMatriz()

                val (fantasmas, reales) = products.partition {
                    val n = it.name.trim().lowercase()
                    n.isEmpty() || n == "-" || n == "." || n == "_" || n.contains("vaci") || n.contains("espacio") || n.contains("fila")
                }
                val ordenDefinitivo = reales + fantasmas

                // 💡 MAGIA APLICADA: mapNotNull ignora los que devuelven 'null'
                val sugerencias = ordenDefinitivo.mapNotNull { product ->
                    val lotes = allLotesEnMatriz[product.id] ?: emptyList()

                    // Si el producto es a granel, y NO tiene costales físicos en almacén...
                    if (product.requiresPackaging && !isTraspasoFijo(product, lotes)) {
                        null // ¡Bórralo de la lista! No se muestra en pantalla.
                    } else {
                        generarSugerenciaInicial(product, lotes)
                    }
                }

                _uiState.update { it.copy(isLoading = false, sugerencias = sugerencias) }
                guardarEnCache(sugerencias)

            } catch (e: Exception) {
                Log.e(TAG, "Error en cargarPlanDeTraspaso", e)
                _uiState.update { it.copy(isLoading = false, error = e.localizedMessage) }
            }
        }
    }

    fun guardarPlanEnFirestore(fechaPlan: Date) {
        val currentUser = auth.currentUser
        if (currentUser == null) {
            _uiState.update { it.copy(snackbarMessage = "Error: Usuario no autenticado.") }
            return
        }
        val planParaGuardar = _uiState.value.sugerencias.filter { it.incluidoEnPdf }
        if (planParaGuardar.isEmpty()) {
            _uiState.update { it.copy(snackbarMessage = "No hay productos seleccionados para el traspaso.") }
            return
        }

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }
            try {
                val planDocRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document()
                val planPrincipal = TraspasoPlanificado(
                    id = planDocRef.id,
                    createdAt = Date(),
                    createdBy = currentUser.displayName ?: currentUser.email ?: "Desconocido",
                    fechaPlan = fechaPlan,
                    estado = TraspasoEstado.PENDIENTE
                )

                val batch: WriteBatch = db.batch()
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
                _uiState.update { it.copy(isSaving = false, planGuardadoExitoso = true) }
            } catch (e: Exception) {
                Log.e(TAG, "Error al guardar plan de traspaso", e)
                _uiState.update { it.copy(isSaving = false, snackbarMessage = "Error al guardar: ${e.message}") }
            }
        }
    }

    private fun guardarEnCache(sugerencias: List<TraspasoSugerenciaItem>) {
        TraspasoPlanCache.planGuardado = sugerencias
        TraspasoPlanCache.timestamp = System.currentTimeMillis()
    }

    private suspend fun cargarLotesEnMatriz() {
        val lotesSnapshot = db.collection(FirestoreCollections.INVENTORY_LOTS)
            .whereEqualTo("location", "MATRIZ")
            .whereEqualTo("isDepleted", false)
            .whereEqualTo("isPackaged", true)
            .orderBy("receivedAt")
            .get().await()

        val lotesDisponibles = lotesSnapshot.toObjects(StockLot::class.java)
            .filter { it.estadoTraspaso == null }

        allLotesEnMatriz = lotesDisponibles.groupBy { it.productId }
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

    // 💡 MAGIA RESTAURADA: Detecta si la mercancía está en costales/cajas reales
    private fun isTraspasoFijo(product: Product, lotes: List<StockLot>): Boolean {
        if (!product.requiresPackaging) return true
        return lotes.any { !it.unidadDeEmpaque.isNullOrBlank() && it.unidadDeEmpaque != "Kg" && (it.pesoPorUnidad ?: 0.0) > 0.0 }
    }

    private fun generarSugerenciaInicial(product: Product, lotesDelProducto: List<StockLot>): TraspasoSugerenciaItem {
        val necesidadKg = max(0.0, product.stockIdealC04 - product.stockCongelador04)
        val disponibleKg = lotesDelProducto.sumOf { it.currentQuantity }
        val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))
        val incluido = sugerenciaKg > 0.0

        val esFijo = isTraspasoFijo(product, lotesDelProducto)

        if (esFijo && sugerenciaKg > 0) {
            val (cantidadEnUnidades, unidad) = convertirKgAUnidades(sugerenciaKg, lotesDelProducto)
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDelProducto)
            return TraspasoSugerenciaItem(
                product, kgTomados, lotesDesglosados, product.stockMatriz - kgTomados,
                incluidoEnPdf = incluido, cantidadEditadaUnidades = cantidadEnUnidades, unidadDeEmpaqueEditada = unidad
            )
        } else if (!esFijo && sugerenciaKg > 0) {
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadKg(sugerenciaKg, lotesDelProducto)
            return TraspasoSugerenciaItem(
                product, kgTomados, lotesDesglosados, product.stockMatriz - kgTomados,
                incluidoEnPdf = incluido, cantidadEditadaUnidades = 0, unidadDeEmpaqueEditada = "Kg"
            )
        } else {
            val unidad = if (esFijo) product.unit ?: "Unidad" else "Kg"
            return TraspasoSugerenciaItem(
                product, 0.0, emptyList(), product.stockMatriz,
                incluidoEnPdf = incluido, cantidadEditadaUnidades = 0, unidadDeEmpaqueEditada = unidad
            )
        }
    }

    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()

        val esFijo = isTraspasoFijo(sugerenciaAfectada.product, lotesDisponibles)
        if (!esFijo) return

        setRecalculatingState(productId, true)
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDisponibles)
        val nuevaUnidad = lotesDesglosados.firstOrNull()?.loteUnidad ?: sugerenciaAfectada.unidadDeEmpaqueEditada

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(
                    sugerenciaKg = kgRealesTomados, lotesParaTraspaso = lotesDesglosados,
                    impactoStockMatriz = it.product.stockMatriz - kgRealesTomados, cantidadEditadaUnidades = cantidadEnUnidades,
                    unidadDeEmpaqueEditada = nuevaUnidad, isRecalculating = false, lotesSeleccionadosManualmente = null
                ) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(sugerencias = nuevasSugerencias)
        }
    }

    fun actualizarLotesManualmente(productId: String, lotesSeleccionados: List<StockLot>) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        setRecalculatingState(productId, true)

        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        val esFijo = isTraspasoFijo(sugerenciaAfectada.product, lotesDisponibles)

        val (lotesDesglosados, kgRealesTomados) = if (esFijo) {
            val unidadesNecesarias = sugerenciaAfectada.cantidadEditadaUnidades
            desglosarLotesParaCantidadUnidades(unidadesNecesarias, lotesSeleccionados)
        } else {
            val kgNecesarios = sugerenciaAfectada.sugerenciaKg
            desglosarLotesParaCantidadKg(kgNecesarios, lotesSeleccionados)
        }

        val unidadesRealesTomadas = if (esFijo) lotesDesglosados.sumOf { it.cantidadATomarUnidades ?: 0.0 }.toInt() else 0
        val nuevaUnidad = lotesSeleccionados.firstOrNull()?.unidadDeEmpaque ?: sugerenciaAfectada.unidadDeEmpaqueEditada

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(
                    lotesSeleccionadosManualmente = lotesSeleccionados, lotesParaTraspaso = lotesDesglosados,
                    sugerenciaKg = kgRealesTomados, impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                    cantidadEditadaUnidades = if (esFijo) unidadesRealesTomadas else it.cantidadEditadaUnidades,
                    unidadDeEmpaqueEditada = if (esFijo) nuevaUnidad else it.unidadDeEmpaqueEditada,
                    isRecalculating = false
                ) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(sugerencias = nuevasSugerencias, snackbarMessage = "Lotes actualizados al stock seleccionado.")
        }
    }

    fun actualizarPorDesgloseManual(productId: String, desglose: List<DesgloseManualResult>) {
        setRecalculatingState(productId, true)
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return

        val esFijo = isTraspasoFijo(sugerenciaAfectada.product, lotesDisponibles)

        var totalKgDesglosado = 0.0
        var totalUnidadesDesglosadas = 0

        val lotesDesglosados = desglose.mapNotNull { itemUsuario ->
            val loteOriginal = lotesDisponibles.find { it.id == itemUsuario.loteId }
            if (loteOriginal != null) {
                var kgATomar = 0.0
                var unidadesATomar: Double? = null

                if (esFijo) {
                    val pesoUnidad = loteOriginal.pesoPorUnidad ?: 1.0
                    val unidades = itemUsuario.cantidad.toInt()
                    kgATomar = unidades * pesoUnidad
                    unidadesATomar = unidades.toDouble()
                    totalUnidadesDesglosadas += unidades
                } else {
                    kgATomar = itemUsuario.cantidad
                }

                if (kgATomar > 0) {
                    totalKgDesglosado += kgATomar
                    LoteDesglosado(
                        loteId = loteOriginal.id, cantidadATomarKg = kgATomar, cantidadATomarUnidades = unidadesATomar,
                        lote = loteOriginal, loteFecha = loteOriginal.receivedAt, loteProveedor = loteOriginal.supplierName,
                        loteUnidad = if (esFijo) loteOriginal.unidadDeEmpaque else "Kg", lotePesoPorUnidad = loteOriginal.pesoPorUnidad
                    )
                } else null
            } else null
        }

        val nuevaUnidad = lotesDesglosados.firstOrNull()?.loteUnidad ?: sugerenciaAfectada.unidadDeEmpaqueEditada

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(
                    lotesSeleccionadosManualmente = lotesDesglosados.mapNotNull { d -> d.lote },
                    lotesParaTraspaso = lotesDesglosados, sugerenciaKg = totalKgDesglosado,
                    impactoStockMatriz = it.product.stockMatriz - totalKgDesglosado,
                    cantidadEditadaUnidades = totalUnidadesDesglosadas, unidadDeEmpaqueEditada = nuevaUnidad,
                    isRecalculating = false
                ) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(sugerencias = nuevasSugerencias, snackbarMessage = "Plan actualizado con desglose manual.")
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

    private fun desglosarLotesParaCantidadKg(cantidadNecesariaKg: Double, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesariaKg

        for (lote in lotesDisponibles) {
            if (kgRestantes <= 0.01) break
            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)
            if (aTomarDeEsteLote > 0.01) {
                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id, cantidadATomarKg = aTomarDeEsteLote, cantidadATomarUnidades = null,
                        lote = lote, loteFecha = lote.receivedAt, loteProveedor = lote.supplierName,
                        loteUnidad = "Kg", lotePesoPorUnidad = null
                    )
                )
                kgRestantes -= aTomarDeEsteLote
                kgAcumulados += aTomarDeEsteLote
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    private fun desglosarLotesParaCantidadUnidades(unidadesNecesarias: Int, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var unidadesRestantes = unidadesNecesarias
        for (lote in lotesDisponibles) {
            if (unidadesRestantes <= 0) break
            val pesoPorUnidad = lote.pesoPorUnidad ?: 1.0
            if (pesoPorUnidad > 0 && !lote.unidadDeEmpaque.isNullOrBlank()) {
                val unidadesDisponiblesEnLote = Math.floor(lote.currentQuantity / pesoPorUnidad).toInt()
                val unidadesA_TomarDeEsteLote = min(unidadesDisponiblesEnLote, unidadesRestantes)
                if (unidadesA_TomarDeEsteLote > 0) {
                    val kgA_TomarDeEsteLote = unidadesA_TomarDeEsteLote * pesoPorUnidad
                    lotesDesglosados.add(
                        LoteDesglosado(
                            loteId = lote.id, cantidadATomarKg = kgA_TomarDeEsteLote, cantidadATomarUnidades = unidadesA_TomarDeEsteLote.toDouble(),
                            lote = lote, loteFecha = lote.receivedAt, loteProveedor = lote.supplierName,
                            loteUnidad = lote.unidadDeEmpaque, lotePesoPorUnidad = lote.pesoPorUnidad
                        )
                    )
                    kgAcumulados += kgA_TomarDeEsteLote
                    unidadesRestantes -= unidadesA_TomarDeEsteLote
                }
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    private fun convertirKgAUnidades(kg: Double, lotesDisponibles: List<StockLot>): Pair<Int, String> {
        val primerLoteConUnidad = lotesDisponibles.firstOrNull { it.pesoPorUnidad != null && it.pesoPorUnidad > 0 && !it.unidadDeEmpaque.isNullOrBlank() }
        val unidad = primerLoteConUnidad?.unidadDeEmpaque ?: "Kg"
        val pesoPorUnidad = primerLoteConUnidad?.pesoPorUnidad ?: 1.0
        val cantidadEnUnidades = if (kg > 0 && pesoPorUnidad > 0) ceil(kg / pesoPorUnidad).toInt() else 0
        return Pair(cantidadEnUnidades, unidad)
    }

    fun agregarFilaVacia(cantidadFilas: Int) {
        val filaVacia = TraspasoSugerenciaItem(
            product = Product(id = "FILA_VACIA", name = "Fila vacía"),
            sugerenciaKg = 0.0,
            lotesParaTraspaso = emptyList(),
            impactoStockMatriz = 0.0,
            incluidoEnPdf = true,
            cantidadEditadaUnidades = cantidadFilas,
            unidadDeEmpaqueEditada = ""
        )
        _uiState.update { state ->
            val nuevas = state.sugerencias.toMutableList()
            val index = nuevas.indexOfFirst { it.product.id == "FILA_VACIA" }
            if (index != -1) {
                val cantidadActual = nuevas[index].cantidadEditadaUnidades
                nuevas[index] = filaVacia.copy(cantidadEditadaUnidades = cantidadActual + cantidadFilas)
            } else {
                nuevas.add(filaVacia)
            }
            state.copy(sugerencias = nuevas, snackbarMessage = "Filas vacías actualizadas")
        }
    }

    fun eliminarFilaVacia() {
        _uiState.update { state ->
            state.copy(
                sugerencias = state.sugerencias.filter { it.product.id != "FILA_VACIA" },
                snackbarMessage = "Fila vacía eliminada"
            )
        }
    }

    fun actualizarCantidadFilaVacia(nuevaCantidad: Int) {
        _uiState.update { state ->
            state.copy(sugerencias = state.sugerencias.map {
                if (it.product.id == "FILA_VACIA") it.copy(cantidadEditadaUnidades = nuevaCantidad)
                else it
            })
        }
    }
}