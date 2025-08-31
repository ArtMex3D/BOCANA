package com.cesar.bocana.ui.printing

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Define las propiedades de una plantilla de etiquetas para una hoja A4.
 * Las dimensiones están en puntos (pt), donde 1 pulgada = 72 puntos.
 * Hoja A4: 595pt x 842pt.
 */
@Parcelize
data class LabelTemplate(
    val description: String,
    val totalLabels: Int,
    val columns: Int,
    val labelWidthPt: Float,
    val labelHeightPt: Float,
    // Márgenes y espaciado mínimos para aprovechar al máximo la hoja.
    val horizontalSpacingPt: Float = 1.4175f, // 0.5mm
    val verticalSpacingPt: Float = 1.4175f,   // 0.5mm
    val pageMarginPt: Float = 1.4175f       // 0.5mm
) : Parcelable {
    val rows: Int
        get() = (totalLabels + columns - 1) / columns
}

/**
 * Objeto que contiene las listas de plantillas predefinidas para la aplicación.
 * Esta es la única fuente de plantillas que usaremos.
 */
object LabelTemplates {

    // Plantillas para Etiquetas Simples (Proveedor y Fecha)
    val simpleTemplates = listOf(
        // NUEVA: 30 etiquetas por hoja, en 3 columnas
        LabelTemplate("30 por hoja (3x10)", 30, 3, 196.44f, 82.64f),
        // NUEVA: 24 etiquetas por hoja, en 3 columnas
        LabelTemplate("24 por hoja (3x8)", 24, 3, 196.44f, 103.46f),
        // MANTENIDA: 18 etiquetas por hoja, en 3 columnas
        LabelTemplate("18 por hoja (3x6)", 18, 3, 196.44f, 137.9f),
        // MANTENIDA: 14 etiquetas por hoja, en 2 columnas
        LabelTemplate("14 por hoja (2x7)", 14, 2, 295.37f, 118.9f)
    )

    // Plantillas para Etiquetas Detalladas (Producto, Proveedor, etc.)
    val detailedTemplates = listOf(
        // MANTENIDA: Tus medidas originales y correctas para 8 etiquetas
        LabelTemplate("8 por hoja (2x4)", 8, 2, 295.37f, 208.72f),
        // CORREGIDA: 6 etiquetas usando la misma lógica de maximizar espacio
        LabelTemplate("6 por hoja (2x3)", 6, 2, 295.37f, 278.8f)
    )
}
