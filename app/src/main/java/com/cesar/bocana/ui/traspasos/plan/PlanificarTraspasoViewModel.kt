package com.cesar.bocana.ui.traspasos.plan

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.LoteDesglosado
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Calendar
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

        // Usa la zona horaria por defecto del dispositivo para la comparación
        val zonaHoraria = TimeZone.getDefault()

        val ahora = Calendar.getInstance(zonaHoraria)
        val guardado = Calendar.getInstance(zonaHoraria).apply { timeInMillis = timestamp }

        // Es válido si es del mismo día y del mismo año.
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
    val sugerencias: List<TraspasoSugerenciaItem> = emptyList(),
    val error: String? = null,
    val snackbarMessage: String? = null,
    val preguntaCache: Boolean = false // Flag para que el Fragment muestre el diálogo
)

class PlanificarTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val _uiState = MutableStateFlow(PlanTraspasoUiState())
    val uiState: StateFlow<PlanTraspasoUiState> = _uiState

    private var allLotesEnMatriz: Map<String, List<StockLot>> = emptyMap()
    private val TAG = "PlanTraspasoViewModel"

    init {
        // Al iniciar, solo verifica si debe preguntar al usuario, no carga nada aún.
        if (TraspasoPlanCache.esValido()) {
            _uiState.update { it.copy(preguntaCache = true, isLoading = false) }
        } else {
            cargarPlanDeTraspaso(descartarCache = true)
        }
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun onDialogoMostrado() {
        _uiState.update { it.copy(preguntaCache = false) }
    }

    fun cargarPlanDesdeCache() {
        if (TraspasoPlanCache.esValido()) {
            Log.d(TAG, "Cargando plan desde caché.")
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
            _uiState.value = PlanTraspasoUiState(isLoading = true)
            try {
                val products = db.collection("products")
                    .whereEqualTo("isActive", true)
                    .orderBy("ordenTraspaso")
                    .orderBy("name")
                    .get().await().toObjects(Product::class.java)

                cargarLotesEnMatriz()

                val sugerencias = products.map { product ->
                    generarSugerenciaInicial(product, allLotesEnMatriz[product.id] ?: emptyList())
                }

                _uiState.value = PlanTraspasoUiState(isLoading = false, sugerencias = sugerencias)
                guardarEnCache(sugerencias)

            } catch (e: Exception) {
                Log.e(TAG, "Error en cargarPlanDeTraspaso", e)
                _uiState.value = PlanTraspasoUiState(isLoading = false, error = e.localizedMessage)
            }
        }
    }

    private fun guardarEnCache(sugerencias: List<TraspasoSugerenciaItem>) {
        TraspasoPlanCache.planGuardado = sugerencias
        TraspasoPlanCache.timestamp = System.currentTimeMillis()
        Log.d(TAG, "Plan guardado en caché.")
    }

    private suspend fun cargarLotesEnMatriz() {
        val lotesSnapshot = db.collection("inventoryLots")
            .whereEqualTo("location", "MATRIZ")
            .whereEqualTo("isDepleted", false)
            .whereEqualTo("isPackaged", true)
            .orderBy("receivedAt")
            .get().await()
        allLotesEnMatriz = lotesSnapshot.toObjects(StockLot::class.java).groupBy { it.productId }
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

    private fun generarSugerenciaInicial(product: Product, lotesDelProducto: List<StockLot>): TraspasoSugerenciaItem {
        val necesidadKg = product.stockIdealC04 - product.stockCongelador04
        val disponibleKg = lotesDelProducto.sumOf { it.currentQuantity }
        val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))
        val tieneUnidadesDeEmpaque = lotesDelProducto.any { !it.unidadDeEmpaque.isNullOrBlank() && it.pesoPorUnidad != null && it.pesoPorUnidad > 0 }

        if (tieneUnidadesDeEmpaque && sugerenciaKg > 0) {
            val (cantidadEnUnidades, unidad) = convertirKgAUnidades(sugerenciaKg, lotesDelProducto)
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDelProducto)
            return TraspasoSugerenciaItem(product, kgTomados, lotesDesglosados, product.stockMatriz - kgTomados, cantidadEditadaUnidades = cantidadEnUnidades, unidadDeEmpaqueEditada = unidad)
        } else {
            return TraspasoSugerenciaItem(product, 0.0, emptyList(), product.stockMatriz, cantidadEditadaUnidades = 0, unidadDeEmpaqueEditada = product.unit)
        }
    }

    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        setRecalculatingState(productId, true)
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDisponibles)
        val nuevaUnidad = lotesDesglosados.firstOrNull()?.lote?.unidadDeEmpaque ?: _uiState.value.sugerencias.find { it.product.id == productId }?.unidadDeEmpaqueEditada ?: ""

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
                LoteDesglosado(loteOriginal, kgATomar, unidadesATomar.toDouble())
            } else null
        }
        val nuevaUnidad = lotesDesglosados.firstOrNull()?.lote?.unidadDeEmpaque ?: _uiState.value.sugerencias.find { it.product.id == productId }?.unidadDeEmpaqueEditada ?: ""

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) it.copy(lotesSeleccionadosManualmente = lotesDesglosados.map { d -> d.lote }, lotesParaTraspaso = lotesDesglosados, sugerenciaKg = totalKgDesglosado, impactoStockMatriz = it.product.stockMatriz - totalKgDesglosado, cantidadEditadaUnidades = totalUnidadesDesglosadas, unidadDeEmpaqueEditada = nuevaUnidad, isRecalculating = false) else it
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
                lotesDesglosados.add(LoteDesglosado(lote, kgA_TomarDeEsteLote, unidadesA_TomarDeEsteLote.toDouble()))
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
