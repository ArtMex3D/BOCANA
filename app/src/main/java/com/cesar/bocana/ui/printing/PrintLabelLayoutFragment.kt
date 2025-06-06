package com.cesar.bocana.ui.printing

import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.os.Bundle
import android.os.Environment
import android.print.PrintAttributes
import android.print.pdf.PrintedPdfDocument
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.databinding.FragmentPrintLabelLayoutBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

class PrintLabelLayoutFragment : Fragment() {

    private var _binding: FragmentPrintLabelLayoutBinding? = null
    private val binding get() = _binding!!

    private var labelData: LabelData? = null
    private val simpleDateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    private val PAGE_WIDTH_A4_POINTS = 595
    private val PAGE_HEIGHT_A4_POINTS = 842

    private val PAGE_MARGIN_TOP_POINTS = 36f
    private val PAGE_MARGIN_BOTTOM_POINTS = 36f
    private val PAGE_MARGIN_LEFT_POINTS = 28f
    private val PAGE_MARGIN_RIGHT_POINTS = 28f

    private val LABEL_SPACING_POINTS = 4f

    data class TemplateOption(val description: String, val totalLabelsPerPage: Int, val columns: Int)
    private lateinit var currentTemplateOptions: List<TemplateOption>
    private var selectedTemplate: TemplateOption? = null


    companion object {
        private const val ARG_LABEL_DATA = "label_data_arg"
        fun newInstance(data: LabelData): PrintLabelLayoutFragment {
            val args = Bundle()
            args.putParcelable(ARG_LABEL_DATA, data)
            val fragment = PrintLabelLayoutFragment()
            fragment.arguments = args
            return fragment
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arguments?.let {
            labelData = it.getParcelable(ARG_LABEL_DATA) as? LabelData
        }
        if (labelData == null) {
            Toast.makeText(context, "Error: No se recibieron datos de etiqueta", Toast.LENGTH_LONG).show()
            parentFragmentManager.popBackStack()
        }

        currentTemplateOptions = if (labelData?.labelType == LabelType.SIMPLE) {
            listOf(
                TemplateOption("2 por hoja ", 2, 1),
                TemplateOption("10 por hoja ", 10, 2),
                TemplateOption("16 por hoja ", 16, 2),
                TemplateOption("20 por hoja ", 20, 2)
            )
        } else {
            listOf(
                TemplateOption("2 por hoja ", 2, 1),
                TemplateOption("6 por hoja ", 6, 2),
                TemplateOption("8 por hoja ", 8, 2),
                TemplateOption("10 por hoja ", 10, 2),
                TemplateOption("14 por hoja ", 14, 2),
            )
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentPrintLabelLayoutBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupToolbar()
        populateTemplateOptions()
        updatePreview()

        binding.radioGroupLabelTemplates.setOnCheckedChangeListener { group, checkedId ->
            val selectedRadioButton = group.findViewById<RadioButton>(checkedId)
            selectedTemplate = currentTemplateOptions.find { it.description == selectedRadioButton.text.toString() }
            updatePreview()
        }

        binding.buttonGeneratePdf.setOnClickListener {
            if (selectedTemplate == null) {
                Toast.makeText(context, "Selecciona una plantilla de etiquetas por hoja", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            generateAndSharePdf()
        }

        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                parentFragmentManager.popBackStack()
            }
        })
    }

    private fun setupToolbar() {
        (activity as? AppCompatActivity)?.supportActionBar?.apply {
            title = "Imprimir Etiquetas - Paso 3"
            subtitle = "Diseño y Exportar"
        }
    }

    private fun populateTemplateOptions() {
        binding.radioGroupLabelTemplates.removeAllViews()
        currentTemplateOptions.forEachIndexed { index, template ->
            val radioButton = RadioButton(context).apply {
                text = template.description
                id = View.generateViewId()
                layoutParams = RadioGroup.LayoutParams(
                    RadioGroup.LayoutParams.MATCH_PARENT,
                    RadioGroup.LayoutParams.WRAP_CONTENT
                )
                setPadding(0,16,0,16)
            }
            binding.radioGroupLabelTemplates.addView(radioButton)
            if (index == 0) {
                radioButton.isChecked = true
                selectedTemplate = template
            }
        }
    }

    private fun updatePreview() {
        binding.frameLayoutPreview.removeAllViews()
        labelData?.let { data ->
            val inflater = LayoutInflater.from(context)
            val dateStr = simpleDateFormat.format(data.date)
            val layoutId = if (data.labelType == LabelType.SIMPLE) R.layout.preview_label_simple else R.layout.preview_label_detailed
            val previewView = inflater.inflate(layoutId, binding.frameLayoutPreview, false)

            if (data.labelType == LabelType.SIMPLE) {
                previewView.findViewById<TextView>(R.id.textViewPreviewSupplier).text = data.supplierName ?: "PROVEEDOR"
                previewView.findViewById<TextView>(R.id.textViewPreviewDate).text = dateStr
            } else {
                previewView.findViewById<TextView>(R.id.textViewPreviewProductName).text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"
                previewView.findViewById<TextView>(R.id.textViewPreviewSupplier).text = "Proveedor: ${data.supplierName ?: "N/A"}"
                previewView.findViewById<TextView>(R.id.textViewPreviewDate).text = "Fecha: $dateStr"
                val weightTv = previewView.findViewById<TextView>(R.id.textViewPreviewWeight)
                val manualPlaceholder = previewView.findViewById<TextView>(R.id.textViewPreviewManualWeightPlaceholder)

                if (data.weight == "Manual") {
                    weightTv.visibility = View.GONE
                    manualPlaceholder.visibility = View.VISIBLE
                    manualPlaceholder.text = data.unit?.uppercase(Locale.ROOT) ?: "UNIDAD"
                } else {
                    weightTv.visibility = View.VISIBLE
                    manualPlaceholder.visibility = View.GONE
                    weightTv.text = "Peso: ${data.weight ?: "N/A"} ${data.unit ?: ""}"
                }
            }
            binding.frameLayoutPreview.addView(previewView)
            binding.textViewActualPreview.visibility = View.GONE
        } ?: run {
            binding.textViewActualPreview.text = "Configura los datos de la etiqueta."
            binding.textViewActualPreview.visibility = View.VISIBLE
        }
    }

    private fun drawLabelsOnCanvas(canvas: Canvas, data: LabelData, template: TemplateOption) {
        val usablePageWidth = PAGE_WIDTH_A4_POINTS - PAGE_MARGIN_LEFT_POINTS - PAGE_MARGIN_RIGHT_POINTS
        val usablePageHeight = PAGE_HEIGHT_A4_POINTS - PAGE_MARGIN_TOP_POINTS - PAGE_MARGIN_BOTTOM_POINTS

        val spacingH = if (template.columns > 1) LABEL_SPACING_POINTS else 0f
        val labelWidthPts = (usablePageWidth - ((template.columns - 1) * spacingH)) / template.columns
        val rows = (template.totalLabelsPerPage + template.columns - 1) / template.columns
        val labelHeightPts = (usablePageHeight - ((rows - 1) * LABEL_SPACING_POINTS)) / rows

        val borderPaint = Paint().apply { color = Color.DKGRAY; style = Paint.Style.STROKE; strokeWidth = 1f }

        for (i in 0 until template.totalLabelsPerPage) {
            val row = i / template.columns
            val col = i % template.columns

            val currentX = PAGE_MARGIN_LEFT_POINTS + col * (labelWidthPts + spacingH)
            val currentY = PAGE_MARGIN_TOP_POINTS + row * (labelHeightPts + LABEL_SPACING_POINTS)

            val labelRect = RectF(currentX, currentY, currentX + labelWidthPts, currentY + labelHeightPts)
            canvas.drawRect(labelRect, borderPaint)

            if (data.labelType == LabelType.SIMPLE) {
                drawSimpleLabelContent(canvas, data, labelRect)
            } else {
                drawDetailedLabelContent(canvas, data, labelRect)
            }
        }
    }

    private fun drawSimpleLabelContent(canvas: Canvas, data: LabelData, bounds: RectF) {
        val textPaint = TextPaint().apply { color = Color.BLACK; isAntiAlias = true }
        val dateStr = simpleDateFormat.format(data.date)
        val supplierText = data.supplierName ?: "PROVEEDOR"
        val labelPadding = bounds.width() * 0.05f

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        var yPos = bounds.top + bounds.height() * 0.1f

        yPos += drawTextToFit(canvas, supplierText, bounds.width() - (2 * labelPadding), bounds.height() * 0.45f, textPaint, bounds.left + labelPadding, yPos, Layout.Alignment.ALIGN_CENTER)

        yPos += bounds.height() * 0.05f

        drawTextToFit(canvas, dateStr, bounds.width() - (2 * labelPadding), bounds.height() * 0.30f, textPaint, bounds.left + labelPadding, yPos, Layout.Alignment.ALIGN_CENTER)
    }

    private fun drawDetailedLabelContent(canvas: Canvas, data: LabelData, bounds: RectF) {
        val textPaint = TextPaint().apply { color = Color.BLACK; isAntiAlias = true }
        val dateStr = simpleDateFormat.format(data.date)
        val productName = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"
        val labelPadding = bounds.width() * 0.05f

        var yPos = bounds.top + bounds.height() * 0.08f

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textPaint.letterSpacing = 0.08f
        yPos += drawTextToFit(canvas, productName, bounds.width() - (2 * labelPadding), bounds.height() * 0.22f, textPaint, bounds.left + labelPadding, yPos, Layout.Alignment.ALIGN_CENTER)
        textPaint.letterSpacing = 0f

        yPos += bounds.height() * 0.10f
        textPaint.textSize = bounds.height() * 0.09f
        val startX = bounds.left + labelPadding

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Proveedor:", startX, yPos, textPaint)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(data.supplierName ?: "N/A", startX + textPaint.measureText("Proveedor: ") + 5, yPos, textPaint)

        yPos += bounds.height() * 0.13f

        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.NORMAL)
        canvas.drawText("Fecha:", startX, yPos, textPaint)
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(dateStr, startX + textPaint.measureText("Fecha: ") + 5, yPos, textPaint)

        yPos += bounds.height() * 0.22f
        textPaint.textSize = bounds.height() * 0.25f
        textPaint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)

        if (data.weight == "Manual") {
            val weightLabel = "Peso: "
            val unitText = data.unit?.uppercase(Locale.ROOT) ?: ""
            canvas.drawText(weightLabel, startX, yPos, textPaint)
            val lineStartX = startX + textPaint.measureText(weightLabel) + 4
            val lineEndX = bounds.right - labelPadding - textPaint.measureText(unitText) - 4
            val lineY = yPos - (textPaint.textSize * 0.1f)
            val linePaint = Paint().apply{ color = Color.BLACK; strokeWidth = 1.5f }
            if (lineEndX > lineStartX) {
                canvas.drawLine(lineStartX, lineY, lineEndX, lineY, linePaint)
            }
            canvas.drawText(unitText, lineEndX + 4, yPos, textPaint)
        } else {
            val weightText = "Peso: ${data.weight ?: "N/A"} ${data.unit ?: ""}"
            drawTextToFit(canvas, weightText, bounds.width() - (2 * labelPadding), bounds.height() * 0.2f, textPaint, bounds.left + labelPadding, yPos, Layout.Alignment.ALIGN_CENTER)
        }
    }

    private fun drawTextToFit(canvas: Canvas, text: String, width: Float, maxHeight: Float, paint: TextPaint, x: Float, y: Float, align: Layout.Alignment): Float {
        paint.textSize = maxHeight
        var textWidth = paint.measureText(text)

        while (textWidth > width && paint.textSize > 6f) {
            paint.textSize -= 1f
            textWidth = paint.measureText(text)
        }

        val staticLayout = StaticLayout.Builder.obtain(text, 0, text.length, paint, width.toInt())
            .setAlignment(align)
            .setLineSpacing(0f, 1f)
            .setIncludePad(false)
            .build()

        canvas.save()
        canvas.translate(x, y)
        staticLayout.draw(canvas)
        canvas.restore()
        return staticLayout.height.toFloat()
    }

    private fun generateAndSharePdf() {
        val currentLabelData = labelData ?: return
        val currentTemplate = selectedTemplate ?: return
        val context = requireContext()

        val pdfDocument = PrintedPdfDocument(context, PrintAttributes.Builder()
            .setMediaSize(PrintAttributes.MediaSize.ISO_A4.asPortrait())
            .setResolution(PrintAttributes.Resolution("pdf", "pdf", 300, 300))
            .setMinMargins(PrintAttributes.Margins.NO_MARGINS)
            .build())

        val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_A4_POINTS, PAGE_HEIGHT_A4_POINTS, 1).create()
        val page = pdfDocument.startPage(pageInfo)

        drawLabelsOnCanvas(page.canvas, currentLabelData, currentTemplate)

        pdfDocument.finishPage(page)

        val fileName = "etiquetas_${System.currentTimeMillis()}.pdf"
        val filePath = File(context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS), fileName)

        try {
            FileOutputStream(filePath).use { pdfDocument.writeTo(it) }
            pdfDocument.close()
            sharePdf(filePath)
        } catch (e: IOException) {
            Log.e("PrintLabel", "Error al escribir PDF", e)
            Toast.makeText(context, "Error al generar PDF: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun sharePdf(file: File) {
        val context = requireContext()
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)

        val shareIntent = Intent().apply {
            action = Intent.ACTION_SEND
            putExtra(Intent.EXTRA_STREAM, uri)
            type = "application/pdf"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(shareIntent, "Compartir PDF usando..."))
        } catch (e: Exception) {
            Toast.makeText(context, "No se encontró una aplicación para compartir PDF.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        (activity as? AppCompatActivity)?.supportActionBar?.subtitle = null
        _binding = null
    }
}