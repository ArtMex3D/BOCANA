package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class DevolucionStatus {
    PENDIENTE, COMPLETADO
}

data class DevolucionPendiente(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val quantity: Double = 0.0,
    val provider: String = "",
    val unit: String = "",
    val reason: String = "",
    @ServerTimestamp val registeredAt: Date? = null,
    val userId: String = "",
    val status: DevolucionStatus = DevolucionStatus.PENDIENTE,
    @ServerTimestamp var completedAt: Date? = null
) {
    constructor() : this(id="", productId="", productName="", quantity=0.0, provider="", unit="", reason="", registeredAt=null, userId="", status = DevolucionStatus.PENDIENTE, completedAt = null)
}