package com.cesar.bocana.ui.printing

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class LabelTemplate(
    val description: String,
    val totalLabels: Int,
    val columns: Int,
    // Dimensiones en puntos (1/72 de pulgada) para una hoja A4 (595pt x 842pt)
    val labelWidthPt: Float,
    val labelHeightPt: Float,
    val horizontalSpacingPt: Float = 5.67f, // 2mm
    val verticalSpacingPt: Float = 3.67f,   // 2mm
    val pageMarginPt: Float = 10.17f       // 5mm
) : Parcelable {
    val rows: Int
        get() = (totalLabels + columns - 1) / columns
}