package com.cesar.bocana.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

data class MigrationUiState(
    val isLoading: Boolean = false,
    val products: List<Product> = emptyList(),
    val selectedProduct: Product? = null,
    val lotsForProduct: List<StockLot> = emptyList(),
    val message: String? = "Selecciona un producto para empezar."
)

class LotMigrationViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val _uiState = MutableStateFlow(MigrationUiState())
    val uiState: StateFlow<MigrationUiState> = _uiState

    init {
        loadProducts()
    }

    private fun loadProducts() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                val snapshot = db.collection("products")
                    .orderBy("name")
                    .get().await()
                val productList = snapshot.toObjects(Product::class.java)
                _uiState.value = _uiState.value.copy(isLoading = false, products = productList)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Error al cargar productos.")
            }
        }
    }

    fun fetchLotesForProduct(product: Product) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, lotsForProduct = emptyList(), selectedProduct = product, message = "Cargando lotes...")
            try {
                val snapshot = db.collection("inventoryLots")
                    .whereEqualTo("productId", product.id)
                    .whereEqualTo("isDepleted", false)
                    .orderBy("receivedAt")
                    .get().await()
                val lotes = snapshot.toObjects(StockLot::class.java)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lotsForProduct = lotes,
                    message = if (lotes.isEmpty()) "Este producto no tiene lotes activos." else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Error al cargar los lotes.")
            }
        }
    }

    fun convertLot(lote: StockLot, newValues: Map<String, Any?>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            try {
                db.collection("inventoryLots").document(lote.id)
                    .update(newValues)
                    .await()
                // Vuelve a cargar los lotes para reflejar el cambio
                _uiState.value.selectedProduct?.let { fetchLotesForProduct(it) }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Error al guardar el lote.")
            }
        }
    }
}
