package com.cesar.bocana.data.model

import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import java.util.Date

/**
 * Representa la configuración para una única etiqueta en la hoja.
 * Es Parcelable para poder pasarla entre fragments y dialogs.
 */
@Parcelize
data class IndividualLabelConfig(
    val product: Product,
    val supplierName: String,
    val date: Date,
    val weight: String?, // "Manual" o un valor numérico como "25.50"
    val unit: String,
    val detail: String?
) : Parcelable