package com.cesar.bocana.ui.traspasos.plan

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DesgloseManualResult(
    val loteId: String,
    val cantidad: Double
) : Parcelable