package com.cesar.bocana.ui.traspasos.plan

import android.app.Dialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
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
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogSeleccionarLotesBinding
import com.google.android.material.textfield.TextInputEditText
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.text.SimpleDateFormat
import java.util.*

class SeleccionarLotesDialogFragment : DialogFragment(), LoteAdapterListener {

    private var _binding: DialogSeleccionarLotesBinding? = null
    private val binding get() = _binding!!
    private lateinit var adapter: LoteCheckboxAdapter

    private var allLotes: List<StockLot> = emptyList()
    private val selectedLotIds = mutableSetOf<String>()
    private val manualQuantities = mutableMapOf<String, Int>()

    companion object {
        const val TAG = "SeleccionarLotesDialog"
        const val REQUEST_KEY = "lotes_seleccionados_request"
        const val RESULT_LOTES_KEY = "lotes_result"
        const val RESULT_DESGLOSE_KEY = "desglose_result"
        const val PRODUCT_ID_KEY = "product_id_for_result"
        private const val ARG_PRODUCT_ID = "product_id"
        private const val ARG_PRODUCT_NAME = "product_name"
        private const val ARG_SELECTED_IDS = "selected_ids"

        fun newInstance(productId: String, productName: String, selectedIds: List<String>): SeleccionarLotesDialogFragment {
            return SeleccionarLotesDialogFragment().apply {
                arguments = bundleOf(
                    ARG_PRODUCT_ID to productId,
                    ARG_PRODUCT_NAME to productName,
                    ARG_SELECTED_IDS to ArrayList(selectedIds)
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()
        dialog?.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_PAN)
    }

    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogSeleccionarLotesBinding.inflate(LayoutInflater.from(context))
        val productId = requireArguments().getString(ARG_PRODUCT_ID)!!
        val productName = requireArguments().getString(ARG_PRODUCT_NAME)!!
        val initialSelectedIds = requireArguments().getStringArrayList(ARG_SELECTED_IDS)!!
        selectedLotIds.addAll(initialSelectedIds)

        binding.textviewDialogTitle.text = "Seleccionar Lotes para $productName"

        adapter = LoteCheckboxAdapter(this)
        binding.recyclerViewLotesSeleccion.adapter = adapter
        adapter.setSelectedIds(selectedLotIds)

        binding.switchDesgloseManual.setOnCheckedChangeListener { _, isChecked ->
            adapter.setModoDesglose(isChecked)
            updateAdapterList()
        }

        loadLotes(productId)

        return AlertDialog.Builder(requireContext())
            .setView(binding.root)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Aceptar") { _, _ ->
                val bundle = bundleOf(PRODUCT_ID_KEY to productId)
                if (binding.switchDesgloseManual.isChecked) {
                    val desgloseResult = manualQuantities.filter { it.value > 0 }.map { DesgloseManualResult(it.key, it.value.toDouble()) }
                    bundle.putParcelableArrayList(RESULT_DESGLOSE_KEY, ArrayList(desgloseResult))
                } else {
                    val selectedLotes = allLotes.filter { selectedLotIds.contains(it.id) }
                    bundle.putParcelableArrayList(RESULT_LOTES_KEY, ArrayList(selectedLotes))
                }
                setFragmentResult(REQUEST_KEY, bundle)
            }
            .create()
    }

    override fun onCheckboxToggled(lote: StockLot, isChecked: Boolean) {
        if (isChecked) selectedLotIds.add(lote.id) else selectedLotIds.remove(lote.id)
    }

    override fun onManualEditClicked(lote: StockLot) {
        showEditQuantityDialog(lote)
    }

    private fun showEditQuantityDialog(lote: StockLot) {
        val context = this.context ?: return
        val dialogView = LayoutInflater.from(context).inflate(R.layout.dialog_edit_lote_cantidad, null)
        val loteInfoTextView = dialogView.findViewById<TextView>(R.id.textView_lote_info_edit)
        val stockDispTextView = dialogView.findViewById<TextView>(R.id.textView_stock_disponible_edit)
        val cantidadEditText = dialogView.findViewById<TextInputEditText>(R.id.editText_cantidad_lote_edit)

        val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())
        val fecha = dateFormat.format(lote.receivedAt ?: Date())
        val proveedor = lote.supplierName ?: "S/P"
        loteInfoTextView.text = "Editando: $fecha ($proveedor)"

        val pesoUnidad = lote.pesoPorUnidad ?: 1.0
        val unidadesDisponibles = if (pesoUnidad > 0) Math.floor(lote.currentQuantity / pesoUnidad).toInt() else 0
        val unidad = lote.unidadDeEmpaque ?: "Kg"
        stockDispTextView.text = "Disponible: $unidadesDisponibles $unidad"

        val cantidadActual = manualQuantities[lote.id] ?: 0
        if (cantidadActual > 0) {
            cantidadEditText.setText(cantidadActual.toString())
        }

        val dialog = AlertDialog.Builder(context)
            .setTitle("Asignar Cantidad")
            .setView(dialogView)
            .setPositiveButton("Aceptar") { d, _ ->
                val nuevaCantidad = cantidadEditText.text.toString().toIntOrNull() ?: 0
                if (nuevaCantidad > unidadesDisponibles) {
                    Toast.makeText(context, "La cantidad no puede superar lo disponible ($unidadesDisponibles)", Toast.LENGTH_LONG).show()
                } else if (nuevaCantidad < 0) {
                    Toast.makeText(context, "La cantidad no puede ser negativa", Toast.LENGTH_LONG).show()
                } else {
                    manualQuantities[lote.id] = nuevaCantidad
                    updateAdapterList()
                    d.dismiss()
                }
            }
            .setNegativeButton("Cancelar", null)
            .create()

        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE)
        dialog.show()
        cantidadEditText.requestFocus()
    }

    private fun updateAdapterList() {
        val listForAdapter = allLotes.map { lote ->
            Pair(lote, manualQuantities[lote.id])
        }
        adapter.submitList(listForAdapter)
    }

    private fun loadLotes(productId: String) {
        lifecycleScope.launch {
            binding.progressBarDialogLotes.isVisible = true
            try {
                // <-- ✨ REGLA DE NEGOCIO: Cargar solo lotes empacados para el desglose.
                val snapshot = Firebase.firestore.collection("inventoryLots")
                    .whereEqualTo("productId", productId)
                    .whereEqualTo("location", "MATRIZ")
                    .whereEqualTo("isDepleted", false)
                    .whereEqualTo("isPackaged", true) // <-- Solo lotes listos para traspaso por unidad.
                    .orderBy("receivedAt")
                    .get().await()
                allLotes = snapshot.toObjects(StockLot::class.java)
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

