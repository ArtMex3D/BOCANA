package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.graphics.Color as AndroidColor
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.ui.traspasos.config.ConfiguracionTraspasoFragment
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import com.itextpdf.layout.properties.VerticalAlignment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

object TraspasoPdfGenerator {

    private val dateFormat = SimpleDateFormat("dd/MM/yy", Locale.getDefault())

    private fun getAndroidColor(context: Context, key: String, defaultColor: Int): Int {
        val prefs = context.getSharedPreferences(ConfiguracionTraspasoFragment.PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(key, defaultColor)
    }

    private fun toItextColor(androidColor: Int): Color {
        return DeviceRgb(AndroidColor.red(androidColor), AndroidColor.green(androidColor), AndroidColor.blue(androidColor))
    }

    suspend fun createTraspasoPdf(
        context: Context,
        plan: List<TraspasoSugerenciaItem>,
        fechaTraspaso: Date
    ): File = withContext(Dispatchers.IO) {

        val headerBgColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_HEADER_BG, AndroidColor.DKGRAY)
        val headerFontColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_HEADER_FONT, AndroidColor.WHITE)
        val zebraColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_ZEBRA, AndroidColor.parseColor("#E6F0FF"))

        val headerBgColor = toItextColor(headerBgColorInt)
        val headerFontColor = toItextColor(headerFontColorInt)
        val zebraColor = toItextColor(zebraColorInt)

        val file = File(context.cacheDir, "plan_traspaso.pdf")
        val writer = PdfWriter(FileOutputStream(file))
        val pdfDocument = PdfDocument(writer)
        val document = Document(pdfDocument, PageSize.A4.rotate())
        document.setMargins(20f, 20f, 20f, 20f)

        val headerTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f))).useAllAvailableWidth()
        headerTable.addCell(
            Cell().add(Paragraph("Traspaso Matriz a Congelador"))
                .setBold().setFontSize(14f).setBorder(null)
        )
        headerTable.addCell(
            Cell().add(Paragraph("FECHA: ${dateFormat.format(fechaTraspaso)}"))
                .setTextAlignment(TextAlignment.RIGHT).setBold().setFontSize(12f).setBorder(null)
        )
        document.add(headerTable)
        document.add(Paragraph("\n"))

        val columnWidths = floatArrayOf(1.5f, 3f, 1.5f, 2f, 4f, 2f)
        val table = Table(UnitValue.createPercentArray(columnWidths)).useAllAvailableWidth()

        val headers = listOf("FECHA LOTE", "PRODUCTO", "CANTIDAD", "PROVEEDOR", "PESO C/U", "TOTAL")
        headers.forEach { headerText ->
            val cell = Cell().add(Paragraph(headerText))
                .setBackgroundColor(headerBgColor)
                .setFontColor(headerFontColor)
                .setBold()
                .setFontSize(8f)
                .setTextAlignment(TextAlignment.CENTER)
            table.addHeaderCell(cell)
        }

        var isZebra = false
        plan.filter { it.incluidoEnPdf && (it.lotesParaTraspaso.isNotEmpty() || it.product.id == "FILA_VACIA" || it.product.name == "FILA_VACIA") }.forEach { item ->

            if (item.product.id == "FILA_VACIA" || item.product.name == "FILA_VACIA") {
                val bgColor = if (isZebra) zebraColor else null
                val cantidadFilas = if (item.cantidadEditadaUnidades > 0) item.cantidadEditadaUnidades else 1
                for (i in 1..cantidadFilas) {
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(18f))
                    table.addCell(createCell("", bgColor, TextAlignment.LEFT).setMinHeight(18f))
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(18f))
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(18f))
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(18f))
                    table.addCell(createCell("", bgColor, TextAlignment.RIGHT).setMinHeight(18f))
                }
                isZebra = !isZebra
            } else {
                val bgColor = if (isZebra) zebraColor else null
                val totalLotes = item.lotesParaTraspaso.size.coerceAtLeast(1)

                val rowHeight = calculateRowHeight(item, totalLotes)
                val totalKgForProduct = item.lotesParaTraspaso.sumOf { it.cantidadATomarKg }
                val isManual = item.product.modoManualPDF

                val esAlgunoFijo = !item.product.requiresPackaging
                val totalKgStr = if (esAlgunoFijo && !isManual) String.format("%.2f Kg", totalKgForProduct) else ""

                if (item.lotesParaTraspaso.isNotEmpty()) {
                    item.lotesParaTraspaso.forEachIndexed { index, desglose ->
                        table.addCell(createCell(dateFormat.format(desglose.loteFecha ?: Date()), bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))

                        if (index == 0) {
                            table.addCell(
                                Cell(totalLotes, 1)
                                    .add(Paragraph(item.product.name).setPaddingLeft(5f))
                                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                                    .setBold()
                                    .setBackgroundColor(bgColor)
                            )
                        }

                        val cantidadStr = "${desglose.cantidadATomarUnidades?.toInt() ?: ""} ${desglose.loteUnidad ?: ""}".trim()
                        table.addCell(createCell(cantidadStr, bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))
                        table.addCell(createCell(desglose.loteProveedor ?: "S/P", bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))

                        val esFijo = !item.product.requiresPackaging && desglose.lotePesoPorUnidad != null && desglose.lotePesoPorUnidad > 0
                        val pesoUnitarioStr = if (esFijo && !isManual) String.format("%.2f Kg", desglose.lotePesoPorUnidad) else ""
                        table.addCell(createCell(pesoUnitarioStr, bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))

                        if (index == 0) {
                            table.addCell(
                                Cell(totalLotes, 1)
                                    .add(Paragraph(totalKgStr))
                                    .setTextAlignment(TextAlignment.RIGHT)
                                    .setVerticalAlignment(VerticalAlignment.MIDDLE)
                                    .setBold()
                                    .setBackgroundColor(bgColor)
                            )
                        }
                    }
                } else {
                    val cantidadStr = if (item.cantidadEditadaUnidades > 0) "${item.cantidadEditadaUnidades} ${item.unidadDeEmpaqueEditada}" else ""
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))
                    table.addCell(Cell(1,1).add(Paragraph(item.product.name).setPaddingLeft(5f)).setBold().setBackgroundColor(bgColor))
                    table.addCell(createCell(cantidadStr, bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))
                    table.addCell(createCell("", bgColor, TextAlignment.CENTER).setMinHeight(rowHeight))
                    table.addCell(createCell(totalKgStr, bgColor, TextAlignment.RIGHT).setBold().setMinHeight(rowHeight))
                }
                isZebra = !isZebra
            }
        }
        document.add(table)

        // FIRMAS EN PARALELO, PEGADAS A LA TABLA
        val signatureTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f)))
            .useAllAvailableWidth()
            .setMarginTop(10f) // Salto de renglón pequeñito
            .setBorder(null)

        val celdaReviso = Cell().add(Paragraph("REVISÓ: ________________________").setFontSize(10f).setBold())
            .setTextAlignment(TextAlignment.LEFT)
            .setBorder(null)

        val celdaSaco = Cell().add(Paragraph("SACÓ: ________________________").setFontSize(10f).setBold())
            .setTextAlignment(TextAlignment.RIGHT)
            .setBorder(null)

        signatureTable.addCell(celdaReviso)
        signatureTable.addCell(celdaSaco)

        document.add(signatureTable)
        document.close()

        return@withContext file
    }

    private fun createCell(text: String, bgColor: Color?, alignment: TextAlignment = TextAlignment.LEFT): Cell {
        val cell = Cell().add(Paragraph(text).setFontSize(8f).setPadding(2f))
        cell.setTextAlignment(alignment)
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE)
        bgColor?.let { cell.setBackgroundColor(it) }
        return cell
    }

    private fun calculateRowHeight(item: TraspasoSugerenciaItem, totalLotes: Int): Float {
        val baseHeight = 18f
        if (totalLotes > 1) {
            return baseHeight
        }
        val extraSpaceMultiplier = 1.0f + item.product.espacioExtraPDF.toFloat()
        return baseHeight * extraSpaceMultiplier
    }
}