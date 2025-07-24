package com.cesar.bocana.ui.report

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.core.content.FileProvider
import com.cesar.bocana.data.model.Product
import com.cesar.bocana.data.model.ReportColumn
import com.cesar.bocana.data.model.ReportConfig
import com.cesar.bocana.util.CalculationUtils
import com.google.firebase.firestore.FieldPath
import com.google.firebase.firestore.ktx.firestore
import com.google.firebase.firestore.ktx.toObjects
import com.google.firebase.ktx.Firebase
import com.itextpdf.kernel.colors.DeviceGray
import com.itextpdf.kernel.geom.PageSize
import com.itextpdf.kernel.pdf.PdfDocument
import com.itextpdf.kernel.pdf.PdfWriter
import com.itextpdf.layout.Document
import com.itextpdf.layout.element.Cell
import com.itextpdf.layout.element.Paragraph
import com.itextpdf.layout.element.Table
import com.itextpdf.layout.properties.TextAlignment
import com.itextpdf.layout.properties.UnitValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import com.itextpdf.kernel.colors.DeviceRgb
import com.itextpdf.kernel.colors.Color

object ReportGenerator {

    private const val TAG = "ReportGenerator"

    private val db = Firebase.firestore
    private val dateFormat = SimpleDateFormat("dd/MM/yyyy", Locale.getDefault())
    private val dateTimeFormat = SimpleDateFormat("dd/MM/yy HH:mm", Locale.getDefault())

    suspend fun generatePdf(context: Context, config: ReportConfig) {
        Log.d(TAG, "Iniciando generatePdf. IDs recibidos: ${config.productIds.joinToString()}")
        try {
            val reportData = fetchReportData(config)
            Log.d(TAG, "fetchReportData completado. Se obtuvieron ${reportData.size} filas de datos.")

            if (reportData.isEmpty()){
                throw IllegalStateException("La consulta a Firestore con los IDs seleccionados no devolvió ningún producto. Verifica que los IDs sean correctos y los documentos existan.")
            }

            val file = createPdfFile(context, config, reportData)
            Log.d(TAG, "Archivo PDF creado en: ${file.absolutePath}")
            sharePdf(context, file)
        } catch(e: Exception) {
            Log.e(TAG, "¡ERROR! Fallo en la generación de PDF.", e)
            withContext(Dispatchers.Main) {
                showErrorDialog(context, e, config)
            }
        }
    }

    private suspend fun fetchReportData(config: ReportConfig): List<Map<ReportColumn, String>> {
        if (config.productIds.isEmpty()) {
            Log.w(TAG, "fetchReportData recibió una lista de IDs vacía.")
            return emptyList()
        }

        Log.d(TAG, "Haciendo query a Firestore por ${config.productIds.size} IDs...")
        val products = db.collection("products")
            .whereIn(FieldPath.documentId(), config.productIds.take(30))
            .get().await().toObjects<Product>()
        Log.d(TAG, "Query a Firestore devolvió ${products.size} productos.")

        if (products.isEmpty() && config.productIds.isNotEmpty()) {
            Log.e(TAG, "¡ALERTA! La consulta a Firestore no devolvió productos a pesar de recibir IDs. Revisa la colección 'products' y los IDs: [${config.productIds.joinToString()}]")
        }

        val consumptionData = if (config.columns.contains(ReportColumn.CONSUMO) && config.dateRange != null) {
            Log.d(TAG, "Calculando consumo...")
            CalculationUtils.getConsumptionForProducts(db, config.productIds, config.dateRange.first, config.dateRange.second)
        } else {
            emptyMap()
        }

        return products.sortedBy { it.name }.map { product ->
            val row = mutableMapOf<ReportColumn, String>()
            row[ReportColumn.PRODUCT_NAME] = product.name
            config.columns.forEach { column ->
                // --- CAMBIO AQUÍ: Se añade el caso para UNIT ---
                when (column) {
                    ReportColumn.STOCK_MATRIZ -> row[column] = "%.2f".format(product.stockMatriz)
                    ReportColumn.STOCK_C04 -> row[column] = "%.2f".format(product.stockCongelador04)
                    ReportColumn.STOCK_TOTAL -> row[column] = "%.2f".format(product.totalStock)
                    ReportColumn.CONSUMO -> row[column] = "%.2f".format(consumptionData[product.id] ?: 0.0)
                    ReportColumn.UNIT -> row[column] = product.unit // <-- AGREGADO
                    ReportColumn.ULTIMA_ACTUALIZACION -> row[column] = product.updatedAt?.let { dateTimeFormat.format(it) } ?: "N/A"
                    ReportColumn.PRODUCT_NAME -> { /* ya se agregó */ }
                }
            }
            row
        }
    }


    private suspend fun createPdfFile(context: Context, config: ReportConfig, data: List<Map<ReportColumn, String>>): File {
        return withContext(Dispatchers.IO) {
            val file = File(context.cacheDir, "reporte_inventario.pdf")
            val writer = PdfWriter(file)
            val pdfDocument = PdfDocument(writer)
            val document = Document(pdfDocument, PageSize.A4)
            document.setMargins(36f, 36f, 36f, 36f)

            // --- SECCIÓN DE PERSONALIZACIÓN DE COLORES ---
            // Si quieres otro color, solo cambia los números RGB (Rojo, Verde, Azul) de 0 a 255
            val productoHeaderBg: Color = DeviceRgb(3, 4, 94)      // Azul oscuro (#03045e)
            val productoHeaderFont: Color = DeviceRgb(255, 255, 255) // Blanco

            val totalHeaderBg: Color = DeviceRgb(3, 4, 94)          // Negro
            val totalHeaderFont: Color = DeviceRgb(255, 255, 255)  // Verde brillante

            val defaultHeaderBg: Color = DeviceGray(0.85f)         // Gris claro
            val defaultHeaderFont: Color = DeviceRgb(0, 0, 0)      // Negro

            // rosita tenue: val zebraColor: Color = DeviceRgb(255, 235, 240)
            val zebraColor: Color = DeviceRgb(230, 240, 255)

            // --- FIN DE SECCIÓN DE COLORES ---

            document.add(Paragraph(config.reportTitle).setTextAlignment(TextAlignment.CENTER).setBold().setFontSize(18f))
            document.add(Paragraph("Generado el: ${dateTimeFormat.format(Date())}").setTextAlignment(TextAlignment.CENTER).setFontSize(10f))
            if(config.dateRange != null && config.columns.contains(ReportColumn.CONSUMO)) {
                document.add(Paragraph("Período de Consumo: ${dateFormat.format(config.dateRange.first)} - ${dateFormat.format(config.dateRange.second)}").setTextAlignment(TextAlignment.CENTER).setFontSize(10f).setMarginBottom(20f))
            } else {
                document.add(Paragraph("").setMarginBottom(20f))
            }

            val columnOrder = listOf(
                ReportColumn.STOCK_MATRIZ, ReportColumn.STOCK_C04, ReportColumn.STOCK_TOTAL,
                ReportColumn.CONSUMO, ReportColumn.UNIT, ReportColumn.ULTIMA_ACTUALIZACION
            )
            val sortedColumns = config.columns.filter { it != ReportColumn.PRODUCT_NAME }.sortedBy { columnOrder.indexOf(it) }

            val columnWidths = mutableListOf<Float>()
            // Añadir siempre la columna de Producto primero
            columnWidths.add(4f)
            sortedColumns.forEach { column ->
                when (column) {
                    ReportColumn.UNIT -> columnWidths.add(1f)
                    ReportColumn.ULTIMA_ACTUALIZACION -> columnWidths.add(3f)
                    else -> columnWidths.add(2f)
                }
            }

            val table = Table(UnitValue.createPercentArray(columnWidths.toFloatArray())).useAllAvailableWidth()

            // --- LÓGICA DE DIBUJADO DE CABECERAS CON ESTILOS ---
            // 1. Cabecera "Producto" (estilo especial)
            table.addHeaderCell(
                Cell().add(Paragraph(ReportColumn.PRODUCT_NAME.title))
                    .setBackgroundColor(productoHeaderBg)
                    .setFontColor(productoHeaderFont)
                    .setBold()
            )

            // 2. Resto de las cabeceras
            sortedColumns.forEach { column ->
                val headerCell = Cell().add(Paragraph(column.title)).setBold()
                if (column == ReportColumn.STOCK_TOTAL) {
                    // Estilo especial para "Stock Total"
                    headerCell.setBackgroundColor(totalHeaderBg)
                    headerCell.setFontColor(totalHeaderFont)
                } else {
                    // Estilo por defecto para las demás
                    headerCell.setBackgroundColor(defaultHeaderBg)
                    headerCell.setFontColor(defaultHeaderFont)
                }
                table.addHeaderCell(headerCell)
            }

            // --- DIBUJADO DE FILAS CON CEBRA MEJORADA ---
            var isZebra = false
            data.forEach { rowData ->
                val bgColor = if (isZebra) zebraColor else null

                table.addCell(Cell().add(Paragraph(rowData[ReportColumn.PRODUCT_NAME] ?: "")).also { if(bgColor!=null) it.setBackgroundColor(bgColor) })

                sortedColumns.forEach { columnType ->
                    val cellText = rowData[columnType] ?: ""
                    val cell = Cell().add(Paragraph(cellText))
                    bgColor?.let { cell.setBackgroundColor(it) }

                    if (columnType == ReportColumn.UNIT) {
                        cell.setTextAlignment(TextAlignment.CENTER)
                    } else if (cellText.matches(Regex("-?\\d+(\\.\\d+)?"))) {
                        cell.setTextAlignment(TextAlignment.RIGHT)
                    }
                    table.addCell(cell)
                }
                isZebra = !isZebra
            }
            document.add(table)
            document.close()
            file
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
            context.startActivity(Intent.createChooser(intent, "Compartir Reporte PDF"))
        } catch (e: Exception) {
            Toast.makeText(context, "No se pudo compartir el archivo. ¿Tienes una app para ver PDFs?", Toast.LENGTH_LONG).show()
        }
    }

    private fun showErrorDialog(context: Context, e: Exception, config: ReportConfig) {
        val errorTrace = e.stackTraceToString()
        val configDetails = "IDs intentados (${config.productIds.size}): ${config.productIds.joinToString()}"
        val errorMessage = "Mensaje de Error:\n${e.localizedMessage}\n\nConfiguración del Reporte:\n$configDetails\n\nDetalles Técnicos:\n$errorTrace"

        AlertDialog.Builder(context)
            .setTitle("¡Error al Generar Reporte!")
            .setMessage("No se pudo generar el reporte. Esto puede deberse a un problema con los datos o la conexión.")
            .setPositiveButton("Cerrar") { dialog, _ ->
                dialog.dismiss()
            }
            .setNeutralButton("Copiar Detalles") { dialog, _ ->
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("Error Reporte Bocana", errorMessage)
                clipboard.setPrimaryClip(clip)
                Toast.makeText(context, "Detalles del error copiados.", Toast.LENGTH_LONG).show()
                dialog.dismiss()
            }
            .setCancelable(false)
            .show()
    }
}