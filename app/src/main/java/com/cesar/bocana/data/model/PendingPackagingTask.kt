package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date

data class PendingPackagingTask(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val quantityReceived: Long = 0L,
    val unit: String = "",
    @ServerTimestamp val receivedAt: Date? = null,
    val purchaseMovementId: String? = null
)