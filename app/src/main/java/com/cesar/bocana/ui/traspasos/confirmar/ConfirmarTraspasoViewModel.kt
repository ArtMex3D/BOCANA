package com.cesar.bocana.ui.traspasos.confirmar

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.*

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
        escucharPlanesRecientes()
    }

    private fun escucharPlanesRecientes() {
        // Calcular fecha límite: Hace exactamente 3 días
        val calendar = Calendar.getInstance()
        calendar.add(Calendar.DAY_OF_YEAR, -3)
        val fechaLimite = calendar.time

        db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS)
            .whereEqualTo("estado", TraspasoEstado.PENDIENTE.name)
            .whereGreaterThanOrEqualTo("createdAt", fechaLimite) // Solo trae los recientes
            .orderBy("createdAt", Query.Direction.DESCENDING) // El más nuevo arriba
            .addSnapshotListener { snapshots, error ->
                if (error != null) {
                    _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al cargar PDFs: ${error.message}")) }
                    return@addSnapshotListener
                }

                if (snapshots != null) {
                    viewModelScope.launch {
                        val planes = withContext(Dispatchers.Default) {
                            snapshots.toObjects(TraspasoPlanificado::class.java)
                        }
                        _uiState.update { it.copy(isLoading = false, planes = planes) }
                    }
                }
            }
    }

    fun eliminarPlan(plan: TraspasoPlanificado) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(isLoading = true) }
            try {
                val planRef = db.collection(FirestoreCollections.TRASPASOS_PLANIFICADOS).document(plan.id)

                // Borrar los subdocumentos (detalles)
                val detalles = planRef.collection("detalles").get().await()
                val batch = db.batch()
                detalles.forEach { batch.delete(it.reference) }

                // Borrar el plan principal
                batch.delete(planRef)
                batch.commit().await()

                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "PDF eliminado exitosamente.")) }
            } catch (e: Exception) {
                Log.e("ConfirmarTraspasoVM", "Error al eliminar plan", e)
                _uiState.update { it.copy(isLoading = false, userMessage = UiMessage(message = "Error al eliminar: ${e.message}")) }
            }
        }
    }

    fun onUserMessageShown() {
        _uiState.update { it.copy(userMessage = null) }
    }
}