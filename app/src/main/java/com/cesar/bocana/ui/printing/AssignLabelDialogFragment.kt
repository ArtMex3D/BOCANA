package com.cesar.bocana.ui.printing

import android.app.DatePickerDialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.DialogAssignLabelBinding
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class AssignLabelDialogFragment : BottomSheetDialogFragment() {

    interface OnLabelConfiguredListener {
        fun onLabelConfigured(position: Int, config: IndividualLabelConfig, copies: Int)
    }

    private var _binding: DialogAssignLabelBinding? = null
    private val binding get() = _binding!!
    private var listener: OnLabelConfiguredListener? = null
    private var position = -1
    private var existingConfig: IndividualLabelConfig? = null
    private val selectedDateCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var productsList = listOf<Product>()
    private var selectedProduct: Product? = null
    private val units = listOf("Kg", "Pzas", "Cajas", "Bolsas", "Costales")

    companion object {
        private const val ARG_POSITION = "position"
        private const val ARG_EXISTING = "existing_config"
        fun newInstance(position: Int, existingConfig: IndividualLabelConfig?) = AssignLabelDialogFragment().apply {
            arguments = Bundle().apply {
                putInt(ARG_POSITION, position)
                existingConfig?.let { putParcelable(ARG_EXISTING, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        position = arguments?.getInt(ARG_POSITION) ?: -1
        existingConfig = arguments?.let { args ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) args.getParcelable(ARG_EXISTING, IndividualLabelConfig::class.java)
            else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_EXISTING)
            }
        }
        listener = targetFragment as? OnLabelConfiguredListener
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = DialogAssignLabelBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        binding.textViewDialogTitle.text = "Etiqueta #${position + 1}"
        binding.buttonSelectDate.text = dateFormat.format(Date())
        binding.layoutWeightAndUnit.isVisible = true
        binding.textFieldLayoutWeight.isVisible = false
        binding.buttonCancel.setOnClickListener { dismiss() }
        binding.buttonSave.setOnClickListener { validateAndSave() }
        binding.buttonSelectDate.setOnClickListener { showDatePicker() }
        binding.radioGroupWeightType.setOnCheckedChangeListener { _, id ->
            val predefined = id == R.id.radioButtonPredefinedWeight
            binding.layoutWeightAndUnit.isVisible = true
            binding.textFieldLayoutWeight.isVisible = predefined
            if (!predefined) binding.editTextWeight.text = null
        }
        binding.autoCompleteProduct.setOnItemClickListener { parent, _, pos, _ ->
            selectedProduct = productsList.firstOrNull { it.name == parent.getItemAtPosition(pos).toString() }
            binding.autoCompleteUnit.setText(selectedProduct?.unit ?: "", false)
            selectedProduct?.let { suggestOldestPending(it.id) }
        }
        binding.autoCompleteUnit.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, units))
        loadProducts()
    }

    override fun onStart() {
        super.onStart()
        dialog?.findViewById<View>(com.google.android.material.R.id.design_bottom_sheet)?.let { sheet ->
            BottomSheetBehavior.from(sheet).state = BottomSheetBehavior.STATE_EXPANDED
        }
    }

    private fun loadProducts() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                productsList = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext().applicationContext).productDao().getAllActiveProductsStream().first()
                }
                if (_binding == null) return@launch
                binding.autoCompleteProduct.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productsList.map { it.name }))
                populateExisting()
            } catch (e: Exception) {
                Log.e("AssignLabel", "No se pudieron cargar productos", e)
                populateExisting()
            }
        }
    }

    private fun populateExisting() {
        val c = existingConfig ?: return
        selectedProduct = productsList.firstOrNull { it.id == c.product.id } ?: c.product
        binding.autoCompleteProduct.setText(c.product.name, false)
        binding.editTextSupplier.setText(c.supplierName)
        binding.editTextDetail.setText(c.detail.orEmpty())
        selectedDateCalendar.time = c.date
        binding.buttonSelectDate.text = dateFormat.format(c.date)
        binding.autoCompleteUnit.setText(c.unit, false)
        if (c.weight == "Manual" || c.weight.isNullOrBlank()) {
            binding.radioButtonManualWeight.isChecked = true
        } else {
            binding.radioButtonPredefinedWeight.isChecked = true
            binding.editTextWeight.setText(c.weight)
        }
    }

    private fun suggestOldestPending(productId: String) {
        if (existingConfig != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.getDatabase(requireContext().applicationContext).packagingDao().getAllPackagingTasksStream().first()
                        .filter { it.productId == productId }
                        .minByOrNull { it.receivedAt?.time ?: Long.MAX_VALUE }
                }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch
            task.supplierName?.let { binding.editTextSupplier.setText(it) }
            task.receivedAt?.let {
                selectedDateCalendar.time = it
                binding.buttonSelectDate.text = dateFormat.format(it)
            }
            binding.textViewSuggestion.text = "Sugerido por antigüedad · ${task.supplierName ?: "Sin proveedor"} · ${task.receivedAt?.let { d -> dateFormat.format(d) } ?: "sin fecha"}"
            binding.textViewSuggestion.isVisible = true
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedDateCalendar.set(y, m, d)
                binding.buttonSelectDate.text = dateFormat.format(selectedDateCalendar.time)
            },
            selectedDateCalendar.get(Calendar.YEAR), selectedDateCalendar.get(Calendar.MONTH), selectedDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun validateAndSave() {
        val product = selectedProduct
        val supplier = binding.editTextSupplier.text?.toString()?.trim().orEmpty()
        val unit = binding.autoCompleteUnit.text.toString().trim()
        val copies = (binding.editTextCopies.text?.toString()?.toIntOrNull() ?: 1).coerceAtLeast(1)
        val weight = if (binding.radioButtonPredefinedWeight.isChecked) binding.editTextWeight.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } else "Manual"
        var valid = true
        if (product == null) { binding.textFieldLayoutProduct.error = "Selecciona un producto"; valid = false } else binding.textFieldLayoutProduct.error = null
        if (supplier.isBlank()) { binding.textFieldLayoutSupplier.error = "Proveedor requerido"; valid = false } else binding.textFieldLayoutSupplier.error = null
        if (unit.isBlank()) { binding.textFieldLayoutUnit.error = "Unidad requerida"; valid = false } else binding.textFieldLayoutUnit.error = null
        if (binding.radioButtonPredefinedWeight.isChecked && (weight?.toDoubleOrNull() ?: 0.0) <= 0.0) { binding.textFieldLayoutWeight.error = "Cantidad inválida"; valid = false } else binding.textFieldLayoutWeight.error = null
        if (!valid || product == null) return
        listener?.onLabelConfigured(
            position,
            IndividualLabelConfig(product, supplier, selectedDateCalendar.time, weight, unit, binding.editTextDetail.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }),
            copies
        )
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
