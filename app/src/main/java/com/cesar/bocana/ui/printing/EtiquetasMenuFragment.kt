package com.cesar.bocana.ui.printing

import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import com.cesar.bocana.R
import com.cesar.bocana.databinding.FragmentEtiquetasMenuBinding
import com.google.android.material.bottomsheet.BottomSheetDialog

class EtiquetasMenuFragment : Fragment() {

    private var _binding: FragmentEtiquetasMenuBinding? = null
    private val binding get() = _binding!!
    private lateinit var activeAdapter: ActiveLabelAdapter

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentEtiquetasMenuBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        setupActiveLabels()
        setupListeners()
    }

    override fun onResume() {
        super.onResume()
        refreshActiveLabels()
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Etiquetas"
            subtitle = "Crear, revisar y reimprimir"
            setDisplayHomeAsUpEnabled(false)
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
        binding.cardEtiquetasSimples.setOnClickListener {
            navigateToConfig(LabelType.SIMPLE, null)
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
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, PrintLabelConfigFragment.newInstance(type, template))
            .addToBackStack(null)
            .commit()
    }

    private fun navigateToMulti(template: LabelTemplate) {
        parentFragmentManager.beginTransaction()
            .replace(R.id.nav_host_fragment_content_main, PrintLabelMultiConfigFragment.newInstance(template))
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
