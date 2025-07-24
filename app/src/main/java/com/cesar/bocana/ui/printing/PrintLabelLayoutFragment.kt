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
import android.widget.ImageView
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
import com.cesar.bocana.data.model.QrCodeOption
import com.cesar.bocana.databinding.FragmentPrintLabelLayoutBinding
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

        availableTemplates = if (labelData?.labelType == LabelType.SIMPLE) {
            listOf(
                LabelTemplate("10 por hoja (2x5)", 10, 2, 280f, 158f),
                LabelTemplate("14 por hoja (2x7)", 14, 2, 280f, 110f),
                LabelTemplate("18 por hoja (2x9)", 18, 2, 280f, 85f)
            )
        } else { // DETAILED
            listOf(
                LabelTemplate("6 por hoja (2x3)", 6, 2, 280f, 265f),
                LabelTemplate("8 por hoja (2x4)", 8, 2, 280f, 198f),
                LabelTemplate("10 por hoja (2x5)", 10, 2, 280f, 158f),
                LabelTemplate("12 por hoja (3x4)", 12, 3, 185f, 198f)
            )
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
        val currentTemplate = selectedTemplate ?: return

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

        val (qrBitmapS, qrBitmapM) = generateQrBitmaps(data)

        val labelView = createAndPopulateLabelView(requireContext(), data, qrBitmapS, qrBitmapM)

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

    private suspend fun generateQrBitmaps(data: LabelData): Pair<Bitmap?, Bitmap?> {
        return withContext(Dispatchers.Default) {
            val qrS = if (data.qrCodeOption == QrCodeOption.STOCK_WEB || data.qrCodeOption == QrCodeOption.BOTH) {
                QRGenerator.generate("https://bocana.netlify.app/qr.html?id=${data.productId}", 150, 'S')
            } else null
            val qrM = if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) {
                QRGenerator.generate("https://bocana.netlify.app/movimiento/${data.productId}", 150, 'M')
            } else null
            qrS to qrM
        }
    }

    // ##### INICIO DE LA FUNCIÓN MODIFICADA #####
    private fun createAndPopulateLabelView(context: Context, data: LabelData, qrS: Bitmap?, qrM: Bitmap?): View {
        val inflater = LayoutInflater.from(context)
        val view: View

        if (data.labelType == LabelType.DETAILED) {
            view = inflater.inflate(R.layout.layout_label_detailed, null)
            val productNameTv = view.findViewById<TextView>(R.id.label_product_name)
            // CAMBIO: Se obtienen las referencias a los nuevos TextViews separados
            val supplierTv = view.findViewById<TextView>(R.id.label_supplier)
            val dateTv = view.findViewById<TextView>(R.id.label_date)
            val weightTv = view.findViewById<TextView>(R.id.label_weight)
            // CAMBIO: Solo necesitamos la referencia al QR 'M'
            val qrMIv = view.findViewById<ImageView>(R.id.label_qr_m)

            productNameTv.text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"

            // CAMBIO: Se asigna el texto directamente a cada TextView sin prefijos
            supplierTv.text = data.supplierName ?: "PROVEEDOR"
            dateTv.text = simpleDateFormat.format(data.date)

            weightTv.text = if (data.weight == "Manual") "PESO: ____________ ${data.unit ?: ""}" else "PESO: ${data.weight} ${data.unit}"

            qrMIv.setImageBitmap(qrM)
            // CAMBIO: La visibilidad ahora solo depende del QR 'M'
            qrMIv.visibility = if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE

        } else { // LabelType.SIMPLE
            view = inflater.inflate(R.layout.layout_label_simple, null)
            val supplierTv = view.findViewById<TextView>(R.id.label_supplier_simple)
            val dateTv = view.findViewById<TextView>(R.id.label_date_simple)
            val qrSIv = view.findViewById<ImageView>(R.id.label_qr_s_simple)
            val qrMIv = view.findViewById<ImageView>(R.id.label_qr_m_simple)

            supplierTv.text = data.supplierName ?: "PROVEEDOR"
            dateTv.text = simpleDateFormat.format(data.date)

            qrSIv.setImageBitmap(qrS)
            qrMIv.setImageBitmap(qrM)
            qrSIv.visibility = if (data.qrCodeOption == QrCodeOption.STOCK_WEB || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE
            qrMIv.visibility = if (data.qrCodeOption == QrCodeOption.MOVEMENTS_APP || data.qrCodeOption == QrCodeOption.BOTH) View.VISIBLE else View.GONE
        }
        return view
    }
    // ##### FIN DE LA FUNCIÓN MODIFICADA #####

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