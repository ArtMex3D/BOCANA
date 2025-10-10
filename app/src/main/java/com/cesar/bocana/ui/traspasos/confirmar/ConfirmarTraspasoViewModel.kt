package com.cesar.bocana.ui.traspasos.confirmar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.DetalleTraspasoPlan
import com.cesar.bocana.data.model.TraspasoEstado
import com.cesar.bocana.data.model.TraspasoPlanificado
import com.cesar.bocana.utils.FirestoreCollections
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class UiMessage(val id: Long = System.currentTimeMillis(), val message: String)

data class ConfirmarUiState(
    val isLoading: Boolean = true,
    val planes: List<TraspasoPlanificado> = emptyList(),
    val userMessage: UiMessage? = null
)

class ConfirmarTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val _uiState = MutableStateFlow(ConfirmarUiState())
    val uiState: StateFlow<ConfirmarUiState> = _uiState.asStateFlow()

    init {
        escucharPlanesPendientes()
    }

    private fun escucharPlanesPendientes() {
        db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
            .whereEqualTo("estado", TraspasoEstado.PENDIENTE.name)
            .orderBy("fechaPlan", Query.Direction.DESCENDING)
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al cargar planes: ${error.message}")) }
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    val planes = snapshots.toObjects(TraspasoPlanificado::class.java)
                    _uiState.update { it.copy(isLoading = false, planes = planes) }
                }
            }
    }

    fun cancelarPlan(plan: TraspasoPlanificado) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true) }
            try {
                // ***** INICIO DE SOLUCIÓN CANCELAR *****
                // 1. Obtener los detalles para saber qué lotes liberar
                val detallesSnapshot = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
                    .document(plan.id).collection("detalles").get().await()
                val detalles = detallesSnapshot.toObjects(DetalleTraspasoPlan::class.java)

                val batch = db.batch()

                // 2. Liberar cada lote que estaba reservado
                detalles.forEach { detalle ->
                    detalle.lotesSugeridos.forEach { desglose ->
                        val loteRef = db.collection(FirestoreCollections.INVENTORY_LOTS).document(desglose.loteId)
                        batch.update(loteRef, mapOf("estadoTraspaso" to null))
                    }
                }

                // 3. Marcar el plan como cancelado
                val planRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(plan.id)
                batch.update(planRef, "estado", TraspasoEstado.CANCELADO.name)

                batch.commit().await()
                // ***** FIN DE SOLUCIÓN CANCELAR *****
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Plan cancelado y lotes liberados.")) }
            } catch (e: Exception) {
                Log.e("ConfirmarTraspasoVM", "Error al cancelar plan", e)
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al cancelar el plan: ${e.message}")) }
            }
        }
    }

    fun onUserMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }
}

