package com.cesar.bocana.ui.printing.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.cesar.bocana.R
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.data.model.Product
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

    // --- FUNCIÓN PARA ETIQUETAS FIJAS (EXISTENTE, MODIFICADA LIGERAMENTE) ---
    suspend fun createSingleLabelPdf(context: Context, data: LabelData, template: LabelTemplate): File {
        return withContext(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_A4_POINTS, PAGE_HEIGHT_A4_POINTS, 1).create()
            val page = pdfDocument.startPage(pageInfo)

            // Convertimos el LabelData antiguo al nuevo formato para reutilizar la lógica de inflado
            val individualConfig = data.toIndividualConfig()
            val labelView = createAndPopulateLabelView(context, individualConfig, data.labelType)

            drawLabelsOnCanvas(page.canvas, labelView, template)
            pdfDocument.finishPage(page)
            savePdf(context, pdfDocument)
        }
    }

    // --- NUEVA FUNCIÓN PARA ETIQUETAS VARIABLES ---
    suspend fun createMultiLabelPdf(context: Context, configs: List<IndividualLabelConfig?>, template: LabelTemplate): File {
        return withContext(Dispatchers.IO) {
            val pdfDocument = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(PAGE_WIDTH_A4_POINTS, PAGE_HEIGHT_A4_POINTS, 1).create()
            val page = pdfDocument.startPage(pageInfo)
            val canvas = page.canvas

            for (i in configs.indices) {
                val config = configs[i]
                if (config != null) {
                    val labelView = createAndPopulateLabelView(context, config, LabelType.DETAILED)
                    val widthSpec = View.MeasureSpec.makeMeasureSpec(template.labelWidthPt.toInt(), View.MeasureSpec.EXACTLY)
                    val heightSpec = View.MeasureSpec.makeMeasureSpec(template.labelHeightPt.toInt(), View.MeasureSpec.EXACTLY)
                    labelView.measure(widthSpec, heightSpec)
                    labelView.layout(0, 0, labelView.measuredWidth, labelView.measuredHeight)

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
            pdfDocument.finishPage(page)
            savePdf(context, pdfDocument)
        }
    }


    private fun drawLabelsOnCanvas(canvas: Canvas, labelView: View, template: LabelTemplate) {
        val widthSpec = View.MeasureSpec.makeMeasureSpec(template.labelWidthPt.toInt(), View.MeasureSpec.EXACTLY)
        val heightSpec = View.MeasureSpec.makeMeasureSpec(template.labelHeightPt.toInt(), View.MeasureSpec.EXACTLY)
        labelView.measure(widthSpec, heightSpec)
        labelView.layout(0, 0, labelView.measuredWidth, labelView.measuredHeight)

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

    private fun createAndPopulateLabelView(context: Context, config: IndividualLabelConfig, type: LabelType): View {
        val inflater = LayoutInflater.from(context)
        val view: View

        if (type == LabelType.DETAILED) {
            view = inflater.inflate(R.layout.layout_label_detailed_v2, null)
            view.findViewById<TextView>(R.id.label_product_name).text = config.product.name.uppercase(Locale.ROOT)
            view.findViewById<TextView>(R.id.label_supplier).text = config.supplierName
            view.findViewById<TextView>(R.id.label_date).text = simpleDateFormat.format(config.date)
            val detailTv = view.findViewById<TextView>(R.id.label_detail)
            detailTv.text = config.detail ?: ""
            detailTv.visibility = if (config.detail.isNullOrBlank()) View.GONE else View.VISIBLE

            val predefinedWeightLayout = view.findViewById<TextView>(R.id.label_weight_predefined)
            val manualWeightLayout = view.findViewById<ViewGroup>(R.id.layout_weight_manual)

            if (config.weight == "Manual") {
                predefinedWeightLayout.visibility = View.GONE
                manualWeightLayout.visibility = View.VISIBLE
                manualWeightLayout.findViewById<TextView>(R.id.label_weight_manual_unit).text = config.unit
            } else {
                predefinedWeightLayout.visibility = View.VISIBLE
                manualWeightLayout.visibility = View.GONE
                predefinedWeightLayout.text = "PESO: ${config.weight ?: ""} ${config.unit}"
            }
        } else { // LabelType.SIMPLE
            view = inflater.inflate(R.layout.layout_label_simple, null)
            view.findViewById<TextView>(R.id.label_supplier_simple).text = config.supplierName
            view.findViewById<TextView>(R.id.label_date_simple).text = simpleDateFormat.format(config.date)
        }
        return view
    }


    private fun savePdf(context: Context, pdfDocument: PdfDocument): File {
        val file = File(context.cacheDir, "bocana_etiquetas.pdf")
        FileOutputStream(file).use { outputStream ->
            pdfDocument.writeTo(outputStream)
        }
        pdfDocument.close()
        return file
    }

    private fun LabelData.toIndividualConfig(): IndividualLabelConfig {
        // En el flujo simple, el producto no se selecciona, así que creamos uno dummy.
        val product = if (this.labelType == LabelType.SIMPLE) {
            Product(name = this.productName ?: "")
        } else {
            Product(id = this.productId ?: "", name = this.productName ?: "")
        }
        return IndividualLabelConfig(
            product = product,
            supplierName = this.supplierName ?: "",
            date = this.date,
            weight = this.weight,
            unit = this.unit ?: "",
            detail = this.detail
        )
    }
}