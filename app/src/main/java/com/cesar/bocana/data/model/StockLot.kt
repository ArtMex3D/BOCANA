package com.cesar.bocana.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import java.util.Date
import kotlin.jvm.JvmField
import androidx.room.Index

@Entity(
    tableName = "stock_lots",
    indices = [
        Index(value = ["productId"], unique = false),
        Index(value = ["supplierId"], unique = false)
    ]
)
data class StockLot(
    @PrimaryKey
    @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val unit: String = "",

    val location: String = Location.MATRIZ,

    val supplierId: String? = null,
    val supplierName: String? = null,
    @ServerTimestamp val receivedAt: Date? = null,
    val movementIdIn: String = "",

    val initialQuantity: Double = 0.0, // Siempre en la unidad base (Kg)
    var currentQuantity: Double = 0.0, // Siempre en la unidad base (Kg)

    // --- NUEVOS CAMPOS PARA TRAZABILIDAD DE EMPAQUE ---
    val unidadDeEmpaque: String? = null,      // "Caja", "Bolsa", "Costal", etc.
    val pesoPorUnidad: Double? = null,       // Ej: 4.54 (el peso de UNA caja de este lote)
    val cantidadInicialUnidades: Double? = null, // Ej: 20.5 (el número de cajas que ingresaron) puede ser decimal

    val lotNumber: String? = null,
    val expirationDate: Date? = null,

    @JvmField
    var isDepleted: Boolean = false,
    @JvmField
    var isPackaged: Boolean = false,

    val originalLotId: String? = null,
    @ServerTimestamp val originalReceivedAt: Date? = null,
    val originalSupplierId: String? = null,
    val originalSupplierName: String? = null,
    val originalLotNumber: String? = null

) {
    constructor() : this(
        id = "", productId = "", productName = "", unit = "", location = Location.MATRIZ,
        supplierId = null, supplierName = null, receivedAt = null, movementIdIn = "",
        initialQuantity = 0.0, currentQuantity = 0.0,
        // Valores por defecto para nuevos campos
        unidadDeEmpaque = null, pesoPorUnidad = null, cantidadInicialUnidades = null,
        lotNumber = null, expirationDate = null,
        isDepleted = false, isPackaged = false,
        originalLotId = null, originalReceivedAt = null,
        originalSupplierId = null,
        originalSupplierName = null, originalLotNumber = null
    )
}