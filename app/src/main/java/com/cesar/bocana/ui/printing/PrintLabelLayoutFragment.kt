package com.cesar.bocana.ui.printing

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentPrintLabelLayoutBinding
import com.cesar.bocana.ui.printing.pdf.PdfGenerator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class PrintLabelLayoutFragment : Fragment() {

    private var _binding: FragmentPrintLabelLayoutBinding? = null
    private val binding get() = _binding!!

    private var labelData: LabelData? = null
    private lateinit var availableTemplates: List<LabelTemplate>
    private var selectedTemplate: LabelTemplate? = null

    companion object {
        private const val ARG_LABEL_DATA = "label_data_arg"

        fun newInstance(data: LabelData): PrintLabelLayoutFragment {
            return PrintLabelLayoutFragment().apply {
                arguments = Bundle().apply {
                    putParcelable(ARG_LABEL_DATA, data)
                }
            }
        }
    }

    private fun generateAndSharePdf() {
        val currentLabelData = labelData ?: return
        val currentTemplate = selectedTemplate ?: run {
            Toast.makeText(context, "Por favor, selecciona una plantilla.", Toast.LENGTH_SHORT).show()
            return
        }
        val currentContext = context ?: return

        binding.progressBarLayout.isVisible = true
        binding.buttonGeneratePdf.isEnabled = false
        Toast.makeText(currentContext, "Generando PDF...", Toast.LENGTH_SHORT).show()

        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val pdfFile = withContext(Dispatchers.IO) {
                    PdfGenerator.createSingleLabelPdf(currentContext, currentLabelData, currentTemplate)
                }
                sharePdf(currentContext, pdfFile)

            } catch (e: Exception) {
                Log.e("PDF_CRASH", "Error capturado al generar PDF", e)
                showErrorDialog(currentContext, e)
            } finally {
                if (isAdded && _binding != null) {
                    binding.progressBarLayout.isVisible = false
                    binding.buttonGeneratePdf.isEnabled = true
                }
            }
        }
    }


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            labelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                it.getParcelable(ARG_LABEL_DATA, LabelData::class.java)
            } else {
                @Suppress("DEPRECATION")
                it.getParcelable(ARG_LABEL_DATA)
            }
        }
        if (labelData == null) {
            Toast.makeText(context, "Error: Faltan datos para la etiqueta.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }
        availableTemplates = if (labelData!!.labelType == LabelType.SIMPLE) {
            LabelTemplates.simpleTemplates
        } else {
            LabelTemplates.detailedTemplates
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPrintLabelLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        populateTemplateOptions()
        setupUI()

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { parentFragmentManager.popBackStack() }
        })
    }

    private fun setupUI() {
        binding.buttonGeneratePdf.setOnClickListener {
            generateAndSharePdf()
        }
        // CORRECCIÓN: Se eliminan las líneas que mostraban la previsualización innecesaria.
        // Las vistas de previsualización ahora permanecerán ocultas ('gone'), como
        // están definidas en el archivo XML.
    }

    private fun sharePdf(context: Context, file: File) {
        if (!isAdded || !file.exists()) return
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(shareIntent, "Compartir etiquetas PDF..."))
        } catch (e: Exception) {
            Log.e("SharePdf", "Error al compartir PDF", e)
            Toast.makeText(context, "No se pudo compartir el archivo.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showErrorDialog(context: Context, e: Exception) {
        if (!isAdded) return
        AlertDialog.Builder(context)
            .setTitle("¡Oops! Ocurrió un error")
            .setMessage(e.stackTraceToString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Seleccionar Plantilla"
            subtitle = "Elige el formato de la hoja"
            setDisplayHomeAsUpEnabled(true)
        }
    }

    private fun populateTemplateOptions() {
        binding.radioGroupLabelTemplates.removeAllViews()
        availableTemplates.forEachIndexed { index, template ->
            val radioButton = RadioButton(context).apply {
                text = template.description
                id = View.generateViewId()
                tag = template
            }
            binding.radioGroupLabelTemplates.addView(radioButton)
            if (index == 0) {
                radioButton.isChecked = true
                selectedTemplate = template
            }
        }
        binding.radioGroupLabelTemplates.setOnCheckedChangeListener { group, checkedId ->
            val checkedRadioButton = group.findViewById<RadioButton>(checkedId)
            selectedTemplate = checkedRadioButton.tag as? LabelTemplate
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}