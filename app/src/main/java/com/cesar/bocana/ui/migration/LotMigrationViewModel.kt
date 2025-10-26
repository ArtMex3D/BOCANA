package com.cesar.bocana.ui.migration

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.google.firebase.firestore.Transaction
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.*

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
                // ***** CAMBIO CLAVE *****
                // Se elimina el filtro 'whereEqualTo("isPackaged", false)'.
                // Ahora se mostrarán TODOS los lotes activos (no agotados) para permitir la re-conversión.
                val snapshot = db.collection("inventoryLots")
                    .whereEqualTo("productId", product.id)
                    .whereEqualTo("isDepleted", false) // Solo lotes con stock
                    .orderBy("receivedAt")
                    .get().await()
                val lotes = snapshot.toObjects(StockLot::class.java)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    lotsForProduct = lotes,
                    message = if (lotes.isEmpty()) "Este producto no tiene lotes activos para convertir o editar." else null
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Error al cargar los lotes.")
            }
        }
    }

    fun convertLot(
        lote: StockLot,
        isRedondeo: Boolean,
        isVariable: Boolean,
        isPromedio: Boolean,
        unidadDeEmpaque: String?,
        cantidadUnidades: Int?,
        pesoFijo: Double?
    ) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, message = "Procesando lote...")
            try {
                val loteOriginalRef = db.collection("inventoryLots").document(lote.id)

                db.runTransaction { transaction ->
                    // 1. Desactivar el lote original marcándolo como agotado. Esto funciona tanto para conversión como para re-conversión y EVITA DUPLICADOS.
                    transaction.update(loteOriginalRef, "isDepleted", true)

                    // 2. Crear los nuevos lotes basados en la lógica seleccionada.
                    when {
                        isVariable -> handleVariable(transaction, lote)
                        isRedondeo && pesoFijo != null && cantidadUnidades != null && cantidadUnidades > 0 -> handleRedondeo(transaction, lote, unidadDeEmpaque!!, cantidadUnidades, pesoFijo)
                        isPromedio && cantidadUnidades != null && cantidadUnidades > 0 -> handlePromedio(transaction, lote, unidadDeEmpaque!!, cantidadUnidades)
                    }
                }.await()

                // 3. Refrescar la lista de lotes para el producto seleccionado para que desaparezca el lote procesado.
                _uiState.value.selectedProduct?.let { fetchLotesForProduct(it) }

            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, message = "Error al procesar el lote: ${e.message}")
            }
        }
    }

    /**
     * Lógica CORREGIDA para "Variable (Solo KG)".
     * Crea un nuevo lote que es una copia del original, pero marcado como empacado y sin detalles de unidades.
     * Representa un lote a granel que ahora es utilizable.
     */
    private fun handleVariable(transaction: Transaction, lote: StockLot) {
        val newLotRef = db.collection("inventoryLots").document()
        val newLot = lote.copy(
            id = newLotRef.id,
            isDepleted = false,
            isPackaged = true, // Marcado como empacado
            receivedAt = Date(), // Fecha de conversión
            // Se establecen en null para indicar que es un lote de peso variable (granel empacado)
            unidadDeEmpaque = null,
            pesoPorUnidad = null,
            cantidadInicialUnidades = null,
            // Se mantiene la cantidad total
            initialQuantity = lote.currentQuantity,
            currentQuantity = lote.currentQuantity
        )
        transaction.set(newLotRef, newLot)
    }

    private fun handleRedondeo(
        transaction: Transaction,
        lote: StockLot,
        unidadDeEmpaque: String,
        cantidadUnidades: Int,
        pesoFijo: Double
    ) {
        if (cantidadUnidades == 1) {
            handlePromedio(transaction, lote, unidadDeEmpaque, 1)
            return
        }

        val cajasNormales = cantidadUnidades - 1
        val totalKgCajasNormales = cajasNormales * pesoFijo
        val pesoUltimaCaja = lote.currentQuantity - totalKgCajasNormales

        if (cajasNormales > 0) {
            val newLotNormalRef = db.collection("inventoryLots").document()
            val newLotNormal = lote.copy(
                id = newLotNormalRef.id,
                isDepleted = false,
                isPackaged = true,
                receivedAt = Date(),
                unidadDeEmpaque = unidadDeEmpaque,
                pesoPorUnidad = pesoFijo,
                initialQuantity = totalKgCajasNormales,
                currentQuantity = totalKgCajasNormales,
                cantidadInicialUnidades = cajasNormales.toDouble()
            )
            transaction.set(newLotNormalRef, newLotNormal)
        }

        if (pesoUltimaCaja > 0.01) {
            val newLotSobranteRef = db.collection("inventoryLots").document()
            val newLotSobrante = lote.copy(
                id = newLotSobranteRef.id,
                isDepleted = false,
                isPackaged = true,
                receivedAt = Date(),
                unidadDeEmpaque = unidadDeEmpaque,
                pesoPorUnidad = pesoUltimaCaja,
                initialQuantity = pesoUltimaCaja,
                currentQuantity = pesoUltimaCaja,
                cantidadInicialUnidades = 1.0
            )
            transaction.set(newLotSobranteRef, newLotSobrante)
        }
    }

    private fun handlePromedio(
        transaction: Transaction,
        lote: StockLot,
        unidadDeEmpaque: String,
        cantidadUnidades: Int
    ) {
        val newLotRef = db.collection("inventoryLots").document()
        val pesoPromedio = if (cantidadUnidades > 0) lote.currentQuantity / cantidadUnidades else 0.0

        val newLot = lote.copy(
            id = newLotRef.id,
            isDepleted = false,
            isPackaged = true,
            receivedAt = Date(),
            unidadDeEmpaque = unidadDeEmpaque,
            pesoPorUnidad = pesoPromedio,
            initialQuantity = lote.currentQuantity,
            currentQuantity = lote.currentQuantity,
            cantidadInicialUnidades = cantidadUnidades.toDouble()
        )
        transaction.set(newLotRef, newLot)
    }
}

