package com.cesar.bocana.ui.printing.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.ui.printing.LabelTemplate
import com.cesar.bocana.ui.printing.LabelType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object PdfGenerator {
    private const val PAGE_WIDTH_A4_POINTS = 595
    private const val PAGE_HEIGHT_A4_POINTS = 842
    private val simpleDateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

    suspend fun createSingleLabelPdf(context: Context, data: LabelData, template: LabelTemplate): File {
        return withContext(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_A4_POINTS, PAGE_HEIGHT_A4_POINTS, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            val labelView = createAndPopulateLabelView(context, data)

            val widthSpec = View.MeasureSpec.makeMeasureSpec(template.labelWidthPt.toInt(), View.MeasureSpec.EXACTLY)
            val heightSpec = View.MeasureSpec.makeMeasureSpec(template.labelHeightPt.toInt(), View.MeasureSpec.EXACTLY)
            labelView.measure(widthSpec, heightSpec)
            labelView.layout(0, 0, labelView.measuredWidth, labelView.measuredHeight)

            drawLabelsOnCanvas(canvas, labelView, template)

            pdfDocument.finishPage(page)

            val file = File(context.cacheDir, "bocana_etiquetas.pdf")
            FileOutputStream(file).use { outputStream ->
                pdfDocument.writeTo(outputStream)
            }
            pdfDocument.close()
            file
        }
    }

    private fun drawLabelsOnCanvas(canvas: Canvas, labelView: View, template: LabelTemplate) {
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

        if (data.labelType == LabelType.DETAILED) {
            // CORRECCIÓN 1: Usar el layout v2, que es el correcto y moderno.
            view = inflater.inflate(R.layout.layout_label_detailed_v2, null)

            view.findViewById<TextView>(R.id.label_product_name).text = data.productName?.uppercase(Locale.ROOT) ?: "PRODUCTO"
            view.findViewById<TextView>(R.id.label_supplier).text = data.supplierName ?: "PROVEEDOR"
            view.findViewById<TextView>(R.id.label_date).text = simpleDateFormat.format(data.date)

            val detailTv = view.findViewById<TextView>(R.id.label_detail)
            detailTv.text = data.detail ?: ""
            detailTv.visibility = if (data.detail.isNullOrBlank()) View.GONE else View.VISIBLE

            // CORRECCIÓN 2: Lógica idéntica a la previsualización para manejar los dos tipos de layouts de peso.
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
        } else { // LabelType.SIMPLE
            view = inflater.inflate(R.layout.layout_label_simple, null)
            view.findViewById<TextView>(R.id.label_supplier_simple).text = data.supplierName ?: ""
            view.findViewById<TextView>(R.id.label_date_simple).text = simpleDateFormat.format(data.date)
        }
        return view
    }
}