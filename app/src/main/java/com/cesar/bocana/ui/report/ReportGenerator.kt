package com.cesar.bocana.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.cesar.bocana.data.local.AppDatabase
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.ReportColumn
import com.cesar.bocana.data.model.ReportConfig
import com.cesar.bocana.predictive.v3.data.PredictiveV3Snapshot
import com.itextpdf.kernel.colors.Color
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.element.Text
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.round

object ReportGenerator {

    private const val TAG = "ReportGenerator"
    private val dateTimeFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    private val darkBlue: Color = DeviceRgb(3, 4, 94)
    private val lightBlue: Color = DeviceRgb(230, 240, 255)
    private val veryLightBlue: Color = DeviceRgb(246, 249, 255)
    private val softGray: Color = DeviceRgb(105, 112, 122)
    private val pargosColor: Color = DeviceRgb(0, 105, 92)
    private val filetesColor: Color = DeviceRgb(21, 101, 192)
    private val genericGroupColor: Color = DeviceRgb(80, 92, 120)

    private data class ReportRow(
        val product: Product,
        val snapshot: PredictiveV3Snapshot?
    )

    private data class ReportData(
        val rows: List<ReportRow>,
        val allProducts: List<Product>,
        val allSnapshots: List<PredictiveV3Snapshot>
    )

    suspend fun generatePdf(context: Context, config: ReportConfig) {
        try {
            val data = fetchReportData(context, config)
            if (data.rows.isEmpty()) {
                throw IllegalStateException("No hay productos locales disponibles para el reporte")
            }
            val file = createPdfFile(context, config, data)
            sharePdf(context, file)
        } catch (e: Exception) {
            Log.e(TAG, "Error al generar el reporte", e)
            withContext(Dispatchers.Main) {
                showErrorDialog(context, e, config)
            }
        }
    }

    private suspend fun fetchReportData(context: Context, config: ReportConfig): ReportData = withContext(Dispatchers.IO) {
        val database = AppDatabase.getDatabase(context.applicationContext)
        val allProducts = database.productDao()
            .getAllActiveProductsStream()
            .first()
            .sortedBy { it.name.lowercase(Locale.getDefault()) }
        val allSnapshots = database.predictiveV3SnapshotDao().getAllOnce()
        val snapshotsByProduct = allSnapshots.associateBy { it.productId }
        val selectedIds = config.productIds.toSet()
        val rows = allProducts
            .filter { it.id in selectedIds }
            .map { ReportRow(it, snapshotsByProduct[it.id]) }
        ReportData(rows, allProducts, allSnapshots)
    }

    private suspend fun createPdfFile(context: Context, config: ReportConfig, data: ReportData): File = withContext(Dispatchers.IO) {
        val file = File(context.cacheDir, "Existencias.pdf")
        val writer = PdfWriter(file)
        val pdfDocument = PdfDocument(writer)
        val document = Document(pdfDocument, PageSize.A4)
        document.setMargins(26f, 24f, 28f, 24f)

        document.add(
            Paragraph(config.reportTitle)
                .setTextAlignment(TextAlignment.CENTER)
                .setBold()
                .setFontColor(darkBlue)
                .setFontSize(17f)
                .setMarginBottom(2f)
        )
        document.add(
            Paragraph("Generado el ${dateTimeFormat.format(Date())}")
                .setTextAlignment(TextAlignment.CENTER)
                .setFontColor(softGray)
                .setFontSize(8.5f)
                .setMarginBottom(14f)
        )

        val columns = orderedColumns(config.columns)
        val table = Table(UnitValue.createPercentArray(columnWidths(columns))).useAllAvailableWidth()
        table.setFontSize(tableFontSize(columns.size))

        addHeader(table, "Producto", true)
        columns.forEach { column -> addHeader(table, column.title, column == ReportColumn.STOCK_TOTAL) }

        data.rows.forEachIndexed { index, row ->
            val bg = if (index % 2 == 1) lightBlue else null
            val productCell = Cell()
                .setPadding(4f)
                .add(
                    Paragraph(row.product.name)
                        .setBold()
                        .setFontSize(tableFontSize(columns.size))
                        .setMargin(0f)
                )
            bg?.let { productCell.setBackgroundColor(it) }
            table.addCell(productCell)

            columns.forEach { column ->
                table.addCell(buildDataCell(row, column, bg, columns.size))
            }
        }

        document.add(table)
        addGroupLegendIfNeeded(document, config, data)
        document.close()
        file
    }

    private fun orderedColumns(columns: List<ReportColumn>): List<ReportColumn> {
        val order = listOf(
            ReportColumn.STOCK_C04,
            ReportColumn.STOCK_MATRIZ,
            ReportColumn.STOCK_TOTAL,
            ReportColumn.CONSUMO_SEMANAL,
            ReportColumn.CONSUMO_MENSUAL,
            ReportColumn.SE_AGOTA_EN,
            ReportColumn.ULTIMA_ACTUALIZACION
        )
        return columns.filter { it in order }.sortedBy { order.indexOf(it) }
    }

    private fun columnWidths(columns: List<ReportColumn>): FloatArray {
        val widths = mutableListOf(3.2f)
        columns.forEach { column ->
            widths += when (column) {
                ReportColumn.ULTIMA_ACTUALIZACION -> 2.5f
                ReportColumn.SE_AGOTA_EN -> 1.7f
                ReportColumn.CONSUMO_SEMANAL, ReportColumn.CONSUMO_MENSUAL -> 1.55f
                else -> 1.45f
            }
        }
        return widths.toFloatArray()
    }

    private fun tableFontSize(dataColumnCount: Int): Float = when {
        dataColumnCount >= 7 -> 6.9f
        dataColumnCount >= 6 -> 7.3f
        dataColumnCount >= 5 -> 7.8f
        else -> 8.4f
    }

    private fun addHeader(table: Table, title: String, strong: Boolean) {
        val cell = Cell()
            .setPaddingTop(6f)
            .setPaddingBottom(6f)
            .setPaddingLeft(3f)
            .setPaddingRight(3f)
            .setTextAlignment(TextAlignment.CENTER)
            .add(Paragraph(title).setBold().setMargin(0f))

        if (strong) {
            cell.setBackgroundColor(darkBlue).setFontColor(DeviceRgb(255, 255, 255))
        } else {
            cell.setBackgroundColor(DeviceRgb(225, 231, 246)).setFontColor(darkBlue)
        }
        table.addHeaderCell(cell)
    }

    private fun buildDataCell(
        row: ReportRow,
        column: ReportColumn,
        background: Color?,
        dataColumnCount: Int
    ): Cell {
        val cell = Cell()
            .setPaddingTop(4f)
            .setPaddingBottom(4f)
            .setPaddingLeft(2.5f)
            .setPaddingRight(2.5f)
            .setTextAlignment(TextAlignment.CENTER)
        background?.let { cell.setBackgroundColor(it) }

        when (column) {
            ReportColumn.STOCK_C04 -> cell.add(quantityParagraph(row.product.stockCongelador04, row.product.unit, false, dataColumnCount))
            ReportColumn.STOCK_MATRIZ -> cell.add(quantityParagraph(row.product.stockMatriz, row.product.unit, false, dataColumnCount))
            ReportColumn.STOCK_TOTAL -> cell.add(quantityParagraph(row.product.totalStock, row.product.unit, true, dataColumnCount))
            ReportColumn.CONSUMO_SEMANAL -> addPredictiveQuantity(cell, row, row.snapshot?.baselineWeeklyKg, dataColumnCount)
            ReportColumn.CONSUMO_MENSUAL -> addPredictiveQuantity(
                cell,
                row,
                row.snapshot?.baselineWeeklyKg?.times(30.4375 / 7.0),
                dataColumnCount
            )
            ReportColumn.SE_AGOTA_EN -> addCoverage(cell, row, dataColumnCount)
            ReportColumn.ULTIMA_ACTUALIZACION -> cell.add(
                Paragraph(row.product.updatedAt?.let { dateTimeFormat.format(it) } ?: "—")
                    .setFontSize((tableFontSize(dataColumnCount) - 0.3f).coerceAtLeast(6.2f))
                    .setFontColor(softGray)
                    .setMargin(0f)
            )
            else -> cell.add(Paragraph("—").setMargin(0f))
        }
        return cell
    }

    private fun quantityParagraph(value: Double, unit: String, bold: Boolean, dataColumnCount: Int): Paragraph {
        val baseSize = tableFontSize(dataColumnCount)
        val p = Paragraph().setMargin(0f).setTextAlignment(TextAlignment.CENTER)
        val number = Text(formatNumber(value)).setFontSize(baseSize)
        if (bold) number.setBold()
        p.add(number)
        if (unit.isNotBlank()) {
            p.add(
                Text(" ${unit.trim()}")
                    .setFontSize((baseSize - 1.4f).coerceAtLeast(5.7f))
                    .setFontColor(softGray)
            )
        }
        return p
    }

    private fun addPredictiveQuantity(cell: Cell, row: ReportRow, value: Double?, dataColumnCount: Int) {
        if (value == null || value <= 0.0) {
            cell.add(Paragraph("—").setFontColor(softGray).setMargin(0f))
            return
        }
        cell.add(quantityParagraph(value, row.product.unit, true, dataColumnCount))
        addGroupTag(cell, row.snapshot)
    }

    private fun addCoverage(cell: Cell, row: ReportRow, dataColumnCount: Int) {
        val days = row.snapshot?.coverageDays
        if (days == null) {
            cell.add(Paragraph("—").setFontColor(softGray).setMargin(0f))
            return
        }
        val baseSize = tableFontSize(dataColumnCount)
        cell.add(
            Paragraph()
                .setMargin(0f)
                .setTextAlignment(TextAlignment.CENTER)
                .add(Text(days.toString()).setBold().setFontSize(baseSize))
                .add(Text(" días").setFontSize((baseSize - 1.4f).coerceAtLeast(5.7f)).setFontColor(softGray))
        )
        addGroupTag(cell, row.snapshot)
    }

    private fun addGroupTag(cell: Cell, snapshot: PredictiveV3Snapshot?) {
        val name = snapshot?.groupName?.trim().orEmpty()
        if (name.isBlank()) return
        cell.add(
            Paragraph(name.uppercase(Locale.getDefault()))
                .setFontSize(5.7f)
                .setBold()
                .setFontColor(groupColor(name))
                .setTextAlignment(TextAlignment.CENTER)
                .setMarginTop(0f)
                .setMarginBottom(0f)
        )
    }

    private fun addGroupLegendIfNeeded(document: Document, config: ReportConfig, data: ReportData) {
        val hasConsumption = config.columns.any {
            it == ReportColumn.CONSUMO_SEMANAL || it == ReportColumn.CONSUMO_MENSUAL
        }
        val hasCoverage = config.columns.contains(ReportColumn.SE_AGOTA_EN)
        if (!hasConsumption && !hasCoverage) return

        val groups = data.rows.mapNotNull { row ->
            val snapshot = row.snapshot ?: return@mapNotNull null
            val name = snapshot.groupName?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = snapshot.groupId?.takeIf { it.isNotBlank() } ?: name.lowercase(Locale.getDefault())
            key to name
        }.distinctBy { it.first }

        if (groups.isEmpty()) return

        document.add(
            Paragraph("Referencia de conjuntos")
                .setBold()
                .setFontColor(darkBlue)
                .setFontSize(9f)
                .setMarginTop(12f)
                .setMarginBottom(4f)
        )

        val productsById = data.allProducts.associateBy { it.id }
        groups.forEach { (groupKey, groupName) ->
            val members = data.allSnapshots
                .filter { snapshot ->
                    val key = snapshot.groupId?.takeIf { it.isNotBlank() }
                        ?: snapshot.groupName?.lowercase(Locale.getDefault())
                    key == groupKey
                }
                .mapNotNull { productsById[it.productId]?.name }
                .distinct()
                .sortedBy { it.lowercase(Locale.getDefault()) }

            val metricText = when {
                hasConsumption && hasCoverage -> "El consumo promedio y la cobertura se calculan considerando el conjunto completo, no cada producto por separado."
                hasConsumption -> "El consumo promedio se calcula considerando el conjunto completo, no cada producto por separado."
                else -> "La cobertura estimada se calcula considerando el inventario y consumo del conjunto completo."
            }
            val memberText = if (members.isNotEmpty()) " Integrantes: ${members.joinToString(", ")}." else ""

            val paragraph = Paragraph().setFontSize(7.3f).setMarginTop(1f).setMarginBottom(3f)
            paragraph.add(Text("${groupName.uppercase(Locale.getDefault())}: ").setBold().setFontColor(groupColor(groupName)))
            paragraph.add(Text(metricText + memberText).setFontColor(softGray))
            document.add(paragraph)
        }
    }

    private fun groupColor(name: String): Color {
        val normalized = name.uppercase(Locale.getDefault())
        return when {
            "PARGO" in normalized || "HUACHINANGO" in normalized -> pargosColor
            "FILETE" in normalized || "LENGUA" in normalized || "CURVINA" in normalized -> filetesColor
            else -> genericGroupColor
        }
    }

    private fun formatNumber(value: Double): String {
        return if (abs(value - round(value)) < 0.005) {
            "%.0f".format(Locale.getDefault(), value)
        } else {
            "%.1f".format(Locale.getDefault(), value)
        }
    }

    private fun sharePdf(context: Context, file: File) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.provider", file)
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = "application/pdf"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(intent, "Compartir PDF"))
        } catch (_: Exception) {
            Toast.makeText(context, "No se pudo compartir el PDF", Toast.LENGTH_LONG).show()
        }
    }

    private fun showErrorDialog(context: Context, e: Exception, config: ReportConfig) {
        val errorTrace = e.stackTraceToString()
        val configDetails = "Productos (${config.productIds.size}): ${config.productIds.joinToString()}"
        val errorMessage = "${e.localizedMessage}\n\n$configDetails\n\n$errorTrace"

        AlertDialog.Builder(context)
            .setTitle("Error al generar reporte")
            .setMessage("No se pudo generar el reporte con los datos locales disponibles.")
            .setPositiveButton("Cerrar") { dialog, _ -> dialog.dismiss() }
            .setNeutralButton("Copiar detalles") { dialog, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Error Reporte", errorMessage))
                Toast.makeText(context, "Detalles copiados", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}
