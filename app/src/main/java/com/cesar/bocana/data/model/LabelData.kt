package com.cesar.bocana.data.model

import android.os.Parcelable
import com.cesar.bocana.ui.printing.LabelType
import kotlinx.parcelize.Parcelize
import java.util.Date

@Parcelize
data class LabelData(
    val labelType: LabelType,
    val productId: String? = null, // CAMPO AÑADIDO
    val productName: String? = null,
    val supplierName: String? = null,
    val date: Date,
    val weight: String? = null,
    val unit: String? = null
) : Parcelable
