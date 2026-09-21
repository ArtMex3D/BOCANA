package com.cesar.bocana.ui.printing

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.databinding.FragmentPrintLabelMultiConfigBinding
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintLabelMultiConfigFragment : Fragment(), AssignLabelDialogFragment.OnLabelConfiguredListener {

    private var _binding: FragmentPrintLabelMultiConfigBinding? = null
    private val binding get() = _binding!!
    private lateinit var selectedTemplate: LabelTemplate
    private lateinit var labelSlots: MutableList<IndividualLabelConfig?>
    private lateinit var adapter: LabelSlotAdapter
    private var editingActiveId: String? = null

    companion object {
        private const val ARG_TEMPLATE = "template_arg"
        private const val ARG_EDITING_ID = "editing_active_id"
        fun newInstance(template: LabelTemplate, editingActiveId: String? = null) = PrintLabelMultiConfigFragment().apply {
            arguments = Bundle().apply {
                putParcelable(ARG_TEMPLATE, template)
                editingActiveId?.let { putString(ARG_EDITING_ID, it) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val args = requireArguments()
        selectedTemplate = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            args.getParcelable(ARG_TEMPLATE, LabelTemplate::class.java)!!
        } else {
            @Suppress("DEPRECATION")
            args.getParcelable(ARG_TEMPLATE)!!
        }
        editingActiveId = args.getString(ARG_EDITING_ID)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintLabelMultiConfigBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Cajas variables"
            subtitle = "${selectedTemplate.columns}x${selectedTemplate.rows} · ${selectedTemplate.totalLabels} por hoja"
            setDisplayHomeAsUpEnabled(true)
        }
        labelSlots = editingActiveId?.let { id ->
            ActiveLabelStore.get(requireContext(), id)?.let(ActiveLabelStore::decodeConfigs)
        }?.let { saved ->
            MutableList(selectedTemplate.totalLabels) { index -> saved.getOrNull(index) }
        } ?: MutableList(selectedTemplate.totalLabels) { null }

        setupRecyclerView()
        updateHeader()
        binding.buttonGenerateMultiPdf.setOnClickListener { generatePdf() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = parentFragmentManager.popBackStack()
        })
    }

    private fun setupRecyclerView() {
        adapter = LabelSlotAdapter(
            labelSlots,
            onAssign = { position -> showAssign(position, null) },
            onEdit = { position -> showAssign(position, labelSlots[position]) },
            onDelete = { position ->
                labelSlots[position] = null
                adapter.notifyItemChanged(position)
                updateHeader()
            }
        )
        binding.recyclerViewLabelSlots.adapter = adapter
    }

    private fun showAssign(position: Int, existing: IndividualLabelConfig?) {
        AssignLabelDialogFragment.newInstance(position, existing).apply {
            setTargetFragment(this@PrintLabelMultiConfigFragment, 0)
        }.show(parentFragmentManager, "AssignLabelBottomSheet")
    }

    override fun onLabelConfigured(position: Int, config: IndividualLabelConfig, copies: Int) {
        var filled = 0
        for (i in 0 until copies.coerceAtLeast(1)) {
            val target = position + i
            if (target >= labelSlots.size) break
            labelSlots[target] = config
            filled++
        }
        if (filled > 0) adapter.notifyItemRangeChanged(position, filled)
        updateHeader()
    }

    private fun updateHeader() {
        if (_binding == null) return
        val count = labelSlots.count { it != null }
        binding.textViewTemplateTitle.text = "Variables · ${selectedTemplate.columns}x${selectedTemplate.rows}"
        binding.textViewConfiguredCount.text = "$count de ${selectedTemplate.totalLabels} configuradas"
        binding.buttonGenerateMultiPdf.isEnabled = count > 0
    }

    private fun generatePdf() {
        binding.progressBarMultiConfig.isVisible = true
        binding.buttonGenerateMultiPdf.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) {
                    PdfGenerator.createMultiLabelPdf(requireContext(), labelSlots, selectedTemplate)
                }
                val record = ActiveLabelStore.saveMulti(requireContext(), pdf, selectedTemplate, labelSlots, editingActiveId)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, PdfViewerFragment.newInstance(record.pdfPath, record.id, record.title))
                    .addToBackStack(null)
                    .commit()
            } catch (e: Exception) {
                Log.e("MultiLabels", "Error generando PDF", e)
                Toast.makeText(context, "No se pudo generar el PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.progressBarMultiConfig.isVisible = false
                    updateHeader()
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
