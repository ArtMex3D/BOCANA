package com.cesar.bocana.ui.printing

import android.app.DatePickerDialog
import android.os.Build
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
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.databinding.FragmentPrintLabelConfigBinding
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

class PrintLabelConfigFragment : Fragment() {

    private var _binding: FragmentPrintLabelConfigBinding? = null
    private val binding get() = _binding!!

    private var labelType: LabelType = LabelType.SIMPLE
    private var preselectedTemplate: LabelTemplate? = null
    private var initialData: LabelData? = null
    private var editingActiveId: String? = null
    private val selectedDateCalendar = Calendar.getInstance()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private var productsList = listOf<Product>()
    private var selectedProduct: Product? = null
    private val units = listOf("Kg", "Pzas", "Cajas", "Bolsas", "Costales")

    companion object {
        private const val ARG_LABEL_TYPE = "label_type"
        private const val ARG_TEMPLATE = "template"
        private const val ARG_INITIAL = "initial_data"
        private const val ARG_EDITING_ID = "editing_active_id"

        fun newInstance(
            labelType: LabelType,
            template: LabelTemplate? = null,
            initialData: LabelData? = null,
            editingActiveId: String? = null
        ) = PrintLabelConfigFragment().apply {
            arguments = Bundle().apply {
                putSerializable(ARG_LABEL_TYPE, labelType)
                template?.let { putParcelable(ARG_TEMPLATE, it) }
                initialData?.let { putParcelable(ARG_INITIAL, it) }
                editingActiveId?.let { putString(ARG_EDITING_ID, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let { args ->
            labelType = args.getSerializable(ARG_LABEL_TYPE) as? LabelType ?: LabelType.SIMPLE
            preselectedTemplate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_TEMPLATE, LabelTemplate::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_TEMPLATE)
            }
            initialData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_INITIAL, LabelData::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_INITIAL)
            }
            editingActiveId = args.getString(ARG_EDITING_ID)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintLabelConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupUi()
        setupListeners()
        setupUnitSpinner()
        if (labelType != LabelType.SIMPLE) loadProducts() else applyInitialDataIfAny()
        updatePreview()
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = parentFragmentManager.popBackStack()
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = when (labelType) {
                LabelType.SIMPLE -> "Etiqueta simple"
                LabelType.COSTAL -> "Etiqueta de costal"
                LabelType.DETAILED -> "Etiqueta para cajas"
            }
            subtitle = preselectedTemplate?.let { "${it.columns}x${it.rows} · ${it.totalLabels} por hoja" } ?: "Configura los datos"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun setupUi() {
        val isDetailed = labelType == LabelType.DETAILED
        val hasProduct = labelType != LabelType.SIMPLE
        binding.textViewConfigTitle.text = when (labelType) {
            LabelType.SIMPLE -> "Simple · proveedor y fecha"
            LabelType.COSTAL -> "Costal · producto, proveedor y fecha"
            LabelType.DETAILED -> "Cajas · producto y empaque"
        }
        binding.textFieldLayoutProduct.isVisible = hasProduct
        binding.textViewSuggestion.isVisible = false
        binding.textFieldLayoutDetail.isVisible = isDetailed
        binding.textViewWeightLabel.isVisible = isDetailed
        binding.radioGroupWeightType.isVisible = isDetailed
        binding.layoutWeightAndUnit.isVisible = isDetailed
        binding.textFieldLayoutWeight.isVisible = isDetailed && binding.radioButtonPredefinedWeight.isChecked
        binding.buttonConfigContinue.text = if (preselectedTemplate == null) "Elegir cantidad por hoja" else "Generar etiquetas"
        binding.buttonSelectDate.text = dateFormat.format(selectedDateCalendar.time)
    }

    private fun setupListeners() {
        binding.buttonSelectDate.setOnClickListener { showDatePicker() }
        binding.radioGroupWeightType.setOnCheckedChangeListener { _, checkedId ->
            val predefined = checkedId == R.id.radioButtonPredefinedWeight
            binding.layoutWeightAndUnit.isVisible = labelType == LabelType.DETAILED
            binding.textFieldLayoutWeight.isVisible = predefined
            if (!predefined) binding.editTextWeight.text = null
            updatePreview()
        }
        binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
            val name = parent.getItemAtPosition(position) as? String
            selectedProduct = productsList.firstOrNull { it.name == name }
            if (labelType == LabelType.DETAILED) binding.autoCompleteUnit.setText(selectedProduct?.unit ?: "", false)
            selectedProduct?.let { suggestOldestPending(it.id) }
            updatePreview()
        }
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = updatePreview()
            override fun afterTextChanged(s: Editable?) {}
        }
        binding.editTextSupplier.addTextChangedListener(watcher)
        binding.editTextDetail.addTextChangedListener(watcher)
        binding.editTextWeight.addTextChangedListener(watcher)
        binding.autoCompleteUnit.addTextChangedListener(watcher)
        binding.buttonConfigContinue.setOnClickListener { validateAndContinue() }
    }

    private fun setupUnitSpinner() {
        binding.autoCompleteUnit.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, units))
    }

    private fun loadProducts() {
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                productsList = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext().applicationContext).productDao().getAllActiveProductsStream().first()
                }
                if (_binding == null) return@launch
                binding.autoCompleteProduct.setAdapter(ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, productsList.map { it.name }))
                applyInitialDataIfAny()
            } catch (e: Exception) {
                Log.e("LabelConfig", "No se pudieron cargar productos", e)
                applyInitialDataIfAny()
            }
        }
    }

    private fun applyInitialDataIfAny() {
        val data = initialData ?: return
        data.productName?.let { name ->
            binding.autoCompleteProduct.setText(name, false)
            selectedProduct = productsList.firstOrNull { it.id == data.productId } ?: productsList.firstOrNull { it.name == name }
        }
        binding.editTextSupplier.setText(data.supplierName.orEmpty())
        binding.editTextDetail.setText(data.detail.orEmpty())
        selectedDateCalendar.time = data.date
        binding.buttonSelectDate.text = dateFormat.format(data.date)
        if (labelType == LabelType.DETAILED) {
            binding.autoCompleteUnit.setText(data.unit.orEmpty(), false)
            if (data.weight == "Manual" || data.weight.isNullOrBlank()) {
                binding.radioButtonManualWeight.isChecked = true
            } else {
                binding.radioButtonPredefinedWeight.isChecked = true
                binding.editTextWeight.setText(data.weight)
            }
        }
        updatePreview()
    }

    private fun suggestOldestPending(productId: String) {
        if (initialData != null) return
        viewLifecycleOwner.lifecycleScope.launch {
            val task = withContext(Dispatchers.IO) {
                runCatching {
                    AppDatabase.getDatabase(requireContext().applicationContext)
                        .packagingDao().getAllPackagingTasksStream().first()
                        .filter { it.productId == productId }
                        .minByOrNull { it.receivedAt?.time ?: Long.MAX_VALUE }
                }.getOrNull()
            } ?: return@launch
            if (_binding == null) return@launch
            task.supplierName?.takeIf { it.isNotBlank() }?.let { binding.editTextSupplier.setText(it) }
            task.receivedAt?.let {
                selectedDateCalendar.time = it
                binding.buttonSelectDate.text = dateFormat.format(it)
            }
            binding.textViewSuggestion.text = "Sugerido por antigüedad · ${task.supplierName ?: "Sin proveedor"} · ${task.receivedAt?.let { d -> dateFormat.format(d) } ?: "sin fecha"}"
            binding.textViewSuggestion.isVisible = true
            updatePreview()
        }
    }

    private fun showDatePicker() {
        DatePickerDialog(
            requireContext(),
            { _, y, m, d ->
                selectedDateCalendar.set(y, m, d)
                binding.buttonSelectDate.text = dateFormat.format(selectedDateCalendar.time)
                updatePreview()
            },
            selectedDateCalendar.get(Calendar.YEAR),
            selectedDateCalendar.get(Calendar.MONTH),
            selectedDateCalendar.get(Calendar.DAY_OF_MONTH)
        ).show()
    }

    private fun currentData(): LabelData {
        val detailed = labelType == LabelType.DETAILED
        val weight = if (detailed) {
            if (binding.radioButtonPredefinedWeight.isChecked) binding.editTextWeight.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } else "Manual"
        } else null
        return LabelData(
            labelType = labelType,
            productId = selectedProduct?.id,
            productName = selectedProduct?.name ?: binding.autoCompleteProduct.text.toString().trim().ifEmpty { null },
            supplierName = binding.editTextSupplier.text?.toString()?.trim(),
            date = selectedDateCalendar.time,
            weight = weight,
            unit = if (detailed) binding.autoCompleteUnit.text.toString().trim() else null,
            detail = if (detailed) binding.editTextDetail.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() } else null
        )
    }

    private fun updatePreview() {
        if (_binding == null) return
        binding.previewView.updateView(currentData(), null, null)
    }

    private fun validateAndContinue() {
        val data = currentData()
        var valid = true
        if (labelType != LabelType.SIMPLE && selectedProduct == null && data.productName.isNullOrBlank()) {
            binding.textFieldLayoutProduct.error = "Selecciona un producto"; valid = false
        } else binding.textFieldLayoutProduct.error = null
        if (data.supplierName.isNullOrBlank()) {
            binding.textFieldLayoutSupplier.error = "Proveedor requerido"; valid = false
        } else binding.textFieldLayoutSupplier.error = null
        if (labelType == LabelType.DETAILED) {
            if (data.unit.isNullOrBlank()) { binding.textFieldLayoutUnit.error = "Unidad requerida"; valid = false } else binding.textFieldLayoutUnit.error = null
            if (binding.radioButtonPredefinedWeight.isChecked && (data.weight?.toDoubleOrNull() ?: 0.0) <= 0.0) {
                binding.textFieldLayoutWeight.error = "Cantidad inválida"; valid = false
            } else binding.textFieldLayoutWeight.error = null
        }
        if (!valid) return

        val template = preselectedTemplate
        if (template == null) {
            parentFragmentManager.beginTransaction()
                .replace(R.id.nav_host_fragment_content_main, PrintLabelLayoutFragment.newInstance(data))
                .addToBackStack(null)
                .commit()
        } else generate(data, template)
    }

    private fun generate(data: LabelData, template: LabelTemplate) {
        binding.progressBarConfig.isVisible = true
        binding.buttonConfigContinue.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { PdfGenerator.createSingleLabelPdf(requireContext(), data, template) }
                val flow = when (labelType) {
                    LabelType.SIMPLE -> LabelFlowType.SIMPLE
                    LabelType.COSTAL -> LabelFlowType.COSTAL
                    LabelType.DETAILED -> LabelFlowType.FIXED_DETAILED
                }
                val record = ActiveLabelStore.saveSingle(requireContext(), pdf, flow, template, data, editingActiveId)
                ActiveLabelStore.syncRecordToFirestore(requireContext(), record.id)
                openViewer(ActiveLabelStore.get(requireContext(), record.id) ?: record)
            } catch (e: Exception) {
                Log.e("LabelConfig", "Error generando etiquetas", e)
                Toast.makeText(context, "No se pudo generar el PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.progressBarConfig.isVisible = false
                    binding.buttonConfigContinue.isEnabled = true
                }
            }
        }
    }

    private fun openViewer(record: ActiveLabelRecord) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, PdfViewerFragment.newInstance(record.pdfPath, record.id, record.title))
            .addToBackStack(null)
            .commit()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
