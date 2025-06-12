package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.MovementType
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.data.model.StockMovement
import com.cesar.bocana.helpers.NotificationTriggerHelper
import com.cesar.bocana.ui.adapters.LotSelectionAdapter
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.util.Date
import java.util.Locale

class SalidaConsumoLotesDialogFragment : DialogFragment() {

    private var product: Product? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val stockEpsilon = 0.1

    companion object {
        const val TAG = "SalidaConsumoLotesDialog"
        private const val ARG_PRODUCT = "product_arg"

        fun newInstance(product: Product): SalidaConsumoLotesDialogFragment {
            return SalidaConsumoLotesDialogFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_PRODUCT, product)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firestore = Firebase.firestore
        product = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_PRODUCT, Product::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_PRODUCT)
        }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val currentProduct = product ?: run {
            Toast.makeText(context, "Error: Producto no encontrado.", Toast.LENGTH_SHORT).show()
            return super.onCreateDialog(savedInstanceState)
        }

        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_salida_consumo_lotes, null)

        val titleView = TextView(requireContext()).apply {
            text = "Consumo: ${currentProduct.name}"
            setPadding(60, 40, 60, 20)
            textSize = 18f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewLotesDialog)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLotesDialog)
        val noLotesTextView = view.findViewById<TextView>(R.id.textViewNoLotesDialog)
        val cantidadEditText = view.findViewById<EditText>(R.id.editTextCantidadConsumo)
        val aceptarButton = view.findViewById<Button>(R.id.buttonDialogAceptar)
        val cancelarButton = view.findViewById<Button>(R.id.buttonDialogCancelar)

        val lotAdapter = LotSelectionAdapter()
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = lotAdapter

        builder.setCustomTitle(titleView).setView(view)
        val dialog = builder.create()

        cancelarButton.setOnClickListener { dialog.dismiss() }

        aceptarButton.setOnClickListener {
            val quantityToConsume = cantidadEditText.text.toString().toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            if (quantityToConsume == null || quantityToConsume <= 0.0) {
                cantidadEditText.error = "Cantidad > 0.0"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            } else if (quantityToConsume != null && lotAdapter.currentList.isNotEmpty() && quantityToConsume > selectedLotsTotalNetQuantity + stockEpsilon) {
                cantidadEditText.error = "Excede stock de lotes seleccionados (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToConsume != null) {
                performSalidaConsumo(currentProduct, quantityToConsume, selectedLotIds)
                dialog.dismiss()
            }
        }

        // Cargar lotes
        progressBar.isVisible = true
        noLotesTextView.isVisible = false
        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", currentProduct.id)
            .whereEqualTo("location", Location.MATRIZ)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get().addOnSuccessListener { snapshot ->
            if (!isAdded) return@addOnSuccessListener
            progressBar.isVisible = false
            if (snapshot.isEmpty) {
                noLotesTextView.text = "No hay lotes con stock en Matriz."
                noLotesTextView.isVisible = true
            } else {
                val lotes = snapshot.toObjects(StockLot::class.java).map { it.copy(id = it.id) }
                lotAdapter.submitList(lotes)
                recyclerView.isVisible = true
            }
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            progressBar.isVisible = false
            noLotesTextView.text = "Error al cargar lotes."
            noLotesTextView.isVisible = true
            Log.e(TAG, "Error cargando lotes para consumo", e)
        }

        return dialog
    }

    private fun performSalidaConsumo(product: Product, quantityToConsume: Double, selectedLotIds: List<String>) {
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"

        val productRef = firestore.collection("products").document(product.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        firestore.runTransaction { transaction ->
            val productSnapshot = transaction.get(productRef)
            val currentProduct = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado", FirebaseFirestoreException.Code.ABORTED)

            val lotObjects = selectedLotIds.map { lotId ->
                val lotSnapshot = transaction.get(firestore.collection("inventoryLots").document(lotId))
                lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                    ?: throw FirebaseFirestoreException("Lote no encontrado", FirebaseFirestoreException.Code.ABORTED)
            }.sortedBy { it.receivedAt }

            val totalSelectedStock = lotObjects.sumOf { it.currentQuantity }
            if (quantityToConsume > totalSelectedStock + stockEpsilon) {
                throw FirebaseFirestoreException("Stock insuficiente en lotes seleccionados", FirebaseFirestoreException.Code.ABORTED)
            }

            var remainingToConsume = quantityToConsume
            val affectedLotDetails = mutableListOf<String>()

            for (lot in lotObjects) {
                if (remainingToConsume <= stockEpsilon) break
                val quantityFromThisLot = kotlin.math.min(remainingToConsume, lot.currentQuantity)
                if (quantityFromThisLot > stockEpsilon) {
                    val newLotQuantity = lot.currentQuantity - quantityFromThisLot
                    transaction.update(
                        firestore.collection("inventoryLots").document(lot.id),
                        "currentQuantity", newLotQuantity,
                        "isDepleted", newLotQuantity <= stockEpsilon
                    )
                    remainingToConsume -= quantityFromThisLot
                    affectedLotDetails.add("${lot.id.takeLast(4)}:${String.format("%.2f", quantityFromThisLot)}")
                }
            }

            val newStockMatriz = currentProduct.stockMatriz - quantityToConsume
            val newTotalStock = currentProduct.totalStock - quantityToConsume
            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatriz,
                "totalStock" to newTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            val movement = StockMovement(
                id = newMovementRef.id, userId = user.uid, userName = currentUserName,
                productId = product.id, productName = product.name, type = MovementType.SALIDA_CONSUMO,
                quantity = quantityToConsume, locationFrom = Location.MATRIZ, locationTo = Location.EXTERNO,
                reason = "Lotes: ${affectedLotDetails.joinToString()}",
                stockAfterMatriz = newStockMatriz, stockAfterCongelador04 = currentProduct.stockCongelador04,
                stockAfterTotal = newTotalStock, timestamp = Date()
            )
            transaction.set(newMovementRef, movement)

            currentProduct.copy(stockMatriz = newStockMatriz, totalStock = newTotalStock)
        }.addOnSuccessListener { updatedProduct ->
            Toast.makeText(context, "Salida por consumo registrada.", Toast.LENGTH_SHORT).show()
            updatedProduct?.let {
                lifecycleScope.launch { NotificationTriggerHelper.triggerLowStockNotification(it) }
            }
        }.addOnFailureListener { e ->
            Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
            Log.e(TAG, "Error en transacción de salida consumo", e)
        }
    }
}