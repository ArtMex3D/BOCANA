package com.cesar.bocana.ui.printing.pdf

import android.content.Context
import android.graphics.Canvas
import android.graphics.pdf.PdfDocument
import com.cesar.bocana.data.model.IndividualLabelConfig
import com.cesar.bocana.data.model.LabelData
import com.cesar.bocana.ui.printing.LabelArtworkRenderer
import com.cesar.bocana.ui.printing.LabelTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

object PdfGenerator {

    suspend fun createSingleLabelPdf(context: Context, data: LabelData, template: LabelTemplate): File {
        return withContext(Dispatchers.IO) {
            val pdf = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(template.pageWidthPt, template.pageHeightPt, 1).create()
            val page = pdf.startPage(pageInfo)
            val content = LabelArtworkRenderer.from(data)
            repeat(template.totalLabels) { index ->
                drawCell(context, page.canvas, template, index, content)
            }
            pdf.finishPage(page)
            savePdf(context, pdf)
        }
    }

    suspend fun createMultiLabelPdf(context: Context, configs: List<IndividualLabelConfig?>, template: LabelTemplate): File {
        return withContext(Dispatchers.IO) {
            val pdf = PdfDocument()
            val pageInfo = PdfDocument.PageInfo.Builder(template.pageWidthPt, template.pageHeightPt, 1).create()
            val page = pdf.startPage(pageInfo)
            configs.take(template.totalLabels).forEachIndexed { index, config ->
                config?.let { drawCell(context, page.canvas, template, index, LabelArtworkRenderer.from(it)) }
            }
            pdf.finishPage(page)
            savePdf(context, pdf)
        }
    }

    private fun drawCell(
        context: Context,
        canvas: Canvas,
        template: LabelTemplate,
        index: Int,
        content: LabelArtworkRenderer.Content
    ) {
        val row = index / template.columns
        val col = index % template.columns
        val x = template.pageMarginPt + col * (template.labelWidthPt + template.horizontalSpacingPt)
        val y = template.pageMarginPt + row * (template.labelHeightPt + template.verticalSpacingPt)
        canvas.save()
        canvas.translate(x, y)
        // Blindaje: ningún texto puede invadir la etiqueta vecina.
        canvas.clipRect(0f, 0f, template.labelWidthPt, template.labelHeightPt)
        LabelArtworkRenderer.draw(context, canvas, template.labelWidthPt, template.labelHeightPt, content)
        canvas.restore()
    }

    private fun savePdf(context: Context, pdf: PdfDocument): File {
        val file = File(context.cacheDir, "bocana_etiquetas_${System.currentTimeMillis()}.pdf")
        FileOutputStream(file).use { pdf.writeTo(it) }
        pdf.close()
        return file
    }
}
