package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.AutoCompleteTextView
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
import com.cesar.bocana.data.model.*
import com.cesar.bocana.helpers.NotificationTriggerHelper
import com.cesar.bocana.ui.adapters.LotSelectionAdapter
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale

class SalidaDevolucionLotesDialogFragment : DialogFragment() {

    private var product: Product? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val stockEpsilon = 0.1

    companion object {
        const val TAG = "SalidaDevolucionLotesDialog"
        private const val ARG_PRODUCT = "product_arg"

        fun newInstance(product: Product): SalidaDevolucionLotesDialogFragment {
            return SalidaDevolucionLotesDialogFragment().apply {
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
        val view = inflater.inflate(R.layout.dialog_salida_devolucion_lotes, null)

        val titleView = TextView(requireContext()).apply {
            text = "Devolución: ${currentProduct.name}"
            setPadding(60, 40, 60, 20)
            textSize = 18f
            setTextColor(ContextCompat.getColor(requireContext(), android.R.color.black))
        }

        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewLotesDevolucionDialog)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLotesDevolucionDialog)
        val noLotesTextView = view.findViewById<TextView>(R.id.textViewNoLotesDevolucionDialog)
        val cantidadEditText = view.findViewById<EditText>(R.id.editTextCantidadDevolucion)
        val proveedorLayout = view.findViewById<TextInputLayout>(R.id.textFieldLayoutProveedorDevolucion)
        val proveedorAutoComplete = view.findViewById<AutoCompleteTextView>(R.id.autoCompleteTextViewProveedorDevolucion)
        val motivoLayout = view.findViewById<TextInputLayout>(R.id.textFieldLayoutMotivoDevolucion)
        val motivoEditText = view.findViewById<EditText>(R.id.editTextMotivoDevolucion)
        val aceptarButton = view.findViewById<Button>(R.id.buttonDialogDevolucionAceptar)
        val cancelarButton = view.findViewById<Button>(R.id.buttonDialogDevolucionCancelar)

        val lotAdapter = LotSelectionAdapter()
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = lotAdapter

        builder.setCustomTitle(titleView).setView(view)
        val dialog = builder.create()

        cancelarButton.setOnClickListener { dialog.dismiss() }

        aceptarButton.setOnClickListener {
            val quantity = cantidadEditText.text.toString().toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotal = lotAdapter.getSelectedLotsTotalQuantity()
            val supplierName = proveedorAutoComplete.text.toString().trim()
            val reason = motivoEditText.text.toString().trim()

            if (validateInputs(quantity, selectedLotIds, selectedLotsTotal, supplierName, reason, cantidadEditText, proveedorLayout, motivoLayout)) {
                performSalidaDevolucion(currentProduct, quantity!!, selectedLotIds, supplierName, reason)
            }
        }

        loadInitialData(lotAdapter, proveedorAutoComplete, progressBar, noLotesTextView, recyclerView)

        return dialog
    }

    private fun validateInputs(
        quantity: Double?,
        selectedLotIds: List<String>,
        selectedLotsTotal: Double,
        supplierName: String,
        reason: String,
        cantidadEditText: EditText,
        proveedorLayout: TextInputLayout,
        motivoLayout: TextInputLayout
    ): Boolean {
        var isValid = true
        if (quantity == null || quantity <= 0.0) {
            cantidadEditText.error = "Cantidad debe ser mayor a 0"
            isValid = false
        }
        if (selectedLotIds.isEmpty() && (view?.findViewById<RecyclerView>(R.id.recyclerViewLotesDevolucionDialog)?.adapter?.itemCount ?: 0) > 0) {
            Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
            isValid = false
        }
        if (quantity != null && quantity > selectedLotsTotal + stockEpsilon) {
            cantidadEditText.error = "Excede stock de lotes seleccionados (${String.format("%.2f", selectedLotsTotal)})"
            isValid = false
        }
        if (supplierName.isBlank()) {
            proveedorLayout.error = "Proveedor es obligatorio"
            isValid = false
        } else {
            proveedorLayout.error = null
        }
        if (reason.isBlank()) {
            motivoLayout.error = "Motivo es obligatorio"
            isValid = false
        } else {
            motivoLayout.error = null
        }
        return isValid
    }

    private fun loadInitialData(
        lotAdapter: LotSelectionAdapter,
        proveedorAutoComplete: AutoCompleteTextView,
        progressBar: ProgressBar,
        noLotesTextView: TextView,
        recyclerView: RecyclerView
    ) {
        progressBar.isVisible = true
        lifecycleScope.launch {
            try {
                val suppliersDeferred = async { firestore.collection("suppliers").whereEqualTo("isActive", true).orderBy("name").get().await() }
                val lotsDeferred = async {
                    firestore.collection("inventoryLots")
                        .whereEqualTo("productId", product!!.id)
                        .whereEqualTo("location", Location.MATRIZ)
                        .whereEqualTo("isDepleted", false)
                        .orderBy("receivedAt", Query.Direction.ASCENDING)
                        .get().await()
                }

                val suppliersSnapshot = suppliersDeferred.await()
                val lotsSnapshot = lotsDeferred.await()

                if (!isAdded) return@launch

                val suppliers = suppliersSnapshot.toObjects(Supplier::class.java)
                val supplierNames = suppliers.map { it.name }
                val supplierAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, supplierNames)
                proveedorAutoComplete.setAdapter(supplierAdapter)

                if (lotsSnapshot.isEmpty) {
                    noLotesTextView.text = "No hay lotes con stock en Matriz."
                    noLotesTextView.isVisible = true
                    recyclerView.isVisible = false
                } else {
                    val lotes = lotsSnapshot.toObjects(StockLot::class.java)
                    lotAdapter.submitList(lotes)
                    recyclerView.isVisible = true
                    noLotesTextView.isVisible = false
                }
            } catch (e: Exception) {
                if (!isAdded) return@launch
                Log.e(TAG, "Error cargando datos para devolución", e)
                noLotesTextView.text = "Error al cargar datos."
                noLotesTextView.isVisible = true
            } finally {
                if (isAdded) progressBar.isVisible = false
            }
        }
    }

    private fun performSalidaDevolucion(
        product: Product,
        quantityToDevolver: Double,
        selectedLotIds: List<String>,
        proveedorName: String,
        motivo: String
    ) {
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"

        firestore.runTransaction { transaction ->
            val productRef = firestore.collection("products").document(product.id)
            val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado", FirebaseFirestoreException.Code.ABORTED)

            val lotObjects = selectedLotIds.map { lotId ->
                val lotSnapshot = transaction.get(firestore.collection("inventoryLots").document(lotId))
                lotSnapshot.toObject(StockLot::class.java)
                    ?: throw FirebaseFirestoreException("Lote no encontrado", FirebaseFirestoreException.Code.ABORTED)
            }.sortedBy { it.receivedAt }

            var remainingToDevolver = quantityToDevolver
            val affectedLotDetails = mutableListOf<String>()

            for (lot in lotObjects) {
                if (remainingToDevolver <= stockEpsilon) break
                val qtyFromThisLot = kotlin.math.min(remainingToDevolver, lot.currentQuantity)
                if (qtyFromThisLot > stockEpsilon) {
                    val newLotQty = lot.currentQuantity - qtyFromThisLot
                    transaction.update(
                        firestore.collection("inventoryLots").document(lot.id),
                        "currentQuantity", newLotQty,
                        "isDepleted", newLotQty <= stockEpsilon
                    )
                    remainingToDevolver -= qtyFromThisLot
                    affectedLotDetails.add("${lot.id.takeLast(4)}:${String.format("%.2f", qtyFromThisLot)}")
                }
            }

            val newStockMatriz = currentProduct.stockMatriz - quantityToDevolver
            val newTotalStock = currentProduct.totalStock - quantityToDevolver
            transaction.update(productRef, mapOf(
                "stockMatriz" to newStockMatriz,
                "totalStock" to newTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            val newMovementRef = firestore.collection("stockMovements").document()
            val movement = StockMovement(
                id = newMovementRef.id, userId = user.uid, userName = currentUserName,
                productId = product.id, productName = product.name, type = MovementType.SALIDA_DEVOLUCION,
                quantity = quantityToDevolver, locationFrom = Location.MATRIZ, locationTo = Location.PROVEEDOR,
                reason = "A $proveedorName. Motivo: $motivo. Lotes: ${affectedLotDetails.joinToString()}",
                stockAfterMatriz = newStockMatriz, stockAfterCongelador04 = currentProduct.stockCongelador04,
                stockAfterTotal = newTotalStock, timestamp = Date()
            )
            transaction.set(newMovementRef, movement)

            val newDevolucionRef = firestore.collection("pendingDevoluciones").document()
            val devolucionPendiente = DevolucionPendiente(
                id = newDevolucionRef.id, productId = product.id, productName = product.name,
                quantity = quantityToDevolver, unit = product.unit, provider = proveedorName,
                reason = motivo, userId = user.uid, registeredAt = Date(), status = DevolucionStatus.PENDIENTE
            )
            transaction.set(newDevolucionRef, devolucionPendiente)

            currentProduct.copy(stockMatriz = newStockMatriz, totalStock = newTotalStock)
        }.addOnSuccessListener { updatedProduct ->
            if (!isAdded) return@addOnSuccessListener
            Snackbar.make(requireActivity().findViewById(android.R.id.content), "Devolución registrada.", Snackbar.LENGTH_LONG).show()
            updatedProduct?.let {
                lifecycleScope.launch { NotificationTriggerHelper.triggerLowStockNotification(it) }
            }
            dismiss()
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            Snackbar.make(requireActivity().findViewById(android.R.id.content), "Error en devolución: ${e.message}", Snackbar.LENGTH_LONG).show()
            Log.e(TAG, "Error en transacción de devolución", e)
            dismiss()
        }
    }
}
