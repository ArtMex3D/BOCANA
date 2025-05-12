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
    val supplierName: String? = null, // Este es el proveedor del lote actual
    @ServerTimestamp val receivedAt: Date? = null, // Fecha recepción en esta ubicación o traspaso
    val movementIdIn: String = "",

    val initialQuantity: Double = 0.0,
    var currentQuantity: Double = 0.0,

    val lotNumber: String? = null, // Número de lote del proveedor para este lote
    val expirationDate: Date? = null,

    @JvmField
    var isDepleted: Boolean = false,
    @JvmField
    var isPackaged: Boolean = false,

    // NUEVOS CAMPOS PARA RASTREAR EL LOTE PADRE ORIGINAL (si este lote fue creado por traspaso)
    val originalLotId: String? = null,         // ID del StockLot de Matriz del que provino originalmente
    @ServerTimestamp val originalReceivedAt: Date? = null, // La fecha receivedAt del lote padre original en Matriz
    val originalSupplierName: String? = null,  // El supplierName del lote padre original
    val originalLotNumber: String? = null      // El lotNumber del lote padre original (si lo tenía)

) {
    constructor() : this(
        id = "", productId = "", productName = "", unit = "", location = Location.MATRIZ,
        supplierId = null, supplierName = null, receivedAt = null, movementIdIn = "",
        initialQuantity = 0.0, currentQuantity = 0.0,
        lotNumber = null, expirationDate = null,
        isDepleted = false, isPackaged = false,
        originalLotId = null, originalReceivedAt = null, originalSupplierName = null, originalLotNumber = null // Añadir al constructor vacío
    )
}
