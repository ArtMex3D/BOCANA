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

// Objeto Singleton para mantener el plan en memoria mientras la app vive.
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

                val sugerencias = products.map { product ->
                    generarSugerenciaInicial(product, allLotesEnMatriz[product.id] ?: emptyList())
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
        val planParaGuardar = _uiState.value.sugerencias.filter { it.incluidoEnPdf && it.sugerenciaKg > 0 }
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

                planParaGuardar.forEach { item ->
                    val detalleDocRef = planDocRef.collection("detalles").document()
                    val detalle = DetalleTraspasoPlan(
                        id = detalleDocRef.id,
                        productId = item.product.id,
                        productName = item.product.name,
                        sugerenciaKg = item.sugerenciaKg,
                        sugerenciaUnidades = item.cantidadEditadaUnidades,
                        unidadDeEmpaque = item.unidadDeEmpaqueEditada,
                        lotesSugeridos = item.lotesParaTraspaso
                    )
                    batch.set(detalleDocRef, detalle)

                    // ***** INICIO DE SOLUCIÓN "CANDADO" *****
                    // Marcar cada lote como "RESERVADO"
                    item.lotesParaTraspaso.forEach { desglose ->
                        val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                        batch.update(loteRef, "estadoTraspaso", "RESERVADO")
                    }
                    // ***** FIN DE SOLUCIÓN "CANDADO" *****
                }

                batch.commit().await()
                TraspasoPlanCache.limpiar() // Limpiar caché después de guardar exitosamente
                _uiState.update { it.copy(isSaving = false, snackbarMessage = "Plan de traspaso creado.", planGuardadoExitoso = true) }
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

    private fun generarSugerenciaInicial(product: Product, lotesDelProducto: List<StockLot>): TraspasoSugerenciaItem {
        val necesidadKg = product.stockIdealC04 - product.stockCongelador04
        val disponibleKg = lotesDelProducto.sumOf { it.currentQuantity }
        val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))
        val tieneUnidadesDeEmpaque = lotesDelProducto.any { !it.unidadDeEmpaque.isNullOrBlank() && it.pesoPorUnidad != null && it.pesoPorUnidad > 0 }

        val incluido = sugerenciaKg > 0.0

        if (tieneUnidadesDeEmpaque && sugerenciaKg > 0) {
            val (cantidadEnUnidades, unidad) = convertirKgAUnidades(sugerenciaKg, lotesDelProducto)
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDelProducto)
            return TraspasoSugerenciaItem(product, kgTomados, lotesDesglosados, product.stockMatriz - kgTomados, incluidoEnPdf = incluido, cantidadEditadaUnidades = cantidadEnUnidades, unidadDeEmpaqueEditada = unidad)
        } else {
            return TraspasoSugerenciaItem(product, 0.0, emptyList(), product.stockMatriz, incluidoEnPdf = incluido, cantidadEditadaUnidades = 0, unidadDeEmpaqueEditada = product.unit)
        }
    }

    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        setRecalculatingState(productId, true)
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDisponibles)
        val nuevaUnidad = lotesDesglosados.firstOrNull()?.loteUnidad ?: _uiState.value.sugerencias.find { it.product.id == productId }?.unidadDeEmpaqueEditada ?: ""

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(sugerenciaKg = kgRealesTomados, lotesParaTraspaso = lotesDesglosados, impactoStockMatriz = it.product.stockMatriz - kgRealesTomados, cantidadEditadaUnidades = cantidadEnUnidades, unidadDeEmpaqueEditada = nuevaUnidad, isRecalculating = false, lotesSeleccionadosManualmente = null) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(sugerencias = nuevasSugerencias)
        }
    }

    fun actualizarLotesManualmente(productId: String, lotesSeleccionados: List<StockLot>) {
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        setRecalculatingState(productId, true)
        val unidadesNecesarias = sugerenciaAfectada.cantidadEditadaUnidades
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(unidadesNecesarias, lotesSeleccionados)
        val unidadesRealesTomadas = lotesDesglosados.sumOf { it.cantidadATomarUnidades ?: 0.0 }.toInt()
        val nuevaUnidad = lotesSeleccionados.firstOrNull()?.unidadDeEmpaque ?: sugerenciaAfectada.unidadDeEmpaqueEditada

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(lotesSeleccionadosManualmente = lotesSeleccionados, lotesParaTraspaso = lotesDesglosados, sugerenciaKg = kgRealesTomados, impactoStockMatriz = it.product.stockMatriz - kgRealesTomados, cantidadEditadaUnidades = unidadesRealesTomadas, unidadDeEmpaqueEditada = nuevaUnidad, isRecalculating = false) else it
            }
            guardarEnCache(nuevasSugerencias)
            state.copy(sugerencias = nuevasSugerencias, snackbarMessage = if (unidadesNecesarias > unidadesRealesTomadas) "Cantidad ajustada al stock de los lotes seleccionados." else null)
        }
    }

    fun actualizarPorDesgloseManual(productId: String, desglose: List<DesgloseManualResult>) {
        setRecalculatingState(productId, true)
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        var totalKgDesglosado = 0.0
        var totalUnidadesDesglosadas = 0
        val lotesDesglosados = desglose.mapNotNull { desgloseItem ->
            val loteOriginal = lotesDisponibles.find { it.id == desgloseItem.loteId }
            if (loteOriginal != null) {
                val pesoUnidad = loteOriginal.pesoPorUnidad ?: 1.0
                val unidadesATomar = desgloseItem.cantidad.toInt()
                val kgATomar = unidadesATomar * pesoUnidad
                totalKgDesglosado += kgATomar
                totalUnidadesDesglosadas += unidadesATomar
                LoteDesglosado(
                    loteId = loteOriginal.id,
                    cantidadATomarKg = kgATomar,
                    cantidadATomarUnidades = unidadesATomar.toDouble(),
                    lote = loteOriginal, // Mantener el objeto completo para la UI
                    loteFecha = loteOriginal.receivedAt,
                    loteProveedor = loteOriginal.supplierName,
                    loteUnidad = loteOriginal.unidadDeEmpaque,
                    lotePesoPorUnidad = loteOriginal.pesoPorUnidad
                )
            } else null
        }
        val nuevaUnidad = lotesDesglosados.firstOrNull()?.loteUnidad ?: _uiState.value.sugerencias.find { it.product.id == productId }?.unidadDeEmpaqueEditada ?: ""

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(lotesSeleccionadosManualmente = lotesDesglosados.mapNotNull { d -> d.lote }, lotesParaTraspaso = lotesDesglosados, sugerenciaKg = totalKgDesglosado, impactoStockMatriz = it.product.stockMatriz - totalKgDesglosado, cantidadEditadaUnidades = totalUnidadesDesglosadas, unidadDeEmpaqueEditada = nuevaUnidad, isRecalculating = false) else it
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

    private fun desglosarLotesParaCantidadUnidades(unidadesNecesarias: Int, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var unidadesRestantes = unidadesNecesarias
        for (lote in lotesDisponibles) {
            if (unidadesRestantes <= 0) break
            val pesoPorUnidad = lote.pesoPorUnidad ?: 1.0
            if (pesoPorUnidad <= 0) continue
            val unidadesDisponiblesEnLote = Math.floor(lote.currentQuantity / pesoPorUnidad).toInt()
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
        return Pair(lotesDesglosados, kgAcumulados)
    }

    private fun convertirKgAUnidades(kg: Double, lotesDisponibles: List<StockLot>): Pair<Int, String> {
        val primerLoteConUnidad = lotesDisponibles.firstOrNull { it.pesoPorUnidad != null && it.pesoPorUnidad > 0 && !it.unidadDeEmpaque.isNullOrBlank() }
        val unidad = primerLoteConUnidad?.unidadDeEmpaque ?: "Kg"
        val pesoPorUnidad = primerLoteConUnidad?.pesoPorUnidad ?: 1.0
        val cantidadEnUnidades = if (kg > 0 && pesoPorUnidad > 0) ceil(kg / pesoPorUnidad).toInt() else 0
        return Pair(cantidadEnUnidades, unidad)
    }
}

