package com.cesar.bocana.ui.traspasos.plan

import android.content.Context
import android.graphics.Color as AndroidColor
import com.cesar.bocana.data.model.TraspasoSugerenciaItem
import com.cesar.bocana.ui.traspasos.config.ConfiguracionTraspasoFragment
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.DeviceGray
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

    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())

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

        // --- INICIO DE LA SOLUCIÓN: Cargar colores guardados ---
        val headerBgColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_HEADER_BG, AndroidColor.DKGRAY)
        val headerFontColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_HEADER_FONT, AndroidColor.WHITE)
        val zebraColorInt = getAndroidColor(context, ConfiguracionTraspasoFragment.KEY_ZEBRA, AndroidColor.parseColor("#E6F0FF")) // Azul muy claro

        val headerBgColor = toItextColor(headerBgColorInt)
        val headerFontColor = toItextColor(headerFontColorInt)
        val zebraColor = toItextColor(zebraColorInt)
        // --- FIN DE LA SOLUCIÓN ---

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

        val table = Table(
            UnitValue.createPercentArray(floatArrayOf(1.5f, 3f, 1.5f, 2f, 4f, 2f))
        ).useAllAvailableWidth()

        val headers = listOf("FECHA LOTE", "PRODUCTO", "CANTIDAD", "PROVEEDOR", "PESO C/U", "TOTAL")
        headers.forEach { headerText ->
            // --- INICIO DE LA SOLUCIÓN: Aplicar colores a las cabeceras ---
            val cell = Cell().add(Paragraph(headerText))
                .setBackgroundColor(headerBgColor)
                .setFontColor(headerFontColor)
                .setBold()
                .setFontSize(8f)
                .setTextAlignment(TextAlignment.CENTER)
            table.addHeaderCell(cell)
            // --- FIN DE LA SOLUCIÓN ---
        }

        var isZebra = false
        plan.filter { it.incluidoEnPdf && it.lotesParaTraspaso.isNotEmpty() }.forEach { item ->
            val bgColor = if (isZebra) zebraColor else null
            val totalLotes = item.lotesParaTraspaso.size

            item.lotesParaTraspaso.forEachIndexed { index, desglose ->
                val lote = desglose.lote

                table.addCell(createCell(dateFormat.format(lote.receivedAt ?: Date()), bgColor, TextAlignment.CENTER, isFirstRow = (index == 0), item = item))

                if (index == 0) {
                    table.addCell(
                        Cell(totalLotes, 1)
                            .add(Paragraph(item.product.name).setPaddingLeft(5f))
                            .setVerticalAlignment(VerticalAlignment.MIDDLE)
                            .setBold()
                            .setBackgroundColor(bgColor)
                            .setHeight(calculateRowHeight(item))
                    )
                }

                val cantidadStr = "${desglose.cantidadATomarUnidades?.toInt() ?: ""} ${lote.unidadDeEmpaque ?: ""}".trim()
                table.addCell(createCell(cantidadStr, bgColor, TextAlignment.CENTER, isFirstRow = (index == 0), item = item))
                table.addCell(createCell(lote.supplierName ?: "S/P", bgColor, TextAlignment.CENTER, isFirstRow = (index == 0), item = item))

                val isManual = item.product.modoManualPDF
                val esFijo = !item.product.requiresPackaging && lote.pesoPorUnidad != null && lote.pesoPorUnidad > 0
                val pesoUnitarioStr = if (esFijo && !isManual) String.format("%.2f Kg", lote.pesoPorUnidad) else ""
                val totalKgStr = if (esFijo && !isManual) String.format("%.2f Kg", desglose.cantidadATomarKg) else ""

                table.addCell(createCell(pesoUnitarioStr, bgColor, TextAlignment.CENTER, isFirstRow = (index == 0), item = item))
                table.addCell(createCell(totalKgStr, bgColor, TextAlignment.RIGHT, isFirstRow = (index == 0), item = item))
            }
            isZebra = !isZebra
        }
        document.add(table)

        val signatureTable = Table(UnitValue.createPercentArray(floatArrayOf(1f, 1f)))
            .useAllAvailableWidth()
            .setMarginTop(40f)
            .setBorder(null)

        signatureTable.addCell(createSignatureCell("Verificó mercancía:"))
        signatureTable.addCell(createSignatureCell("Sacó mercancía:"))
        document.add(signatureTable)

        document.close()
        return@withContext file
    }

    private fun createCell(text: String, bgColor: Color?, alignment: TextAlignment = TextAlignment.LEFT, isFirstRow: Boolean, item: TraspasoSugerenciaItem): Cell {
        val cell = Cell().add(Paragraph(text).setFontSize(8f).setPadding(2f))
        cell.setTextAlignment(alignment)
        cell.setVerticalAlignment(VerticalAlignment.MIDDLE)
        bgColor?.let { cell.setBackgroundColor(it) }
        if (isFirstRow) {
            cell.setHeight(calculateRowHeight(item))
        }
        return cell
    }

    private fun calculateRowHeight(item: TraspasoSugerenciaItem): Float {
        val baseHeight = 18f
        val extraSpaceMultiplier = if (item.product.espacioExtraPDF > 0) item.product.espacioExtraPDF.toFloat() + 1.0f else 1.0f
        val totalLotesMultiplier = item.lotesParaTraspaso.size.toFloat()
        return baseHeight * totalLotesMultiplier * extraSpaceMultiplier
    }

    private fun createSignatureCell(text: String): Cell {
        return Cell().add(Paragraph(text).setFontSize(10f))
            .add(Paragraph("\n\n__________________").setFontSize(10f))
            .setTextAlignment(TextAlignment.CENTER)
            .setBorder(null)
    }
}
