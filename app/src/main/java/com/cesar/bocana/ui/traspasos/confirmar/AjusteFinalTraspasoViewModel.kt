package com.cesar.bocana.ui.traspasos.confirmar

// Asegúrate de importar UiMessage si la moviste a otro paquete
// import com.cesar.bocana.ui.traspasos.confirmar.UiMessage // O la ruta correcta donde esté definida

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
    val product: @RawValue Product,
    var cantidadConfirmadaKg: Double,
    var lotesConfirmados: @RawValue List<LoteDesglosado>,
    // **CORRECCIÓN**: Cambiado a Boolean
    var lotesSeleccionadosManualmente: Boolean = false // Flag para saber si el usuario hizo una selección manual (checkbox o desglose)
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
                val plan = planSnapshot.toObject(TraspasoPlanificado::class.java)?.copy(id = planSnapshot.id)

                if (plan == null || plan.estado != TraspasoEstado.PENDIENTE) {
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error: Plan no encontrado o ya no está pendiente."), navigationEvent = NavigationEvent.GoBack) }
                    return@launch
                }

                val detallesSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(planId).collection("detalles").get().await()
                val detalles = detallesSnapshot.documents.mapNotNull { doc ->
                    doc.toObject(DetalleTraspasoPlan::class.java)?.copy(id = doc.id)
                }.filter { it.productId.isNotBlank() }

                val items = detalles.map { detalle ->
                    async(Dispatchers.IO) {
                        val productDoc = db.collection(FirestoreCollections.PRODUCTS).document(detalle.productId).get().await()
                        val product = productDoc.toObject(Product::class.java)?.copy(id = productDoc.id)
                        if (product == null) {
                            Log.e(TAG, "Error: Producto ${detalle.productId} no encontrado para detalle ${detalle.id}")
                            return@async null
                        }

                        // Cargar Lotes CON ID
                        val lotesCompletosDesglosados = detalle.lotesSugeridos.mapNotNull { desglose ->
                            try {
                                if (desglose.loteId.isBlank()) {
                                    Log.w(TAG, "Lote ID vacío encontrado en detalle ${detalle.id}")
                                    return@mapNotNull null
                                }
                                val loteDoc = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId).get().await()
                                loteDoc.toObject(StockLot::class.java)?.let { loteCompleto ->
                                    desglose.copy(lote = loteCompleto.copy(id = loteDoc.id)) // Asegurar ID
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Error cargando lote ${desglose.loteId} para detalle ${detalle.id}", e)
                                null
                            }
                        }
                        AjusteFinalItem(
                            detalleOriginal = detalle,
                            product = product,
                            cantidadConfirmadaKg = detalle.sugerenciaKg,
                            lotesConfirmados = lotesCompletosDesglosados,
                            lotesSeleccionadosManualmente = false // Inicialmente no es manual
                        )
                    }
                }.mapNotNull { it.await() }

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

            // **INICIO CORRECCIÓN**: Usar el flag booleano
            if (itemToUpdate.lotesSeleccionadosManualmente) {
                Log.d(TAG, "updateConfirmedQuantity (Manual): Actualizando solo cantidad mostrada a ${String.format("%.2f", newQuantityKg)}")
                val updatedItems = currentItems.toMutableList()
                updatedItems[itemIndex] = itemToUpdate.copy(cantidadConfirmadaKg = newQuantityKg)
                _uiState.update { it.copy(itemsParaAjustar = updatedItems) }
            } else {
                Log.d(TAG, "updateConfirmedQuantity (FIFO): Recalculando desglose FIFO para ${String.format("%.2f", newQuantityKg)} Kg")
                val lotesParaDesglosar = fetchLotesDisponibles(itemToUpdate.detalleOriginal.productId, _uiState.value.plan?.id)
                val (nuevosLotesDesglosados, cantidadRealTomada) = desglosarLotesParaCantidad(newQuantityKg, lotesParaDesglosar)

                val updatedItems = currentItems.toMutableList()
                updatedItems[itemIndex] = itemToUpdate.copy(
                    cantidadConfirmadaKg = cantidadRealTomada,
                    lotesConfirmados = nuevosLotesDesglosados,
                    lotesSeleccionadosManualmente = false // Sigue siendo FIFO
                )
                _uiState.update { it.copy(itemsParaAjustar = updatedItems) }
            }
            // **FIN CORRECCIÓN**
        }
    }

    fun actualizarLotesManualmentePorIds(productId: String, loteIdsSeleccionados: List<String>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // **INICIO CORRECCIÓN**: Usar fetchLotesDisponibles para incluir los reservados por el plan
                val planId = _uiState.value.plan?.id
                val lotesCompletos = fetchLotesDisponiblesConIds(productId, planId, loteIdsSeleccionados)
                // **FIN CORRECCIÓN**

                if (lotesCompletos.isNotEmpty() || loteIdsSeleccionados.isEmpty()) {
                    actualizarEstadoConLotesManuales(productId, lotesCompletos) // Llamar a la lógica común
                } else if (loteIdsSeleccionados.isNotEmpty()){
                    Log.w(TAG, "actualizarLotesManualmentePorIds (Ajuste): No se encontraron lotes para IDs $loteIdsSeleccionados")
                    actualizarEstadoConLotesManuales(productId, emptyList()) // Llama con lista vacía para limpiar
                    _uiState.update { it.copy(userMessage = UiMessage(message = "Algunos lotes seleccionados ya no están disponibles o pertenecen a otro plan.")) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar lotes por IDs para $productId (Ajuste)", e)
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al procesar selección: ${e.message}")) }
            }
        }
    }

    // --- FUNCIÓN OBSOLETA (Lógica movida a la común 'actualizarEstadoConLotesManuales') ---
    // fun actualizarLotesManualmente(productId: String, nuevosLotesSeleccionados: List<StockLot>) { ... }
    // --- FIN FUNCIÓN OBSOLETA ---

    // --- FUNCIÓN MODIFICADA Y CENTRALIZADA ---
    fun actualizarPorDesgloseManual(productId: String, desgloseManualUsuario: List<DesgloseManualResult>) {
        val currentItems = _uiState.value.itemsParaAjustar
        val itemIndex = currentItems.indexOfFirst { it.detalleOriginal.productId == productId }
        if (itemIndex == -1) return
        val itemToUpdate = currentItems[itemIndex]
        // Indicar carga visualmente si es necesario (el diálogo de carga lo maneja el fragmento)
        // _uiState.update { it.copy(isLoading = true) } // O un flag específico

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val lotIdsNecesarios = desgloseManualUsuario.map { it.loteId }
                // **INICIO CORRECCIÓN**: Usar fetchLotesDisponiblesConIds para incluir los reservados por el plan
                val planId = _uiState.value.plan?.id
                val lotesCompletos = fetchLotesDisponiblesConIds(productId, planId, lotIdsNecesarios)
                val lotesCompletosMap = lotesCompletos.associateBy { it.id }
                // **FIN CORRECCIÓN**

                var totalKgDesglosado = 0.0
                val lotesDesglosadosFinal = mutableListOf<LoteDesglosado>()
                var ajusteRealizadoMsg: String? = null
                var finalMessage = "Desglose manual aplicado."

                desgloseManualUsuario.forEach { itemUsuario ->
                    val loteOriginal = lotesCompletosMap[itemUsuario.loteId]
                    if (loteOriginal != null) {
                        val cantidadUsuario = itemUsuario.cantidad
                        var cantidadRealTomadaKg = 0.0
                        var cantidadRealTomadaUnidades: Double? = null
                        val esLoteGranel = itemToUpdate.product.requiresPackaging

                        if (esLoteGranel) {
                            val kgRealesATomar = min(cantidadUsuario, loteOriginal.currentQuantity)
                            if (kgRealesATomar > stockEpsilon) {
                                cantidadRealTomadaKg = kgRealesATomar
                                if (kotlin.math.abs(cantidadUsuario - kgRealesATomar) > stockEpsilon) {
                                    ajusteRealizadoMsg = "Una o más cantidades ajustadas al stock."
                                }
                            }
                        } else {
                            val unidadesUsuario = cantidadUsuario.toInt()
                            val pesoUnidad = loteOriginal.pesoPorUnidad ?: 1.0
                            val unidadesDisponibles = if (pesoUnidad > 0) floor(loteOriginal.currentQuantity / pesoUnidad).toInt() else 0
                            val unidadesRealesATomar = min(unidadesUsuario, unidadesDisponibles)

                            if (unidadesRealesATomar > 0) {
                                cantidadRealTomadaKg = unidadesRealesATomar * pesoUnidad
                                cantidadRealTomadaUnidades = unidadesRealesATomar.toDouble()
                                if (unidadesRealesATomar < unidadesUsuario) {
                                    ajusteRealizadoMsg = "Una o más cantidades ajustadas al stock."
                                }
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
                        Log.w(TAG, "Lote ${itemUsuario.loteId} no encontrado/disponible para desglose manual (Ajuste)")
                        ajusteRealizadoMsg = "Algunos lotes no estaban disponibles."
                    }
                } // Fin forEach

                finalMessage = ajusteRealizadoMsg ?: finalMessage
                Log.d(TAG, "actualizarPorDesgloseManual para $productId (Ajuste): KG totales: ${String.format("%.2f", totalKgDesglosado)}")

                withContext(Dispatchers.Main) {
                    // **INICIO CORRECCIÓN**: Llamar a la lógica común de actualización de estado
                    actualizarEstadoConDesgloseManual(
                        itemToUpdate,
                        lotesDesglosadosFinal.mapNotNull { it.lote }, // Lotes de StockLot usados
                        lotesDesglosadosFinal, // Desglose completo
                        totalKgDesglosado,
                        finalMessage
                    )
                    // **FIN CORRECCIÓN**
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error en actualizarPorDesgloseManual para $productId (Ajuste)", e)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al procesar desglose: ${e.message}")) }
                    // setRecalculatingState(productId, false) // Quitar flag de carga si existiera
                }
            }
        }
    }
    // --- FIN FUNCIÓN MODIFICADA ---

    // --- NUEVA FUNCIÓN PRIVADA PARA ACTUALIZAR ESTADO (CHECKBOX) ---
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

        val cantidadARedistribuir = itemToUpdate.cantidadConfirmadaKg
        val (nuevosLotesDesglosados, cantidadRealTomada) = desglosarLotesParaCantidad(cantidadARedistribuir, lotesSeleccionadosCompletos)

        Log.d(TAG, "actualizarEstadoConLotesManuales para $productId (Ajuste): KG reales: ${String.format("%.2f", cantidadRealTomada)}")

        val snackbarMessage = if (kotlin.math.abs(cantidadARedistribuir - cantidadRealTomada) > stockEpsilon) {
            "Cantidad ajustada (${String.format("%.2f", cantidadRealTomada)} Kg) al stock disponible."
        } else {
            "Selección manual (checkbox) aplicada."
        }

        _uiState.update { state ->
            val updatedItems = state.itemsParaAjustar.toMutableList()
            if (itemIndex >= 0 && itemIndex < updatedItems.size) {
                updatedItems[itemIndex] = itemToUpdate.copy(
                    cantidadConfirmadaKg = cantidadRealTomada,
                    lotesConfirmados = nuevosLotesDesglosados,
                    lotesSeleccionadosManualmente = true // MARCAR COMO MANUAL
                )
            }
            // Quitar isLoading y mostrar mensaje
            state.copy(isLoading = false, itemsParaAjustar = updatedItems, userMessage = UiMessage(message = snackbarMessage))
        }
    }
    // --- FIN NUEVA FUNCIÓN PRIVADA (CHECKBOX) ---

    // --- NUEVA FUNCIÓN PRIVADA PARA ACTUALIZAR ESTADO (DESGLOSE MANUAL) ---
    private fun actualizarEstadoConDesgloseManual(
        itemOriginal: AjusteFinalItem,
        lotesSeleccionadosCompletos: List<StockLot>, // Lotes usados en el desglose
        lotesDesglosadosCalculados: List<LoteDesglosado>, // Desglose con cantidades
        totalKgCalculado: Double,
        mensaje: String
    ) {
        _uiState.update { state ->
            val updatedItems = state.itemsParaAjustar.toMutableList()
            val itemIndex = updatedItems.indexOfFirst { it.detalleOriginal.id == itemOriginal.detalleOriginal.id }

            if (itemIndex != -1) {
                updatedItems[itemIndex] = itemOriginal.copy(
                    cantidadConfirmadaKg = totalKgCalculado,
                    lotesConfirmados = lotesDesglosadosCalculados.sortedBy { ld -> ld.loteFecha ?: Date(0) },
                    lotesSeleccionadosManualmente = true // MARCAR COMO MANUAL
                )
            }
            // Quitar isLoading y mostrar mensaje
            state.copy(isLoading = false, itemsParaAjustar = updatedItems, userMessage = UiMessage(message = mensaje))
        }
    }
    // --- FIN NUEVA FUNCIÓN PRIVADA (DESGLOSE MANUAL) ---

    // --- NUEVA FUNCIÓN AUXILIAR ---
    // Carga lotes completos (libres O reservados por el plan actual) que coincidan con los IDs dados
    private suspend fun fetchLotesDisponiblesConIds(productId: String, planId: String?, loteIds: List<String>): List<StockLot> {
        if (loteIds.isEmpty()) return emptyList()
        return loteIds.chunked(30).flatMap { chunk ->
            try {
                // Query base buscando por IDs
                var query = db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereIn(FieldPath.documentId(), chunk)
                    .whereEqualTo("productId", productId) // Asegurar que sean del producto correcto
                    .whereEqualTo("isDepleted", false) // Solo con stock

                // Condición OR para estadoTraspaso (null O igual al planId)
                // Firestore no soporta OR directo en queries complejas, simulamos con dos queries si es necesario
                val lotesLibres = query.whereEqualTo("estadoTraspaso", null).get().await()
                val lotesReservados = if (planId != null) {
                    query.whereEqualTo("estadoTraspaso", planId).get().await()
                } else null

                // Combinar resultados y mapear
                val allDocs = lotesLibres.documents + (lotesReservados?.documents ?: emptyList())
                allDocs.distinctBy { it.id }.mapNotNull { doc ->
                    doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                }

            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchLotesDisponiblesConIds chunk", e)
                emptyList<StockLot>()
            }
        }
    }
    // --- FIN NUEVA FUNCIÓN AUXILIAR ---

    private suspend fun fetchLotesDisponibles(productId: String, planId: String?): List<StockLot> {
        return try {
            val freeLotesQuery = db.collection(FirestoreCollections.INVENTORY_LOTS)
                .whereEqualTo("productId", productId).whereEqualTo("location", Location.MATRIZ)
                .whereEqualTo("isDepleted", false).whereEqualTo("estadoTraspaso", null)
                .get().await()
            val reservedLotesQuery = if (planId != null) {
                db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereEqualTo("productId", productId).whereEqualTo("location", Location.MATRIZ)
                    .whereEqualTo("isDepleted", false).whereEqualTo("estadoTraspaso", planId)
                    .get().await()
            } else null

            val allDocs = freeLotesQuery.documents + (reservedLotesQuery?.documents ?: emptyList())
            allDocs.distinctBy { it.id }.mapNotNull { doc ->
                doc.toObject(StockLot::class.java)?.copy(id = doc.id)
            }.sortedBy { it.receivedAt ?: Date(0) }
        } catch (e: Exception) {
            Log.e(TAG, "Error en fetchLotesDisponibles para $productId", e)
            emptyList()
        }
    }

    private suspend fun fetchLotesCompletos(loteIds: List<String>): List<StockLot> {
        if (loteIds.isEmpty()) return emptyList()
        return loteIds.chunked(30).flatMap { chunk: List<String> ->
            try {
                db.collection(FirestoreCollections.INVENTORY_LOTS)
                    .whereIn(FieldPath.documentId(), chunk)
                    .get().await().documents.mapNotNull { doc ->
                        doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                    }
            } catch (e: Exception) {
                Log.e(TAG, "Error en fetchLotesCompletos chunk", e)
                emptyList<StockLot>()
            }
        }
    }

    private fun desglosarLotesParaCantidad(cantidadNecesariaKg: Double, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesariaKg

        for (lote in lotesDisponibles.sortedBy { it.receivedAt ?: Date(0) }) {
            if (kgRestantes <= stockEpsilon) break
            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)

            if (aTomarDeEsteLote > stockEpsilon) {
                val pesoUnidad = lote.pesoPorUnidad
                val unidades = if (!lote.unidadDeEmpaque.isNullOrBlank() && pesoUnidad != null && pesoUnidad > 0) {
                    floor(aTomarDeEsteLote / pesoUnidad).takeIf { it > 0 }
                } else null

                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id, cantidadATomarKg = aTomarDeEsteLote, cantidadATomarUnidades = unidades?.toDouble(),
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

    fun ejecutarTraspaso() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true) }
            val plan = _uiState.value.plan ?: run {
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error: Plan no cargado.")) }
                return@launch
            }
            val itemsAjustados = _uiState.value.itemsParaAjustar.filter { it.cantidadConfirmadaKg > stockEpsilon }

            val currentUser = auth.currentUser ?: run {
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error de autenticación.")) }
                return@launch
            }
            val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"
            val loteIdsReservadosOriginalmente = mutableSetOf<String>()

            try {
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
                    Log.d("AjusteFinalVM_DEBUG", "FASE 1: LEYENDO...")
                    val planRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(plan.id)
                    val planDoc = transaction.get(planRef)

                    val refsLotesOrigen = itemsAjustados.flatMap { item ->
                        item.lotesConfirmados.map { desglose ->
                            db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId) to desglose
                        }
                    }.distinctBy { it.first.id }

                    val refsProductos = itemsAjustados.map {
                        db.collection(FirestoreCollections.PRODUCTS).document(it.detalleOriginal.productId)
                    }.distinctBy { it.id }

                    val lotesOrigenSnapshots = refsLotesOrigen.map { transaction.get(it.first) }
                    val productSnapshots = refsProductos.map { transaction.get(it) }

                    Log.d("AjusteFinalVM_DEBUG", "FASE 2: VALIDANDO...")

                    if (planDoc.toObject(TraspasoPlanificado::class.java)?.estado != TraspasoEstado.PENDIENTE) {
                        throw FirebaseFirestoreException("El plan ya no está pendiente.", FirebaseFirestoreException.Code.ABORTED)
                    }

                    val lotesOrigenData = mutableMapOf<String, StockLot>()
                    lotesOrigenSnapshots.forEachIndexed { index, snapshot ->
                        val loteRef = refsLotesOrigen[index].first
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("Lote origen ...${loteRef.id.takeLast(6)} no existe.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        val lote = snapshot.toObject(StockLot::class.java)?.copy(id=snapshot.id)!!
                        lotesOrigenData[loteRef.id] = lote

                        val totalATomarDeEsteLote = itemsAjustados.sumOf { item ->
                            item.lotesConfirmados
                                .filter { it.loteId == loteRef.id }
                                .sumOf { it.cantidadATomarKg }
                        }

                        if (lote.currentQuantity < totalATomarDeEsteLote - stockEpsilon) {
                            throw FirebaseFirestoreException("Stock insuficiente lote ...${lote.id.takeLast(6)}. Disp:${String.format("%.2f", lote.currentQuantity)}, Req:${String.format("%.2f", totalATomarDeEsteLote)}", FirebaseFirestoreException.Code.ABORTED)
                        }
                        // Validar estadoTraspaso
                        if (lote.estadoTraspaso != null && lote.estadoTraspaso != plan.id) {
                            throw FirebaseFirestoreException("Lote ...${lote.id.takeLast(6)} está reservado por otro plan (${lote.estadoTraspaso}).", FirebaseFirestoreException.Code.ABORTED)
                        }
                    }

                    val productosData = mutableMapOf<String, Product>()
                    productSnapshots.forEachIndexed { index, snapshot ->
                        val prodRef = refsProductos[index]
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("Producto ID ${prodRef.id} eliminado.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        productosData[prodRef.id] = snapshot.toObject(Product::class.java)?.copy(id=snapshot.id)!!
                    }

                    Log.d("AjusteFinalVM_DEBUG", "FASE 3: ESCRIBIENDO...")
                    val traspasoTimestamp = Date()
                    val loteIdsUsadosEnConfirmacion = mutableSetOf<String>()

                    itemsAjustados.forEach { item ->
                        val productRef = db.collection(FirestoreCollections.PRODUCTS).document(item.detalleOriginal.productId)
                        val currentProduct = productosData[productRef.id]!!
                        var cantidadTotalConfirmadaKgParaEsteProducto = 0.0
                        val newMovementRef = db.collection(FirestoreCollections.STOCK_MOVEMENTS).document()
                        val sublotesCreadosIds = mutableListOf<String>()

                        item.lotesConfirmados.forEach { desgloseConfirmado ->
                            val loteOrigenRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desgloseConfirmado.loteId)
                            val loteOrigenActual = lotesOrigenData[loteOrigenRef.id]!!
                            val cantidadATomarConfirmada = desgloseConfirmado.cantidadATomarKg

                            if (cantidadATomarConfirmada > stockEpsilon) {
                                loteIdsUsadosEnConfirmacion.add(loteOrigenRef.id)
                                val nuevaCantidadEnLoteOrigen = loteOrigenActual.currentQuantity - cantidadATomarConfirmada
                                val isDepleted = nuevaCantidadEnLoteOrigen <= stockEpsilon

                                transaction.update(loteOrigenRef, mapOf(
                                    "currentQuantity" to nuevaCantidadEnLoteOrigen,
                                    "isDepleted" to isDepleted,
                                    "estadoTraspaso" to null // Liberar
                                ))
                                Log.d("AjusteFinalVM", "Ejecutando: Lote ${loteOrigenRef.id.takeLast(4)} -> Nueva Cant: ${String.format("%.2f", nuevaCantidadEnLoteOrigen)}, Liberado")

                                val newSubloteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document()
                                val nuevoSublote = StockLot(
                                    id = newSubloteRef.id, productId = loteOrigenActual.productId, productName = loteOrigenActual.productName,
                                    unit = loteOrigenActual.unit, location = Location.CONGELADOR_04, receivedAt = traspasoTimestamp,
                                    movementIdIn = newMovementRef.id, initialQuantity = cantidadATomarConfirmada, currentQuantity = cantidadATomarConfirmada,
                                    isPackaged = loteOrigenActual.isPackaged, expirationDate = loteOrigenActual.expirationDate,
                                    originalLotId = loteOrigenActual.id, originalReceivedAt = loteOrigenActual.receivedAt,
                                    originalSupplierId = loteOrigenActual.supplierId, originalSupplierName = loteOrigenActual.supplierName,
                                    originalLotNumber = loteOrigenActual.lotNumber,
                                    unidadDeEmpaque = loteOrigenActual.unidadDeEmpaque,
                                    pesoPorUnidad = loteOrigenActual.pesoPorUnidad,
                                    cantidadInicialUnidades = desgloseConfirmado.cantidadATomarUnidades
                                )
                                transaction.set(newSubloteRef, nuevoSublote)
                                sublotesCreadosIds.add(newSubloteRef.id)
                                cantidadTotalConfirmadaKgParaEsteProducto += cantidadATomarConfirmada
                            }
                        } // Fin forEach desgloseConfirmado

                        val nuevoStockMatriz = currentProduct.stockMatriz - cantidadTotalConfirmadaKgParaEsteProducto
                        val nuevoStockC04 = currentProduct.stockCongelador04 + cantidadTotalConfirmadaKgParaEsteProducto
                        val nuevoTotalStock = nuevoStockMatriz + nuevoStockC04

                        transaction.update(productRef, mapOf(
                            "stockMatriz" to nuevoStockMatriz, "stockCongelador04" to nuevoStockC04,
                            "totalStock" to nuevoTotalStock, "updatedAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedByName" to currentUserName
                        ))

                        val movement = StockMovement(
                            id = newMovementRef.id, userId = currentUser.uid, userName = currentUserName,
                            productId = item.detalleOriginal.productId, productName = item.detalleOriginal.productName, type = MovementType.TRASPASO_M_C04,
                            quantity = cantidadTotalConfirmadaKgParaEsteProducto, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04,
                            reason = "Traspaso confirmado plan: ${plan.id.takeLast(6)}", timestamp = traspasoTimestamp,
                            affectedLotIds = item.lotesConfirmados.map { it.loteId },
                            stockAfterMatriz = nuevoStockMatriz, stockAfterCongelador04 = nuevoStockC04,
                            stockAfterTotal = nuevoTotalStock
                        )
                        transaction.set(newMovementRef, movement)
                    } // Fin forEach itemsAjustados

                    val loteIdsNoUsados = loteIdsReservadosOriginalmente - loteIdsUsadosEnConfirmacion
                    loteIdsNoUsados.forEach { loteId ->
                        val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(loteId)
                        transaction.update(loteRef, "estadoTraspaso", null) // Liberar
                        Log.d("AjusteFinalVM", "Ejecutando: Lote NO USADO ${loteId.takeLast(4)} liberado")
                    }

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


    fun onUserMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }

    fun onNavigationHandled() {
        _uiState.update { it.copy(navigationEvent = null) }
    }
}
