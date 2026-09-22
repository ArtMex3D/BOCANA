package com.cesar.bocana.ui.report

import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.ReportColumn
import com.cesar.bocana.data.model.ReportConfig
import com.cesar.bocana.databinding.FragmentReportConfigBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ReportConfigFragment : Fragment() {

    private var _binding: FragmentReportConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var productAdapter: ReportProductAdapter
    private var allProducts = listOf<Product>()
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentReportConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Generar reporte"
            subtitle = "Inventario y consumo"
        }

        setupRecyclerView()
        setupListeners()
        updateConsumptionOptions()
        loadProducts()
    }

    private fun setupRecyclerView() {
        productAdapter = ReportProductAdapter { }
        binding.recyclerViewProducts.adapter = productAdapter
    }

    private fun setupListeners() {
        binding.editTextSearchProduct.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterProducts(s.toString())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

        binding.buttonSelectAll.setOnClickListener {
            productAdapter.setSelectedIds(allProducts.map { it.id }.toSet())
        }

        binding.buttonDeselectAll.setOnClickListener {
            productAdapter.setSelectedIds(emptySet())
        }

        binding.chipConsumo.setOnCheckedChangeListener { _, _ -> updateConsumptionOptions() }

        binding.fabGenerateReport.setOnClickListener { generateReport() }
        binding.fabShareWhatsapp.setOnClickListener { shareToWhatsApp() }
    }

    private fun updateConsumptionOptions() {
        binding.layoutConsumoOptions.isVisible = binding.chipConsumo.isChecked
        if (!binding.chipConsumo.isChecked) {
            binding.checkboxConsumoSemanal.isChecked = false
            binding.checkboxConsumoMensual.isChecked = false
        }
    }

    private fun loadProducts() {
        showLoading(true)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                allProducts = withContext(Dispatchers.IO) {
                    AppDatabase.getDatabase(requireContext().applicationContext)
                        .productDao()
                        .getAllActiveProductsStream()
                        .first()
                        .sortedBy { it.name.lowercase(Locale.getDefault()) }
                }
                if (_binding == null) return@launch
                filterProducts("")
            } catch (e: Exception) {
                if (_binding != null) {
                    Toast.makeText(requireContext(), "No se pudieron cargar los productos", Toast.LENGTH_SHORT).show()
                }
            } finally {
                if (_binding != null) showLoading(false)
            }
        }
    }

    private fun filterProducts(query: String) {
        val filtered = if (query.isBlank()) {
            allProducts
        } else {
            allProducts.filter { it.name.contains(query, ignoreCase = true) }
        }
        productAdapter.submitList(filtered)
    }

    private fun generateReport() {
        val selectedProductIds = productAdapter.getSelectedIds().toList()
        if (selectedProductIds.isEmpty()) {
            Toast.makeText(context, "Selecciona al menos un producto", Toast.LENGTH_SHORT).show()
            return
        }

        val selectedColumns = mutableListOf<ReportColumn>()
        if (binding.chipStockC04.isChecked) selectedColumns.add(ReportColumn.STOCK_C04)
        if (binding.chipStockMatriz.isChecked) selectedColumns.add(ReportColumn.STOCK_MATRIZ)
        if (binding.chipStockTotal.isChecked) selectedColumns.add(ReportColumn.STOCK_TOTAL)
        if (binding.chipLastUpdate.isChecked) selectedColumns.add(ReportColumn.ULTIMA_ACTUALIZACION)

        if (binding.chipConsumo.isChecked) {
            val weekly = binding.checkboxConsumoSemanal.isChecked
            val monthly = binding.checkboxConsumoMensual.isChecked
            if (!weekly && !monthly) {
                Toast.makeText(context, "Selecciona consumo semanal, mensual o ambos", Toast.LENGTH_SHORT).show()
                return
            }
            if (weekly) selectedColumns.add(ReportColumn.CONSUMO_SEMANAL)
            if (monthly) selectedColumns.add(ReportColumn.CONSUMO_MENSUAL)
        }

        if (binding.chipSeAgota.isChecked) selectedColumns.add(ReportColumn.SE_AGOTA_EN)

        if (selectedColumns.isEmpty()) {
            Toast.makeText(context, "Selecciona al menos un dato para el reporte", Toast.LENGTH_SHORT).show()
            return
        }

        val config = ReportConfig(
            productIds = selectedProductIds,
            columns = selectedColumns,
            dateRange = null,
            reportTitle = "Existencias ${dateFormat.format(Date())}"
        )

        showLoading(true)
        lifecycleScope.launch {
            try {
                ReportGenerator.generatePdf(requireContext(), config)
            } catch (e: Exception) {
                Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) showLoading(false)
            }
        }
    }

    private fun showLoading(isLoading: Boolean) {
        binding.progressBar.isVisible = isLoading
        binding.fabGenerateReport.isEnabled = !isLoading
        binding.fabShareWhatsapp.isEnabled = !isLoading
    }

    private fun shareToWhatsApp() {
        val selectedProductIds = productAdapter.getSelectedIds().toList()
        if (selectedProductIds.isEmpty()) {
            Toast.makeText(context, "Selecciona al menos un producto", Toast.LENGTH_SHORT).show()
            return
        }

        val mostrarMatriz = binding.chipStockMatriz.isChecked
        val mostrarC04 = binding.chipStockC04.isChecked
        val mostrarTotal = binding.chipStockTotal.isChecked
        val productosSeleccionados = allProducts.filter { it.id in selectedProductIds }
        if (productosSeleccionados.isEmpty()) return

        fun numeroSeguro(valor: Double): String {
            val rounded = if (kotlin.math.abs(valor - kotlin.math.round(valor)) < 0.005) {
                "%.0f".format(valor)
            } else {
                "%.1f".format(valor)
            }
            return rounded.replace(".", ".\u200B").replace(",", ",\u200B")
        }

        val sb = StringBuilder("*REPORTE DE STOCK*\n\n")
        productosSeleccionados.forEach { producto ->
            if (mostrarTotal && !mostrarMatriz && !mostrarC04) {
                sb.append("- *${producto.name}* : ${numeroSeguro(producto.totalStock)} ${producto.unit}\n")
            } else {
                sb.append("*${producto.name}*\n")
                if (mostrarC04) sb.append(" ├ C04 : ${numeroSeguro(producto.stockCongelador04)} ${producto.unit}\n")
                if (mostrarMatriz) sb.append(" ├ Matriz : ${numeroSeguro(producto.stockMatriz)} ${producto.unit}\n")
                if (mostrarTotal) sb.append(" └ *TOTAL : ${numeroSeguro(producto.totalStock)} ${producto.unit}*\n")
                sb.append("\n")
            }
        }

        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            setPackage("com.whatsapp")
        }

        try {
            startActivity(sendIntent)
        } catch (_: Exception) {
            try {
                sendIntent.setPackage("com.whatsapp.w4b")
                startActivity(sendIntent)
            } catch (_: Exception) {
                Toast.makeText(context, "No se encontró WhatsApp instalado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
