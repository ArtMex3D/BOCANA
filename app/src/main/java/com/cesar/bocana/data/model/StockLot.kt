package com.cesar.bocana.data.model

import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField

data class StockLot(
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val unit: String = "",

    val location: String = Location.MATRIZ,

    val supplierId: String? = null,
    val supplierName: String? = null,
    @ServerTimestamp val receivedAt: Date? = null,
    val movementIdIn: String = "",

    val initialQuantity: Double = 0.0,
    var currentQuantity: Double = 0.0,

    val lotNumber: String? = null,
    val expirationDate: Date? = null,

    @JvmField
    var isDepleted: Boolean = false,
    @JvmField
    var isPackaged: Boolean = false,

    val originalLotId: String? = null,
    @ServerTimestamp val originalReceivedAt: Date? = null,
    val originalSupplierId: String? = null, // CAMPO AÑADIDO
    val originalSupplierName: String? = null,
    val originalLotNumber: String? = null

) {
    constructor() : this(
        id = "", productId = "", productName = "", unit = "", location = Location.MATRIZ,
        supplierId = null, supplierName = null, receivedAt = null, movementIdIn = "",
        initialQuantity = 0.0, currentQuantity = 0.0,
        lotNumber = null, expirationDate = null,
        isDepleted = false, isPackaged = false,
        originalLotId = null, originalReceivedAt = null,
        originalSupplierId = null, // AÑADIDO AL CONSTRUCTOR VACÍO
        originalSupplierName = null, originalLotNumber = null
    )
}