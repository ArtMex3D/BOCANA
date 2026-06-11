package com.cesar.bocana.ui.traspasos.plan

import android.app.Dialog
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.util.Log
import android.view.LayoutInflater
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
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogSeleccionarLotesBinding
import com.cesar.bocana.utils.FirestoreCollections
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.floor

class SeleccionarLotesDialogFragment : DialogFragment(), LoteAdapterListener {

    private var _binding: DialogSeleccionarLotesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LoteCheckboxAdapter

    private var allLotes: List<StockLot> = emptyList()
    private val selectedLotIds = mutableSetOf<String>()

    // ✨ RESCATADO: Usamos Double para soportar decimales en los kilos
    private val manualQuantities = mutableMapOf<String, Double>()

    // ✨ RESCATADO: Saber si es granel o fijo
    private var isBulkProductArg: Boolean = false
    private var productInfo: Product? = null

    companion object {
        const val TAG = "SeleccionarLotesDialog"
        const val REQUEST_KEY = "lotes_seleccionados_request"
        const val RESULT_LOTES_KEY = "lotes_result"
        const val RESULT_DESGLOSE_KEY = "desglose_result"
        const val PRODUCT_ID_KEY = "product_id_for_result"

        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_PRODUCT_NAME = "product_name"
        private const val ARG_IS_BULK_PRODUCT = "is_bulk_product"
        private const val ARG_SELECTED_IDS = "selected_ids"
        private const val ARG_MANUAL_DESGLOSE = "manual_desglose"

        fun newInstance(
            productId: String,
            productName: String,
            isBulkProduct: Boolean,
            selectedIdsOrDesglose: Any?
        ): SeleccionarLotesDialogFragment {
            return SeleccionarLotesDialogFragment().apply {
                arguments = bundleOf(
                    ARG_PRODUCT_ID to productId,
                    ARG_PRODUCT_NAME to productName,
                    ARG_IS_BULK_PRODUCT to isBulkProduct
                ).apply {
                    // Lógica para detectar si nos mandan Checkboxes (Strings) o Desglose Manual
                    when (selectedIdsOrDesglose) {
                        is ArrayList<*> -> {
                            if (selectedIdsOrDesglose.firstOrNull() is DesgloseManualResult) {
                                @Suppress("UNCHECKED_CAST")
                                putParcelableArrayList(ARG_MANUAL_DESGLOSE, selectedIdsOrDesglose as ArrayList<DesgloseManualResult>)
                            } else {
                                @Suppress("UNCHECKED_CAST")
                                putStringArrayList(ARG_SELECTED_IDS, selectedIdsOrDesglose as ArrayList<String>)
                            }
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isBulkProductArg = requireArguments().getBoolean(ARG_IS_BULK_PRODUCT)

        val productId = requireArguments().getString(ARG_PRODUCT_ID)
        if (productId != null) {
            loadProductInfo(productId)
        } else {
            dismissAllowingStateLoss()
        }
    }

    private fun loadProductInfo(productId: String) {
        lifecycleScope.launch {
            try {
                val productDoc = Firebase.firestore.collection(FirestoreCollections.PRODUCTS).document(productId).get().await()
                productInfo = productDoc.toObject(Product::class.java)?.copy(id = productDoc.id)
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar información del producto $productId", e)
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

        val initialSelectedIds = requireArguments().getStringArrayList(ARG_SELECTED_IDS)
        val initialManualDesglose: ArrayList<DesgloseManualResult>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelableArrayList(ARG_MANUAL_DESGLOSE, DesgloseManualResult::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelableArrayList(ARG_MANUAL_DESGLOSE)
        }

        binding.textviewDialogTitle.text = "Seleccionar Lotes para $productName"

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
                manualQuantities.clear()
                adapter.setSelectedIds(selectedLotIds)
            } else {
                manualQuantities.clear()
                selectedLotIds.forEach { id -> manualQuantities[id] = 0.0 }
            }
            updateAdapterList()
        }

        loadLotes(productId)

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ ->
                val bundle = bundleOf(PRODUCT_ID_KEY to productId)
                if (binding.switchDesgloseManual.isChecked) {
                    val desgloseResult = manualQuantities
                        .filter { it.value > 0.0 }
                        .map { DesgloseManualResult(it.key, it.value) }
                    bundle.putParcelableArrayList(RESULT_DESGLOSE_KEY, ArrayList(desgloseResult))
                } else {
                    bundle.putStringArrayList(RESULT_LOTES_KEY, ArrayList(selectedLotIds))
                }
                setFragmentResult(REQUEST_KEY, bundle)
            }
            .create()
    }

    override fun onCheckboxToggled(lote: StockLot, isChecked: Boolean) {
        if (!binding.switchDesgloseManual.isChecked) {
            if (isChecked) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
        }
    }

    override fun onManualEditClicked(lote: StockLot) {
        if (binding.switchDesgloseManual.isChecked) {
            showEditQuantityDialog(lote)
        }
    }

    private fun showEditQuantityDialog(lote: StockLot) {
        val context = this.context ?: return
        val currentProduct = productInfo ?: return

        // ✨ RESCATADO: Usamos el flag para definir la lógica del teclado y las validaciones
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
            .setTitle(if (esGranel) "Asignar Cantidad (Kg)" else "Asignar Cantidad (Unidades)")
            .setView(dialogView)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar", null)
            .create()

        if (esGranel) {
            stockDispTextView.text = "Disponible: ${String.format(Locale.getDefault(), "%.2f", lote.currentQuantity)} Kg"
            cantidadEditText.hint = "Cantidad en Kg"
            // ✨ RESCATADO: Permite escribir decimales en el teclado
            cantidadEditText.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL
            inputLayout.suffixText = "Kg"

            val cantidadActualKg = manualQuantities[lote.id] ?: 0.0
            cantidadEditText.setText(if (cantidadActualKg > 0) String.format(Locale.getDefault(), "%.2f", cantidadActualKg) else "")
        } else {
            val pesoUnidad = lote.pesoPorUnidad?.takeIf { it > 0 } ?: 1.0
            val unidad = lote.unidadDeEmpaque?.takeIf { it.isNotBlank() } ?: currentProduct.unit ?: "Unidad"
            val unidadesDisponibles = if (pesoUnidad > 0) floor(lote.currentQuantity / pesoUnidad).toInt() else 0

            stockDispTextView.text = "Disponible: $unidadesDisponibles $unidad"
            cantidadEditText.hint = "Cantidad en $unidad"
            // ✨ RESCATADO: Bloquea el teclado solo a enteros
            cantidadEditText.inputType = InputType.TYPE_CLASS_NUMBER
            inputLayout.suffixText = unidad

            val cantidadActualUnidades = manualQuantities[lote.id]?.toInt() ?: 0
            cantidadEditText.setText(if (cantidadActualUnidades > 0) cantidadActualUnidades.toString() else "")
        }

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        cantidadEditText.requestFocus()
        cantidadEditText.selectAll()

        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            if (esGranel) {
                val nuevaCantidadKg = cantidadEditText.text.toString().toDoubleOrNull()
                if (nuevaCantidadKg == null || nuevaCantidadKg < 0) {
                    inputLayout.error = "Cantidad inválida (>= 0)"
                } else if (nuevaCantidadKg > lote.currentQuantity + 0.01) {
                    inputLayout.error = "Excede disponible (${String.format("%.2f", lote.currentQuantity)} Kg)"
                } else {
                    inputLayout.error = null
                    manualQuantities[lote.id] = nuevaCantidadKg
                    if (nuevaCantidadKg > 0) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
                    updateAdapterList()
                    dialog.dismiss()
                }
            } else {
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
                    manualQuantities[lote.id] = nuevaCantidadUnidades.toDouble()
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
            lote to manualQuantities[lote.id]
        }
        adapter.submitList(listForAdapter)
    }

    private fun loadLotes(productId: String) {
        lifecycleScope.launch {
            binding.progressBarDialogLotes.isVisible = true
            try {
                // 🛡️ ACTUAL: Consulta súper rápida y limpia, sin lógicas muertas de reservas.
                val snapshot = Firebase.firestore.collection("inventoryLots")
                    .whereEqualTo("productId", productId)
                    .whereEqualTo("location", "MATRIZ")
                    .whereEqualTo("isDepleted", false)
                    .whereEqualTo("estadoTraspaso", null)
                    .orderBy("receivedAt")
                    .get().await()

                allLotes = snapshot.toObjects(StockLot::class.java)
                    .filter { it.isPackaged != false && it.estadoTraspaso == null }

                updateAdapterList()
            } catch (e: Exception) {
                Log.e(TAG, "Error al cargar lotes", e)
            } finally {
                if(isAdded) {
                    binding.progressBarDialogLotes.isVisible = false
                    binding.textviewNoLotes.isVisible = allLotes.isEmpty()
                    if (allLotes.isEmpty()) {
                        binding.textviewNoLotes.text = "No hay lotes empacados disponibles en Matriz."
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