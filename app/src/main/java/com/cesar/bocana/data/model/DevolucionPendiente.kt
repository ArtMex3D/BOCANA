package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

// Enum para el estado
enum class DevolucionStatus {
    PENDIENTE, COMPLETADO
}

data class DevolucionPendiente(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val quantity: Long = 0L,
    val provider: String = "",
    val unit: String = "",
    val reason: String = "",
    @ServerTimestamp val registeredAt: Date? = null,
    val userId: String = "",
    // --- CAMPOS IMPORTANTES ---
    val status: DevolucionStatus = DevolucionStatus.PENDIENTE, // <-- CAMPO CLAVE
    @ServerTimestamp var completedAt: Date? = null
) {
    constructor() : this( status = DevolucionStatus.PENDIENTE, completedAt = null) // Asegurar valores por defecto
}