package com.cesar.bocana.data.model

import android.os.Parcelable
import androidx.room.Entity
import androidx.room.PrimaryKey
import androidx.room.TypeConverters
import com.cesar.bocana.data.local.Converters
import com.google.firebase.firestore.DocumentId
import com.google.firebase.firestore.ServerTimestamp
import kotlinx.parcelize.Parcelize
import java.util.Date
import androidx.room.Index

@Parcelize
@Entity(
    tableName = "stock_lots",
    indices = [
        Index(value = ["productId"]),
        Index(value = ["supplierId"])
    ]
)
@TypeConverters(Converters::class)
data class StockLot(
    @PrimaryKey @DocumentId val id: String = "",
    val productId: String = "",
    val productName: String = "",
    val unit: String = "Kg",
    val location: String = Location.MATRIZ,
    val supplierId: String? = null,
    val supplierName: String? = null,
    @ServerTimestamp val receivedAt: Date? = null,
    val movementIdIn: String? = null,
    val initialQuantity: Double = 0.0,
    val currentQuantity: Double = 0.0,
    val lotNumber: String? = null,
    val expirationDate: Date? = null,
    @JvmField var isDepleted: Boolean = false,
    @JvmField var isPackaged: Boolean = true,
    val unidadDeEmpaque: String? = null,
    val pesoPorUnidad: Double? = null,
    val cantidadInicialUnidades: Double? = null,
    val originalLotId: String? = null,
    @ServerTimestamp val originalReceivedAt: Date? = null,
    val originalSupplierId: String? = null,
    val originalSupplierName: String? = null,
    val originalLotNumber: String? = null,
    var estadoTraspaso: String? = null
) : Parcelable {
    constructor() : this(
        id = "",
        productId = "",
        productName = "",
        unit = "Kg",
        location = Location.MATRIZ,
        supplierId = null,
        supplierName = null,
        receivedAt = null,
        movementIdIn = null,
        initialQuantity = 0.0,
        currentQuantity = 0.0,
        lotNumber = null,
        expirationDate = null,
        isDepleted = false,
        isPackaged = true,
        unidadDeEmpaque = null,
        pesoPorUnidad = null,
        cantidadInicialUnidades = null,
        originalLotId = null,
        originalReceivedAt = null,
        originalSupplierId = null,
        originalSupplierName = null,
        originalLotNumber = null,
        estadoTraspaso = null
    )
}
