// main/java/com/cesar/bocana/ui/migration/CategoryMigrationViewModel.kt
package com.cesar.bocana.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.PrioridadDesabasto // Importar las constantes
import com.cesar.bocana.data.model.Product
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CategoryMigrationViewModel : ViewModel() {
    private val db = Firebase.firestore
    private val _uiState = MutableStateFlow<List<Product>>(emptyList())
    val uiState: StateFlow<List<Product>> = _uiState

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    init {
        loadAllProducts()
    }

    private fun loadAllProducts() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val snapshot = db.collection("products").orderBy("name").get().await()
                _uiState.value = snapshot.toObjects(Product::class.java)
            } catch (e: Exception) {
                // Handle error in a real app, e.g., show a message
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProductCategory(productId: String, newCategory: String) {
        _uiState.update { currentList ->
            currentList.map {
                if (it.id == productId) {
                    val newRectorId = if (newCategory != "PESCADO_CHICO") null else it.productoRectorId
                    it.copy(categoria = newCategory, productoRectorId = newRectorId)
                } else {
                    it
                }
            }
        }
    }

    fun updateProductRector(productId: String, newRectorId: String?) {
        _uiState.update { currentList ->
            currentList.map {
                if (it.id == productId) {
                    it.copy(productoRectorId = newRectorId)
                } else {
                    it
                }
            }
        }
    }

    suspend fun saveAllChanges() {
        _isLoading.value = true
        val batch = db.batch()
        _uiState.value.forEach { product ->
            val docRef = db.collection("products").document(product.id)

            // Crear el mapa base con las actualizaciones de categoría y rector
            val updateData = mutableMapOf<String, Any?>(
                "categoria" to product.categoria,
                "productoRectorId" to product.productoRectorId
            )

            // Añadir valores por defecto SOLO si los campos nuevos NO existen o son 0/default
            // (Esto asume que el objeto 'product' en memoria aún no tiene los valores si no existían en Firestore)
            // Una forma más segura sería leer el documento de Firestore aquí, pero sería más lento.
            // Asumimos que si stockMaximoC04 es 0.0, es porque no existía.
            if (product.stockMaximoC04 == 0.0 && product.stockIdealC04 > 0.0) {
                // Cálculo inicial sugerido
                updateData["stockMaximoC04"] = product.stockIdealC04 * 1.5
            } else if (product.stockMaximoC04 == 0.0 && product.stockIdealC04 == 0.0) {
                updateData["stockMaximoC04"] = 0.0 // O algún otro valor por defecto si stockIdeal es 0
            }
            // Si la prioridad es la por defecto ("MEDIA"), la establecemos explícitamente.
            // Esto asegura que el campo se cree en Firestore si no existía.
            if (product.prioridadDesabasto == PrioridadDesabasto.MEDIA) {
                updateData["prioridadDesabasto"] = PrioridadDesabasto.MEDIA
            }
            // Si el usuario ya cambió la prioridad en la UI (aunque no la tengamos aquí),
            // NO la sobrescribimos con "MEDIA". Se necesitaría lógica adicional
            // en CategoryMigrationFragment para guardar estos cambios temporalmente si se desea.
            // Por ahora, solo añadimos los defaults si no existen o son el valor base.

            batch.update(docRef, updateData)
        }
        batch.commit().await()
        _isLoading.value = false
    }
}