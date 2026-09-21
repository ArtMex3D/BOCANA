package com.cesar.bocana.ui.printing

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

enum class LabelPageFormat(val widthPt: Int, val heightPt: Int) {
    LETTER(612, 792),
    A4(595, 842)
}

/**
 * Plantilla geométrica. Las celdas se calculan desde el tamaño real de hoja,
 * por lo que ninguna fila puede salirse de la página.
 */
@Parcelize
data class LabelTemplate(
    val description: String,
    val totalLabels: Int,
    val columns: Int,
    val pageFormat: LabelPageFormat = LabelPageFormat.LETTER,
    val horizontalSpacingPt: Float = 3f,
    val verticalSpacingPt: Float = 3f,
    val pageMarginPt: Float = 9f
) : Parcelable {
    val rows: Int get() = (totalLabels + columns - 1) / columns
    val pageWidthPt: Int get() = pageFormat.widthPt
    val pageHeightPt: Int get() = pageFormat.heightPt
    val labelWidthPt: Float
        get() = (pageWidthPt - (pageMarginPt * 2f) - ((columns - 1) * horizontalSpacingPt)) / columns
    val labelHeightPt: Float
        get() = (pageHeightPt - (pageMarginPt * 2f) - ((rows - 1) * verticalSpacingPt)) / rows
}

object LabelTemplates {
    // Hoja Carta. Costales reutiliza estas densidades.
    val simpleTemplates = listOf(
        LabelTemplate("30 por hoja (3x10)", 30, 3),
        LabelTemplate("24 por hoja (3x8)", 24, 3),
        LabelTemplate("18 por hoja (3x6)", 18, 3),
        LabelTemplate("14 por hoja (2x7)", 14, 2)
    )

    val costalTemplates: List<LabelTemplate> = simpleTemplates

    val detailedTemplates = listOf(
        LabelTemplate("8 por hoja (2x4)", 8, 2),
        LabelTemplate("6 por hoja (2x3)", 6, 2)
    )

    fun findByDescription(description: String?): LabelTemplate? {
        if (description.isNullOrBlank()) return null
        return (simpleTemplates + detailedTemplates).firstOrNull { it.description == description }
    }
}
