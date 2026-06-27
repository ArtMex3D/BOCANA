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
import com.cesar.bocana.R
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.ReportColumn
import com.cesar.bocana.data.model.ReportConfig
import com.cesar.bocana.databinding.FragmentReportConfigBinding
import com.google.android.material.datepicker.MaterialDatePicker
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObjects
import com.google.firebase.ktx.Firebase
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone

class ReportConfigFragment : Fragment() {

    private var _binding: FragmentReportConfigBinding? = null
    private val binding get() = _binding!!

    private lateinit var productAdapter: ReportProductAdapter
    private var allProducts = listOf<Product>()
    private var selectedDateRange: Pair<Date, Date>? = null
    private val db = Firebase.firestore
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentReportConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Configurar"

        setupRecyclerView()
        setupListeners()
        loadProducts()
    }

    private fun setupRecyclerView() {
        productAdapter = ReportProductAdapter { selectedIds ->
            // Opcional: actualizar un contador de seleccionados si se desea
        }
        binding.recyclerViewProducts.adapter = productAdapter
    }

    private fun setupListeners() {
        binding.editTextSearchProduct.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                filterProducts(s.toString())
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        binding.buttonSelectAll.setOnClickListener {
            productAdapter.setSelectedIds(allProducts.map { it.id }.toSet())
        }

        binding.buttonDeselectAll.setOnClickListener {
            productAdapter.setSelectedIds(emptySet())
        }

        binding.chipConsumo.setOnCheckedChangeListener { _, isChecked ->
            binding.layoutDateRange.isVisible = isChecked
            if (!isChecked) {
                selectedDateRange = null
                binding.buttonDateRange.text = "Seleccionar rango de fechas"
            }
        }

        binding.buttonDateRange.setOnClickListener {
            showDateRangePicker()
        }

        binding.fabGenerateReport.setOnClickListener {
            generateReport()
        }
        // 🔥 NUEVO: Clic para WhatsApp
        binding.fabShareWhatsapp.setOnClickListener {
            shareToWhatsApp()
        }
    }

    private fun loadProducts() {
        showLoading(true)
        db.collection("products")
            .whereEqualTo("isActive", true)
            .orderBy("name")
            .get()
            .addOnSuccessListener { snapshot ->
                if (!isAdded) return@addOnSuccessListener
                allProducts = snapshot.toObjects()
                filterProducts("")
                showLoading(false)
            }
            .addOnFailureListener {
                if (!isAdded) return@addOnFailureListener
                showLoading(false)
                Toast.makeText(context, "Error al cargar productos", Toast.LENGTH_SHORT).show()
            }
    }

    private fun filterProducts(query: String) {
        val filteredList = if (query.isBlank()) {
            allProducts
        } else {
            allProducts.filter { it.name.contains(query, ignoreCase = true) }
        }
        productAdapter.submitList(filteredList)
    }

    // --- FUNCIÓN CORREGIDA ---
    private fun showDateRangePicker() {
        val datePicker = MaterialDatePicker.Builder.dateRangePicker()
            .setTitleText("Selecciona un rango de fechas")
            .setSelection(androidx.core.util.Pair(MaterialDatePicker.thisMonthInUtcMilliseconds(), MaterialDatePicker.todayInUtcMilliseconds()))
            .build()

        datePicker.addOnPositiveButtonClickListener { selection ->
            // El picker devuelve UTC. Lo ajustamos para que abarque el día completo en la zona horaria local.
            val tz = TimeZone.getDefault()

            // Fecha de Inicio: al principio del día
            val startCal = Calendar.getInstance(tz).apply {
                timeInMillis = selection.first
                set(Calendar.HOUR_OF_DAY, 0)
                set(Calendar.MINUTE, 0)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            val startDate = startCal.time

            // Fecha de Fin: al final del día
            val endCal = Calendar.getInstance(tz).apply {
                timeInMillis = selection.second
                set(Calendar.HOUR_OF_DAY, 23)
                set(Calendar.MINUTE, 59)
                set(Calendar.SECOND, 59)
                set(Calendar.MILLISECOND, 999)
            }
            val endDate = endCal.time

            selectedDateRange = Pair(startDate, endDate)
            binding.buttonDateRange.text = "${dateFormat.format(startDate)} - ${dateFormat.format(endDate)}"
        }

        datePicker.show(parentFragmentManager, "DATE_PICKER")
    }

    private fun generateReport() {
        val selectedProductIds = productAdapter.getSelectedIds().toList()
        if (selectedProductIds.isEmpty()) {
            Toast.makeText(context, "Debes seleccionar al menos un producto", Toast.LENGTH_SHORT).show()
            return
        }

        // --- CAMBIO AQUÍ: Se añade la lectura del chip de "Unidad" ---
        val selectedColumns = mutableListOf<ReportColumn>()
        if(binding.chipStockMatriz.isChecked) selectedColumns.add(ReportColumn.STOCK_MATRIZ)
        if(binding.chipStockC04.isChecked) selectedColumns.add(ReportColumn.STOCK_C04)
        if(binding.chipStockTotal.isChecked) selectedColumns.add(ReportColumn.STOCK_TOTAL)
        if(binding.checkboxColumnUnit.isChecked) selectedColumns.add(ReportColumn.UNIT) // <-- AGREGADO
        if(binding.chipLastUpdate.isChecked) selectedColumns.add(ReportColumn.ULTIMA_ACTUALIZACION)

        if (binding.chipConsumo.isChecked) {
            if (selectedDateRange == null) {
                Toast.makeText(context, "Debes seleccionar un rango de fechas para el consumo", Toast.LENGTH_SHORT).show()
                return
            }
            selectedColumns.add(ReportColumn.CONSUMO)
        }

        if (selectedColumns.isEmpty()){
            Toast.makeText(context, "Debes seleccionar al menos una columna de datos", Toast.LENGTH_SHORT).show()
            return
        }

        val config = ReportConfig(
            productIds = selectedProductIds,
            columns = selectedColumns,
            dateRange = selectedDateRange,
            reportTitle = "Existencias ${dateFormat.format(Date())}"
        )

        showLoading(true)
        lifecycleScope.launch {
            try {
                ReportGenerator.generatePdf(requireContext(), config)
            } catch (e: Exception) {
                Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (isAdded) {
                    showLoading(false)
                }
            }
        }
    }

    private fun showLoading(isLoading: Boolean){
        binding.progressBar.isVisible = isLoading
        binding.fabGenerateReport.isEnabled = !isLoading
    }
    // 🔥 NUEVA FUNCIÓN INDEPENDIENTE PARA WHATSAPP 🔥
    private fun shareToWhatsApp() {
        val selectedProductIds = productAdapter.getSelectedIds().toList()
        if (selectedProductIds.isEmpty()) {
            Toast.makeText(context, "Debes seleccionar al menos un producto", Toast.LENGTH_SHORT).show()
            return
        }

        // Leemos qué seleccionó el usuario en los chips
        val mostrarMatriz = binding.chipStockMatriz.isChecked
        val mostrarC04 = binding.chipStockC04.isChecked
        val mostrarTotal = binding.chipStockTotal.isChecked

        // Filtramos de la lista original solo los productos seleccionados
        val productosSeleccionados = allProducts.filter { it.id in selectedProductIds }

        if (productosSeleccionados.isEmpty()) return

        val sb = StringBuilder()
        sb.append("*REPORTE DE STOCK*\n")

        // Si activó la fecha, la ponemos en el encabezado
        if (binding.chipLastUpdate.isChecked) {
            val dateTimeFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())
            sb.append("_Generado: ${dateTimeFormat.format(Date())}_\n")
        }
        sb.append("\n")

        for (producto in productosSeleccionados) {

            // 🔥 TRUCO MAESTRO: Esta función inyecta el "espacio invisible" (\u200B) en el punto o coma decimal.
            // Engaña a WhatsApp para que NUNCA lo convierta en un enlace subrayado.
            fun numeroSeguro(valor: Double): String {
                return "%.2f".format(valor).replace(".", ".\u200B").replace(",", ",\u200B")
            }

            // Lógica inteligente con los números ya protegidos
            if (mostrarTotal && !mostrarMatriz && !mostrarC04) {
                sb.append("- *${producto.name}* : ${numeroSeguro(producto.totalStock)} ${producto.unit}\n")
            } else {
                sb.append("*${producto.name}*\n")
                if (mostrarMatriz) {
                    sb.append(" ├ Matriz : ${numeroSeguro(producto.stockMatriz)} ${producto.unit}\n")
                }
                if (mostrarC04) {
                    sb.append(" ├ C-04 : ${numeroSeguro(producto.stockCongelador04)} ${producto.unit}\n")
                }
                if (mostrarTotal) {
                    sb.append(" └ *TOTAL : ${numeroSeguro(producto.totalStock)} ${producto.unit}*\n")
                }
                sb.append("\n")
            }
        }
        // Lanzamos el intent a WhatsApp
        val sendIntent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(android.content.Intent.EXTRA_TEXT, sb.toString())
            setPackage("com.whatsapp")
        }

        try {
            startActivity(sendIntent)
        } catch (e: Exception) {
            // Plan B: Si no tiene el WhatsApp normal, intentamos con WhatsApp Business
            try {
                sendIntent.setPackage("com.whatsapp.w4b")
                startActivity(sendIntent)
            } catch (e2: Exception) {
                Toast.makeText(context, "No se encontró WhatsApp instalado.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}