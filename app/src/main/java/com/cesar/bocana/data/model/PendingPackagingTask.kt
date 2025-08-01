package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

@Entity(tableName = "pending_packaging")
data class PendingPackagingTask(
    @PrimaryKey
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val quantityReceived: Double = 0.0,
    val unit: String = "",
    @ServerTimestamp val receivedAt: Date? = null,
    val purchaseMovementId: String? = null,
    val supplierId: String? = null,
    val supplierName: String? = null
){
    constructor() : this(
        id = "",
        productId = "",
        productName = "",
        quantityReceived = 0.0,
        unit = "",
        receivedAt = null,
        purchaseMovementId = null,
        supplierId = null,
        supplierName = null
    )
}