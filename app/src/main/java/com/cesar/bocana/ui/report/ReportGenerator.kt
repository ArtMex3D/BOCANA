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
                when (column) {
                    ReportColumn.STOCK_MATRIZ -> row[column] = "%.2f".format(product.stockMatriz)
                    ReportColumn.STOCK_C04 -> row[column] = "%.2f".format(product.stockCongelador04)
                    ReportColumn.STOCK_TOTAL -> row[column] = "%.2f".format(product.totalStock)
                    ReportColumn.CONSUMO -> row[column] = "%.2f".format(consumptionData[product.id] ?: 0.0)
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

            document.add(Paragraph(config.reportTitle).setTextAlignment(TextAlignment.CENTER).setBold().setFontSize(18f))
            document.add(Paragraph("Generado el: ${dateTimeFormat.format(Date())}").setTextAlignment(TextAlignment.CENTER).setFontSize(10f))
            if(config.dateRange != null && config.columns.contains(ReportColumn.CONSUMO)) {
                document.add(Paragraph("Período de Consumo: ${dateFormat.format(config.dateRange.first)} - ${dateFormat.format(config.dateRange.second)}").setTextAlignment(TextAlignment.CENTER).setFontSize(10f).setMarginBottom(20f))
            } else {
                document.add(Paragraph("").setMarginBottom(20f))
            }

            val columnsInOrder = mutableListOf(ReportColumn.PRODUCT_NAME).apply { addAll(config.columns) }
            val table = Table(UnitValue.createPercentArray(columnsInOrder.size)).useAllAvailableWidth()

            val headerColor = DeviceGray(0.75f)
            columnsInOrder.forEach { columnType ->
                table.addHeaderCell(Cell().add(Paragraph(columnType.title)).setBackgroundColor(headerColor).setBold())
            }

            var isZebra = false
            val zebraColor = DeviceGray(0.95f)
            data.forEach { rowData ->
                val bgColor = if (isZebra) zebraColor else null
                columnsInOrder.forEach { columnType ->
                    val cell = Cell().add(Paragraph(rowData[columnType] ?: ""))
                    bgColor?.let { cell.setBackgroundColor(it) }
                    if (columnType != ReportColumn.PRODUCT_NAME) {
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