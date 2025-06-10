package com.cesar.bocana.ui.printing

import android.app.DatePickerDialog
import android.graphics.Bitmap
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
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
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.QrCodeOption
import com.cesar.bocana.databinding.FragmentPrintLabelConfigBinding
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

class PrintLabelConfigFragment : Fragment() {

    private var _binding: FragmentPrintLabelConfigBinding? = null
    private val binding get() = _binding!!

    private var labelType: LabelType? = null
    private val selectedDateCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var productsList = listOf<Product>()
    private var selectedProduct: Product? = null
    private val units = listOf("Kg", "Pzas", "Cajas", "Bolsas")

    private var qrBitmapS: Bitmap? = null
    private var qrBitmapM: Bitmap? = null

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
        loadProducts()
        updatePreview()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Imprimir Etiquetas - Configuración"
            subtitle = "Ajusta y previsualiza tu etiqueta"
        }
    }

    private fun setupUIForLabelType() {
        binding.buttonSelectDate.text = dateFormat.format(Date())
        val isDetailed = labelType == LabelType.DETAILED

        binding.textViewConfigTitle.text = if (isDetailed) "Configurar Etiqueta Detallada" else "Configurar Etiqueta Simple"

        binding.textViewWeightLabel.isVisible = isDetailed
        binding.radioGroupWeightType.isVisible = isDetailed
        binding.layoutWeightAndUnit.isVisible = false

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
            updatePreview()
        }

        binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as? String
            selectedProduct = productsList.find { it.name == selectedName }
            binding.autoCompleteUnit.setText(selectedProduct?.unit ?: "", false)
            updatePreview()
        }

        binding.radioGroupQrType.setOnCheckedChangeListener { _, _ ->
            updatePreview()
        }

        val textWatcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                updatePreview()
            }
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.editTextSupplier.addTextChangedListener(textWatcher)
        binding.editTextWeight.addTextChangedListener(textWatcher)
        binding.autoCompleteUnit.addTextChangedListener(textWatcher)


        binding.buttonConfigContinue.setOnClickListener {
            validateAndNavigate()
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
            updatePreview()
        }
        DatePickerDialog(context, dateSetListener,
            selectedDateCalendar.get(Calendar.YEAR),
            selectedDateCalendar.get(Calendar.MONTH),
            selectedDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun getCurrentLabelData(): LabelData {
        val qrOption = when (binding.radioGroupQrType.checkedRadioButtonId) {
            R.id.radioQrStockWeb -> QrCodeOption.STOCK_WEB
            R.id.radioQrMovementsApp -> QrCodeOption.MOVEMENTS_APP
            R.id.radioQrBoth -> QrCodeOption.BOTH
            else -> QrCodeOption.NONE
        }

        var weight: String? = null
        var unit: String? = null

        if (labelType == LabelType.DETAILED) {
            unit = binding.autoCompleteUnit.text.toString().trim()
            weight = when (binding.radioGroupWeightType.checkedRadioButtonId) {
                R.id.radioButtonManualWeight -> "Manual"
                R.id.radioButtonPredefinedWeight -> binding.editTextWeight.text.toString().trim().ifEmpty { null }
                else -> null
            }
        }

        return LabelData(
            labelType = labelType!!,
            qrCodeOption = qrOption,
            productId = selectedProduct?.id,
            productName = selectedProduct?.name ?: binding.autoCompleteProduct.text.toString(),
            supplierName = binding.editTextSupplier.text.toString().trim(),
            date = selectedDateCalendar.time,
            weight = weight,
            unit = unit
        )
    }

    private fun updatePreview() {
        if (_binding == null) return

        val data = getCurrentLabelData()

        lifecycleScope.launch {
            qrBitmapS?.recycle()
            qrBitmapM?.recycle()
            qrBitmapS = null
            qrBitmapM = null

            if (!data.productId.isNullOrEmpty()) {
                if (data.qrCodeOption == QrCodeOption.STOCK_WEB || data.qrCodeOption == QrCodeOption.BOTH) {
                    qrBitmapS = withContext(Dispatchers.Default) {
                        QRGenerator.generate("https://bocana.netlify.app/qr.html?id=${data.productId}", 150, 'S')
                    }
                }
                if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) {
                    qrBitmapM = withContext(Dispatchers.Default) {
                        QRGenerator.generate("bocana-app-movements://${data.productId}", 150, 'M')
                    }
                }
            }

            // --- LLAMADA CORREGIDA ---
            // Ahora se le pasan los 3 parámetros que la función `updateView` espera.
            binding.previewView.updateView(data, qrBitmapS, qrBitmapM)
        }
    }

    private fun validateAndNavigate() {
        val data = getCurrentLabelData()
        var isValid = true

        if (data.qrCodeOption != QrCodeOption.NONE && data.productId.isNullOrEmpty()) {
            binding.textFieldLayoutProduct.error = "Selecciona un producto para el QR"
            isValid = false
        } else {
            binding.textFieldLayoutProduct.error = null
        }

        if (data.supplierName.isNullOrEmpty()) {
            binding.textFieldLayoutSupplier.error = "Proveedor es obligatorio"
            isValid = false
        } else {
            binding.textFieldLayoutSupplier.error = null
        }

        if (data.labelType == LabelType.DETAILED) {
            if (data.unit.isNullOrEmpty()) {
                binding.textFieldLayoutUnit.error = "Unidad requerida"
                isValid = false
            } else {
                binding.textFieldLayoutUnit.error = null
            }

            if (binding.radioGroupWeightType.checkedRadioButtonId == R.id.radioButtonPredefinedWeight) {
                if (data.weight.isNullOrEmpty() || (data.weight.toDoubleOrNull() ?: 0.0) <= 0.0) {
                    binding.textFieldLayoutWeight.error = "Peso inválido"
                    isValid = false
                } else {
                    binding.textFieldLayoutWeight.error = null
                }
            }
        }

        if (!isValid) {
            Toast.makeText(context, "Por favor, corrige los errores", Toast.LENGTH_SHORT).show()
            return
        }

        val fragment = PrintLabelLayoutFragment.newInstance(data)
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, fragment)
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        qrBitmapS?.recycle()
        qrBitmapM?.recycle()
        _binding = null
    }
}