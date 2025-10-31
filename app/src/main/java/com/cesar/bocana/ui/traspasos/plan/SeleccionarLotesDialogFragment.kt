package com.cesar.bocana.ui.traspasos.plan

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.InputType // Necesario para cambiar tipo de input
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.os.bundleOf
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.setFragmentResult
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Location
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogSeleccionarLotesBinding
import com.cesar.bocana.utils.FirestoreCollections
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor
import kotlin.math.min

class SeleccionarLotesDialogFragment : DialogFragment(), LoteAdapterListener {

    private var _binding: DialogSeleccionarLotesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LoteCheckboxAdapter

    private var allLotes: List<StockLot> = emptyList()
    private val selectedLotIds = mutableSetOf<String>()
    // La clave es LoteID, el Valor es la cantidad (ya sea Unidades o KG)
    private val manualQuantities = mutableMapOf<String, Double>()
    // Este flag es la clave: true = Granel (Kg), false = Fijo (Unidades)
    private var isBulkProductArg: Boolean = false
    private var productInfo: Product? = null // Para almacenar info del producto (ej. unidad por defecto)

    companion object {
        const val TAG = "SeleccionarLotesDialog"
        const val REQUEST_KEY = "lotes_seleccionados_request"
        const val RESULT_LOTES_KEY = "lotes_result"
        const val RESULT_DESGLOSE_KEY = "desglose_result"
        const val PRODUCT_ID_KEY = "product_id_for_result"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_PRODUCT_NAME = "product_name"
        // Flag que define el comportamiento del diálogo
        private const val ARG_IS_BULK_PRODUCT = "is_bulk_product"
        private const val ARG_SELECTED_IDS = "selected_ids"
        private const val ARG_MANUAL_DESGLOSE = "manual_desglose"
        private const val ARG_PLAN_ID = "plan_id"

        fun newInstance(
            productId: String,
            productName: String,
            isBulkProduct: Boolean, // true si es Granel(Kg), false si es Fijo(Unidades)
            selectedIdsOrDesglose: Any?,
            planId: String? = null
        ): SeleccionarLotesDialogFragment {
            return SeleccionarLotesDialogFragment().apply {
                arguments = bundleOf(
                    ARG_PRODUCT_ID to productId,
                    ARG_PRODUCT_NAME to productName,
                    ARG_IS_BULK_PRODUCT to isBulkProduct // Guardamos el flag
                ).apply {
                    planId?.let { putString(ARG_PLAN_ID, it) }
                    // Lógica para manejar selecciones iniciales
                    when (selectedIdsOrDesglose) {
                        is List<*> -> {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val ids = selectedIdsOrDesglose as? List<String>
                                if (ids != null) {
                                    putStringArrayList(ARG_SELECTED_IDS, ArrayList(ids))
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    val desglose = selectedIdsOrDesglose as? List<DesgloseManualResult>
                                    if (desglose != null) {
                                        putParcelableArrayList(ARG_MANUAL_DESGLOSE, ArrayList(desglose))
                                    }
                                }
                            } catch (e: ClassCastException) { Log.e(TAG, "Error casting List selection", e) }
                        }
                        is ArrayList<*> -> {
                            try {
                                @Suppress("UNCHECKED_CAST")
                                val desglose = selectedIdsOrDesglose as? ArrayList<DesgloseManualResult>
                                if (desglose != null) {
                                    putParcelableArrayList(ARG_MANUAL_DESGLOSE, desglose)
                                } else {
                                    @Suppress("UNCHECKED_CAST")
                                    val ids = selectedIdsOrDesglose as? ArrayList<String>
                                    if (ids != null) {
                                        putStringArrayList(ARG_SELECTED_IDS, ids)
                                    }
                                }
                            } catch (e: ClassCastException) { Log.e(TAG, "Error casting ArrayList selection", e) }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Recuperamos el flag que define el comportamiento
        isBulkProductArg = requireArguments().getBoolean(ARG_IS_BULK_PRODUCT)

        val productId = requireArguments().getString(ARG_PRODUCT_ID)
        if (productId != null) {
            loadProductInfo(productId) // Cargar datos del producto
        } else {
            Log.e(TAG, "Product ID es nulo en onCreate")
            dismissAllowingStateLoss()
        }
    }

    private fun loadProductInfo(productId: String) {
        lifecycleScope.launch {
            try {
                val productDoc = Firebase.firestore.collection(FirestoreCollections.PRODUCTS)
                    .document(productId).get().await()
                productInfo = productDoc.toObject(Product::class.java)?.copy(id = productDoc.id)
                if (productInfo == null) {
                    Log.e(TAG, "No se encontró el producto con ID: $productId")
                    if (isAdded) Toast.makeText(context, "Error: Producto no encontrado.", Toast.LENGTH_SHORT).show()
                    dismissAllowingStateLoss()
                } else {
                    Log.d(TAG, "Info del producto '${productInfo?.name}' cargada. Es Granel (arg): $isBulkProductArg")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar información del producto $productId", e)
                if (isAdded) Toast.makeText(context, "Error al cargar datos del producto.", Toast.LENGTH_SHORT).show()
                dismissAllowingStateLoss()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        dialog?.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSeleccionarLotesBinding.inflate(LayoutInflater.from(context))
        val productId = requireArguments().getString(ARG_PRODUCT_ID)!!
        val productName = requireArguments().getString(ARG_PRODUCT_NAME)!!
        val planId = requireArguments().getString(ARG_PLAN_ID)

        val initialSelectedIds = requireArguments().getStringArrayList(ARG_SELECTED_IDS)
        val initialManualDesglose: ArrayList<DesgloseManualResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_MANUAL_DESGLOSE, DesgloseManualResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelableArrayList(ARG_MANUAL_DESGLOSE)
        }

        binding.textviewDialogTitle.text = "Seleccionar Lotes para $productName"

        // **AQUÍ ESTÁ LA FUSIÓN**: Pasamos el flag (isBulkProductArg) al adaptador.
        adapter = LoteCheckboxAdapter(this, isBulkProductArg)
        binding.recyclerViewLotesSeleccion.adapter = adapter

        val startInManualMode = initialManualDesglose != null && initialManualDesglose.isNotEmpty()
        if (startInManualMode) {
            binding.switchDesgloseManual.isChecked = true
            adapter.setModoDesglose(true)
            initialManualDesglose!!.forEach { manualQuantities[it.loteId] = it.cantidad }
            selectedLotIds.addAll(initialManualDesglose.map { it.loteId })
        } else {
            binding.switchDesgloseManual.isChecked = false
            adapter.setModoDesglose(false)
            initialSelectedIds?.let { selectedLotIds.addAll(it) }
            adapter.setSelectedIds(selectedLotIds)
        }

        binding.switchDesgloseManual.setOnCheckedChangeListener { _, isChecked ->
            adapter.setModoDesglose(isChecked)
            if (!isChecked) {
                manualQuantities.clear() // Borrar cantidades manuales si salimos del modo desglose
                adapter.setSelectedIds(selectedLotIds) // Re-aplicar selección de checkboxes
            } else {
                // Al entrar en modo desglose, inicializar cantidades de los ya chequeados
                manualQuantities.clear() // Empezar de cero
                selectedLotIds.forEach { id -> manualQuantities[id] = 0.0 }
            }
            updateAdapterList()
        }

        loadLotes(productId, planId) // Cargar los lotes disponibles

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ ->
                val bundle = bundleOf(PRODUCT_ID_KEY to productId)
                if (binding.switchDesgloseManual.isChecked) {
                    val desgloseResult = manualQuantities
                        .filter { it.value > 0.0 } // Enviar solo los que tienen cantidad
                        .map { DesgloseManualResult(it.key, it.value) } // El valor es Double (Unidades o KG)
                    bundle.putParcelableArrayList(RESULT_DESGLOSE_KEY, ArrayList(desgloseResult))
                    Log.d(TAG, "Resultado Diálogo (Manual): ${desgloseResult.size} items con cantidad > 0")
                } else {
                    bundle.putStringArrayList(RESULT_LOTES_KEY, ArrayList(selectedLotIds))
                    Log.d(TAG, "Resultado Diálogo (Checkbox): ${selectedLotIds.size} IDs seleccionados")
                }
                setFragmentResult(REQUEST_KEY, bundle) // Enviar resultado al fragmento padre
            }
            .create()
    }

    // Lógica del AdapterListener
    override fun onCheckboxToggled(lote: StockLot, isChecked: Boolean) {
        if (!binding.switchDesgloseManual.isChecked) {
            if (isChecked) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
        }
        Log.d(TAG, "Checkbox Toggled: Lote ${lote.id.takeLast(4)}, isChecked: $isChecked. Modo Manual: ${binding.switchDesgloseManual.isChecked}")
        Log.d(TAG, "selectedLotIds: $selectedLotIds")
    }

    override fun onManualEditClicked(lote: StockLot) {
        if (binding.switchDesgloseManual.isChecked) {
            showEditQuantityDialog(lote)
        }
    }

    // **DIÁLOGO DE EDICIÓN CON LÓGICA FUSIONADA**
    private fun showEditQuantityDialog(lote: StockLot) {
        val context = this.context ?: return
        val currentProduct = productInfo ?: run {
            Toast.makeText(context, "Error: Datos del producto no disponibles.", Toast.LENGTH_SHORT).show()
            return
        }
        // **LA CLAVE DE LA FUSIÓN**: Usamos el flag del producto
        val esGranel = isBulkProductArg

        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_lote_cantidad, null)
        val loteInfoTextView = dialogView.findViewById<TextView>(R.id.textView_lote_info_edit)
        val stockDispTextView = dialogView.findViewById<TextView>(R.id.textView_stock_disponible_edit)
        val cantidadEditText = dialogView.findViewById<TextInputEditText>(R.id.editText_cantidad_lote_edit)
        val inputLayout = dialogView.findViewById<com.google.android.material.textfield.TextInputLayout>(R.id.textField_layout_cantidad_edit)

        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val fecha = dateFormat.format(lote.receivedAt ?: Date())
        val proveedor = lote.supplierName ?: "S/P"
        loteInfoTextView.text = "Editando: $fecha ($proveedor)"

        val dialog = AlertDialog.Builder(context)
            .setTitle(if (esGranel) "Asignar Cantidad (Kg)" else "Asignar Cantidad (Unidades)") // Título dinámico
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar", null) // Desactivar cierre automático
            .create()

        if (esGranel) {
            // --- LÓGICA PARA GRANEL (KG) ---
            stockDispTextView.text = "Disponible: ${String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)} Kg"
            cantidadEditText.hint = "Cantidad en Kg"
            cantidadEditText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            inputLayout.suffixText = "Kg"
            val cantidadActualKg = manualQuantities[lote.id] ?: 0.0
            cantidadEditText.setText(if (cantidadActualKg > 0) String.format(Locale.getDefault(), "%.2f", cantidadActualKg) else "")

        } else {
            // --- LÓGICA PARA FIJOS (UNIDADES) ---
            // Usar el peso/unidad del lote si existe, si no, el del producto
            val pesoUnidad = lote.pesoPorUnidad?.takeIf { it > 0 } ?: 1.0
            val unidad = lote.unidadDeEmpaque?.takeIf { it.isNotBlank() } ?: currentProduct.unit ?: "Unidad"
            val unidadesDisponibles = if (pesoUnidad > 0) floor(lote.currentQuantity / pesoUnidad).toInt() else 0

            stockDispTextView.text = "Disponible: $unidadesDisponibles $unidad"
            cantidadEditText.hint = "Cantidad en $unidad"
            cantidadEditText.inputType = InputType.TYPE_CLASS_NUMBER // Solo números enteros
            inputLayout.suffixText = unidad
            val cantidadActualUnidades = manualQuantities[lote.id]?.toInt() ?: 0
            cantidadEditText.setText(if (cantidadActualUnidades > 0) cantidadActualUnidades.toString() else "")
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        cantidadEditText.requestFocus()
        cantidadEditText.selectAll()

        // Lógica de validación en el botón positivo para evitar cierre
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (esGranel) {
                // --- VALIDACIÓN GRANEL (KG) ---
                val nuevaCantidadKg = cantidadEditText.text.toString().toDoubleOrNull()
                if (nuevaCantidadKg == null || nuevaCantidadKg < 0) {
                    inputLayout.error = "Cantidad inválida (>= 0)"
                } else if (nuevaCantidadKg > lote.currentQuantity + 0.01) { // 0.01 de epsilon
                    inputLayout.error = "Excede disponible (${String.format("%.2f", lote.currentQuantity)} Kg)"
                } else {
                    inputLayout.error = null
                    manualQuantities[lote.id] = nuevaCantidadKg
                    if (nuevaCantidadKg > 0) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
                    updateAdapterList()
                    dialog.dismiss()
                }
            } else {
                // --- VALIDACIÓN FIJOS (UNIDADES) ---
                val nuevaCantidadUnidades = cantidadEditText.text.toString().toIntOrNull() ?: 0
                val pesoUnidad = lote.pesoPorUnidad?.takeIf { it > 0 } ?: 1.0
                val unidad = lote.unidadDeEmpaque?.takeIf { it.isNotBlank() } ?: currentProduct.unit ?: "Unidad"
                val unidadesDisponibles = if (pesoUnidad > 0) floor(lote.currentQuantity / pesoUnidad).toInt() else 0

                if (nuevaCantidadUnidades < 0) {
                    inputLayout.error = "Cantidad no puede ser negativa"
                } else if (nuevaCantidadUnidades > unidadesDisponibles) {
                    inputLayout.error = "Excede disponible ($unidadesDisponibles $unidad)"
                } else {
                    inputLayout.error = null
                    manualQuantities[lote.id] = nuevaCantidadUnidades.toDouble() // Guardar como Double
                    if (nuevaCantidadUnidades > 0) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
                    updateAdapterList()
                    dialog.dismiss()
                }
            }
        }
    }


    private fun updateAdapterList() {
        if (!::adapter.isInitialized) return
        val listForAdapter = allLotes.map { lote ->
            // El segundo valor es la cantidad (Kg o Unidades) guardada
            lote to manualQuantities[lote.id]
        }
        adapter.submitList(listForAdapter)
    }

    private fun loadLotes(productId: String, planId: String?) {
        lifecycleScope.launch {
            binding.progressBarDialogLotes.isVisible = true
            binding.textviewNoLotes.isVisible = false
            try {
                // Lotes Libres
                val freeLotesDeferred = async {
                    Firebase.firestore.collection(FirestoreCollections.INVENTORY_LOTS)
                        .whereEqualTo("productId", productId)
                        .whereEqualTo("location", Location.MATRIZ)
                        .whereEqualTo("isDepleted", false)
                        .whereEqualTo("estadoTraspaso", null).get().await()
                }

                // Lotes ya reservados por ESTE plan (solo relevante en "AjusteFinal")
                val reservedLotesDeferred = if (planId != null) {
                    async {
                        Firebase.firestore.collection(FirestoreCollections.INVENTORY_LOTS)
                            .whereEqualTo("productId", productId)
                            .whereEqualTo("location", Location.MATRIZ)
                            .whereEqualTo("isDepleted", false)
                            .whereEqualTo("estadoTraspaso", planId).get().await()
                    }
                } else { null }

                val freeLotesSnapshot = freeLotesDeferred.await()
                val freeLotes = freeLotesSnapshot.documents.mapNotNull { it.toObject(StockLot::class.java)?.copy(id = it.id) }

                val reservedLotesSnapshot = reservedLotesDeferred?.await()
                val reservedLotes = reservedLotesSnapshot?.documents?.mapNotNull { it.toObject(StockLot::class.java)?.copy(id = it.id) } ?: emptyList()

                // Combinar listas y eliminar duplicados (por si acaso)
                allLotes = (freeLotes + reservedLotes).distinctBy { it.id }.sortedBy { it.receivedAt ?: Date(0) }

                Log.d(TAG, "Lotes cargados (Libres: ${freeLotes.size}, Reservados por $planId: ${reservedLotes.size}, Total: ${allLotes.size})")

                updateAdapterList()

            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) {
                    Log.w(TAG, "Carga de lotes cancelada.")
                } else {
                    Log.e(TAG, "Error al cargar lotes", e)
                    if (isAdded) Toast.makeText(context, "Error al cargar lotes: ${e.message}", Toast.LENGTH_LONG).show()
                }
                allLotes = emptyList()
                updateAdapterList()
            } finally {
                if (isAdded && _binding != null) {
                    binding.progressBarDialogLotes.isVisible = false
                    binding.textviewNoLotes.isVisible = allLotes.isEmpty()
                    if (allLotes.isEmpty()) {
                        binding.textviewNoLotes.text = "No hay lotes disponibles en Matriz (libres o reservados por este plan)."
                    }
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

