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
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers

class AjusteCompletoFragment : Fragment() {

    private var _binding: FragmentAjusteCompletoC04Binding? = null
    private val binding get() = _binding!!

    private val viewModel: AjusteCompletoViewModel by viewModels()
    private lateinit var adapter: AjusteCompletoAdapter
    private val stockChanges = mutableMapOf<String, Double?>()
    private var isAdjusting = false

    companion object {
        private const val TAG = "AjusteCompletoFragment"
        private const val PREF_INSTRUCTIONS_SEEN = "instrucciones_vistas_c04"

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
        setupInstructionsCard()
        loadProducts()
    }

    private fun setupInstructionsCard() {
        val prefs = requireContext().getSharedPreferences("ajuste_completo", Context.MODE_PRIVATE)
        val instructionsSeen = prefs.getBoolean(PREF_INSTRUCTIONS_SEEN, false)

        if (!instructionsSeen) {
            binding.cardInstrucciones.visibility = View.VISIBLE
            binding.imageViewCloseInstructions.setOnClickListener {
                binding.cardInstrucciones.visibility = View.GONE
                prefs.edit().putBoolean(PREF_INSTRUCTIONS_SEEN, true).apply()
            }
        } else {
            binding.cardInstrucciones.visibility = View.GONE
        }
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

        // 🔥 OBSERVER SIMPLIFICADO Y SINCRONIZADO
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
                            saveDraft(productId, null) // Limpiar draft inválido
                        }
                    }
                }

                // 🔥 Sincronizar drafts con el adapter
                adapter.setDrafts(validDrafts)
            }

            adapter.submitList(products)
            binding.textViewEmptyList.visibility = if (products.isEmpty()) View.VISIBLE else View.GONE
        }

        viewModel.ajusteResult.observe(viewLifecycleOwner) { result ->
            Log.d(TAG, "Resultado: $result")
            when (result) {
                is AjusteResult.Success -> {
                    Toast.makeText(requireContext(), "✅ Ajuste completado: ${result.movementsCount} producto(s)", Toast.LENGTH_LONG).show()
                    clearAllDrafts()
                    requireActivity().onBackPressedDispatcher.onBackPressed()
                }
                is AjusteResult.Error -> {
                    Toast.makeText(requireContext(), "❌ Error: ${result.message}", Toast.LENGTH_LONG).show()
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
        Log.e(TAG, "🚀 executeAdjustment: INICIO con ${adjustments.size} productos")
        isAdjusting = true
        showProgressOverlay()

        // 🔥 PRUEBA 1: Verificar que el scope existe
        if (lifecycleScope == null) {
            Log.e(TAG, "❌ lifecycleScope es NULL")
            hideProgressOverlay()
            isAdjusting = false
            Toast.makeText(requireContext(), "Error interno: lifecycleScope null", Toast.LENGTH_SHORT).show()
            return
        }

        Log.e(TAG, "✅ lifecycleScope existe, lanzando corrutina...")

        // 🔥 PRUEBA 2: Usar GlobalScope como respaldo (solo para diagnóstico)
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.Main) {
            try {
                Log.e(TAG, "PASO 1: Dentro de GlobalScope.launch")
                Log.e(TAG, "PASO 1b: ViewModel existe? ${viewModel != null}")

                val result = viewModel.executeCompleteAdjustment(adjustments)

                Log.e(TAG, "PASO 2: Resultado recibido: $result")

                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    if (result) {
                        Log.e(TAG, "✅ Éxito, cerrando pantalla")
                        clearAllDrafts()
                        requireActivity().onBackPressedDispatcher.onBackPressed()
                    } else {
                        Log.e(TAG, "❌ Falló la ejecución")
                        hideProgressOverlay()
                        isAdjusting = false
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "❌ Excepción en executeAdjustment", e)
                withContext(kotlinx.coroutines.Dispatchers.Main) {
                    Toast.makeText(requireContext(), "Error: ${e.message}", Toast.LENGTH_LONG).show()
                    hideProgressOverlay()
                    isAdjusting = false
                }
            }
        }

        Log.e(TAG, "🚀 executeAdjustment: Corrutina lanzada, continuando...")
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
        val prefs = requireContext().getSharedPreferences("ajuste_draft", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
        stockChanges.clear()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}