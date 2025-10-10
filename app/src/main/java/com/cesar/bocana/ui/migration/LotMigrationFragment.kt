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
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.StockLot
import com.cesar.bocana.databinding.DialogLotConversionBinding
import com.cesar.bocana.databinding.FragmentLotMigrationBinding
import kotlinx.coroutines.flow.collectLatest
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

                if (binding.autoCompleteProduct.adapter == null && state.products.isNotEmpty()) {
                    val productAdapter = ArrayAdapter(requireContext(), android.R.layout.simple_dropdown_item_1line, state.products.map { it.name })
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

        // Lógica especial para Tilapia
        val isTilapiaCase = product.name.contains("tilapia", ignoreCase = true) && product.unit.equals("cajas", ignoreCase = true)

        dialogBinding.textViewDialogTitle.text = "Convertir Lote de ${product.name}"
        dialogBinding.textViewStockActual.text = "Stock Registrado: ${String.format("%.2f", lote.currentQuantity)} ${lote.unit}"

        if(isTilapiaCase){
            dialogBinding.textViewConversionInfo.isVisible = true
            dialogBinding.textViewConversionInfo.text = "¡Atención! Se asumirá que el stock son CAJAS y se calculará el total en Kg."
            dialogBinding.editTextPesoUnidad.setText("4.54")
            dialogBinding.editTextUnidadEmpaque.setText("caja")
        } else {
            // Pre-llenar con datos existentes si ya fue convertido antes
            lote.unidadDeEmpaque?.let { dialogBinding.editTextUnidadEmpaque.setText(it) }
            lote.pesoPorUnidad?.let { dialogBinding.editTextPesoUnidad.setText(it.toString()) }
        }

        val textWatcher = object : TextWatcher {
            override fun afterTextChanged(s: Editable?) {
                val pesoStr = dialogBinding.editTextPesoUnidad.text.toString()
                val peso = pesoStr.toDoubleOrNull() ?: 0.0

                if (isTilapiaCase) {
                    val cajas = lote.currentQuantity
                    val totalKg = cajas * peso
                    dialogBinding.textViewCalculado.text = "Total Convertido: ${String.format("%.2f", totalKg)} Kg"
                } else {
                    val totalKg = lote.currentQuantity
                    val unidades = if (peso > 0) totalKg / peso else 0.0
                    dialogBinding.textViewCalculado.text = "= ${String.format("%.2f", unidades)} Unidades | Total: ${String.format("%.2f", totalKg)} Kg"
                }
            }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
        }
        dialogBinding.editTextPesoUnidad.addTextChangedListener(textWatcher)
        // Disparar el cálculo inicial
        textWatcher.afterTextChanged(null)

        builder.setPositiveButton("Guardar Conversión") { dialog, _ ->
            val unidad = dialogBinding.editTextUnidadEmpaque.text.toString().trim()
            val peso = dialogBinding.editTextPesoUnidad.text.toString().toDoubleOrNull()

            if (unidad.isEmpty() || peso == null || peso <= 0) {
                Toast.makeText(context, "Completa todos los campos con valores válidos.", Toast.LENGTH_LONG).show()
                return@setPositiveButton
            }

            val updates = mutableMapOf<String, Any?>()

            // ***** INICIO DE LA SOLUCIÓN *****
            // Se agrega 'isPackaged' = true para que el sistema reconozca el lote como listo para traspaso.
            // Esto es crucial para que los lotes convertidos sean visibles en la pantalla de planificación.
            updates["isPackaged"] = true
            // ***** FIN DE LA SOLUCIÓN *****

            if(isTilapiaCase){
                val cantidadCajas = lote.currentQuantity
                val totalKg = cantidadCajas * peso
                updates["currentQuantity"] = totalKg
                updates["initialQuantity"] = totalKg // Asumimos que la cantidad inicial también estaba en cajas
                updates["unit"] = "Kg"
                updates["unidadDeEmpaque"] = unidad
                updates["pesoPorUnidad"] = peso
                updates["cantidadInicialUnidades"] = cantidadCajas
            } else {
                val totalKg = lote.currentQuantity
                val unidades = totalKg / peso
                updates["unidadDeEmpaque"] = unidad
                updates["pesoPorUnidad"] = peso
                updates["cantidadInicialUnidades"] = unidades
            }

            viewModel.convertLot(lote, updates)
            dialog.dismiss()
        }

        builder.create().show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}