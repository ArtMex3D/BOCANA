package com.cesar.bocana.ui.printing

import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentEtiquetasMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class EtiquetasMenuFragment : Fragment() {

    private var _binding: FragmentEtiquetasMenuBinding? = null
    private val binding get() = _binding!!
    private lateinit var activeAdapter: ActiveLabelAdapter
    private var initialData: LabelData? = null
    private var fromPackaging: Boolean = false

    companion object {
        private const val ARG_INITIAL_DATA = "initial_label_data"
        private const val ARG_FROM_PACKAGING = "from_packaging"

        fun newInstance(initialData: LabelData? = null, fromPackaging: Boolean = false) =
            EtiquetasMenuFragment().apply {
                arguments = Bundle().apply {
                    initialData?.let { putParcelable(ARG_INITIAL_DATA, it) }
                    putBoolean(ARG_FROM_PACKAGING, fromPackaging)
                }
            }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        initialData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_INITIAL_DATA, LabelData::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_INITIAL_DATA)
        }
        fromPackaging = arguments?.getBoolean(ARG_FROM_PACKAGING, false) == true
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEtiquetasMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupActiveLabels()
        setupListeners()
        applyPackagingContext()
    }

    override fun onResume() {
        super.onResume()
        refreshActiveLabels()
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Etiquetas"
            subtitle = if (fromPackaging) "Producto y llegada preparados" else "Crear, revisar y reimprimir"
            setDisplayHomeAsUpEnabled(false)
        }
    }

    private fun applyPackagingContext() {
        if (!fromPackaging) return
        binding.cardEtiquetasSimples.alpha = 0.45f
        binding.cardEtiquetasSimples.setOnClickListener {
            Toast.makeText(requireContext(), "Simples se usa para producto ya empacado.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupActiveLabels() {
        activeAdapter = ActiveLabelAdapter(
            onView = { record -> openViewer(record) },
            onEdit = { record -> LabelEditRouter.open(this, record.id) },
            onDelete = { record -> confirmDelete(record) }
        )
        binding.recyclerViewActiveLabels.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = activeAdapter
            isNestedScrollingEnabled = false
        }
        refreshActiveLabels()
    }

    private fun refreshActiveLabels() {
        if (_binding == null) return
        val records = ActiveLabelStore.list(requireContext())
        activeAdapter.submitList(records)
        binding.textViewNoActiveLabels.isVisible = records.isEmpty()
        binding.recyclerViewActiveLabels.isVisible = records.isNotEmpty()
    }

    private fun setupListeners() {
        if (!fromPackaging) {
            binding.cardEtiquetasSimples.setOnClickListener {
                navigateToConfig(LabelType.SIMPLE, null)
            }
        }
        binding.cardCostales.setOnClickListener {
            navigateToConfig(LabelType.COSTAL, null)
        }
        binding.cardAvanzadas.setOnClickListener { showAdvancedOptions() }
    }

    private fun showAdvancedOptions() {
        val dialog = BottomSheetDialog(requireContext())
        val content = layoutInflater.inflate(R.layout.dialog_cajas_options, null)
        dialog.setContentView(content)

        fun template(total: Int): LabelTemplate? = LabelTemplates.detailedTemplates.firstOrNull {
            it.totalLabels == total
        }

        fun openFixed(total: Int) {
            val selected = template(total) ?: return
            dialog.dismiss()
            navigateToConfig(LabelType.DETAILED, selected)
        }

        fun openVariable(total: Int) {
            val selected = template(total) ?: return
            dialog.dismiss()
            navigateToMulti(selected)
        }

        content.findViewById<View>(R.id.cardCajasIdenticas2x4).setOnClickListener { openFixed(8) }
        content.findViewById<View>(R.id.cardCajasIdenticas2x3).setOnClickListener { openFixed(6) }
        content.findViewById<View>(R.id.cardCajasVariables2x4).setOnClickListener { openVariable(8) }
        content.findViewById<View>(R.id.cardCajasVariables2x3).setOnClickListener { openVariable(6) }
        content.findViewById<View>(R.id.buttonCancelCajas).setOnClickListener { dialog.dismiss() }

        dialog.setOnShowListener {
            dialog.findViewById<FrameLayout>(com.google.android.material.R.id.design_bottom_sheet)
                ?.setBackgroundColor(Color.TRANSPARENT)
        }
        dialog.show()
    }

    private fun navigateToConfig(type: LabelType, template: LabelTemplate?) {
        val seed = initialData?.copy(labelType = type)
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.nav_host_fragment_content_main,
                PrintLabelConfigFragment.newInstance(type, template, seed)
            )
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToMulti(template: LabelTemplate) {
        val seed = initialData?.copy(labelType = LabelType.DETAILED)
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.nav_host_fragment_content_main,
                PrintLabelMultiConfigFragment.newInstance(template, initialData = seed)
            )
            .addToBackStack(null)
            .commit()
    }

    private fun openViewer(record: ActiveLabelRecord) {
        parentFragmentManager.beginTransaction()
            .replace(
                R.id.nav_host_fragment_content_main,
                PdfViewerFragment.newInstance(record.pdfPath, record.id, record.title)
            )
            .addToBackStack(null)
            .commit()
    }

    private fun confirmDelete(record: ActiveLabelRecord) {
        AlertDialog.Builder(requireContext())
            .setTitle("Eliminar etiqueta")
            .setMessage("¿Eliminar '${record.title}' de Etiquetas activas?")
            .setPositiveButton("Eliminar") { _, _ ->
                ActiveLabelStore.delete(requireContext(), record.id)
                refreshActiveLabels()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
