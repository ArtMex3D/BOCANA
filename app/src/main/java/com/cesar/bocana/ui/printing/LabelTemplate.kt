package com.cesar.bocana.ui.printing

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Define las plantillas de impresión de etiquetas en una hoja A4.
 * Las dimensiones están en puntos (pt), donde 1 pulgada = 72 puntos y 1 mm ≈ 2.835 puntos.
 * Hoja A4: 210mm x 297mm ≈ 595pt x 842pt.
 */
@Parcelize
data class LabelTemplate(
    val description: String,
    val totalLabels: Int,
    val columns: Int,
    val labelWidthPt: Float,
    val labelHeightPt: Float,
    val horizontalSpacingPt: Float = 5.67f, // 2mm
    val verticalSpacingPt: Float = 5.67f,   // 2mm
    val pageMarginPt: Float = 14.17f       // 5mm
) : Parcelable {
    val rows: Int
        get() = (totalLabels + columns - 1) / columns
}

// Objeto para centralizar la creación de plantillas disponibles
object LabelTemplates {
    // Plantillas para Etiquetas Simples
    val simpleTemplates = listOf(
        LabelTemplate("18 por hoja (3x6)", 18, 3, 184f, 132f), // Aprox. 65mm x 46.5mm
        LabelTemplate("14 por hoja (2x7)", 14, 2, 280f, 110f), // Aprox. 99mm x 39mm
        LabelTemplate("10 por hoja (2x5)", 10, 2, 280f, 158f)  // Aprox. 99mm x 56mm
    )

    // Plantillas para Etiquetas Detalladas (Fijas y Variables)
    val detailedTemplates = listOf(
        // Medidas exactas que proporcionaste: 105mm x 74.25mm
        LabelTemplate("8 por hoja (2x4)", 8, 2, 297.6f, 210.4f),
        LabelTemplate("6 por hoja (2x3)", 6, 2, 280f, 265f), // Aprox. 99mm x 93.5mm
        LabelTemplate("4 por hoja (2x2)", 4, 2, 280f, 400f)  // Aprox. 99mm x 141mm
    )
}