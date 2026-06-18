package com.cesar.bocana.ui.ajustecompleto

import android.content.Context
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.InputMethodManager
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentAjusteCompletoC04Binding
import com.cesar.bocana.ui.dialogs.DialogConfirmarAjuste
import kotlinx.coroutines.launch

class AjusteCompletoFragment : Fragment() {

    private var _binding: FragmentAjusteCompletoC04Binding? = null
    private val binding get() = _binding!!

    private val viewModel: AjusteCompletoViewModel by viewModels()
    private lateinit var adapter: AjusteCompletoAdapter
    private val stockChanges = mutableMapOf<String, Double?>()
    private var isAdjusting = false

    companion object {
        private const val TAG = "AjusteCompletoFragment"
        fun newInstance() = AjusteCompletoFragment()
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentAjusteCompletoC04Binding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        Log.d(TAG, "onViewCreated: INICIO")

        setupRecyclerView()
        setupListeners()
        observeViewModel()
        loadProducts()
    }

    private fun setupRecyclerView() {
        adapter = AjusteCompletoAdapter(
            onStockChanged = { productId, physicalStock, maxStock ->
                if (physicalStock != null) {
                    stockChanges[productId] = physicalStock
                    saveDraft(productId, physicalStock)
                    Log.d(TAG, "Stock actualizado: $physicalStock (max: $maxStock)")
                } else {
                    stockChanges.remove(productId)
                    saveDraft(productId, null)
                    Log.d(TAG, "Stock eliminado")
                }
            },
            onEditorActionNext = { position ->
                binding.recyclerViewAjusteCompleto.smoothScrollToPosition(position)
                binding.recyclerViewAjusteCompleto.postDelayed({
                    val targetHolder = binding.recyclerViewAjusteCompleto.findViewHolderForAdapterPosition(position)
                    targetHolder?.itemView?.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextPhysicalStock)?.apply {
                        requestFocus()
                        val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                        imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
                    }
                }, 150)
            },
            onEditorActionDone = {
                val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                imm.hideSoftInputFromWindow(binding.root.windowToken, 0)
            }
        )

        binding.recyclerViewAjusteCompleto.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@AjusteCompletoFragment.adapter
        }
    }

    private fun setupListeners() {
        binding.buttonRevisarAjuste.setOnClickListener {
            Log.d(TAG, "========================================")
            Log.d(TAG, "Botón REVISAR AJUSTE clickeado")
            if (isAdjusting) {
                Toast.makeText(requireContext(), "Ajuste en progreso, espera...", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            binding.root.requestFocus()
            val imm = requireContext().getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            imm.hideSoftInputFromWindow(binding.root.windowToken, 0)

            binding.root.postDelayed({
                val adjustments = buildAdjustmentsMap()
                Log.d(TAG, "Ajustes encontrados: ${adjustments.size} productos")

                if (adjustments.isEmpty()) {
                    Toast.makeText(requireContext(), "No hay cambios para ajustar", Toast.LENGTH_SHORT).show()
                    return@postDelayed
                }

                val productsList = adapter.currentList
                val dialog = DialogConfirmarAjuste.newInstance(productsList, adjustments)
                dialog.setOnConfirmListener { confirmedAdjustments ->
                    Log.d(TAG, "Confirmado, ejecutando ajuste...")
                    executeAdjustment(confirmedAdjustments)
                }
                dialog.show(parentFragmentManager, DialogConfirmarAjuste.TAG)
            }, 100)
        }

        binding.buttonCancelProcess.setOnClickListener {
            Log.d(TAG, "Cancelación manual")
            isAdjusting = false
            hideProgressOverlay()
            Toast.makeText(requireContext(), "Ajuste cancelado", Toast.LENGTH_SHORT).show()
        }
    }

    private fun buildAdjustmentsMap(): Map<String, Double> {
        val adjustments = mutableMapOf<String, Double>()

        for ((productId, physicalStock) in stockChanges) {
            if (physicalStock != null) {
                val product = adapter.currentList.find { it.id == productId }
                if (product != null) {
                    val difference = kotlin.math.abs(product.stockCongelador04 - physicalStock)
                    if (difference > 0.01 && physicalStock <= product.stockCongelador04 + 0.01) {
                        adjustments[productId] = physicalStock
                        Log.d(TAG, "  ${product.name}: ${product.stockCongelador04} → $physicalStock")
                    }
                }
            }
        }
        return adjustments
    }

    private fun observeViewModel() {
        viewModel.isLoading.observe(viewLifecycleOwner) { isLoading ->
            Log.d(TAG, "isLoading: $isLoading")
            if (!isLoading && isAdjusting) {
                isAdjusting = false
                hideProgressOverlay()
            }
        }

        viewModel.progressMessage.observe(viewLifecycleOwner) { message ->
            if (message.isNotEmpty()) {
                binding.textViewProgressMessage.text = message
                binding.textViewProgressMessage.visibility = View.VISIBLE
            } else {
                binding.textViewProgressMessage.visibility = View.GONE
            }
        }

        viewModel.products.observe(viewLifecycleOwner) { products ->
            Log.d(TAG, "Products recibidos: ${products.size}")

            if (products.isNotEmpty()) {
                val drafts = loadDrafts()
                val validDrafts = mutableMapOf<String, Double>()

                drafts.forEach { (productId, stock) ->
                    if (stock != null) {
                        val product = products.find { it.id == productId }
                        if (product != null && stock <= product.stockCongelador04 + 0.01) {
                            validDrafts[productId] = stock
                            stockChanges[productId] = stock
                            Log.d(TAG, "Draft válido restaurado: ${product.name} = $stock")
                        } else {
                            Log.w(TAG, "Draft inválido ignorado para $productId")
                            saveDraft(productId, null)
                        }
                    }
                }

                adapter.setDrafts(validDrafts)
            }

            adapter.submitList(products)
            binding.textViewEmptyList.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.ajusteResult.observe(viewLifecycleOwner) { result ->
            Log.d(TAG, "Resultado: $result")
            when (result) {
                is AjusteResult.Success -> {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "✅ Ajuste completado: ${result.movementsCount} producto(s)", Toast.LENGTH_LONG).show()
                    }
                    clearAllDrafts()
                    if (isAdded && activity != null && !requireActivity().isFinishing) {
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    }
                    isAdjusting = false
                }
                is AjusteResult.Error -> {
                    if (isAdded && context != null) {
                        Toast.makeText(requireContext(), "❌ Error: ${result.message}", Toast.LENGTH_LONG).show()
                    }
                    hideProgressOverlay()
                    isAdjusting = false
                }
            }
        }
    }

    private fun showProgressOverlay() {
        binding.viewOverlay.visibility = View.VISIBLE
        binding.progressBarAjusteCompleto.visibility = View.VISIBLE
        binding.textViewProgressMessage.visibility = View.VISIBLE
        binding.buttonCancelProcess.visibility = View.VISIBLE
        binding.buttonRevisarAjuste.isEnabled = false
    }

    private fun hideProgressOverlay() {
        binding.viewOverlay.visibility = View.GONE
        binding.progressBarAjusteCompleto.visibility = View.GONE
        binding.textViewProgressMessage.visibility = View.GONE
        binding.buttonCancelProcess.visibility = View.GONE
        binding.buttonRevisarAjuste.isEnabled = true
    }

    private fun loadProducts() {
        lifecycleScope.launch {
            viewModel.loadActiveProductsWithStockInC04()
        }
    }

    private fun executeAdjustment(adjustments: Map<String, Double>) {
        Log.d(TAG, "🚀 executeAdjustment: INICIO con ${adjustments.size} productos")
        isAdjusting = true
        showProgressOverlay()

        // ✅ Solo llama al ViewModel. El observer maneja el resultado.
        // ✅ Si hay JobCancellationException, se ignora porque el fragmento ya se cerró.
        lifecycleScope.launch {
            viewModel.executeCompleteAdjustment(adjustments)
        }
    }

    private fun saveDraft(productId: String, physicalStock: Double?) {
        val prefs = requireContext().getSharedPreferences("ajuste_draft", Context.MODE_PRIVATE)
        if (physicalStock != null) {
            prefs.edit().putString(productId, physicalStock.toString()).apply()
        } else {
            prefs.edit().remove(productId).apply()
        }
    }

    private fun loadDrafts(): Map<String, Double?> {
        val prefs = requireContext().getSharedPreferences("ajuste_draft", Context.MODE_PRIVATE)
        return prefs.all.mapNotNull { (key, value) ->
            if (key != "last_product") key to (value as String).toDoubleOrNull()
            else null
        }.toMap()
    }

    private fun clearAllDrafts() {
        if (!isAdded || context == null) {
            Log.w(TAG, "clearAllDrafts: Fragmento no attachado, saltando")
            return
        }
        val prefs = requireContext().getSharedPreferences("ajuste_draft", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        stockChanges.clear()
        Log.d(TAG, "clearAllDrafts: Drafts limpiados")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}