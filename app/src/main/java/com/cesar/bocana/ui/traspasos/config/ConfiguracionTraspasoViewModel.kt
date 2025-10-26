// main/java/com/cesar/bocana/ui/traspasos/config/ConfiguracionTraspasoViewModel.kt
package com.cesar.bocana.ui.traspasos.config

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.PrioridadDesabasto // Asegúrate de importar tus constantes
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
                // Ordenar primero por el orden de traspaso, luego por nombre como secundario
                val snapshot = db.collection("products")
                    .whereEqualTo("isActive", true) // Solo activos en esta pantalla
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

    // Se mantiene igual, guarda el orden después del Drag & Drop
    fun updateProductOrder(orderedProducts: List<Product>) {
        viewModelScope.launch {
            try {
                val batch = db.batch()
                orderedProducts.forEachIndexed { index, product ->
                    val productRef = db.collection("products").document(product.id)
                    // Solo actualizar si el orden realmente cambió para optimizar escrituras
                    if (product.ordenTraspaso != index) {
                        batch.update(productRef, "ordenTraspaso", index)
                    }
                }
                batch.commit().await()
                Log.d("ConfigTraspasoVM", "Orden de productos actualizado en Firestore.")
                // Actualizar el estado local para reflejar el nuevo orden guardado
                // Es importante crear una NUEVA lista para que el StateFlow emita el cambio
                _products.value = orderedProducts.mapIndexed { index, product -> product.copy(ordenTraspaso = index) }
            } catch (e: Exception) {
                Log.e("ConfigTraspasoVM", "Error actualizando el orden", e)
                _error.value = "Error al guardar el nuevo orden."
                // Considerar no revertir aquí, sino mostrar el error y permitir al usuario reintentar
                // fetchProducts() // Revertir a la lista anterior en caso de error podría ser confuso
            }
        }
    }

    // --- FUNCIÓN MODIFICADA ---
    fun updateProductConfig(productId: String, field: String, value: Any) {
        viewModelScope.launch {
            try {
                // Actualizar en Firestore
                db.collection("products").document(productId)
                    .update(field, value)
                    .await()
                Log.d("ConfigTraspasoVM", "Campo '$field' actualizado para producto $productId.")

                // ***** INICIO DE LA SOLUCIÓN MEJORADA *****
                // Actualiza el estado local (_products) INMEDIATAMENTE después de
                // la confirmación de Firestore para que la UI no revierta el cambio.
                _products.update { currentList ->
                    // Crear una nueva lista modificada
                    currentList.map { product ->
                        if (product.id == productId) {
                            // Usar 'when' para manejar todos los campos actualizables
                            when (field) {
                                "modoManualPDF" -> product.copy(modoManualPDF = value as? Boolean ?: product.modoManualPDF)
                                "stockIdealC04" -> product.copy(stockIdealC04 = value as? Double ?: product.stockIdealC04)
                                "espacioExtraPDF" -> product.copy(espacioExtraPDF = value as? Double ?: product.espacioExtraPDF)
                                // *** AÑADIDO: Manejar los campos faltantes ***
                                "stockMaximoC04" -> product.copy(stockMaximoC04 = value as? Double ?: product.stockMaximoC04)
                                "prioridadDesabasto" -> product.copy(prioridadDesabasto = value as? String ?: product.prioridadDesabasto)
                                else -> product // Si es otro campo, devolver el producto sin cambios
                            }
                        } else {
                            product // Devolver los otros productos sin cambios
                        }
                    }
                }
                // ***** FIN DE LA SOLUCIÓN *****

            } catch (e: Exception) {
                Log.e("ConfigTraspasoVM", "Error actualizando campo '$field' para $productId", e)
                _error.value = "Error al guardar la configuración para el campo '$field'."
                // Podríamos intentar revertir el cambio en _products aquí si falló Firestore,
                // pero por simplicidad, solo mostramos el error. El listener de Firestore
                // eventualmente corregirá el estado si la escritura falló.
            }
        }
    }

    // Función para limpiar el mensaje de error una vez mostrado
    fun clearError() {
        _error.value = null
    }
}