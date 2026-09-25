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
    val payloadJson: String,
    // false mantiene compatibilidad con etiquetas locales creadas antes de esta mejora.
    val remoteManaged: Boolean = false,
    // true permite reintentar la subida si el teléfono generó la etiqueta sin conexión.
    val syncPending: Boolean = false
)
