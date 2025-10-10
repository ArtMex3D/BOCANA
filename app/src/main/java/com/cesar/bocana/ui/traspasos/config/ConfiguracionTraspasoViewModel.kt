package com.cesar.bocana.ui.traspasos.config

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.Product
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class ConfiguracionTraspasoViewModel : ViewModel() {

    private val db = Firebase.firestore
    private val _products = MutableStateFlow<List<Product>>(emptyList())
    val products: StateFlow<List<Product>> = _products

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        fetchProducts()
    }

    private fun fetchProducts() {
        _isLoading.value = true
        viewModelScope.launch {
            try {
                val snapshot = db.collection("products")
                    .whereEqualTo("isActive", true)
                    .orderBy("ordenTraspaso")
                    .orderBy("name")
                    .get()
                    .await()
                _products.value = snapshot.toObjects(Product::class.java)
            } catch (e: Exception) {
                Log.e("ConfigTraspasoVM", "Error fetching products", e)
                _error.value = "Error al cargar productos: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun updateProductOrder(orderedProducts: List<Product>) {
        viewModelScope.launch {
            try {
                val batch = db.batch()
                orderedProducts.forEachIndexed { index, product ->
                    val productRef = db.collection("products").document(product.id)
                    batch.update(productRef, "ordenTraspaso", index)
                }
                batch.commit().await()
                Log.d("ConfigTraspasoVM", "Orden de productos actualizado en Firestore.")
                // Actualizar el estado local para reflejar el nuevo orden guardado
                _products.value = orderedProducts.mapIndexed { index, product -> product.copy(ordenTraspaso = index) }
            } catch (e: Exception) {
                Log.e("ConfigTraspasoVM", "Error actualizando el orden", e)
                _error.value = "Error al guardar el nuevo orden."
                // Revertir a la lista anterior en caso de error
                fetchProducts()
            }
        }
    }

    fun updateProductConfig(productId: String, field: String, value: Any) {
        viewModelScope.launch {
            try {
                db.collection("products").document(productId)
                    .update(field, value)
                    .await()
                Log.d("ConfigTraspasoVM", "Campo '$field' actualizado para producto $productId.")

                // ***** INICIO DE LA SOLUCIÓN DE PERSISTENCIA *****
                // Actualiza el estado local inmediatamente después de la confirmación de Firestore.
                // Esto asegura que la UI refleje el cambio al instante y no se revierta al hacer scroll.
                _products.update { currentList ->
                    currentList.map { product ->
                        if (product.id == productId) {
                            when (field) {
                                "modoManualPDF" -> product.copy(modoManualPDF = value as Boolean)
                                "stockIdealC04" -> product.copy(stockIdealC04 = value as Double)
                                "espacioExtraPDF" -> product.copy(espacioExtraPDF = value as Double)
                                else -> product
                            }
                        } else {
                            product
                        }
                    }
                }
                // ***** FIN DE LA SOLUCIÓN *****

            } catch (e: Exception) {
                Log.e("ConfigTraspasoVM", "Error actualizando campo '$field'", e)
                _error.value = "Error al guardar la configuración."
            }
        }
    }
}
