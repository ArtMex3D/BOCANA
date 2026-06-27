package com.cesar.bocana.ui.traspasos.config

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.GridLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.databinding.FragmentConfiguracionTraspasoBinding
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class ConfiguracionTraspasoFragment : Fragment() {

    private var _binding: FragmentConfiguracionTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfiguracionTraspasoViewModel by viewModels()
    private lateinit var adapter: ConfiguracionTraspasoAdapter
    private lateinit var prefs: SharedPreferences

    companion object {
        const val PREFS_NAME = "PdfColorConfig"
        const val KEY_HEADER_BG = "headerBgColor"
        const val KEY_HEADER_FONT = "headerFontColor"
        const val KEY_ZEBRA = "zebraColor"
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfiguracionTraspasoBinding.inflate(inflater, container, false)
        prefs = requireActivity().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Configuración de Traspasos"

        setupRecyclerView()
        observeViewModel()
        setupColorPickerButtons()
    }

    private fun setupColorPickerButtons() {
        binding.buttonHeaderBgColor.setOnClickListener {
            showColorPickerDialog("Color Fondo Título", KEY_HEADER_BG)
        }
        binding.buttonHeaderFontColor.setOnClickListener {
            showColorPickerDialog("Color Texto Título", KEY_HEADER_FONT)
        }
        binding.buttonZebraColor.setOnClickListener {
            showColorPickerDialog("Color de zebra", KEY_ZEBRA)
        }
    }

    private fun showColorPickerDialog(title: String, preferenceKey: String) {
        // --- INICIO DE LA SOLUCIÓN: Paleta de colores mejorada ---
        val colors = listOf(
            // --- Tonos "Ice" / Pasteles ultra tenues (Ideales para la Zebra) ---
            Color.parseColor("#FFEBEE"), // Rojo/Rosa muy tenue
            Color.parseColor("#E3F2FD"), // Azul muy tenue
            Color.parseColor("#E8F5E9"), // Verde muy tenue
            Color.parseColor("#FFF3E0"), // Naranja muy tenue
            Color.parseColor("#F3E5F5"), // Morado/Lavanda muy tenue
            Color.parseColor("#F5F5F5"), // Gris perla (casi blanco)

            // --- Tonos Pasteles Intermedios ---
            Color.parseColor("#FFCDD2"), // Rojo pastel
            Color.parseColor("#BBDEFB"), // Azul pastel
            Color.parseColor("#C8E6C9"), // Verde pastel
            Color.parseColor("#FFE0B2"), // Naranja pastel
            Color.parseColor("#E1BEE7"), // Morado pastel
            Color.parseColor("#CFD8DC"), // Gris azulado pastel

            // --- Tonos Oscuros Elegantes (Ideales para Cabeceras) ---
            Color.parseColor("#D32F2F"), // Rojo oscuro
            Color.parseColor("#1565C0"), // Azul marino
            Color.parseColor("#2E7D32"), // Verde bosque
            Color.parseColor("#E65100"), // Naranja quemado
            Color.parseColor("#4527A0"), // Púrpura oscuro
            Color.parseColor("#37474F"), // Gris grafito

            // --- Colores Básicos de Seguridad ---
            Color.WHITE,
            Color.LTGRAY,
            Color.DKGRAY,
            Color.BLACK
        )        // --- FIN DE LA SOLUCIÓN ---

        val gridLayout = GridLayout(requireContext()).apply {
            columnCount = 4
            alignmentMode = GridLayout.ALIGN_BOUNDS
            setPadding(40, 40, 40, 40)
        }

        val dialog = AlertDialog.Builder(requireContext())
            .setTitle(title)
            .setView(gridLayout)
            .setNegativeButton("Cancelar", null)
            .create()

        // --- INICIO DE LA SOLUCIÓN: Lógica de creación de vistas corregida ---
        val sizeInDp = 48
        val sizeInPx = (sizeInDp * resources.displayMetrics.density).toInt()
        val marginInDp = 8
        val marginInPx = (marginInDp * resources.displayMetrics.density).toInt()

        colors.forEach { color ->
            val colorView = View(context).apply {
                val params = GridLayout.LayoutParams()
                params.width = sizeInPx
                params.height = sizeInPx
                params.setMargins(marginInPx, marginInPx, marginInPx, marginInPx)
                layoutParams = params

                val shape = GradientDrawable()
                shape.shape = GradientDrawable.OVAL
                shape.setColor(color)
                shape.setStroke(2, Color.GRAY)
                background = shape

                setOnClickListener {
                    prefs.edit().putInt(preferenceKey, color).apply()
                    Toast.makeText(context, "Color guardado", Toast.LENGTH_SHORT).show()
                    dialog.dismiss()
                }
            }
            gridLayout.addView(colorView)
        }
        // --- FIN DE LA SOLUCIÓN ---

        dialog.show()
    }

    private fun setupRecyclerView() {
        adapter = ConfiguracionTraspasoAdapter(viewModel)
        binding.recyclerViewConfigTraspasos.adapter = adapter
        binding.recyclerViewConfigTraspasos.layoutManager = LinearLayoutManager(context)

        val itemTouchHelperCallback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN, 0
        ) {
            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                val fromPosition = viewHolder.adapterPosition
                val toPosition = target.adapterPosition
                adapter.moveItem(fromPosition, toPosition)
                return true
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewModel.updateProductOrder(adapter.getFinalOrder())
            }
        }

        val itemTouchHelper = ItemTouchHelper(itemTouchHelperCallback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewConfigTraspasos)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.products.collectLatest { products ->
                adapter.submitList(products)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isLoading.collectLatest { isLoading ->
                binding.progressBarConfig.isVisible = isLoading
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.error.collectLatest { error ->
                error?.let {
                    Toast.makeText(context, it, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}

