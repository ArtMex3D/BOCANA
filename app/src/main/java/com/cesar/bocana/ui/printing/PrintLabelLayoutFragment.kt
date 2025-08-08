package com.cesar.bocana.ui.printing

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentPrintLabelLayoutBinding
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PrintLabelLayoutFragment : Fragment() {

    private var _binding: FragmentPrintLabelLayoutBinding? = null
    private val binding get() = _binding!!

    private var labelData: LabelData? = null
    private lateinit var availableTemplates: List<LabelTemplate>
    private var selectedTemplate: LabelTemplate? = null
    private val simpleDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    private val PAGE_WIDTH_A4_POINTS = 595
    private val PAGE_HEIGHT_A4_POINTS = 842

    companion object {
        private const val ARG_LABEL_DATA = "label_data_arg"
        fun newInstance(data: LabelData): PrintLabelLayoutFragment {
            return PrintLabelLayoutFragment().apply {
                arguments = Bundle().apply { putParcelable(ARG_LABEL_DATA, data) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        labelData = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arguments?.getParcelable(ARG_LABEL_DATA, LabelData::class.java)
        } else {
            @Suppress("DEPRECATION")
            arguments?.getParcelable(ARG_LABEL_DATA)
        }

        if (labelData == null) {
            Toast.makeText(context, "Error: No se recibieron datos para la etiqueta.", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
            return
        }

        availableTemplates = when(labelData?.labelType) {
            LabelType.SIMPLE -> LabelTemplates.simpleTemplates
            LabelType.DETAILED -> LabelTemplates.detailedTemplates
            else -> emptyList()
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

        binding.buttonGeneratePdf.setOnClickListener {
            generateAndSharePdfWithLayoutInflation()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { parentFragmentManager.popBackStack() }
        })
    }

    private fun generateAndSharePdfWithLayoutInflation() {
        val currentLabelData = labelData ?: return
        val currentTemplate = selectedTemplate ?: run {
            Toast.makeText(context, "Por favor, selecciona una plantilla.", Toast.LENGTH_SHORT).show()
            return
        }

        binding.progressBarLayout.visibility = View.VISIBLE
        binding.buttonGeneratePdf.isEnabled = false
        Toast.makeText(context, "Generando PDF...", Toast.LENGTH_SHORT).show()

        CoroutineScope(Dispatchers.Main).launch {
            try {
                val pdfFile = createPdf(currentLabelData, currentTemplate)
                sharePdf(pdfFile)
            } catch (e: Exception) {
                Log.e("PDF_CRASH", "Error capturado al generar PDF", e)
                showErrorDialog(e)
            } finally {
                if(isAdded) {
                    binding.progressBarLayout.visibility = View.GONE
                    binding.buttonGeneratePdf.isEnabled = true
                }
            }
        }
    }

    private suspend fun createPdf(data: LabelData, template: LabelTemplate): File {
        val pdfDocument = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_A4_POINTS, PAGE_HEIGHT_A4_POINTS, 1).create()
        val page = pdfDocument.startPage(pageInfo)
        val canvas = page.canvas

        val labelView = createAndPopulateLabelView(requireContext(), data)

        val widthSpec = View.MeasureSpec.makeMeasureSpec(template.labelWidthPt.toInt(), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(template.labelHeightPt.toInt(), View.MeasureSpec.EXACTLY)
        labelView.measure(widthSpec, heightSpec)
        labelView.layout(0, 0, labelView.measuredWidth, labelView.measuredHeight)

        drawLabelsOnCanvas(canvas, labelView, template)

        pdfDocument.finishPage(page)

        val file = File(requireContext().cacheDir, "bocana_etiquetas.pdf")
        withContext(Dispatchers.IO) {
            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
        }
        pdfDocument.close()
        return file
    }

    private fun drawLabelsOnCanvas(
        canvas: Canvas,
        labelView: View,
        template: LabelTemplate
    ) {
        for (i in 0 until template.totalLabels) {
            val row = i / template.columns
            val col = i % template.columns

            val x = template.pageMarginPt + col * (template.labelWidthPt + template.horizontalSpacingPt)
            val y = template.pageMarginPt + row * (template.labelHeightPt + template.verticalSpacingPt)

            canvas.save()
            canvas.translate(x, y)
            labelView.draw(canvas)
            canvas.restore()
        }
    }

    private fun createAndPopulateLabelView(context: Context, data: LabelData): View {
        val inflater = LayoutInflater.from(context)
        val view: View

        when (data.labelType) {
            LabelType.DETAILED -> {
                view = inflater.inflate(R.layout.layout_label_detailed_v2, null)
                view.findViewById<TextView>(R.id.label_product_name).text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"
                view.findViewById<TextView>(R.id.label_supplier).text = data.supplierName ?: "PROVEEDOR"
                view.findViewById<TextView>(R.id.label_date).text = simpleDateFormat.format(data.date)
                val detailTv = view.findViewById<TextView>(R.id.label_detail)
                detailTv.text = data.detail ?: ""
                detailTv.visibility = if (data.detail.isNullOrBlank()) View.GONE else View.VISIBLE

                val predefinedWeightLayout = view.findViewById<TextView>(R.id.label_weight_predefined)
                val manualWeightLayout = view.findViewById<ViewGroup>(R.id.layout_weight_manual)

                if (data.weight == "Manual") {
                    predefinedWeightLayout.visibility = View.GONE
                    manualWeightLayout.visibility = View.VISIBLE
                    manualWeightLayout.findViewById<TextView>(R.id.label_weight_manual_unit).text = data.unit ?: ""
                } else {
                    predefinedWeightLayout.visibility = View.VISIBLE
                    manualWeightLayout.visibility = View.GONE
                    predefinedWeightLayout.text = "PESO: ${data.weight ?: ""} ${data.unit ?: ""}"
                }
            }
            LabelType.SIMPLE -> {
                view = inflater.inflate(R.layout.layout_label_simple, null)
                view.findViewById<TextView>(R.id.label_supplier_simple).text = data.supplierName ?: "PROVEEDOR"
                view.findViewById<TextView>(R.id.label_date_simple).text = simpleDateFormat.format(data.date)
            }
        }
        return view
    }

    private fun sharePdf(file: File) {
        if (!isAdded || !file.exists()) return
        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.provider", file)
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Compartir etiquetas PDF..."))
    }

    private fun showErrorDialog(e: Exception) {
        if (!isAdded) return
        AlertDialog.Builder(requireContext())
            .setTitle("¡Oops! Ocurrió un error")
            .setMessage(e.stackTraceToString())
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Generar PDF de Etiquetas"
            subtitle = "Paso final"
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