package com.cesar.bocana.ui.traspasos.config

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.KeyEvent
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.TransferPdfConfig
import com.cesar.bocana.databinding.FragmentConfiguracionTraspasoBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class ConfiguracionTraspasoFragment : Fragment() {

    private var _binding: FragmentConfiguracionTraspasoBinding? = null
    private val binding get() = _binding!!

    private val viewModel: ConfiguracionTraspasoViewModel by viewModels()
    private lateinit var adapter: ConfiguracionTraspasoAdapter
    private lateinit var itemTouchHelper: ItemTouchHelper
    private var currentPdfConfig = TransferPdfConfig.defaults()
    private var pdfSectionExpanded = false

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentConfiguracionTraspasoBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.title = "Configuración de Traspasos"

        setupRecyclerView()
        setupPdfSection()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ConfiguracionTraspasoAdapter(viewModel) { holder ->
            itemTouchHelper.startDrag(holder)
        }
        binding.recyclerViewConfigTraspasos.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerViewConfigTraspasos.adapter = adapter
        binding.recyclerViewConfigTraspasos.itemAnimator?.changeDuration = 120L

        val callback = object : ItemTouchHelper.SimpleCallback(
            ItemTouchHelper.UP or ItemTouchHelper.DOWN,
            0
        ) {
            override fun isLongPressDragEnabled(): Boolean = false

            override fun onMove(
                recyclerView: RecyclerView,
                viewHolder: RecyclerView.ViewHolder,
                target: RecyclerView.ViewHolder
            ): Boolean {
                return adapter.moveItem(
                    viewHolder.bindingAdapterPosition,
                    target.bindingAdapterPosition
                )
            }

            override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) = Unit

            override fun onSelectedChanged(viewHolder: RecyclerView.ViewHolder?, actionState: Int) {
                super.onSelectedChanged(viewHolder, actionState)
                if (actionState == ItemTouchHelper.ACTION_STATE_DRAG) {
                    viewHolder?.itemView?.apply {
                        alpha = 0.94f
                        scaleX = 1.015f
                        scaleY = 1.015f
                        elevation = dp(8).toFloat()
                    }
                }
            }

            override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
                super.clearView(recyclerView, viewHolder)
                viewHolder.itemView.apply {
                    alpha = 1f
                    scaleX = 1f
                    scaleY = 1f
                    elevation = dp(1).toFloat()
                }
                adapter.consumeFinalOrder()?.let(viewModel::updateProductOrder)
            }
        }
        itemTouchHelper = ItemTouchHelper(callback)
        itemTouchHelper.attachToRecyclerView(binding.recyclerViewConfigTraspasos)
    }

    private fun setupPdfSection() {
        binding.pdfConfigHeader.setOnClickListener {
            pdfSectionExpanded = !pdfSectionExpanded
            binding.pdfConfigContent.isVisible = pdfSectionExpanded
            binding.imagePdfArrow.animate()
                .rotation(if (pdfSectionExpanded) 180f else 0f)
                .setDuration(160L)
                .start()
        }

        binding.buttonHeaderBgColor.setOnClickListener {
            showColorPickerDialog(
                title = "Fondo del título",
                initialHex = currentPdfConfig.headerBackgroundHex
            ) { hex -> persistPdfConfig(currentPdfConfig.copy(headerBackgroundHex = hex)) }
        }
        binding.buttonHeaderFontColor.setOnClickListener {
            showColorPickerDialog(
                title = "Texto del título",
                initialHex = currentPdfConfig.headerTextHex
            ) { hex -> persistPdfConfig(currentPdfConfig.copy(headerTextHex = hex)) }
        }
        binding.buttonZebraColor.setOnClickListener {
            showColorPickerDialog(
                title = "Color de filas zebra",
                initialHex = currentPdfConfig.zebraHex
            ) { hex -> persistPdfConfig(currentPdfConfig.copy(zebraHex = hex)) }
        }

        binding.editTextPdfTitle.setOnFocusChangeListener { _, hasFocus ->
            if (!hasFocus) saveTitleIfChanged()
        }
        binding.editTextPdfTitle.setOnEditorActionListener { _, actionId, event ->
            val done = actionId == EditorInfo.IME_ACTION_DONE ||
                (event?.keyCode == KeyEvent.KEYCODE_ENTER && event.action == KeyEvent.ACTION_DOWN)
            if (done) {
                binding.editTextPdfTitle.clearFocus()
                saveTitleIfChanged()
            }
            done
        }

        binding.buttonResetPdfStyle.setOnClickListener {
            MaterialAlertDialogBuilder(requireContext())
                .setTitle("Restaurar diseño")
                .setMessage("Se restaurarán el título y los tres colores en todos los equipos.")
                .setNegativeButton("Cancelar", null)
                .setPositiveButton("Restaurar") { _, _ ->
                    persistPdfConfig(TransferPdfConfig.defaults())
                }
                .show()
        }
    }

    private fun saveTitleIfChanged() {
        val title = binding.editTextPdfTitle.text?.toString().orEmpty().trim()
        if (title.isNotBlank() && title != currentPdfConfig.titleText) {
            persistPdfConfig(currentPdfConfig.copy(titleText = title))
        } else if (title.isBlank()) {
            binding.editTextPdfTitle.setText(currentPdfConfig.titleText)
        }
    }

    private fun persistPdfConfig(config: TransferPdfConfig) {
        currentPdfConfig = config.normalized()
        renderPdfConfig(currentPdfConfig)
        viewModel.savePdfConfig(currentPdfConfig)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.products.collect { adapter.setProducts(it) }
                }
                launch {
                    viewModel.pdfConfig.collect { config ->
                        currentPdfConfig = config
                        renderPdfConfig(config)
                    }
                }
                launch {
                    viewModel.isLoading.collect { loading ->
                        binding.progressBarConfig.isVisible = loading
                    }
                }
                launch {
                    viewModel.isSaving.collect { saving ->
                        binding.textViewSyncState.text = if (saving) "Guardando…" else "Sincronizado"
                        binding.textViewSyncState.alpha = if (saving) 0.72f else 1f
                    }
                }
                launch {
                    viewModel.error.collect { error ->
                        if (error != null) {
                            Toast.makeText(requireContext(), error, Toast.LENGTH_LONG).show()
                            viewModel.consumeError()
                        }
                    }
                }
            }
        }
    }

    private fun renderPdfConfig(config: TransferPdfConfig) {
        if (!binding.editTextPdfTitle.hasFocus() &&
            binding.editTextPdfTitle.text?.toString() != config.titleText
        ) {
            binding.editTextPdfTitle.setText(config.titleText)
        }

        binding.buttonHeaderBgColor.text = "Fondo título · ${config.headerBackgroundHex}"
        binding.buttonHeaderFontColor.text = "Texto título · ${config.headerTextHex}"
        binding.buttonZebraColor.text = "Filas zebra · ${config.zebraHex}"

        val headerBackground = safeColor(config.headerBackgroundHex)
        val headerText = safeColor(config.headerTextHex)
        val zebra = safeColor(config.zebraHex)

        setSwatch(binding.viewHeaderBgSwatch, headerBackground)
        setSwatch(binding.viewHeaderTextSwatch, headerText)
        setSwatch(binding.viewZebraSwatch, zebra)

        binding.previewPdfTitle.text = config.titleText
        binding.previewPdfTitle.setBackgroundColor(headerBackground)
        binding.previewPdfTitle.setTextColor(headerText)
        binding.previewPdfZebra.setBackgroundColor(zebra)
    }

    private fun showColorPickerDialog(
        title: String,
        initialHex: String,
        onColorSelected: (String) -> Unit
    ) {
        val content = layoutInflater.inflate(R.layout.dialog_hex_color_picker, null)
        val preview = content.findViewById<View>(R.id.color_preview)
        val hexInput = content.findViewById<EditText>(R.id.edit_hex_color)
        val seekRed = content.findViewById<SeekBar>(R.id.seek_red)
        val seekGreen = content.findViewById<SeekBar>(R.id.seek_green)
        val seekBlue = content.findViewById<SeekBar>(R.id.seek_blue)
        val valueRed = content.findViewById<TextView>(R.id.value_red)
        val valueGreen = content.findViewById<TextView>(R.id.value_green)
        val valueBlue = content.findViewById<TextView>(R.id.value_blue)
        val presets = content.findViewById<LinearLayout>(R.id.preset_colors)

        var selectedColor = safeColor(initialHex)
        var internalChange = false

        fun colorHex(color: Int): String = String.format("#%06X", 0xFFFFFF and color)

        fun renderColor(color: Int, updateInput: Boolean = true) {
            selectedColor = color
            internalChange = true
            seekRed.progress = Color.red(color)
            seekGreen.progress = Color.green(color)
            seekBlue.progress = Color.blue(color)
            valueRed.text = Color.red(color).toString()
            valueGreen.text = Color.green(color).toString()
            valueBlue.text = Color.blue(color).toString()
            if (updateInput) hexInput.setText(colorHex(color))
            setSwatch(preview, color, radiusDp = 12)
            internalChange = false
        }

        val seekListener = object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!internalChange && fromUser) {
                    renderColor(Color.rgb(seekRed.progress, seekGreen.progress, seekBlue.progress))
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        }
        seekRed.setOnSeekBarChangeListener(seekListener)
        seekGreen.setOnSeekBarChangeListener(seekListener)
        seekBlue.setOnSeekBarChangeListener(seekListener)

        hexInput.doAfterTextChanged { editable ->
            if (internalChange) return@doAfterTextChanged
            val raw = editable?.toString().orEmpty().trim()
            if (raw.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                renderColor(Color.parseColor(raw), updateInput = false)
            }
        }

        val presetHexes = listOf(
            "#37474F", "#263238", "#0D47A1", "#1565C0", "#00695C", "#2E7D32",
            "#6A1B9A", "#E8005E", "#C62828", "#E65100", "#FFFFFF", "#F5F5F5",
            "#FFEBEE", "#E3F2FD", "#E8F5E9", "#FFF3E0", "#F3E5F5", "#E8F1FF"
        )
        presetHexes.forEach { hex ->
            val swatch = View(requireContext()).apply {
                val size = dp(38)
                layoutParams = LinearLayout.LayoutParams(size, size).also {
                    it.marginEnd = dp(8)
                }
                contentDescription = "Seleccionar $hex"
                setSwatch(this, Color.parseColor(hex), radiusDp = 19)
                setOnClickListener { renderColor(Color.parseColor(hex)) }
            }
            presets.addView(swatch)
        }

        renderColor(selectedColor)

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(content)
            .setNegativeButton("Cancelar", null)
            .setPositiveButton("Guardar", null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val raw = hexInput.text?.toString().orEmpty().trim()
                if (!raw.matches(Regex("^#[0-9a-fA-F]{6}$"))) {
                    hexInput.error = "Usa el formato #RRGGBB"
                } else {
                    onColorSelected(colorHex(selectedColor))
                    dialog.dismiss()
                }
            }
        }
        dialog.show()
    }

    private fun safeColor(hex: String): Int = runCatching {
        Color.parseColor(TransferPdfConfig.normalizeHex(hex, "#FFFFFF"))
    }.getOrDefault(Color.WHITE)

    private fun setSwatch(view: View, color: Int, radiusDp: Int = 8) {
        view.background = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(radiusDp).toFloat()
            setColor(color)
            setStroke(dp(1), Color.parseColor("#98A2B3"))
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
