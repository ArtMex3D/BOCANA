package com.cesar.bocana.ui.printing

import android.app.Dialog
import android.app.DatePickerDialog
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.view.isVisible
import androidx.fragment.app.DialogFragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.DialogAssignLabelBinding
import com.cesar.bocana.utils.FirestoreCollections
import com.cesar.bocana.utils.ProductFields
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class AssignLabelDialogFragment : DialogFragment() {

    interface OnLabelConfiguredListener {
        fun onLabelConfigured(position: Int, config: IndividualLabelConfig, copies: Int)
    }

    private var _binding: DialogAssignLabelBinding? = null
    private val binding get() = _binding!!

    private var listener: OnLabelConfiguredListener? = null
    private var position: Int = -1
    private var existingConfig: IndividualLabelConfig? = null

    private val selectedDateCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var productsList = listOf<Product>()
    private var selectedProduct: Product? = null
    private val units = listOf("Kg", "Pzas", "Cajas", "Bolsas")

    companion object {
        private const val ARG_POSITION = "position"
        private const val ARG_EXISTING_CONFIG = "existing_config"
        fun newInstance(position: Int, existingConfig: IndividualLabelConfig?): AssignLabelDialogFragment {
            return AssignLabelDialogFragment().apply {
                arguments = Bundle().apply {
                    putInt(ARG_POSITION, position)
                    putParcelable(ARG_EXISTING_CONFIG, existingConfig)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            position = it.getInt(ARG_POSITION)
            existingConfig = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_EXISTING_CONFIG, IndividualLabelConfig::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(ARG_EXISTING_CONFIG)
            }
        }
        // Asignar el listener desde el fragmento que lo llama.
        listener = targetFragment as? OnLabelConfiguredListener
    }

    // Usamos onCreateDialog para tener control sobre los botones Aceptar/Cancelar
    override fun onCreateDialog(savedInstanceState: Bundle?): Dialog {
        _binding = DialogAssignLabelBinding.inflate(LayoutInflater.from(context))

        setupViews()
        loadProducts()
        setupListeners()
        setupUnitSpinner()
        populateExistingData()

        val builder = AlertDialog.Builder(requireActivity())
            .setView(binding.root)
            .setPositiveButton("Guardar", null) // Se deshabilita el cierre automático
            .setNegativeButton("Cancelar") { dialog, _ -> dialog.cancel() }

        val dialog = builder.create()
        // El listener del botón positivo se configura en onStart para evitar el cierre automático
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                validateAndSave()
            }
        }
        return dialog
    }


    private fun setupViews(){
        binding.textViewDialogTitle.text = "Configurar Etiqueta #${position + 1}"
        binding.layoutWeightAndUnit.isVisible = binding.radioButtonPredefinedWeight.isChecked
    }

    private fun populateExistingData() {
        existingConfig?.let {
            selectedProduct = it.product
            binding.autoCompleteProduct.setText(it.product.name, false)
            binding.editTextSupplier.setText(it.supplierName)
            binding.editTextDetail.setText(it.detail)
            selectedDateCalendar.time = it.date
            binding.buttonSelectDate.text = dateFormat.format(it.date)
            binding.autoCompleteUnit.setText(it.unit, false)

            if (it.weight == "Manual") {
                binding.radioButtonManualWeight.isChecked = true
                binding.layoutWeightAndUnit.isVisible = false
            } else {
                binding.radioButtonPredefinedWeight.isChecked = true
                binding.layoutWeightAndUnit.isVisible = true
                binding.editTextWeight.setText(it.weight)
            }
            binding.editTextCopies.setText("1")
        } ?: run {
            binding.buttonSelectDate.text = dateFormat.format(Date())
        }
    }

    private fun setupListeners() {
        binding.buttonSelectDate.setOnClickListener { showDatePicker() }

        binding.radioGroupWeightType.setOnCheckedChangeListener { _, checkedId ->
            val isPredefined = checkedId == R.id.radioButtonPredefinedWeight
            binding.layoutWeightAndUnit.isVisible = isPredefined
            if (!isPredefined) {
                binding.editTextWeight.text = null
            }
        }

        binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as? String
            selectedProduct = productsList.find { it.name == selectedName }
            binding.autoCompleteUnit.setText(selectedProduct?.unit ?: "", false)
        }
    }

    private fun loadProducts() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val snapshot = Firebase.firestore.collection(FirestoreCollections.PRODUCTS)
                    .whereEqualTo(ProductFields.IS_ACTIVE, true)
                    .orderBy(ProductFields.NAME)
                    .get().await()

                productsList = snapshot.documents.mapNotNull { doc -> doc.toObject(Product::class.java)?.copy(id = doc.id) }
                val productNames = productsList.map { it.name }

                withContext(Dispatchers.Main) {
                    if (context != null && _binding != null) {
                        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
                        binding.autoCompleteProduct.setAdapter(adapter)
                        existingConfig?.let { binding.autoCompleteProduct.setText(it.product.name, false) }
                    }
                }
            } catch (e: Exception) {
                Log.e("AssignLabelDialog", "Error cargando productos", e)
            }
        }
    }

    private fun setupUnitSpinner() {
        if (context != null) {
            val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, units)
            binding.autoCompleteUnit.setAdapter(adapter)
        }
    }

    private fun showDatePicker() {
        val context = requireContext()
        val dateSetListener = DatePickerDialog.OnDateSetListener { _, year, month, dayOfMonth ->
            selectedDateCalendar.set(year, month, dayOfMonth)
            binding.buttonSelectDate.text = dateFormat.format(selectedDateCalendar.time)
        }
        DatePickerDialog(context, dateSetListener,
            selectedDateCalendar.get(Calendar.YEAR),
            selectedDateCalendar.get(Calendar.MONTH),
            selectedDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun validateAndSave() {
        val product = selectedProduct
        val supplier = binding.editTextSupplier.text.toString().trim()
        val detail = binding.editTextDetail.text.toString().trim().ifEmpty { null }
        val date = selectedDateCalendar.time
        val unit = binding.autoCompleteUnit.text.toString().trim()
        val copies = binding.editTextCopies.text.toString().toIntOrNull() ?: 1

        val weight: String? = if (binding.radioButtonPredefinedWeight.isChecked) {
            binding.editTextWeight.text.toString().trim().ifEmpty { null }
        } else {
            "Manual"
        }

        if (product == null) {
            binding.textFieldLayoutProduct.error = "Selecciona un producto"; return
        } else {
            binding.textFieldLayoutProduct.error = null
        }

        if (supplier.isEmpty()) {
            binding.textFieldLayoutSupplier.error = "Proveedor es obligatorio"; return
        } else {
            binding.textFieldLayoutSupplier.error = null
        }

        if (unit.isEmpty()) {
            binding.textFieldLayoutUnit.error = "Unidad requerida"; return
        } else {
            binding.textFieldLayoutUnit.error = null
        }

        if (binding.radioButtonPredefinedWeight.isChecked && (weight.isNullOrEmpty() || (weight.toDoubleOrNull() ?: 0.0) <= 0.0)) {
            binding.textFieldLayoutWeight.error = "Peso inválido"; return
        } else {
            binding.textFieldLayoutWeight.error = null
        }

        val config = IndividualLabelConfig(product, supplier, date, weight, unit, detail)
        listener?.onLabelConfigured(position, config, copies)
        dismiss()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}