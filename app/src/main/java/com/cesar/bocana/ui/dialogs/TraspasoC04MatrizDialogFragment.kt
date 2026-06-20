package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.content.DialogInterface
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.View
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
import com.cesar.bocana.ui.products.ProductListFragment
import com.google.android.material.snackbar.Snackbar
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

class TraspasoC04MatrizDialogFragment : DialogFragment() {

    private var product: Product? = null
    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val stockEpsilon = 0.1

    companion object {
        const val TAG = "TraspasoC04MatrizDialog"
        private const val ARG_PRODUCT = "product_arg"

        fun newInstance(product: Product): TraspasoC04MatrizDialogFragment {
            return TraspasoC04MatrizDialogFragment().apply {
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
        Log.d(TAG, "🔵 onCreateDialog INICIO")

        val currentProduct = product ?: run {
            Log.e(TAG, "❌ Producto es NULL")
            Toast.makeText(context, "Error: Producto no encontrado.", Toast.LENGTH_SHORT).show()
            return super.onCreateDialog(savedInstanceState)
        }
        Log.d(TAG, "✅ Producto: ${currentProduct.name}")

        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_traspaso_lotes, null)

        val titleProductTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoTitleProduct)
        val directionTextView = view.findViewById<TextView>(R.id.textViewDialogTraspasoDirection)
        val recyclerViewLotes = view.findViewById<RecyclerView>(R.id.recyclerViewLotesTraspasoDialog)
        val progressBarLotes = view.findViewById<ProgressBar>(R.id.progressBarLotesTraspasoDialog)
        val textViewNoLotes = view.findViewById<TextView>(R.id.textViewNoLotesTraspasoDialog)
        val inputQuantityNet = view.findViewById<EditText>(R.id.editTextCantidadTraspaso)
        val buttonAceptar = view.findViewById<Button>(R.id.buttonDialogTraspasoAceptar)
        val buttonCancelar = view.findViewById<Button>(R.id.buttonDialogTraspasoCancelar)

        titleProductTextView.text = currentProduct.name
        directionTextView.text = "Origen: C-04  --->  Destino: MATRIZ"
        buttonAceptar.text = "Regresar"

        val lotAdapter = LotSelectionAdapter()
        recyclerViewLotes.layoutManager = LinearLayoutManager(context)
        recyclerViewLotes.adapter = lotAdapter

        builder.setView(view)
        val dialog = builder.create()
        dialog.setCanceledOnTouchOutside(true)

        buttonCancelar.setOnClickListener { dialog.dismiss() }

        buttonAceptar.setOnClickListener {
            if (lotAdapter.currentList.isEmpty()) {
                Toast.makeText(context, "No hay lotes disponibles en C-04 para traspasar.", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            val quantityToTraspasar = inputQuantityNet.text.toString().toDoubleOrNull()
            val selectedLotIds = lotAdapter.getSelectedLotIds()
            val selectedLotsTotalNetQuantity = lotAdapter.getSelectedLotsTotalQuantity()

            var validationError = false
            inputQuantityNet.error = null

            if (quantityToTraspasar == null || quantityToTraspasar <= stockEpsilon) {
                inputQuantityNet.error = "Cantidad debe ser > ${String.format(Locale.getDefault(), "%.2f", stockEpsilon)}"
                validationError = true
            }
            if (selectedLotIds.isEmpty() && lotAdapter.currentList.isNotEmpty()) {
                Toast.makeText(context, "Debes seleccionar al menos un lote origen de C-04", Toast.LENGTH_SHORT).show()
                validationError = true
            }
            if (quantityToTraspasar != null && lotAdapter.currentList.isNotEmpty() &&
                (quantityToTraspasar - selectedLotsTotalNetQuantity > stockEpsilon)) {
                inputQuantityNet.error = "Excede stock de lotes seleccionados en C-04 (${String.format(Locale.getDefault(), "%.2f", selectedLotsTotalNetQuantity)})"
                validationError = true
            }

            if (!validationError && quantityToTraspasar != null) {
                if (selectedLotIds.isNotEmpty()) {
                    buttonAceptar.isEnabled = false
                    buttonCancelar.isEnabled = false
                    progressBarLotes.isVisible = true

                    performTraspasoC04ToMatriz(
                        currentProduct,
                        quantityToTraspasar,
                        selectedLotIds,
                        dialog,
                        buttonAceptar,
                        buttonCancelar,
                        progressBarLotes
                    )
                }
            }
        }

        progressBarLotes.visibility = View.VISIBLE
        textViewNoLotes.visibility = View.GONE
        recyclerViewLotes.visibility = View.GONE

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", currentProduct.id)
            .whereEqualTo("location", Location.CONGELADOR_04)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get().addOnSuccessListener { snapshot ->
            Log.d(TAG, "🔵 Carga de lotes EXITOSA - tamaño: ${snapshot?.size() ?: 0}")

            if (!isAdded) {
                Log.w(TAG, "⚠️ Fragment no está añadido, ignorando")
                return@addOnSuccessListener
            }

            progressBarLotes.visibility = View.GONE

            if (snapshot != null && !snapshot.isEmpty) {
                Log.d(TAG, "✅ Hay ${snapshot.size()} lotes disponibles")
                val loadedLots = snapshot.documents.mapNotNull { doc ->
                    try {
                        doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                    } catch (e: Exception) {
                        Log.e(TAG, "❌ Error convirtiendo lote: ${e.message}")
                        null
                    }
                }
                lotAdapter.submitList(loadedLots)
                textViewNoLotes.visibility = View.GONE
                recyclerViewLotes.visibility = View.VISIBLE
            } else {
                Log.w(TAG, "⚠️ NO hay lotes disponibles")
                textViewNoLotes.text = "No hay lotes disponibles en C-04 para este producto."
                textViewNoLotes.visibility = View.VISIBLE
                recyclerViewLotes.visibility = View.GONE
                if (!buttonCancelar.isEnabled) {
                    Log.d(TAG, "✅ Habilitando botón Cancelar porque estaba deshabilitado")
                    buttonCancelar.isEnabled = true
                }
            }
        }.addOnFailureListener { e ->
            Log.e(TAG, "❌ ERROR cargando lotes: ${e.message}", e)
            if (!isAdded) return@addOnFailureListener
            progressBarLotes.visibility = View.GONE
            textViewNoLotes.text = "Error al cargar lotes de C-04."
            textViewNoLotes.visibility = View.VISIBLE
            recyclerViewLotes.visibility = View.GONE
            buttonCancelar.isEnabled = true
            Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
        }

        Log.d(TAG, "🔵 Diálogo configurado, retornando")
        return dialog
    }

    // ✅ VERSIÓN CORREGIDA - Busca el ProductListFragment en la Activity
    override fun onDismiss(dialog: DialogInterface) {
        super.onDismiss(dialog)
        Log.d(TAG, "🔴 onDismiss() llamado")

        try {
            // Buscar el ProductListFragment en la Activity
            val fragment = requireActivity().supportFragmentManager
                .findFragmentByTag("ProductListFragment")

            if (fragment is ProductListFragment) {
                fragment.onDialogClosed()
                Log.d(TAG, "✅ onDialogClosed() llamado en ProductListFragment")
            } else {
                Log.w(TAG, "⚠️ No se encontró ProductListFragment, buscando en backstack...")

                // Fallback: buscar todos los fragmentos
                val fragments = requireActivity().supportFragmentManager.fragments
                for (f in fragments) {
                    if (f is ProductListFragment) {
                        f.onDialogClosed()
                        Log.d(TAG, "✅ onDialogClosed() llamado en ProductListFragment (encontrado en lista)")
                        break
                    }
                }

                // Si aún no se encontró, forzar reset desde la Activity
                Log.w(TAG, "⚠️ Forzando reset desde Activity")
                val activity = activity
                if (activity is com.cesar.bocana.ui.main.MainActivity) {
                    // Buscar el fragment en la Activity
                    val fragmentManager = activity.supportFragmentManager
                    val fragmentByTag = fragmentManager.findFragmentByTag("ProductListFragment")
                    if (fragmentByTag is ProductListFragment) {
                        fragmentByTag.onDialogClosed()
                        Log.d(TAG, "✅ onDialogClosed() llamado desde Activity")
                    } else {
                        Log.e(TAG, "❌ No se pudo encontrar ProductListFragment en ninguna parte")
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "❌ Error en onDismiss: ${e.message}", e)
        }
    }

    private fun performTraspasoC04ToMatriz(
        productArgument: Product,
        quantityToTraspasarTotal: Double,
        selectedLotIdsFromC04: List<String>,
        dialog: Dialog,
        aceptarButton: Button,
        cancelarButton: Button,
        progressBar: ProgressBar
    ) {
        val currentUser = auth.currentUser ?: run {
            Toast.makeText(context, "Error de autenticación.", Toast.LENGTH_SHORT).show()
            dialog.dismiss()
            return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"
        val traspasoTimestamp = Date()

        val productRef = firestore.collection("products").document(productArgument.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        lifecycleScope.launch {
            try {
                val lotesOrigenC04 = selectedLotIdsFromC04.map { lotId ->
                    async(Dispatchers.IO) {
                        val lotSnapshot = firestore.collection("inventoryLots").document(lotId).get().await()
                        val stockLot = lotSnapshot.toObject(StockLot::class.java)?.copy(id = lotSnapshot.id)
                        if (stockLot == null || stockLot.location != Location.CONGELADOR_04 || stockLot.isDepleted) {
                            throw FirebaseFirestoreException(
                                "Lote inválido para traspaso C-04: ${lotId}",
                                FirebaseFirestoreException.Code.ABORTED
                            )
                        }
                        stockLot
                    }
                }.awaitAll().sortedBy { it.receivedAt ?: Date(0) }

                val totalDisponible = lotesOrigenC04.sumOf { it.currentQuantity }
                if (quantityToTraspasarTotal > totalDisponible + stockEpsilon) {
                    throw FirebaseFirestoreException(
                        "Stock insuficiente en lotes seleccionados (${String.format("%.2f", totalDisponible)} ${productArgument.unit})",
                        FirebaseFirestoreException.Code.ABORTED
                    )
                }

                firestore.runTransaction { transaction ->
                    val currentProduct = transaction.get(productRef).toObject(Product::class.java)
                        ?: throw FirebaseFirestoreException(
                            "Producto no encontrado: ${productArgument.name}",
                            FirebaseFirestoreException.Code.ABORTED
                        )

                    var restanteATraspasar = quantityToTraspasarTotal
                    val idsOrigenAfectados = mutableListOf<String>()
                    val idsDestinoAfectados = mutableListOf<String>()

                    for (loteOrigen in lotesOrigenC04) {
                        if (restanteATraspasar <= stockEpsilon) break
                        val cantDeEsteLote = kotlin.math.min(loteOrigen.currentQuantity, restanteATraspasar)

                        if (cantDeEsteLote > stockEpsilon) {
                            idsOrigenAfectados.add("${loteOrigen.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)}")
                            val nuevaCantOrigen = loteOrigen.currentQuantity - cantDeEsteLote
                            transaction.update(
                                firestore.collection("inventoryLots").document(loteOrigen.id),
                                mapOf(
                                    "currentQuantity" to nuevaCantOrigen,
                                    "isDepleted" to (nuevaCantOrigen <= stockEpsilon)
                                )
                            )

                            val newLotRef = firestore.collection("inventoryLots").document()
                            val nuevoLoteEnMatriz = StockLot(
                                id = newLotRef.id,
                                productId = loteOrigen.productId,
                                productName = loteOrigen.productName,
                                unit = loteOrigen.unit,
                                location = Location.MATRIZ,
                                supplierId = loteOrigen.supplierId,
                                supplierName = loteOrigen.supplierName,
                                lotNumber = loteOrigen.lotNumber,
                                receivedAt = traspasoTimestamp,
                                movementIdIn = newMovementRef.id,
                                initialQuantity = cantDeEsteLote,
                                currentQuantity = cantDeEsteLote,
                                isDepleted = false,
                                isPackaged = loteOrigen.isPackaged,
                                expirationDate = loteOrigen.expirationDate,
                                originalLotId = null,
                                originalReceivedAt = null,
                                originalSupplierName = null,
                                originalLotNumber = null
                            )
                            transaction.set(newLotRef, nuevoLoteEnMatriz)
                            idsDestinoAfectados.add("${newLotRef.id.takeLast(4)}:${String.format("%.2f", cantDeEsteLote)} (Nuevo en M)")

                            restanteATraspasar -= cantDeEsteLote
                        }
                    }

                    val nuevoStockMatriz = currentProduct.stockMatriz + quantityToTraspasarTotal
                    val nuevoStockC04 = currentProduct.stockCongelador04 - quantityToTraspasarTotal

                    transaction.update(productRef, mapOf(
                        "stockMatriz" to nuevoStockMatriz,
                        "stockCongelador04" to nuevoStockC04,
                        "updatedAt" to FieldValue.serverTimestamp(),
                        "lastUpdatedByName" to currentUserName
                    ))

                    val movement = StockMovement(
                        id = newMovementRef.id,
                        userId = currentUser.uid,
                        userName = currentUserName,
                        productId = currentProduct.id,
                        productName = currentProduct.name,
                        type = MovementType.TRASPASO_C04_M,
                        quantity = quantityToTraspasarTotal,
                        locationFrom = Location.CONGELADOR_04,
                        locationTo = Location.MATRIZ,
                        reason = "Origen(C04): ${idsOrigenAfectados.joinToString()}; Destino(M): ${idsDestinoAfectados.joinToString()}",
                        stockAfterCongelador04 = nuevoStockC04,
                        stockAfterMatriz = nuevoStockMatriz,
                        stockAfterTotal = currentProduct.totalStock,
                        timestamp = traspasoTimestamp,
                        affectedLotIds = lotesOrigenC04.map { it.id }
                    )
                    transaction.set(newMovementRef, movement)
                }.await()

                if (isAdded) {
                    val msg = "✅ Traspaso C-04 → Matriz realizado: ${String.format("%.2f", quantityToTraspasarTotal)} ${productArgument.unit}"
                    Snackbar.make(requireActivity().findViewById(android.R.id.content), msg, Snackbar.LENGTH_LONG).show()
                    dialog.dismiss()
                }

            } catch (e: Exception) {
                if (isAdded) {
                    val errorMessage = (e as? FirebaseFirestoreException)?.message ?: "Error inesperado: ${e.message}"
                    Snackbar.make(
                        requireActivity().findViewById(android.R.id.content),
                        "❌ $errorMessage",
                        Snackbar.LENGTH_LONG
                    ).show()
                    Log.e(TAG, "Error en traspaso C04→M", e)

                    aceptarButton.isEnabled = true
                    cancelarButton.isEnabled = true
                    progressBar.isVisible = false
                }
            }
        }
    }
}