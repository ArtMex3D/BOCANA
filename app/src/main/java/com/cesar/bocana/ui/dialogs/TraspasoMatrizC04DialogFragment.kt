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
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.Query
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
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
    private val stockEpsilon = 0.1

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

        val titleTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoTitleInfo)
        val subtitleTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoSubtitle)
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
            if (quantityToTraspasar == null || quantityToTraspasar <= 0.0) {
                cantidadEditText.error = "Cantidad > 0.0"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen", Toast.LENGTH_SHORT).show()
                validationError = true
            } else if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() && quantityToTraspasar > selectedLotsTotalNetQuantity + stockEpsilon) {
                cantidadEditText.error = "Excede stock de lotes seleccionados (${String.format("%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                // Se deshabilita el botón para evitar clics duplicados mientras la operación se ejecuta
                aceptarButton.isEnabled = false
                cancelarButton.isEnabled = false
                progressBar.isVisible = true

                // La función performTraspasoMatrizToC04 ahora se encarga de cerrar el diálogo
                performTraspasoMatrizToC04(currentProduct, quantityToTraspasar, selectedLotIds)
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

                // --- MEJORA: LEER DATOS NECESARIOS ANTES DE LA TRANSACCIÓN ---
                val lotesOrigenMatriz = selectedLotIdsFromMatriz.map { lotId ->
                    async {
                        val lotSnapshot = firestore.collection("inventoryLots").document(lotId).get().await()
                        val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                        if (stockLot == null || stockLot.location != Location.MATRIZ || stockLot.isDepleted) {
                            throw FirebaseFirestoreException("El lote ${lotSnapshot.id} no es válido para traspaso.", FirebaseFirestoreException.Code.ABORTED)
                        }
                        stockLot
                    }
                }.awaitAll().sortedBy { it.receivedAt ?: Date(0) }

                val totalDisponible = lotesOrigenMatriz.sumOf { it.currentQuantity }
                if (quantityToTraspasarTotal > totalDisponible + stockEpsilon) {
                    throw FirebaseFirestoreException("Stock insuficiente en lotes seleccionados (${String.format("%.2f", totalDisponible)} ${product.unit})", FirebaseFirestoreException.Code.ABORTED)
                }

                // --- MEJORA: BUSCAR SUBLOTES EXISTENTES ---
                val sublotesExistentesData = lotesOrigenMatriz.map { loteOrigen ->
                    async {
                        val query = firestore.collection("inventoryLots")
                            .whereEqualTo("productId", loteOrigen.productId)
                            .whereEqualTo("location", Location.CONGELADOR_04)
                            .whereEqualTo("isDepleted", false)
                            .whereEqualTo("originalLotId", loteOrigen.id) // La clave para agrupar
                            .limit(1)
                        val snapshot = query.get().await()
                        loteOrigen.id to if (snapshot.documents.isNotEmpty()) snapshot.documents.first() else null
                    }
                }.awaitAll().toMap()

                // --- TRANSACCIÓN DE ESCRITURA ---
                firestore.runTransaction { transaction ->
                    val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                        ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

                    var restanteATraspasar = quantityToTraspasarTotal
                    val idsOrigenAfectados = mutableListOf<String>()
                    val idsDestinoAfectados = mutableListOf<String>()
                    val newMovementRef = firestore.collection("stockMovements").document()

                    for (loteOrigen in lotesOrigenMatriz) {
                        if (restanteATraspasar <= stockEpsilon) break
                        val cantDeEsteLote = kotlin.math.min(loteOrigen.currentQuantity, restanteATraspasar)

                        if (cantDeEsteLote > stockEpsilon) {
                            idsOrigenAfectados.add("${loteOrigen.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)}")
                            val nuevaCantOrigen = loteOrigen.currentQuantity - cantDeEsteLote
                            transaction.update(firestore.collection("inventoryLots").document(loteOrigen.id), mapOf(
                                "currentQuantity" to nuevaCantOrigen,
                                "isDepleted" to (nuevaCantOrigen <= stockEpsilon)
                            ))

                            val subloteExistenteSnapshot = sublotesExistentesData[loteOrigen.id]

                            // --- MEJORA: LÓGICA DE AGRUPACIÓN ---
                            if (subloteExistenteSnapshot != null) {
                                val subloteRef = subloteExistenteSnapshot.reference
                                // Se usa FieldValue.increment para sumar de forma segura
                                transaction.update(subloteRef, "currentQuantity", FieldValue.increment(cantDeEsteLote))
                                idsDestinoAfectados.add("${subloteRef.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)} (Exist.)")
                            } else {
                                val newLotRef = firestore.collection("inventoryLots").document()
                                val nuevoSublote = StockLot(
                                    id = newLotRef.id, productId = loteOrigen.productId, productName = loteOrigen.productName,
                                    unit = loteOrigen.unit, location = Location.CONGELADOR_04, receivedAt = traspasoTimestamp,
                                    movementIdIn = newMovementRef.id, initialQuantity = cantDeEsteLote, currentQuantity = cantDeEsteLote,
                                    isPackaged = loteOrigen.isPackaged, expirationDate = loteOrigen.expirationDate,
                                    originalLotId = loteOrigen.id, originalReceivedAt = loteOrigen.receivedAt,
                                    originalSupplierId = loteOrigen.supplierId, originalSupplierName = loteOrigen.supplierName,
                                    originalLotNumber = loteOrigen.lotNumber
                                )
                                transaction.set(newLotRef, nuevoSublote)
                                idsDestinoAfectados.add("${newLotRef.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)} (Nuevo)")
                            }
                            restanteATraspasar -= cantDeEsteLote
                        }
                    }

                    // Actualización de stocks en el producto principal
                    transaction.update(productRef, mapOf(
                        "stockMatriz" to FieldValue.increment(-quantityToTraspasarTotal),
                        "stockCongelador04" to FieldValue.increment(quantityToTraspasarTotal),
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "lastUpdatedByName" to currentUserName
                    ))

                    // Creación del registro de movimiento
                    val movement = StockMovement(
                        id = newMovementRef.id, userId = user.uid, userName = currentUserName,
                        productId = product.id, productName = product.name, type = MovementType.TRASPASO_M_C04,
                        quantity = quantityToTraspasarTotal, locationFrom = Location.MATRIZ, locationTo = Location.CONGELADOR_04,
                        reason = "Origen(M): ${idsOrigenAfectados.joinToString()}; Destino(C04): ${idsDestinoAfectados.joinToString()}",
                        stockAfterMatriz = currentProduct.stockMatriz - quantityToTraspasarTotal,
                        stockAfterCongelador04 = currentProduct.stockCongelador04 + quantityToTraspasarTotal,
                        stockAfterTotal = currentProduct.totalStock, timestamp = traspasoTimestamp
                    )
                    transaction.set(newMovementRef, movement)
                }.await() // --- FIX: ESPERAR A QUE LA TRANSACCIÓN TERMINE ---

                // --- ESTE CÓDIGO AHORA SE EJECUTA DESPUÉS DEL ÉXITO ---
                if (isAdded) {
                    Toast.makeText(context, "Traspaso realizado con éxito.", Toast.LENGTH_SHORT).show()
                    dismiss() // Cierra el diálogo solo si todo salió bien
                }

            } catch (e: Exception) {
                // --- MANEJO DE ERRORES MEJORADO ---
                if (isAdded) {
                    val errorMessage = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                        e.message ?: "Error de datos durante el traspaso."
                    } else {
                        "Error inesperado: ${e.message}"
                    }
                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                    Log.e(TAG, "Error en la operación de traspaso", e)

                    // Si el diálogo todavía existe, reactivar los botones para un nuevo intento
                    dialog?.let {
                        val aceptarButton = it.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
                        val cancelarButton = it.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)
                        val progressBar = it.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
                        aceptarButton?.isEnabled = true
                        cancelarButton?.isEnabled = true
                        progressBar?.isVisible = false
                    }
                }
            }
        }
    }
}