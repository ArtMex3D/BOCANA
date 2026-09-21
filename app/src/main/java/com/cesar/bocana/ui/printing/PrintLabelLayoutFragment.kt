package com.cesar.bocana.ui.printing

import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentPrintLabelLayoutBinding
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintLabelLayoutFragment : Fragment() {

    private var _binding: FragmentPrintLabelLayoutBinding? = null
    private val binding get() = _binding!!
    private var labelData: LabelData? = null
    private lateinit var availableTemplates: List<LabelTemplate>
    private var selectedTemplate: LabelTemplate? = null

    companion object {
        private const val ARG_LABEL_DATA = "label_data_arg"
        fun newInstance(data: LabelData) = PrintLabelLayoutFragment().apply {
            arguments = Bundle().apply { putParcelable(ARG_LABEL_DATA, data) }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labelData = arguments?.let { args ->
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                args.getParcelable(ARG_LABEL_DATA, LabelData::class.java)
            } else {
                @Suppress("DEPRECATION")
                args.getParcelable(ARG_LABEL_DATA)
            }
        }
        if (labelData == null) return
        availableTemplates = when (labelData!!.labelType) {
            LabelType.SIMPLE -> LabelTemplates.simpleTemplates
            LabelType.COSTAL -> LabelTemplates.costalTemplates
            LabelType.DETAILED -> LabelTemplates.detailedTemplates
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintLabelLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (labelData == null) {
            Toast.makeText(context, "Faltan datos para la etiqueta.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Cantidad por hoja"
            subtitle = "Hoja Carta"
            setDisplayHomeAsUpEnabled(true)
        }
        populateTemplateOptions()
        binding.buttonGeneratePdf.text = "Generar y revisar"
        binding.buttonGeneratePdf.setOnClickListener { generate() }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = parentFragmentManager.popBackStack()
        })
    }

    private fun populateTemplateOptions() {
        binding.radioGroupLabelTemplates.removeAllViews()
        availableTemplates.forEachIndexed { index, template ->
            val radio = RadioButton(requireContext()).apply {
                text = template.description
                id = View.generateViewId()
                tag = template
                setPadding(8, 10, 8, 10)
            }
            binding.radioGroupLabelTemplates.addView(radio)
            if (index == 0) {
                radio.isChecked = true
                selectedTemplate = template
            }
        }
        binding.radioGroupLabelTemplates.setOnCheckedChangeListener { group, checkedId ->
            selectedTemplate = group.findViewById<RadioButton>(checkedId)?.tag as? LabelTemplate
        }
    }

    private fun generate() {
        val data = labelData ?: return
        val template = selectedTemplate ?: return
        binding.progressBarLayout.isVisible = true
        binding.buttonGeneratePdf.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pdf = withContext(Dispatchers.IO) { PdfGenerator.createSingleLabelPdf(requireContext(), data, template) }
                val flow = when (data.labelType) {
                    LabelType.SIMPLE -> LabelFlowType.SIMPLE
                    LabelType.COSTAL -> LabelFlowType.COSTAL
                    LabelType.DETAILED -> LabelFlowType.FIXED_DETAILED
                }
                val record = ActiveLabelStore.saveSingle(requireContext(), pdf, flow, template, data)
                parentFragmentManager.beginTransaction()
                    .replace(R.id.nav_host_fragment_content_main, PdfViewerFragment.newInstance(record.pdfPath, record.id, record.title))
                    .addToBackStack(null)
                    .commit()
            } catch (e: Exception) {
                Log.e("LabelLayout", "Error generando PDF", e)
                Toast.makeText(context, "No se pudo generar el PDF: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                if (_binding != null) {
                    binding.progressBarLayout.isVisible = false
                    binding.buttonGeneratePdf.isEnabled = true
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
