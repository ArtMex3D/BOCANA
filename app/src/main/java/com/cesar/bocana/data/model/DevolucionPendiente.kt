package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.cesar.bocana.data.local.Converters
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

enum class DevolucionStatus {
    PENDIENTE, COMPLETADO
}

@Entity(tableName = "pending_devoluciones")
@TypeConverters(Converters::class)
data class DevolucionPendiente(
    @PrimaryKey
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