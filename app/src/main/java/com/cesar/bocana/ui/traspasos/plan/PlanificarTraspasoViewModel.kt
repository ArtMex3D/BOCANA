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
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

data class PlanTraspasoUiState(
    val isLoading: Boolean = true,
    val sugerencias: List<TraspasoSugerenciaItem> = emptyList(),
    val error: String? = null,
    val snackbarMessage: String? = null
)

class PlanificarTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val _uiState = MutableStateFlow(PlanTraspasoUiState())
    val uiState: StateFlow<PlanTraspasoUiState> = _uiState

    // <-- CAMBIO: Mantenemos un mapa de todos los lotes para no tener que consultarlos de nuevo
    private var allLotesEnMatriz: Map<String, List<StockLot>> = emptyMap()
    private val TAG = "PlanTraspasoViewModel"

    init {
        cargarPlanDeTraspaso()
    }

    fun onSnackbarShown() {
        _uiState.update { it.copy(snackbarMessage = null) }
    }

    fun cargarPlanDeTraspaso() {
        viewModelScope.launch {
            _uiState.value = PlanTraspasoUiState(isLoading = true)
            try {
                // <-- SIN CAMBIOS: La carga inicial de datos es correcta
                val products = db.collection("products")
                    .whereEqualTo("isActive", true)
                    .orderBy("ordenTraspaso")
                    .orderBy("name")
                    .get().await().toObjects(Product::class.java)

                val lotesSnapshot = db.collection("inventoryLots")
                    .whereEqualTo("location", "MATRIZ")
                    .whereEqualTo("isDepleted", false)
                    .orderBy("receivedAt")
                    .get().await()

                allLotesEnMatriz = lotesSnapshot.toObjects(StockLot::class.java).groupBy { it.productId }

                val sugerencias = products.map { product ->
                    generarSugerenciaInicial(product, allLotesEnMatriz[product.id] ?: emptyList())
                }
                Log.d(TAG, "Carga inicial completa. ${sugerencias.size} sugerencias generadas.")
                _uiState.value = PlanTraspasoUiState(isLoading = false, sugerencias = sugerencias)

            } catch (e: Exception) {
                Log.e(TAG, "Error en cargarPlanDeTraspaso", e)
                _uiState.value = PlanTraspasoUiState(isLoading = false, error = e.localizedMessage)
            }
        }
    }

    // <-- FUNCIÓN NUEVA: Se activa cuando editas la cantidad en la pantalla principal
    fun recalcularSugerenciaPorUnidades(productId: String, cantidadEnUnidades: Int) {
        setRecalculatingState(productId, true)
        Log.d(TAG, "Recalculando para $productId con $cantidadEnUnidades unidades.")

        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        val totalUnidadesDisponibles = lotesDisponibles.sumOf {
            val pesoUnidad = it.pesoPorUnidad ?: 1.0
            if (pesoUnidad > 0) Math.floor(it.currentQuantity / pesoUnidad) else 0.0
        }.toInt()

        val unidadesRealesATomar = min(cantidadEnUnidades, totalUnidadesDisponibles)
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(unidadesRealesATomar, lotesDisponibles)

        Log.d(TAG, "Recálculo para $productId: Unidades a tomar: $unidadesRealesATomar, Kg resultantes: $kgRealesTomados")

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) {
                    it.copy(
                        sugerenciaKg = kgRealesTomados,
                        lotesParaTraspaso = lotesDesglosados,
                        impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                        cantidadEditadaUnidades = unidadesRealesATomar,
                        isRecalculating = false,
                        lotesSeleccionadosManualmente = null // <-- Importante: Se anula la selección manual al cambiar la cantidad
                    )
                } else { it }
            }
            val snackbarMsg = if (cantidadEnUnidades > unidadesRealesATomar) "Cantidad ajustada al stock máximo disponible." else null
            state.copy(sugerencias = nuevasSugerencias, snackbarMessage = snackbarMsg)
        }
    }

    // <-- FUNCIÓN NUEVA: Se activa cuando seleccionas lotes con checkbox en el diálogo
    fun actualizarLotesManualmente(productId: String, lotesSeleccionados: List<StockLot>) {
        setRecalculatingState(productId, true)
        val sugerenciaAfectada = _uiState.value.sugerencias.find { it.product.id == productId } ?: return
        val unidadesNecesarias = sugerenciaAfectada.cantidadEditadaUnidades

        Log.d(TAG, "Actualizando lotes manualmente para $productId. Lotes seleccionados: ${lotesSeleccionados.map { it.id }}. Unidades necesarias: $unidadesNecesarias")

        // <-- Lógica clave: Se aplica PEPS solo sobre los lotes que el usuario eligió
        val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidadUnidades(unidadesNecesarias, lotesSeleccionados)

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) {
                    it.copy(
                        lotesSeleccionadosManualmente = lotesSeleccionados, // Guardamos la selección
                        lotesParaTraspaso = lotesDesglosados,
                        sugerenciaKg = kgRealesTomados,
                        impactoStockMatriz = it.product.stockMatriz - kgRealesTomados,
                        isRecalculating = false
                    )
                } else { it }
            }
            state.copy(sugerencias = nuevasSugerencias)
        }
    }

    // <-- FUNCIÓN NUEVA: Se activa cuando usas el modo manual en el diálogo
    fun actualizarPorDesgloseManual(productId: String, desgloseManual: List<DesgloseManualResult>) {
        setRecalculatingState(productId, true)
        val lotesDisponibles = allLotesEnMatriz[productId] ?: emptyList()
        Log.d(TAG, "Actualizando por desglose manual para $productId. Datos: $desgloseManual")

        var totalKgDesglosado = 0.0
        var totalUnidadesDesglosadas = 0

        val lotesDesglosados = desgloseManual.mapNotNull { itemDesglose ->
            val loteOriginal = lotesDisponibles.find { it.id == itemDesglose.loteId }
            if (loteOriginal != null) {
                val pesoUnidad = loteOriginal.pesoPorUnidad ?: 1.0
                // <-- CORRECCIÓN: La cantidad ya viene validada como unidades enteras desde el diálogo
                val unidadesATomar = itemDesglose.cantidad.toInt()
                val kgATomar = unidadesATomar * pesoUnidad
                totalKgDesglosado += kgATomar
                totalUnidadesDesglosadas += unidadesATomar
                LoteDesglosado(loteOriginal, kgATomar, unidadesATomar.toDouble())
            } else { null }
        }

        _uiState.update { state ->
            val nuevasSugerencias = state.sugerencias.map {
                if (it.product.id == productId) {
                    it.copy(
                        lotesSeleccionadosManualmente = lotesDesglosados.map { it.lote },
                        lotesParaTraspaso = lotesDesglosados,
                        sugerenciaKg = totalKgDesglosado,
                        impactoStockMatriz = it.product.stockMatriz - totalKgDesglosado,
                        cantidadEditadaUnidades = totalUnidadesDesglosadas, // <-- Actualiza la cantidad total
                        isRecalculating = false
                    )
                } else it
            }
            state.copy(sugerencias = nuevasSugerencias, snackbarMessage = "Plan actualizado con desglose manual.")
        }
    }

    // <-- Lógica Interna de Cálculo (Refactorizada y más robusta) -->

    private fun generarSugerenciaInicial(product: Product, lotesDelProducto: List<StockLot>): TraspasoSugerenciaItem {
        val necesidadKg = product.stockIdealC04 - product.stockCongelador04
        val disponibleKg = product.stockMatriz
        val sugerenciaKg = max(0.0, min(necesidadKg, disponibleKg))

        val tieneUnidadesDeEmpaque = lotesDelProducto.any { !it.unidadDeEmpaque.isNullOrBlank() && it.pesoPorUnidad != null && it.pesoPorUnidad > 0 }

        if (tieneUnidadesDeEmpaque) {
            val (cantidadEnUnidades, unidad) = convertirKgAUnidades(sugerenciaKg, lotesDelProducto)
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadUnidades(cantidadEnUnidades, lotesDelProducto)
            return TraspasoSugerenciaItem(
                product = product,
                sugerenciaKg = kgTomados,
                lotesParaTraspaso = lotesDesglosados,
                impactoStockMatriz = product.stockMatriz - kgTomados,
                cantidadEditadaUnidades = cantidadEnUnidades,
                unidadDeEmpaqueEditada = unidad
            )
        } else { // Caso a granel (Kg)
            val (lotesDesglosados, kgTomados) = desglosarLotesParaCantidadKg(sugerenciaKg, lotesDelProducto)
            return TraspasoSugerenciaItem(
                product = product,
                sugerenciaKg = kgTomados,
                lotesParaTraspaso = lotesDesglosados,
                impactoStockMatriz = product.stockMatriz - kgTomados,
                cantidadEditadaUnidades = kgTomados.toInt(),
                unidadDeEmpaqueEditada = product.unit
            )
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
            // <-- CORRECCIÓN LÓGICA: Se usa floor para no prometer unidades que no existen completas
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

    private fun desglosarLotesParaCantidadKg(kgNecesarios: Double, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = kgNecesarios
        for (lote in lotesDisponibles) {
            if (kgRestantes <= 0.001) break
            val kgA_TomarDeEsteLote = min(lote.currentQuantity, kgRestantes)
            lotesDesglosados.add(LoteDesglosado(lote, kgA_TomarDeEsteLote, null))
            kgAcumulados += kgA_TomarDeEsteLote
            kgRestantes -= kgA_TomarDeEsteLote
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    private fun convertirKgAUnidades(kg: Double, lotesDisponibles: List<StockLot>): Pair<Int, String> {
        val primerLoteConUnidad = lotesDisponibles.firstOrNull { it.pesoPorUnidad != null && it.pesoPorUnidad > 0 && !it.unidadDeEmpaque.isNullOrBlank() }
        val unidad = primerLoteConUnidad?.unidadDeEmpaque ?: "Kg"
        val pesoPorUnidad = primerLoteConUnidad?.pesoPorUnidad ?: 1.0
        // <-- CORRECCIÓN LÓGICA: Se usa ceil para asegurar que se pidan suficientes unidades para cubrir los Kg
        val cantidadEnUnidades = if (kg > 0 && pesoPorUnidad > 0) ceil(kg / pesoPorUnidad).toInt() else 0
        return Pair(cantidadEnUnidades, unidad)
    }

    private fun setRecalculatingState(productId: String, isRecalculating: Boolean) {
        _uiState.update { currentState ->
            val updatedSugerencias = currentState.sugerencias.map {
                if (it.product.id == productId) it.copy(isRecalculating = isRecalculating) else it
            }
            currentState.copy(sugerencias = updatedSugerencias)
        }
    }
}
