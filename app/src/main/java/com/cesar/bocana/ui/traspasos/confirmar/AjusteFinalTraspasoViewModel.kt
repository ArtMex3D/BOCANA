package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.*
import com.cesar.bocana.ui.traspasos.plan.DesgloseManualResult
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FieldPath // Importación necesaria
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

sealed class NavigationEvent {
    object GoBack : NavigationEvent()
}



@Parcelize
data class AjusteFinalItem(
    val detalleOriginal: @RawValue DetalleTraspasoPlan,
    val product: @RawValue Product, // <-- Añadido el objeto Product
    var cantidadConfirmadaKg: Double,
    var lotesConfirmados: @RawValue List<LoteDesglosado>,
    // Flag para saber si el usuario hizo una selección manual (checkbox o desglose)
    var lotesSeleccionadosManualmente: Boolean = false,
    var cantidadEditadaUnidades: Int = 0 // Almacena las unidades (para fijos)
) : Parcelable

data class AjusteFinalUiState(
    val isLoading: Boolean = true,
    val isExecuting: Boolean = false,
    val plan: TraspasoPlanificado? = null,
    val itemsParaAjustar: List<AjusteFinalItem> = emptyList(),
    val userMessage: UiMessage? = null,
    val navigationEvent: NavigationEvent? = null
)

class AjusteFinalTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val _uiState = MutableStateFlow(AjusteFinalUiState())
    val uiState: StateFlow<AjusteFinalUiState> = _uiState.asStateFlow()
    private val stockEpsilon = 0.01
    private val TAG = "AjusteFinalVM" // Tag para logs

    fun loadPlanDetails(planId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val planSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(planId).get().await()
                val plan = planSnapshot.toObject(TraspasoPlanificado::class.java)?.copy(id = planSnapshot.id) // Asegurar ID

                if (plan == null || plan.estado != TraspasoEstado.PENDIENTE) { // Verificar estado PENDIENTE aquí
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error: El plan no se encontró o ya no está pendiente."), navigationEvent = NavigationEvent.GoBack) }
                    return@launch
                }

                val detallesSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(planId).collection("detalles").get().await()
                // Filtrar detalles vacíos o inválidos y asegurar ID
                val detalles = detallesSnapshot.documents.mapNotNull { doc ->
                    doc.toObject(DetalleTraspasoPlan::class.java)?.copy(id = doc.id)
                }.filter { it.productId.isNotBlank() }


                val items = detalles.map { detalle ->
                    async(Dispatchers.IO) { // Ejecutar en paralelo para eficiencia
                        // Cargar Producto
                        val productDoc = db.collection(FirestoreCollections.PRODUCTS).document(detalle.productId).get().await()
                        val product = productDoc.toObject(Product::class.java)?.copy(id = productDoc.id)
                        if (product == null) {
                            Log.e(TAG, "Error: Producto ${detalle.productId} no encontrado para detalle ${detalle.id}")
                            return@async null // Omitir este item si el producto no existe
                        }

                        // Cargar Lotes
                        val lotesCompletosDesglosados = detalle.lotesSugeridos.mapNotNull { desglose ->
                            try {
                                if (desglose.loteId.isBlank()) {
                                    Log.w(TAG, "Lote ID vacío encontrado en detalle ${detalle.id} para producto ${detalle.productName}")
                                    return@mapNotNull null // Ignorar si el ID está vacío
                                }
                                val loteDoc = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId).get().await()
                                loteDoc.toObject(StockLot::class.java)?.let { loteCompleto ->
                                    desglose.copy(lote = loteCompleto.copy(id = loteDoc.id)) // Asegurar ID del lote también
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error cargando lote ${desglose.loteId} para detalle ${detalle.id}", e)
                                null // Ignorar lote si falla la carga
                            }
                        }
                        AjusteFinalItem(
                            detalleOriginal = detalle,
                            product = product, // <-- Añadir el producto cargado
                            cantidadConfirmadaKg = detalle.sugerenciaKg,
                            lotesConfirmados = lotesCompletosDesglosados,
                            lotesSeleccionadosManualmente = false, // Inicialmente no es manual
                            cantidadEditadaUnidades = detalle.sugerenciaUnidades // <-- Añadido
                        )
                    }
                }.mapNotNull { it.await() } // Esperar y filtrar nulos (si un producto falló)

                _uiState.update { it.copy(isLoading = false, plan = plan, itemsParaAjustar = items) }
            } catch (e: Exception) {
                Log.e(TAG, "Error crítico al cargar detalles del plan $planId", e)
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al cargar detalles: ${e.message}")) }
            }
        }
    }

    fun updateConfirmedQuantity(detalleId: String, newQuantityKg: Double) {
        viewModelScope.launch {
            val currentItems = _uiState.value.itemsParaAjustar
            val itemIndex = currentItems.indexOfFirst { it.detalleOriginal.id == detalleId }
            if (itemIndex == -1) return@launch
            val itemToUpdate = currentItems[itemIndex]

            if (itemToUpdate.lotesSeleccionadosManualmente) {
                // Si hay selección manual, SOLO actualizamos la cantidad confirmada mostrada.
                Log.d(TAG, "updateConfirmedQuantity (Manual): Actualizando solo cantidad mostrada a ${String.format("%.2f", newQuantityKg)}")
                val updatedItems = currentItems.toMutableList()

                val esFijo = !itemToUpdate.product.requiresPackaging
                val unidadesCalculadas = if (esFijo) {
                    val pesoRef = itemToUpdate.product.labelConfig?.get("weightPerUnit") as? Double ?: 1.0
                    ceil(newQuantityKg / pesoRef).toInt()
                } else {
                    itemToUpdate.cantidadEditadaUnidades // Mantener 0
                }

                updatedItems[itemIndex] = itemToUpdate.copy(
                    cantidadConfirmadaKg = newQuantityKg,
                    cantidadEditadaUnidades = unidadesCalculadas // Guardar unidades
                )
                _uiState.update { it.copy(itemsParaAjustar = updatedItems) }

            } else {
                // Si NO hay selección manual, recalculamos FIFO.
                Log.d(TAG, "updateConfirmedQuantity (FIFO): Recalculando desglose FIFO para ${String.format("%.2f", newQuantityKg)} Kg")
                // Obtenemos los lotes disponibles (libres + reservados por este plan)
                val lotesParaDesglosar = fetchLotesDisponibles(itemToUpdate.detalleOriginal.productId, _uiState.value.plan?.id)

                // **INICIO CORRECCIÓN**: Desglosar por Unidades o KG
                val esFijo = !itemToUpdate.product.requiresPackaging
                val (nuevosLotesDesglosados, cantidadRealTomadaKg, unidadesRealesTomadas) = if (esFijo) {
                    val pesoRef = itemToUpdate.product.labelConfig?.get("weightPerUnit") as? Double ?: 1.0
                    val unidadesNecesarias = ceil(newQuantityKg / pesoRef).toInt()
                    val (desglose, kgReales) = desglosarLotesParaCantidadUnidades(unidadesNecesarias, lotesParaDesglosar)
                    Triple(desglose, kgReales, unidadesNecesarias)
                } else {
                    val (desglose, kgReales) = desglosarLotesParaCantidad(newQuantityKg, lotesParaDesglosar)
                    Triple(desglose, kgReales, 0)
                }
                // **FIN CORRECCIÓN**

                val updatedItems = currentItems.toMutableList()
                updatedItems[itemIndex] = itemToUpdate.copy(
                    cantidadConfirmadaKg = cantidadRealTomadaKg, // Actualizar con la cantidad real obtenida
                    lotesConfirmados = nuevosLotesDesglosados,
                    lotesSeleccionadosManualmente = false, // Asegurar que sigue siendo FIFO
                    cantidadEditadaUnidades = unidadesRealesTomadas // Guardar unidades
                )
                _uiState.update { it.copy(itemsParaAjustar = updatedItems) }
            }
        }
    }

    fun actualizarLotesManualmentePorIds(productId: String, loteIdsSeleccionados: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val planId = _uiState.value.plan?.id
                // 1. Cargar los lotes completos que el usuario seleccionó (y que están disponibles)
                val lotesCompletos = fetchLotesDisponiblesConIds(productId, planId, loteIdsSeleccionados)

                if (lotesCompletos.isNotEmpty() || loteIdsSeleccionados.isEmpty()) {
                    // 2. Encontrar el item que estamos editando en nuestra lista de UI
                    val currentItems = _uiState.value.itemsParaAjustar
                    val itemIndex = currentItems.indexOfFirst { it.detalleOriginal.productId == productId }
                    if (itemIndex == -1) {
                        Log.w(TAG, "actualizarLotesManualmentePorIds: No se encontró el item para $productId")
                        _uiState.update { it.copy(isLoading = false) }
                        return@launch
                    }
                    val itemToUpdate = currentItems[itemIndex]
                    val esFijo = !itemToUpdate.product.requiresPackaging

                    // 3. Tomar la cantidad de KG que el usuario ya tenía en la pantalla
                    val cantidadKgNecesarios = itemToUpdate.cantidadConfirmadaKg

                    // 4. Recalcular el desglose (FIFO) USANDO SOLO LOS LOTES SELECCIONADOS
                    val (lotesDesglosados, kgRealesTomados) = desglosarLotesParaCantidad(cantidadKgNecesarios, lotesCompletos)

                    // 5. (Opcional) Recalcular unidades si es un producto fijo
                    val unidadesRealesTomadas = if (esFijo) {
                        val pesoUnidadRef = lotesCompletos.firstNotNullOfOrNull { it.pesoPorUnidad?.takeIf { p -> p > 0 } } ?: 1.0
                        ceil(kgRealesTomados / pesoUnidadRef).toInt()
                    } else {
                        0 // No aplica para granel
                    }

                    val nuevaUnidad = lotesCompletos.firstNotNullOfOrNull { it.unidadDeEmpaque?.takeIf { u -> u.isNotBlank() } }
                        ?: itemToUpdate.product.unit ?: "Kg"

                    // 6. Preparar mensaje de feedback
                    val msg = if (cantidadKgNecesarios > kgRealesTomados + stockEpsilon) {
                        "Cantidad necesaria (${String.format("%.2f", cantidadKgNecesarios)} Kg) excede stock. Ajustado a ${String.format("%.2f", kgRealesTomados)} Kg."
                    } else {
                        "Selección de lotes manual aplicada."
                    }

                    // 7. Actualizar el estado de la UI
                    _uiState.update { state ->
                        val updatedItems = state.itemsParaAjustar.toMutableList()
                        updatedItems[itemIndex] = itemToUpdate.copy(
                            lotesSeleccionadosManualmente = true, // ¡Marcar como manual!
                            lotesConfirmados = lotesDesglosados.sortedBy { d -> d.loteFecha ?: Date(0) },
                            cantidadConfirmadaKg = kgRealesTomados // Actualizar a los KG reales tomados
                        )
                        state.copy(isLoading = false, itemsParaAjustar = updatedItems, userMessage = UiMessage(message = msg))
                    }

                } else if (loteIdsSeleccionados.isNotEmpty()){
                    Log.w(TAG, "actualizarLotesManualmentePorIds (Ajuste): No se encontraron lotes para IDs $loteIdsSeleccionados")
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Algunos lotes seleccionados ya no están disponibles o pertenecen a otro plan.")) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar lotes por IDs para $productId (Ajuste)", e)
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al procesar selección: ${e.message}")) }
            }
        }
    }

    // --- FUNCIÓN PRIVADA PARA ACTUALIZAR ESTADO (CHECKBOX) ---
    // Esta función contenía el error.
    private fun actualizarEstadoConLotesManuales(
        productId: String,
        lotesSeleccionadosCompletos: List<StockLot> // Lotes seleccionados por checkbox
    ) {
        val currentItems = _uiState.value.itemsParaAjustar
        val itemIndex = currentItems.indexOfFirst { it.detalleOriginal.productId == productId }
        if (itemIndex == -1) {
            _uiState.update { it.copy(isLoading = false) } // Asegurar quitar isLoading
            return
        }
        val itemToUpdate = currentItems[itemIndex]

        // **INICIO DE LA CORRECCIÓN**
        val esFijo = !itemToUpdate.product.requiresPackaging // Determinar si es Fijo
        val cantidadConfirmadaKg = itemToUpdate.cantidadConfirmadaKg // KG totales que el usuario quiere
        val cantidadConfirmadaUnidades = itemToUpdate.cantidadEditadaUnidades // Unidades que el usuario quiere

        val (nuevosLotesDesglosados, cantidadRealTomadaKg) = if (esFijo) {
            // --- LÓGICA FIJO (UNIDADES) ---
            Log.d(TAG, "actualizarEstadoConLotesManuales (Ajuste) para Fijo: Desglosando para $cantidadConfirmadaUnidades unidades.")
            desglosarLotesParaCantidadUnidades(cantidadConfirmadaUnidades, lotesSeleccionadosCompletos)
        } else {
            // --- LÓGICA GRANEL (KG) ---
            Log.d(TAG, "actualizarEstadoConLotesManuales (Ajuste) para Granel: Desglosando para ${String.format("%.2f", cantidadConfirmadaKg)} Kg.")
            desglosarLotesParaCantidad(cantidadConfirmadaKg, lotesSeleccionadosCompletos)
        }
        // **FIN DE LA CORRECCIÓN**

        Log.d(TAG, "actualizarEstadoConLotesManuales para $productId (Ajuste): KG reales: ${String.format("%.2f", cantidadRealTomadaKg)}")

        val snackbarMessage = if ( (esFijo && cantidadRealTomadaKg < cantidadConfirmadaKg - stockEpsilon) || (!esFijo && kotlin.math.abs(cantidadConfirmadaKg - cantidadRealTomadaKg) > stockEpsilon) ) {
            "Cantidad ajustada (${String.format("%.2f", cantidadRealTomadaKg)} Kg) al stock disponible."
        } else {
            "Selección manual (checkbox) aplicada."
        }

        _uiState.update { state ->
            val updatedItems = state.itemsParaAjustar.toMutableList()
            if (itemIndex >= 0 && itemIndex < updatedItems.size) {
                updatedItems[itemIndex] = itemToUpdate.copy(
                    cantidadConfirmadaKg = cantidadRealTomadaKg, // Actualizar a los KG reales
                    lotesConfirmados = nuevosLotesDesglosados.sortedBy { d -> d.loteFecha ?: Date(0) },
                    lotesSeleccionadosManualmente = true // MARCAR COMO MANUAL
                    // cantidadEditadaUnidades no se toca, ya la usamos
                )
            }
            // Asegurar quitar isLoading
            state.copy(isLoading = false, itemsParaAjustar = updatedItems, userMessage = UiMessage(message = snackbarMessage))
        }
    }
    // --- FIN NUEVA FUNCIÓN PRIVADA (CHECKBOX) ---


    // Modificar actualizarLotesManualmente para llamar a la función privada
    fun actualizarLotesManualmente(productId: String, nuevosLotesSeleccionados: List<StockLot>) {
        viewModelScope.launch { // Usar corutina
            actualizarEstadoConLotesManuales(productId, nuevosLotesSeleccionados)
        }
    }

    // Actualiza directamente con el desglose manual KG/Unidades y marca como manual.
    // --- FUNCIÓN CORREGIDA PARA ACTUALIZAR ESTADO (DESGLOSE MANUAL) ---
    fun actualizarPorDesgloseManual(productId: String, desgloseManualUsuario: List<DesgloseManualResult>) {
        viewModelScope.launch(Dispatchers.IO) { // Puede seguir en IO para cargar lotes
            _uiState.update { it.copy(isLoading = true) } // Indicar carga
            val currentItems = _uiState.value.itemsParaAjustar
            val itemIndex = currentItems.indexOfFirst { it.detalleOriginal.productId == productId }
            if (itemIndex == -1) {
                _uiState.update { it.copy(isLoading = false) } // Quitar carga si no se encuentra
                return@launch
            }
            val itemToUpdate = currentItems[itemIndex]

            try {
                // Obtener lotes disponibles (libres + reservados) para validar
                val lotesDisponibles = fetchLotesDisponibles(productId, _uiState.value.plan?.id)
                val lotesCompletosMap = lotesDisponibles.associateBy { it.id }

                var totalKgDesglosado = 0.0
                var totalUnidadesDesglosadas = 0 // Solo para Fijos
                val lotesDesglosadosFinal = mutableListOf<LoteDesglosado>()
                var unidadFinal = itemToUpdate.product.unit ?: "Kg" // Usar unidad del producto como fallback
                var ajusteRealizadoMsg: String? = null
                var finalMessage = "Desglose manual aplicado."

                // **CORRECCIÓN**: Determinar si es Fijo usando el producto del item
                val esFijo = !itemToUpdate.product.requiresPackaging

                desgloseManualUsuario.forEach { itemUsuario ->
                    val loteOriginal = lotesCompletosMap[itemUsuario.loteId]
                    if (loteOriginal != null) {
                        val cantidadUsuario = itemUsuario.cantidad // Es Double (Unidades o KG)
                        var cantidadRealTomadaKg = 0.0
                        var cantidadRealTomadaUnidades: Double? = null

                        if (esFijo) {
                            // --- LÓGICA FIJO (UNIDADES) ---
                            val unidadesUsuario = cantidadUsuario.toInt()
                            val pesoUnidad = loteOriginal.pesoPorUnidad?.takeIf { it > 0 } ?: 1.0
                            val unidadesDisponibles = floor(loteOriginal.currentQuantity / pesoUnidad).toInt()
                            val unidadesRealesATomar = min(unidadesUsuario, unidadesDisponibles)
                            val kgATomar = unidadesRealesATomar * pesoUnidad
                            unidadFinal = loteOriginal.unidadDeEmpaque?.takeIf { it.isNotBlank() } ?: unidadFinal

                            if (unidadesRealesATomar > 0) {
                                if (unidadesRealesATomar < unidadesUsuario) ajusteRealizadoMsg = "Cantidades ajustadas al stock disponible."
                                cantidadRealTomadaKg = kgATomar
                                cantidadRealTomadaUnidades = unidadesRealesATomar.toDouble()
                                totalUnidadesDesglosadas += unidadesRealesATomar // Sumar unidades
                            }
                        } else {
                            // --- LÓGICA GRANEL (KG) ---
                            val kgUsuario = cantidadUsuario
                            val kgDisponibles = loteOriginal.currentQuantity
                            val kgRealesATomar = min(kgUsuario, kgDisponibles)
                            unidadFinal = "Kg"

                            if (kgRealesATomar > stockEpsilon) {
                                if (kotlin.math.abs(kgUsuario - kgRealesATomar) > stockEpsilon) ajusteRealizadoMsg = "Cantidades ajustadas al stock disponible."
                                cantidadRealTomadaKg = kgRealesATomar
                            }
                        }

                        // Añadir al desglose final si se tomó algo
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
                        Log.w(TAG, "Lote ${itemUsuario.loteId} no encontrado/disponible para desglose manual de ${itemToUpdate.detalleOriginal.productName}")
                        ajusteRealizadoMsg = "Algunos lotes no estaban disponibles."
                    }
                } // Fin forEach

                finalMessage = ajusteRealizadoMsg ?: finalMessage
                Log.d(TAG, "actualizarPorDesgloseManual para $productId: KG totales: ${String.format("%.2f", totalKgDesglosado)}, Unidades: $totalUnidadesDesglosadas")

                // Llamar a la función interna para actualizar el estado de la UI
                actualizarEstadoConDesgloseManual(
                    itemOriginal = itemToUpdate, // Pasar el item original
                    lotesSeleccionadosCompletos = lotesDesglosadosFinal.mapNotNull { it.lote }, // Lotes completos usados
                    lotesDesglosadosCalculados = lotesDesglosadosFinal, // El desglose resultante
                    totalKgCalculado = totalKgDesglosado, // KG sumados
                    totalUnidadesCalculadas = totalUnidadesDesglosadas, // Unidades sumadas
                    unidadFinalCalculada = unidadFinal, // Unidad determinada
                    mensaje = finalMessage
                )

            } catch (e: Exception) {
                Log.e(TAG, "Error en actualizarPorDesgloseManual para $productId", e)
                withContext(Dispatchers.Main) { // Cambiar a Main thread para UI
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al procesar desglose: ${e.message}")) }
                }
            }
        }
    }
    // --- FIN FUNCIÓN (DESGLOSE MANUAL) ---

    // --- FUNCIÓN INTERNA (DESGLOSE MANUAL) ---
    private fun actualizarEstadoConDesgloseManual(
        itemOriginal: AjusteFinalItem, // Recibe el item original
        lotesSeleccionadosCompletos: List<StockLot>, // Lotes completos usados
        lotesDesglosadosCalculados: List<LoteDesglosado>, // El desglose resultante
        totalKgCalculado: Double,
        totalUnidadesCalculadas: Int, // Recibe las unidades sumadas
        unidadFinalCalculada: String,
        mensaje: String
    ) {
        _uiState.update { state ->
            val updatedItems = state.itemsParaAjustar.toMutableList()
            // Usar el ID del detalle original para encontrar el índice
            val itemIndex = updatedItems.indexOfFirst { it.detalleOriginal.id == itemOriginal.detalleOriginal.id }

            if (itemIndex != -1) {
                // Actualizar el item en la lista
                updatedItems[itemIndex] = itemOriginal.copy(
                    cantidadConfirmadaKg = totalKgCalculado, // Usar los KG calculados del desglose
                    lotesConfirmados = lotesDesglosadosCalculados.sortedBy { ld -> ld.loteFecha ?: Date(0) }, // Usar el desglose calculado
                    lotesSeleccionadosManualmente = true, // MARCAR COMO MANUAL
                    cantidadEditadaUnidades = totalUnidadesCalculadas // **CORRECCIÓN**: Guardar las unidades calculadas
                )
            } else {
                Log.e(TAG, "actualizarEstadoConDesgloseManual: itemIndex inválido $itemIndex al actualizar UI.")
            }
            // Asegurar quitar isLoading y mostrar mensaje
            state.copy(isLoading = false, itemsParaAjustar = updatedItems, userMessage = UiMessage(message = mensaje))
        }
    }
    // --- FIN FUNCIÓN INTERNA (DESGLOSE MANUAL) ---

    // --- FUNCIÓN AUXILIAR AÑADIDA ---
    private suspend fun fetchLotesDisponiblesConIds(productId: String, planId: String?, loteIds: List<String>): List<StockLot> {
        if (loteIds.isEmpty()) return emptyList()
        // Firestore 'in' query tiene un límite (ahora 30), dividir si es necesario
        return loteIds.chunked(30).flatMap { chunk ->
            try {
                // Buscar lotes que coincidan con los IDs Y que estén libres O reservados por ESTE plan
                val query = db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereIn(FieldPath.documentId(), chunk)
                    .whereEqualTo("productId", productId) // Seguridad extra
                    .whereEqualTo("isDepleted", false) // Seguridad extra

                val snapshot = query.get().await()
                snapshot.documents.mapNotNull { doc ->
                    val lote = doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                    // Filtrar manualmente por estado de traspaso
                    if (lote != null && (lote.estadoTraspaso == null || lote.estadoTraspaso == planId)) {
                        lote
                    } else {
                        null
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchLotesDisponiblesConIds chunk", e)
                emptyList<StockLot>()
            }
        }
    }

    // Función auxiliar para obtener lotes disponibles (libres + reservados por este plan)
    private suspend fun fetchLotesDisponibles(productId: String, planId: String?): List<StockLot> {
        return try {
            // Cargar IDs de lotes libres
            val freeLotesQuery = db.collection(FirestoreCollections.INVENTORY_LOTS)
                .whereEqualTo("productId", productId)
                .whereEqualTo("location", Location.MATRIZ)
                .whereEqualTo("isDepleted", false)
                .whereEqualTo("estadoTraspaso", null)
                .get().await()

            val reservedLotesQuery = if (planId != null) {
                db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereEqualTo("productId", productId)
                    .whereEqualTo("location", Location.MATRIZ)
                    .whereEqualTo("isDepleted", false)
                    .whereEqualTo("estadoTraspaso", planId)
                    .get().await()
            } else null

            val allIds = (freeLotesQuery.documents.map { it.id } +
                    (reservedLotesQuery?.documents?.map { it.id } ?: emptyList())).distinct()

            if (allIds.isEmpty()) return emptyList()

            // Cargar los objetos completos y actualizados para tener currentQuantity correcta
            fetchLotesCompletos(allIds).sortedBy { it.receivedAt ?: Date(0) } // Ordenar por fecha

        } catch (e: Exception) {
            Log.e(TAG, "Error en fetchLotesDisponibles para $productId", e)
            emptyList()
        }
    }

    // Función auxiliar para cargar objetos StockLot completos dado una lista de IDs
    private suspend fun fetchLotesCompletos(loteIds: List<String>): List<StockLot> {
        if (loteIds.isEmpty()) return emptyList()
        // Firestore 'in' query tiene un límite (ahora 30), dividir si es necesario
        return loteIds.chunked(30).flatMap { chunk: List<String> -> // <-- Tipo explícito
            try {
                db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereIn(FieldPath.documentId(), chunk) // <-- Usar FieldPath.documentId()
                    .get().await().documents.mapNotNull { doc ->
                        doc.toObject(StockLot::class.java)?.copy(id = doc.id) // Asegurar ID
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchLotesCompletos chunk", e)
                emptyList<StockLot>()
            }
        }
    }


    // Función auxiliar para desglosar FIFO (ya está correcta)
    private fun desglosarLotesParaCantidad(cantidadNecesariaKg: Double, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesariaKg

        // Ordenar por fecha por si acaso no vienen ordenados
        for (lote in lotesDisponibles.sortedBy { it.receivedAt ?: Date(0) }) {
            if (kgRestantes <= stockEpsilon) break
            // Usar la cantidad actual REAL del lote
            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)

            if (aTomarDeEsteLote > stockEpsilon) {
                val pesoUnidad = lote.pesoPorUnidad
                // Calcular unidades solo si el lote es Fijo
                val unidades = if (!lote.unidadDeEmpaque.isNullOrBlank() && pesoUnidad != null && pesoUnidad > 0) {
                    floor(aTomarDeEsteLote / pesoUnidad).takeIf { it > 0 } // Calcular unidades si es lote fijo
                } else null

                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id,
                        cantidadATomarKg = aTomarDeEsteLote,
                        cantidadATomarUnidades = unidades?.toDouble(), // Guardar como Double?
                        lote = lote, // Incluir el objeto completo
                        loteFecha = lote.receivedAt,
                        loteProveedor = lote.supplierName,
                        loteUnidad = lote.unidadDeEmpaque,
                        lotePesoPorUnidad = lote.pesoPorUnidad
                    )
                )
                kgRestantes -= aTomarDeEsteLote
                kgAcumulados += aTomarDeEsteLote
            }
        }
        return Pair(lotesDesglosados, kgAcumulados)
    }

    // --- FUNCIÓN AUXILIAR AÑADIDA: desglosarLotesParaCantidadUnidades ---
    private fun desglosarLotesParaCantidadUnidades(
        unidadesNecesarias: Int,
        lotesDisponibles: List<StockLot>
    ): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var unidadesRestantes = unidadesNecesarias

        for (lote in lotesDisponibles.sortedBy { it.receivedAt ?: Date(0) }) { // Ordenar FIFO
            if (unidadesRestantes <= 0) break

            val pesoPorUnidad = lote.pesoPorUnidad
            // Asegurarse de que sea un lote de unidades (tenga peso y unidad)
            if (pesoPorUnidad != null && pesoPorUnidad > 0 && !lote.unidadDeEmpaque.isNullOrBlank()) {
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
    // --- FIN FUNCIÓN AUXILIAR ---

    // --- FUNCIÓN EJECUTAR TRASPASO (COMPLETA Y CORREGIDA) ---
    fun ejecutarTraspaso() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true) }
            val plan = _uiState.value.plan ?: run {
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error: Plan no cargado.")) }
                return@launch
            }
            // Usar los items del estado actual, que ya reflejan las ediciones del usuario
            val itemsAjustados = _uiState.value.itemsParaAjustar.filter { it.cantidadConfirmadaKg > stockEpsilon }

            val currentUser = auth.currentUser ?: run {
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error de autenticación.")) }
                return@launch
            }
            val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"
            val loteIdsReservadosOriginalmente = mutableSetOf<String>() // Para liberar los no usados

            try {
                // Recolectar todos los IDs que estaban reservados originalmente por este plan
                val detallesOriginalesSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
                    .document(plan.id).collection("detalles").get().await()
                detallesOriginalesSnapshot.toObjects(DetalleTraspasoPlan::class.java).forEach { detalle ->
                    detalle.lotesSugeridos.forEach { desglose ->
                        if (desglose.loteId.isNotBlank()) {
                            loteIdsReservadosOriginalmente.add(desglose.loteId)
                        }
                    }
                }

                db.runTransaction { transaction ->
                    Log.d("AjusteFinalVM_DEBUG", "FASE 1: LEYENDO todos los documentos...")
                    val planRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(plan.id)
                    val planDoc = transaction.get(planRef)

                    // Obtener referencias y datos necesarios DENTRO de la transacción
                    val refsLotesOrigen = itemsAjustados.flatMap { item ->
                        item.lotesConfirmados.map { desglose ->
                            db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId) to desglose
                        }
                    }.distinctBy { it.first.id } // Referencia única por lote origen

                    val refsProductos = itemsAjustados.map {
                        db.collection(FirestoreCollections.PRODUCTS).document(it.detalleOriginal.productId)
                    }.distinctBy { it.id }

                    val lotesOrigenSnapshots = refsLotesOrigen.map { transaction.get(it.first) }
                    val productSnapshots = refsProductos.map { transaction.get(it) }

                    Log.d("AjusteFinalVM_DEBUG", "FASE 2: VALIDANDO todos los datos...")

                    if (planDoc.toObject(TraspasoPlanificado::class.java)?.estado != TraspasoEstado.PENDIENTE) {
                        throw FirebaseFirestoreException("El plan ya no está pendiente.", FirebaseFirestoreException.Code.ABORTED)
                    }

                    // Validar existencia y stock de lotes de origen confirmados por el usuario
                    val lotesOrigenData = mutableMapOf<String, StockLot>()
                    lotesOrigenSnapshots.forEachIndexed { index, snapshot ->
                        val loteRef = refsLotesOrigen[index].first
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("El lote origen ID ...${loteRef.id.takeLast(6)} ya no existe.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        val lote = snapshot.toObject(StockLot::class.java)?.copy(id=snapshot.id)!! // Asegurar ID
                        lotesOrigenData[loteRef.id] = lote // Guardar para referencia después

                        // Encontrar cuánto se quiere tomar *en total* de este lote específico en este traspaso final
                        val totalATomarDeEsteLote = itemsAjustados.sumOf { item ->
                            item.lotesConfirmados
                                .filter { it.loteId == loteRef.id } // Buscar este lote en el desglose confirmado del item
                                .sumOf { it.cantidadATomarKg } // Sumar lo que se toma de él
                        }

                        // Ajustar la validación de stock para permitir una pequeña tolerancia
                        if (lote.currentQuantity < totalATomarDeEsteLote - stockEpsilon) {
                            throw FirebaseFirestoreException("Stock insuficiente en lote ...${lote.id.takeLast(6)}. Disp: ${String.format("%.2f", lote.currentQuantity)}, Req: ${String.format("%.2f", totalATomarDeEsteLote)}", FirebaseFirestoreException.Code.ABORTED)
                        }
                    }

                    // Validar existencia de productos
                    val productosData = mutableMapOf<String, Product>()
                    productSnapshots.forEachIndexed { index, snapshot ->
                        val prodRef = refsProductos[index]
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("El producto ID ${prodRef.id} fue eliminado.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        productosData[prodRef.id] = snapshot.toObject(Product::class.java)?.copy(id=snapshot.id)!! // Asegurar ID
                    }

                    Log.d("AjusteFinalVM_DEBUG", "FASE 3: ESCRIBIENDO todos los cambios...")
                    val traspasoTimestamp = Date()
                    val loteIdsUsadosEnConfirmacion = mutableSetOf<String>()

                    itemsAjustados.forEach { item ->
                        val productRef = db.collection(FirestoreCollections.PRODUCTS).document(item.detalleOriginal.productId)
                        val currentProduct = productosData[productRef.id]!! // Ya validamos que existe
                        var cantidadTotalConfirmadaKgParaEsteProducto = 0.0
                        val newMovementRef = db.collection(FirestoreCollections.STOCK_MOVEMENTS).document()
                        val sublotesCreadosIds = mutableListOf<String>() // Para el movimiento

                        // **INICIO CORRECCIÓN**: Determinar si es Fijo y sumar unidades
                        val esFijo = !currentProduct.requiresPackaging
                        var unidadesTotalesParaEsteProducto = 0
                        // **FIN CORRECCIÓN**

                        // Iterar sobre el DESGLOSE CONFIRMADO por el usuario
                        item.lotesConfirmados.forEach { desgloseConfirmado ->
                            val loteOrigenRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desgloseConfirmado.loteId)
                            val loteOrigenActual = lotesOrigenData[loteOrigenRef.id]!! // Ya validamos que existe
                            val cantidadATomarConfirmada = desgloseConfirmado.cantidadATomarKg // Usar la cantidad del desglose confirmado

                            if (cantidadATomarConfirmada > stockEpsilon) {
                                loteIdsUsadosEnConfirmacion.add(loteOrigenRef.id) // Marcar como usado
                                val nuevaCantidadEnLoteOrigen = loteOrigenActual.currentQuantity - cantidadATomarConfirmada
                                val isDepleted = nuevaCantidadEnLoteOrigen <= stockEpsilon

                                // Actualizar lote origen y LIBERARLO
                                transaction.update(loteOrigenRef, mapOf(
                                    "currentQuantity" to nuevaCantidadEnLoteOrigen,
                                    "isDepleted" to isDepleted,
                                    "estadoTraspaso" to null // Liberar
                                ))
                                Log.d("AjusteFinalVM", "Ejecutando: Lote ${loteOrigenRef.id.takeLast(4)} -> Nueva Cant: ${String.format("%.2f", nuevaCantidadEnLoteOrigen)}, Liberado")

                                // **INICIO CORRECCIÓN**: Obtener unidades del desglose si es Fijo
                                val unidadesParaEsteDesglose = if (esFijo) {
                                    desgloseConfirmado.cantidadATomarUnidades // Usar las unidades ya calculadas
                                } else {
                                    null // Granel no tiene unidades
                                }
                                if (unidadesParaEsteDesglose != null) {
                                    unidadesTotalesParaEsteProducto += unidadesParaEsteDesglose.toInt()
                                }
                                // **FIN CORRECCIÓN**

                                // Crear/Actualizar sublote en C04
                                val newSubloteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document()
                                val nuevoSublote = StockLot(
                                    id = newSubloteRef.id, productId = loteOrigenActual.productId, productName = loteOrigenActual.productName,
                                    unit = loteOrigenActual.unit, location = Location.CONGELADOR_04, receivedAt = traspasoTimestamp,
                                    movementIdIn = newMovementRef.id, initialQuantity = cantidadATomarConfirmada, currentQuantity = cantidadATomarConfirmada,
                                    isPackaged = loteOrigenActual.isPackaged, expirationDate = loteOrigenActual.expirationDate,
                                    originalLotId = loteOrigenActual.id, originalReceivedAt = loteOrigenActual.receivedAt,
                                    originalSupplierId = loteOrigenActual.supplierId, originalSupplierName = loteOrigenActual.supplierName,
                                    originalLotNumber = loteOrigenActual.lotNumber,
                                    unidadDeEmpaque = loteOrigenActual.unidadDeEmpaque, // Heredar
                                    pesoPorUnidad = loteOrigenActual.pesoPorUnidad, // Heredar
                                    cantidadInicialUnidades = unidadesParaEsteDesglose // <-- Usar valor corregido
                                )
                                transaction.set(newSubloteRef, nuevoSublote)
                                sublotesCreadosIds.add(newSubloteRef.id)
                                cantidadTotalConfirmadaKgParaEsteProducto += cantidadATomarConfirmada
                            }
                        } // Fin forEach desgloseConfirmado

                        // Actualizar stocks del producto basado en la suma REAL del desglose confirmado
                        val nuevoStockMatriz = currentProduct.stockMatriz - cantidadTotalConfirmadaKgParaEsteProducto
                        val nuevoStockC04 = currentProduct.stockCongelador04 + cantidadTotalConfirmadaKgParaEsteProducto
                        val nuevoTotalStock = nuevoStockMatriz + nuevoStockC04

                        transaction.update(productRef, mapOf(
                            "stockMatriz" to nuevoStockMatriz,
                            "stockCongelador04" to nuevoStockC04,
                            "totalStock" to nuevoTotalStock,
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedByName" to currentUserName
                        ))

                        // Crear el movimiento de traspaso con la cantidad REAL del desglose
                        val movement = StockMovement(
                            id = newMovementRef.id, userId = currentUser.uid, userName = currentUserName,
                            productId = item.detalleOriginal.productId, productName = item.detalleOriginal.productName, type = MovementType.TRASPASO_M_C04,
                            quantity = cantidadTotalConfirmadaKgParaEsteProducto, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04,
                            reason = "Traspaso confirmado plan: ${plan.id.takeLast(6)}", timestamp = traspasoTimestamp,
                            affectedLotIds = item.lotesConfirmados.map { it.loteId }, // Lotes origen usados
                            stockAfterMatriz = nuevoStockMatriz,
                            stockAfterCongelador04 = nuevoStockC04,
                            stockAfterTotal = nuevoTotalStock
                        )
                        transaction.set(newMovementRef, movement)

                        // **NUEVO**: Actualizar el Detalle del Plan con las cantidades finales
                        val detalleRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
                            .document(plan.id)
                            .collection("detalles")
                            .document(item.detalleOriginal.id)
                        // **CORRECCIÓN**: Guardar las unidades sumadas
                        transaction.update(detalleRef, mapOf(
                            "sugerenciaKg" to cantidadTotalConfirmadaKgParaEsteProducto,
                            "sugerenciaUnidades" to unidadesTotalesParaEsteProducto // Guardar unidades si es Fijo
                        ))
                        // **FIN NUEVO**

                    } // Fin forEach itemsAjustados

                    // Liberar lotes que estaban reservados originalmente pero NO se usaron en la confirmación final
                    val loteIdsNoUsados = loteIdsReservadosOriginalmente - loteIdsUsadosEnConfirmacion
                    loteIdsNoUsados.forEach { loteId ->
                        val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(loteId)
                        transaction.update(loteRef, "estadoTraspaso", null) // Liberar
                        Log.d("AjusteFinalVM", "Ejecutando: Lote NO USADO ${loteId.takeLast(4)} liberado")
                    }

                    // Marcar el plan como confirmado
                    transaction.update(planRef, mapOf(
                        "estado" to TraspasoEstado.CONFIRMADO.name,
                        "confirmedAt" to FieldValue.serverTimestamp(),
                        "confirmedBy" to currentUserName
                    ))

                }.await() // Fin de la transacción

                _uiState.update {
                    it.copy(
                        isExecuting = false,
                        userMessage = UiMessage(message = "Traspaso ejecutado con éxito."),
                        navigationEvent = NavigationEvent.GoBack
                    )
                }
            } catch (e: Exception) {
                Log.e("AjusteFinalVM", "Error en transacción de traspaso", e)
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error al ejecutar: ${e.message}")) }
            }
        }
    }
    // --- FIN FUNCIÓN EJECUTAR TRASPASO ---

    fun onUserMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigationEvent = null) }
    }
}

