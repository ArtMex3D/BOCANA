package com.cesar.bocana.ui.traspasos.confirmar

import android.os.Parcelable
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.*
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
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
import kotlinx.parcelize.Parcelize
import kotlinx.parcelize.RawValue
import kotlin.math.min
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale


// --- CLASES DE DATOS NECESARIAS PARA ESTA PANTALLA ---
sealed class NavigationEvent {
    object GoBack : NavigationEvent()
}

@Parcelize
data class AjusteFinalItem(
    val detalleOriginal: @RawValue DetalleTraspasoPlan,
    var cantidadConfirmadaKg: Double,
    var lotesConfirmados: @RawValue List<LoteDesglosado>
) : Parcelable


// --- UI STATE PARA ESTA PANTALLA ---
data class AjusteFinalUiState(
    val isLoading: Boolean = true,
    val isExecuting: Boolean = false,
    val plan: TraspasoPlanificado? = null,
    val itemsParaAjustar: List<AjusteFinalItem> = emptyList(),
    val userMessage: UiMessage? = null,
    val navigationEvent: NavigationEvent? = null
)

// --- VIEWMODEL COMPLETO ---
class AjusteFinalTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val auth = Firebase.auth
    private val _uiState = MutableStateFlow(AjusteFinalUiState())
    val uiState: StateFlow<AjusteFinalUiState> = _uiState.asStateFlow()
    private val stockEpsilon = 0.01
    private val lotDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())


    fun loadPlanDetails(planId: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val planSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(planId).get().await()
                val plan = planSnapshot.toObject(TraspasoPlanificado::class.java)

                if (plan == null) {
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error: No se encontró el plan.")) }
                    return@launch
                }

                val detallesSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(planId).collection("detalles").get().await()
                val detalles = detallesSnapshot.toObjects(DetalleTraspasoPlan::class.java)

                // ***** INICIO DE SOLUCIÓN: CARGAR LOTES REALES *****
                val items = detalles.map { detalle ->
                    // Para cada detalle, cargamos los objetos StockLot completos usando los IDs guardados
                    val lotesCompletosDesglosados = detalle.lotesSugeridos.mapNotNull { desglose ->
                        val loteDoc = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId).get().await()
                        loteDoc.toObject(StockLot::class.java)?.let {
                            desglose.copy(lote = it) // Crea una copia del desglose con el objeto lote completo
                        }
                    }
                    AjusteFinalItem(
                        detalleOriginal = detalle,
                        cantidadConfirmadaKg = detalle.sugerenciaKg,
                        lotesConfirmados = lotesCompletosDesglosados
                    )
                }
                // ***** FIN DE SOLUCIÓN *****

                _uiState.update { it.copy(isLoading = false, plan = plan, itemsParaAjustar = items) }

            } catch (e: Exception) {
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al cargar detalles: ${e.message}")) }
            }
        }
    }

    // --- NUEVA LÓGICA DE ACTUALIZACIÓN ---
    fun updateConfirmedQuantity(detalleId: String, newQuantityKg: Double) {
        viewModelScope.launch {
            val currentItems = _uiState.value.itemsParaAjustar
            val itemToUpdate = currentItems.find { it.detalleOriginal.id == detalleId } ?: return@launch

            val lotesDisponibles = fetchLotesDisponibles(itemToUpdate.detalleOriginal.productId)
            val (nuevosLotesDesglosados, cantidadRealTomada) = desglosarLotesParaCantidad(newQuantityKg, lotesDisponibles)

            val updatedItems = currentItems.map {
                if (it.detalleOriginal.id == detalleId) {
                    it.copy(
                        cantidadConfirmadaKg = cantidadRealTomada,
                        lotesConfirmados = nuevosLotesDesglosados
                    )
                } else {
                    it
                }
            }
            _uiState.update { it.copy(itemsParaAjustar = updatedItems) }
        }
    }

    fun actualizarLotesManualmente(productId: String, nuevosLotes: List<StockLot>) {
        val currentItems = _uiState.value.itemsParaAjustar
        val itemToUpdate = currentItems.find { it.detalleOriginal.productId == productId } ?: return

        val nuevaCantidadKg = nuevosLotes.sumOf { it.currentQuantity }
        val nuevosLotesDesglosados = desglosarLotesParaCantidad(nuevaCantidadKg, nuevosLotes).first

        val updatedItems = currentItems.map {
            if (it.detalleOriginal.productId == productId) {
                it.copy(
                    cantidadConfirmadaKg = nuevaCantidadKg,
                    lotesConfirmados = nuevosLotesDesglosados
                )
            } else {
                it
            }
        }
        _uiState.update { it.copy(itemsParaAjustar = updatedItems) }
    }

    private suspend fun fetchLotesDisponibles(productId: String): List<StockLot> {
        return try {
            db.collection(FirestoreCollections.INVENTORY_LOTS)
                .whereEqualTo("productId", productId)
                .whereEqualTo("location", Location.MATRIZ)
                .whereEqualTo("isDepleted", false)
                .orderBy("receivedAt", Query.Direction.ASCENDING)
                .get().await().toObjects(StockLot::class.java)
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun desglosarLotesParaCantidad(cantidadNecesariaKg: Double, lotesDisponibles: List<StockLot>): Pair<List<LoteDesglosado>, Double> {
        val lotesDesglosados = mutableListOf<LoteDesglosado>()
        var kgAcumulados = 0.0
        var kgRestantes = cantidadNecesariaKg

        for (lote in lotesDisponibles) {
            if (kgRestantes <= stockEpsilon) break
            val aTomarDeEsteLote = min(lote.currentQuantity, kgRestantes)
            if (aTomarDeEsteLote > stockEpsilon) {
                val pesoUnidad = lote.pesoPorUnidad ?: 1.0
                val unidades = if (pesoUnidad > 0) aTomarDeEsteLote / pesoUnidad else null
                lotesDesglosados.add(
                    LoteDesglosado(
                        loteId = lote.id,
                        cantidadATomarKg = aTomarDeEsteLote,
                        cantidadATomarUnidades = unidades,
                        lote = lote, // Se pasa el objeto para uso temporal en la UI
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

    // --- FIN DE LA NUEVA LÓGICA ---


    fun ejecutarTraspaso() {
        viewModelScope.launch {
            _uiState.update { it.copy(isExecuting = true) }
            val plan = _uiState.value.plan ?: return@launch
            val itemsAjustados = _uiState.value.itemsParaAjustar.filter { it.cantidadConfirmadaKg > 0 }
            val currentUser = auth.currentUser ?: run {
                _uiState.update { it.copy(isExecuting = false, userMessage = UiMessage(message = "Error de autenticación.")) }
                return@launch
            }
            val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

            try {
                // ***** INICIO SOLUCIÓN "READS-BEFORE-WRITES" *****
                db.runTransaction { transaction ->
                    // --- FASE 1: TODAS LAS LECTURAS PRIMERO ---
                    Log.d("AjusteFinalVM_DEBUG", "FASE 1: LEYENDO todos los documentos...")

                    val planRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(plan.id)
                    val planDoc = transaction.get(planRef)

                    val refsYDesgloses = itemsAjustados.flatMap { item ->
                        item.lotesConfirmados.map { desglose ->
                            Triple(db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId), desglose, item)
                        }
                    }
                    val productRefs = itemsAjustados.map { db.collection(FirestoreCollections.PRODUCTS).document(it.detalleOriginal.productId) to it }.toMap()

                    val lotesOrigenSnapshots = refsYDesgloses.map { transaction.get(it.first) }
                    val productSnapshots = productRefs.keys.map { transaction.get(it) }

                    // --- FASE 2: VALIDACIÓN DE TODOS LOS DATOS ---
                    Log.d("AjusteFinalVM_DEBUG", "FASE 2: VALIDANDO todos los datos...")

                    if (planDoc.toObject(TraspasoPlanificado::class.java)?.estado != TraspasoEstado.PENDIENTE) {
                        throw FirebaseFirestoreException("El plan ya no está pendiente.", FirebaseFirestoreException.Code.ABORTED)
                    }

                    // Validar todos los lotes
                    lotesOrigenSnapshots.forEachIndexed { index, snapshot ->
                        val (loteRef, desglose, _) = refsYDesgloses[index]
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("El lote ID ...${loteRef.id.takeLast(6)} ya no existe.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        val lote = snapshot.toObject(StockLot::class.java)!!
                        if (lote.currentQuantity < desglose.cantidadATomarKg - stockEpsilon) {
                            throw FirebaseFirestoreException("Stock insuficiente en lote ...${lote.id.takeLast(6)}.", FirebaseFirestoreException.Code.ABORTED)
                        }
                    }

                    // Validar todos los productos
                    productSnapshots.forEach { snapshot ->
                        if (!snapshot.exists()) {
                            throw FirebaseFirestoreException("El producto ID ${snapshot.id} fue eliminado.", FirebaseFirestoreException.Code.ABORTED)
                        }
                    }

                    // --- FASE 3: ESCRITURA DE TODOS LOS CAMBIOS ---
                    Log.d("AjusteFinalVM_DEBUG", "FASE 3: ESCRIBIENDO todos los cambios...")
                    val traspasoTimestamp = Date()

                    itemsAjustados.forEach { item ->
                        val productRef = db.collection(FirestoreCollections.PRODUCTS).document(item.detalleOriginal.productId)
                        var cantidadTotalConfirmadaKg = 0.0
                        val newMovementRef = db.collection(FirestoreCollections.STOCK_MOVEMENTS).document()

                        item.lotesConfirmados.forEach { desglose ->
                            val loteOrigenRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                            transaction.update(loteOrigenRef, "currentQuantity", FieldValue.increment(-desglose.cantidadATomarKg))
                            transaction.update(loteOrigenRef, "estadoTraspaso", null) // Liberar candado

                            // Lógica de sublotes
                            val newSubloteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document()
                            val nuevoSublote = StockLot(
                                id = newSubloteRef.id, productId = desglose.lote!!.productId, productName = desglose.lote!!.productName,
                                unit = desglose.lote!!.unit, location = Location.CONGELADOR_04, receivedAt = traspasoTimestamp,
                                movementIdIn = newMovementRef.id, initialQuantity = desglose.cantidadATomarKg, currentQuantity = desglose.cantidadATomarKg,
                                isPackaged = desglose.lote!!.isPackaged, expirationDate = desglose.lote!!.expirationDate,
                                originalLotId = desglose.loteId, originalReceivedAt = desglose.lote!!.receivedAt,
                                originalSupplierId = desglose.lote!!.supplierId, originalSupplierName = desglose.lote!!.supplierName,
                                originalLotNumber = desglose.lote!!.lotNumber
                            )
                            transaction.set(newSubloteRef, nuevoSublote)
                            cantidadTotalConfirmadaKg += desglose.cantidadATomarKg
                        }

                        transaction.update(productRef, mapOf(
                            "stockMatriz" to FieldValue.increment(-cantidadTotalConfirmadaKg),
                            "stockCongelador04" to FieldValue.increment(cantidadTotalConfirmadaKg),
                            "updatedAt" to FieldValue.serverTimestamp(),
                            "lastUpdatedByName" to currentUserName
                        ))

                        val movement = StockMovement(
                            id = newMovementRef.id, userId = currentUser.uid, userName = currentUserName,
                            productId = item.detalleOriginal.productId, productName = item.detalleOriginal.productName, type = MovementType.TRASPASO_M_C04,
                            quantity = cantidadTotalConfirmadaKg, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04,
                            reason = "Traspaso planificado: ${plan.id.takeLast(6)}", timestamp = traspasoTimestamp,
                            affectedLotIds = item.lotesConfirmados.map { it.loteId }
                        )
                        transaction.set(newMovementRef, movement)
                    }

                    transaction.update(planRef, mapOf(
                        "estado" to TraspasoEstado.CONFIRMADO.name,
                        "confirmedAt" to FieldValue.serverTimestamp(),
                        "confirmedBy" to currentUserName
                    ))
                }.await()
                // ***** FIN SOLUCIÓN "READS-BEFORE-WRITES" *****

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

