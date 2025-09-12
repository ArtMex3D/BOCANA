package com.cesar.bocana.ui.dialogs

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout // <--- (Opcional, puede que Android Studio lo agregue solo)
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.*
import com.cesar.bocana.helpers.NotificationTriggerHelper
import com.cesar.bocana.ui.adapters.GroupableListItem
import com.cesar.bocana.ui.adapters.SubloteC04SelectionAdapter
import com.cesar.bocana.ui.adapters.SubloteC04SelectionListener
import com.google.android.material.snackbar.Snackbar
import com.google.android.material.textfield.TextInputLayout
import com.google.firebase.auth.ktx.auth
import com.google.firebase.firestore.*
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.*

class AjusteSubloteC04DialogFragment : DialogFragment() {

    private lateinit var firestore: FirebaseFirestore
    private val auth = Firebase.auth
    private val stockEpsilon = 0.01

    private var productArgument: Product? = null
    private var productIdArg: String? = null

    private lateinit var titleProductTextView: TextView
    private lateinit var recyclerViewSubLotes: RecyclerView
    private lateinit var progressBarSubLotes: ProgressBar
    private lateinit var textViewNoSubLotes: TextView
    private lateinit var selectedSubLoteInfoTextView: TextView
    private lateinit var inputLayoutNuevaCantidad: TextInputLayout
    private lateinit var editTextNuevaCantidad: EditText
    private lateinit var buttonAceptar: Button
    private lateinit var buttonCancelar: Button

    private lateinit var subloteAdapter: SubloteC04SelectionAdapter
    private var subloteSeleccionadoPorUsuario: StockLot? = null
    private val shortDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    interface AjusteSubloteC04Listener {
        fun onSubloteAjustado(productId: String)
    }
    var listener: AjusteSubloteC04Listener? = null

    companion object {
        const val TAG = "AjusteSubloteC04Dialog"
        private const val ARG_PRODUCT_ID = "product_id_arg"

        fun newInstance(productId: String): AjusteSubloteC04DialogFragment {
            val args = Bundle()
            args.putString(ARG_PRODUCT_ID, productId)
            val fragment = AjusteSubloteC04DialogFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        firestore = Firebase.firestore
        productIdArg = arguments?.getString(ARG_PRODUCT_ID)

        if (productIdArg == null) {
            Toast.makeText(context, "Error: ID de producto no especificado.", Toast.LENGTH_LONG).show()
            dismissAllowingStateLoss()
            return
        }
        loadProductData(productIdArg!!)

        listener = parentFragment as? AjusteSubloteC04Listener
        if (listener == null) {
            listener = activity as? AjusteSubloteC04Listener
        }
    }

    private fun loadProductData(productId: String) {
        firestore.collection("products").document(productId).get()
            .addOnSuccessListener { documentSnapshot ->
                if (!isAdded || context == null) return@addOnSuccessListener
                if (documentSnapshot.exists()) {
                    productArgument = documentSnapshot.toObject(Product::class.java)
                    if (this::titleProductTextView.isInitialized && productArgument != null) {
                        titleProductTextView.text = "Ajustar Lote C04: ${productArgument!!.name}"
                    }
                    if(productArgument != null && this::recyclerViewSubLotes.isInitialized) {
                        loadSublotesFromC04()
                    } else if (productArgument == null) {
                        showSnackbar("Error al cargar datos del producto.", true)
                        dismissAllowingStateLoss()
                    }
                } else {
                    showSnackbar("Error: Producto no encontrado para ajuste.", true)
                    dismissAllowingStateLoss()
                }
            }
            .addOnFailureListener {
                if (!isAdded || context == null) return@addOnFailureListener
                showSnackbar("Error de conexión al cargar producto.", true)
                dismissAllowingStateLoss()
            }
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        val builder = AlertDialog.Builder(requireActivity())
        val inflater = requireActivity().layoutInflater
        val view = inflater.inflate(R.layout.dialog_ajuste_sublote_c04, null)

        titleProductTextView = view.findViewById(R.id.textViewDialogAjusteSubLoteTitleProduct)
        recyclerViewSubLotes = view.findViewById(R.id.recyclerViewSubLotesC04Dialog)
        progressBarSubLotes = view.findViewById(R.id.progressBarSubLotesC04Dialog)
        textViewNoSubLotes = view.findViewById(R.id.textViewNoSubLotesC04Dialog)
        selectedSubLoteInfoTextView = view.findViewById(R.id.textViewSelectedSubLoteInfo)
        inputLayoutNuevaCantidad = view.findViewById(R.id.textFieldLayoutNuevaCantidadSubLote)
        editTextNuevaCantidad = view.findViewById(R.id.editTextNuevaCantidadSubLote)
        buttonAceptar = view.findViewById(R.id.buttonDialogAjusteSubLoteAceptar)
        buttonCancelar = view.findViewById(R.id.buttonDialogAjusteSubLoteCancelar)


        if (productArgument != null) {
            titleProductTextView.text = "Ajustar Lote C04: ${productArgument!!.name}"
        } else {
            titleProductTextView.text = "Cargando producto..."
        }

        inputLayoutNuevaCantidad.isEnabled = false
        buttonAceptar.isEnabled = false
        selectedSubLoteInfoTextView.visibility = View.GONE

        setupRecyclerView()

        buttonCancelar.setOnClickListener { dismiss() }
        buttonAceptar.setOnClickListener { handleAceptarClick() }

        builder.setView(view)
        val dialog = builder.create()
        dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        return dialog
    }

    private fun showSnackbar(message: String, isError: Boolean = false) {
        val view = activity?.findViewById<View>(android.R.id.content)
        if (view != null && isAdded) {
            val snackbar = Snackbar.make(view, message, Snackbar.LENGTH_LONG)
            if (isError) {
                snackbar.setBackgroundTint(ContextCompat.getColor(requireContext(), R.color.negative_red))
            }
            snackbar.show()
        }
    }

    private fun setupRecyclerView() {
        subloteAdapter = SubloteC04SelectionAdapter(object : SubloteC04SelectionListener {
            override fun onSubloteSelected(selectedLot: StockLot) {
                subloteSeleccionadoPorUsuario = selectedLot
                val currentQtyStr = String.format(Locale.getDefault(), "%.2f %s", selectedLot.currentQuantity, selectedLot.unit)

                val origenDisplayConciso: String
                if (selectedLot.originalLotId != null && selectedLot.originalSupplierName != null && selectedLot.originalReceivedAt != null) {
                    val supplierName = selectedLot.originalSupplierName
                    val dateStr = shortDateFormat.format(selectedLot.originalReceivedAt!!)
                    origenDisplayConciso = "$supplierName ($dateStr)"
                } else if (selectedLot.supplierName != null && selectedLot.receivedAt != null) {
                    val supplierName = selectedLot.supplierName
                    val dateStr = shortDateFormat.format(selectedLot.receivedAt!!)
                    val loteProvInfo = selectedLot.lotNumber?.let { ", LoteP: $it" } ?: ""
                    origenDisplayConciso = "$supplierName ($dateStr)$loteProvInfo"
                } else {
                    origenDisplayConciso = "ID:...${selectedLot.id.takeLast(4)}"
                }

                selectedSubLoteInfoTextView.text = "Lote Origen: $origenDisplayConciso | Cant: $currentQtyStr"

                selectedSubLoteInfoTextView.visibility = View.VISIBLE
                inputLayoutNuevaCantidad.hint = "Nueva Cant. (${selectedLot.unit})"
                inputLayoutNuevaCantidad.isEnabled = true
                buttonAceptar.isEnabled = true
                editTextNuevaCantidad.setText(String.format(Locale.getDefault(), "%.2f", selectedLot.currentQuantity))
                editTextNuevaCantidad.requestFocus()
                editTextNuevaCantidad.selectAll()
            }
        })
        recyclerViewSubLotes.layoutManager = LinearLayoutManager(context)
        recyclerViewSubLotes.adapter = subloteAdapter
    }

    private fun loadSublotesFromC04() {
        // ... (el resto del código no cambia)
        val currentProduct = productArgument ?: return
        progressBarSubLotes.visibility = View.VISIBLE
        textViewNoSubLotes.visibility = View.GONE
        recyclerViewSubLotes.visibility = View.GONE
        buttonAceptar.isEnabled = false

        val lotsQuery = firestore.collection("inventoryLots")
            .whereEqualTo("productId", currentProduct.id)
            .whereEqualTo("location", Location.CONGELADOR_04)
            .whereEqualTo("isDepleted", false)
            .orderBy("receivedAt", Query.Direction.ASCENDING)

        lotsQuery.get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded || context == null) return@addOnSuccessListener
                progressBarSubLotes.visibility = View.GONE

                if (snapshot != null && !snapshot.isEmpty) {
                    val allSublotes = snapshot.documents.mapNotNull { doc ->
                        doc.toObject(StockLot::class.java)?.copy(id = doc.id)
                    }
                    val groupedListItems = mutableListOf<GroupableListItem>()
                    val groups = TreeMap<String, MutableList<StockLot>>()

                    allSublotes.forEach { sublote ->
                        val headerTitle: String
                        if (sublote.originalLotId != null && sublote.originalSupplierName != null && sublote.originalReceivedAt != null) {
                            val supplierName = sublote.originalSupplierName
                            val dateStr = shortDateFormat.format(sublote.originalReceivedAt!!)
                            headerTitle = "$supplierName ($dateStr)"
                        } else if (sublote.supplierName != null && sublote.receivedAt != null) {
                            val supplierName = sublote.supplierName
                            val dateStr = shortDateFormat.format(sublote.receivedAt!!)
                            val loteProvInfo = sublote.lotNumber?.let { ", LoteP: $it" } ?: ""
                            headerTitle = "Lote C04: $supplierName ($dateStr)$loteProvInfo"
                        } else {
                            val dateStr = sublote.receivedAt?.let { shortDateFormat.format(it) } ?: "Sin Fecha"
                            val loteProvInfo = sublote.lotNumber?.let { ", LoteP: $it" } ?: ""
                            headerTitle = "Sin Proveedor ($dateStr)$loteProvInfo"
                        }
                        groups.getOrPut(headerTitle) { mutableListOf() }.add(sublote)
                    }

                    groups.forEach { (header, sublotesInGroup) ->
                        groupedListItems.add(GroupableListItem.HeaderItem(header))
                        sublotesInGroup.sortedBy { it.receivedAt ?: Date(0) }.forEach { sublote ->
                            groupedListItems.add(GroupableListItem.SubLoteItem(sublote))
                        }
                    }
                    subloteAdapter.submitList(groupedListItems)
                    textViewNoSubLotes.visibility = View.GONE
                    recyclerViewSubLotes.visibility = View.VISIBLE
                } else {
                    textViewNoSubLotes.text = "No hay lotes activos en C-04 para este producto."
                    textViewNoSubLotes.visibility = View.VISIBLE
                    recyclerViewSubLotes.visibility = View.GONE
                }
            }
            .addOnFailureListener { e ->
                if (!isAdded || context == null) return@addOnFailureListener
                progressBarSubLotes.visibility = View.GONE
                textViewNoSubLotes.text = "Error al cargar lotes C-04."
                textViewNoSubLotes.visibility = View.VISIBLE
                recyclerViewSubLotes.visibility = View.GONE
                showSnackbar("Error cargando lotes: ${e.message}", true)
            }
    }

    private fun handleAceptarClick() {
        val nuevaCantidadString = editTextNuevaCantidad.text.toString()
        val nuevaCantidadNeta = nuevaCantidadString.toDoubleOrNull()
        val subloteActual = subloteSeleccionadoPorUsuario
        val currentProduct = productArgument

        if (currentProduct == null) {
            showSnackbar("Error: Producto no disponible.", true)
            return
        }
        if (subloteActual == null) {
            showSnackbar("Selecciona un lote.", true)
            return
        }
        if (nuevaCantidadNeta == null || nuevaCantidadNeta < 0.0) {
            inputLayoutNuevaCantidad.error = "Cantidad debe ser >= 0.0"
            return
        } else {
            inputLayoutNuevaCantidad.error = null
        }
        if (nuevaCantidadNeta - subloteActual.currentQuantity > stockEpsilon) {
            inputLayoutNuevaCantidad.error = "Aumento no permitido aquí."
            return
        } else {
            inputLayoutNuevaCantidad.error = null
        }

        val consumoNeto = subloteActual.currentQuantity - nuevaCantidadNeta
        if (kotlin.math.abs(consumoNeto) <= stockEpsilon && kotlin.math.abs(subloteActual.currentQuantity - nuevaCantidadNeta) <= stockEpsilon) {
            showSnackbar("No se requiere ajuste.")
            dismiss()
            return
        }
        performAjusteSubLoteC04Transaction(currentProduct, subloteActual, nuevaCantidadNeta)
    }

    private fun performAjusteSubLoteC04Transaction(
        product: Product,
        subloteAActualizar: StockLot,
        nuevaCantidadNetaSublote: Double
    ) {
        val currentUser = auth.currentUser ?: run {
            showSnackbar("Error de autenticación.", true)
            return
        }
        val currentUserName = currentUser.displayName ?: currentUser.email ?: "Unknown"

        progressBarSubLotes.visibility = View.VISIBLE
        buttonAceptar.isEnabled = false
        buttonCancelar.isEnabled = false

        val productRef = firestore.collection("products").document(product.id)
        val subloteRef = firestore.collection("inventoryLots").document(subloteAActualizar.id)
        val newMovementRef = firestore.collection("stockMovements").document()

        var productForNotification: Product? = null

        firestore.runTransaction { transaction ->
            val productSnapshot = transaction.get(productRef)
            val currentProductFS = productSnapshot.toObject(Product::class.java)
                ?: throw FirebaseFirestoreException("Producto no encontrado: ${product.name}", FirebaseFirestoreException.Code.ABORTED)

            val subloteSnapshot = transaction.get(subloteRef)
            val currentSubloteFS = subloteSnapshot.toObject(StockLot::class.java)
                ?: throw FirebaseFirestoreException("Lote no encontrado: ${subloteAActualizar.id}", FirebaseFirestoreException.Code.ABORTED)

            val cantidadActualSubloteFS = currentSubloteFS.currentQuantity
            val consumoRealCalculado = cantidadActualSubloteFS - nuevaCantidadNetaSublote
            val nuevaCantidadRealSubloteFinal = if (nuevaCantidadNetaSublote < stockEpsilon) 0.0 else nuevaCantidadNetaSublote

            if (consumoRealCalculado < 0 && kotlin.math.abs(consumoRealCalculado) > stockEpsilon) {
                throw FirebaseFirestoreException("Error de lógica: consumo no puede ser negativo (actual FS: ${String.format("%.2f", cantidadActualSubloteFS)}, nueva: ${String.format("%.2f", nuevaCantidadNetaSublote)})", FirebaseFirestoreException.Code.ABORTED)
            }

            transaction.update(subloteRef, mapOf(
                "currentQuantity" to nuevaCantidadRealSubloteFinal,
                "isDepleted" to (nuevaCantidadRealSubloteFinal <= stockEpsilon)
            ))

            val nuevoStockC04 = currentProductFS.stockCongelador04 - consumoRealCalculado
            val nuevoTotalStock = currentProductFS.totalStock - consumoRealCalculado

            if (nuevoStockC04 < -stockEpsilon || nuevoTotalStock < -stockEpsilon) {
                throw FirebaseFirestoreException("Error: Stock del producto quedaría negativo.", FirebaseFirestoreException.Code.ABORTED)
            }

            transaction.update(productRef, mapOf(
                "stockCongelador04" to nuevoStockC04,
                "totalStock" to nuevoTotalStock,
                "updatedAt" to FieldValue.serverTimestamp(),
                "lastUpdatedByName" to currentUserName
            ))

            if (consumoRealCalculado > stockEpsilon) {
                val movement = StockMovement(
                    id = newMovementRef.id, userId = currentUser.uid, userName = currentUserName,
                    productId = product.id, productName = product.name, type = MovementType.AJUSTE_STOCK_C04,
                    quantity = consumoRealCalculado, locationFrom = Location.CONGELADOR_04, locationTo = Location.EXTERNO,
                    reason = "Ajuste lote C04 ID: ${subloteAActualizar.id.takeLast(6)}",
                    stockAfterMatriz = currentProductFS.stockMatriz, stockAfterCongelador04 = nuevoStockC04,
                    stockAfterTotal = nuevoTotalStock, timestamp = Date()
                )
                transaction.set(newMovementRef, movement)
            }
            productForNotification = currentProductFS.copy(stockCongelador04 = nuevoStockC04, totalStock = nuevoTotalStock)
            null
        }.addOnSuccessListener {
            if (!isAdded || context == null) return@addOnSuccessListener
            progressBarSubLotes.visibility = View.GONE
            val consumoNetoReal = subloteAActualizar.currentQuantity - nuevaCantidadNetaSublote
            val msg = if (consumoNetoReal > stockEpsilon) "Lote ajustado. Consumo: ${String.format(Locale.getDefault(), "%.2f", consumoNetoReal)} ${product.unit}" else "Lote ajustado."
            showSnackbar(msg)
            productForNotification?.let { prod ->
                lifecycleScope.launch { NotificationTriggerHelper.triggerLowStockNotification(prod) }
            }
            listener?.onSubloteAjustado(product.id)
            dismiss()
        }.addOnFailureListener { e ->
            if (!isAdded || context == null) return@addOnFailureListener
            progressBarSubLotes.visibility = View.GONE
            buttonAceptar.isEnabled = true
            buttonCancelar.isEnabled = true
            val errorMsg = if (e is FirebaseFirestoreException && e.code == FirebaseFirestoreException.Code.ABORTED) {
                e.message ?: "Error de datos durante el ajuste."
            } else { "Error ajustando lote: ${e.message}" }
            Log.e(TAG, "Error en transacción de ajuste C04: ", e)
            showSnackbar(errorMsg, true)
        }
    }
}

