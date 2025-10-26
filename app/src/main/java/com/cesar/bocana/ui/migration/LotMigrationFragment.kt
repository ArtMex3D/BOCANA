package com.cesar.bocana.ui.migration

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogLotConversionBinding
import com.cesar.bocana.databinding.FragmentLotMigrationBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.util.*

class LotMigrationFragment : Fragment() {

    private var _binding: FragmentLotMigrationBinding? = null
    private val binding get() = _binding!!

    private val viewModel: LotMigrationViewModel by viewModels()
    private lateinit var adapter: LotMigrationAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLotMigrationBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupProductSelector()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = LotMigrationAdapter { lote ->
            showConversionDialog(lote)
        }
        binding.recyclerViewLotes.adapter = adapter
    }

    private fun setupProductSelector() {
        binding.autoCompleteProduct.setOnItemClickListener { parent, _, position, _ ->
            val selectedName = parent.getItemAtPosition(position) as String
            val selectedProduct = viewModel.uiState.value.products.find { it.name == selectedName }
            selectedProduct?.let {
                viewModel.fetchLotesForProduct(it)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.collectLatest { state ->
                binding.progressBarLotes.isVisible = state.isLoading
                binding.textViewMessage.text = state.message
                binding.textViewMessage.isVisible = state.message != null

                adapter.submitList(state.lotsForProduct)
                binding.recyclerViewLotes.isVisible = state.lotsForProduct.isNotEmpty()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.uiState.map { it.products }.distinctUntilChanged().collect { products ->
                if (products.isNotEmpty()) {
                    val productAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, products.map { it.name })
                    binding.autoCompleteProduct.setAdapter(productAdapter)
                }
            }
        }
    }

    private fun showConversionDialog(lote: StockLot) {
        val dialogBinding = DialogLotConversionBinding.inflate(LayoutInflater.from(context))
        val builder = AlertDialog.Builder(requireContext())
            .setView(dialogBinding.root)
            .setNegativeButton("Cancelar", null)

        val product = viewModel.uiState.value.selectedProduct ?: return

        dialogBinding.textViewDialogTitle.text = "Convertir Lote de ${product.name}"
        dialogBinding.textViewStockActual.text = "Stock Registrado: ${String.format("%.2f", lote.currentQuantity)} ${lote.unit}"


        lote.unidadDeEmpaque?.let { dialogBinding.editTextUnidadEmpaque.setText(it) }
        lote.pesoPorUnidad?.let { dialogBinding.editTextPesoFijo.setText(it.toString()) }


        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                updateCalculoResultado(dialogBinding, lote.currentQuantity)
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }

        dialogBinding.radioGroupCalculationType.setOnCheckedChangeListener { _, checkedId ->
            val isRedondeo = checkedId == R.id.radioButtonRedondeo
            val isVariable = checkedId == R.id.radioButtonVariable
            dialogBinding.textFieldLayoutPesoFijo.isVisible = isRedondeo
            dialogBinding.textFieldLayoutCantidadUnidades.isVisible = !isVariable
            dialogBinding.textInputLayoutUnidadEmpaque.isVisible = !isVariable
            updateCalculoResultado(dialogBinding, lote.currentQuantity)
        }
        // Set initial visibility
        dialogBinding.radioButtonVariable.isChecked = true
        dialogBinding.textFieldLayoutPesoFijo.isVisible = false
        dialogBinding.textFieldLayoutCantidadUnidades.isVisible = false
        dialogBinding.textInputLayoutUnidadEmpaque.isVisible = false


        dialogBinding.editTextCantidadUnidades.addTextChangedListener(textWatcher)
        dialogBinding.editTextPesoFijo.addTextChangedListener(textWatcher)
        updateCalculoResultado(dialogBinding, lote.currentQuantity)

        builder.setPositiveButton("Guardar Conversión") { dialog, _ ->
            val unidad = dialogBinding.editTextUnidadEmpaque.text.toString().trim()
            val cantidadUnidades = dialogBinding.editTextCantidadUnidades.text.toString().toIntOrNull()
            val pesoFijo = dialogBinding.editTextPesoFijo.text.toString().toDoubleOrNull()
            val isRedondeo = dialogBinding.radioButtonRedondeo.isChecked
            val isVariable = dialogBinding.radioButtonVariable.isChecked
            val isPromedio = dialogBinding.radioButtonPromedio.isChecked

            if (!isVariable) {
                if (unidad.isEmpty()) {
                    Toast.makeText(context, "Define un tipo de empaque.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (cantidadUnidades == null || cantidadUnidades <= 0) {
                    Toast.makeText(context, "La cantidad de unidades debe ser mayor a cero.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if(isRedondeo && (pesoFijo == null || pesoFijo <= 0)) {
                    Toast.makeText(context, "El Peso Fijo es obligatorio en modo redondeo.", Toast.LENGTH_LONG).show()
                    return@setPositiveButton
                }
                if (isRedondeo && cantidadUnidades > 1 && pesoFijo != null) {
                    if ((cantidadUnidades - 1) * pesoFijo >= lote.currentQuantity) {
                        Toast.makeText(context, "Incongruencia: El total de las unidades fijas supera el stock a granel.", Toast.LENGTH_LONG).show()
                        return@setPositiveButton
                    }
                }
            }

            viewModel.convertLot(lote, isRedondeo, isVariable, isPromedio, unidad, cantidadUnidades, pesoFijo)
            dialog.dismiss()
        }

        builder.create().show()
    }

    private fun updateCalculoResultado(dialogBinding: DialogLotConversionBinding, totalKg: Double) {
        val cantidadUnidades = dialogBinding.editTextCantidadUnidades.text.toString().toIntOrNull() ?: 0
        val unidadEmpaque = dialogBinding.editTextUnidadEmpaque.text.toString().trim().ifEmpty { "Unidad" }

        dialogBinding.textViewCalculado.isVisible = false
        if (cantidadUnidades <= 0 || totalKg <= 0 || dialogBinding.radioButtonVariable.isChecked) return

        if (dialogBinding.radioButtonPromedio.isChecked) {
            val promedio = totalKg / cantidadUnidades
            dialogBinding.textViewCalculado.text = String.format(Locale.getDefault(), "= %.2f Kg promedio por %s", promedio, unidadEmpaque)
            dialogBinding.textViewCalculado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
            dialogBinding.textViewCalculado.isVisible = true
        } else if (dialogBinding.radioButtonRedondeo.isChecked) {
            val pesoFijo = dialogBinding.editTextPesoFijo.text.toString().toDoubleOrNull() ?: 0.0
            if (pesoFijo <= 0) return

            if (cantidadUnidades == 1) {
                dialogBinding.textViewCalculado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
                dialogBinding.textViewCalculado.text = String.format(Locale.getDefault(), "= 1 %s de %.2f Kg", unidadEmpaque, totalKg)
            } else {
                val cajasNormales = cantidadUnidades - 1
                val totalEnCajasNormales = cajasNormales * pesoFijo
                val pesoUltimaCaja = totalKg - totalEnCajasNormales

                if (pesoUltimaCaja <= 0) {
                    dialogBinding.textViewCalculado.text = "Incongruencia: El peso es mayor al disponible."
                    dialogBinding.textViewCalculado.setTextColor(ContextCompat.getColor(requireContext(), R.color.negative_red))
                } else {
                    dialogBinding.textViewCalculado.setTextColor(ContextCompat.getColor(requireContext(), R.color.purple_700))
                    dialogBinding.textViewCalculado.text = String.format(Locale.getDefault(), "= %d %ss de %.2f Kg y 1 %s de %.2f Kg", cajasNormales, unidadEmpaque, pesoFijo, unidadEmpaque, pesoUltimaCaja)
                }
            }
            dialogBinding.textViewCalculado.isVisible = true
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

