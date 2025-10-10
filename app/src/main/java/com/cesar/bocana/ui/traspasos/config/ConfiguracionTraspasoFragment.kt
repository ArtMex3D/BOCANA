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
            // Tonos Pastel (ideales para zebra)
            Color.parseColor("#f5958e"), // Azul claro
            Color.parseColor("#E0F2F1"), // Verde menta
            Color.parseColor("#FFF8E1"), // Amarillo pálido
            Color.parseColor("#FBE9E7"), // Rosa suave
            Color.parseColor("#F3E5F5"), // Lavanda
            Color.parseColor("#ECEFF1"), // Gris muy claro
            // Tonos Medios y Oscuros (ideales para cabeceras)
            Color.parseColor("#f5ee8e"), // Azul oscuro
            Color.parseColor("#b7f58e"), // Verde oscuro
            Color.parseColor("#8ef5c1"), // Naranja oscuro
            Color.parseColor("#8ee9f5"), // Rojo oscuro
            Color.parseColor("#4A148C"), // Púrpura oscuro
            Color.parseColor("#db8ef5"), // Gris oscuro
            Color.parseColor("#05025c"),
            Color.parseColor("#f1f516"),
            Color.parseColor("#16f51d"),
            Color.parseColor("#f57716"),
            // Colores Básicos
            Color.WHITE,
            Color.LTGRAY,
            Color.DKGRAY,
            Color.BLACK
        )
        // --- FIN DE LA SOLUCIÓN ---

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

