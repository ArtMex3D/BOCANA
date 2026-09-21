package com.cesar.bocana.ui.printing

data class ActiveLabelRecord(
    val id: String,
    val createdAtMillis: Long,
    val expiresAtMillis: Long,
    val title: String,
    val typeLabel: String,
    val summary: String,
    val pdfPath: String,
    val flowType: LabelFlowType,
    val templateDescription: String,
    val payloadJson: String
)
