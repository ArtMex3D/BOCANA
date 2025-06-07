package com.cesar.bocana.data.model

import android.os.Parcelable
import com.cesar.bocana.ui.printing.LabelType
import kotlinx.parcelize.Parcelize
import java.util.Date

enum class QrCodeOption {
    NONE,
    STOCK_WEB,
    MOVEMENTS_APP,
    BOTH
}

@Parcelize
data class LabelData(
    val labelType: LabelType,
    val qrCodeOption: QrCodeOption, // CAMPO AÑADIDO
    val productId: String? = null,
    val productName: String? = null,
    val supplierName: String? = null,
    val date: Date,
    val weight: String? = null,
    val unit: String? = null
) : Parcelable
