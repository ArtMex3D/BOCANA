package com.cesar.bocana.ui.ajustecompleto

import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.Location
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.WriteBatch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale

class AjusteCompletoViewModel : ViewModel() {

    private val firestore = FirebaseFirestore.getInstance()
    private val auth = FirebaseAuth.getInstance()
    private val TAG = "AjusteCompletoVM"

    private val _isLoading = MutableLiveData(false)
    val isLoading: LiveData<Boolean> = _isLoading

    private val _products = MutableLiveData<List<Product>>()
    val products: LiveData<List<Product>> = _products

    private val _ajusteResult = MutableLiveData<AjusteResult>()
    val ajusteResult: LiveData<AjusteResult> = _ajusteResult

    private val _progressMessage = MutableLiveData<String>("")
    val progressMessage: LiveData<String> = _progressMessage

    suspend fun loadActiveProductsWithStockInC04() {
        Log.d(TAG, "loadActiveProductsWithStockInC04: INICIO")
        withContext(Dispatchers.Main) { _isLoading.value = true }

        try {
            val snapshot = firestore.collection("products")
                .whereEqualTo("isActive", true)
                .get()
                .await()

            Log.d(TAG, "loadActiveProductsWithStockInC04: ${snapshot.size()} productos activos encontrados")

            val allProducts = snapshot.documents.mapNotNull { doc ->
                doc.toObject(Product::class.java)?.copy(id = doc.id)
            }

            val productsWithStock = allProducts.filter { it.stockCongelador04 > 0.01 }
                .sortedBy { it.name }

            Log.d(TAG, "loadActiveProductsWithStockInC04: ${productsWithStock.size} productos con stock en C-04")
            withContext(Dispatchers.Main) { _products.value = productsWithStock }
        } catch (e: Exception) {
            Log.e(TAG, "loadActiveProductsWithStockInC04: ERROR", e)
            withContext(Dispatchers.Main) { _products.value = emptyList() }
        } finally {
            withContext(Dispatchers.Main) { _isLoading.value = false }
        }
    }

    suspend fun executeCompleteAdjustment(adjustments: Map<String, Double>): Boolean = withContext(Dispatchers.IO) {
        Log.e(TAG, "🚨🚨🚨 executeCompleteAdjustment: LLAMADA RECIBIDA con ${adjustments.size} productos 🚨🚨🚨")

        withContext(Dispatchers.Main) {
            _isLoading.value = true
            _progressMessage.value = "Preparando ajuste..."
        }

        try {
            val currentUser = auth.currentUser
            if (currentUser == null) {
                Log.e(TAG, "executeCompleteAdjustment: Usuario no autenticado")
                throw Exception("Usuario no autenticado")
            }

            Log.d(TAG, "Usuario: ${currentUser.email}")
            val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"
            val batch = firestore.batch()
            val movements = mutableListOf<StockMovement>()

            var productCounter = 0
            for ((productId, newPhysicalStock) in adjustments) {
                productCounter++
                Log.d(TAG, "--- Producto $productCounter/${adjustments.size} ---")

                withContext(Dispatchers.Main) {
                    _progressMessage.value = "Procesando producto $productCounter/${adjustments.size}..."
                }

                val productSnapshot = firestore.collection("products").document(productId).get().await()
                val product = productSnapshot.toObject(Product::class.java)
                if (product == null) {
                    Log.e(TAG, "Producto $productId no encontrado")
                    continue
                }

                val currentStock = product.stockCongelador04
                val difference = currentStock - newPhysicalStock
                Log.d(TAG, "${product.name}: actual=$currentStock, nuevo=$newPhysicalStock, diferencia=$difference")

                if (kotlin.math.abs(difference) <= 0.01) {
                    Log.d(TAG, "Diferencia insignificante, saltando")
                    continue
                }

                if (difference > 0) {
                    Log.d(TAG, "▶️ REDUCIENDO stock: ${product.name}, cantidad: $difference kg")
                    processFifoReduction(product, difference, batch, currentUserName)
                } else {
                    Log.e(TAG, "❌ AUMENTO DE STOCK DETECTADO para ${product.name}: $difference kg - ESTO NO DEBERÍA OCURRIR")
                    throw Exception("No se permiten aumentos de stock en ajuste completo. Producto: ${product.name}")
                }

                val productRef = firestore.collection("products").document(productId)
                batch.update(productRef, mapOf(
                    "stockCongelador04" to newPhysicalStock,
                    "totalStock" to (product.stockMatriz + newPhysicalStock),
                    "updatedAt" to FieldValue.serverTimestamp(),
                    "lastUpdatedByName" to currentUserName
                ))

                val movement = StockMovement(
                    id = firestore.collection("stockMovements").document().id,
                    userId = currentUser.uid,
                    userName = currentUserName,
                    productId = product.id,
                    productName = product.name,
                    type = MovementType.AJUSTE_STOCK_C04,
                    quantity = kotlin.math.abs(difference),
                    locationFrom = Location.CONGELADOR_04,
                    locationTo = Location.EXTERNO,
                    reason = "AJUSTE COMPLETO C-04: Teórico: ${String.format(Locale.getDefault(), "%.2f", currentStock)} → Físico: ${String.format(Locale.getDefault(), "%.2f", newPhysicalStock)}",
                    stockAfterCongelador04 = newPhysicalStock,
                    stockAfterMatriz = product.stockMatriz,
                    stockAfterTotal = product.stockMatriz + newPhysicalStock,
                    timestamp = Date()
                )
                movements.add(movement)
            }

            Log.d(TAG, "Total movimientos a guardar: ${movements.size}")

            withContext(Dispatchers.Main) {
                _progressMessage.value = "Guardando ${movements.size} movimiento(s)..."
            }

            movements.forEach { movement ->
                batch.set(firestore.collection("stockMovements").document(movement.id), movement)
            }

            Log.d(TAG, "Ejecutando batch en Firestore...")
            val batchStartTime = System.currentTimeMillis()
            batch.commit().await()
            val batchEndTime = System.currentTimeMillis()
            Log.d(TAG, "✅ Batch ejecutado en ${batchEndTime - batchStartTime} ms")

            withContext(Dispatchers.Main) {
                _ajusteResult.value = AjusteResult.Success(movements.size)
            }
            true
        } catch (e: Exception) {
            Log.e(TAG, "❌ ERROR EN AJUSTE", e)
            withContext(Dispatchers.Main) {
                _ajusteResult.value = AjusteResult.Error(e.message ?: "Error desconocido")
            }
            false
        } finally {
            withContext(Dispatchers.Main) {
                _isLoading.value = false
                _progressMessage.value = ""
            }
        }
    }

    private suspend fun processFifoReduction(
        product: Product,
        quantityToReduce: Double,
        batch: WriteBatch,
        currentUserName: String
    ) {
        Log.d(TAG, "processFifoReduction: ${product.name}, reducir: $quantityToReduce kg")
        var remainingToReduce = quantityToReduce

        val lotsSnapshot = firestore.collection("inventoryLots")
            .whereEqualTo("productId", product.id)
            .whereEqualTo("location", Location.CONGELADOR_04)
            .whereEqualTo("isDepleted", false)
            .get()
            .await()

        val lots = lotsSnapshot.documents.mapNotNull { doc ->
            doc.toObject(StockLot::class.java)?.copy(id = doc.id)
        }.sortedBy { it.receivedAt ?: Date(0) }

        Log.d(TAG, "Lotes FIFO encontrados: ${lots.size}")

        for (lot in lots) {
            if (remainingToReduce <= 0.01) break

            val quantityFromThisLot = kotlin.math.min(lot.currentQuantity, remainingToReduce)
            val newLotQuantity = lot.currentQuantity - quantityFromThisLot
            val lotRef = firestore.collection("inventoryLots").document(lot.id)

            Log.d(TAG, "  Lote ${lot.id.takeLast(6)}: tomando $quantityFromThisLot de ${lot.currentQuantity}")

            batch.update(lotRef, mapOf(
                "currentQuantity" to newLotQuantity,
                "isDepleted" to (newLotQuantity <= 0.01)
            ))

            remainingToReduce -= quantityFromThisLot
        }

        if (remainingToReduce > 0.01) {
            Log.e(TAG, "Stock insuficiente para ${product.name}. Faltante: $remainingToReduce")
            throw Exception("Stock insuficiente en lotes para ${product.name}. Faltante: $remainingToReduce")
        }
    }
}

sealed class AjusteResult {
    data class Success(val movementsCount: Int) : AjusteResult()
    data class Error(val message: String) : AjusteResult()
}