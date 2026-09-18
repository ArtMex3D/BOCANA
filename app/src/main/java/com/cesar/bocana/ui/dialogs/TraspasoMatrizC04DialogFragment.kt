
package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
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
import com.cesar.bocana.ui.adapters.LotSelectionAdapter
import com.cesar.bocana.util.StockQuantityPolicy
import com.google.android.material.snackbar.Snackbar // <-- IMPORTACIÓN AÑADIDA
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.Date
import java.util.Locale

class TraspasoMatrizC04DialogFragment : DialogFragment() {

    private var product: Product? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val stockEpsilon = StockQuantityPolicy.MIN_USABLE_KG

    companion object {
        const val TAG = "TraspasoMatrizC04Dialog"
        private const val ARG_PRODUCT = "product_arg"

        fun newInstance(product: Product): TraspasoMatrizC04DialogFragment {
            return TraspasoMatrizC04DialogFragment().apply {
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
        val view = inflater.inflate(R.layout.dialog_traspaso_matriz_c04, null)

        val titleTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoTitleProduct)
        val subtitleTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoDirection)
        val recyclerView = view.findViewById<RecyclerView>(R.id.recyclerViewLotesTraspasoDialog)
        val progressBar = view.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
        val noLotesTextView = view.findViewById<TextView>(R.id.textViewNoLotesTraspasoDialog)
        val cantidadEditText = view.findViewById<EditText>(R.id.editTextCantidadTraspaso)
        val aceptarButton = view.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
        val cancelarButton = view.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)

        titleTextView.text = "Traspaso: ${currentProduct.name}"
        subtitleTextView.text = "Matriz  ----->  C-04"

        val lotAdapter = LotSelectionAdapter()
        recyclerView.layoutManager = LinearLayoutManager(context)
        recyclerView.adapter = lotAdapter

        builder.setView(view)
        val dialog = builder.create()

        cancelarButton.setOnClickListener { dialog.dismiss() }

        aceptarButton.setOnClickListener {
            val quantityToTraspasar = cantidadEditText.text.toString().toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            if (quantityToTraspasar == null || quantityToTraspasar + StockQuantityPolicy.FLOAT_EPSILON < stockEpsilon) {
                cantidadEditText.error = "Cantidad mínima: 0.10 kg"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            } else if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() && quantityToTraspasar > selectedLotsTotalNetQuantity + StockQuantityPolicy.FLOAT_EPSILON) {
                cantidadEditText.error = "Excede stock de lotes seleccionados (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                aceptarButton.isEnabled = false
                cancelarButton.isEnabled = false
                progressBar.isVisible = true

                performTraspasoMatrizToC04(currentProduct, quantityToTraspasar, selectedLotIds)
            }
        }

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
                val lotes = snapshot.toObjects(StockLot::class.java)
                    .filter { StockQuantityPolicy.isUsable(it.currentQuantity) }
                lotAdapter.submitList(lotes)
                recyclerView.isVisible = true
            }
        }.addOnFailureListener { e ->
            if (!isAdded) return@addOnFailureListener
            progressBar.isVisible = false
            noLotesTextView.text = "Error al cargar lotes."
            noLotesTextView.isVisible = true
            Log.e(TAG, "Error cargando lotes para traspaso", e)
        }

        return dialog
    }

    private fun performTraspasoMatrizToC04(
        product: Product,
        quantityToTraspasarTotal: Double,
        selectedLotIdsFromMatriz: List<String>
    ) {
        val user = auth.currentUser ?: return
        val currentUserName = user.displayName ?: user.email ?: "Unknown"
        val traspasoTimestamp = Date()

        lifecycleScope.launch {
            try {
                val productRef = firestore.collection("products").document(product.id)

                val lotesOrigenMatriz = selectedLotIdsFromMatriz.map { lotId ->
                    async(Dispatchers.IO) {
                        val lotSnapshot = firestore.collection("inventoryLots").document(lotId).get().await()
                        val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                        if (stockLot == null || stockLot.location != Location.MATRIZ || stockLot.isDepleted || !StockQuantityPolicy.isUsable(stockLot.currentQuantity)) {
                            throw FirebaseFirestoreException("El lote ${lotSnapshot.id} no es válido para traspaso.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        stockLot
                    }
                }.awaitAll().sortedBy { (it.originalReceivedAt ?: it.receivedAt)?.time ?: Long.MAX_VALUE }

                val totalDisponible = lotesOrigenMatriz.sumOf { it.currentQuantity }
                if (quantityToTraspasarTotal > totalDisponible + StockQuantityPolicy.FLOAT_EPSILON) {
                    throw FirebaseFirestoreException("Stock insuficiente en lotes seleccionados (${String.format("%.2f", totalDisponible)} ${product.unit})", FirebaseFirestoreException.Code.ABORTED)
                }

                val sublotesExistentesData = lotesOrigenMatriz.map { loteOrigen ->
                    async(Dispatchers.IO) {
                        val query = firestore.collection("inventoryLots")
                            .whereEqualTo("productId", loteOrigen.productId)
                            .whereEqualTo("location", Location.CONGELADOR_04)
                            .whereEqualTo("isDepleted", false)
                            .whereEqualTo("originalLotId", loteOrigen.id)
                            .limit(1)
                        val snapshot = query.get().await()
                        loteOrigen.id to if (!snapshot.isEmpty) snapshot.documents.first() else null
                    }
                }.awaitAll().toMap()

                var actualTransferredForMessage = 0.0

                firestore.runTransaction { transaction ->
                    val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                        ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

                    var restanteATraspasar = quantityToTraspasarTotal
                    var actualTransferredKg = 0.0
                    var closedResidualKg = 0.0
                    val idsOrigenAfectados = mutableListOf<String>()
                    val idsDestinoAfectados = mutableListOf<String>()
                    val newMovementRef = firestore.collection("stockMovements").document()

                    for (loteOrigen in lotesOrigenMatriz) {
                        if (restanteATraspasar <= StockQuantityPolicy.FLOAT_EPSILON) break

                        val withdrawal = StockQuantityPolicy.withdrawFromLot(loteOrigen.currentQuantity, restanteATraspasar)
                        if (withdrawal.actualTakenKg <= StockQuantityPolicy.FLOAT_EPSILON) continue

                        val requestedFromLot = kotlin.math.min(restanteATraspasar, loteOrigen.currentQuantity)
                        closedResidualKg += (withdrawal.actualTakenKg - requestedFromLot).coerceAtLeast(0.0)
                        val cantDeEsteLote = withdrawal.actualTakenKg

                        idsOrigenAfectados.add("${loteOrigen.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)}")
                        transaction.update(
                            firestore.collection("inventoryLots").document(loteOrigen.id),
                            mapOf(
                                "currentQuantity" to withdrawal.remainingKg,
                                "isDepleted" to withdrawal.depleted
                            )
                        )

                        val subloteExistenteSnapshot = sublotesExistentesData[loteOrigen.id]
                        if (subloteExistenteSnapshot != null) {
                            val subloteRef = subloteExistenteSnapshot.reference
                            transaction.update(
                                subloteRef,
                                mapOf(
                                    "currentQuantity" to FieldValue.increment(cantDeEsteLote),
                                    "isDepleted" to false
                                )
                            )
                            idsDestinoAfectados.add("${subloteRef.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)} (Exist.)")
                        } else {
                            val newLotRef = firestore.collection("inventoryLots").document()
                            val fechaOriginal = loteOrigen.originalReceivedAt ?: loteOrigen.receivedAt
                            val nuevoSublote = StockLot(
                                id = newLotRef.id,
                                productId = loteOrigen.productId,
                                productName = loteOrigen.productName,
                                unit = loteOrigen.unit,
                                location = Location.CONGELADOR_04,
                                receivedAt = traspasoTimestamp,
                                movementIdIn = newMovementRef.id,
                                initialQuantity = cantDeEsteLote,
                                currentQuantity = cantDeEsteLote,
                                isDepleted = false,
                                isPackaged = loteOrigen.isPackaged,
                                expirationDate = loteOrigen.expirationDate,
                                originalLotId = loteOrigen.originalLotId ?: loteOrigen.id,
                                originalReceivedAt = fechaOriginal,
                                originalSupplierId = loteOrigen.originalSupplierId ?: loteOrigen.supplierId,
                                originalSupplierName = loteOrigen.originalSupplierName ?: loteOrigen.supplierName,
                                originalLotNumber = loteOrigen.originalLotNumber ?: loteOrigen.lotNumber
                            )
                            transaction.set(newLotRef, nuevoSublote)
                            idsDestinoAfectados.add("${newLotRef.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)} (Nuevo)")
                        }

                        actualTransferredKg += cantDeEsteLote
                        restanteATraspasar = (restanteATraspasar - cantDeEsteLote).coerceAtLeast(0.0)
                    }

                    if (restanteATraspasar > StockQuantityPolicy.FLOAT_EPSILON) {
                        throw FirebaseFirestoreException(
                            "No fue posible completar el traspaso. Faltan ${String.format("%.3f", restanteATraspasar)} kg",
                            FirebaseFirestoreException.Code.ABORTED
                        )
                    }

                    val newStockMatriz = StockQuantityPolicy.normalizeLotQuantity(currentProduct.stockMatriz - actualTransferredKg)
                    val newStockC04 = StockQuantityPolicy.normalizeLotQuantity(currentProduct.stockCongelador04 + actualTransferredKg)
                    val stableTotalStock = StockQuantityPolicy.normalizeLotQuantity(newStockMatriz + newStockC04)
                    transaction.update(productRef, mapOf(
                        "stockMatriz" to newStockMatriz,
                        "stockCongelador04" to newStockC04,
                        "totalStock" to stableTotalStock,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "lastUpdatedByName" to currentUserName
                    ))

                    val closeNote = if (closedResidualKg > StockQuantityPolicy.FLOAT_EPSILON) {
                        " | Cierre automático de residuo: ${String.format("%.3f", closedResidualKg)} kg"
                    } else ""

                    val movement = StockMovement(
                        id = newMovementRef.id, userId = user.uid, userName = currentUserName,
                        productId = product.id, productName = product.name, type = MovementType.TRASPASO_M_C04,
                        quantity = actualTransferredKg, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04,
                        reason = "Origen(M): ${idsOrigenAfectados.joinToString()}; Destino(C04): ${idsDestinoAfectados.joinToString()}$closeNote",
                        stockAfterMatriz = newStockMatriz,
                        stockAfterCongelador04 = newStockC04,
                        stockAfterTotal = stableTotalStock, timestamp = traspasoTimestamp,
                        affectedLotIds = lotesOrigenMatriz.map { it.id }
                    )
                    transaction.set(newMovementRef, movement)
                    actualTransferredForMessage = actualTransferredKg
                }.await()

                if (isAdded) {
                    Snackbar.make(
                        requireActivity().findViewById(android.R.id.content),
                        "Traspaso realizado: ${String.format("%.2f", actualTransferredForMessage)} ${product.unit}",
                        Snackbar.LENGTH_LONG
                    ).show()
                    dismiss()
                }

            } catch (e: Exception) {
                if (isAdded) {
                    val errorMessage = (e as? FirebaseFirestoreException)?.message ?: "Error inesperado: ${e.message}"
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), errorMessage, Snackbar.LENGTH_LONG).show()
                    Log.e(TAG, "Error en la operación de traspaso", e)
                    dismiss()
                }
            }
        }
    }

}

