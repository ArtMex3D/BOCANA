package com.cesar.bocana.ui.printing

import android.app.DatePickerDialog
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.QrCodeOption
import com.cesar.bocana.databinding.FragmentPrintLabelConfigBinding
import com.cesar.bocana.utils.FirestoreCollections
import com.cesar.bocana.utils.ProductFields
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.*

class PrintLabelConfigFragment : Fragment() {

    private var _binding: FragmentPrintLabelConfigBinding? = null
    private val binding get() = _binding!!

    private var labelType: LabelType? = null
    private val selectedDateCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var productsList = listOf<Product>()
    private var selectedProduct: Product? = null
    private val units = listOf("Kg", "Pzas", "Cajas", "Bolsas")

    companion object {
        private const val ARG_LABEL_TYPE = "label_type"
        fun newInstance(labelType: LabelType): PrintLabelConfigFragment {
            val args = Bundle()
            args.putSerializable(ARG_LABEL_TYPE, labelType)
            val fragment = PrintLabelConfigFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            labelType = it.getSerializable(ARG_LABEL_TYPE) as? LabelType
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrintLabelConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupUIForLabelType()
        setupListeners()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Imprimir Etiquetas - Paso 2"
            subtitle = "Configurar datos"
        }
    }

    private fun setupUIForLabelType() {
        binding.buttonSelectDate.text = dateFormat.format(Date())
        val isDetailed = labelType == LabelType.DETAILED

        binding.textViewConfigTitle.text = if (isDetailed) "Configurar Etiqueta Detallada" else "Configurar Etiqueta Simple"

        binding.textFieldLayoutProduct.isVisible = true
        binding.textViewWeightLabel.isVisible = isDetailed
        binding.radioGroupWeightType.isVisible = isDetailed
        binding.layoutWeightAndUnit.isVisible = false

        loadProducts()
        setupUnitSpinner()
    }

    private fun setupListeners() {
        binding.buttonSelectDate.setOnClickListener { showDatePicker() }

        binding.radioGroupWeightType.setOnCheckedChangeListener { _, checkedId ->
            binding.layoutWeightAndUnit.isVisible = true
            binding.textFieldLayoutWeight.isVisible = (checkedId == R.id.radioButtonPredefinedWeight)
            if (checkedId != R.id.radioButtonPredefinedWeight) {
                binding.editTextWeight.text = null
            }
        }

        binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as? String
            selectedProduct = productsList.find { it.name == selectedName }
            binding.autoCompleteUnit.setText(selectedProduct?.unit ?: "", false)
        }

        binding.buttonConfigContinue.setOnClickListener {
            validateAndNavigate()
        }
    }

    private fun loadProducts() {
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val snapshot = Firebase.firestore.collection(FirestoreCollections.PRODUCTS)
                    .whereEqualTo(ProductFields.IS_ACTIVE, true)
                    .orderBy(ProductFields.NAME)
                    .get().await()

                productsList = snapshot.documents.mapNotNull { doc -> doc.toObject(Product::class.java)?.copy(id = doc.id) }
                val productNames = productsList.map { it.name }

                withContext(Dispatchers.Main) {
                    if (context != null) {
                        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productNames)
                        binding.autoCompleteProduct.setAdapter(adapter)
                    }
                }
            } catch (e: Exception) {
                Log.e("PrintLabelConfig", "Error cargando productos", e)
                withContext(Dispatchers.Main) {
                    if (context != null) {
                        Toast.makeText(context, "Error al cargar productos", Toast.LENGTH_SHORT).show()
                    }
                }
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

    private fun validateAndNavigate() {
        var isValid = true
        val supplier = binding.editTextSupplier.text.toString().trim()
        val date = selectedDateCalendar.time

        var productId: String? = null
        var productName: String? = null
        var weight: String? = null
        var unit: String? = null

        val qrOption = when (binding.radioGroupQrType.checkedRadioButtonId) {
            R.id.radioQrStockWeb -> QrCodeOption.STOCK_WEB
            R.id.radioQrMovementsApp -> QrCodeOption.MOVEMENTS_APP
            R.id.radioQrBoth -> QrCodeOption.BOTH
            else -> QrCodeOption.NONE
        }

        if (qrOption != QrCodeOption.NONE) {
            if (binding.autoCompleteProduct.text.isNullOrEmpty() || selectedProduct == null) {
                binding.textFieldLayoutProduct.error = "Selecciona un producto para el QR"; isValid = false
            } else {
                binding.textFieldLayoutProduct.error = null
                productName = selectedProduct?.name
                productId = selectedProduct?.id
            }
        } else {
            productName = binding.autoCompleteProduct.text.toString() // Opcional, puede ir vacío
            productId = selectedProduct?.id // Opcional
        }


        if (labelType == LabelType.SIMPLE) {
            if (supplier.isEmpty()) {
                binding.textFieldLayoutSupplier.error = "Proveedor es obligatorio"; isValid = false
            } else { binding.textFieldLayoutSupplier.error = null }
        } else {
            val weightTypeId = binding.radioGroupWeightType.checkedRadioButtonId
            if (weightTypeId == -1) {
                Toast.makeText(context, "Selecciona un tipo de peso", Toast.LENGTH_SHORT).show(); isValid = false
            } else {
                unit = binding.autoCompleteUnit.text.toString().trim()
                if (unit.isEmpty()) {
                    binding.textFieldLayoutUnit.error = "Unidad requerida"; isValid = false
                } else { binding.textFieldLayoutUnit.error = null }

                if (weightTypeId == R.id.radioButtonManualWeight) {
                    weight = "Manual"
                } else {
                    weight = binding.editTextWeight.text.toString().trim()
                    if (weight.isEmpty() || weight.toDoubleOrNull() == null || (weight.toDoubleOrNull() ?: 0.0) <= 0.0) {
                        binding.textFieldLayoutWeight.error = "Peso inválido (>0)"; isValid = false
                    } else { binding.textFieldLayoutWeight.error = null }
                }
            }
        }

        if (!isValid) return

        val labelData = LabelData(
            labelType = labelType!!,
            qrCodeOption = qrOption,
            productId = productId,
            productName = productName,
            supplierName = supplier.ifEmpty { null },
            date = date,
            weight = weight,
            unit = unit
        )

        val fragment = PrintLabelLayoutFragment.newInstance(labelData)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack("PrintLabelLayoutFragment")
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
